package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.PromptJudgeProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.config.TenantAwareTaskExecutor;
import io.virbius.engine.eval.judge.PromptJudgeBackend;
import io.virbius.engine.eval.judge.PromptJudgeBackendRegistry;
import io.virbius.policy.MatchContext;
import io.virbius.policy.prompt.PromptJudgeBackends;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptRunnerJudgeTest {

    private RuleCache cache;
    private PromptLlmClient llmClient;
    private FakeBackend neoHorse;
    private FakeBackend startLux;
    private PromptRunner runner;
    private int globalBatch = 16;
    private int backendBatch;

    @BeforeEach
    void setUp() {
        cache = mock(RuleCache.class);
        llmClient = mock(PromptLlmClient.class);
        neoHorse = new FakeBackend(PromptJudgeBackends.NEOHORSE);
        startLux = new FakeBackend(PromptJudgeBackends.STARTLUX);
        runner = runner(PromptJudgeBackends.NEOHORSE);
    }

    @Test
    void judgeRuleAboveThresholdTriggersWithoutCallingCategoryLlm() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(judgeRule("R1")));
        neoHorse.answer = PromptJudgeBackend.JudgeAnswer.probability(0.8);

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(1, signals.size());
        assertEquals("R1", signals.get(0).ruleId());
        assertEquals(1, neoHorse.calls.get());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void judgeRuleBelowThresholdDoesNotTrigger() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(judgeRule("R1")));
        neoHorse.answer = PromptJudgeBackend.JudgeAnswer.probability(0.49);

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertTrue(signals.isEmpty());
    }

    @Test
    void failClosedRuleTriggersWhenBackendFails() {
        when(cache.rulesForTenant("default")).thenReturn(List.of(judgeRule("R1", true)));
        neoHorse.result = PromptJudgeBackend.JudgeResult.failed("down", 1);

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(1, signals.size());
        assertEquals("R1", signals.get(0).ruleId());
    }

    @Test
    void invalidJudgeJsonDoesNotFallBackToCategoryLlm() {
        when(cache.rulesForTenant("default"))
                .thenReturn(List.of(promptRule("R_BAD", "{\"mode\":\"judge\"")));

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(1, signals.size());
        assertEquals("R_BAD", signals.get(0).ruleId());
        assertEquals(0, neoHorse.calls.get());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void defaultBatchSizeSplitsTwentyRulesIntoThreeCalls() {
        neoHorse = new FakeBackend(PromptJudgeBackends.NEOHORSE, 16);
        neoHorse.answer = PromptJudgeBackend.JudgeAnswer.probability(0.8);
        globalBatch = 8;
        runner = runner(PromptJudgeBackends.NEOHORSE);
        when(cache.rulesForTenant("default")).thenReturn(rules(20));

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(20, signals.size());
        assertEquals(3, neoHorse.calls.get());
        assertEquals(8, neoHorse.maxRulesSeen.get());
    }

    @Test
    void backendBatchOverrideTakesPrecedence() {
        neoHorse = new FakeBackend(PromptJudgeBackends.NEOHORSE, 16);
        neoHorse.answer = PromptJudgeBackend.JudgeAnswer.probability(0.8);
        globalBatch = 8;
        backendBatch = 4;
        runner = runner(PromptJudgeBackends.NEOHORSE);
        when(cache.rulesForTenant("default")).thenReturn(rules(20));

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(20, signals.size());
        assertEquals(5, neoHorse.calls.get());
        assertEquals(4, neoHorse.maxRulesSeen.get());
    }

    @Test
    void declaredCapabilityCapsConfiguredBatch() {
        neoHorse = new FakeBackend(PromptJudgeBackends.NEOHORSE, 1);
        neoHorse.answer = PromptJudgeBackend.JudgeAnswer.probability(0.8);
        globalBatch = 8;
        backendBatch = 8;
        runner = runner(PromptJudgeBackends.NEOHORSE);
        when(cache.rulesForTenant("default")).thenReturn(rules(3));

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(3, signals.size());
        assertEquals(3, neoHorse.calls.get());
        assertEquals(1, neoHorse.maxRulesSeen.get());
    }

    @Test
    void usesGloballyConfiguredBackend() {
        runner = runner(PromptJudgeBackends.STARTLUX);
        when(cache.rulesForTenant("default")).thenReturn(List.of(judgeRule("R1")));
        startLux.answer = PromptJudgeBackend.JudgeAnswer.probability(0.9);

        List<SignalDto> signals = runner.run("default", ctx("hello"));

        assertEquals(1, signals.size());
        assertEquals(1, startLux.calls.get());
        assertEquals(0, neoHorse.calls.get());
    }

    private PromptRunner runner(String defaultBackend) {
        PromptJudgeProperties judgeProps = new PromptJudgeProperties(
                defaultBackend,
                1000,
                globalBatch,
                Map.of(
                        PromptJudgeBackends.NEOHORSE, backendConfig(true, backendBatch),
                        PromptJudgeBackends.STARTLUX, backendConfig(true, backendBatch)));
        PromptLlmProperties llmProps = new PromptLlmProperties(
                "http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                "<|im_start|>", "", null, Map.of());
        return new PromptRunner(
                cache,
                llmProps,
                llmClient,
                new PromptAuditJsonParser(new com.fasterxml.jackson.databind.ObjectMapper()),
                mock(TenantAwareTaskExecutor.class),
                mock(AsyncActionHandler.class),
                new PromptJudgeBackendRegistry(List.of(neoHorse, startLux), judgeProps),
                judgeProps);
    }

    private static PromptJudgeProperties.Backend backendConfig(boolean enabled, int batch) {
        return new PromptJudgeProperties.Backend(
                "http", "http://127.0.0.1", enabled, "test", true, false, "test", batch);
    }

    private static RuleEntry promptRule(String ruleId, String body) {
        return new RuleEntry(
                "default", ruleId, 1, "cloud", "prompt", "TEST", 100, "deny",
                "dry_run", 0, "dry_run", body, Map.of("bind_scope", "global"), false, null);
    }

    private static List<RuleEntry> rules(int count) {
        List<RuleEntry> out = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(judgeRule("R" + i));
        }
        return out;
    }

    private static RuleEntry judgeRule(String ruleId) {
        return judgeRule(ruleId, false);
    }

    private static RuleEntry judgeRule(String ruleId, boolean failClosed) {
        String body = """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "Detect the target behavior",
                  "threshold": 0.5,
                  "on_error": "%s",
                  "enforcement_ready": %s
                }
                """.formatted(
                failClosed ? "fail_closed" : "fail_open",
                failClosed);
        return new RuleEntry(
                "default", ruleId, 1, "cloud", "prompt", "TEST", 100, "deny",
                "dry_run", 0, "dry_run", body, Map.of("bind_scope", "global"), false, null);
    }

    private static MatchContext ctx(String content) {
        return MatchContext.forToolCall(
                content, null, null, null, "sess", Map.of("app_id", "test"), "read_file");
    }

    private static final class FakeBackend implements PromptJudgeBackend {

        private final String id;
        private final int maxRulesPerBatch;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger maxRulesSeen = new AtomicInteger();
        private volatile JudgeAnswer answer = JudgeAnswer.probability(0);
        private volatile JudgeResult result;

        private FakeBackend(String id) {
            this(id, 1);
        }

        private FakeBackend(String id, int maxRulesPerBatch) {
            this.id = id;
            this.maxRulesPerBatch = maxRulesPerBatch;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public JudgeCapabilities capabilities() {
            return new JudgeCapabilities(
                    true, false, false, 0, maxRulesPerBatch, "test", true);
        }

        @Override
        public JudgeResult judge(JudgeRequest request) {
            calls.incrementAndGet();
            maxRulesSeen.accumulateAndGet(request.rules().size(), Math::max);
            JudgeResult failed = result;
            if (failed != null) {
                return failed;
            }
            Map<String, JudgeAnswer> answers = new java.util.LinkedHashMap<>();
            for (JudgeRule rule : request.rules()) {
                answers.put(rule.id(), answer);
            }
            return new JudgeResult(answers, 1, null);
        }

        @Override
        public JudgeHealth health() {
            return JudgeHealth.up();
        }
    }
}
