package io.virbius.engine.eval;

import io.virbius.engine.config.GuardDetectProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects prompt injection / jailbreak attempts in user input.
 *
 * <p>Uses a single-tier strategy:
 * <ol>
 *   <li><b>LLM detection</b>: qwen3guard:0.6b model for semantic injection detection</li>
 * </ol>
 *
 * <p>The qwen3guard model provides superior semantic detection compared to regex
 * pattern matching, covering injection variants, obfuscation, and novel attack
 * patterns that fixed regex rules cannot capture.
 *
 * <p>Reuses {@link PromptLlmClient} and {@link PromptAuditJsonParser} from the existing
 * prompt runtime infrastructure, but with an injection-specific system prompt.
 */
@Component
public class PromptInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionDetector.class);

    private final GuardDetectProperties guardProps;
    private final PromptLlmProperties llmProps;
    private final PromptLlmClient llmClient;
    private final PromptAuditJsonParser auditParser;

    public PromptInjectionDetector(
            GuardDetectProperties guardProps,
            PromptLlmProperties llmProps,
            PromptLlmClient llmClient,
            PromptAuditJsonParser auditParser) {
        this.guardProps = guardProps;
        this.llmProps = llmProps;
        this.llmClient = llmClient;
        this.auditParser = auditParser;
    }

    /**
     * Detect prompt injection in user input text.
     *
     * @param text the user input or content to check
     * @return detection result with hit status, matched pattern, and risk delta
     */
    public InjectionDetectionResult detect(String text) {
        if (!guardProps.injectionEnabled()) {
            return InjectionDetectionResult.clean();
        }
        if (text == null || text.isBlank()) {
            return InjectionDetectionResult.clean();
        }

        // LLM-based semantic detection via qwen3guard
        String prompt = buildInjectionPrompt(text);
        PromptLlmClient.CompleteResult result = llmClient.completeDetail(prompt);

        if (result.content() == null || result.content().isBlank()) {
            if (guardProps.failOpen()) {
                log.warn("injection-detect LLM unavailable; fail-open");
                return InjectionDetectionResult.clean();
            }
            return new InjectionDetectionResult(true, "llm_unavailable", 15, "fail_closed:llm_unavailable");
        }

        PromptAuditResult audit = auditParser.parse(result.content());
        if (!audit.hitRule()) {
            return InjectionDetectionResult.clean();
        }

        String reason = audit.reason() != null ? audit.reason() : "llm_injection_detected";
        return new InjectionDetectionResult(true, reason, 30, "llm:" + reason);
    }

    private String buildInjectionPrompt(String userContent) {
        // Use the guard-specific injection system prompt instead of the default content safety one
        return llmProps.imStart()
                + "system\n"
                + guardProps.injectionSystemPrompt()
                + llmProps.imEnd()
                + "\n"
                + llmProps.imStart()
                + "user\n"
                + userContent
                + llmProps.imEnd()
                + "\n"
                + llmProps.imStart()
                + "assistant\n";
    }

    /**
     * Detection result for prompt injection.
     *
     * @param hit whether injection was detected
     * @param matchedPattern the pattern that matched (LLM-detected category)
     * @param riskDelta risk score increment to apply to the session
     * @param auditDetail detail string for audit logging
     */
    public record InjectionDetectionResult(
            boolean hit,
            String matchedPattern,
            int riskDelta,
            String auditDetail) {

        static InjectionDetectionResult clean() {
            return new InjectionDetectionResult(false, null, 0, null);
        }
    }
}
