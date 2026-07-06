package io.virbius.control.service;

import io.virbius.control.domain.ExtendedVar;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class ExtendedVarsHelper {

    private ExtendedVarsHelper() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> varsBlock(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Object ev = metadata.get("extended_vars");
        if (!(ev instanceof Map<?, ?> map)) {
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

    public static List<ExtendedVar> parseAllVars(Map<String, Object> metadata) {
        Map<String, Object> block = varsBlock(metadata);
        if (block.isEmpty()) {
            return List.of();
        }
        Object vars = block.get("vars");
        if (!(vars instanceof Map<?, ?> varMap)) {
            return List.of();
        }
        List<ExtendedVar> out = new ArrayList<>();
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
                out.add(ExtendedVar.fromMap(logical, defMap));
            }
        }
        return List.copyOf(out);
    }

    public static List<ExtendedVar> parseVars(Map<String, Object> metadata) {
        return parseAllVars(metadata).stream()
                .filter(v -> !v.isDeleted())
                .toList();
    }

    public static Map<String, Object> toMetadataBlock(List<ExtendedVar> vars) {
        Map<String, Object> varsMap = new LinkedHashMap<>();
        for (ExtendedVar v : vars) {
            varsMap.put(v.logical(), v.toMap());
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("version", 1);
        block.put("vars", varsMap);
        return block;
    }

    public static Set<String> logicalNames(Map<String, Object> metadata) {
        return parseVars(metadata).stream().map(ExtendedVar::logical).collect(Collectors.toSet());
    }
}
