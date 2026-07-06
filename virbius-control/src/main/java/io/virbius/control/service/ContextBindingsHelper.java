package io.virbius.control.service;

import io.virbius.control.domain.ContextVarBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ContextBindingsHelper {

    private ContextBindingsHelper() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> bindingsBlock(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Object cb = metadata.get("context_bindings");
        if (!(cb instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Object ver = map.get("version");
        out.put("version", ver != null ? ver : Integer.valueOf(1));
        Object vars = map.get("vars");
        if (vars instanceof Map<?, ?> varMap) {
            Map<String, Object> varsOut = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : varMap.entrySet()) {
                if (e.getKey() != null && e.getValue() instanceof Map<?, ?> def) {
                    varsOut.put(e.getKey().toString(), def);
                }
            }
            out.put("vars", varsOut);
        }
        return out;
    }

    public static List<ContextVarBinding> parseBindings(Map<String, Object> metadata) {
        Map<String, Object> block = bindingsBlock(metadata);
        if (block.isEmpty()) {
            return List.of();
        }
        Object vars = block.get("vars");
        if (!(vars instanceof Map<?, ?> varMap)) {
            return List.of();
        }
        List<ContextVarBinding> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : varMap.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            String logical = e.getKey().toString().trim();
            if (logical.isEmpty()) {
                continue;
            }
            if (e.getValue() instanceof Map<?, ?> def) {
                @SuppressWarnings("unchecked")
                Map<String, Object> defMap = (Map<String, Object>) def;
                out.add(ContextVarBinding.fromMap(logical, defMap));
            }
        }
        return List.copyOf(out);
    }

    public static Map<String, Object> toMetadataBlock(List<ContextVarBinding> bindings) {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (ContextVarBinding b : bindings) {
            vars.put(b.logical(), b.toMap());
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("version", 1);
        block.put("vars", vars);
        return block;
    }

    public static void validateVarListEntry(String entry, Set<String> declaredLogicalNames) {
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq >= entry.length() - 1) {
            throw new IllegalArgumentException("var entry must be logical_name=value, e.g. app_id=beta");
        }
        String logical = entry.substring(0, eq).trim();
        String value = entry.substring(eq + 1).trim();
        if (logical.isEmpty() || value.isEmpty()) {
            throw new IllegalArgumentException("var entry must be logical_name=value");
        }
        if (!logical.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid logical name: " + logical);
        }
        if (!declaredLogicalNames.isEmpty() && !declaredLogicalNames.contains(logical)) {
            throw new IllegalArgumentException("logical name not declared in context_bindings: " + logical);
        }
    }

    public static Set<String> logicalNames(Map<String, Object> metadata) {
        return parseBindings(metadata).stream().map(ContextVarBinding::logical).collect(Collectors.toSet());
    }
}
