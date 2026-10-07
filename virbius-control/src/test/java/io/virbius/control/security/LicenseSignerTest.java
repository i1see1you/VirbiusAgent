package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

class LicenseSignerTest {

    private static final String STRONG_KEY =
            "unit-test-strong-master-key-0123456789abcdef0123456789abcdef";

    private LicenseSigner signer(MockEnvironment env, String masterKey) {
        LicenseSigner signer = new LicenseSigner(env);
        ReflectionTestUtils.setField(signer, "masterKey", masterKey);
        return signer;
    }

    private static MockEnvironment dev() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        return env;
    }

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }

    // ─── master key policy ────────────────────────────────────────────────

    @Test
    void blankMasterKeyFailsInEveryProfile() {
        assertThrows(IllegalStateException.class,
                () -> signer(dev(), "").validateMasterKey());
        assertThrows(IllegalStateException.class,
                () -> signer(prod(), null).validateMasterKey());
    }

    @Test
    void denylistedMasterKeyFails() {
        assertThrows(IllegalStateException.class,
                () -> signer(prod(), "virbius-default-license-key-change-me").validateMasterKey());
    }

    @Test
    void shortMasterKeyFails() {
        assertThrows(IllegalStateException.class,
                () -> signer(prod(), "short-key").validateMasterKey());
    }

    @Test
    void devFallbackAllowsWeakKeyOnlyWithExplicitFlag() {
        MockEnvironment env = dev();
        // Without the flag: rejected.
        assertThrows(IllegalStateException.class,
                () -> signer(env, "short-key").validateMasterKey());
        // With the flag: accepted (warns).
        env.setProperty("virbius.license.allow-insecure-dev-key", "true");
        assertDoesNotThrow(() -> signer(env, "short-key").validateMasterKey());
        // Blank is never accepted, even with the flag.
        assertThrows(IllegalStateException.class,
                () -> signer(env, " ").validateMasterKey());
    }

    @Test
    void devFallbackFlagRejectedOutsideDev() {
        MockEnvironment env = prod();
        env.setProperty("virbius.license.allow-insecure-dev-key", "true");
        assertThrows(IllegalStateException.class,
                () -> signer(env, STRONG_KEY).validateMasterKey());
    }

    @Test
    void strongKeyPassesAllProfiles() {
        assertDoesNotThrow(() -> signer(dev(), STRONG_KEY).validateMasterKey());
        assertDoesNotThrow(() -> signer(prod(), STRONG_KEY).validateMasterKey());
    }

    // ─── versioned envelope ───────────────────────────────────────────────

    @Test
    void encryptedPrivateKeyUsesV1EnvelopeAndRoundTrips() {
        LicenseSigner signer = signer(prod(), STRONG_KEY);
        LicenseSigner.KeyPairResult kp = signer.generateKeyPair();
        assertTrue(kp.encryptedPrivateKey().startsWith("v1:"),
                "new ciphertext must use the v1 envelope");
        byte[] decrypted = assertDoesNotThrow(() -> signer.decryptPrivateKey(kp.encryptedPrivateKey()));
        // Raw PKCS8 Ed25519 private key material is 48 bytes.
        assertEquals(48, decrypted.length);
    }

    @Test
    void legacyCiphertextStillDecrypts() throws Exception {
        byte[] raw = new byte[48];
        new java.security.SecureRandom().nextBytes(raw);

        // Rebuild the legacy envelope exactly as pre-v1 code did:
        // AES-GCM with key = SHA-256(masterKey), IV||ct, base64 without prefix.
        byte[] legacyKey = MessageDigest.getInstance("SHA-256")
                .digest(STRONG_KEY.getBytes(StandardCharsets.UTF_8));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(legacyKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(raw);
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        String legacyEnvelope = Base64.getEncoder().encodeToString(combined);

        LicenseSigner signer = signer(dev(), STRONG_KEY);
        assertArrayEquals(raw, signer.decryptPrivateKey(legacyEnvelope));
    }

    // ─── HKDF (RFC 5869, A.1 test vector) ─────────────────────────────────

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = LicenseSigner.hkdfSha256(ikm, salt, info, 42);
        assertEquals(
                "3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865",
                hexEncode(okm));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
