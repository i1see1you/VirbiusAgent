package io.virbius.control.ruleauthoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConditionCompilerParserTest {

    @Test
    void compilesAndParsesListMatchLua() {
        Map<String, Object> condition =
                Map.of("type", "list_match", "list_name", "deny_keyword", "value_source", "content");
        String script = String.valueOf(ConditionCompiler.compile("gateway", "lua", condition).get("script"));
        assertTrue(script.contains("listMatch('deny_keyword', ctx.content)"));
        Map<String, Object> parsed = ConditionParser.parse("gateway", "lua", script);
        assertTrue(Boolean.TRUE.equals(parsed.get("parseable")));
    }

    @Test
    void compilesAndParsesCumulativeGroovy() {
        Map<String, Object> condition = Map.of(
                "type", "cumulative", "cumulative_name", "user_req_1h", "compare", "gte", "threshold", 120);
        String script = String.valueOf(ConditionCompiler.compile("cloud", "groovy", condition).get("script"));
        Map<String, Object> parsed = ConditionParser.parse("cloud", "groovy", script);
        assertTrue(Boolean.TRUE.equals(parsed.get("parseable")));
        @SuppressWarnings("unchecked")
        Map<String, Object> leaf = (Map<String, Object>) parsed.get("condition");
        assertEquals("cumulative", leaf.get("type"));
    }

    @Test
    void compilesAndParsesAndGroup() {
        Map<String, Object> condition = Map.of(
                "op",
                "and",
                "children",
                List.of(
                        Map.of("type", "list_match", "list_name", "deny_keyword", "value_source", "content"),
                        Map.of(
                                "type",
                                "cumulative",
                                "cumulative_name",
                                "user_req_1h",
                                "compare",
                                "gte",
                                "threshold",
                                120)));
        String script = String.valueOf(ConditionCompiler.compile("gateway", "lua", condition).get("script"));
        Map<String, Object> parsed = ConditionParser.parse("gateway", "lua", script);
        assertTrue(Boolean.TRUE.equals(parsed.get("parseable")));
    }
}
