package io.virbius.control.domain.enums;

public enum EnforceMode {
    DRY_RUN("dry_run"),
    CANARY("canary"),
    FULL("full");

    private final String value;

    EnforceMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static EnforceMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DRY_RUN;
        }
        for (EnforceMode mode : values()) {
            if (mode.value.equalsIgnoreCase(raw.trim())) {
                return mode;
            }
        }
        throw new IllegalArgumentException("enforce_mode must be dry_run, canary, or full");
    }
}