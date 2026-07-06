package io.virbius.control.ruleauthoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Best-effort parse of {@code decide(ctx)} script into condition AST (not stored). */
public final class ConditionParser {

    private static final Pattern LIST_LUA =
            Pattern.compile("listMatch\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*ctx\\.content\\s*\\)");
    private static final Pattern LIST_LUA_VAR = Pattern.compile(
            "listMatch\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*ctx\\.var\\(['\"]([^'\"]+)['\"]\\)\\s*\\)");
    private static final Pattern LIST_GROOVY =
            Pattern.compile("ctx\\.listMatch\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern LIST_GROOVY_VAL = Pattern.compile(
            "ctx\\.listMatch\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*,\\s*ctx\\.var\\(['\"]([^'\"]+)['\"]\\)\\s*\\)");
    private static final Pattern CUM_LUA = Pattern.compile(
            "getCumulative\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*(>=|>|<=|<|==)\\s*(\\d+)");
    private static final Pattern CUM_GROOVY = Pattern.compile(
            "ctx\\.getCumulative\\s*\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)\\s*(>=|>|<=|<|==)\\s*(\\d+)");
    private static final Pattern L3_GROOVY = Pattern.compile(
            "ctx\\.listMatch\\s*\\(['\"]([^'\"]+)['\"]\\).*wouldHitBlock", Pattern.DOTALL);

    private ConditionParser() {}

    public static Map<String, Object> parse(String layer, String runtime, String script) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (script == null || script.isBlank()) {
            out.put("parseable", false);
            out.put("condition", null);
            out.put("inferred_recipe", null);
            out.put("warnings", List.of("empty script"));
            return out;
        }
        String rt = runtime != null ? runtime.trim().toLowerCase(Locale.ROOT) : "";
        boolean lua = "lua".equals(rt);
        boolean groovy = "groovy".equals(rt);
        String body = normalize(script);
        Map<String, Object> condition = tryParseBody(body, lua, groovy, warnings);
        boolean parseable = condition != null;
        out.put("parseable", parseable);
        out.put("condition", condition);
        out.put("inferred_recipe", parseable ? inferRecipe(condition, layer) : null);
        out.put("warnings", warnings);
        return out;
    }

    private static String normalize(String script) {
        String s = script.replace("\r\n", "\n");
        int start = s.indexOf("return");
        if (start >= 0) {
            s = s.substring(start + "return".length());
        }
        int end = s.lastIndexOf('\n');
        if (end > 0) {
            s = s.substring(0, end);
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    private static Map<String, Object> tryParseBody(String body, boolean lua, boolean groovy, List<String> warnings) {
        if (body.contains("&&") || body.contains("||")) {
            return parseFlatGroup(body, lua, groovy, warnings);
        }
        Map<String, Object> leaf = parseLeaf(body, lua, groovy);
        if (leaf != null) {
            return leaf;
        }
        return null;
    }

    private static Map<String, Object> parseFlatGroup(String body, boolean lua, boolean groovy, List<String> warnings) {
        String op = body.contains("||") ? "or" : "and";
        String split = body.contains("||") ? "\\|\\|" : "&&";
        String[] parts = body.split(split);
        List<Map<String, Object>> children = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            // outer group wraps (... && ...): the leading ( is on first child,
            // the trailing ) is on the last child; strip only one from each.
            if (i == 0 && p.startsWith("(")) p = p.substring(1).trim();
            if (i == parts.length - 1 && p.endsWith(")")) p = p.substring(0, p.length() - 1).trim();
            Map<String, Object> leaf = parseLeaf(p, lua, groovy);
            if (leaf == null) {
                warnings.add("unparseable segment: " + p);
                return null;
            }
            children.add(leaf);
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("op", op);
        group.put("children", children);
        return group;
    }

    private static Map<String, Object> parseLeaf(String expr, boolean lua, boolean groovy) {
        Matcher m;
        if (lua) {
            m = LIST_LUA.matcher(expr);
            if (m.find()) {
                return listNode(m.group(1), "content");
            }
            m = LIST_LUA_VAR.matcher(expr);
            if (m.find()) {
                return listNode(m.group(1), "var:" + m.group(2));
            }
            m = CUM_LUA.matcher(expr);
            if (m.find()) {
                return cumNode(m.group(1), m.group(2), m.group(3));
            }
        }
        if (groovy) {
            if (expr.contains("wouldHitBlock")) {
                m = L3_GROOVY.matcher(expr);
                if (m.find()) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("type", "l3_aggregate");
                    n.put("list_name", m.group(1));
                    return n;
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("type", "l3_aggregate");
                return n;
            }
            m = LIST_GROOVY_VAL.matcher(expr);
            if (m.find()) {
                return listNode(m.group(1), "var:" + m.group(2));
            }
            m = LIST_GROOVY.matcher(expr);
            if (m.find()) {
                return listNode(m.group(1), "content");
            }
            m = CUM_GROOVY.matcher(expr);
            if (m.find()) {
                return cumNode(m.group(1), m.group(2), m.group(3));
            }
        }
        return null;
    }

    private static Map<String, Object> listNode(String listName, String valueSource) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "list_match");
        n.put("list_name", listName);
        n.put("value_source", valueSource);
        return n;
    }

    private static Map<String, Object> cumNode(String name, String opSymbol, String threshold) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("type", "cumulative");
        n.put("cumulative_name", name);
        n.put("compare", symbolToCompare(opSymbol));
        n.put("threshold", Long.parseLong(threshold));
        return n;
    }

    private static String symbolToCompare(String sym) {
        return switch (sym) {
            case ">" -> "gt";
            case ">=" -> "gte";
            case "<" -> "lt";
            case "<=" -> "lte";
            case "==" -> "eq";
            default -> "gte";
        };
    }

    @SuppressWarnings("unchecked")
    private static String inferRecipe(Map<String, Object> condition, String layer) {
        if (condition == null) {
            return null;
        }
        String type = condition.get("type") != null ? String.valueOf(condition.get("type")) : null;
        if ("list_match".equals(type)) {
            return "gateway".equals(layer) ? "gateway.list.deny" : "cloud.list.deny";
        }
        if ("cumulative".equals(type)) {
            return "gateway".equals(layer) ? "gateway.cum.rate_limit" : "cloud.cum.rate_limit";
        }
        if ("l3_aggregate".equals(type)) {
            return "cloud.l3.aggregate";
        }
        if ("and".equals(condition.get("op")) || "or".equals(condition.get("op"))) {
            List<Map<String, Object>> children = (List<Map<String, Object>>) condition.get("children");
            if (children != null && children.size() == 1) {
                return inferRecipe(children.get(0), layer);
            }
        }
        return null;
    }
}
