package io.virbius.engine.config;

import java.util.Collections;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "virbius.prompt-llm")
public record PromptLlmProperties(
        String baseUrl,
        String model,
        String apiPath,
        int timeoutMs,
        boolean failOpen,
        String imStart,
        String imEnd,
        String systemPrompt,
        Map<String, String> categoryRuleMapping) {

    public PromptLlmProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:11434";
        }
        if (model == null || model.isBlank()) {
            model = "sileader/qwen3guard:0.6b";
        }
        if (apiPath == null || apiPath.isBlank()) {
            apiPath = "/v1/chat/completions";
        }
        if (timeoutMs <= 0) {
            timeoutMs = 3000;
        }
        if (imStart == null || imStart.isBlank()) {
            imStart = "<|im_start|>";
        }
        if (imEnd == null) {
            imEnd = "";
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = """
                    You are a strict LLM input auditor. Classify the user input for safety.
                    If unsafe or controversial, output strict JSON only with no extra text:
                    {"hit_rule": true, "triggered_id": "SYSTEM", "reason": "<specific safety category>"}
                    If safe:
                    {"hit_rule": false, "triggered_id": null, "reason": ""}
                    """.stripIndent();
        }
        if (categoryRuleMapping == null) {
            categoryRuleMapping = Collections.emptyMap();
        }
    }
}
