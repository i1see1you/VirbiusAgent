package io.virbius.engine.eval;

public record EvaluateResponseDto(
        String effectiveAction,
        int maxRiskScore,
        String ruleId,
        int ruleRevision,
        String reasonCode,
        String traceId,
        boolean degraded,
        String enforceMode) {}
