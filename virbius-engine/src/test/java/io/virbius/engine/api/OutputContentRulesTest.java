package io.virbius.engine.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OutputContentRulesTest {

    private final OutputContentRules rules = new OutputContentRules();
    private final OutputActionResolver resolver =
            new OutputActionResolver("PII=redact,PROMPT_INJECTION=omit,OUTPUT_CREDENTIAL_LEAK=redact");

    @Test
    void detectsCodeInjection() {
        assertTrue(rules.scan("import subprocess; subprocess.run(['rm','-rf','/'])")
                .stream().anyMatch(h -> h.reasonCode().equals("OUTPUT_CODE_INJECTION")));
    }

    @Test
    void detectsSqlInjection() {
        assertTrue(rules.scan("'; DROP TABLE users; --").stream()
                .anyMatch(h -> h.reasonCode().equals("OUTPUT_SQL_INJECTION")));
    }

    @Test
    void detectsXss() {
        assertTrue(rules.scan("<script>alert(1)</script>").stream()
                .anyMatch(h -> h.reasonCode().equals("OUTPUT_XSS")));
    }

    @Test
    void detectsCredentialLeak() {
        assertTrue(rules.scan("key=AKIAIOSFODNN7EXAMPLE").stream()
                .anyMatch(h -> h.reasonCode().equals("OUTPUT_CREDENTIAL_LEAK")));
    }

    @Test
    void cleanTextNoHits() {
        assertTrue(rules.scan("今天天气不错，我们去看电影吧。").isEmpty());
    }

    @Test
    void actionResolverMapsReasonCodes() {
        assertEquals("omit", resolver.resolve("PROMPT_INJECTION", true));
        assertEquals("redact", resolver.resolve("PII", true));
        assertEquals("redact", resolver.resolve("OUTPUT_CREDENTIAL_LEAK", true));
        assertEquals("reject", resolver.resolve("SOMETHING_ELSE", true));
        assertEquals("warn", resolver.resolve("PII", false));
    }

    @Test
    void maskerRedactsPiiAndSecrets() {
        String masked = OutputMasker.redact("call me 13800138000 or a@b.com token Bearer abcdefghijklmnopqrstuvwxyz");
        assertFalse(masked.contains("13800138000"));
        assertFalse(masked.contains("a@b.com"));
        assertTrue(masked.contains("[REDACTED_PHONE]"));
        assertTrue(masked.contains("[REDACTED_EMAIL]"));
    }
}
