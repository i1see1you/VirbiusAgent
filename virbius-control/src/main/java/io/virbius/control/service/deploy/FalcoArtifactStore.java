package io.virbius.control.service.deploy;

import io.virbius.control.config.ControlJedisPools;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * Stores falco rule YAML snapshots in Redis and broadcasts change notifications.
 *
 * <p>Kernel nodes resolve their own target revision (stable vs canary) from
 * {@code virbius:falco:pointer:{tenant}} + {@code virbius:deploy:active:{tenant}} and fetch the
 * artifact themselves; the Pub/Sub notification is only a "re-resolve now" hint, so the payload
 * stays small and pool-agnostic.
 */
@Component
public class FalcoArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FalcoArtifactStore.class);

    /** Pub/Sub channel for falco content changes (replaces the old per-target streams). */
    public static final String RULE_CHANGED_CHANNEL = "virbius:falco:rule-changed";

    private static final String KEY_PREFIX = "virbius:falco:artifact:";
    private static final String POINTER_KEY = "virbius:falco:pointer";
    /** How many recent revisions to keep after a promote; older snapshots are pruned. */
    private static final int RETAIN_REVISIONS = 10;

    private final Optional<JedisPool> jedisPool;

    public FalcoArtifactStore(ControlJedisPools jedisPools) {
        this.jedisPool = jedisPools.pool();
    }

    public boolean isAvailable() {
        return jedisPool.isPresent();
    }

    private JedisPool requirePool() {
        return jedisPool.orElseThrow(
                () -> new IllegalStateException("falco artifact store unavailable (Redis not configured)"));
    }

    public long nextRevision(String tenantId) {
        try (Jedis jedis = requirePool().getResource()) {
            return jedis.incr(KEY_PREFIX + tenantId + ":seq");
        }
    }

    /**
     * Stores a snapshot with no TTL: an active gray may outlive any fixed expiry, and revisions
     * referenced by the falco/deploy pointers must always be fetchable. Old revisions are
     * reclaimed by {@link #pruneSnapshots} at promote time.
     */
    public void putSnapshot(String tenantId, long revision, String rulesYaml) {
        try (Jedis jedis = requirePool().getResource()) {
            String key = artifactKey(tenantId, revision);
            jedis.set(key.getBytes(StandardCharsets.UTF_8), rulesYaml.getBytes(StandardCharsets.UTF_8));
            // Track write order for pruning; skip if this revision is already the newest entry
            // (promote re-PUTs the same revision to guarantee the key exists).
            String revsKey = revsKey(tenantId);
            String newest = jedis.lindex(revsKey, -1);
            if (!String.valueOf(revision).equals(newest)) {
                jedis.rpush(revsKey, String.valueOf(revision));
            }
            log.info("falco artifact stored tenant={} revision={}", tenantId, revision);
        }
    }

    public Optional<String> getSnapshot(String tenantId, long revision) {
        try (Jedis jedis = requirePool().getResource()) {
            byte[] data = jedis.get(artifactKey(tenantId, revision).getBytes(StandardCharsets.UTF_8));
            if (data == null) return Optional.empty();
            return Optional.of(new String(data, StandardCharsets.UTF_8));
        }
    }

    public void updatePointer(String tenantId, long stableRevision, long canaryRevision) {
        try (Jedis jedis = requirePool().getResource()) {
            jedis.hset(POINTER_KEY + ":" + tenantId, Map.of(
                    "stable_revision", String.valueOf(stableRevision),
                    "canary_revision", String.valueOf(canaryRevision)));
        }
    }

    /** Current stable revision recorded on the falco pointer; 0 when none has been promoted yet. */
    public long getStableRevision(String tenantId) {
        if (jedisPool.isEmpty()) return 0L;
        try (Jedis jedis = jedisPool.get().getResource()) {
            String raw = jedis.hget(POINTER_KEY + ":" + tenantId, "stable_revision");
            if (raw == null || raw.isBlank()) return 0L;
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException nfe) {
                return 0L;
            }
        }
    }

    /**
     * Broadcasts a pool-agnostic "falco rules changed" hint. Kernel nodes re-resolve their own
     * pool from the pointers, so the message only carries context fields.
     */
    public void publishRuleUpdate(String tenantId, long revision, String reason) {
        try (Jedis jedis = requirePool().getResource()) {
            String payload = "{\"tenant_id\":\"" + escape(tenantId)
                    + "\",\"revision\":" + revision
                    + ",\"reason\":\"" + escape(reason == null ? "" : reason) + "\"}";
            jedis.publish(RULE_CHANGED_CHANNEL, payload);
            log.info("falco rule change broadcast tenant={} revision={} reason={}", tenantId, revision, reason);
        }
    }

    /**
     * Deletes snapshots older than {@code keepFromRevision} (exclusive lower bound is revision
     * order, not time). Never touches revisions {@code >= keepFromRevision}; callers pass a value
     * that keeps the newly promoted stable revision.
     */
    public void pruneSnapshots(String tenantId, long keepFromRevision) {
        if (jedisPool.isEmpty()) return;
        try (Jedis jedis = jedisPool.get().getResource()) {
            String revsKey = revsKey(tenantId);
            while (true) {
                String head = jedis.lindex(revsKey, 0);
                if (head == null) return;
                long rev;
                try {
                    rev = Long.parseLong(head.trim());
                } catch (NumberFormatException nfe) {
                    jedis.lpop(revsKey); // corrupt entry, drop it
                    continue;
                }
                if (rev >= keepFromRevision) return;
                jedis.lpop(revsKey);
                jedis.del(artifactKey(tenantId, rev));
                log.info("falco artifact pruned tenant={} revision={}", tenantId, rev);
            }
        }
    }

    /** Retention helper used by promote: keep the latest {@link #RETAIN_REVISIONS} revisions. */
    public long retentionFloor(long promotedRevision) {
        return Math.max(1L, promotedRevision - RETAIN_REVISIONS + 1);
    }

    static String artifactKey(String tenantId, long revision) {
        return KEY_PREFIX + tenantId + ":" + revision;
    }

    private static String revsKey(String tenantId) {
        return KEY_PREFIX + tenantId + ":revs";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
