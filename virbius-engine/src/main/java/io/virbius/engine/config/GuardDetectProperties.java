package io.virbius.engine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Prompt Injection Detection and STI Taint Detection.
 *
 * <p>Both detectors reuse the same {@link PromptLlmClient} (and thus the same
 * model configured under {@code virbius.prompt-llm}) but with their own system
 * prompt. By default the system prompts are identical to the prompt-llm
 * auditor prompt so that fine-tuned models (e.g. virbiusguard) operate
 * within their training distribution.
 */
@ConfigurationProperties(prefix = "virbius.guard-detect")
public record GuardDetectProperties(
        boolean injectionEnabled,
        boolean taintEnabled,
        String injectionSystemPrompt,
        String taintSystemPrompt,
        int taintMinContentLength,
        int taintMaxContentLength,
        int timeoutMs,
        boolean failOpen,
        List<String> taintBlockCategories,
        List<String> injectionExcludeCategories) {

    /** Safety labels treated as indirect injection in tool returns when unset. */
    private static final List<String> DEFAULT_TAINT_BLOCK_CATEGORIES =
            List.of("Jailbreak", "Agent Tool Misuse");

    /**
     * Input-gate injection categories that must NOT block when unset.
     *
     * <p>PII in a user prompt is legitimate (and DLP/masking's job), and
     * agent-tool-misuse is only meaningful once a tool call exists — judging it
     * on the raw user input over-refuses benign agent tasks. Everything else
     * still blocks, so an unknown/new category stays fail-safe.
     */
    private static final List<String> DEFAULT_INJECTION_EXCLUDE_CATEGORIES =
            List.of("PII", "Agent Tool Misuse");

    public GuardDetectProperties {
        if (taintMinContentLength <= 0) {
            taintMinContentLength = 512;
        }
        if (taintMaxContentLength <= 0) {
            taintMaxContentLength = 8192;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
        taintBlockCategories = normalize(taintBlockCategories, DEFAULT_TAINT_BLOCK_CATEGORIES);
        injectionExcludeCategories =
                normalize(injectionExcludeCategories, DEFAULT_INJECTION_EXCLUDE_CATEGORIES);
    }

    private static List<String> normalize(List<String> configured, List<String> fallback) {
        List<String> cleaned = configured == null
                ? List.of()
                : configured.stream()
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
