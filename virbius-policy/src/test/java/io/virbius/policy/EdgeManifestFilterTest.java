package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeManifestFilterTest {

    private static SceneRegistry registry() {
        return new SceneRegistry(
                false,
                false,
                List.of(
                        new SceneRegistry.SceneEntry(
                                "beta_chat", "beta", true, List.of("/v1/chat/completions"), 0, Map.of(), Map.of()),
                        new SceneRegistry.SceneEntry(
                                "medical-prod_clinical",
                                "medical-prod",
                                false,
                                List.of("/v1/chat/completions"),
                                10,
                                Map.of("mode", "clinical"),
                                Map.of())));
    }

    @Test
    void globalRuleIncludedForEveryApp() {
        SceneRegistry reg = registry();
        Map<String, Object> global = Map.of("bind_scope", "global");
        assertTrue(EdgeManifestFilter.includesForApp(global, "beta", reg));
        assertTrue(EdgeManifestFilter.includesForApp(global, "medical-prod", reg));
        assertTrue(EdgeManifestFilter.includesForApp(Map.of(), "beta", reg));
    }

    @Test
    void serviceRuleFilteredByAppIds() {
        SceneRegistry reg = registry();
        Map<String, Object> scope =
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("medical-prod")));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "beta", reg));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "medical-prod", reg));
    }

    @Test
    void routeRuleFilteredBySceneOwner() {
        SceneRegistry reg = registry();
        Map<String, Object> scope = Map.of(
                "bind_scope", "route", "bind_ref", Map.of("scenes", List.of("medical-prod_clinical")));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "beta", reg));
        assertTrue(EdgeManifestFilter.includesForApp(scope, "medical-prod", reg));
    }

    @Test
    void routeRuleWithUnregisteredSceneExcludedFromEdgeManifest() {
        SceneRegistry reg = registry();
        Map<String, Object> scope = Map.of(
                "bind_scope", "route", "bind_ref", Map.of("scenes", List.of("nonexistent_scene")));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "beta", reg));
        assertFalse(EdgeManifestFilter.includesForApp(scope, "medical-prod", reg));
    }

    @Test
    void collectAppIdsMergesRegistryAndServiceBinds() {
        SceneRegistry reg = registry();
        List<Map<String, Object>> scopes = List.of(
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("extra-app"))),
                Map.of("bind_scope", "global"));
        assertEquals(List.of("beta", "medical-prod", "extra-app"), EdgeManifestFilter.collectAppIds(reg, scopes));
    }
}
