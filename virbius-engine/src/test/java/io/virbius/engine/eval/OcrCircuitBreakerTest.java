package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class OcrCircuitBreakerTest {

    /** Clock whose current-millis can be advanced manually. */
    private static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long start) {
            this.millis = start;
        }

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    @Test
    void opensAfterSustainedFailuresAndRecoversViaProbe() {
        MutableClock clock = new MutableClock(1_000_000);
        OcrCircuitBreaker breaker = new OcrCircuitBreaker(0.5, 45_000, clock);

        // closed below min samples
        for (int i = 0; i < 9; i++) {
            assertTrue(breaker.allowRequest());
            breaker.recordFailure();
            assertFalse(breaker.isOpen());
        }
        // 10th failure: 100% failure rate over 10 samples >= threshold -> open
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        // open: requests refused without waiting
        assertFalse(breaker.allowRequest());

        // after the wait window, one probe passes
        clock.advance(45_000);
        assertTrue(breaker.allowRequest());
        // half-open: no other request until the probe resolves
        assertFalse(breaker.allowRequest());

        breaker.recordSuccess();
        assertFalse(breaker.isOpen());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void halfOpenProbeFailureReopens() {
        MutableClock clock = new MutableClock(0);
        OcrCircuitBreaker breaker = new OcrCircuitBreaker(0.5, 45_000, clock);
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure();
        }
        assertTrue(breaker.isOpen());
        clock.advance(45_000);
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertTrue(breaker.isOpen());
        // must wait a full window again
        assertFalse(breaker.allowRequest());
        clock.advance(44_999);
        assertFalse(breaker.allowRequest());
        clock.advance(1);
        assertTrue(breaker.allowRequest());
    }

    @Test
    void healthyTrafficStaysClosed() {
        OcrCircuitBreaker breaker = new OcrCircuitBreaker(0.5, 45_000);
        for (int i = 0; i < 50; i++) {
            assertTrue(breaker.allowRequest());
            breaker.recordSuccess();
        }
        for (int i = 0; i < 4; i++) {
            breaker.recordFailure();
        }
        assertFalse(breaker.isOpen());
    }
}
