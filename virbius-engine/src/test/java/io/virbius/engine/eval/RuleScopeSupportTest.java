package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.MatchContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleScopeSupportTest {

    @Test
    void toolScopeMatchesByToolName() {
        RuleEntry rule = new RuleEntry(
                "default",
                "Rule_201",
                1,
                "cloud",
                "prompt",
                "X",
                100,
                "deny",
                "dry_run",
                0,
                "dry_run",
                "body",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", java.util.List.of("read_file"))),
                false,
                null);
        MatchContext ctx = MatchContext.forToolCall("x", null, null, null, null, Map.of("app_id", "test"), "read_file");
        assertTrue(RuleScopeSupport.matchesBind(rule, ctx));
    }

    @Test
    void toolScopeRejectsOtherTool() {
        RuleEntry rule = new RuleEntry(
                "default",
                "Rule_201",
                1,
                "cloud",
                "prompt",
                "X",
                100,
                "deny",
                "dry_run",
                0,
                "dry_run",
                "body",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", java.util.List.of("write_file"))),
                false,
                null);
        MatchContext ctx = MatchContext.forToolCall("x", null, null, null, null, Map.of("app_id", "test"), "read_file");
        assertFalse(RuleScopeSupport.matchesBind(rule, ctx));
    }

    @Test
    void legacyL3MetaRuleId() {
        assertTrue(LegacyPolicyRules.isDeprecatedMetaRule("cloud_groovy_l3"));
        assertFalse(LegacyPolicyRules.isDeprecatedMetaRule("Rule_201"));
    }
}
