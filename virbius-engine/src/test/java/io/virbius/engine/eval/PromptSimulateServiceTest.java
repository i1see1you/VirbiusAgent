package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptLlmProperties;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PromptSimulateServiceTest {

    private PromptLlmClient llmClient;
    private PromptSimulateService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(PromptLlmClient.class);
        PromptLlmProperties props =
                new PromptLlmProperties("http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                        "<|im_start|>", "", null, Map.of("Violent", "prompt-violent"));
        service = new PromptSimulateService(props, llmClient, new PromptAuditJsonParser(new ObjectMapper()));
    }

    @Test
    void hitWhenJsonReasonMatchesMappedRule() {
        when(llmClient.completeDetail(anyString(), anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Violent\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "禁止暴力", "VIOLENT", "我要打人"));

        assertTrue(resp.hit());
        assertTrue(resp.llmHitRule());
        assertEquals("SYSTEM", resp.triggeredId());
        assertEquals("Violent", resp.reason());
    }

    @Test
    void hitWhenQwen3GuardNativeFormat() {
        when(llmClient.completeDetail(anyString(), anyString()))
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
        when(llmClient.completeDetail(anyString(), anyString()))
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
        when(llmClient.completeDetail(anyString(), anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "hello"));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void errorWhenLlmEmpty() {
        when(llmClient.completeDetail(anyString(), anyString()))
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
        when(llmClient.completeDetail(anyString(), anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT", "What is the capital of France?"));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }

    @Test
    void v11UnsafeOutputWithAgentToolMisuse() {
        when(llmClient.completeDetail(anyString(), anyString()))
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
        when(llmClient.completeDetail(anyString(), anyString()))
                .thenReturn(new PromptLlmClient.CompleteResult(
                        "{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"none\"}", null));

        PromptSimulateResponseDto resp = service.simulate(new PromptSimulateRequestDto(
                "prompt-violent", "body", "VIOLENT",
                "How do I hack my own WiFi password? I forgot it."));

        assertFalse(resp.hit());
        assertFalse(resp.llmHitRule());
    }
}
