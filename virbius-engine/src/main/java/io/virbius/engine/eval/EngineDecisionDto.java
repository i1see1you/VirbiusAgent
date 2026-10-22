package io.virbius.engine.eval;

public record EngineDecisionDto(String effectiveAction, int maxRiskScore, String enforceMode, boolean suppressed) {

    /** Non-merge decisions (pre-pipeline denials, direct constructions) are never suppression events. */
    public EngineDecisionDto(String effectiveAction, int maxRiskScore, String enforceMode) {
        this(effectiveAction, maxRiskScore, enforceMode, false);
    }
}
