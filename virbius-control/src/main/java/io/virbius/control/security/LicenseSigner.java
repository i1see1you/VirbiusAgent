package io.virbius.control.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.AgentLicense;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Issues and signs Runtime License JWTs using EdDSA (Ed25519).
 *
 * <p>The License JWT is consumed by virbius-core (edge layer) for tool allowlist
 * enforcement, risk quota tracking, and session identity. The signature is
 * verified with the public key; the private key never leaves control.
 */
@Component
public class LicenseSigner {

    private static final Logger log = LoggerFactory.getLogger(LicenseSigner.class);
    private static final String JWT_HEADER = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
    /** Minimum master-key length in characters (32 chars ≈ 256 bits of entropy for random keys). */
    private static final int MIN_MASTER_KEY_LENGTH = 32;
    /** Envelope prefix for ciphertext encrypted with the HKDF-derived key (v1). */
    private static final String ENVELOPE_V1 = "v1:";
    private static final byte[] HKDF_SALT = "virbius-license-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "license-private-key-v1".getBytes(StandardCharsets.UTF_8);
    /** Known-insecure master keys that must never be accepted (historical defaults / trivial values). */
    private static final java.util.Set<String> DENYLIST = java.util.Set.of(
            "virbius-default-license-key-change-me",
            "local-dev-license-master-key",
            "local-dev-compose-master-key-0123456789",
            "admin",
            "password",
            "change-me",
            "changeme",
            "secret");

    private final ObjectMapper json = new ObjectMapper();
    private final Environment env;

    public LicenseSigner(Environment env) {
        this.env = env;
    }

    @Value("${virbius.license.master-key:}")
    private String masterKey;

    /**
     * Fail fast when the master key is missing or insecure.
     *
     * <p>Blank keys are rejected in every profile. Denylisted or too-short keys are
     * rejected unless the {@code dev} profile is active AND
     * {@code virbius.license.allow-insecure-dev-key=true} is explicitly set
     * (migration escape hatch for legacy local databases only; setting the flag
     * outside {@code dev} is itself rejected).
     */
    @PostConstruct
    public void validateMasterKey() {
        boolean isDev = env.matchesProfiles("dev");
        boolean allowInsecureDevKey =
                env.getProperty("virbius.license.allow-insecure-dev-key", Boolean.class, false);

        if (allowInsecureDevKey && !isDev) {
            throw new IllegalStateException(
                "virbius.license.allow-insecure-dev-key=true is only permitted with the dev profile.");
        }
        if (masterKey == null || masterKey.isBlank()) {
            throw new IllegalStateException(
                "virbius.license.master-key is required. Set the VIRBIUS_LICENSE_MASTER_KEY " +
                "environment variable to a strong secret (>= " + MIN_MASTER_KEY_LENGTH + " chars).");
        }

        boolean insecure = masterKey.length() < MIN_MASTER_KEY_LENGTH || DENYLIST.contains(masterKey);
        if (!insecure) {
            return;
        }
        if (isDev && allowInsecureDevKey) {
            log.warn("virbius.license.master-key is weak/denylisted but allowed via " +
                "virbius.license.allow-insecure-dev-key=true (dev only). Rotate to a strong key " +
                "before any non-dev deployment.");
            return;
        }
        throw new IllegalStateException(
            "virbius.license.master-key is insecure (too short or a known default). " +
            "Set VIRBIUS_LICENSE_MASTER_KEY to a strong secret (>= " + MIN_MASTER_KEY_LENGTH +
            " chars). For legacy local dev databases only, the dev profile may set " +
            "virbius.license.allow-insecure-dev-key=true to bypass this check.");
    }

