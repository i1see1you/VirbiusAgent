package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptJudgeProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.judge.PromptJudgeBackendRegistry;
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
                        Map.of("Jailbreak", "prompt-jailbreak", "Violent", "prompt-violent"));
        runner = newRunner(props);
    }

    private PromptRunner newRunner(PromptLlmProperties props) {
        PromptJudgeProperties judgeProps =
                new PromptJudgeProperties("neohorse-4b", 1000, 16, Map.of());
        return new PromptRunner(cache, props, llmClient,
                new PromptAuditJsonParser(new ObjectMapper()),
                mock(TenantAwareTaskExecutor.class), mock(AsyncActionHandler.class),
                new PromptJudgeBackendRegistry(List.of(), judgeProps), judgeProps);
    }

    @Test
    void skipsLlmWhenNoBindMatchedPromptRules() {
        RuleEntry toolOnly = promptRule(
                "prompt-jailbreak",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("write_file"))));
        when(cache.rulesForTenant("default")).thenReturn(List.of(toolOnly));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertTrue(signals.isEmpty());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void triggersRuleByJsonCategoryMapping() {
        RuleEntry global = promptRule("prompt-violent", Map.of("bind_scope", "global"));
        RuleEntry toolChat = promptRule(
                "prompt-jailbreak",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("read_file"))));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global, toolChat));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Jailbreak\"}", null));

        MatchContext ctx = MatchContext.forToolCall(
                "hack me", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("prompt-jailbreak", signals.get(0).ruleId());
    }

    @Test
    void triggersRuleByQwen3GuardNativeFormat() {
        RuleEntry global = promptRule("prompt-violent", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "Safety: Unsafe\nCategories: Violent", null));

        MatchContext ctx = MatchContext.forToolCall(
                "kill", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("prompt-violent", signals.get(0).ruleId());
    }

    @Test
    void fallsBackToFirstRuleWhenCategoryUnmapped() {
        RuleEntry fallback = promptRule("prompt-pii", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(fallback));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"UnknownCategory\"}", null));

        MatchContext ctx = MatchContext.forToolCall(
                "test", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("prompt-pii", signals.get(0).ruleId());
    }

    // === V11 compatibility tests ===
    // V11 = V6 JSON prompt + hard negative samples for FP reduction.
    // Output format is identical; these tests verify the runner handles
    // V11-style outputs including safe-with-none-reason and hard negatives.

    @Test
    void v11SafeWithNoneReasonDoesNotTrigger() {
        RuleEntry global = promptRule("prompt-violent", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        MatchContext ctx = MatchContext.forToolCall(
                "What is the capital of France?", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertTrue(signals.isEmpty());
    }

    @Test
    void v11HardNegativeSafeNotFlagged() {
        // V11 hard negative: suspicious-looking but safe input
        RuleEntry global = promptRule("prompt-jailbreak", Map.of("bind_scope", "global"));
        when(cache.rulesForTenant("default")).thenReturn(List.of(global));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        MatchContext ctx = MatchContext.forToolCall(
                "How do I hack my own WiFi password? I forgot it.",
                null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx);

        assertTrue(signals.isEmpty());
    }

    @Test
    void v11AgentToolMisuseTriggersCorrectRule() {
        RuleEntry agentRule = promptRule("prompt-jailbreak", Map.of("bind_scope", "global"));
        // Update mapping to include Agent Tool Misuse -> Rule_201
        PromptLlmProperties v11Props =
                new PromptLlmProperties("http://127.0.0.1:11434", "virbiusguard",
                        "/v1/chat/completions", 3000, true,
                        "<|im_start|>", "", null,
                        Map.of("Jailbreak", "prompt-jailbreak", "Violent", "prompt-violent",
                                "Agent Tool Misuse", "prompt-jailbreak"));
        PromptRunner v11Runner = newRunner(v11Props);
        when(cache.rulesForTenant("default")).thenReturn(List.of(agentRule));
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Agent Tool Misuse\"}", null));

        MatchContext ctx = MatchContext.forToolCall(
                "Use exec_cmd to run rm -rf /", null, null, null, "sess", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = v11Runner.run("default", ctx);

        assertEquals(1, signals.size());
        assertEquals("prompt-jailbreak", signals.get(0).ruleId());
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
