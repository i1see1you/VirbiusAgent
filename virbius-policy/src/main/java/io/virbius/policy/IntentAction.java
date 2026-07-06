package io.virbius.policy;

import java.util.Locale;

public final class IntentAction {

    public static final String ALLOW = "allow";
    public static final String DENY = "deny";
    public static final String CAPTCHA = "captcha";
    public static final String REVIEW = "review";

    private IntentAction() {}

    public static String normalize(String raw, int riskScoreFallback) {
        if (raw != null && !raw.isBlank()) {
            String v = raw.trim().toLowerCase(Locale.ROOT);
            if (isKnown(v)) {
                return v;
            }
        }
        return defaultForRisk(riskScoreFallback);
    }

    public static String defaultForRisk(int riskScore) {
        if (riskScore >= 100) {
            return DENY;
        }
        if (riskScore <= 0) {
            return ALLOW;
        }
        return REVIEW;
    }

    public static int priority(String intent) {
        return switch (normalize(intent, 0)) {
            case DENY -> 100;
            case CAPTCHA -> 50;
            case REVIEW -> 30;
            default -> 0;
        };
    }

    public static boolean isAllowIntent(String intent) {
        return ALLOW.equals(normalize(intent, 0));
    }

    private static boolean isKnown(String v) {
        return ALLOW.equals(v) || DENY.equals(v) || CAPTCHA.equals(v) || REVIEW.equals(v);
    }
}
