package io.virbius.control.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Signs short-lived evaluate access tokens (P0-min: control is the single issuer).
 *
 * <p>Token = compact EdDSA JWT: {@code header.payload.signature}, header carrying
 * the {@code kid} so the engine can select the verification key. The platform
 * signing key is generated on first use and stored (encrypted with the license
 * master key) in {@code tb_evaluate_token_issuers}; the public PEM is published
 * to Redis for the engine. The long-lived License JWT is NEVER used as a
 * per-request bearer — it is only presented once at the exchange endpoint.
 */
@Component
public class EvaluateTokenSigner {

    private static final Logger log = LoggerFactory.getLogger(EvaluateTokenSigner.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Claims accepted by the signer (subject, scope, sid are provided by the caller). */
    public record TokenRequest(
            String subject,
            String tenantId,
            String appId,
            String agentAid,
            String licenseId,
            String sid,
            boolean crossTenant,
            java.util.List<String> allowedTools,
            Integer riskQuota,
            Integer toolRateLimit,
            long ttlSeconds) {}

    /** Verified, decoded token claims (mirrors the payload map). */
    public record VerifiedToken(
            String kid,
            String issuer,
            String subject,
            String audience,
            String tenantId,
            String appId,
            String agentAid,
            String sid,
            String jti,
            boolean crossTenant,
            java.util.List<String> allowedTools,
            Integer riskQuota,
            Integer toolRateLimit,
            long issuedAt,
            long expiresAt) {}

    private volatile String kid;
    private volatile PrivateKey privateKey;
    private volatile PublicKey publicKey;
    private volatile String publicKeyPem;

    /** Activate a signing key.
     *
     *  @param rawPrivateKey the raw Ed25519 private key — either the 32-byte
     *         seed OR the full 48-byte PKCS#8 encoding (which is what
     *         {@link LicenseSigner#generateKeyPair()} stores/returns); both are
     *         accepted so the two key sources stay interchangeable. */
    public void activate(String kid, String publicKeyPem, byte[] rawPrivateKey) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            this.kid = kid;
            this.publicKeyPem = publicKeyPem;
            byte[] pkcs8 = rawPrivateKey.length == 32
                    ? encodePkcs8(rawPrivateKey)
                    : rawPrivateKey;
            this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            // The public key comes from the PEM (X509 SubjectPublicKeyInfo), NOT
            // from the private seed.
            this.publicKey = kf.generatePublic(new X509EncodedKeySpec(pemToDer(publicKeyPem)));
            log.info("evaluate-token signer activated (kid={})", kid);
        } catch (Exception e) {
            throw new IllegalStateException("failed to activate evaluate-token signing key: " + e.getMessage(), e);
        }
    }

    public boolean isReady() {
        return privateKey != null;
    }

    public String kid() {
        return kid;
    }

    public String publicKeyPem() {
        return publicKeyPem;
    }

    /** Mint a signed short token. */
    public String sign(TokenRequest req) {
        if (privateKey == null) {
            throw new IllegalStateException("evaluate-token signer not activated");
        }
        try {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", "EdDSA");
            header.put("typ", "JWT");
            header.put("kid", kid);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("iss", "control");
            payload.put("sub", req.subject());
            payload.put("aud", "evaluate");
            payload.put("jti", newJti());
            if (req.tenantId() != null) {
                payload.put("tenant_id", req.tenantId());
            }
            if (req.appId() != null) {
                payload.put("app_id", req.appId());
            }
            if (req.agentAid() != null && !req.agentAid().isBlank()) {
                payload.put("agent_aid", req.agentAid());
            }
            Map<String, Object> scope = new LinkedHashMap<>();
            if (req.crossTenant()) {
                scope.put("cross_tenant", true);
                scope.put("tenant_ids", java.util.List.of());
            }
            if (req.allowedTools() != null) {
                scope.put("allowed_tools", req.allowedTools());
            }
            if (req.riskQuota() != null) {
                scope.put("risk_quota", req.riskQuota());
            }
            if (req.toolRateLimit() != null) {
                scope.put("tool_rate_limit", req.toolRateLimit());
            }
            payload.put("scope", scope);
            if (req.sid() != null && !req.sid().isBlank()) {
                payload.put("sid", req.sid());
            }
            long now = Instant.now().getEpochSecond();
            payload.put("iat", now);
            payload.put("exp", now + req.ttlSeconds());

            String headerB64 = b64(JSON.writeValueAsBytes(header));
            String payloadB64 = b64(JSON.writeValueAsBytes(payload));
            String message = headerB64 + "." + payloadB64;

            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(privateKey);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            String sigB64 = b64(sig.sign());
            return message + "." + sigB64;
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign evaluate token: " + e.getMessage(), e);
        }
    }

    /**
     * Verify a token against the ACTIVE key (used by unit tests and control-side
     * self-checks; the engine has its own verifier with the multi-issuer registry).
     */
    public VerifiedToken verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            Map<String, Object> payload = JSON.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Map.class);
            return toVerified(payload, kid);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse + validate an existing token WITHOUT signature verification (engine-side
     *  verification flow first stage; also used to extract claims from a token minted
     *  by an older kid). Returns null when the payload is malformed. */
    public static VerifiedToken parseUnverified(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String kid = JSON.readValue(headerJson, Map.class).get("kid").toString();
            Map<String, Object> payload = JSON.readValue(
                    Base64.getUrlDecoder().decode(parts[1]), Map.class);
            return toVerified(payload, kid);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static VerifiedToken toVerified(Map<String, Object> payload, String kid) {
        Map<String, Object> scope = (Map<String, Object>) payload.getOrDefault("scope", Map.of());
        return new VerifiedToken(
                kid,
                str(payload.get("iss")),
                str(payload.get("sub")),
                str(payload.get("aud")),
                nullableStr(payload.get("tenant_id")),
                nullableStr(payload.get("app_id")),
                nullableStr(payload.get("agent_aid")),
                nullableStr(payload.get("sid")),
                nullableStr(payload.get("jti")),
                Boolean.TRUE.equals(scope.get("cross_tenant")),
                (java.util.List<String>) scope.get("allowed_tools"),
                scope.get("risk_quota") instanceof Number n ? n.intValue() : null,
                scope.get("tool_rate_limit") instanceof Number n ? n.intValue() : null,
                payload.get("iat") instanceof Number n ? n.longValue() : 0,
                payload.get("exp") instanceof Number n ? n.longValue() : 0);
    }

    /** Extract the kid from a token header (no verification). */
    public static String tokenKid(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            Map<String, Object> header = JSON.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), Map.class);
            Object kid = header.get("kid");
            return kid == null ? null : kid.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Verify a license JWT (compact EdDSA JWT signed by the per-tenant license key):
     *  parse the claims, verify the signature with the given public PEM. */
    public static boolean verifyWithPem(String token, String publicKeyPem) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return false;
            }
            byte[] pubDer = pemToDer(publicKeyPem);
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            PublicKey pub = kf.generatePublic(new X509EncodedKeySpec(pubDer));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
            return sig.verify(Base64.getUrlDecoder().decode(parts[2]));
        } catch (Exception e) {
            return false;
        }
    }

    public static Map<String, Object> parsePayload(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }
            return JSON.readValue(Base64.getUrlDecoder().decode(parts[1]), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static String newJti() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    public static String newKid() {
        return "etk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // -- crypto helpers -------------------------------------------------------

    private static byte[] encodePkcs8(byte[] raw32) {
        // PKCS#8 Ed25519 private key: 302e020100300506032b657004220420 || raw32
        byte[] out = new byte[48];
        out[0] = 0x30; out[1] = 0x2e; out[2] = 0x02; out[3] = 0x01; out[4] = 0x00;
        out[5] = 0x30; out[6] = 0x05; out[7] = 0x06; out[8] = 0x03; out[9] = 0x2b;
        out[10] = 0x65; out[11] = 0x70; out[12] = 0x04; out[13] = 0x22;
        out[14] = 0x04; out[15] = 0x20;
        System.arraycopy(raw32, 0, out, 16, 32);
        return out;
    }

    static byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /** DER (X509 SubjectPublicKeyInfo) → PEM. */
    public static String toPublicPem(byte[] der) {
        StringBuilder sb = new StringBuilder("-----BEGIN PUBLIC KEY-----\n");
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        sb.append(b64).append("\n-----END PUBLIC KEY-----\n");
        return sb.toString();
    }

    /** PEM (public) → raw 32-byte Ed25519 key (trailing 12 SPKI header bytes stripped). */
    public static byte[] pemToRaw32(String pem) {
        byte[] der = pemToDer(pem);
        byte[] raw = new byte[32];
        System.arraycopy(der, der.length - 32, raw, 0, 32);
        return raw;
    }

    private static String b64(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nullableStr(Object o) {
        return o == null ? null : o.toString();
    }
}
