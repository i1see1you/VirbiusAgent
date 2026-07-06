package io.virbius.control.domain.dto.response;

import io.virbius.control.domain.RolloutEnforceExport;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuleResponseMapper {

    private RuleResponseMapper() {}

    public static Map<String, Object> toSummary(io.virbius.control.domain.RuleRevision r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_id", r.ruleId());
        m.put("current_revision", r.ruleRevision());
        m.put("layer", r.layer());
        m.put("runtime", r.runtime());
        m.put("reason_code", r.reasonCode() != null ? r.reasonCode() : "");
        m.put("rollout_state", r.rolloutState() != null ? r.rolloutState() : "draft");
        m.put("enforce_mode", RolloutEnforceExport.enforceMode(r.rolloutState()));
        m.put("risk_score", r.riskScore());
        m.put("intent_action", r.intentAction() != null ? r.intentAction() : "deny");
        m.put("is_async", r.isAsync());
        if (r.asyncActionConfig() != null && !r.asyncActionConfig().isBlank()) {
            m.put("async_action_config", r.asyncActionConfig());
        }
        if (r.canaryPercent() != null) {
            m.put("canary_percent", r.canaryPercent());
        }
        return m;
    }

    public static Map<String, Object> toDetail(io.virbius.control.domain.RuleRevision r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule_revision", r.ruleRevision());
        m.put("rollout_state", r.rolloutState() != null ? r.rolloutState() : "draft");
        m.put("enforce_mode", RolloutEnforceExport.enforceMode(r.rolloutState()));
        m.put("modified_at", r.modifiedAt() != null ? r.modifiedAt().toString() : java.time.Instant.now().toString());
        m.put("effective_from", r.effectiveFrom() != null ? r.effectiveFrom().toString() : null);
        m.put("effective_to", r.effectiveTo());
        m.put("tenant_id", r.tenantId());
        m.put("rule_id", r.ruleId());
        m.put("layer", r.layer());
        m.put("runtime", r.runtime());
        m.put("reason_code", r.reasonCode());
        m.put("risk_score", r.riskScore());
        m.put("intent_action", r.intentAction() != null ? r.intentAction() : "deny");
        m.put("scope", r.scope() != null ? r.scope() : Map.of());
        m.put("body", r.body());
        m.put("bundle_id", r.bundleId());
        m.put("is_async", r.isAsync());
        if (r.asyncActionConfig() != null && !r.asyncActionConfig().isBlank()) {
            m.put("async_action_config", r.asyncActionConfig());
        }
        if (r.canaryPercent() != null) {
            m.put("canary_percent", r.canaryPercent());
        }
        return m;
    }
}
