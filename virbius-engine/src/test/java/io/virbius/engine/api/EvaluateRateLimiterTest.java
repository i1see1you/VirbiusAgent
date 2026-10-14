package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvaluateRateLimiterTest {

    private static final String SESSION = "sess-1";
    private static final String IP = "10.0.0.1";
    private static final String TRACE = "tr-1";

    @Test
    void streamWindowsOfOneTraceShareASingleSessionUnit() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 3, 10_000, 64);
        for (int i = 0; i < 30; i++) {
            Optional<Duration> limited = limiter.check(SESSION, IP, TRACE, true);
            assertTrue(limited.isEmpty(), "window " + i + " must not be throttled");
        }
    }

    @Test
    void perTraceWindowCapRejects() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 1_000, 10_000, 5);
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.check(SESSION, IP, TRACE, true).isEmpty());
        }
        Optional<Duration> limited = limiter.check(SESSION, IP, TRACE, true);
        assertTrue(limited.isPresent());
        assertTrue(limited.get().toSeconds() >= 1);
    }

    @Test
    void separateTracesEachConsumeOneSessionUnit() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 2, 10_000, 64);
        assertTrue(limiter.check(SESSION, IP, "tr-a", true).isEmpty());
        assertTrue(limiter.check(SESSION, IP, "tr-a", true).isEmpty());
        assertTrue(limiter.check(SESSION, IP, "tr-b", true).isEmpty());
        // Third distinct trace in the same minute exceeds per-session=2.
        assertTrue(limiter.check(SESSION, IP, "tr-c", true).isPresent());
    }

    @Test
    void nonStreamCallsEachConsumeOneUnit() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 2, 10_000, 64);
        assertTrue(limiter.check(SESSION, IP, "tr-a", false).isEmpty());
        assertTrue(limiter.check(SESSION, IP, "tr-b", false).isEmpty());
        assertTrue(limiter.check(SESSION, IP, "tr-c", false).isPresent());
    }

    @Test
    void streamWithoutTraceCountsPerCall() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 2, 10_000, 64);
        assertTrue(limiter.check(SESSION, IP, null, true).isEmpty());
        assertTrue(limiter.check(SESSION, IP, null, true).isEmpty());
        assertTrue(limiter.check(SESSION, IP, null, true).isPresent());
    }

    @Test
    void ipBackstopCoversMissingSession() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 1_000, 3, 64);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.check(null, IP, "tr-" + i, false).isEmpty());
        }
        assertTrue(limiter.check(null, IP, "tr-x", false).isPresent());
    }

    @Test
    void disabledLimiterNeverThrottles() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(false, 1, 1, 1);
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.check(SESSION, IP, TRACE, true).isEmpty());
        }
    }

    @Test
    void retryAfterIsWithinAMinute() {
        EvaluateRateLimiter limiter = new EvaluateRateLimiter(true, 1, 10_000, 64);
        limiter.check(SESSION, IP, "tr-a", false);
        Optional<Duration> limited = limiter.check(SESSION, IP, "tr-b", false);
        assertTrue(limited.isPresent());
        assertTrue(limited.get().toSeconds() <= 60);
        assertFalse(limited.get().isNegative());
        assertEquals(Duration.ofSeconds(limited.get().toSeconds()), limited.get().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
    }
}
