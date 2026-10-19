package io.virbius.engine.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** T9: the Redis-fallback counter actually increments (observable baseline). */
class RedisFallbackMetricsTest {

    @Test
    void recordIncrementsTaggedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try {
            RedisFallbackMetrics.record("unit-test");

            var counter = registry.find(RedisFallbackMetrics.METRIC_NAME)
                    .tag("component", "unit-test")
                    .counter();
            assertNotNull(counter, "counter must be registered under " + RedisFallbackMetrics.METRIC_NAME);
            assertEquals(1.0, counter.count());
        } finally {
            Metrics.removeRegistry(registry);
            Metrics.globalRegistry.remove(
                    Metrics.globalRegistry.find(RedisFallbackMetrics.METRIC_NAME)
                            .tag("component", "unit-test").meter());
        }
    }
}
