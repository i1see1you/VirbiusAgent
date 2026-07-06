package io.virbius.control.domain.enums;

public enum IntentAction {
    ALLOW("allow"),
    DENY("deny"),
    CAPTCHA("captcha"),
    REVIEW("review");

    private final String value;

    IntentAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static IntentAction parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("intent_action required");
        }
        for (IntentAction a : values()) {
            if (a.value.equalsIgnoreCase(raw.trim())) {
                return a;
            }
        }
        throw new IllegalArgumentException("intent_action must be allow, deny, captcha, or review");
    }

    public static IntentAction defaultForRisk(int riskScore) {
        return switch (io.virbius.policy.IntentAction.defaultForRisk(riskScore)) {
            case "allow" -> ALLOW;
            case "deny" -> DENY;
            case "captcha" -> CAPTCHA;
            default -> REVIEW;
        };
    }
}
