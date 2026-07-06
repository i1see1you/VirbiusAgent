package io.virbius.policy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Gateway rule binding: global / service / route. See docs/DESIGN.md §11.4 */
public final class BindScope {

    public static final String GLOBAL = "global";
    public static final String SERVICE = "service";
    public static final String ROUTE = "route";

    private BindScope() {}

    public static String scopeFromRuleScope(Map<String, Object> scope) {
        if (scope == null || scope.isEmpty()) {
            return GLOBAL;
        }
        Object raw = scope.get("bind_scope");
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim().toLowerCase(Locale.ROOT);
        }
        return GLOBAL;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> bindRefFromScope(Map<String, Object> scope) {
        if (scope == null) {
            return Map.of();
        }
        Object ref = scope.get("bind_ref");
        if (ref instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        Object scenes = scope.get("scenes");
        if (scenes instanceof List<?> list && !list.isEmpty()) {
            legacy.put("scenes", list);
        }
        return legacy;
    }

    public static boolean matches(JsonNode body, MatchContext ctx) {
        if (body == null) {
            return true;
        }
        String scope = text(body, "bind_scope");
        if (scope == null || scope.isBlank()) {
            scope = GLOBAL;
        }
        JsonNode ref = body.get("bind_ref");
        return matches(scope, ref, ctx);
    }

    public static boolean matches(String bindScope, Map<String, Object> bindRef, MatchContext ctx) {
        JsonNode refNode = bindRef == null || bindRef.isEmpty() ? null : JsonMapperHelper.toJsonNode(bindRef);
        return matches(bindScope, refNode, ctx);
    }

    public static boolean matches(String bindScope, JsonNode bindRef, MatchContext ctx) {
        String scope = bindScope != null ? bindScope.trim().toLowerCase(Locale.ROOT) : GLOBAL;
        return switch (scope) {
            case SERVICE -> matchesService(bindRef, ctx);
            case ROUTE -> matchesRoute(bindRef, ctx);
            default -> true;
        };
    }

    private static boolean matchesService(JsonNode ref, MatchContext ctx) {
        if (ref == null || ref.isEmpty()) {
            return false;
        }
        List<String> appIds = stringList(ref.get("app_ids"));
        if (appIds.isEmpty()) {
            return false;
        }
        String appId = ctx.vars() != null ? ctx.vars().get("app_id") : null;
        if (appId == null || appId.isBlank()) {
            return false;
        }
        for (String id : appIds) {
            if (appId.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRoute(JsonNode ref, MatchContext ctx) {
        if (ref == null) {
            return false;
        }
        List<String> scenes = stringList(ref.get("scenes"));
        if (scenes.isEmpty()) {
            return false;
        }
        String scene = ctx.scene();
        if (scene == null || scene.isBlank()) {
            return false;
        }
        for (String s : scenes) {
            if ("*".equals(s) || scene.equals(s)) {
                return true;
            }
        }
        return false;
    }

    /** Validates URI pattern grammar shared by gateway.routes and scene_registry.uris. */
    public static void validateUriPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("uri pattern required");
        }
        String pat = pattern.trim();
        if (!pat.startsWith("/")) {
            throw new IllegalArgumentException("uri pattern must start with /: " + pattern);
        }
        int star = pat.indexOf('*');
        if (star >= 0 && star != pat.length() - 1) {
            throw new IllegalArgumentException("uri wildcard * allowed only as suffix: " + pattern);
        }
        if (pat.contains("**")) {
            throw new IllegalArgumentException("uri pattern must not contain **: " + pattern);
        }
    }

    /**
     * True when every request path matching {@code rulePattern} would also match {@code gatewayPattern}
     * (gateway entry covers the rule/scene URI constraint).
     */
    public static boolean patternCovers(String gatewayPattern, String rulePattern) {
        validateUriPattern(gatewayPattern);
        validateUriPattern(rulePattern);
        String g = normalizeUri(gatewayPattern);
        String r = normalizeUri(rulePattern);
        if (g == null || r == null) {
            return false;
        }
        boolean gWildcard = g.endsWith("*");
        boolean rWildcard = r.endsWith("*");
        if (!rWildcard) {
            return uriMatches(r, g);
        }
        if (!gWildcard) {
            return false;
        }
        String gPrefix = g.substring(0, g.length() - 1);
        String rPrefix = r.substring(0, r.length() - 1);
        return rPrefix.startsWith(gPrefix);
    }

    public static boolean coveredByAny(String rulePattern, List<String> gatewayPatterns) {
        if (gatewayPatterns == null || gatewayPatterns.isEmpty()) {
            return false;
        }
        for (String gatewayPattern : gatewayPatterns) {
            if (gatewayPattern != null && patternCovers(gatewayPattern, rulePattern)) {
                return true;
            }
        }
        return false;
    }

    public static boolean uriMatches(String routeUri, String pattern) {
        if (routeUri == null || pattern == null || pattern.isBlank()) {
            return false;
        }
        String uri = normalizeUri(routeUri);
        String pat = normalizeUri(pattern);
        if (uri == null || pat == null) {
            return false;
        }
        if (pat.endsWith("*")) {
            String prefix = pat.substring(0, pat.length() - 1);
            return uri.startsWith(prefix);
        }
        return uri.equals(pat);
    }

    public static String normalizeUri(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        int hash = s.indexOf('#');
        if (hash >= 0) {
            s = s.substring(0, hash);
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode item : node) {
            if (item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }

    /** Minimal map→JsonNode without pulling ObjectMapper into hot path callers. */
    static final class JsonMapperHelper {
        private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();

        static JsonNode toJsonNode(Map<String, Object> map) {
            return MAPPER.valueToTree(map);
        }
    }
}
