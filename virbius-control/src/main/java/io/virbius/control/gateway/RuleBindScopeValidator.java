package io.virbius.control.gateway;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.policy.BindScope;
import io.virbius.policy.EdgeManifestFilter;
import java.util.List;
import java.util.Map;

/** Validates rule {@code bind_scope} against bundle metadata. */
public final class RuleBindScopeValidator {

    private static final String DEFAULT_BUNDLE_VERSION = "0.1.0";

    private RuleBindScopeValidator() {}

    public static void validateToolScope(
            Map<String, Object> bundleMetadata, Map<String, Object> ruleScope, String ruleId) {
        if (ruleScope == null || ruleScope.isEmpty()) {
            return;
        }
        if (!BindScope.TOOL.equals(BindScope.scopeFromRuleScope(ruleScope))) {
            return;
        }
        Map<String, Object> bindRef = BindScope.bindRefFromScope(ruleScope);
        Object toolNamesObj = bindRef.get("tool_names");
        if (toolNamesObj instanceof List<?> toolList && !toolList.isEmpty()) {
            for (Object raw : toolList) {
                if (raw == null) {
                    continue;
                }
                String tn = String.valueOf(raw).trim();
                if (!tn.isEmpty() && !tn.matches("[a-z][a-z0-9_-]*") && !"*".equals(tn)) {
                    throw new IllegalArgumentException("invalid tool_name in bind_ref.tool_names: "
                            + tn + " (rule " + ruleId + ")");
                }
            }
        }
    }

    public static void validateToolScope(UpsertRuleRequest req, Map<String, Object> bundleMetadata) {
        validateToolScope(bundleMetadata, req.scope(), req.ruleId());
        if ("edge".equalsIgnoreCase(req.layer())) {
            validateEdgeBind(req, bundleMetadata);
        }
        if ("falco".equalsIgnoreCase(req.layer()) || "kernel".equalsIgnoreCase(req.layer())) {
            validateFalcoBind(req, bundleMetadata);
        }
    }

    /** Edge rules: {@code service} app_ids required; tool scope always valid (tool_names optional). */
    public static void validateEdgeBind(UpsertRuleRequest req, Map<String, Object> bundleMetadata) {
        Map<String, Object> scope = req.scope();
        if (scope == null || scope.isEmpty()) {
            return;
        }
        String bind = BindScope.scopeFromRuleScope(scope);
        Map<String, Object> ref = BindScope.bindRefFromScope(scope);
        if (BindScope.SERVICE.equals(bind)) {
            List<String> appIds = EdgeManifestFilter.appIdsFromBindRef(ref);
            if (appIds.isEmpty()) {
                throw new IllegalArgumentException("bind_ref.app_ids required for service bind (rule "
                        + req.ruleId() + ")");
            }
        }
    }

    /**
     * Falco/kernel rules: {@code global} and {@code service} are allowed;
     * {@code tool} scope is rejected (Falco monitors syscalls, not tools).
     * {@code service} requires {@code bind_ref.app_ids}.
     */
    public static void validateFalcoBind(UpsertRuleRequest req, Map<String, Object> bundleMetadata) {
        Map<String, Object> scope = req.scope();
        if (scope == null || scope.isEmpty()) {
            return;
        }
        String bind = BindScope.scopeFromRuleScope(scope);
        if (BindScope.TOOL.equals(bind)) {
            throw new IllegalArgumentException(
                    "tool bind_scope not supported for falco rules (rule " + req.ruleId() + ")");
        }
        if (BindScope.SERVICE.equals(bind)) {
            Map<String, Object> ref = BindScope.bindRefFromScope(scope);
            List<String> appIds = EdgeManifestFilter.appIdsFromBindRef(ref);
            if (appIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "bind_ref.app_ids required for service bind (rule " + req.ruleId() + ")");
            }
        }
    }

    public static String defaultBundleVersion() {
        return DEFAULT_BUNDLE_VERSION;
    }
}
