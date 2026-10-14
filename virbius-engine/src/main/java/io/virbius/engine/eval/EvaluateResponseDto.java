package io.virbius.engine.eval;

import com.fasterxml.jackson.annotation.JsonInclude;

public record EvaluateResponseDto(
        String effectiveAction,
        int maxRiskScore,
        int sessionRiskScore,
        String ruleId,
        int ruleRevision,
        String reasonCode,
        String traceId,
        boolean degraded,
        String enforceMode,
        String challengeId,
        String argsHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) String disposalAction,
        @JsonInclude(JsonInclude.Include.NON_NULL) String sanitizedContent) {

    /**
     * Legacy constructor without disposal fields — used by every role except
     * {@code output}; the two extra fields are omitted from JSON when null so
     * existing consumers see a byte-identical response.
     */
    public EvaluateResponseDto(
            String effectiveAction,
            int maxRiskScore,
            int sessionRiskScore,
            String ruleId,
            int ruleRevision,
            String reasonCode,
            String traceId,
            boolean degraded,
            String enforceMode,
            String challengeId,
            String argsHash) {
        this(effectiveAction, maxRiskScore, sessionRiskScore, ruleId, ruleRevision,
                reasonCode, traceId, degraded, enforceMode, challengeId, argsHash, null, null);
    }
}
