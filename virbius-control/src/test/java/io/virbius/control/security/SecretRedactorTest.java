package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

    @Test
    void redactsUrlPassword() {
        assertEquals("redis://***:***@redis:6379",
                SecretRedactor.redact("redis://user:secret@redis:6379"));
        assertEquals("redis://***@redis:6379",
                SecretRedactor.redact("redis://:p%40ss@redis:6379"));
    }

    @Test
    void keepsUrlWithoutCredentials() {
        assertEquals("redis://redis:6379", SecretRedactor.redact("redis://redis:6379"));
        assertEquals("http://virbius-control:8080/api/v1/health",
                SecretRedactor.redact("http://virbius-control:8080/api/v1/health"));
    }

    @Test
    void redactsTokens() {
        assertEquals("api_key=vrb_tk_***",
                SecretRedactor.redact("api_key=vrb_tk_AbC123-_xYz"));
        assertEquals("jwt=<redacted-jwt>",
                SecretRedactor.redact(
                        "jwt=eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJpc3MiIjoidmlyYml1cyJ9.c2lnbmF0dXJlLXZhbHVlLTEyMw"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals("", SecretRedactor.redact((String) null));
        assertEquals("", SecretRedactor.redact(""));
        assertEquals("plain text", SecretRedactor.redact("plain text"));
    }
}
