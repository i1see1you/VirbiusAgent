package io.virbius.control.domain.dto.request;

public record RolloutEvaluateRequest(String targetState, Integer canaryPercent) {}
