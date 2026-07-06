package io.virbius.control.gateway;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.gateway.SceneRegistryHelper;
import io.virbius.policy.BindScope;
import io.virbius.policy.EdgeManifestFilter;
import io.virbius.policy.SceneRegistry;
import java.util.List;
import java.util.Map;

/** Validates rule {@code bind_scope} against bundle gateway metadata. */
public final class RuleBindScopeValidator {

    private static final String DEFAULT_BUNDLE_VERSION = "0.1.0";

    private RuleBindScopeValidator() {}

    public static void validateRouteScenes(
            Map<String, Object> bundleMetadata, Map<String, Object> ruleScope, String ruleId) {
        if (ruleScope == null || ruleScope.isEmpty()) {
            return;
        }
        if (!BindScope.ROUTE.equals(BindScope.scopeFromRuleScope(ruleScope))) {
            return;
        }
        Map<String, Object> bindRef = BindScope.bindRefFromScope(ruleScope);
        Object scenesObj = bindRef.get("scenes");
        if (!(scenesObj instanceof List<?> sceneList) || sceneList.isEmpty()) {
            throw new IllegalArgumentException("bind_ref.scenes required for route bind (rule "
                    + ruleId
                    + ")");
        }
        SceneRegistry registry = SceneRegistryHelper.parseRegistry(bundleMetadata);
        if (registry == null) {
            return;
        }
        for (Object raw : sceneList) {
            if (raw == null) {
                continue;
            }
            String sid = String.valueOf(raw).trim();
            if (sid.isEmpty() || "*".equals(sid)) {
                continue;
            }
            if (!registry.hasScene(sid)) {
                throw new IllegalArgumentException("bind_ref.scenes contains unknown scene: "
                        + sid
                        + " (rule "
                        + ruleId
                        + ")");
            }
        }
    }

    public static void validateRouteScenes(UpsertRuleRequest req, Map<String, Object> bundleMetadata) {
        validateRouteScenes(bundleMetadata, req.scope(), req.ruleId());
        if ("edge".equalsIgnoreCase(req.layer())) {
            validateEdgeBind(req, bundleMetadata);
        }
    }

    /** Edge rules: {@code service} app_ids must exist in scene_registry; {@code route} is not supported. */
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
                        + req.ruleId()
                        + ")");
            }
            SceneRegistry registry = SceneRegistryHelper.parseRegistry(bundleMetadata);
            List<String> known = registry.appIds();
            if (!known.isEmpty()) {
                for (String id : appIds) {
                    if (!known.contains(id)) {
                        throw new IllegalArgumentException("bind_ref.app_ids unknown in scene_registry: "
                                + id
                                + " (rule "
                                + req.ruleId()
                                + ")");
                    }
                }
            }
        }
        if (BindScope.ROUTE.equals(bind)) {
            throw new IllegalArgumentException(
                    "edge rules do not support bind_scope route; use global or service + app_ids (rule "
                            + req.ruleId()
                            + ")");
        }
    }

    public static String defaultBundleVersion() {
        return DEFAULT_BUNDLE_VERSION;
    }
}
