package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.policy.MatchContext;
import java.util.List;
import java.util.Map;
import io.virbius.engine.config.TenantAwareTaskExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptRunnerBindTest {

    private RuleCache cache;
    private PromptLlmClient llmClient;
    private PromptRunner runner;

    @BeforeEach
    void setUp() {
        cache = mock(RuleCache.class);
        llmClient = mock(PromptLlmClient.class);
        PromptLlmProperties props =
                new PromptLlmProperties("http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                        "<|im_start|>", "", null,
                        Map.of("Jailbreak", "Rule_201", "Violent", "Rule_202"));
        runner = new PromptRunner(cache, props, llmClient, new PromptAuditJsonParser(new ObjectMapper()),
                mock(TenantAwareTaskExecutor.class), mock(AsyncActionHandler.class));
    }

    @Test
    void skipsLlmWhenNoBindMatchedPromptRules() {
        RuleEntry routeOnly = promptRule(
                "Rule_201",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("sse"))));
        when(cache.rulesForTenant("default")).thenReturn(List.of(routeOnly));

        MatchContext ctx = MatchContext.withBind(
                "hello", null, null, null, "sess", Map.of(), "chat", "/v1/chat/completions");

        List<SignalDto> signals = runner.run("default", ctx);

        assertTrue(signals.isEmpty());
        verify(llmClient, never()).complete(anyString());
    }

    @Test
    void triggersRuleByJsonCategoryMapping() {
        RuleEntry global = promptRule("Rule_202", Map.of("bind_scope", "global"));
        RuleEntry routeChat = promptRule(
                "Rule_201",
                Map.of("bind_scope", "route", "bind_ref", Map.of("scenes", List.of("chat"))));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global, routeChat));
        when(llmClient.complete(anyString()))
                .thenReturn("{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Jailbreak\"}");

        MatchContext ctx = MatchContext.withBind(
                "hack me", null, null, null, "sess", Map.of(), "chat", "/v1/chat/completions");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("Rule_201", signals.get(0).ruleId());
    }

    @Test
    void triggersRuleByQwen3GuardNativeFormat() {
        RuleEntry global = promptRule("Rule_202", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global));
        when(llmClient.complete(anyString()))
                .thenReturn("Safety: Unsafe\nCategories: Violent");

        MatchContext ctx = MatchContext.withBind(
                "kill", null, null, null, "sess", Map.of(), "chat", "/v1/chat/completions");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("Rule_202", signals.get(0).ruleId());
    }

    @Test
    void fallsBackToFirstRuleWhenCategoryUnmapped() {
        RuleEntry fallback = promptRule("Rule_999", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(fallback));
        when(llmClient.complete(anyString()))
                .thenReturn("{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"UnknownCategory\"}");

        MatchContext ctx = MatchContext.withBind(
                "test", null, null, null, "sess", Map.of(), "chat", "/v1/chat/completions");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("Rule_999", signals.get(0).ruleId());
    }

    private static RuleEntry promptRule(String ruleId, Map<String, Object> scope) {
        return new RuleEntry(
                "default",
                ruleId,
                1,
                "cloud",
                "prompt",
                "TEST",
                100,
                "deny",
                "dry_run",
                0,
                "dry_run",
                "body",
                scope,
                false,
                null);
    }
}
