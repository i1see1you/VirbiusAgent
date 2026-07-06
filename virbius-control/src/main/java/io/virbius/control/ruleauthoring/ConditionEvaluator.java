package io.virbius.control.ruleauthoring;

import io.virbius.control.domain.AccessListEntry;
import io.virbius.control.domain.AccessListMeta;
import io.virbius.control.repository.ListMetaRepository;
import io.virbius.policy.ListMatcher;
import io.virbius.policy.MatchContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates condition AST for rule simulate (mock-friendly). */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    public static boolean evaluate(
            Map<String, Object> condition,
            MatchContext ctx,
            String tenantId,
            ListMetaRepository listRepo,
            Map<String, Long> cumulativeOverrides,
            boolean forceListHits) {
        if (condition == null || condition.isEmpty()) {
            return false;
        }
        return evalNode(condition, ctx, tenantId, listRepo, cumulativeOverrides, forceListHits);
    }

    @SuppressWarnings("unchecked")
    private static boolean evalNode(
            Map<String, Object> node,
            MatchContext ctx,
            String tenantId,
            ListMetaRepository listRepo,
            Map<String, Long> cumulativeOverrides,
            boolean forceListHits) {
        String type = str(node.get("type"));
        if (type == null || type.isBlank()) {
            String op = str(node.get("op"));
            if ("and".equals(op)) {
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children == null) {
                    return false;
                }
                for (Map<String, Object> c : children) {
                    if (!evalNode(c, ctx, tenantId, listRepo, cumulativeOverrides, forceListHits)) {
                        return false;
                    }
                }
                return true;
            }
            if ("or".equals(op)) {
                List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
                if (children == null) {
                    return false;
                }
                for (Map<String, Object> c : children) {
                    if (evalNode(c, ctx, tenantId, listRepo, cumulativeOverrides, forceListHits)) {
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
        return switch (type) {
            case "list_match" -> evalList(node, ctx, tenantId, listRepo, forceListHits);
            case "cumulative" -> evalCumulative(node, cumulativeOverrides);
            case "var_compare" -> evalVar(node, ctx);
            case "l3_aggregate" -> evalL3(node, ctx, tenantId, listRepo, forceListHits);
            default -> false;
        };
    }

    private static boolean evalList(
            Map<String, Object> node,
            MatchContext ctx,
            String tenantId,
            ListMetaRepository listRepo,
            boolean forceListHits) {
        String listName = str(node.get("list_name"));
        if (listName == null) {
            return false;
        }
        if (forceListHits) {
            return true;
        }
        AccessListMeta meta = listRepo.getMeta(tenantId, listName).orElse(null);
        if (meta == null) {
            return false;
        }
        List<String> entries = listRepo.listEntries(tenantId, listName).stream()
                .map(AccessListEntry::value)
                .toList();
        if (entries.isEmpty()) {
            return false;
        }
        String valueSource = str(node.get("value_source"));
        String value;
        if (valueSource != null && valueSource.startsWith("var:")) {
            value = ctx.vars().get(valueSource.substring(4));
        } else {
            value = ctx.content();
        }
        if (value == null || value.isBlank()) {
            return false;
        }
        return ListMatcher.match(meta.dimension(), value, ctx.content() != null ? ctx.content() : "", entries);
    }

    private static boolean evalCumulative(Map<String, Object> node, Map<String, Long> overrides) {
        String name = str(node.get("cumulative_name"));
        if (name == null) {
            return false;
        }
        long count = overrides != null && overrides.containsKey(name) ? overrides.get(name) : 0L;
        long threshold = number(node.get("threshold"), 1);
        String compare = str(node.get("compare"));
        if (compare == null) {
            compare = "gte";
        }
        return switch (compare.toLowerCase(Locale.ROOT)) {
            case "gt" -> count > threshold;
            case "gte" -> count >= threshold;
            case "lt" -> count < threshold;
            case "lte" -> count <= threshold;
            case "eq" -> count == threshold;
            default -> false;
        };
    }

    private static boolean evalVar(Map<String, Object> node, MatchContext ctx) {
        String logical = str(node.get("logical"));
        String literal = str(node.get("literal"));
        String op = str(node.get("op"));
        if (logical == null || literal == null) {
            return false;
        }
        String actual = ctx.vars().get(logical);
        if (actual == null) {
            return false;
        }
        return "eq".equals(op != null ? op : "eq") && literal.equals(actual);
    }

    private static boolean evalL3(
            Map<String, Object> node,
            MatchContext ctx,
            String tenantId,
            ListMetaRepository listRepo,
            boolean forceListHits) {
        String listName = str(node.get("list_name"));
        if (listName != null && !listName.isBlank()) {
            if (evalList(listNode(listName, "content"), ctx, tenantId, listRepo, forceListHits)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> listNode(String listName, String valueSource) {
        return Map.of("type", "list_match", "list_name", listName, "value_source", valueSource);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static long number(Object o, long defaultVal) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        if (o == null) {
            return defaultVal;
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
