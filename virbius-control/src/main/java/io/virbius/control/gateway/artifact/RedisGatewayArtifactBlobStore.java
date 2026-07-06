package io.virbius.control.gateway.artifact;

import io.virbius.control.config.ControlJedisPools;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

@Component
public class RedisGatewayArtifactBlobStore implements GatewayArtifactBlobStore {

    private static final Logger log = LoggerFactory.getLogger(RedisGatewayArtifactBlobStore.class);

    private final Optional<JedisPool> pool;
    private final GatewayArtifactProperties properties;

    public RedisGatewayArtifactBlobStore(ControlJedisPools jedisPools, GatewayArtifactProperties properties) {
        this.pool = jedisPools.pool();
        this.properties = properties;
    }

    @Override
    public String storageType() {
        return "redis";
    }

    @Override
    public void putBlob(String tenantId, long revision, GatewayArtifactPart part, byte[] body) {
        JedisPool jedisPool = requirePool();
        String staging = GatewayArtifactKeys.blobStagingKey(
                properties.getRedis().getBlobPrefix(), tenantId, revision, part);
        String live = GatewayArtifactKeys.blobKey(
                properties.getRedis().getBlobPrefix(), tenantId, revision, part);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(staging.getBytes(), body);
            if (jedis.exists(live)) {
                jedis.del(live);
            }
            jedis.rename(staging, live);
        } catch (Exception ex) {
            log.warn("redis blob publish failed tenant={} rev={} part={}: {}", tenantId, revision, part, ex.getMessage());
            throw ex;
        }
    }

    @Override
    public byte[] getBlob(GatewayArtifactPointer pointer, GatewayArtifactPart part) {
        String key = part == GatewayArtifactPart.ACCESS_LISTS ? pointer.accessListsKey() : pointer.sceneRegistryKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("missing redis blob key for " + part);
        }
        try (Jedis jedis = requirePool().getResource()) {
            byte[] bytes = jedis.get(key.getBytes());
            if (bytes == null) {
                throw new IllegalStateException("redis blob missing: " + key);
            }
            return bytes;
        }
    }

    @Override
    public String locatorFor(String tenantId, long revision, GatewayArtifactPart part) {
        return GatewayArtifactKeys.blobKey(properties.getRedis().getBlobPrefix(), tenantId, revision, part);
    }

    public long nextRevision(String tenantId) {
        try (Jedis jedis = requirePool().getResource()) {
            return jedis.incr(GatewayArtifactKeys.seqKey(properties.getRedis().getPointerPrefix(), tenantId));
        }
    }

    public void updatePointer(GatewayArtifactPointer pointer) {
        try (Jedis jedis = requirePool().getResource()) {
            String key = GatewayArtifactKeys.pointerKey(properties.getRedis().getPointerPrefix(), pointer.tenantId());
            jedis.hset(key, pointer.toRedisHash());
        }
    }

    public Optional<GatewayArtifactPointer> getPointer(String tenantId) {
        try (Jedis jedis = requirePool().getResource()) {
            Map<String, String> hash = jedis.hgetAll(
                    GatewayArtifactKeys.pointerKey(properties.getRedis().getPointerPrefix(), tenantId));
            GatewayArtifactPointer pointer = GatewayArtifactPointer.fromRedisHash(tenantId, hash);
            return Optional.ofNullable(pointer);
        }
    }

    public void notifyPublished(GatewayArtifactPointer pointer) {
        try (Jedis jedis = requirePool().getResource()) {
            jedis.xadd(
                    properties.getRedis().getEventsStream(),
                    StreamEntryID.NEW_ENTRY,
                    Map.of(
                            "layer", "gateway",
                            "tenant_id", pointer.tenantId(),
                            "artifact_revision", String.valueOf(pointer.artifactRevision()),
                            "published_at", pointer.publishedAt() != null ? pointer.publishedAt() : "",
                            "access_lists_sha256", pointer.accessListsSha256() != null ? pointer.accessListsSha256() : "",
                            "trigger", pointer.trigger() != null ? pointer.trigger() : ""));
        } catch (Exception ex) {
            log.warn("gateway artifact stream notify failed: {}", ex.getMessage());
        }
    }

    public void recordHistory(String tenantId, long revision, long publishedAtEpochSec) {
        String historyKey = GatewayArtifactKeys.historyKey(properties.getRedis().getBlobPrefix(), tenantId);
        int retain = Math.max(1, properties.getRedis().getRetainRevisions());
        try (Jedis jedis = requirePool().getResource()) {
            jedis.zadd(historyKey, publishedAtEpochSec, String.valueOf(revision));
            long count = jedis.zcard(historyKey);
            if (count > retain) {
                List<String> old = new ArrayList<>(jedis.zrange(historyKey, 0, count - retain - 1));
                for (String revStr : old) {
                    long oldRev = Long.parseLong(revStr);
                    jedis.del(GatewayArtifactKeys.blobKey(
                            properties.getRedis().getBlobPrefix(), tenantId, oldRev, GatewayArtifactPart.ACCESS_LISTS));
                    jedis.del(GatewayArtifactKeys.blobKey(
                            properties.getRedis().getBlobPrefix(), tenantId, oldRev, GatewayArtifactPart.SCENE_REGISTRY));
                }
                if (!old.isEmpty()) {
                    jedis.zrem(historyKey, old.toArray(String[]::new));
                }
            }
        }
    }

    public List<Map<String, String>> listNodeAcks(String tenantId) {
        String prefix = GatewayArtifactKeys.ackKeyPrefix(properties.getRedis().getAckPrefix(), tenantId);
        List<Map<String, String>> out = new ArrayList<>();
        try (Jedis jedis = requirePool().getResource()) {
            for (String key : jedis.keys(prefix + "*")) {
                Map<String, String> ack = jedis.hgetAll(key);
                if (!ack.isEmpty()) {
                    ack.put("node_id", key.substring(prefix.length()));
                    out.add(ack);
                }
            }
        }
        return out;
    }

    private JedisPool requirePool() {
        return pool.orElseThrow(() -> new IllegalStateException("Redis unavailable for gateway artifacts"));
    }
}
