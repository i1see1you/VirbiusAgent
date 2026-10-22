package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** token 静态加密：AES-256-GCM，密钥变更必须 fail-fast。 */
class EvaluationSettingsCipherTest {

    private static final String KEY = "settings-master-key-0123456789abcdef";

    private EvaluationSettingsCipher cipher(String key) {
        return new EvaluationSettingsCipher(key, "");
    }

    @Test
    void roundtrip() {
        EvaluationSettingsCipher c = cipher(KEY);
        assertTrue(c.available());
        String ct = c.encrypt("vrb-service-token");
        assertTrue(ct.startsWith("esv1:1:"));
        assertFalse(ct.contains("vrb-service-token"));
        assertNotEquals(ct, c.encrypt("vrb-service-token"), "随机 IV：密文应不同");
        org.junit.jupiter.api.Assertions.assertEquals("vrb-service-token", c.decrypt(ct));
    }

    @Test
    void wrongKeyFailsLoudly() {
        String ct = cipher(KEY).encrypt("secret");
        EvaluationSettingsCipher other = cipher("another-settings-master-key-0123456789");
        assertThrows(IllegalStateException.class, () -> other.decrypt(ct));
    }

    @Test
    void noKeyMeansUnavailable() {
        EvaluationSettingsCipher c = cipher("");
        assertFalse(c.available());
        assertThrows(IllegalStateException.class, () -> c.encrypt("x"));
    }

    @Test
    void shortSettingsKeyRejected() {
        assertThrows(IllegalStateException.class, () -> cipher("short"));
    }
}
