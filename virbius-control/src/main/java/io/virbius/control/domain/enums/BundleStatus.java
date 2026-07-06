package io.virbius.control.domain.enums;

public enum BundleStatus {
    DRAFT("draft"),
    VALIDATING("validating"),
    EVAL_RUNNING("eval_running"),
    COMPILING("compiling"),
    SYNCING("syncing"),
    ACTIVE("active");

    private final String value;

    BundleStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}