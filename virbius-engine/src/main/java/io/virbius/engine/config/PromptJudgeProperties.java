package io.virbius.engine.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.prompt-judge")
public record PromptJudgeProperties(
        String defaultBackend,
        int timeoutMs,
        int maxRulesPerBatch,
        int maxPromptChars,
        int maxContentChars,
        Map<String, Backend> backends) {

    public PromptJudgeProperties {
        if (defaultBackend == null || defaultBackend.isBlank()) {
            defaultBackend = "neohorse-4b";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 5000;
        }
        if (maxRulesPerBatch <= 0) {
            maxRulesPerBatch = 8;
        }
        // Approximate questions-side expansion budget in characters, not exact tokens.
        if (maxPromptChars <= 0) {
            maxPromptChars = 24000;
        }
        // Vendor state limit is 2048 tokens; oversized content is truncated head+tail.
        if (maxContentChars <= 0) {
            maxContentChars = 4000;
        }
        backends = backends == null ? Collections.emptyMap() : Map.copyOf(backends);
    }

    /**
     * Per-backend configuration. {@code model} is the wire model name required
     * by the Jev /v1/systemone protocol (it is not the model artifact label):
     * NeoHorse only accepts its documented aliases. Backend licensing is
     * documented in {@code docs/judge-protocol-v1.md}, not configured here.
     */
    public record Backend(
            String baseUrl,
            boolean enabled,
            String model,
            String authToken,
            int maxRulesPerBatch) {

        public Backend {
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (model == null) {
                model = "";
            }
            if (authToken == null) {
                authToken = "";
            }
        }
    }
}
