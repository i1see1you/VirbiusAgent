package io.virbius.engine.eval;

import io.virbius.engine.config.GuardDetectProperties;
import io.virbius.engine.config.PromptLlmProperties;
import io.virbius.engine.eval.PromptAuditJsonParser.PromptAuditResult;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Detects prompt injection / jailbreak attempts in user input.
 *
 * <p>Uses a two-tier strategy:
 * <ol>
 *   <li><b>Regex pre-filter</b>: fast pattern matching for known injection markers</li>
 *   <li><b>LLM detection</b>: qwen3guard:0.6b model for semantic injection detection</li>
 * </ol>
 *
 * <p>Reuses {@link PromptLlmClient} and {@link PromptAuditJsonParser} from the existing
 * prompt runtime infrastructure, but with an injection-specific system prompt.
 */
@Component
public class PromptInjectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionDetector.class);

    private static final Pattern[] INJECTION_MARKERS = {
        Pattern.compile("(?i)ignore\\s+(previous|above|prior)\\s+instructions"),
        Pattern.compile("(?i)you\\s+are\\s+now\\s+(DAN|developer\\s+mode|jailbreak)"),
        Pattern.compile("(?i)forget\\s+(everything|all|previous)"),
        Pattern.compile("(?i)disregard\\s+(prior|above|previous)"),
        Pattern.compile("(?i)<\\s*system\\s*>|<\\s*instruction\\s*>"),
        Pattern.compile("(?i)system\\s*prompt|reveal\\s+your\\s+instructions"),
        Pattern.compile("(?i)act\\s+as\\s+(if\\s+you\\s+(have|are)|a\\s+different)"),
    };

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

        // 1. Regex pre-filter for fast detection
        for (Pattern p : INJECTION_MARKERS) {
            if (p.matcher(text).find()) {
                String pattern = p.pattern();
                log.info("injection regex hit: pattern={}", pattern);
                return new InjectionDetectionResult(true, pattern, 30, "regex_match:" + pattern);
            }
        }

        // 2. LLM-based semantic detection via qwen3guard
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
     * @param matchedPattern the pattern that matched (regex pattern or LLM-detected category)
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
