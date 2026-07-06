package io.virbius.control.ruleauthoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RuleRecipeCatalog {

    private RuleRecipeCatalog() {}

    public static List<Map<String, Object>> recipes(String layer) {
        List<Map<String, Object>> all = List.of(
                recipe(
                        "gateway.list.deny",
                        "gateway",
                        "List deny",
                        "global",
                        Map.of("type", "list_match", "list_name", "deny_keyword", "value_source", "content"),
                        100,
                        "deny"),
                recipe(
                        "gateway.cum.rate_limit",
                        "gateway",
                        "Cumulative threshold exceeded",
                        "global",
                        Map.of(
                                "type",
                                "cumulative",
                                "cumulative_name",
                                "user_req_1h",
                                "compare",
                                "gte",
                                "threshold",
                                120),
                        100,
                        "deny"),
                recipe(
                        "gateway.route.scene_list",
                        "gateway",
                        "Route + scene list",
                        "route",
                        Map.of("type", "list_match", "list_name", "deny_keyword", "value_source", "content"),
                        100,
                        "deny",
                        Map.of("scenes", List.of("medical-prod_clinical"))),
                recipe(
                        "cloud.list.deny",
                        "cloud",
                        "List deny",
                        "global",
                        Map.of("type", "list_match", "list_name", "deny_keyword", "value_source", "content"),
                        100,
                        "deny"),
                recipe(
                        "cloud.l3.aggregate",
                        "cloud",
                        "L3 Aggregation",
                        "global",
                        Map.of("type", "l3_aggregate", "list_name", "deny_keyword"),
                        100,
                        "deny"),
                recipe(
                        "cloud.cum.rate_limit",
                        "cloud",
                        "Cumulative threshold exceeded",
                        "global",
                        Map.of(
                                "type",
                                "cumulative",
                                "cumulative_name",
                                "user_req_1h",
                                "compare",
                                "gte",
                                "threshold",
                                120),
                        100,
                        "deny"));
        if (layer == null || layer.isBlank()) {
            return all;
        }
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> r : all) {
            if (layer.equals(r.get("layer"))) {
                filtered.add(r);
            }
        }
        return filtered;
    }

    private static Map<String, Object> recipe(
            String id,
            String layer,
            String label,
            String bindScope,
            Map<String, Object> condition,
            int risk,
            String intent) {
        return recipe(id, layer, label, bindScope, condition, risk, intent, Map.of());
    }

    private static Map<String, Object> recipe(
            String id,
            String layer,
            String label,
            String bindScope,
            Map<String, Object> condition,
            int risk,
            String intent,
            Map<String, Object> bindRefExtra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recipe_id", id);
        m.put("layer", layer);
        m.put("label", label);
        m.put("bind_scope", bindScope);
        Map<String, Object> ref = new LinkedHashMap<>(bindRefExtra);
        m.put("bind_ref", ref);
        m.put("condition", condition);
        m.put("risk_score", risk);
        m.put("intent_action", intent);
        return m;
    }
}
