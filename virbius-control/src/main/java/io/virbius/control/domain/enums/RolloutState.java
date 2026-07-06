package io.virbius.control.domain.enums;

public enum RolloutState {
    DRAFT("draft"),
    DISABLED("disabled"),
    DRY_RUN("dry_run"),
    CANARY("canary"),
    FULL("full");

    private final String value;

    RolloutState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RolloutState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRAFT;
        }
        for (RolloutState s : values()) {
            if (s.value.equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        throw new IllegalArgumentException("rollout_state must be draft, disabled, dry_run, canary, or full");
    }
}
