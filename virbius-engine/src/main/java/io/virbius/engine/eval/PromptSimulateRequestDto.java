package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PromptSimulateRequestDto(
        @JsonProperty("rule_id") String ruleId,
        String body,
        @JsonProperty("reason_code") String reasonCode,
        String content) {}
