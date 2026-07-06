package io.virbius.engine.deploy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/**
 * Periodically (every 30s) writes this engine instance's metadata to Redis as a heartbeat
 * under {@code virbius:nodes:cloud:{tenant}:{instance_id}} with a 60s TTL.
 *
 * <p>Written fields: {@code hostname}, {@code pid}, {@code pool}, {@code artifact_revision},
 * {@code policy_version}, {@code last_seen}.
 */
@Component
public class EngineNodeHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(EngineNodeHeartbeat.class);

    private final Optional<JedisPool> jedisPool;
    private final NodePoolResolver poolResolver;
    private final DeployPointerWatcher pointerWatcher;
    private final String hostname;
    private final String pid;
    private final String instanceId;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "engine-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public EngineNodeHeartbeat(
            Optional<JedisPool> jedisPool,
            NodePoolResolver poolResolver,
            DeployPointerWatcher pointerWatcher) {
        this.jedisPool = jedisPool;
        this.poolResolver = poolResolver;
        this.pointerWatcher = pointerWatcher;
        this.instanceId = poolResolver.instanceId();
        this.hostname = instanceId.contains("-") ? instanceId.substring(0, instanceId.lastIndexOf('-')) : instanceId;
        this.pid = instanceId.contains("-") ? instanceId.substring(instanceId.lastIndexOf('-') + 1) : "0";
    }

    @PostConstruct
    public void start() {
        if (jedisPool.isEmpty()) {
            log.warn("Redis not available — engine heartbeat disabled");
            return;
        }
        scheduler.scheduleAtFixedRate(this::beat, 0, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void beat() {
        try (var jedis = jedisPool.get().getResource()) {
            long now = System.currentTimeMillis() / 1000;
            for (String tenant : pointerWatcher.knownTenants()) {
                String key = "virbius:nodes:cloud:" + tenant + ":" + instanceId;
                String pool = poolResolver.resolvePool(tenant);
                jedis.hset(key, Map.of(
                        "hostname", hostname,
                        "pid", pid,
                        "pool", pool,
                        "last_seen", String.valueOf(now)));
                jedis.expire(key, 60);
            }
        } catch (Exception e) {
            log.warn("heartbeat failed: {}", e.getMessage());
        }
    }
}
