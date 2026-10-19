package io.virbius.engine.metrics;

import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Unified counter for "Redis unavailable — component degraded to local/fallback
 * behavior" events. One metric name, the {@code component} tag identifies the
 * degradation point. Currently instrumented components: {@code review-risk-guard}
 * (dedup fell back to the local ledger), {@code session-risk-read} and
 * {@code session-risk-update} (SessionRiskManager swallowed a Redis error).</p>
 *
 * <p>The meter name is the conventional dotted form ({@code virbius.redis.fallback});
 * a Prometheus registry renders it as {@code virbius_redis_fallback_total}. This is
 * the observable baseline for the Redis-loss posture matrix: a Redis failover drill
 * must show this counter rising and recovering, and steady-state must show it flat.</p>
 */
public final class RedisFallbackMetrics {

    private static final Logger log = LoggerFactory.getLogger(RedisFallbackMetrics.class);

    public static final String METRIC_NAME = "virbius.redis.fallback";

    private RedisFallbackMetrics() {
    }

    public static void record(String component) {
        try {
            Metrics.counter(METRIC_NAME, "component", component).increment();
        } catch (Exception e) {
            // Metrics must never break the guarded call path.
            log.debug("failed to record redis fallback metric: {}", e.getMessage());
        }
    }
}
