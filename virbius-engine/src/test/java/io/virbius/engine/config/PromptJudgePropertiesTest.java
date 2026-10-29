package io.virbius.engine.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class PromptJudgePropertiesTest {

    @Test
    void bindsGlobalAndPerBackendBatchSizes() {
        PromptJudgeProperties props = bind(Map.of(
                "virbius.prompt-judge.default-backend", "neohorse-4b",
                "virbius.prompt-judge.max-rules-per-batch", "8",
                "virbius.prompt-judge.backends.neohorse-4b.max-rules-per-batch", "1",
                "virbius.prompt-judge.backends.startlux-4b.max-rules-per-batch", "8"));

        assertEquals(8, props.maxRulesPerBatch());
        assertEquals(1, props.backends().get("neohorse-4b").maxRulesPerBatch());
        assertEquals(8, props.backends().get("startlux-4b").maxRulesPerBatch());
    }

    @Test
    void omittedBackendOverrideBindsToZeroForInheritance() {
        PromptJudgeProperties props = bind(Map.of(
                "virbius.prompt-judge.backends.laya-zh.base-url", "http://127.0.0.1:8092"));

        assertEquals(0, props.backends().get("laya-zh").maxRulesPerBatch());
        assertEquals(8, props.maxRulesPerBatch());
    }

    private static PromptJudgeProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("virbius.prompt-judge", PromptJudgeProperties.class)
                .orElseThrow(() -> new IllegalStateException("bind failed"));
    }
}
