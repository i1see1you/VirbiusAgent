package io.virbius.engine.deploy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.virbius.engine.cache.StreamGroupJanitor;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Subscribes to the {@code virbius:deploy:notify} Redis stream and refreshes a local cached
 * copy of the active deploy rollout pointer. On each notification the watcher reads the latest
 * pointer from Redis so that {@link NodePoolResolver} can reflect the latest
 * {@code canary_percent} promptly.
 *
 * <p><b>Multi-replica:</b> the consumer group is per-instance
 * ({@code engine-deploy-{instanceId}}) so every replica receives every
 * notification (broadcast, mirroring {@code CacheReloadSubscriber}). A shared
 * group would deliver each notification to exactly one replica, leaving the
 * others with a stale {@code canary_percent} cache and divergent pool routing.
 * The group is destroyed on shutdown to avoid leaking group metadata across
 * pod restarts.
 */
@Component
public class DeployPointerWatcher {

    private static final Logger log = LoggerFactory.getLogger(DeployPointerWatcher.class);
    private static final String STREAM_KEY = "virbius:deploy:notify";
    /** Reap prefix-matching groups idle this long (a live replica reads every <=5s). */
    private static final long STALE_IDLE_MS = 1_800_000L;
    private final String consumerGroup;
    private final String consumerName = "engine-" + UUID.randomUUID().toString().substring(0, 8);

    private final Optional<JedisPool> jedisPool;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "deploy-pointer-watcher");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    /** tenantId -> canary_percent (cached). */
    private final ConcurrentHashMap<String, Integer> canaryPercentCache = new ConcurrentHashMap<>();

    public DeployPointerWatcher(
            Optional<JedisPool> jedisPool,
            @Value("${virbius.instance.id:}") String instanceId,
            @Value("${hostname:}") String hostname) {
        this.jedisPool = jedisPool;
        // Resolve directly (static helper) instead of injecting NodePoolResolver:
        // that bean itself depends on this watcher, so a proxy call during this
        // watcher's initialization (constructor OR @PostConstruct) would re-enter
        // the still-in-creation NodePoolResolver and fail with
        // BeanCurrentlyInCreationException. The id resolution algorithm is shared.
        this.consumerGroup = groupName(NodePoolResolver.resolveInstanceId(instanceId, hostname));
    }

    /**
     * Consumer-group name for a resolved instance id. Single source of truth (the
     * watcher reuses {@link NodePoolResolver#instanceId()} rather than resolving
     * its own id, so the deploy group and the canary bucket can never diverge).
     */
    static String groupName(String instanceId) {
        return "engine-deploy-" + instanceId;
    }

    @PostConstruct
    public void start() {
        if (jedisPool.isEmpty()) {
            log.warn("Redis not available — deploy-pointer watcher disabled");
            return;
        }
        executor.submit(this::consume);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        executor.shutdownNow();
        if (jedisPool.isPresent() && consumerGroup != null) {
            try (var jedis = jedisPool.get().getResource()) {
                StreamGroupJanitor.drainPending(jedis, STREAM_KEY, consumerGroup);
                jedis.xgroupDestroy(STREAM_KEY, consumerGroup);
            } catch (Exception ignored) {
                // best-effort cleanup; a crashed pod is reaped at the next startup
            }
        }
    }

    /** Returns the cached canary percent for a tenant, or 0 if none. */
    public int canaryPercent(String tenantId) {
        return canaryPercentCache.getOrDefault(tenantId, 0);
    }

    /** Returns all tenant IDs with a cached pointer. */
    public List<String> knownTenants() {
        return List.copyOf(canaryPercentCache.keySet());
    }

    private void consume() {
        JedisPool pool = jedisPool.get();
        reapStaleGroups(pool);
        while (running) {
            try {
                ensureGroup(pool);
                try (var jedis = pool.getResource()) {
                    List<Map.Entry<String, List<StreamEntry>>> results = jedis.xreadGroup(
                            consumerGroup, consumerName,
                            XReadGroupParams.xReadGroupParams().count(1).block(5000),
                            Map.of(STREAM_KEY, StreamEntryID.UNRECEIVED_ENTRY));
                    List<StreamEntry> entries = results != null && !results.isEmpty()
                            ? results.get(0).getValue()
                            : Collections.emptyList();
                    for (StreamEntry entry : entries) {
                        handle(entry);
                        jedis.xack(STREAM_KEY, consumerGroup, entry.getID());
                    }
                }
            } catch (redis.clients.jedis.exceptions.JedisConnectionException e) {
                if (running) {
                    log.warn("redis connection lost, retrying in 3s...");
                    sleep(3000);
                }
            } catch (Exception e) {
                log.warn("deploy pointer watcher error: {}", e.getMessage());
                sleep(1000);
            }
        }
    }

    private void ensureGroup(JedisPool pool) {
        try (var jedis = pool.getResource()) {
            jedis.xgroupCreate(STREAM_KEY, consumerGroup, StreamEntryID.LAST_ENTRY, true);
        } catch (Exception ignored) {
        }
    }

    /** Startup pass: destroy per-instance groups left behind by dead pods. */
    private void reapStaleGroups(JedisPool pool) {
        try (var jedis = pool.getResource()) {
            StreamGroupJanitor.reapStaleGroups(jedis, STREAM_KEY, "engine-deploy-", consumerGroup, STALE_IDLE_MS);
        } catch (Exception ignored) {
        }
    }

    private void handle(StreamEntry entry) {
        try {
            Map<String, String> fields = entry.getFields();
            String tenantId = fields.get("tenant_id");
            if (tenantId == null || tenantId.isBlank()) {
                return;
            }
            // Re-read the full pointer from Redis
            String pointerKey = "virbius:deploy:active:" + tenantId;
            try (var jedis = jedisPool.get().getResource()) {
                Map<String, String> hash = jedis.hgetAll(pointerKey);
                String raw = hash.get("canary_percent");
                int pct = (raw != null && !raw.isBlank()) ? Integer.parseInt(raw) : 0;
                canaryPercentCache.put(tenantId, pct);
                log.info("deploy pointer refreshed tenant={} canary_percent={}", tenantId, pct);
            }
        } catch (Exception e) {
            log.warn("failed to process deploy pointer message: {}", e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
