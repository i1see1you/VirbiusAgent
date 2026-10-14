package io.virbius.engine.eval;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
 */
@Component
public class ReviewRiskGuard {

    private final long ttlMs;
    private final ConcurrentHashMap<String, Long> acquired = new ConcurrentHashMap<>();

    public ReviewRiskGuard(@Value("${virbius.review.input-risk-dedup-ttl-ms:600000}") long ttlMs) {
        this.ttlMs = Math.max(0, ttlMs);
    }

    /**
     * @return {@code true} when this trace may apply its risk increment (first
     *         sighting within the TTL); {@code false} when it already counted.
     */
    public boolean tryAcquire(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return true;
        }
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
