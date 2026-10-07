package io.virbius.control.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Startup-time policy for platform secrets. Aggregates presence / strength / transport
 * checks for every secret the control plane consumes and fails fast with a single
 * actionable error listing all violations.
 *
 * <p>Registered as an {@link EnvironmentPostProcessor} (after config data is
 * loaded, before any bean — including datasource/Flyway — is created), so a
 * missing secret is reported as such instead of being masked by a downstream
 * connection error.
 *
 * <p>Strict mode (profiles {@code prod}/{@code staging}, or explicit
 * {@code virbius.security.strict-secrets}) additionally enforces:
 * an authentication mechanism must be enabled, the API-key pepper must be set,
 * Redis URLs must carry a password, the JDBC URL must enable TLS, and Kafka
 * must use SASL with credentials. Non-strict profiles only enforce that the
 * license master key is present (blank is always fatal).
 */
public class SecretPolicyValidator implements EnvironmentPostProcessor, Ordered {

    /** Minimum length for symmetric secrets (master key, pepper). */
    static final int MIN_SECRET_LENGTH = 32;

    static final List<String> DENYLIST = List.of(
            "virbius-default-license-key-change-me",
            "local-dev-license-master-key",
            "admin",
            "password",
            "change-me",
            "changeme",
            "secret",
            "sk-REPLACE-ME");

    /** Inputs under validation; a record so the rule set is unit-testable without Spring. */
    record SecretsInputs(
            String licenseMasterKey,
            boolean apiKeyEnabled,
            boolean operatorJwtEnabled,
            String apiKeyPepper,
            String redisUrl,
            String jdbcUrl,
            String kafkaBootstrapServers,
            String kafkaSecurityProtocol,
            String kafkaSaslUsername,
            String kafkaSaslPassword) {}

    private static final Logger log = LoggerFactory.getLogger(SecretPolicyValidator.class);

    public SecretPolicyValidator() {
        // Stateless: every invocation reads the live environment.
    }

    static boolean resolveStrict(Environment env) {
        String override = env.getProperty("virbius.security.strict-secrets");
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return env.matchesProfiles("prod") || env.matchesProfiles("staging");
    }

