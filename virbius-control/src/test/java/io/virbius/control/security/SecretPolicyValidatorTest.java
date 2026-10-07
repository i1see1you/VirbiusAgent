package io.virbius.control.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecretPolicyValidatorTest {

    private static final String STRONG = "unit-test-strong-master-key-0123456789abcdef0123456789abcdef";
    private static final String STRONG_PEPPER = "unit-test-strong-pepper-0123456789abcdef0123456789";

    private SecretPolicyValidator.SecretsInputs inputs() {
        return inputsWithMasterKey(STRONG);
    }

    private SecretPolicyValidator.SecretsInputs inputsWithMasterKey(String masterKey) {
        return new SecretPolicyValidator.SecretsInputs(
                masterKey,
                true,
                false,
                STRONG_PEPPER,
                "redis://:secret-pass@redis:6379",
                "jdbc:mariadb://mysql:3306/virbius?sslMode=VERIFY_IDENTITY&serverSslCert=/certs/ca.pem",
                "kafka:9092",
                "SASL_PLAINTEXT",
                "kafka-user",
                "kafka-pass");
    }

    @Test
    void blankMasterKeyFailsInEveryProfile() {
        var in = inputsWithMasterKey("");
        assertFalse(SecretPolicyValidator.runChecks(in, false).isEmpty());
        assertFalse(SecretPolicyValidator.runChecks(in, true).isEmpty());
    }

    @Test
    void compliantInputsPassStrict() {
        assertTrue(SecretPolicyValidator.runChecks(inputs(), true).isEmpty());
    }

    @Test
    void weakMasterKeyFailsOnlyInStrict() {
        var in = inputsWithMasterKey("short");
        // Non-strict: presence is enough.
        assertTrue(SecretPolicyValidator.runChecks(in, false).isEmpty());
        // Strict: weak/denylisted rejected.
        assertFalse(SecretPolicyValidator.runChecks(in, true).isEmpty());
    }

    @Test
    void denylistedMasterKeyFailsStrict() {
        var in = inputsWithMasterKey("virbius-default-license-key-change-me");
        assertFalse(SecretPolicyValidator.runChecks(in, true).isEmpty());
    }

    @Test
    void fullyDisabledAuthFailsStrictOnly() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, false, false, "", "", "", "", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, false).isEmpty());
        List<String> violations = SecretPolicyValidator.runChecks(in, true);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("authentication is fully disabled"));
    }

    @Test
    void missingPepperFailsStrictWhenApiKeyEnabled() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, "", "", "", "", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, true).stream()
                .anyMatch(v -> v.contains("pepper")));
    }

    @Test
    void shortPepperFailsStrict() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, "short-pepper", "", "", "", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, true).stream()
                .anyMatch(v -> v.contains("VIRBIUS_API_KEY_PEPPER is weak")));
    }

    @Test
    void redisUrlWithoutPasswordFailsStrict() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, STRONG_PEPPER,
                "redis://redis:6379", "", "", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, true).stream()
                .anyMatch(v -> v.contains("virbius.redis.url")));
    }

    @Test
    void redisUrlWithPasswordPasses() {
        assertTrue(SecretPolicyValidator.redisUrlHasPassword("redis://:p%40ss@redis:6379"));
        assertTrue(SecretPolicyValidator.redisUrlHasPassword("redis://user:pass@redis:6379"));
        assertFalse(SecretPolicyValidator.redisUrlHasPassword("redis://redis:6379"));
        assertFalse(SecretPolicyValidator.redisUrlHasPassword("redis://redis:6379/0"));
    }

    @Test
    void jdbcWithoutTlsFailsStrict() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, STRONG_PEPPER, "",
                "jdbc:mariadb://mysql:3306/virbius?useSSL=false&allowPublicKeyRetrieval=true",
                "", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, true).stream()
                .anyMatch(v -> v.contains("sslMode=VERIFY_IDENTITY")));
    }

    @Test
    void kafkaPlaintextWithoutSaslFailsStrict() {
        var in = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, STRONG_PEPPER, "", "", "kafka:9092", "", "", "");
        assertTrue(SecretPolicyValidator.runChecks(in, true).stream()
                .anyMatch(v -> v.contains("KAFKA_SECURITY_PROTOCOL")));

        // SASL protocol set but credentials missing.
        var noCreds = new SecretPolicyValidator.SecretsInputs(
                STRONG, true, false, STRONG_PEPPER, "", "", "kafka:9092",
                "SASL_PLAINTEXT", "", "");
        assertTrue(SecretPolicyValidator.runChecks(noCreds, true).stream()
                .anyMatch(v -> v.contains("KAFKA_SASL_USERNAME")));
    }

    @Test
    void strictModeResolvedFromProfilesAndOverride() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertTrue(SecretPolicyValidator.resolveStrict(prod));

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertFalse(SecretPolicyValidator.resolveStrict(dev));

        MockEnvironment devForced = new MockEnvironment();
        devForced.setActiveProfiles("dev");
        devForced.setProperty("virbius.security.strict-secrets", "true");
        assertTrue(SecretPolicyValidator.resolveStrict(devForced));
    }

    @Test
    void postProcessEnvironmentFailsStartupOnViolations() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("virbius.license.master-key", "");
        var thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> new SecretPolicyValidator().postProcessEnvironment(prod, null));
        assertTrue(thrown.getMessage().contains("master-key is required"));
    }

    @Test
    void postProcessEnvironmentPassesWhenCompliant() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        prod.setProperty("virbius.license.master-key", STRONG);
        prod.setProperty("virbius.security.api-key.enabled", "true");
        prod.setProperty("virbius.security.api-key.pepper", STRONG_PEPPER);
        prod.setProperty("virbius.redis.url", "redis://:pw@redis:6379");
        prod.setProperty("spring.datasource.url",
                "jdbc:mariadb://mysql:3306/virbius?sslMode=VERIFY_IDENTITY");
        prod.setProperty("spring.kafka.bootstrap-servers", "kafka:9092");
        prod.setProperty("KAFKA_SECURITY_PROTOCOL", "SASL_PLAINTEXT");
        prod.setProperty("KAFKA_SASL_USERNAME", "u");
        prod.setProperty("KAFKA_SASL_PASSWORD", "p");
        new SecretPolicyValidator().postProcessEnvironment(prod, null);
    }

    @Test
    void runsAfterConfigDataIsLoaded() {
        // Must run after ConfigDataEnvironmentPostProcessor so application*.yml
        // values (e.g. virbius.security.api-key.enabled) are already resolved.
        assertTrue(new SecretPolicyValidator().getOrder()
                > org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);
    }
}
