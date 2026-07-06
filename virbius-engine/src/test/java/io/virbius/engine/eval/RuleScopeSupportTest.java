package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.engine.cache.RuleEntry;
import io.virbius.policy.MatchContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleScopeSupportTest {

    @Test
    void routeSceneBindMatchesPromptRule() {
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
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("chat"))),
                false,
                null);
        MatchContext ctx = MatchContext.withBind("x", null, null, null, null, Map.of(), "chat", null);
        assertTrue(RuleScopeSupport.matchesBind(rule, ctx));
    }

    @Test
    void routeSceneBindRejectsOtherScene() {
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
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("sse"))),
                false,
                null);
        MatchContext ctx = MatchContext.withBind("x", null, null, null, null, Map.of(), "chat", null);
        assertFalse(RuleScopeSupport.matchesBind(rule, ctx));
    }

    @Test
    void legacyL3MetaRuleId() {
        assertTrue(LegacyPolicyRules.isDeprecatedMetaRule("cloud_groovy_l3"));
        assertFalse(LegacyPolicyRules.isDeprecatedMetaRule("Rule_201"));
    }
}
