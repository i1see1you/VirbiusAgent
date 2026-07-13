package io.virbius.control.service.deploy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

@Component
public class FalcoArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FalcoArtifactStore.class);
    private static final String KEY_PREFIX = "virbius:falco:artifact:";
    private static final String POINTER_KEY = "virbius:falco:pointer";

    private final Optional<JedisPool> jedisPool;

    @Autowired
    public FalcoArtifactStore(Optional<JedisPool> jedisPool) {
        this.jedisPool = jedisPool;
    }

    public long nextRevision(String tenantId) {
        if (jedisPool.isEmpty()) return System.currentTimeMillis();
        try (Jedis jedis = jedisPool.get().getResource()) {
            String key = KEY_PREFIX + tenantId + ":seq";
            return jedis.incr(key);
        }
    }

    public void putSnapshot(String tenantId, long revision, String rulesYaml) {
        if (jedisPool.isEmpty()) {
            log.warn("Redis not available - falco artifact not stored");
            return;
        }
        try (Jedis jedis = jedisPool.get().getResource()) {
            String key = KEY_PREFIX + tenantId + ":" + revision;
            jedis.set(key.getBytes(StandardCharsets.UTF_8), rulesYaml.getBytes(StandardCharsets.UTF_8));
            jedis.expire(key, 86400);
            log.info("falco artifact stored tenant={} revision={}", tenantId, revision);
        }
    }

    public Optional<String> getSnapshot(String tenantId, long revision) {
        if (jedisPool.isEmpty()) return Optional.empty();
        try (Jedis jedis = jedisPool.get().getResource()) {
            String key = KEY_PREFIX + tenantId + ":" + revision;
            byte[] data = jedis.get(key.getBytes(StandardCharsets.UTF_8));
            if (data == null) return Optional.empty();
            return Optional.of(new String(data, StandardCharsets.UTF_8));
        }
    }

    public void updatePointer(String tenantId, long stableRevision, long canaryRevision) {
        if (jedisPool.isEmpty()) return;
        try (Jedis jedis = jedisPool.get().getResource()) {
            String key = POINTER_KEY + ":" + tenantId;
            jedis.hset(key, Map.of(
                    "stable_revision", String.valueOf(stableRevision),
                    "canary_revision", String.valueOf(canaryRevision)));
        }
    }

    public void publishRuleUpdate(String tenantId, long revision, String target) {
        if (jedisPool.isEmpty()) return;
        try (Jedis jedis = jedisPool.get().getResource()) {
            String stream = "virbius:falco:rule-update:" + target;
            Map<String, String> fields = Map.of(
                    "tenant_id", tenantId,
                    "revision", String.valueOf(revision),
                    "target", target,
                    "published_at", java.time.Instant.now().toString());
            jedis.xadd(stream, StreamEntryID.NEW_ENTRY, fields);
            log.info("falco rule update published tenant={} revision={} target={}", tenantId, revision, target);
        }
    }
}
