package io.virbius.control.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ApiKeyHasher {

    private ApiKeyHasher() {}

    /**
     * Legacy, unkeyed hash — kept only to authenticate rows stored before the
     * pepper was introduced. New credentials and lookups prefer
     * {@link #hmacSha256Hex(String, String)}.
     */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Keyed hash (HMAC-SHA256) with a server-side pepper. Deterministic so the
     * existing {@code UNIQUE(key_hash)} lookup keeps working, while a leaked
     * database alone is not enough to verify guesses offline.
     */
    public static String hmacSha256Hex(String pepper, String raw) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    (pepper == null ? "" : pepper).getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] hash = mac.doFinal(raw.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String toHex(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
