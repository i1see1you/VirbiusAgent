package io.virbius.engine.eval;

import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Simulates a single draft prompt rule via LLM safety classification. */
@Service
public class PromptSimulateService {

    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public PromptSimulateService(
            PromptLlmProperties llmProps, PromptLlmClient llmClient, PromptAuditJsonParser auditParser) {
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    public PromptSimulateResponseDto simulate(PromptSimulateRequestDto request) {
        String ruleId = request.ruleId();
        if (ruleId == null || ruleId.isBlank()) {
            return new PromptSimulateResponseDto(false, false, null, null, null, "rule_id required");
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            return new PromptSimulateResponseDto(false, false, null, null, null, "content required");
        }

        String prompt = PromptMatrixBuilder.buildChatMlPrompt(llmProps, content);
        PromptLlmClient.CompleteResult llm = llmClient.completeDetail(prompt);
        if (llm.error() != null && !llm.error().isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, llm.content(), llm.error());
        }
        String assistant = llm.content();
        if (assistant == null || assistant.isBlank()) {
            return new PromptSimulateResponseDto(
                    false, false, null, null, assistant, "LLM empty response (model=" + llmProps.model() + ")");
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
                hit, audit.hitRule(), audit.triggeredId(), audit.reason(), assistant, null);
    }
}
