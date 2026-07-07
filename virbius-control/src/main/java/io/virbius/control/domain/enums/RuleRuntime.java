package io.virbius.control.domain.enums;

public enum RuleRuntime {
    LUA_DSL("lua-dsl"),
    DLP_DSL("dlp-dsl"),
    LUA("lua"),
    PROMPT("prompt"),
    GROOVY("groovy"),
    AGENT_GROOVY("agent-groovy");

    private final String value;

    RuleRuntime(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}