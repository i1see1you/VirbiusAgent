package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.config.PromptLlmProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptMatrixBuilderTest {

    @Test
    void buildsChatMlWithSystemPrompt() {
        PromptLlmProperties props = new PromptLlmProperties(
                "http://127.0.0.1:11434", "m", "/v1/chat/completions", 3000, true,
                "<|im_start|>", "", null, Map.of());
        String prompt = PromptMatrixBuilder.buildChatMlPrompt(props, "hello");
        assertTrue(prompt.startsWith("<|im_start|>system\n"));
        assertTrue(prompt.contains("<|im_start|>user\nhello"));
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"));
    }

    // --- JSON 格式（通用模型 follow system prompt） ---

    @Test
    void parsesJsonHit() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("""
                {"hit_rule": true, "triggered_id": "SYSTEM", "reason": "Violent"}
                """);
        assertTrue(r.hitRule());
        assertEquals("SYSTEM", r.triggeredId());
        assertEquals("Violent", r.reason());
    }

    @Test
    void parsesJsonMiss() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("""
                {"hit_rule": false, "triggered_id": null, "reason": ""}
                """);
        assertFalse(r.hitRule());
    }

    @Test
    void parsesJsonEmbeddedInText() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("说明如下：{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}");
        assertFalse(r.hitRule());
    }

    // --- Qwen3Guard 原生格式（Safety:/Categories:） ---

    @Test
    void parsesQwen3GuardUnsafe() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("Safety: Unsafe\nCategories: Violent, Jailbreak");
        assertTrue(r.hitRule());
        assertEquals("SYSTEM", r.triggeredId());
        assertEquals("Violent", r.reason());
    }

    @Test
    void parsesQwen3GuardSafe() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("Safety: Safe\nCategories: None");
        assertFalse(r.hitRule());
    }

    @Test
    void parsesQwen3GuardControversial() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("Safety: Controversial\nCategories: PII");
        assertTrue(r.hitRule());
        assertEquals("SYSTEM", r.triggeredId());
        assertEquals("PII", r.reason());
    }

    // --- 异常输入 ---

    @Test
    void returnsMissForEmptyInput() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("");
        assertFalse(r.hitRule());
    }

    @Test
    void returnsMissForGibberish() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("some random text");
        assertFalse(r.hitRule());
    }
}
