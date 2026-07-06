package io.virbius.control.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Logical variable → HTTP/body source mapping with optional scope. Supports logical deletion. */
public record ContextVarBinding(
        String logical, String from, String name, String field, Scope scope, String deletedAt) {

    public static final String FROM_QUERY = "query";
    public static final String FROM_HEADER = "header";
    public static final String FROM_SUBJECT = "subject";
    public static final String FROM_NETWORK = "network";

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

    public ContextVarBinding(String logical, String from, String name, String field, Scope scope) {
        this(logical, from, name, field, scope, null);
    }

    public ContextVarBinding {
        boolean hasDeletedAt = deletedAt != null && !deletedAt.isBlank();
        if (!hasDeletedAt) {
            if (logical == null || logical.isBlank()) {
                throw new IllegalArgumentException("logical name required");
            }
            logical = logical.trim();
            if (!logical.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("logical name must be lowercase snake_case: " + logical);
            }
            if (from == null || from.isBlank()) {
                throw new IllegalArgumentException("from required for " + logical);
            }
            from = from.trim().toLowerCase(Locale.ROOT);
            switch (from) {
                case FROM_QUERY, FROM_HEADER -> {
                    if (name == null || name.isBlank()) {
                        name = logical;
                    } else {
                        name = name.trim();
                    }
                }
                case FROM_SUBJECT, FROM_NETWORK -> {
                    if (field == null || field.isBlank()) {
                        field = logical;
                    } else {
                        field = field.trim().toLowerCase(Locale.ROOT);
                    }
                }
                default -> throw new IllegalArgumentException("from must be query, header, subject, or network");
            }
            if (scope == null) {
                scope = new Scope(SCOPE_GLOBAL, List.of(), List.of());
            }
        }
    }

    public boolean isDeleted() {
        return deletedAt != null && !deletedAt.isBlank();
    }

    @SuppressWarnings("unchecked")
    public static ContextVarBinding fromMap(String logical, Map<String, Object> def) {
        if (def == null) {
            throw new IllegalArgumentException("binding def required for " + logical);
        }
        String from = def.get("from") != null ? def.get("from").toString() : null;
        String name = def.get("name") != null ? def.get("name").toString() : null;
        String field = def.get("field") != null ? def.get("field").toString() : null;
        Scope scope = parseScope(def.get("scope"));
        String deletedAt = def.get("deleted_at") != null ? def.get("deleted_at").toString() : null;
        return new ContextVarBinding(logical, from, name, field, scope, deletedAt);
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
        m.put("from", from);
        if (name != null && !name.isBlank()) {
            m.put("name", name);
        }
        if (field != null && !field.isBlank()) {
            m.put("field", field);
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
        if (deletedAt != null) {
            m.put("deleted_at", deletedAt);
        }
        return m;
    }
}
