package io.virbius.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time edge manifest partitioning by {@code bind_scope} + {@code app_id}.
 * See docs/DESIGN.md §11.4 (service {@code app_ids}); runtime matching is not used on edge SDK.
 */
public final class EdgeManifestFilter {

    private EdgeManifestFilter() {}

    /** Union of scene-registry apps and {@code service} bind {@code app_ids} on edge rules. */
    public static List<String> collectAppIds(SceneRegistry registry, List<Map<String, Object>> ruleScopes) {
        Set<String> ids = new LinkedHashSet<>();
        if (registry != null) {
            ids.addAll(registry.appIds());
        }
        if (ruleScopes != null) {
            for (Map<String, Object> scope : ruleScopes) {
                if (BindScope.SERVICE.equals(BindScope.scopeFromRuleScope(scope))) {
                    ids.addAll(appIdsFromBindRef(BindScope.bindRefFromScope(scope)));
                }
            }
        }
        return List.copyOf(ids);
    }

    /** Whether an edge rule belongs in the manifest compiled for {@code appId}. */
    public static boolean includesForApp(Map<String, Object> scope, String appId, SceneRegistry registry) {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        String bind = BindScope.scopeFromRuleScope(scope);
        Map<String, Object> ref = BindScope.bindRefFromScope(scope);
        return switch (bind) {
            case BindScope.SERVICE -> matchesServiceApp(ref, appId);
            case BindScope.ROUTE -> matchesRouteForApp(ref, appId, registry);
            default -> true;
        };
    }

    private static boolean matchesServiceApp(Map<String, Object> ref, String appId) {
        List<String> appIds = appIdsFromBindRef(ref);
        if (appIds.isEmpty()) {
            return false;
        }
        return appIds.contains(appId);
    }

    private static boolean matchesRouteForApp(Map<String, Object> ref, String appId, SceneRegistry registry) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        Object scenesObj = ref.get("scenes");
        if (!(scenesObj instanceof List<?> scenes) || scenes.isEmpty()) {
            return false;
        }
        for (Object raw : scenes) {
            if (raw == null) {
                continue;
            }
            String sceneId = String.valueOf(raw).trim();
            if (sceneId.isEmpty()) {
                continue;
            }
            if ("*".equals(sceneId)) {
                return true;
            }
            String owner = appIdForScene(registry, sceneId);
            if (appId.equals(owner)) {
                return true;
            }
        }
        return false;
    }

    private static String appIdForScene(SceneRegistry registry, String sceneId) {
        if (registry == null || sceneId == null) {
            return null;
        }
        for (SceneRegistry.SceneEntry entry : registry.scenes()) {
            if (sceneId.equals(entry.sceneId())) {
                return entry.appId();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static List<String> appIdsFromBindRef(Map<String, Object> ref) {
        if (ref == null) {
            return List.of();
        }
        Object appIds = ref.get("app_ids");
        if (!(appIds instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String id = String.valueOf(item).trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }
}
