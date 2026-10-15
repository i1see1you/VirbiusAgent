package io.virbius.engine.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

/**
 * Evaluate-auth revocation cache (agent-level + jti-level).
 *
 * <p>Two delivery paths (same as control's publisher):
 * <ul>
 *   <li>Redis hash {@code virbius:engine:revocations} — boot-loads the full
 *       snapshot and reconciles every 30s (covers pub/sub message loss, C-gap).</li>
 *   <li>Redis pub/sub channel {@code virbius:license:revoked} — near-real-time
 *       push (sub-second propagation).</li>
 * </ul>
 *
 * <p>Checked on EVERY request (B1: the signature-verify cache never bypasses
 * this). Temporary revocations ({@code expires_at}) auto-expire.
 */
@Component
public class RevocationCache {

    private static final Logger log = LoggerFactory.getLogger(RevocationCache.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JedisPool jedisPool;
    private final String redisKey;
    private final String channel;

    /** entryKey (agent:<aid> | jti:<jti>) → expiresAt epoch-ms (0 = permanent). */
    private final ConcurrentHashMap<String, Long> revoked = new ConcurrentHashMap<>();

    private volatile boolean running = true;
    private Thread subscriberThread;

    public RevocationCache(
            JedisPool jedisPool,
            @Value("${virbius.evaluate.auth.revocation-redis-key:virbius:engine:revocations}")
            String redisKey,
            @Value("${virbius.evaluate.auth.revocation-channel:virbius:license:revoked}")
            String channel) {
        this.jedisPool = jedisPool;
        this.redisKey = redisKey;
        this.channel = channel;
    }

    /** Boot: load the full snapshot + start the pub/sub subscriber. */
    public void start() {
        loadSnapshot();
        subscriberThread = new Thread(this::subscribeLoop, "evaluate-revocation-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (subscriberThread != null) {
            subscriberThread.interrupt();
        }
    }

    /** Periodic reconciliation (missed-message backstop). */
    @Scheduled(fixedDelayString = "PT30S")
    public void reconcile() {
        try {
            loadSnapshot();
        } catch (Exception e) {
            log.warn("revocation reconciliation failed: {}", e.getMessage());
        }
    }

    private void loadSnapshot() {
        Map<String, String> hash;
        try (var jedis = jedisPool.getResource()) {
            hash = jedis.hgetAll(redisKey);
        } catch (Exception e) {
            log.warn("revocation snapshot load failed: {}", e.getMessage());
            return;
        }
        if (hash == null || hash.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> e : hash.entrySet()) {
            applyEntry(e.getKey(), e.getValue());
        }
        log.debug("revocation snapshot loaded: {} entr(y/ies)", revoked.size());
    }

    private void subscribeLoop() {
        while (running) {
            try (var jedis = jedisPool.getResource()) {
                jedis.subscribe(new JedisPubSub() {
                    @Override
                    public void onMessage(String channel, String message) {
                        try {
                            Map<String, Object> payload = JSON.readValue(message, Map.class);
                            String agentAid = payload.get("agent_aid") == null
                                    ? null : payload.get("agent_aid").toString();
                            String jti = payload.get("jti") == null
                                    ? null : payload.get("jti").toString();
                            if (agentAid != null) {
                                applyEntry("agent:" + agentAid, message);
                            }
                            if (jti != null) {
                                applyEntry("jti:" + jti, message);
                            }
                        } catch (Exception ex) {
                            log.debug("revocation message parse failed: {}", ex.getMessage());
                        }
                    }
                }, channel);
            } catch (Exception e) {
                if (running) {
                    log.warn("revocation subscriber error, reconnecting in 3s: {}", e.getMessage());
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void applyEntry(String entryKey, String payloadJson) {
        try {
            Map<String, Object> payload = JSON.readValue(payloadJson, Map.class);
            Object expiresAt = payload.get("expires_at");
            long expiryMs = 0;
            if (expiresAt != null && !expiresAt.toString().isBlank()) {
                try {
                    expiryMs = java.time.Instant.parse(expiresAt.toString()).toEpochMilli();
                } catch (Exception ignore) {
                    expiryMs = 0;
                }
            }
            revoked.put(entryKey, expiryMs);
            log.info("evaluate revocation applied: key={} temporary={}", entryKey, expiryMs > 0);
        } catch (Exception e) {
            log.debug("revocation entry parse failed (key={}): {}", entryKey, e.getMessage());
        }
    }

    /** Is the given agent / token revoked right now? */
    public boolean isRevoked(String agentAid, String jti) {
        prune();
        if (agentAid != null && isRevokedKey("agent:" + agentAid)) {
            return true;
        }
        return jti != null && isRevokedKey("jti:" + jti);
    }

    private boolean isRevokedKey(String key) {
        Long expiry = revoked.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry > 0 && System.currentTimeMillis() >= expiry) {
            return false; // temporary revocation lapsed
        }
        return true;
    }

    private void prune() {
        if (revoked.size() < 8192) {
            return;
        }
        long now = System.currentTimeMillis();
        revoked.entrySet().removeIf(e -> e.getValue() > 0 && e.getValue() <= now);
    }

    public int size() {
        prune();
        return revoked.size();
    }
}
