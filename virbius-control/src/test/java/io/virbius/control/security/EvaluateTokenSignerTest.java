package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.security.EvaluateTokenSigner.TokenRequest;
import io.virbius.control.security.EvaluateTokenSigner.VerifiedToken;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvaluateTokenSignerTest {

    private EvaluateTokenSigner signer;
    private String kid;

    @BeforeEach
    void setUp() {
        // Ed25519 keypair directly (no LicenseSigner dependency — crypto only).
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
            java.security.KeyPair kp = kpg.generateKeyPair();
            byte[] pubDer = kp.getPublic().getEncoded();
            byte[] privDer = kp.getPrivate().getEncoded();
            // Extract raw 32-byte private key from PKCS#8 (last 32 bytes).
            byte[] rawPriv = new byte[32];
            System.arraycopy(privDer, privDer.length - 32, rawPriv, 0, 32);
            kid = EvaluateTokenSigner.newKid();
            String pem = EvaluateTokenSigner.toPublicPem(pubDer);
            signer = new EvaluateTokenSigner();
            signer.activate(kid, pem, rawPriv);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static TokenRequest agentRequest(long ttl) {
        return new TokenRequest(
                "agent:aid:test-1", "default", "app-1", "aid:test-1", "lic_1",
                "sess-1", false, List.of("read_file", "curl"), 80, 50, ttl);
    }

    @Test
    void signAndVerifyRoundTrip() {
        String token = signer.sign(agentRequest(900));
        VerifiedToken verified = signer.verify(token);
        assertNotNull(verified);
        assertEquals(kid, verified.kid());
        assertEquals("control", verified.issuer());
        assertEquals("agent:aid:test-1", verified.subject());
        assertEquals("evaluate", verified.audience());
        assertEquals("default", verified.tenantId());
        assertEquals("app-1", verified.appId());
        assertEquals("aid:test-1", verified.agentAid());
        assertEquals("sess-1", verified.sid());
        assertNotNull(verified.jti());
        assertFalse_(verified.crossTenant());
        assertEquals(List.of("read_file", "curl"), verified.allowedTools());
        assertEquals(80, verified.riskQuota());
        assertEquals(50, verified.toolRateLimit());
        assertTrue(verified.expiresAt() > verified.issuedAt());
        assertTrue(verified.expiresAt() - verified.issuedAt() <= 900);
    }

    @Test
    void parseUnverifiedExtractsKidAndClaims() {
        String token = signer.sign(agentRequest(900));
        assertEquals(kid, EvaluateTokenSigner.tokenKid(token));
        VerifiedToken parsed = EvaluateTokenSigner.parseUnverified(token);
        assertNotNull(parsed);
        assertEquals(kid, parsed.kid());
        assertEquals("agent:aid:test-1", parsed.subject());
    }

    @Test
    void verifyRejectsTamperedPayload() {
        String token = signer.sign(agentRequest(900));
        String[] parts = token.split("\\.");
        // Flip a char in the payload.
        String tampered = parts[0] + "." + (parts[1].startsWith("A") ? "B" : "A") + parts[1].substring(1)
                + "." + parts[2];
        assertNull(signer.verify(tampered));
    }

    @Test
    void verifyRejectsGarbage() {
        assertNull(signer.verify("not-a-token"));
        assertNull(signer.verify("a.b"));
        assertNull(EvaluateTokenSigner.parseUnverified("x.y"));
        assertNull(EvaluateTokenSigner.tokenKid("x.y"));
    }

    @Test
    void serviceTokenHasCrossTenantScopeAndNoTenant() {
        TokenRequest req = new TokenRequest(
                "service:control-simulate", null, null, null, null, null,
                true, List.of("*"), null, null, 900);
        String token = signer.sign(req);
        VerifiedToken verified = signer.verify(token);
        assertNotNull(verified);
        assertNull(verified.tenantId());
        assertTrue(verified.crossTenant());
        assertEquals(List.of("*"), verified.allowedTools());
    }

    @Test
    void verifyWithPemValidatesThirdPartyToken() {
        // Independent keypair simulating a per-tenant license key.
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
            java.security.KeyPair kp = kpg.generateKeyPair();
            String pubPem = EvaluateTokenSigner.toPublicPem(kp.getPublic().getEncoded());
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            String header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
            String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"app_id\":\"a\",\"tenant_id\":\"default\"}".getBytes());
            String message = header + "." + payload;
            sig.initSign(kp.getPrivate());
            sig.update(message.getBytes());
            String sigB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign());
            String token = message + "." + sigB64;
            assertTrue(EvaluateTokenSigner.verifyWithPem(token, pubPem));
            // Wrong key must fail.
            java.security.KeyPair other = kpg.generateKeyPair();
            String otherPem = EvaluateTokenSigner.toPublicPem(other.getPublic().getEncoded());
            org.junit.jupiter.api.Assertions.assertFalse(EvaluateTokenSigner.verifyWithPem(token, otherPem));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assertFalse_(boolean v) {
        org.junit.jupiter.api.Assertions.assertFalse(v);
    }
}
