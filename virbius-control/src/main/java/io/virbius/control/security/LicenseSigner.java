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
    private static final String DEFAULT_KEY = "virbius-default-license-key-change-me";

    private final ObjectMapper json = new ObjectMapper();
    private final Environment env;

    public LicenseSigner(Environment env) {
        this.env = env;
    }

    @Value("${virbius.license.master-key:virbius-default-license-key-change-me}")
    private String masterKey;

    @PostConstruct
    public void validateMasterKey() {
        boolean isProd = env.matchesProfiles("prod");
        if (DEFAULT_KEY.equals(masterKey)) {
            if (isProd) {
                throw new IllegalStateException(
                    "virbius.license.master-key is set to the insecure default value. " +
                    "Set the VIRBIUS_LICENSE_MASTER_KEY environment variable to a strong secret before starting in prod profile.");
            }
            log.warn("virbius.license.master-key is using the insecure default value. " +
                "This is acceptable for local development only — set a strong secret for any non-dev deployment.");
        }
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
     */
    public byte[] decryptPrivateKey(String encryptedBase64) {
        try {
            byte[] encrypted = Base64.getDecoder().decode(encryptedBase64);
            byte[] keyBytes = deriveKey();
            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[12];
            System.arraycopy(encrypted, 0, iv, 0, 12);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
            byte[] decrypted = cipher.doFinal(encrypted, 12, encrypted.length - 12);
            return decrypted;
        } catch (Exception e) {
            throw new IllegalStateException("failed to decrypt private key: " + e.getMessage(), e);
        }
    }

    private byte[] deriveKey() throws Exception {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        return sha256.digest(masterKey.getBytes(StandardCharsets.UTF_8));
    }

    private String encryptPrivateKey(byte[] raw) throws Exception {
        byte[] keyBytes = deriveKey();
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(raw);
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
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
