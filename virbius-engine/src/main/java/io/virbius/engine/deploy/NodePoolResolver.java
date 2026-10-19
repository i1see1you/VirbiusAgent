package io.virbius.engine.deploy;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Resolves the machine-level pool (stable / canary) for <em>this</em> engine instance.
 *
 * <p>Bucket = CRC32C(instance_id) % 100. If the active deploy rollout pointer has
 * {@code canary_percent > 0} and {@code instance_bucket < canary_percent}, this instance
 * belongs to the canary pool; otherwise stable.
 *
 * <p>Uses the {@link DeployPointerWatcher} cache when available; falls back to reading
 * from Redis directly.
 */
@Component
public class NodePoolResolver {

    private static final Logger log = LoggerFactory.getLogger(NodePoolResolver.class);
    private static final String POINTER_KEY_PREFIX = "virbius:deploy:active:";

    private final Optional<JedisPool> jedisPool;
    private final DeployPointerWatcher pointerWatcher;
    private final String instanceId;
    private final int instanceBucket;

    public NodePoolResolver(
            Optional<JedisPool> jedisPool,
            DeployPointerWatcher pointerWatcher,
            @Value("${virbius.instance.id:}") String instanceId,
            @Value("${hostname:}") String hostname) {
        this.jedisPool = jedisPool;
        this.pointerWatcher = pointerWatcher;
        this.instanceId = resolveInstanceId(instanceId, hostname);
        this.instanceBucket = computeBucket(this.instanceId);
        log.info("NodePoolResolver instance={} bucket={}", this.instanceId, this.instanceBucket);
        if ((instanceId == null || instanceId.isBlank()) && (hostname == null || hostname.isBlank())) {
            log.error("VIRBIUS_INSTANCE_ID and hostname are both blank — instance id fell back to '{}'. "
                            + "Per-instance Redis consumer groups (engine-cache-*/engine-deploy-*) and the "
                            + "canary bucket rely on a UNIQUE id per replica; in containers HOSTNAME covers "
                            + "this, but bare-metal/systemd HA deployments MUST set VIRBIUS_INSTANCE_ID "
                            + "or two replicas can collide.",
                    this.instanceId);
        }
    }

    /** Returns the instance's bucket (0-99). */
    public int bucket() {
        return instanceBucket;
    }

    /** Returns this instance's identifier. */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Reads the active deploy rollout pointer (from watcher cache or Redis) for the given
     * tenant and decides whether this instance belongs to the canary or stable pool.
     */
    public String resolvePool(String tenantId) {
        int canaryPercent = pointerWatcher.canaryPercent(tenantId);
        if (canaryPercent > 0) {
            return instanceBucket < canaryPercent ? "canary" : "stable";
        }
        // Fallback: read from Redis (e.g. watcher not yet initialized)
        if (jedisPool.isEmpty()) {
            return "stable";
        }
        try (var jedis = jedisPool.get().getResource()) {
            Map<String, String> hash = jedis.hgetAll(POINTER_KEY_PREFIX + tenantId);
            String raw = hash.get("canary_percent");
            if (raw != null && !raw.isBlank()) {
                canaryPercent = Integer.parseInt(raw);
            }
            if (canaryPercent > 0) {
                return instanceBucket < canaryPercent ? "canary" : "stable";
            }
        } catch (Exception ex) {
            log.warn("resolvePool fallback failed tenant={}: {}", tenantId, ex.getMessage());
        }
        return "stable";
    }

    // ---------------------------------------------------------------

    static String resolveInstanceId(String configured, String hostname) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String pid = Long.toString(ProcessHandle.current().pid());
        String host = (hostname != null && !hostname.isBlank()) ? hostname : "unknown";
        return host + "-" + pid;
    }

    private static int computeBucket(String id) {
        CRC32C crc = new CRC32C();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100);
    }
}
