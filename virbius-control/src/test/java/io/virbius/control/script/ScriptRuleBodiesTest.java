package io.virbius.control.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ScriptRuleBodiesTest {

    @Test
    void normalizesEscapedNewlines() {
        String raw = "function decide(ctx)\\n  return true\\nend";
        String out = ScriptRuleBodies.normalizeEscapedNewlines(raw);
        assertTrue(out.contains("\n  return true"));
        assertFalse(out.contains("\\n"));
    }

    @Test
    void rejectsJsonDslMapForLua() {
        Map<String, Object> body = Map.of("list_type", "deny", "keywords", java.util.List.of("bad"));
        assertThrows(IllegalArgumentException.class, () -> ScriptRuleBodies.asExecutableScript(body, "lua"));
    }

    @Test
    void rejectsJsonDslStringForLua() {
        String body = "{\"list_type\":\"deny\",\"keywords\":[\"x\"]}";
        assertThrows(IllegalArgumentException.class, () -> ScriptRuleBodies.asExecutableScript(body, "lua"));
    }

    @Test
    void artifactExportSkipsLegacyJsonDsl() {
        String body = "{\"keywords\":[],\"list_type\":\"deny\"}";
        assertEquals("", ScriptRuleBodies.asArtifactScript(body, "lua"));
    }

    @Test
    void acceptsValidLuaScript() {
        String body =
                """
                -- virbius:generated v1
                function decide(ctx)
                  return listMatch('deny_keyword', ctx.content)
                end""";
        assertEquals(body, ScriptRuleBodies.asExecutableScript(body, "lua"));
    }
}
