package io.virbius.engine.eval;

public final class RiskScore {

    public static final int ALLOW = 0;
    public static final int BLOCK_THRESHOLD = 100;

    private RiskScore() {}

    public static boolean isBlock(int riskScore) {
        return riskScore >= BLOCK_THRESHOLD;
    }

    public static boolean isAllow(int riskScore) {
        return riskScore == ALLOW;
    }

    public static boolean isGray(int riskScore) {
        return riskScore > ALLOW && riskScore < BLOCK_THRESHOLD;
    }

    public static String suggest(int riskScore) {
        if (isBlock(riskScore)) {
            return "block";
        }
        if (isAllow(riskScore)) {
            return "allow";
        }
        return "review";
    }
}