    /**
     * Build a signed License JWT from the given license fields.
     *
     * @param signingKeyRaw the raw Ed25519 private key bytes (32 bytes)
     * @param license       the license domain object
     * @return the signed JWT string
     */
    public String sign(byte[] signingKeyRaw, AgentLicense license) {
        try {
            // Build claims payload
            Map<String, Object> claims = Map.of(
                    "app_id", license.getAppId(),
                    "tenant_id", license.getTenantId(),
                    "agent_name", license.getAgentName() != null ? license.getAgentName() : "",
                    "agent_aid", license.getAgentAid() != null ? license.getAgentAid() : "",
                    "allowed_tools", license.getAllowedTools() != null ? license.getAllowedTools() : List.of(),
                    "risk_quota", license.getRiskQuota(),
                    "tool_rate_limit", license.getToolRateLimit(),
                    "exp", license.getExpiry() != null ? license.getExpiry().getEpochSecond() : 0L,
                    "iat", Instant.now().getEpochSecond());

            String payloadJson = json.writeValueAsString(claims);
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String message = JWT_HEADER + "." + payloadB64;

            // Ed25519 sign using pure Java EdDSA (JDK 15+)
            java.security.Signature edSig = java.security.Signature.getInstance("Ed25519");
            java.security.PrivateKey privateKey = rawToEd25519Private(signingKeyRaw);
            edSig.initSign(privateKey);
            edSig.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] signature = edSig.sign();

            String sigB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
            return message + "." + sigB64;
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign license: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a new Ed25519 key pair for a tenant.
     *
     * @return [privateKeyBytes (32), publicKeyBytes (32)]
     */
    /**
     * Compute the SHA-256 hex digest of a JWT string.
     * <p>Used to store a non-reversible fingerprint of the License JWT
     * for audit/identification purposes. The original JWT is only returned
     * once at issuance time.
     *
     * @param jwt the signed JWT string
     * @return 64-character lowercase hex string
     */
    public static String sha256Hex(String jwt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(jwt.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to hash jwt: " + e.getMessage(), e);
        }
    }

    public KeyPairResult generateKeyPair() {
        try {
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("Ed25519");
            java.security.KeyPair kp = kpg.generateKeyPair();

            byte[] pubRaw = kp.getPublic().getEncoded();
            byte[] privRaw = kp.getPrivate().getEncoded();

            // Convert to PEM
            String pubPem = toPem("PUBLIC KEY", pubRaw);
            String privEnc = encryptPrivateKey(privRaw);

            String keyId = "lk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            return new KeyPairResult(keyId, pubPem, privEnc);
        } catch (Exception e) {
            throw new IllegalStateException("failed to generate Ed25519 key pair: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypt the stored private key and return raw key bytes.
     *
     * <p>Supports two envelope versions: {@code v1:} prefix uses an
     * HKDF-SHA256-derived key (domain-separated); no prefix is the legacy
     * SHA-256(masterKey) derivation. Rows written by this class always use v1.
     */
    public byte[] decryptPrivateKey(String encryptedBase64) {
        try {
            if (encryptedBase64 != null && encryptedBase64.startsWith(ENVELOPE_V1)) {
                byte[] encrypted = Base64.getDecoder().decode(encryptedBase64.substring(ENVELOPE_V1.length()));
                return aesGcmDecrypt(encrypted, deriveKeyV1());
            }
            byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
            return aesGcmDecrypt(encrypted, deriveKeyLegacy());
        } catch (Exception e) {
            throw new IllegalStateException("failed to decrypt private key: " + e.getMessage(), e);
        }
    }

    /** Legacy key derivation: SHA-256(masterKey). Kept only to decrypt pre-v1 rows. */
    private byte[] deriveKeyLegacy() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return sha256.digest(masterKey.getBytes(StandardCharsets.UTF_8));
    }

    /** v1 key derivation: HKDF-SHA256(masterKey, salt, info) — domain-separated from other uses. */
    private byte[] deriveKeyV1() {
        return hkdfSha256(masterKey.getBytes(StandardCharsets.UTF_8), HKDF_SALT, HKDF_INFO, 32);
    }

    private String encryptPrivateKey(byte[] raw) throws Exception {
        byte[] keyBytes = deriveKeyV1();
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(raw);
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return ENVELOPE_V1 + Base64.getEncoder().encodeToString(combined);
    }

    private static byte[] aesGcmDecrypt(byte[] encrypted, byte[] keyBytes) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        System.arraycopy(encrypted, 0, iv, 0, 12);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        return cipher.doFinal(encrypted, 12, encrypted.length - 12);
    }

    /** HKDF extract-and-expand (RFC 5869) with HMAC-SHA256. */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            byte[] t = new byte[0];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int counter = 1;
            while (out.size() < length) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update((byte) counter++);
                t = mac.doFinal();
                out.write(t, 0, Math.min(t.length, length - out.size()));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("hkdf derivation failed: " + e.getMessage(), e);
        }
    }

    private static String toPem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    @SuppressWarnings("java:S3329")
    private static java.security.PrivateKey rawToEd25519Private(byte[] raw) throws Exception {
        // PKCS8 encoding for Ed25519 private key
        // For JDK 15+, we use KeyFactory with PKCS8 DER
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("Ed25519");
        // The raw bytes from KeyPairGenerator are already in PKCS8 format
        java.security.spec.PKCS8EncodedKeySpec spec = new java.security.spec.PKCS8EncodedKeySpec(raw);
        return kf.generatePrivate(spec);
    }

    public record KeyPairResult(String keyId, String publicKeyPem, String encryptedPrivateKey) {}
}
