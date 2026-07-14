package io.virbius.control.audit;

import io.virbius.control.config.ControlJedisPools;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Component
public class HashChainOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(HashChainOrchestrator.class);
    private static final String GENESIS_HASH = "sha256:" + "0".repeat(64);
    private static final String CHAIN_KEY_PREFIX = "virbius:audit:chain:";

    private static final String LUA_CAS_UPDATE =
            "local cur = redis.call('HGET', KEYS[1], 'seq') or '0' " +
            "if tonumber(cur) ~= tonumber(ARGV[1]) then return -1 end " +
            "redis.call('HSET', KEYS[1], 'seq', ARGV[2], 'last_hash', ARGV[3], 'updated_at', ARGV[4]) " +
            "return 1";

    private final Optional<JedisPool> jedisPool;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public HashChainOrchestrator(
            ControlJedisPools jedisPools,
            JdbcTemplate jdbc,
            @Value("${virbius.audit.hash-chain.enabled:true}") boolean enabled) {
        this.jedisPool = jedisPools.pool();
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public Map<String, Object> chain(String tenantId, Map<String, Object> event) {
        chainBatch(tenantId, List.of(event));
        return event;
    }

    public void chainBatch(String tenantId, List<Map<String, Object>> events) {
        if (!enabled || events.isEmpty()) {
            return;
        }
        try {
            if (jedisPool.isPresent()) {
                chainBatchRedis(tenantId, events);
            } else {
                chainBatchMysql(tenantId, events);
            }
        } catch (Exception e) {
            log.warn("hash chain failed for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    private void chainBatchRedis(String tenantId, List<Map<String, Object>> events) {
        String key = CHAIN_KEY_PREFIX + tenantId;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Jedis jedis = jedisPool.get().getResource()) {
                long seq = parseSeq(jedis.hget(key, "seq"));
                String prevHash = jedis.hget(key, "last_hash");
                if (prevHash == null || prevHash.isEmpty()) {
                    prevHash = GENESIS_HASH;
                }

                for (Map<String, Object> event : events) {
                    seq++;
                    String currHash = computeHash(prevHash, seq, event);
                    event.put("audit_seq", seq);
                    event.put("prev_hash", prevHash);
                    event.put("curr_hash", currHash);
                    prevHash = currHash;
                }

                Object result = jedis.eval(
                        LUA_CAS_UPDATE,
                        java.util.List.of(key),
                        java.util.List.of(
                                String.valueOf(seq - events.size()),
                                String.valueOf(seq),
                                prevHash,
                                Instant.now().toString()));

                if (result != null && ((Long) result) == 1L) {
                    return;
                }
                log.debug("CAS retry {} for tenant {}", attempt + 1, tenantId);
            } catch (Exception e) {
                log.warn("redis chain attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }
        log.warn("redis CAS exhausted for tenant {}, falling back to mysql", tenantId);
        chainBatchMysql(tenantId, events);
    }

    private void chainBatchMysql(String tenantId, List<Map<String, Object>> events) {
        jdbc.queryForList(
                "SELECT seq, last_hash, version FROM tb_audit_chain_state WHERE tenant_id = ? FOR UPDATE",
                tenantId);

        var rows = jdbc.queryForList(
                "SELECT seq, last_hash, version FROM tb_audit_chain_state WHERE tenant_id = ?",
                tenantId);

        long seq;
        String prevHash;
        int version;

        if (rows.isEmpty()) {
            seq = 0;
            prevHash = GENESIS_HASH;
            version = 0;
            jdbc.update("INSERT INTO tb_audit_chain_state (tenant_id, seq, last_hash, version) VALUES (?, 0, ?, 0)",
                    tenantId, GENESIS_HASH);
        } else {
            Map<String, Object> row = rows.get(0);
            seq = ((Number) row.get("seq")).longValue();
            prevHash = (String) row.get("last_hash");
            if (prevHash == null || prevHash.isEmpty()) {
                prevHash = GENESIS_HASH;
            }
            version = ((Number) row.get("version")).intValue();
        }

        for (Map<String, Object> event : events) {
            seq++;
            String currHash = computeHash(prevHash, seq, event);
            event.put("audit_seq", seq);
            event.put("prev_hash", prevHash);
            event.put("curr_hash", currHash);
            prevHash = currHash;
        }

        int updated = jdbc.update(
                "UPDATE tb_audit_chain_state SET seq = ?, last_hash = ?, version = version + 1, updated_at = ? " +
                "WHERE tenant_id = ? AND version = ?",
                seq, prevHash, Instant.now().toString(), tenantId, version);

        if (updated == 0) {
            log.warn("mysql CAS failed for tenant {}, retrying", tenantId);
            chainBatchMysql(tenantId, events);
        }
    }

    public ChainHead getChainHead(String tenantId) {
        if (jedisPool.isPresent()) {
            try (Jedis jedis = jedisPool.get().getResource()) {
                String key = CHAIN_KEY_PREFIX + tenantId;
                String seqStr = jedis.hget(key, "seq");
                String lastHash = jedis.hget(key, "last_hash");
                String updatedAt = jedis.hget(key, "updated_at");
                return new ChainHead(
                        parseSeq(seqStr),
                        lastHash != null && !lastHash.isEmpty() ? lastHash : GENESIS_HASH,
                        updatedAt != null ? Instant.parse(updatedAt) : null);
            } catch (Exception e) {
                log.warn("failed to get chain head from redis: {}", e.getMessage());
            }
        }
        var rows = jdbc.queryForList(
                "SELECT seq, last_hash, updated_at FROM tb_audit_chain_state WHERE tenant_id = ?",
                tenantId);
        if (rows.isEmpty()) {
            return new ChainHead(0, GENESIS_HASH, null);
        }
        Map<String, Object> row = rows.get(0);
        return new ChainHead(
                ((Number) row.get("seq")).longValue(),
                (String) row.getOrDefault("last_hash", GENESIS_HASH),
                null);
    }

    static String computeHash(String prevHash, long seq, Map<String, Object> event) {
        String input = prevHash
                + "|" + seq
                + "|" + str(event.get("tenant_id"))
                + "|" + str(event.get("trace_id"))
                + "|" + str(event.get("event_id"))
                + "|" + str(event.get("effective_action"))
                + "|" + str(event.get("layer"))
                + "|" + str(event.get("reason_code"))
                + "|" + str(event.get("rule_id"))
                + "|" + str(event.get("scene"))
                + "|" + str(event.get("user_id"))
                + "|" + str(event.get("device_id"))
                + "|" + str(event.get("intercepted_at"));
        return "sha256:" + sha256Hex(input);
    }

    private static long parseSeq(String seqStr) {
        if (seqStr == null || seqStr.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(seqStr);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record ChainHead(long seq, String lastHash, Instant updatedAt) {}
}
