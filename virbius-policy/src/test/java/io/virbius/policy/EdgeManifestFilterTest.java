package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeManifestFilterTest {

    @Test
    void globalRuleIncludedForEveryApp() {
        Map<String, Object> global = Map.of("bind_scope", "global");
        assertTrue(EdgeManifestFilter.includesForApp(global, "beta"));
        assertTrue(EdgeManifestFilter.includesForApp(global, "medical-prod"));
        assertTrue(EdgeManifestFilter.includesForApp(Map.of(), "beta"));
    }

    @Test
    void serviceRuleFilteredByAppIds() {
        Map<String, Object> scope =
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("medical-prod")));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "beta"));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "medical-prod"));
    }

    @Test
    void toolRuleIncludedWhenNoAppIdFilter() {
        Map<String, Object> scope = Map.of(
                "bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("read_file")));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "beta"));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "medical-prod"));
    }

    @Test
    void toolRuleFilteredByAppIds() {
        Map<String, Object> scope = Map.of(
                "bind_scope", "tool", "bind_ref",
                Map.of("tool_names", List.of("read_file"), "app_ids", List.of("medical-prod")));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "beta"));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "medical-prod"));
    }

    @Test
    void collectAppIdsFromServiceBinds() {
        List<Map<String, Object>> scopes = List.of(
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("extra-app"))),
                Map.of("bind_scope", "global"));
        assertEquals(List.of("extra-app"), EdgeManifestFilter.collectAppIds(scopes));
    }
}
