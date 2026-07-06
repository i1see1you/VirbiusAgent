package io.virbius.engine.eval;

public record SignalDto(
        String ruleId,
        int ruleRevision,
        String source,
        String layer,
        double score,
        String reasonCode,
        String intentAction,
        String enforceMode,
        Integer canaryPercent,
        String asyncActionConfig) {}
