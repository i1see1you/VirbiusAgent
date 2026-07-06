package io.virbius.control.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Derived variable computed from a Lua expression referencing request-factor vars. */
public record ExtendedVar(String logical, String expr, Scope scope, String deletedAt) {

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_SERVICE = "service";
    public static final String SCOPE_ROUTE = "route";

    public record Scope(String bindScope, List<String> appIds, List<String> scenes) {
        public Scope {
            if (bindScope == null || bindScope.isBlank()) {
                bindScope = SCOPE_GLOBAL;
            }
            bindScope = bindScope.trim().toLowerCase(Locale.ROOT);
            appIds = appIds != null ? List.copyOf(appIds) : List.of();
            scenes = scenes != null ? List.copyOf(scenes) : List.of();
        }
    }

    public ExtendedVar(String logical, String expr, Scope scope) {
        this(logical, expr, scope, null);
    }

    public ExtendedVar {
        boolean hasDeletedAt = deletedAt != null && !deletedAt.isBlank();
        if (!hasDeletedAt) {
            if (logical == null || logical.isBlank()) {
                throw new IllegalArgumentException("logical name required");
            }
            logical = logical.trim();
            if (!logical.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("logical name must be lowercase snake_case: " + logical);
            }
            if (expr == null || expr.isBlank()) {
                throw new IllegalArgumentException("expr required for " + logical);
            }
            expr = expr.trim();
        }
        if (scope == null) {
            scope = new Scope(SCOPE_GLOBAL, List.of(), List.of());
        }
    }

    public boolean isDeleted() {
        return deletedAt != null && !deletedAt.isBlank();
    }

    @SuppressWarnings("unchecked")
    public static ExtendedVar fromMap(String logical, Map<String, Object> def) {
        if (def == null) {
            throw new IllegalArgumentException("extended var def required for " + logical);
        }
        String expr = def.get("expr") != null ? def.get("expr").toString() : null;
        Scope scope = parseScope(def.get("scope"));
        String deletedAt = def.get("deleted_at") != null ? def.get("deleted_at").toString() : null;
        return new ExtendedVar(logical, expr, scope, deletedAt);
    }

    @SuppressWarnings("unchecked")
    private static Scope parseScope(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            return new Scope(SCOPE_GLOBAL, List.of(), List.of());
        }
        String bs = m.get("bind_scope") != null ? m.get("bind_scope").toString() : null;
        List<String> appIds = Collections.emptyList();
        List<String> scenes = Collections.emptyList();
        if (m.get("app_ids") instanceof List<?> appList) {
            appIds = appList.stream().map(Object::toString).toList();
        }
        if (m.get("scenes") instanceof List<?> sceneList) {
            scenes = sceneList.stream().map(Object::toString).toList();
        }
        return new Scope(bs, appIds, scenes);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expr", expr);
        if (deletedAt != null) {
            m.put("deleted_at", deletedAt);
        }
        if (scope != null && !SCOPE_GLOBAL.equals(scope.bindScope())) {
            Map<String, Object> scopeMap = new LinkedHashMap<>();
            scopeMap.put("bind_scope", scope.bindScope());
            if (!scope.appIds().isEmpty()) {
                scopeMap.put("app_ids", scope.appIds());
            }
            if (!scope.scenes().isEmpty()) {
                scopeMap.put("scenes", scope.scenes());
            }
            m.put("scope", scopeMap);
        }
        return m;
    }
}
