//! Shared helpers for the audit / trace sinks (Redis and Kafka backends).

use std::net::SocketAddr;

/// A parsed Redis target: the resolved address plus the optional password
/// from the URL userinfo (`redis://[:password@]host:port`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RedisTarget {
    pub addr: SocketAddr,
    pub password: Option<String>,
}

/// Parse a Redis URL into a socket address and an optional password.
///
/// Accepted forms: `host:port`, `redis://host:port`,
/// `redis://:password@host:port`, `redis://user:password@host:port`.
/// Percent-encoding in the password is NOT decoded — configure passwords
/// without `%` for these raw-TCP sinks.
///
/// Panics (like the previous `parse_redis_addr`) when the host cannot be
/// resolved; the URL in the panic message is redacted first.
pub fn parse_redis_target(url: &str) -> RedisTarget {
    use std::net::ToSocketAddrs;

    let (password, host_port) = split_userinfo(url);
    let addr = host_port
        .to_socket_addrs()
        .ok()
        .and_then(|mut addrs| addrs.next())
        .unwrap_or_else(|| {
            panic!(
                "invalid Redis address '{}': cannot resolve. Expected 'host:port' or 'redis://[:password@]host:port'",
                redact_url(url)
            )
        });
    RedisTarget { addr, password }
}

/// Strip the scheme and `user:password@` userinfo from a Redis URL.
/// Returns `(password, host:port)`.
fn split_userinfo(url: &str) -> (Option<String>, &str) {
    let after_scheme = match url.find("://") {
        Some(idx) => &url[idx + 3..],
        None => url,
    };
    match after_scheme.find('@') {
        Some(at) => {
            let userinfo = &after_scheme[..at];
            let password = match userinfo.find(':') {
                Some(colon) => &userinfo[colon + 1..],
                None => userinfo,
            };
            let password = password.to_string();
            (Some(password), &after_scheme[at + 1..])
        }
        None => (None, after_scheme),
    }
}

/// Mask credentials in a URL for log/panic output.
/// `redis://user:pass@h` / `redis://:pass@h` → `redis://***@h`.
pub fn redact_url(url: &str) -> String {
    let after_scheme = match url.find("://") {
        Some(idx) => &url[idx + 3..],
        None => url,
    };
    if let Some(at) = after_scheme.find('@') {
        let prefix_end = url.len() - after_scheme.len();
        format!("{}***{}", &url[..prefix_end], &after_scheme[at..])
    } else {
        url.to_string()
    }
}

/// Encode a Redis command as a RESP array (binary-safe: arguments may contain
/// any bytes, unlike the inline protocol used by the XADD fast path).
pub fn resp_command(cmd: &str, args: &[&str]) -> Vec<u8> {
    let mut out = format!("*{}\r\n", args.len() + 1).into_bytes();
    fn push(buf: &mut Vec<u8>, s: &str) {
        buf.extend_from_slice(format!("${}\r\n{}\r\n", s.len(), s).as_bytes());
    }
    push(&mut out, cmd);
    for a in args {
        push(&mut out, a);
    }
    out
}

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
    fn parse_plain_host_port() {
        let t = parse_redis_target("127.0.0.1:6379");
        assert_eq!(t.addr.port(), 6379);
        assert_eq!(t.password, None);
    }

    #[test]
    fn parse_scheme_without_password() {
        let t = parse_redis_target("redis://10.0.0.1:6380");
        assert_eq!(t.addr.port(), 6380);
        assert_eq!(t.password, None);
    }

    #[test]
    fn parse_password_only_userinfo() {
        // Use an IP literal — hostnames need DNS and would fail in CI sandboxes.
        let t = parse_redis_target("redis://:s3cret@10.0.0.2:6379");
        assert_eq!(t.addr.port(), 6379);
        assert_eq!(t.password.as_deref(), Some("s3cret"));
    }

    #[test]
    fn parse_user_and_password() {
        let t = parse_redis_target("redis://app:p%40ss@10.0.0.3:6380");
        assert_eq!(t.password.as_deref(), Some("p%40ss"));
        assert_eq!(t.addr.port(), 6380);
    }

    #[test]
    fn redact_url_masks_password() {
        assert_eq!(
            redact_url("redis://:s3cret@redis:6379"),
            "redis://***@redis:6379"
        );
        assert_eq!(
            redact_url("redis://user:pass@redis:6379"),
            "redis://***@redis:6379"
        );
        assert_eq!(redact_url("redis://redis:6379"), "redis://redis:6379");
    }

    #[test]
    fn resp_command_encodes_auth() {
        let encoded = String::from_utf8(resp_command("AUTH", &["p@ss w0rd"])).unwrap();
        assert_eq!(encoded, "*2\r\n$4\r\nAUTH\r\n$9\r\np@ss w0rd\r\n");
    }

    #[test]
    fn kafka_security_plain_and_empty() {
        assert!(kafka_security_settings("", "", "", "", "").is_empty());
        assert!(kafka_security_settings("PLAINTEXT", "", "", "", "").is_empty());
    }

    #[test]
    fn kafka_security_sasl_plaintext() {
        let s = kafka_security_settings(
            "SASL_PLAINTEXT",
            "SCRAM-SHA-256",
            "u",
            "p",
            "/ignored.pem",
        );
        let keys: Vec<&str> = s.iter().map(|(k, _)| k.as_str()).collect();
        assert_eq!(keys, vec!["security.protocol", "sasl.mechanism", "sasl.username", "sasl.password"]);
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
