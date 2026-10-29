package io.virbius.engine.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.prompt-judge")
public record PromptJudgeProperties(
        String defaultBackend,
        int timeoutMs,
        int maxRulesPerBatch,
        Map<String, Backend> backends) {

    public PromptJudgeProperties {
        if (defaultBackend == null || defaultBackend.isBlank()) {
            defaultBackend = "neohorse-4b";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 2000;
        }
        if (maxRulesPerBatch <= 0) {
            maxRulesPerBatch = 8;
        }
        backends = backends == null ? Collections.emptyMap() : Map.copyOf(backends);
    }

    public record Backend(
            String type,
            String baseUrl,
            boolean enabled,
            String license,
            boolean commercialUse,
            boolean experimental,
            String modelVersion,
            int maxRulesPerBatch) {

        public Backend {
            if (type == null || type.isBlank()) {
                type = "unknown";
            }
            if (baseUrl == null) {
                baseUrl = "";
            }
            if (license == null) {
                license = "unknown";
            }
            if (modelVersion == null) {
                modelVersion = "";
            }
        }
    }
}
