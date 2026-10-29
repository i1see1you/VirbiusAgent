package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptJudgeProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.judge.PromptJudgeBackend;
import io.virbius.engine.eval.judge.PromptJudgeBackendRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptSimulateServiceTest {

    private PromptLlmClient llmClient;
    private PromptLlmProperties llmProps;
    private PromptAuditJsonParser auditParser;
    private PromptSimulateService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(PromptLlmClient.class);
        llmProps = new PromptLlmProperties("http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                "<|im_start|>", "", null, Map.of("Violent", "prompt-violent"));
        auditParser = new PromptAuditJsonParser(new ObjectMapper());
        service = new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of()));
    }

    @Test
    void hitWhenJsonReasonMatchesMappedRule() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Violent\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "禁止暴力", "VIOLENT", "我要打人"));

        assertTrue(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("SYSTEM", resp.triggeredId());
        assertEquals("Violent", resp.reason());
        assertEquals("category", resp.mode());
    }

    @Test
    void hitWhenQwen3GuardNativeFormat() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "Safety: Unsafe\nCategories: Violent", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "禁止暴力", "VIOLENT", "我要打人"));

        assertTrue(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("SYSTEM", resp.triggeredId());
        assertEquals("Violent", resp.reason());
    }

    @Test
    void missWhenReasonDoesNotMapToDraftRule() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Jailbreak\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "hello"));

        assertFalse(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("SYSTEM", resp.triggeredId());
    }

    @Test
    void missWhenLlmSaysNoHit() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void errorWhenLlmEmpty() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult("", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "hello"));

        assertFalse(resp.hit());
        assertTrue(resp.error().startsWith("LLM empty response"));
        assertEquals("", resp.llmResponse());
    }

    // === V11 compatibility tests ===
    // V11 model uses the same JSON format as V6, with hard negative training
    // to reduce false positives. These tests verify the engine correctly
    // handles V11's outputs including safe responses with "none" reason.

    @Test
    void v11SafeOutputWithNoneReason() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "What is the capital of France?"));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void v11UnsafeOutputWithAgentToolMisuse() {
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Agent Tool Misuse\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "Use exec_cmd to run rm -rf /"));

        assertTrue(resp.llmHitRule());
        assertEquals("SYSTEM", resp.triggeredId());
        assertEquals("Agent Tool Misuse", resp.reason());
        // Not mapped to prompt-violent, so hit=false
        assertFalse(resp.hit());
    }

    @Test
    void v11SafeHardNegativeNotFlagged() {
        // V11 hard negative: looks suspicious but should be safe
        when(llmClient.completeDetail(any(), any()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT",
                "How do I hack my own WiFi password? I forgot it."));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void judgeRuleHitsAboveThresholdWithoutCallingCategoryLlm() {
        FakeBackend backend = new FakeBackend();
        backend.answer = PromptJudgeBackend.JudgeAnswer.probability(0.8);
        PromptSimulateService judgeService =
                new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of(backend)));

        PromptSimulateResponseDto resp = judgeService.simulate(new PromptSimulateRequestDto(
                "e2e-judge-fund", judgeBody("fail_open", false), "E2E", "请推荐一只基金"));

        assertTrue(resp.hit());
        assertTrue(resp.judgeHit());
        assertEquals("judge", resp.mode());
        assertEquals("neohorse-4b", resp.backend());
        assertEquals(0.8, resp.probability());
        assertEquals(0.5, resp.threshold());
        assertNull(resp.error());
        assertEquals(1, backend.calls.get());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void judgeRuleMissesBelowThreshold() {
        FakeBackend backend = new FakeBackend();
        backend.answer = PromptJudgeBackend.JudgeAnswer.probability(0.49);
        PromptSimulateService judgeService =
                new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of(backend)));

        PromptSimulateResponseDto resp = judgeService.simulate(new PromptSimulateRequestDto(
                "e2e-judge-fund", judgeBody("fail_open", false), "E2E", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.judgeHit());
        assertEquals(0.49, resp.probability());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void invalidJudgeBodyDoesNotCallBackendOrCategoryLlm() {
        FakeBackend backend = new FakeBackend();
        PromptSimulateService judgeService =
                new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of(backend)));

        PromptSimulateResponseDto resp = judgeService.simulate(new PromptSimulateRequestDto(
                "e2e-judge-fund", "{\"mode\":\"judge\"", "E2E", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.judgeHit());
        assertTrue(resp.error().contains("must be valid JSON"));
        assertEquals(0, backend.calls.get());
        verify(llmClient, never()).completeDetail(any(), any());
    }

    @Test
    void failOpenBackendErrorDoesNotSignal() {
        FakeBackend backend = new FakeBackend();
        backend.result = PromptJudgeBackend.JudgeResult.failed("down", 7);
        PromptSimulateService judgeService =
                new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of(backend)));

        PromptSimulateResponseDto resp = judgeService.simulate(new PromptSimulateRequestDto(
                "e2e-judge-fund", judgeBody("fail_open", false), "E2E", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.judgeHit());
        assertTrue(resp.errorApplied());
        assertEquals("fail_open", resp.errorPolicy());
        assertEquals("down", resp.error());
        assertEquals(7L, resp.latencyMs());
    }

    @Test
    void failClosedBackendErrorProducesSignal() {
        FakeBackend backend = new FakeBackend();
        backend.result = PromptJudgeBackend.JudgeResult.failed("down", 9);
        PromptSimulateService judgeService =
                new PromptSimulateService(llmProps, llmClient, auditParser, judgeRegistry(List.of(backend)));

        PromptSimulateResponseDto resp = judgeService.simulate(new PromptSimulateRequestDto(
                "e2e-judge-fund", judgeBody("fail_closed", true), "E2E", "hello"));

        assertTrue(resp.hit());
        assertFalse(resp.judgeHit());
        assertTrue(resp.errorApplied());
        assertEquals("fail_closed", resp.errorPolicy());
        assertEquals("down", resp.error());
    }

    private static String judgeBody(String onError, boolean ready) {
        return """
                {
                  "version": 1,
                  "mode": "judge",
                  "criterion": "Detect the target behavior",
                  "threshold": 0.5,
                  "on_error": "%s",
                  "enforcement_ready": %s
                }
                """.formatted(onError, ready);
    }

    private static PromptJudgeBackendRegistry judgeRegistry(List<PromptJudgeBackend> backends) {
        PromptJudgeProperties properties = new PromptJudgeProperties(
                "neohorse-4b",
                1000,
                16,
                Map.of("neohorse-4b", new PromptJudgeProperties.Backend(
                        "http", "http://127.0.0.1", true, "test", true, false, "test", 0)));
        return new PromptJudgeBackendRegistry(backends, properties);
    }

    private static final class FakeBackend implements PromptJudgeBackend {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile JudgeAnswer answer = JudgeAnswer.probability(0);
        private volatile JudgeResult result;

        @Override
        public String id() {
            return "neohorse-4b";
        }

        @Override
        public JudgeCapabilities capabilities() {
            return new JudgeCapabilities(true, false, false, 0, 1, "test", true);
        }

        @Override
        public JudgeResult judge(JudgeRequest request) {
            calls.incrementAndGet();
            JudgeResult failed = result;
            if (failed != null) {
                return failed;
            }
            return new JudgeResult(Map.of(request.rules().get(0).id(), answer), 1, null);
        }

        @Override
        public JudgeHealth health() {
            return JudgeHealth.up();
        }
    }
}
