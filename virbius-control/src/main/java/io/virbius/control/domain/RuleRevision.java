package io.virbius.control.domain;

import java.time.Instant;
import java.util.Map;

public record RuleRevision(
        String tenantId,
        String ruleId,
        int ruleRevision,
        String bundleId,
        String layer,
        String runtime,
        String reasonCode,
        int riskScore,
        String intentAction,
        Map<String, Object> scope,
        Object body,
        String rolloutState,
        Integer canaryPercent,
        Instant modifiedAt,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean isAsync,
        String asyncActionConfig) {

    public String enforceMode() {
        return RolloutEnforceExport.enforceMode(rolloutState);
    }

    public Integer exportedCanaryPercent() {
        return RolloutEnforceExport.exportedCanaryPercent(rolloutState, canaryPercent);
    }
}
