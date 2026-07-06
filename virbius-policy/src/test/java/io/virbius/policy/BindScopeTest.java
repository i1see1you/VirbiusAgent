package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BindScopeTest {

    @Test
    void globalAlwaysMatches() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "general_chat", "/v1/chat/completions");
        assertTrue(BindScope.matches("global", java.util.Map.of(), ctx));
    }

    @Test
    void routeSceneExactMatch() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "chat", null);
        assertTrue(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("chat")), ctx));
        assertFalse(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("sse")), ctx));
    }

    @Test
    void routeSceneWildcard() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "any_scene", null);
        assertTrue(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("*")), ctx));
    }

    @Test
    void routeSceneNoScenes() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "chat", null);
        assertFalse(BindScope.matches("route", java.util.Map.of(), ctx));
    }

    @Test
    void routeSceneMissingContextScene() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, null, "", null);
        assertFalse(BindScope.matches("route", java.util.Map.of("scenes", java.util.List.of("chat")), ctx));
    }

    @Test
    void serviceMatchesAppIds() {
        MatchContext ctx = MatchContext.withBind(
                "hi", "u1", null, null, null, java.util.Map.of("app_id", "medical-prod"), "x", "/v1/chat/completions");
        assertTrue(BindScope.matches(
                "service", java.util.Map.of("app_ids", java.util.List.of("beta", "medical-prod")), ctx));
        assertFalse(BindScope.matches("service", java.util.Map.of("app_ids", java.util.List.of("beta")), ctx));
    }

    @Test
    void patternCoversPrefixAndExact() {
        assertTrue(BindScope.patternCovers("/v1/chat/*", "/v1/chat/completions"));
        assertTrue(BindScope.patternCovers("/v1/chat/*", "/v1/chat/*"));
        assertTrue(BindScope.patternCovers("/v1/*", "/v1/chat/*"));
        assertFalse(BindScope.patternCovers("/v1/chat/completions", "/v1/chat/*"));
        assertFalse(BindScope.patternCovers("/v1/chat/*", "/v1/*"));
    }

    @Test
    void coveredByAnyGatewayList() {
        assertTrue(BindScope.coveredByAny(
                "/v1/chat/completions", java.util.List.of("/v1/other", "/v1/chat/*")));
        assertFalse(BindScope.coveredByAny("/v1/embeddings", java.util.List.of("/v1/chat/*")));
    }
}
