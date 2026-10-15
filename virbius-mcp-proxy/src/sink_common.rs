//! Shared helpers for the audit / trace Kafka sinks.

/// Kafka client security settings derived from the environment.
///
/// Recognized variables:
/// - `VIRBIUS_KAFKA_SECURITY_PROTOCOL`: `SASL_PLAINTEXT` or `SASL_SSL`
///   (empty/`PLAINTEXT` → no settings, i.e. unauthenticated local broker)
/// - `VIRBIUS_KAFKA_SASL_MECHANISM`: defaults to `SCRAM-SHA-256`
/// - `VIRBIUS_KAFKA_SASL_USERNAME` / `VIRBIUS_KAFKA_SASL_PASSWORD`
/// - `VIRBIUS_KAFKA_SSL_CA`: CA bundle path for `SASL_SSL`
pub fn kafka_security_from_env() -> Vec<(String, String)> {
    let protocol = std::env::var("VIRBIUS_KAFKA_SECURITY_PROTOCOL").unwrap_or_default();
    let mechanism = std::env::var("VIRBIUS_KAFKA_SASL_MECHANISM")
        .unwrap_or_else(|_| "SCRAM-SHA-256".to_string());
    let username = std::env::var("VIRBIUS_KAFKA_SASL_USERNAME").unwrap_or_default();
    let password = std::env::var("VIRBIUS_KAFKA_SASL_PASSWORD").unwrap_or_default();
    let ssl_ca = std::env::var("VIRBIUS_KAFKA_SSL_CA").unwrap_or_default();
    kafka_security_settings(&protocol, &mechanism, &username, &password, &ssl_ca)
}

/// Pure form of [`kafka_security_from_env`] (unit-testable).
pub fn kafka_security_settings(
    protocol: &str,
    mechanism: &str,
    username: &str,
    password: &str,
    ssl_ca: &str,
) -> Vec<(String, String)> {
    let protocol = protocol.trim();
    match protocol {
        "" | "PLAINTEXT" => Vec::new(),
        "SASL_PLAINTEXT" | "SASL_SSL" => {
            let mut settings = vec![
                ("security.protocol".to_string(), protocol.to_string()),
                ("sasl.mechanism".to_string(), mechanism.to_string()),
                ("sasl.username".to_string(), username.to_string()),
                ("sasl.password".to_string(), password.to_string()),
            ];
            if protocol == "SASL_SSL" && !ssl_ca.trim().is_empty() {
                settings.push(("ssl.ca.location".to_string(), ssl_ca.trim().to_string()));
            }
            settings
        }
        other => {
            // Unknown protocol: refuse rather than silently misconfigure.
            panic!("unsupported VIRBIUS_KAFKA_SECURITY_PROTOCOL '{other}' (expected SASL_PLAINTEXT or SASL_SSL)");
        }
    }
}

/// Apply [`kafka_security_from_env`] to an rdkafka client config.
pub fn apply_kafka_security(cfg: &mut rdkafka::config::ClientConfig) {
    for (key, value) in kafka_security_from_env() {
        cfg.set(key, value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn kafka_security_plain_and_empty() {
        assert!(kafka_security_settings("", "", "", "", "").is_empty());
        assert!(kafka_security_settings("PLAINTEXT", "", "", "", "").is_empty());
    }

    #[test]
    fn kafka_security_sasl_plaintext() {
        let s =
            kafka_security_settings("SASL_PLAINTEXT", "SCRAM-SHA-256", "u", "p", "/ignored.pem");
        let keys: Vec<&str> = s.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(
            keys,
            vec![
                "security.protocol",
                "sasl.mechanism",
                "sasl.username",
                "sasl.password"
            ]
        );
        // CA path is only meaningful for SASL_SSL.
        assert!(!s.iter().any(|(k, _)| k == "ssl.ca.location"));
    }

    #[test]
    fn kafka_security_sasl_ssl_includes_ca() {
        let s = kafka_security_settings("SASL_SSL", "SCRAM-SHA-512", "u", "p", "/ca.pem");
        assert!(s.contains(&("ssl.ca.location".to_string(), "/ca.pem".to_string())));
        assert!(s.contains(&("sasl.mechanism".to_string(), "SCRAM-SHA-512".to_string())));
    }

    #[test]
    #[should_panic(expected = "unsupported VIRBIUS_KAFKA_SECURITY_PROTOCOL")]
    fn kafka_security_rejects_unknown_protocol() {
        kafka_security_settings("TLS", "", "", "", "");
    }
}
