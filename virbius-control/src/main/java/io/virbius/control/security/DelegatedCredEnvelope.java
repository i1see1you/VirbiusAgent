package io.virbius.control.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Delegated credential envelope (v2.5-min, A+B1 wire contract).
 *
 * <p>Format: {@code dcv1:1:<base64-standard-padded(iv[12] || ct || tag[16])>},
 * AES-256-GCM. The tenant KEK is {@code HKDF-SHA256(master, salt, info)} with
 * {@code salt = lenprefix("virbius/delegated/kek/v1")} and {@code info =
 * lenprefix("tenant-kek-v1") || lenprefix(tenant_id) || u64_be(1)}; phase 1
 * pins kek_version = 1. AAD binds every row field:
 * {@code lenprefix(tenant_id) || lenprefix(handle) || lenprefix(host) ||
 * lenprefix(header_name) || lenprefix(header_prefix)} where
 * {@code lenprefix(x) = u32_be(len(x)) || x}.
 *
 * <p>The KEK is handed to the tenant's proxy once (deploy-time config); the
 * proxy decrypts with the raw KEK — it never performs HKDF. Envelope bytes
 * must stay byte-identical with the Rust side (see delegated.rs vectors).
 */
public final class DelegatedCredEnvelope {

    public static final String PREFIX = "dcv1:1:";
    static final int IV_LEN = 12;
    static final int TAG_BITS = 128;

    private static final byte[] HKDF_SALT = lenprefix("virbius/delegated/kek/v1");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] masterKey;

    public DelegatedCredEnvelope(String masterKey) {
        byte[] raw = masterKey == null ? new byte[0] : masterKey.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "virbius.delegated.master-key must be >= 32 bytes (fail-fast)");
        }
        this.masterKey = raw;
    }

    /** Derive the per-tenant KEK (phase 1: kek_version pinned to 1). */
    public byte[] deriveTenantKek(String tenantId) {
        byte[] tenant = tenantId.getBytes(StandardCharsets.UTF_8);
        byte[] info = concat(
                lenprefix("tenant-kek-v1"),
                lenprefix(tenantId),
                u64be(1L));
        return LicenseSigner.hkdfSha256(masterKey, HKDF_SALT, info, 32);
    }

    /** Base64 (standard, padded) of the tenant KEK — for one-time ops reveal. */
    public String revealTenantKekB64(String tenantId) {
        return Base64.getEncoder().encodeToString(deriveTenantKek(tenantId));
    }

    /** Encrypt a credential value bound to its row fields (random 96-bit IV). */
    public String encrypt(String tenantId, String handle, String host,
            String headerName, String headerPrefix, String value) {
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        return encryptWithIv(iv, deriveTenantKek(tenantId), tenantId, handle, host,
                headerName, headerPrefix, value);
    }

    /** Deterministic encrypt for cross-language test vectors (fixed IV). */
    String encryptWithIv(byte[] iv, byte[] kek, String tenantId, String handle, String host,
            String headerName, String headerPrefix, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(tenantId, handle, host, headerName, headerPrefix));
            byte[] ct = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ct, 0, combined, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("delegated envelope encrypt failed: " + e.getMessage(), e);
        }
    }

    /** AAD = lenprefix(tenant)||lenprefix(handle)||lenprefix(host)||… */
    static byte[] aad(String tenantId, String handle, String host,
            String headerName, String headerPrefix) {
        return concat(
                lenprefix(tenantId),
                lenprefix(handle),
                lenprefix(host),
                lenprefix(headerName),
                lenprefix(headerPrefix));
    }

    /** lenprefix(x) = u32_be(len(x)) || x (wire contract with the Rust proxy). */
    static byte[] lenprefix(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[4 + b.length];
        out[0] = (byte) (b.length >>> 24);
        out[1] = (byte) (b.length >>> 16);
        out[2] = (byte) (b.length >>> 8);
        out[3] = (byte) b.length;
        System.arraycopy(b, 0, out, 4, b.length);
        return out;
    }

    static byte[] u64be(long v) {
        return new byte[] {
            (byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
            (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v };
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int i = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, i, p.length);
            i += p.length;
        }
        return out;
    }
}
