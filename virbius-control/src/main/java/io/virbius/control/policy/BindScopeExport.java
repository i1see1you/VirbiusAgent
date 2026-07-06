package io.virbius.control.policy;

import io.virbius.policy.BindScope;
import io.virbius.policy.ValueSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BindScopeExport {

    private BindScopeExport() {}

    public static Map<String, Object> bindRefMap(Map<String, Object> scope) {
        return BindScope.bindRefFromScope(scope);
    }

    public static String bindScope(Map<String, Object> scope) {
        return BindScope.scopeFromRuleScope(scope);
    }

    public static void putBindFields(Map<String, Object> block, Map<String, Object> scope) {
        String bs = bindScope(scope);
        block.put("bind_scope", bs);
        Map<String, Object> ref = bindRefMap(scope);
        if (!ref.isEmpty()) {
            block.put("bind_ref", ref);
        }
    }

    public static Map<String, Object> bindEntry(Map<String, Object> scope) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("bind_scope", bindScope(scope));
        Map<String, Object> ref = bindRefMap(scope);
        if (!ref.isEmpty()) {
            e.put("bind_ref", ref);
        }
        return e;
    }

    public static List<Map<String, Object>> ingestTargetsFromRules(List<RuleBindingSource> rules) {
        List<Map<String, Object>> targets = new ArrayList<>();
        boolean seenDefault = false;
        for (RuleBindingSource rule : rules) {
            if (rule.valueSource() == null) {
                if (!seenDefault) {
                    targets.add(Map.of("kind", "default"));
                    seenDefault = true;
                }
            } else {
                Map<String, Object> t = valueSourceTarget(rule.valueSource());
                if (!targets.contains(t)) {
                    targets.add(t);
                }
            }
        }
        if (targets.isEmpty()) {
            targets.add(Map.of("kind", "default"));
        }
        return targets;
    }

    public static Map<String, Object> valueSourceTarget(ValueSource vs) {
        Map<String, Object> m = new LinkedHashMap<>();
        String kind =
                switch (vs.kind()) {
                    case REQUEST_FIELD -> "request_field";
                    case VAR -> "var";
                    case HEADER -> "header";
                    case QUERY -> "query";
                    case CONTENT -> "content";
                    case LITERAL -> "literal";
                    default -> "default";
                };
        m.put("kind", kind);
        if (vs.ref() != null) {
            m.put("ref", vs.ref());
        }
        if (vs.literalValue() != null) {
            m.put("value", vs.literalValue());
        }
        return m;
    }

    public record RuleBindingSource(ValueSource valueSource, Map<String, Object> scope) {}
}
