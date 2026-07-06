package io.virbius.control.ruleauthoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Compiles in-memory condition AST to {@code decide(ctx)} script (not persisted as AST). */
public final class ConditionCompiler {

    public static final String GENERATED_MARKER = "virbius:generated v1";

    private ConditionCompiler() {}

    public static Map<String, Object> compile(String layer, String runtime, Map<String, Object> condition) {
        if (condition == null || condition.isEmpty()) {
            throw new IllegalArgumentException("condition required");
        }
        String rt = runtime != null ? runtime.trim().toLowerCase(Locale.ROOT) : "";
        boolean gateway = "gateway".equals(layer);
        boolean lua = "lua".equals(rt);
        boolean groovy = "groovy".equals(rt);
        if (!lua && !groovy) {
            throw new IllegalArgumentException("compile-condition supports runtime lua or groovy only");
        }
        if (isL3Root(condition)) {
            String script = compileL3Script(condition, groovy);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("script", script);
            out.put("warnings", List.of());
            return out;
        }
        String expr = compileExpr(condition, gateway, lua, groovy);
        List<String> warnings = new ArrayList<>();
        String script;
        if (lua) {
            script = "-- " + GENERATED_MARKER + "\nfunction decide(ctx)\n  return " + expr + "\nend";
        } else {
            script = "// " + GENERATED_MARKER + "\n" + "def decide(ctx) {\n  return " + expr + "\n}";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("script", script);
        out.put("warnings", warnings);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String compileExpr(Map<String, Object> node, boolean gateway, boolean lua, boolean groovy) {
        String type = str(node.get("type"));
        if (type == null || type.isBlank()) {
            String op = str(node.get("op"));
            if ("and".equals(op) || "or".equals(op)) {
                return compileGroup(op, (List<Map<String, Object>>) node.get("children"), gateway, lua, groovy);
            }
            throw new IllegalArgumentException("condition node requires type or op");
        }
        return switch (type) {
            case "list_match" -> compileListMatch(node, gateway, lua, groovy);
            case "cumulative" -> compileCumulative(node, gateway, lua, groovy);
            case "var_compare" -> compileVarCompare(node, lua, groovy);
            case "l3_aggregate" -> compileL3(node, groovy);
            default -> throw new IllegalArgumentException("unsupported condition type: " + type);
        };
    }

    private static String compileGroup(
            String op, List<Map<String, Object>> children, boolean gateway, boolean lua, boolean groovy) {
        if (children == null || children.isEmpty()) {
            throw new IllegalArgumentException("condition group requires children");
        }
        String join = "and".equals(op) ? " && " : " || ";
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> child : children) {
            parts.add(compileExpr(child, gateway, lua, groovy));
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        return "(" + String.join(join, parts) + ")";
    }

    private static String compileListMatch(Map<String, Object> node, boolean gateway, boolean lua, boolean groovy) {
        String listName = requireStr(node, "list_name");
        String valueSource = str(node.get("value_source"));
        if (valueSource == null || valueSource.isBlank() || "content".equals(valueSource)) {
            if (lua) {
                return "listMatch('" + esc(listName) + "', ctx.content)";
            }
            return "ctx.listMatch('" + esc(listName) + "')";
        }
        if (valueSource.startsWith("var:")) {
            String logical = valueSource.substring(4);
            if (lua) {
                return "listMatch('" + esc(listName) + "', ctx.var('" + esc(logical) + "'))";
            }
            return "ctx.listMatch('" + esc(listName) + "', ctx.var('" + esc(logical) + "'))";
        }
        throw new IllegalArgumentException("unsupported value_source: " + valueSource);
    }

    private static String compileCumulative(Map<String, Object> node, boolean gateway, boolean lua, boolean groovy) {
        String name = requireStr(node, "cumulative_name");
        String compare = str(node.get("compare"));
        if (compare == null || compare.isBlank()) {
            compare = "gte";
        }
        long threshold = number(node.get("threshold"), 1);
        String op = compareOp(compare);
        String call = lua ? "getCumulative('" + esc(name) + "')" : "ctx.getCumulative('" + esc(name) + "')";
        return call + " " + op + " " + threshold;
    }

    private static String compileVarCompare(Map<String, Object> node, boolean lua, boolean groovy) {
        String logical = requireStr(node, "logical");
        String op = compareOp(str(node.get("op")));
        String literal = requireStr(node, "literal");
        String var = lua ? "ctx.var('" + esc(logical) + "')" : "ctx.var('" + esc(logical) + "')";
        return var + " " + op + " '" + esc(literal) + "'";
    }

    private static boolean isL3Root(Map<String, Object> condition) {
        return "l3_aggregate".equals(str(condition.get("type")));
    }

    private static String compileL3Script(Map<String, Object> node, boolean groovy) {
        if (!groovy) {
            throw new IllegalArgumentException("l3_aggregate requires cloud groovy");
        }
        String listName = str(node.get("list_name"));
        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(GENERATED_MARKER).append("\n");
        sb.append("def decide(ctx) {\n");
        if (listName != null && !listName.isBlank()) {
            sb.append("  if (ctx.listMatch('").append(esc(listName)).append("')) return true\n");
        }
        sb.append("  if (!ctx.wouldHitBlock()) return false\n");
        sb.append("  return true\n");
        sb.append("}");
        return sb.toString();
    }

    private static String compileL3(Map<String, Object> node, boolean groovy) {
        throw new IllegalArgumentException("l3_aggregate must be root condition");
    }

    private static String compareOp(String compare) {
        return switch (compare != null ? compare.toLowerCase(Locale.ROOT) : "gte") {
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            case "eq" -> "==";
            default -> throw new IllegalArgumentException("unsupported compare: " + compare);
        };
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String requireStr(Map<String, Object> node, String key) {
        String v = str(node.get(key));
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(key + " required");
        }
        return v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static long number(Object o, long defaultVal) {
        if (o == null) {
            return defaultVal;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
