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
    void toolExactMatch() {
        MatchContext ctx = MatchContext.forToolCall("hi", "u1", null, null, null, java.util.Map.of("app_id", "test"), "read_file");
        assertTrue(BindScope.matches("tool", java.util.Map.of("tool_names", java.util.List.of("read_file")), ctx));
        assertFalse(BindScope.matches("tool", java.util.Map.of("tool_names", java.util.List.of("write_file")), ctx));
    }

    @Test
    void toolWildcard() {
        MatchContext ctx = MatchContext.forToolCall("hi", "u1", null, null, null, java.util.Map.of("app_id", "test"), "any_tool");
        assertTrue(BindScope.matches("tool", java.util.Map.of("tool_names", java.util.List.of("*")), ctx));
    }

    @Test
    void toolNoToolNamesPassesIfNoAppIds() {
        MatchContext ctx = MatchContext.forToolCall("hi", "u1", null, null, null, java.util.Map.of("app_id", "test"), "read_file");
        assertTrue(BindScope.matches("tool", java.util.Map.of(), ctx));
    }

    @Test
    void toolMissingContextToolName() {
        MatchContext ctx = MatchContext.forToolCall("hi", "u1", null, null, null, java.util.Map.of("app_id", "test"), "");
        assertTrue(BindScope.matches("tool", java.util.Map.of(), ctx));
    }

    @Test
    void toolWithAppIdsFilter() {
        MatchContext ctx = MatchContext.forToolCall("hi", "u1", null, null, null, java.util.Map.of("app_id", "medical-prod"), "read_file");
        assertTrue(BindScope.matches("tool", java.util.Map.of("tool_names", java.util.List.of("read_file"), "app_ids", java.util.List.of("beta", "medical-prod")), ctx));
        assertFalse(BindScope.matches("tool", java.util.Map.of("tool_names", java.util.List.of("read_file"), "app_ids", java.util.List.of("beta")), ctx));
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
