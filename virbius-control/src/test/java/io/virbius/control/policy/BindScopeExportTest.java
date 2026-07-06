package io.virbius.control.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.policy.ValueSource;
import io.virbius.policy.ValueSourceKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BindScopeExportTest {

    @Test
    void ingestTargetsDefaultWhenNoValueSource() {
        List<Map<String, Object>> targets = BindScopeExport.ingestTargetsFromRules(List.of(
                new BindScopeExport.RuleBindingSource(null, Map.of("bind_scope", "global"))));
        assertEquals(1, targets.size());
        assertEquals("default", targets.get(0).get("kind"));
    }

    @Test
    void ingestTargetsDedupesDefault() {
        List<Map<String, Object>> targets = BindScopeExport.ingestTargetsFromRules(List.of(
                new BindScopeExport.RuleBindingSource(null, Map.of()),
                new BindScopeExport.RuleBindingSource(null, Map.of())));
        assertEquals(1, targets.size());
    }

    @Test
    void bindEntryFromScope() {
        Map<String, Object> entry = BindScopeExport.bindEntry(Map.of(
                "bind_scope", "route",
                "bind_ref", Map.of("scenes", List.of("chat"))));
        assertEquals("route", entry.get("bind_scope"));
        assertTrue(entry.containsKey("bind_ref"));
    }

    @Test
    void valueSourceTargetVar() {
        Map<String, Object> t = BindScopeExport.valueSourceTarget(new ValueSource(ValueSourceKind.VAR, "app_id", null));
        assertEquals("var", t.get("kind"));
        assertEquals("app_id", t.get("ref"));
    }
}
