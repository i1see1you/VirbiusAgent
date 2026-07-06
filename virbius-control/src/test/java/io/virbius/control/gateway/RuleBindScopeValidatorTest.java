package io.virbius.control.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBindScopeValidatorTest {

    private static final Map<String, Object> SCENE_METADATA = Map.of(
            "scene_registry",
            Map.of(
                    "version",
                    1,
                    "scenes",
                    Map.of(
                            "beta_chat", Map.of("app_id", "beta", "default", true),
                            "beta_sse", Map.of("app_id", "beta", "default", false))));

    @Test
    void acceptsSceneInRegistry() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("beta_chat"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void rejectsUnknownScene() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("unknown"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void rejectsEmptyScenes() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of()),
                Map.of(),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void acceptsWildcardSceneWithoutRegistryCheck() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("*"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void skipsWhenNoScope() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1", "poc-default", "cloud", "groovy", "X", 100, "deny", null, Map.of(), null, null, null, null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void acceptsMultipleScenes() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "r1",
                "poc-default",
                "cloud",
                "groovy",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref",
                        Map.of("scenes", List.of("beta_chat", "beta_sse"))),
                Map.of(),
                null,
                null,
                null,
                null);
        assertDoesNotThrow(() -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void edgeServiceBindRequiresKnownAppId() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r1",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "service", "bind_ref", Map.of("app_ids", List.of("unknown-app"))),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }

    @Test
    void edgeRouteBindRejected() {
        UpsertRuleRequest req = new UpsertRuleRequest(
                "edge_r2",
                "poc-default",
                "edge",
                "lua-dsl",
                "X",
                100,
                "deny",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("beta_chat"))),
                Map.of("list_type", "deny", "keywords", List.of("x")),
                null,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> RuleBindScopeValidator.validateRouteScenes(req, SCENE_METADATA));
    }
}
