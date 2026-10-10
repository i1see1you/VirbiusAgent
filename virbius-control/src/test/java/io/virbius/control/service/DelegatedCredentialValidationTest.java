package io.virbius.control.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.virbius.control.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** Host/validation rules must mirror the proxy (delegated.rs) byte-for-byte. */
class DelegatedCredentialValidationTest {

    @Test
    void canonicalHostMirrorsRustRules() {
        assertEquals("api.example.com", DelegatedCredentialService.canonicalHost("API.Example.COM"));
        assertEquals("api.example.com", DelegatedCredentialService.canonicalHost("api.example.com.."));
        assertEquals("llm-gateway", DelegatedCredentialService.canonicalHost("llm-gateway"));
        assertNull(DelegatedCredentialService.canonicalHost("1.2.3.4"));
        assertNull(DelegatedCredentialService.canonicalHost("2130706433"));
        assertNull(DelegatedCredentialService.canonicalHost("127.1"));
        assertNull(DelegatedCredentialService.canonicalHost("0x7f.1"));
        assertNull(DelegatedCredentialService.canonicalHost("bad_underscore"));
        assertNull(DelegatedCredentialService.canonicalHost("café.com"));
        assertNull(DelegatedCredentialService.canonicalHost(".."));
        assertNull(DelegatedCredentialService.canonicalHost(""));
        assertNull(DelegatedCredentialService.canonicalHost(null));
    }

    @Test
    void headerDenyListAndContentStar() {
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("host"));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("HOST"));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("accept-encoding"));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("content-type"));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("transfer-encoding"));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateHeaderName("bad header"));
        DelegatedCredentialService.validateHeaderName("Authorization"); // ok (injected cred itself)
        DelegatedCredentialService.validateHeaderName("x-api-key"); // ok
    }

    @Test
    void valueRules() {
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("short", ""));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue(null, ""));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("line\rbreak-123456", ""));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("a".repeat(5000), ""));
        DelegatedCredentialService.validateValue("sk-valid-value-000000", "Bearer ");
    }

    @Test
    void valueRejectsAllControlCharsButKeepsObsText() {
        // Aligns with the proxy's HeaderValue::try_from: reject 0x00-0x1F + 0x7F.
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("sk-abcdefgh\u0001ijklmnop", ""));
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("sk-abcdefgh\u0009ijklmnop", "")); // tab
        assertThrows(BusinessException.class,
                () -> DelegatedCredentialService.validateValue("sk-abcdefgh\u007fijklmnop", "")); // DEL
        // obs-text (>= 0x80) stays allowed — HeaderValue accepts it and
        // byte-exact redaction is unaffected.
        DelegatedCredentialService.validateValue("sk-abcdefgh\u00e9ijklmnop", "");
    }

    @Test
    void tenantNormalization() {
        // Proxy normalizes its deploy tenant identically; a mismatch would
        // make it drop the whole snapshot as foreign and stay cold forever.
        assertEquals("acme", DelegatedCredentialService.normalizeTenant("Acme"));
        assertEquals("acme", DelegatedCredentialService.normalizeTenant("  ACME  "));
        assertEquals("acme", DelegatedCredentialService.normalizeTenant("acme"));
        assertEquals("", DelegatedCredentialService.normalizeTenant(null));
    }
}
