package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/** T2: ReviewRiskGuard Redis primary path + local fallback. */
class ReviewRiskGuardTest {

    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    private ReviewRiskGuard guard(long ttl) {
        return new ReviewRiskGuard(ttl, Optional.of(pool));
    }

    @Test
    void firstSightingAcquiresViaRedis() {
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(eq("virbius:review:dedup:t1"), eq("1"), any(SetParams.class))).thenReturn("OK");

        assertTrue(guard(600000).tryAcquire("t1"));
    }

    @Test
    void duplicateReturnsFalse() {
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class))).thenReturn(null);

        assertFalse(guard(600000).tryAcquire("t2"));
    }

    @Test
    void fallsBackToLocalLedgerOnRedisError() {
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.set(anyString(), anyString(), any(SetParams.class)))
                .thenThrow(new RuntimeException("redis down"));

        ReviewRiskGuard g = guard(600000);
        assertTrue(g.tryAcquire("t3"));   // local: first sighting
        assertFalse(g.tryAcquire("t3"));  // local: duplicate within TTL
    }

    @Test
    void blankTraceIsNeverDeduplicated() {
        assertTrue(new ReviewRiskGuard(600000).tryAcquire(""));
    }

    /** V5: the Redis-error path emits the shared fallback counter (call-site assertion). */
    @Test
    void fallbackEmitsRedisFallbackMetric() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry reg =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        io.micrometer.core.instrument.Metrics.addRegistry(reg);
        try {
            when(pool.getResource()).thenReturn(jedis);
            when(jedis.set(anyString(), anyString(), any(SetParams.class)))
                    .thenThrow(new RuntimeException("redis down"));

            new ReviewRiskGuard(600000, Optional.of(pool)).tryAcquire("t-metric");

            org.junit.jupiter.api.Assertions.assertNotNull(
                    reg.find("virbius.redis.fallback").tag("component", "review-risk-guard").counter());
        } finally {
            io.micrometer.core.instrument.Metrics.removeRegistry(reg);
            io.micrometer.core.instrument.Metrics.globalRegistry.remove(
                    io.micrometer.core.instrument.Metrics.globalRegistry
                            .find("virbius.redis.fallback").tag("component", "review-risk-guard").meter());
        }
    }
}
