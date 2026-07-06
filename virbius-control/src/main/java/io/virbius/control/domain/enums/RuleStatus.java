package io.virbius.control.domain.enums;

public enum RuleStatus {
    DRAFT("draft"),
    ACTIVE("active"),
    DISABLED("disabled");

    private final String value;

    RuleStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RuleStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRAFT;
        }
        for (RuleStatus s : values()) {
            if (s.value.equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        throw new IllegalArgumentException("rule_status must be draft, active, or disabled");
    }
}
