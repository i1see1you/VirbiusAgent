package io.virbius.engine.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for Prompt Injection Detection and STI Taint Detection.
 *
 * <p>Both detectors reuse the same {@link PromptLlmClient} (and thus the same
 * model configured under {@code virbius.prompt-llm}) but with their own system
 * prompt. By default the system prompts are identical to the prompt-llm
 * auditor prompt so that fine-tuned models (e.g. virbiusguard-v11) operate
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
        boolean failOpen) {

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
    }
}
