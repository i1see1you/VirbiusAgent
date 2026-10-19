package io.virbius.engine.eval;

import io.virbius.engine.metrics.RedisFallbackMetrics;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Deduplication ledger for review-driven session-risk increments.
 *
 * <p>Input review ({@code role=input}) may push risk into the session model at
 * most once per {@code trace_id} (a retried review of the same user message
 * must not double-count). Streaming output review never increments risk at all
 * (see the role side-effect matrix in PROTOCOL), so only the input path
 * consults this ledger. A blank {@code trace_id} is never deduplicated — the
 * caller opted out of trace correlation, so decay + rate limiting are the only
 * bounds (accepted risk).
 *
 * <p><b>Multi-replica:</b> the primary ledger is Redis ({@code SET NX PX}) so a
 * retried review load-balanced to another engine replica cannot double-count.
 * When Redis is unavailable the guard falls back to this process's local map:
 * per-replica dedup only — a known, bounded window in which a cross-replica
 * retry may double-count (tracked by {@code virbius_redis_fallback_total}).
 */
@Component
public class ReviewRiskGuard {

    private static final String REDIS_KEY_PREFIX = "virbius:review:dedup:";

    private final long ttlMs;
    private final Optional<JedisPool> jedisPool;
    private final ConcurrentHashMap<String, Long> acquired = new ConcurrentHashMap<>();

    @Autowired
    public ReviewRiskGuard(
            @Value("${virbius.review.input-risk-dedup-ttl-ms:600000}") long ttlMs,
            Optional<JedisPool> jedisPool) {
        this.ttlMs = Math.max(0, ttlMs);
        this.jedisPool = jedisPool;
    }

    /** Test/local constructor: local-map-only behavior (single replica). */
    public ReviewRiskGuard(@Value("${virbius.review.input-risk-dedup-ttl-ms:600000}") long ttlMs) {
        this(ttlMs, Optional.empty());
    }

    /**
     * @return {@code true} when this trace may apply its risk increment (first
     *         sighting within the TTL); {@code false} when it already counted.
     */
    public boolean tryAcquire(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return true;
        }
        if (ttlMs > 0 && jedisPool.isPresent()) {
            try (var jedis = jedisPool.get().getResource()) {
                String res = jedis.set(REDIS_KEY_PREFIX + traceId, "1",
                        SetParams.setParams().nx().px(ttlMs));
                return "OK".equals(res);
            } catch (Exception e) {
                RedisFallbackMetrics.record("review-risk-guard");
                // fall through to the local ledger (per-replica dedup)
            }
        }
        return localTryAcquire(traceId);
    }

    private boolean localTryAcquire(String traceId) {
        long now = System.currentTimeMillis();
        if (acquired.size() > 8192) {
            evict(now);
        }
        Long expiry = acquired.putIfAbsent(traceId, now + ttlMs);
        if (expiry == null) {
            return true;
        }
        if (expiry < now) {
            acquired.put(traceId, now + ttlMs);
            return true;
        }
        return false;
    }

    private void evict(long now) {
        for (Iterator<Map.Entry<String, Long>> it = acquired.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue() < now) {
                it.remove();
            }
        }
    }
}
