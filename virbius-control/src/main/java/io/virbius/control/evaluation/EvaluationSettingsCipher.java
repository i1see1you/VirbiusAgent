package io.virbius.control.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 评估服务 token 的静态加密（AES-256-GCM）。
 *
 * <p>密钥来源：{@code virbius.evaluation.settings-key}（推荐，独立密钥）；缺省时回退用
 * {@code virbius.license.master-key} 经 HKDF 派生（dev/compose 便利）。两者都缺 → 不可用
 * （保存 token 时报错，读取 env 配置不受影响）。
 *
 * <p>信封格式：{@code esv1:1:<base64(iv[12] || ct || tag[16])>}。解密失败（密钥变更/密文损坏）
 * 必须 fail-fast——静默降级会让 token 变成空值、表现为"未配置"。
 */
@Component
public class EvaluationSettingsCipher {

    private static final String PREFIX = "esv1:1:";
    private static final byte[] SALT = "virbius/evaluation/settings/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO = "token-v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;

    public EvaluationSettingsCipher(
            @Value("${virbius.evaluation.settings-key:}") String settingsKey,
            @Value("${virbius.license.master-key:}") String licenseMasterKey) {
        byte[] ikm = firstNonBlank(settingsKey, licenseMasterKey);
        this.key = ikm == null ? null : hkdfSha256(ikm, SALT, INFO, 32);
    }

    /** 是否有可用密钥（无 → 不能保存 token）。 */
    public boolean available() {
        return key != null;
    }

    public String encrypt(String plain) {
        requireKey();
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("encrypt evaluation token failed: " + e.getMessage(), e);
        }
    }

    public String decrypt(String envelope) {
        requireKey();
        if (envelope == null || envelope.isBlank()) {
            return "";
        }
        if (!envelope.startsWith(PREFIX)) {
            throw new IllegalStateException("unsupported evaluation token envelope");
        }
        try {
            byte[] all = Base64.getDecoder().decode(envelope.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LEN];
            byte[] ct = new byte[all.length - IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            System.arraycopy(all, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "评估服务 token 解密失败（VIRBIUS_SETTINGS_MASTER_KEY 变更？）：" + e.getMessage(), e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "未配置 VIRBIUS_SETTINGS_MASTER_KEY（或 VIRBIUS_LICENSE_MASTER_KEY），无法加密保存评估 token");
        }
    }

    private static byte[] firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            if (a.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("virbius.evaluation.settings-key 必须 >= 32 字节");
            }
            return a.getBytes(StandardCharsets.UTF_8);
        }
        if (b != null && !b.isBlank()) {
            return b.getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    /** RFC 5869 HKDF-SHA256。 */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("hkdf failed: " + e.getMessage(), e);
        }
    }
}
