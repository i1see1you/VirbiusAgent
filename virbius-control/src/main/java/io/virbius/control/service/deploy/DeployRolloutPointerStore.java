package io.virbius.control.service.deploy;

import io.virbius.control.config.ControlJedisPools;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

/**
 * Reads / writes the active deploy rollout pointer in Redis and broadcasts notifications via the
 * deploy notify stream. Engine + gateway-agent subscribe to the stream and re-resolve their pool
 * (stable / canary) based on this pointer + their own {@code instance_bucket}.
 */
@Component
public class DeployRolloutPointerStore {

    private static final Logger log = LoggerFactory.getLogger(DeployRolloutPointerStore.class);

    private final Optional<JedisPool> pool;

    public DeployRolloutPointerStore(ControlJedisPools jedisPools) {
        this.pool = jedisPools.pool();
    }

    public boolean redisAvailable() {
        return pool.isPresent();
    }

    /** Returns a Jedis resource from the pool for callers that need direct commands. */
    public redis.clients.jedis.Jedis requireJedis() {
        return pool.orElseThrow(() -> new IllegalStateException("Redis unavailable")).getResource();
    }

    public Optional<DeployRolloutPointer> getPointer(String tenantId) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        try (Jedis jedis = pool.get().getResource()) {
            Map<String, String> hash = jedis.hgetAll(DeployRolloutKeys.activePointerKey(tenantId));
            return Optional.ofNullable(DeployRolloutPointer.fromRedisHash(tenantId, hash));
        } catch (Exception ex) {
            log.warn("read deploy rollout pointer failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public void writePointer(DeployRolloutPointer pointer) {
        if (pool.isEmpty()) {
            log.warn("redis unavailable; deploy rollout pointer not persisted");
            return;
        }
        try (Jedis jedis = pool.get().getResource()) {
            jedis.hset(DeployRolloutKeys.activePointerKey(pointer.tenantId()), pointer.toRedisHash());
        } catch (Exception ex) {
            log.warn("write deploy rollout pointer failed: {}", ex.getMessage());
        }
    }

    public void clearPointer(String tenantId) {
        if (pool.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.get().getResource()) {
            jedis.del(DeployRolloutKeys.activePointerKey(tenantId));
        } catch (Exception ex) {
            log.warn("clear deploy rollout pointer failed: {}", ex.getMessage());
        }
    }

    public Optional<String> notifyChange(String tenantId, String deployId, String reason) {
        if (pool.isEmpty()) {
            return Optional.empty();
        }
        try (Jedis jedis = pool.get().getResource()) {
            StreamEntryID id = jedis.xadd(
                    DeployRolloutKeys.STREAM_KEY,
                    StreamEntryID.NEW_ENTRY,
                    Map.of(
                            "tenant_id", tenantId,
                            "deploy_id", deployId == null ? "" : deployId,
                            "reason", reason == null ? "" : reason));
            return Optional.of(id.toString());
        } catch (Exception ex) {
            log.warn("notify deploy rollout change failed: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
