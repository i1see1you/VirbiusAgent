package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ListRedisScoresTest {

    @Test
    void permanentScoreAlwaysActive() {
        assertTrue(ListRedisScores.isActive(0, 1_000_000));
    }

    @Test
    void futureExpireActive() {
        long now = Instant.parse("2026-06-08T08:00:00Z").getEpochSecond();
        assertTrue(ListRedisScores.isActive(now + 3600, now));
    }

    @Test
    void pastExpireInactive() {
        long now = Instant.parse("2026-06-08T08:00:00Z").getEpochSecond();
        assertFalse(ListRedisScores.isActive(now - 1, now));
    }

    @Test
    void toScoreUsesEpochSeconds() {
        Instant exp = Instant.parse("2026-06-08T09:00:00Z");
        assertTrue(ListRedisScores.toScore(exp) > 0);
        assertTrue(ListRedisScores.toScore(null) == 0);
    }
}
