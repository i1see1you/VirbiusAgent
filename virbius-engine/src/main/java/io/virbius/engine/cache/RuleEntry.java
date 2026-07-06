package io.virbius.engine.cache;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record RuleEntry(
        String tenantId,
        String ruleId,
        int ruleRevision,
        String layer,
        String runtime,
        String reasonCode,
        @JsonProperty("risk_score") int riskScore,
        @JsonProperty("intent_action") String intentAction,
        @JsonProperty("enforce_mode") String enforceMode,
        @JsonProperty("canary_percent") int canaryPercent,
        @JsonProperty("rollout_state") String rolloutState,
        String body,
        Object scope,
        @JsonProperty("is_async") boolean isAsync,
        @JsonProperty("async_action_config") String asyncActionConfig) {

    public String rolloutStateOrDefault() {
        if (rolloutState != null && !rolloutState.isBlank()) {
            return rolloutState;
        }
        if (enforceMode == null) {
            return "dry_run";
        }
        return switch (enforceMode.toLowerCase()) {
            case "full" -> "full";
            case "canary" -> "canary";
            default -> "dry_run";
        };
    }

    @SuppressWarnings("unchecked")
    public static RuleEntry fromMap(Map<String, Object> m) {
        return new RuleEntry(
                str(m, "tenant_id"),
                str(m, "rule_id"),
                intVal(m, "rule_revision"),
                str(m, "layer"),
                str(m, "runtime"),
                str(m, "reason_code"),
                intVal(m, "risk_score"),
                str(m, "intent_action"),
                str(m, "enforce_mode"),
                intVal(m, "canary_percent"),
                str(m, "rollout_state"),
                str(m, "body"),
                m.get("scope"),
                boolVal(m, "is_async"),
                str(m, "async_action_config"));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : null;
    }

    private static int intVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private static boolean boolVal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return Boolean.TRUE.equals(v) || "true".equals(String.valueOf(v));
    }
}
