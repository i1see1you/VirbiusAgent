package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Delegated envelope wire-contract tests, including the fixed-IV cross-language
 * vector consumed verbatim by the Rust side (delegated.rs `java_interop_vector`).
 */
class DelegatedCredEnvelopeTest {

    private static final String MASTER = "0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void kekDerivationIsDeterministicAndDomainSeparated() {
        DelegatedCredEnvelope env = new DelegatedCredEnvelope(MASTER);
        byte[] kek1 = env.deriveTenantKek("acme");
        byte[] kek2 = env.deriveTenantKek("acme");
        byte[] kekOther = env.deriveTenantKek("other");
        assertEquals(32, kek1.length);
        assertEquals(Base64.getEncoder().encodeToString(kek1),
                Base64.getEncoder().encodeToString(kek2));
        assertTrue(!Base64.getEncoder().encodeToString(kek1)
                .equals(Base64.getEncoder().encodeToString(kekOther)));
    }

    @Test
    void lenprefixIsU32Be() {
        byte[] lp = DelegatedCredEnvelope.lenprefix("ab");
        assertEquals(6, lp.length);
        assertEquals(0, lp[0]);
        assertEquals(0, lp[1]);
        assertEquals(0, lp[2]);
        assertEquals(2, lp[3]);
        assertEquals('a', lp[4]);
        assertEquals('b', lp[5]);
    }

    @Test
    void aadBindsAllFieldsInOrder() {
        byte[] aad = DelegatedCredEnvelope.aad("acme", "h1", "api.example.com",
                "Authorization", "Bearer ");
        // tenant(4+4) + handle(4+2) + host(4+15) + header(4+13) + prefix(4+7)
        assertEquals(8 + 6 + 19 + 17 + 11, aad.length);
        assertEquals(4, aad[3]); // u32_be len(tenant)=4
        assertEquals(2, aad[11]); // u32_be len(handle)=2
        assertEquals(15, aad[17]); // u32_be len(host)=15
        assertEquals(13, aad[36]); // u32_be len(header_name)=13
        assertEquals(7, aad[53]); // u32_be len(prefix)=7
    }

    @Test
    void envelopeFormatAndRoundtripViaProxyDecryptPath() throws Exception {
        DelegatedCredEnvelope env = new DelegatedCredEnvelope(MASTER);
        String ct = env.encrypt("acme", "h1", "api.example.com",
                "Authorization", "Bearer ", "sk-roundtrip-value-000111");
        assertTrue(ct.startsWith("dcv1:1:"));
        // Standard padded base64 after the prefix.
        String b64 = ct.substring("dcv1:1:".length());
        byte[] blob = Base64.getDecoder().decode(b64);
        assertTrue(blob.length > 12 + 16, "iv(12) + ct + tag(16)");

        // Decrypt through the same path the proxy uses (raw KEK + AAD + GCM).
        byte[] kek = env.deriveTenantKek("acme");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(kek, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12));
        cipher.updateAAD(DelegatedCredEnvelope.aad("acme", "h1", "api.example.com",
                "Authorization", "Bearer "));
        byte[] plain = cipher.doFinal(blob, 12, blob.length - 12);
        assertEquals("sk-roundtrip-value-000111", new String(plain, "UTF-8"));

        // AAD field change must fail auth.
        javax.crypto.Cipher bad = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        bad.init(javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(kek, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, blob, 0, 12));
        bad.updateAAD(DelegatedCredEnvelope.aad("acme", "h1", "evil.com",
                "Authorization", "Bearer "));
        assertThrows(Exception.class, () -> bad.doFinal(blob, 12, blob.length - 12));
    }

    @Test
    void masterKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new DelegatedCredEnvelope("short"));
        assertThrows(IllegalStateException.class, () -> new DelegatedCredEnvelope(null));
    }

    /**
     * Fixed-IV vector: the identical bytes are asserted by the Rust test
     * `delegated::tests::java_interop_vector` (GCM is deterministic for a
     * fixed key/iv/aad/plaintext). If this vector changes, regenerate BOTH.
     */
    @Test
    void javaInteropVectorFixedIv() {
        byte[] kek = new byte[32];
        java.util.Arrays.fill(kek, (byte) 7);
        byte[] iv = new byte[12];
        java.util.Arrays.fill(iv, (byte) 9);
        DelegatedCredEnvelope env = new DelegatedCredEnvelope(MASTER);
        String ct = env.encryptWithIv(iv, kek, "acme", "h1", "api.example.com",
                "Authorization", "Bearer ", "sk-interop-vector-0001");
        assertNotNull(ct);
        System.out.println("JAVA_INTEROP_VECTOR=" + ct);
    }
}
