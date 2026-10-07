package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ApiKeyHasherTest {

    @Test
    void hmacKnownAnswerVector() {
        // HMAC-SHA256("key", "The quick brown fox jumps over the lazy dog")
        assertEquals(
                "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                ApiKeyHasher.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"));
    }

    @Test
    void hmacIsDeterministicAndKeyed() {
        String raw = "vrb_tk_abc123";
        String a = ApiKeyHasher.hmacSha256Hex("pepper-one", raw);
        String b = ApiKeyHasher.hmacSha256Hex("pepper-one", raw);
        String c = ApiKeyHasher.hmacSha256Hex("pepper-two", raw);
        assertEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, ApiKeyHasher.sha256Hex(raw));
    }

    @Test
    void sha256OutputLengthIs64HexChars() {
        assertEquals(64, ApiKeyHasher.sha256Hex("x").length());
        assertEquals(64, ApiKeyHasher.hmacSha256Hex("k", "x").length());
    }
}
