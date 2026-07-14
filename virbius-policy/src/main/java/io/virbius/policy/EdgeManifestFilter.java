package io.virbius.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time edge manifest partitioning by {@code bind_scope} + {@code app_id}.
 * See docs/DESIGN.md §11.4 (service {@code app_ids}, tool {@code tool_names+app_ids});
 * runtime matching is not used on edge SDK.
 */
public final class EdgeManifestFilter {

    private EdgeManifestFilter() {}

    /** Collect app_ids from service-bind rules only (tool scope app_ids pulled directly). */
    public static List<String> collectAppIds(List<Map<String, Object>> ruleScopes) {
        Set<String> ids = new LinkedHashSet<>();
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
    public static boolean includesForApp(Map<String, Object> scope, String appId) {
        if (appId == null || appId.isBlank()) {
            return false;
        }
        String bind = BindScope.scopeFromRuleScope(scope);
        Map<String, Object> ref = BindScope.bindRefFromScope(scope);
        return switch (bind) {
            case BindScope.SERVICE -> matchesServiceApp(ref, appId);
            case BindScope.TOOL -> matchesToolForApp(ref, appId);
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

    private static boolean matchesToolForApp(Map<String, Object> ref, String appId) {
        if (ref == null || ref.isEmpty()) {
            return true;
        }
        List<String> appIds = appIdsFromBindRef(ref);
        if (appIds.isEmpty()) {
            return true;
        }
        return appIds.contains(appId);
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
