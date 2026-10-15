package io.virbius.engine.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure verification logic (no Spring, no Redis) for the issuer registry. */
class TrustedIssuerRegistryVerifyTest {

    private KeyPair kp;
    private TrustedIssuerRegistry registry;
    private String kid;

    @BeforeEach
    void setUp() {
        try {
            kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        registry = new TrustedIssuerRegistry(null, "ignored", 0, 900);
        kid = "etk_test";
        registry.registerBootstrapKey(kid, pem(kp));
    }

    private static String pem(KeyPair kp) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes())
                .encodeToString(kp.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    private String token(String tenant, String sub, String aud, long iat, long exp, String sid) {
        try {
            String header = b64(("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}").getBytes());
            String payloadJson = "{\"iss\":\"control\",\"sub\":\"" + sub + "\",\"aud\":\"" + aud
                    + "\",\"jti\":\"j1\",\"tenant_id\":\"" + tenant
                    + "\",\"app_id\":\"app-1\",\"agent_aid\":\"aid-1\""
                    + (sid != null ? ",\"sid\":\"" + sid + "\"" : "")
                    + ",\"scope\":{\"allowed_tools\":[\"read_file\"],\"risk_quota\":80},"
                    + "\"iat\":" + iat + ",\"exp\":" + exp + "}";
            String payload = b64(payloadJson.getBytes());
            String message = header + "." + payload;
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(kp.getPrivate());
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            return message + "." + b64(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    @Test
    void verifiesValidToken() {
        long now = System.currentTimeMillis() / 1000;
        Optional<TrustedIssuerRegistry.IssuedTokenClaims> claims =
                registry.verify(token("default", "agent:aid-1", "evaluate", now, now + 900, "s1"), "evaluate");
        assertTrue(claims.isPresent());
        assertEquals("default", claims.get().tenantId());
        assertEquals("aid-1", claims.get().agentAid());
        assertEquals("s1", claims.get().sid());
        assertEquals(java.util.List.of("read_file"), claims.get().allowedTools());
        assertEquals(80, claims.get().riskQuota());
    }

    @Test
    void rejectsWrongAudience() {
        long now = System.currentTimeMillis() / 1000;
        assertFalse(registry.verify(token("default", "agent:aid-1", "other", now, now + 900, null), "evaluate").isPresent());
    }

    @Test
    void rejectsExpired() {
        long now = System.currentTimeMillis() / 1000;
        assertFalse(registry.verify(token("default", "agent:aid-1", "evaluate", now - 1000, now - 100, null), "evaluate").isPresent());
    }

    @Test
    void rejectsUnknownKid() {
        long now = System.currentTimeMillis() / 1000;
        String t = token("default", "agent:aid-1", "evaluate", now, now + 900, null)
                .replaceFirst("eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCIsImtpZCI6", "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCIsImtpZCI6");
        // Tamper the kid by re-encoding the header.
        try {
            String header = b64(("{\"alg\":\"EdDSA\",\"typ\":\"JWT\",\"kid\":\"unknown-kid\"}").getBytes());
            String[] parts = t.split("\\.");
            String tampered = header + "." + parts[1] + "." + parts[2];
            assertFalse(registry.verify(tampered, "evaluate").isPresent());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsTamperedSignature() {
        long now = System.currentTimeMillis() / 1000;
        String t = token("default", "agent:aid-1", "evaluate", now, now + 900, null);
        String[] parts = t.split("\\.");
        String tampered = parts[0] + "." + parts[1] + "." + (parts[2].startsWith("A") ? "B" : "A")
                + parts[2].substring(1);
        assertFalse(registry.verify(tampered, "evaluate").isPresent());
    }

    @Test
    void rejectsGarbage() {
        assertFalse(registry.verify("garbage", "evaluate").isPresent());
        assertFalse(registry.verify("a.b", "evaluate").isPresent());
    }

    @Test
    void verifyCacheHitsForRepeatedToken() {
        long now = System.currentTimeMillis() / 1000;
        String t = token("default", "agent:aid-1", "evaluate", now, now + 900, null);
        assertNotNull(registry.verify(t, "evaluate").orElse(null));
        // Second call served from cache (same result without re-verifying).
        assertNotNull(registry.verify(t, "evaluate").orElse(null));
    }

    @Test
    void bootstrapKeyMissingThrows() {
        TrustedIssuerRegistry empty = new TrustedIssuerRegistry(null, "ignored", 0, 900);
        assertFalse(empty.hasAnyKey());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> empty.registerBootstrapKey("k", "not-a-pem"));
    }
}
