package io.virbius.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ValueResolverVarDimensionTest {

    @Test
    void resolveVarDimensionFromContextVars() {
        MatchContext ctx = MatchContext.withBind(
                "hi", "u1", null, null, null, Map.of("app_id", "acme"), "general_chat", "/v1/chat");
        assertEquals("acme", ValueResolver.resolve("var:app_id", null, ctx).orElseThrow());
    }

    @Test
    void emptyWhenVarMissing() {
        MatchContext ctx = MatchContext.withBind("hi", "u1", null, null, null, Map.of(), "x", "/v1/chat");
        assertTrue(ValueResolver.resolve("var:app_id", null, ctx).isEmpty());
    }
}
