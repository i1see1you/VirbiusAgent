package io.virbius.control.domain.enums;

public enum RuleLayer {
    EDGE("edge"),
    GATEWAY("gateway"),
    CLOUD("cloud");

    private final String value;

    RuleLayer(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}