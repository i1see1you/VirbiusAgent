package io.virbius.control.ruleauthoring;

import io.virbius.control.domain.ContextVarBinding;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContextVarResolver {

    private ContextVarResolver() {}

    public static Map<String, String> resolve(
            List<ContextVarBinding> bindings,
            Map<String, String> headers,
            Map<String, String> query,
            Map<String, String> fixtureVars) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fixtureVars != null) {
            fixtureVars.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        if (bindings == null) {
            return out;
        }
        Map<String, String> h = headers != null ? headers : Map.of();
        Map<String, String> q = query != null ? query : Map.of();
        for (ContextVarBinding b : bindings) {
            String val = switch (b.from()) {
                case ContextVarBinding.FROM_HEADER -> h.get(headerKey(b.name()));
                case ContextVarBinding.FROM_QUERY -> q.get(b.name());
                case ContextVarBinding.FROM_SUBJECT -> h.get("X-Virbius-User-Id");
                case ContextVarBinding.FROM_NETWORK -> h.get("X-Forwarded-For");
                default -> null;
            };
            if (val != null && !val.isBlank()) {
                out.putIfAbsent(b.logical(), val.trim());
            }
        }
        return out;
    }

    private static String headerKey(String name) {
        if (name == null) {
            return "";
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("x-")) {
            return name;
        }
        return name;
    }
}
