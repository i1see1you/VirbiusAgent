package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.audit.AuditWriter;
import io.virbius.engine.eval.EngineDecisionDto;
import io.virbius.engine.eval.EvaluateOrchestrator;
import io.virbius.engine.eval.EvaluateRequestDto;
import io.virbius.engine.eval.EvaluateResponseDto;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutputReviewServiceTest {

    @Mock private EvaluateOrchestrator orchestrator;
    @Mock private AuditWriter auditWriter;
    private final OutputContentRules contentRules = new OutputContentRules();
    private final OutputActionResolver actionResolver =
            new OutputActionResolver("PII=redact,PROMPT_INJECTION=omit,OUTPUT_CREDENTIAL_LEAK=redact");

    @Captor private ArgumentCaptor<EvaluateRequestDto> reqCaptor;
    @Captor private ArgumentCaptor<EngineDecisionDto> decisionCaptor;

    private OutputReviewService service;

    @BeforeEach
    void setUp() {
        service = new OutputReviewService(orchestrator, contentRules, actionResolver, auditWriter);
    }

    private EvaluateRequestDto request(String content, boolean streamChunk) {
        return new EvaluateRequestDto(
                "default", "output", "sess-1", content, streamChunk, null,
                "tr-1", "u-1", null, Map.of(), null, null, null, null,
                "chat_completion", null, 0, null);
    }

    @Test
    void deterministicRuleHitSkipsPipelineAndWritesAudit() {
        EvaluateResponseDto resp = service.review(request(
                "Sure! Here is the snippet: <script>alert(document.cookie)</script>", false));

        assertEquals("deny", resp.effectiveAction());
        assertEquals("reject", resp.disposalAction()); // OUTPUT_XSS → keyword fallback → reject
        assertNull(resp.sanitizedContent());
        assertNull(resp.ruleId());
        assertEquals("OUTPUT_XSS", resp.reasonCode());
        verify(auditWriter).write(reqCaptor.capture(), decisionCaptor.capture(), any(),
                anyInt(), anyString(), anyBoolean());
        assertEquals("deny", decisionCaptor.getValue().effectiveAction());
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void credentialLeakRedactsInPlace() {
        EvaluateResponseDto resp = service.review(request(
                "leaked key AKIAABCDEFGHIJKLMNOP and mail a@b.com", false));

        assertEquals("deny", resp.effectiveAction());
        assertEquals("redact", resp.disposalAction());
        assertTrue(resp.sanitizedContent().contains("[REDACTED_AWS_KEY]"));
        assertTrue(resp.sanitizedContent().contains("[REDACTED_EMAIL]"));
        verify(auditWriter).write(any(), any(), any(), anyInt(), anyString(), anyBoolean());
        org.mockito.Mockito.verifyNoInteractions(orchestrator);
    }

    @Test
    void pipelineVerdictMapsDisposalAndSanitized() {
        EvaluateRequestDto req = request("some answer", false);
        when(orchestrator.evaluate(req)).thenReturn(new EvaluateResponseDto(
                "deny", 60, 0, "prompt-pii", 3, "PII", "tr-1", false, "full", null, null));

        EvaluateResponseDto resp = service.review(req);

        assertEquals("deny", resp.effectiveAction());
        assertEquals("redact", resp.disposalAction()); // PII → redact (action-map)
        assertTrue(resp.sanitizedContent().contains("some answer"));
        assertEquals("prompt-pii", resp.ruleId());
        verify(auditWriter, org.mockito.Mockito.never()).write(any(), any(), any(), anyInt(), anyString(), anyBoolean());
    }

    @Test
    void pipelineAllowMapsToWarn() {
        EvaluateRequestDto req = request("the weather is sunny", true);
        when(orchestrator.evaluate(req)).thenReturn(new EvaluateResponseDto(
                "allow", 0, 0, "POLICY_ALLOW", 0, "POLICY_ALLOW", "tr-1", false, "dry_run", null, null));

        EvaluateResponseDto resp = service.review(req);

        assertEquals("allow", resp.effectiveAction());
        assertEquals("warn", resp.disposalAction());
        assertNull(resp.sanitizedContent());
    }

    @Test
    void pipelineInjectionVerdictMapsToOmit() {
        EvaluateRequestDto req = request("injected answer", false);
        when(orchestrator.evaluate(req)).thenReturn(new EvaluateResponseDto(
                "block", 90, 0, "prompt-jailbreak", 2, "PROMPT_INJECTION", "tr-1", false, "full", null, null));

        EvaluateResponseDto resp = service.review(req);

        assertEquals("omit", resp.disposalAction()); // PROMPT_INJECTION → omit
        assertNull(resp.sanitizedContent());
    }
}
