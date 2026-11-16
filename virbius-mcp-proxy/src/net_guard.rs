//! Upstream URL egress guard (SSRF protection).
//!
//! Upstream URLs come from static config and from Control-pushed policy.
//! Both are validated here before the proxy opens a connection:
//!
//! - link-local, cloud-metadata and unspecified addresses are always denied
//! - loopback and private ranges are allowed by default (internal deployments)
//!   but can be denied with `upstream_deny_private = true`
//! - IPv4-mapped IPv6 literals are normalized before the checks
//!
//! Known limitation: DNS can change between validation and connection. The
//! caller pins the validated address with `reqwest::ClientBuilder::resolve`
//! where practical; re-validation happens on reconnect.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr};

/// Extra addresses that must never be reachable from an upstream URL even
/// though they are not in a reserved range (cloud metadata endpoints).
const DENIED_LITERALS: [&str; 2] = [
    "100.100.100.200", // Alibaba Cloud metadata
    "fd00:ec2::254",   // AWS IMDS over IPv6
];

pub fn check_ip(ip: IpAddr, deny_private: bool) -> Result<(), String> {
    let ip = normalize_ip(ip);

    if ip.is_unspecified() {
        return Err(format!("upstream address {ip} is unspecified"));
    }
    if is_denied_literal(ip) {
        return Err(format!("upstream address {ip} is a metadata endpoint"));
    }
    if is_link_local(ip) {
        return Err(format!("upstream address {ip} is link-local"));
    }
    if deny_private && (ip.is_loopback() || is_private(ip)) {
        return Err(format!(
            "upstream address {ip} is private/loopback (upstream_deny_private = true)"
        ));
    }
    Ok(())
}

/// Resolve a URL's host and validate every resolved address.
pub async fn validate_url(url: &str, deny_private: bool) -> Result<(), String> {
    let parsed = url::Url::parse(url).map_err(|e| format!("invalid upstream url '{url}': {e}"))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| format!("upstream url '{url}' has no host"))?
        .to_string();
    let port = parsed.port_or_known_default().unwrap_or(80);

    // IP literal: no DNS involved.
    if let Ok(ip) = host.parse::<IpAddr>() {
        return check_ip(ip, deny_private);
    }

    let addrs = tokio::net::lookup_host((host.as_str(), port))
        .await
        .map_err(|e| format!("cannot resolve upstream host '{host}': {e}"))?;
    let mut checked = 0usize;
    for addr in addrs {
        check_ip(addr.ip(), deny_private)?;
        checked += 1;
    }
    if checked == 0 {
        return Err(format!("upstream host '{host}' resolved to no addresses"));
    }
    Ok(())
}

fn normalize_ip(ip: IpAddr) -> IpAddr {
    match ip {
        // ::ffff:a.b.c.d — an IPv4 address wearing an IPv6 costume.
        IpAddr::V6(v6) => match v6.to_ipv4_mapped() {
            Some(v4) => IpAddr::V4(v4),
            None => IpAddr::V6(v6),
        },
        other => other,
    }
}

fn is_denied_literal(ip: IpAddr) -> bool {
    DENIED_LITERALS
        .iter()
        .filter_map(|s| s.parse::<IpAddr>().ok())
        .any(|denied| normalize_ip(denied) == ip)
}

fn is_link_local(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.octets()[0] == 169 && v4.octets()[1] == 254,
        IpAddr::V6(v6) => (v6.segments()[0] & 0xffc0) == 0xfe80,
    }
}

fn is_private(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => is_private_v4(v4),
        IpAddr::V6(v6) => is_private_v6(v6),
    }
}

fn is_private_v4(ip: Ipv4Addr) -> bool {
    let [a, b, ..] = ip.octets();
    a == 10
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 168)
        || (a == 100 && (64..=127).contains(&b)) // CGNAT 100.64/10
}

fn is_private_v6(ip: Ipv6Addr) -> bool {
    // Unique local addresses fc00::/7 and loopback ::1 (handled separately).
    (ip.segments()[0] & 0xfe00) == 0xfc00
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn loopback_and_private_allowed_by_default() {
        assert!(check_ip(ip("127.0.0.1"), false).is_ok());
        assert!(check_ip(ip("10.0.5.4"), false).is_ok());
        assert!(check_ip(ip("192.168.1.10"), false).is_ok());
        assert!(check_ip(ip("172.20.0.1"), false).is_ok());
        assert!(check_ip(ip("::1"), false).is_ok());
    }

    #[test]
    fn deny_private_flag_rejects_loopback_and_ranges() {
        assert!(check_ip(ip("127.0.0.1"), true).is_err());
        assert!(check_ip(ip("10.0.5.4"), true).is_err());
        assert!(check_ip(ip("172.20.0.1"), true).is_err());
        assert!(check_ip(ip("100.64.0.1"), true).is_err());
        assert!(check_ip(ip("fd00::1"), true).is_err());
        assert!(check_ip(ip("8.8.8.8"), true).is_ok());
    }

    #[test]
    fn link_local_always_rejected() {
        assert!(check_ip(ip("169.254.169.254"), false).is_err());
        assert!(check_ip(ip("169.254.1.1"), true).is_err());
        assert!(check_ip(ip("fe80::1"), false).is_err());
    }

    #[test]
    fn metadata_literals_always_rejected() {
        assert!(check_ip(ip("100.100.100.200"), false).is_err());
        assert!(check_ip(ip("fd00:ec2::254"), false).is_err());
    }

    #[test]
    fn ipv4_mapped_ipv6_is_normalized() {
        // ::ffff:169.254.169.254 must be rejected as link-local.
        assert!(check_ip(ip("::ffff:169.254.169.254"), false).is_err());
        // ::ffff:127.0.0.1 is loopback under deny_private.
        assert!(check_ip(ip("::ffff:127.0.0.1"), true).is_err());
        assert!(check_ip(ip("::ffff:127.0.0.1"), false).is_ok());
    }

    #[test]
    fn unspecified_rejected() {
        assert!(check_ip(ip("0.0.0.0"), false).is_err());
        assert!(check_ip(ip("::"), false).is_err());
    }

    #[tokio::test]
    async fn validates_ip_literal_urls_without_dns() {
        assert!(validate_url("http://127.0.0.1:8080/sse", false)
            .await
            .is_ok());
        assert!(validate_url("http://169.254.169.254/latest", false)
            .await
            .is_err());
        assert!(validate_url("http://127.0.0.1:8080", true).await.is_err());
        assert!(validate_url("not-a-url", false).await.is_err());
    }
}
