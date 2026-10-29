package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import io.virbius.engine.eval.judge.PromptJudgeBackend;
import io.virbius.engine.eval.judge.PromptJudgeBackendRegistry;
import io.virbius.engine.eval.judge.PromptJudgeSupport;
import io.virbius.policy.prompt.PromptRuleKind;
import io.virbius.policy.prompt.PromptRuleParser;
import io.virbius.policy.prompt.PromptRuleSpec;
import io.virbius.policy.prompt.PromptRuleValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Simulates a single draft prompt rule via category LLM or prompt judge. */
@Service
public class PromptSimulateService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;
    private final PromptJudgeBackendRegistry judgeRegistry;

    public PromptSimulateService(
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser,
            PromptJudgeBackendRegistry judgeRegistry) {
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
        this.judgeRegistry = judgeRegistry;
    }

    public PromptSimulateResponseDto simulate(PromptSimulateRequestDto request) {
        String ruleId = request.ruleId();
        if (ruleId == null || ruleId.isBlank()) {
            return categoryError("rule_id required");
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            return categoryError("content required");
        }
        if (PromptRuleKind.isBuiltInCategory(ruleId)) {
            return simulateCategory(ruleId, request.body(), content);
        }
        return simulateJudge(ruleId, request.body(), content);
    }

    private PromptSimulateResponseDto simulateCategory(String ruleId, String body, String content) {
        PromptLlmClient.CompleteResult llm =
                llmClient.completeDetail(llmProps.systemPrompt(), content);
        if (llm.error() != null && !llm.error().isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, llm.content(), llm.error(),
                    "category", null, null, null, null, null, false, null);
        }
        String assistant = llm.content();
        if (assistant == null || assistant.isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, assistant,
                    "LLM empty response (model=" + llmProps.model() + ")",
                    "category", null, null, null, null, null, false, null);
        }

        PromptAuditResult audit = auditParser.parse(assistant);
        boolean mapped = false;
        Map<String, String> mapping = llmProps.categoryRuleMapping();
        String category = audit.reason();
        if (category != null) {
            String mappedId = mapping.get(category);
            mapped = ruleId.equals(mappedId);
        }
        boolean hit = audit.hitRule() && mapped;
        return new PromptSimulateResponseDto(
                hit, audit.hitRule(), audit.triggeredId(), audit.reason(), assistant, null,
                "category", null, null, null, null, null, false, null);
    }

    private PromptSimulateResponseDto simulateJudge(String ruleId, String body, String content) {
        PromptRuleSpec spec;
        try {
            spec = PromptRuleParser.parse(body, JSON);
        } catch (PromptRuleValidationException e) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, null, e.getMessage(),
                    "judge", null, null, null, false, null, false, null);
        }

        String backendId = judgeRegistry.defaultBackendId();
        Optional<PromptJudgeBackend> backend = judgeRegistry.findEnabled(backendId);
        if (backend.isEmpty()) {
            boolean failClosed = spec.onError() == PromptRuleSpec.ErrorPolicy.FAIL_CLOSED;
            return new PromptSimulateResponseDto(
                    failClosed, false, failClosed ? ruleId : null, null, null,
                    "prompt judge backend is unavailable: " + backendId,
                    "judge", backendId, null, spec.threshold(), false,
                    errorPolicy(spec), true, 0L);
        }

        PromptJudgeBackend.JudgeResult result = backend.get().judge(new PromptJudgeBackend.JudgeRequest(
                content,
                List.of(new PromptJudgeBackend.JudgeRule(
                        ruleId, "noul", PromptJudgeSupport.instructions(spec)))));
        PromptJudgeSupport.Outcome outcome = PromptJudgeSupport.evaluate(spec, result, ruleId);
        return new PromptSimulateResponseDto(
                outcome.signal(),
                outcome.probabilityHit(),
                outcome.signal() ? ruleId : null,
                outcome.error(),
                null,
                outcome.error(),
                "judge",
                backendId,
                outcome.probability(),
                spec.threshold(),
                outcome.probabilityHit(),
                errorPolicy(spec),
                outcome.errorApplied(),
                result != null ? result.latencyMs() : 0L);
    }

    private static String errorPolicy(PromptRuleSpec spec) {
        return spec.onError().name().toLowerCase(Locale.ROOT);
    }

    private static PromptSimulateResponseDto categoryError(String error) {
        return new PromptSimulateResponseDto(
                false, false, null, null, null, error,
                "category", null, null, null, null, null, false, null);
    }
}
