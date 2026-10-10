package io.virbius.control.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Request-scoped redaction must strip the submitted value (and its base64 /
 * urlencode variants) from any text it scrubs.
 */
class DelegatedRedactionTest {

    @Test
    void scrubsRawBase64AndUrlencodedVariants() throws Exception {
        String value = "sk-secret-value-000123";
        DelegatedRedaction.register(value);
        try {
            String raw = "error for " + value;
            String b64 = java.util.Base64.getEncoder()
                    .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String url = java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
            String text = DelegatedRedaction.scrub(raw + " " + b64 + " " + url);
            assertTrue(text.contains(DelegatedRedaction.REDACTED));
            assertTrue(!text.contains(value), "raw value must be gone");
            assertTrue(!text.contains(b64), "base64 variant must be gone");
            assertTrue(!text.contains(url), "urlencoded variant must be gone");
        } finally {
            DelegatedRedaction.clear();
        }
    }

    @Test
    void scrubWithoutRegistrationIsIdentity() {
        DelegatedRedaction.clear();
        assertEquals("nothing secret here", DelegatedRedaction.scrub("nothing secret here"));
    }

    @Test
    void clearRemovesNeedles() {
        DelegatedRedaction.register("sk-cccccccccc");
        DelegatedRedaction.clear();
        assertEquals("sk-cccccccccc", DelegatedRedaction.scrub("sk-cccccccccc"));
    }
}
