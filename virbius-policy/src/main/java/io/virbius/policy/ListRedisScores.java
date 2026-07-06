package io.virbius.policy;

import java.time.Instant;

public final class ListRedisScores {

    private ListRedisScores() {}

    /** Permanent entries use score 0; otherwise expire_at as Unix seconds. */
    public static double toScore(Instant expiresAt) {
        if (expiresAt == null) {
            return 0d;
        }
        long sec = expiresAt.getEpochSecond();
        return sec > 0 ? sec : 0d;
    }

    /** Active when score is 0 (permanent) or score > now (not yet expired). */
    public static boolean isActive(double score, long nowUnixSec) {
        if (Double.isNaN(score)) {
            return false;
        }
        if (score == 0d) {
            return true;
        }
        return score > nowUnixSec;
    }
}
