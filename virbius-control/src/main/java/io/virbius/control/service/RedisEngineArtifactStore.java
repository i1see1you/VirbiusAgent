package io.virbius.control.service;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.gateway.artifact.GatewayArtifactHash;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

@Component
public class RedisEngineArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(RedisEngineArtifactStore.class);
    private static final int RETAIN_REVISIONS = 10;

    private final JedisPool pool;

    public RedisEngineArtifactStore(ControlJedisPools jedisPools) {
        this.pool = jedisPools.pool().orElseThrow(
                () -> new IllegalStateException("Redis required for engine artifact store"));
    }

    public long nextRevision(String tenantId) {
        try (Jedis jed = pool.getResource()) {
            return jed.incr(EngineArtifactKeys.seqKey(tenantId));
        }
    }

    public String putSnapshot(String tenantId, long revision, byte[] snapshotJson) {
        byte[] staging = EngineArtifactKeys.blobStagingKey(tenantId, revision).getBytes(StandardCharsets.UTF_8);
        byte[] live = EngineArtifactKeys.blobKey(tenantId, revision).getBytes(StandardCharsets.UTF_8);
        String sha256 = GatewayArtifactHash.sha256Hex(snapshotJson);
        try (Jedis jed = pool.getResource()) {
            jed.set(staging, snapshotJson);
            jed.del(live);
            jed.rename(staging, live);
        }
        return sha256;
    }

    public void updatePointer(String tenantId, EngineArtifactPointer pointer) {
        try (Jedis jed = pool.getResource()) {
            jed.hset(EngineArtifactKeys.pointerKey(tenantId), pointer.toRedisHash());
        }
    }

    public Optional<EngineArtifactPointer> getPointer(String tenantId) {
        try (Jedis jed = pool.getResource()) {
            Map<String, String> hash = jed.hgetAll(EngineArtifactKeys.pointerKey(tenantId));
            return Optional.ofNullable(EngineArtifactPointer.fromRedisHash(tenantId, hash));
        }
    }

    public Optional<byte[]> getSnapshot(String tenantId, long revision) {
        try (Jedis jed = pool.getResource()) {
            byte[] key = EngineArtifactKeys.blobKey(tenantId, revision).getBytes(StandardCharsets.UTF_8);
            byte[] blob = jed.get(key);
            return Optional.ofNullable(blob);
        }
    }

    public void notifyReload(String tenantId, long revision) {
        try (Jedis jed = pool.getResource()) {
            jed.xadd(EngineArtifactKeys.streamKey(), StreamEntryID.NEW_ENTRY,
                    Map.of("tenant_id", tenantId, "artifact_revision", String.valueOf(revision)));
        } catch (Exception ex) {
            log.warn("engine stream notify failed: {}", ex.getMessage());
        }
    }

    public void retainHistory(String tenantId) {
        try (Jedis jed = pool.getResource()) {
            String historyKey = EngineArtifactKeys.historyKey(tenantId);
            long now = Instant.now().getEpochSecond();
            jed.zadd(historyKey, now, String.valueOf(revisionFromPointer(tenantId)));
            long count = jed.zcard(historyKey);
            if (count <= RETAIN_REVISIONS) return;
            List<String> old = new ArrayList<>(jed.zrange(historyKey, 0, count - RETAIN_REVISIONS - 1));
            for (String revStr : old) {
                jed.del(EngineArtifactKeys.blobKey(tenantId, Long.parseLong(revStr)));
            }
            if (!old.isEmpty()) {
                jed.zrem(historyKey, old.toArray(String[]::new));
            }
        }
    }

    private long revisionFromPointer(String tenantId) {
        try (Jedis jed = pool.getResource()) {
            String rev = jed.hget(EngineArtifactKeys.pointerKey(tenantId), "artifact_revision");
            return rev != null ? Long.parseLong(rev) : 0L;
        }
    }
}
