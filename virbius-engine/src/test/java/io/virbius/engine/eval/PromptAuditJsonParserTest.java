package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

// ChatML prompt building was removed — the serving backend now applies its own
// chat template via standard OpenAI system/user messages. Only parser tests remain.

class PromptAuditJsonParserTest {

    // --- JSON format (generic models following system prompt) ---

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

    // --- VirbiusGuard V13 format (category in triggered_id, no reason) ---

    @Test
    void parsesV13Hit() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("""
                {"hit_rule": true, "triggered_id": "Jailbreak"}
                """);
        assertTrue(r.hitRule());
        assertEquals("Jailbreak", r.triggeredId());
        assertEquals("Jailbreak", r.reason());
    }

    @Test
    void parsesV13Miss() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("""
                {"hit_rule": false, "triggered_id": "none"}
                """);
        assertFalse(r.hitRule());
    }

    @Test
    void parsesTruncatedJsonMissingBrace() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"");
        assertFalse(r.hitRule());
    }

    @Test
    void parsesTruncatedJsonMissingQuoteAndBrace() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse(
                "{\"hit_rule\": true, \"triggered_id\": \"SYSTEM\", \"reason\": \"Jailbreak");
        assertTrue(r.hitRule());
        assertEquals("SYSTEM", r.triggeredId());
        assertEquals("Jailbreak", r.reason());
    }

    @Test
    void parsesJsonEmbeddedInText() {
        PromptAuditJsonParser parser = new PromptAuditJsonParser(new ObjectMapper());
        var r = parser.parse("说明如下：{\"hit_rule\": false, \"triggered_id\": null, \"reason\": \"\"}");
        assertFalse(r.hitRule());
    }

    // --- Qwen3Guard native format (Safety:/Categories:) ---

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

    // --- Edge cases ---

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