    /** Runs after config data (application*.yml) is loaded, before any bean exists. */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SecretsInputs inputs = new SecretsInputs(
                environment.getProperty("virbius.license.master-key", ""),
                environment.getProperty("virbius.security.api-key.enabled", Boolean.class, false),
                environment.getProperty("virbius.security.operator-jwt.enabled", Boolean.class, false),
                environment.getProperty("virbius.security.api-key.pepper", ""),
                environment.getProperty("virbius.redis.url", ""),
                environment.getProperty("spring.datasource.url", ""),
                environment.getProperty("spring.kafka.bootstrap-servers", ""),
                environment.getProperty("KAFKA_SECURITY_PROTOCOL", ""),
                environment.getProperty("KAFKA_SASL_USERNAME", ""),
                environment.getProperty("KAFKA_SASL_PASSWORD", ""));
        boolean strict = resolveStrict(environment);
        List<String> violations = runChecks(inputs, strict);
        if (!violations.isEmpty()) {
            String joined = String.join("\n  - ", violations);
            throw new IllegalStateException(
                    "Secret policy violations detected (strict=" + strict + "):\n  - " + joined);
        }
        log.info("secret policy validation passed (strict={})", strict);
    }

    /** Pure rule set — returns all violations (empty list means compliant). */
    static List<String> runChecks(SecretsInputs in, boolean strict) {
        List<String> violations = new ArrayList<>();

        // License master key: presence is mandatory in every profile.
        if (isBlank(in.licenseMasterKey())) {
            violations.add("virbius.license.master-key is required — set VIRBIUS_LICENSE_MASTER_KEY"
                    + " (>= " + MIN_SECRET_LENGTH + " chars).");
        } else if (strict && isWeakSecret(in.licenseMasterKey())) {
            violations.add("virbius.license.master-key is weak or a known default — set a strong"
                    + " VIRBIUS_LICENSE_MASTER_KEY (>= " + MIN_SECRET_LENGTH + " chars).");
        }

        if (!strict) {
            return violations;
        }

        // An authentication mechanism must be enabled — no implicit platform_admin.
        if (!in.apiKeyEnabled() && !in.operatorJwtEnabled()) {
            violations.add("authentication is fully disabled — enable api-key"
                    + " (VIRBIUS_SECURITY_API_KEY_ENABLED) and/or operator-jwt; the implicit"
                    + " dev principal is not allowed in strict profiles.");
        }

        // API-key pepper is required whenever api-key auth is on.
        if (in.apiKeyEnabled()) {
            if (isBlank(in.apiKeyPepper())) {
                violations.add("virbius.security.api-key.pepper is required when api-key auth is"
                        + " enabled — set VIRBIUS_API_KEY_PEPPER.");
            } else if (isWeakSecret(in.apiKeyPepper())) {
                violations.add("VIRBIUS_API_KEY_PEPPER is weak or a known default"
                        + " (>= " + MIN_SECRET_LENGTH + " chars required).");
            }
        }

        // Redis: password must be embedded in the URL (redis://:pass@host:port).
        if (!isBlank(in.redisUrl()) && !redisUrlHasPassword(in.redisUrl())) {
            violations.add("virbius.redis.url must include a password"
                    + " (redis://:<password>@host:port) — set REDIS_PASSWORD."
                    + " Unauthenticated Redis is not allowed in strict profiles.");
        }

        // MySQL: TLS with certificate validation is mandatory.
        if (!isBlank(in.jdbcUrl()) && !jdbcUrlEnforcesTls(in.jdbcUrl())) {
            violations.add("spring.datasource.url must enable TLS with certificate validation"
                    + " (sslMode=VERIFY_IDENTITY or VERIFY_CA, useSSL=false is not allowed)");
        }

        // Kafka: SASL with credentials is mandatory when a broker is configured.
        if (!isBlank(in.kafkaBootstrapServers())) {
            String protocol = in.kafkaSecurityProtocol() == null
                    ? "" : in.kafkaSecurityProtocol().trim().toUpperCase(Locale.ROOT);
            if (!protocol.equals("SASL_PLAINTEXT") && !protocol.equals("SASL_SSL")) {
                violations.add("KAFKA_SECURITY_PROTOCOL must be SASL_PLAINTEXT or SASL_SSL"
                        + " when Kafka is configured — unauthenticated PLAINTEXT brokers are"
                        + " not allowed in strict profiles.");
            } else if (isBlank(in.kafkaSaslUsername()) || isBlank(in.kafkaSaslPassword())) {
                violations.add("KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD are required when"
                        + " KAFKA_SECURITY_PROTOCOL is set to a SASL mode.");
            }
        }

        return violations;
    }

    /** {@code redis://[:password@]host:port} — true when userinfo is present. */
    static boolean redisUrlHasPassword(String url) {
        String u = url.trim();
        int schemeEnd = u.indexOf("://");
        String rest = schemeEnd >= 0 ? u.substring(schemeEnd + 3) : u;
        int at = rest.indexOf('@');
        int slash = rest.indexOf('/');
        return at > 0 && (slash < 0 || at < slash);
    }

    /** Accepts sslMode=VERIFY_IDENTITY / VERIFY_CA (MariaDB & MySQL driver spellings). */
    static boolean jdbcUrlEnforcesTls(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return u.contains("sslmode=verify_identity")
                || u.contains("sslmode=verify_ca")
                || u.contains("sslmode=verify-full")
                || u.contains("sslmode=verify-ca");
    }

    static boolean isWeakSecret(String secret) {
        return secret.length() < MIN_SECRET_LENGTH || DENYLIST.contains(secret);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
