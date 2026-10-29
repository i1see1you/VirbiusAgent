package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PromptSimulateResponseDto(
        boolean hit,
        @JsonProperty("llm_hit_rule") boolean llmHitRule,
        @JsonProperty("triggered_id") String triggeredId,
        String reason,
        @JsonProperty("llm_response") String llmResponse,
        String error,
        String mode,
        String backend,
        Double probability,
        Double threshold,
        @JsonProperty("judge_hit") Boolean judgeHit,
        @JsonProperty("error_policy") String errorPolicy,
        @JsonProperty("error_applied") Boolean errorApplied,
        @JsonProperty("latency_ms") Long latencyMs) {}
