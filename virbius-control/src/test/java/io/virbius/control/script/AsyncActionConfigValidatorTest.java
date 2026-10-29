package io.virbius.control.script;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AsyncActionConfigValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void blankOrNullPasses() {
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate(null, JSON));
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate("", JSON));
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate("  ", JSON));
    }

    @Test
    void webhookValidPasses() {
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate(
                "{\"type\":\"webhook\",\"url\":\"https://hooks.example.com/x\",\"body\":{\"r\":\"{{rule_id}}\"}}", JSON));
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate(
                "{\"type\":\"webhook\",\"url\":\"http://127.0.0.1:18803/hook\"}", JSON));
    }

    @Test
    void kafkaValidPasses() {
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate("{\"type\":\"kafka\"}", JSON));
        assertDoesNotThrow(() -> AsyncActionConfigValidator.validate(
                "{\"type\":\"kafka\",\"topic\":\"virbius-rule-actions\",\"message\":{\"r\":\"{{rule_id}}\"}}", JSON));
    }

    @Test
    void legacyRedisStreamRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"redis_stream\",\"stream_key\":\"k\"}", JSON));
        assertTrue(e.getMessage().contains("redis_stream"));
        assertTrue(e.getMessage().contains("kafka"));
    }

    @Test
    void unknownTypeRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"email\"}", JSON));
        assertTrue(e.getMessage().contains("webhook or kafka"));
    }

    @Test
    void missingTypeRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"url\":\"http://x\"}", JSON));
        assertTrue(e.getMessage().contains("type is required"));
    }

    @Test
    void invalidJsonRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("not-json", JSON));
        assertTrue(e.getMessage().contains("valid JSON"));
    }

    @Test
    void nonObjectRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("[1,2]", JSON));
        assertTrue(e.getMessage().contains("JSON object"));
    }

    @Test
    void webhookUrlRequiredAndHttpOnly() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"webhook\"}", JSON));
        assertTrue(missing.getMessage().contains("url is required"));
        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"webhook\",\"url\":\"file:///etc/passwd\"}", JSON));
        assertTrue(scheme.getMessage().contains("http or https"));
        IllegalArgumentException ftp = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"webhook\",\"url\":\"ftp://x/y\"}", JSON));
        assertTrue(ftp.getMessage().contains("http or https"));
    }

    @Test
    void webhookBodyMustBeObject() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate(
                        "{\"type\":\"webhook\",\"url\":\"http://x/h\",\"body\":\"oops\"}", JSON));
        assertTrue(e.getMessage().contains("body must be an object"));
    }

    @Test
    void kafkaTopicPattern() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate("{\"type\":\"kafka\",\"topic\":\"bad topic!\"}", JSON));
        assertTrue(e.getMessage().contains("topic"));
        assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate(
                        "{\"type\":\"kafka\",\"topic\":\"" + "a".repeat(250) + "\"}", JSON));
    }

    @Test
    void kafkaMessageMustBeObject() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate(
                        "{\"type\":\"kafka\",\"message\":\"oops\"}", JSON));
        assertTrue(e.getMessage().contains("message must be an object"));
    }

    @Test
    void oversizedConfigRejected() {
        String big = "{\"type\":\"kafka\",\"message\":{\"pad\":\"" + "x".repeat(9000) + "\"}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncActionConfigValidator.validate(big, JSON));
        assertTrue(e.getMessage().contains("8192"));
    }
}
