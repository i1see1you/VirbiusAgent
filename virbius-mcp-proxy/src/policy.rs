//! Per-tenant proxy policy pushed by Control over Redis.
//!
//! Local toml remains the fallback until a snapshot arrives for that tenant.
//! Listen address, Redis URL, and license paths are not part of this document.

use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use serde::Deserialize;
use tracing::{info, warn};

use crate::config::{
    FailoverConfig, FallbackPolicy, FastPathConfig, MemorySection, OutputReviewConfig,
    UpstreamEntry, UpstreamGates,
};

pub const STREAM_KEY: &str = "virbius:proxy:config-reload";
const SNAPSHOT_PREFIX: &str = "virbius:proxy:";
const SNAPSHOT_SUFFIX: &str = ":snapshot";

#[derive(Debug, Clone, Deserialize)]
pub struct RuntimePolicy {
    #[serde(default)]
    pub upstreams: Vec<UpstreamEntry>,
    #[serde(default = "default_ttl")]
    pub session_ttl_secs: u64,
    #[serde(default = "default_fallback_name")]
    pub fallback_policy: String,
    #[serde(default)]
    pub fast_path: FastPathConfig,
    #[serde(default)]
    pub failover: FailoverConfig,
    #[serde(default)]
    pub output_review: OutputReviewConfig,
    #[serde(default)]
    pub memory: MemorySection,
}

fn default_ttl() -> u64 {
    1800
}
fn default_fallback_name() -> String {
    "minimum_privilege".to_string()
}

impl RuntimePolicy {
    pub fn fallback(&self) -> FallbackPolicy {
        match self.fallback_policy.as_str() {
            "default_deny" => FallbackPolicy::DefaultDeny,
            "audit_only" => FallbackPolicy::AuditOnly,
            _ => FallbackPolicy::MinimumPrivilege,
        }
    }
}

/// Tenant overrides. Missing tenant means "use the process-local config".
#[derive(Debug, Default)]
pub struct PolicyRegistry {
    tenants: DashMap<String, RuntimePolicy>,
}

impl PolicyRegistry {
    pub fn empty() -> Self {
        Self {
            tenants: DashMap::new(),
        }
    }

    pub fn put(&self, tenant_id: String, policy: RuntimePolicy) {
        self.tenants.insert(tenant_id, policy);
    }

    pub fn get(&self, tenant_id: &str) -> Option<RuntimePolicy> {
        self.tenants.get(tenant_id).map(|e| e.clone())
    }
}

/// Subscribe to `virbius:proxy:config-reload` and load each tenant snapshot.
/// Empty `redis_url` leaves the proxy on its local toml policy.
pub fn spawn_subscriber(redis_url: String, registry: Arc<PolicyRegistry>, gates: UpstreamGates) {
    if redis_url.is_empty() {
        info!("proxy policy subscriber disabled (no redis url)");
        return;
    }
    let url = normalize_redis_url(&redis_url);
    tokio::spawn(async move {
        loop {
            if let Err(e) = run_subscriber(&url, &registry, &gates).await {
                warn!("proxy policy subscriber error: {e}; retrying in 3s");
                tokio::time::sleep(Duration::from_secs(3)).await;
            }
        }
    });
}

fn normalize_redis_url(raw: &str) -> String {
    if raw.starts_with("redis://") || raw.starts_with("rediss://") {
        raw.to_string()
    } else {
        format!("redis://{raw}")
    }
}

async fn run_subscriber(
    url: &str,
    registry: &PolicyRegistry,
    gates: &UpstreamGates,
) -> redis::RedisResult<()> {
    use redis::AsyncCommands;

    let client = redis::Client::open(url)?;
    let mut conn = client.get_multiplexed_async_connection().await?;
    load_existing(&mut conn, registry, gates).await;
    let mut last_id = "$".to_string();
    loop {
        let reply: redis::streams::StreamReadReply = conn
            .xread_options(
                &[STREAM_KEY],
                &[last_id.as_str()],
                &redis::streams::StreamReadOptions::default()
                    .block(5000)
                    .count(16),
            )
            .await?;
        for key in reply.keys {
            for id in key.ids {
                last_id = id.id.clone();
                let Some(tenant) = id.map.get("tenant_id").and_then(redis_string) else {
                    continue;
                };
                apply_tenant(&mut conn, registry, &tenant, gates).await;
            }
        }
    }
}

async fn load_existing(
    conn: &mut redis::aio::MultiplexedConnection,
    registry: &PolicyRegistry,
    gates: &UpstreamGates,
) {
    use redis::AsyncCommands;
    let keys: Vec<String> = redis::cmd("KEYS")
        .arg(format!("{SNAPSHOT_PREFIX}*{SNAPSHOT_SUFFIX}"))
        .query_async(conn)
        .await
        .unwrap_or_default();
    for key in keys {
        let Some(tenant) = tenant_from_key(&key) else {
            continue;
        };
        if let Ok(raw) = conn.get::<_, String>(&key).await {
            store_raw(registry, tenant, &raw, gates);
        }
    }
}

async fn apply_tenant(
    conn: &mut redis::aio::MultiplexedConnection,
    registry: &PolicyRegistry,
    tenant: &str,
    gates: &UpstreamGates,
) {
    use redis::AsyncCommands;
    let key = format!("{SNAPSHOT_PREFIX}{tenant}{SNAPSHOT_SUFFIX}");
    match conn.get::<_, String>(&key).await {
        Ok(raw) => store_raw(registry, tenant, &raw, gates),
        Err(e) => warn!("proxy policy get {key} failed: {e}"),
    }
}

fn store_raw(registry: &PolicyRegistry, tenant: &str, raw: &str, gates: &UpstreamGates) {
    match serde_json::from_str::<RuntimePolicy>(raw) {
        Ok(policy) => {
            let policy = sanitize_policy(policy, gates, tenant);
            info!(
                "proxy policy loaded tenant={} upstreams={}",
                tenant,
                policy.upstreams.len()
            );
            registry.put(tenant.to_string(), policy);
        }
        Err(e) => warn!("proxy policy json rejected tenant={tenant}: {e}"),
    }
}

/// Drop tenant-policy upstream entries that violate the deployment gates.
///
/// Static TOML entries are validated at load time; this is the equivalent
/// enforcement point for Control-pushed policies. Rejected entries are logged
/// and skipped individually — one bad entry must not discard the whole policy.
fn sanitize_policy(
    mut policy: RuntimePolicy,
    gates: &UpstreamGates,
    tenant: &str,
) -> RuntimePolicy {
    let before = policy.upstreams.len();
    policy.upstreams.retain(|e| {
        if let Err(err) = e.validate() {
            warn!("proxy policy upstream '{}' rejected (tenant={tenant}): {err}", e.name);
            return false;
        }
        if !e.headers.is_empty() {
            warn!(
                "proxy policy upstream '{}' rejected (tenant={tenant}): headers are static-TOML only",
                e.name
            );
            return false;
        }
        if !gates.transport_enabled(&e.transport) {
            warn!(
                "proxy policy upstream '{}' rejected (tenant={tenant}): transport '{}' disabled",
                e.name, e.transport
            );
            return false;
        }
        if e.is_stdio() {
            if !gates.allow_dynamic_stdio {
                warn!(
                    "proxy policy upstream '{}' rejected (tenant={tenant}): dynamic stdio upstreams disabled",
                    e.name
                );
                return false;
            }
            let cmd = e.command.as_deref().unwrap_or("");
            if !gates.command_allowed(cmd) {
                warn!(
                    "proxy policy upstream '{}' rejected (tenant={tenant}): command '{cmd}' not in allowlist",
                    e.name
                );
                return false;
            }
        }
        true
    });
    if policy.upstreams.len() != before {
        warn!(
            "proxy policy for tenant={tenant}: {} of {} upstream entries dropped by gates",
            before - policy.upstreams.len(),
            before
        );
    }
    policy
}

fn tenant_from_key(key: &str) -> Option<&str> {
    key.strip_prefix(SNAPSHOT_PREFIX)?
        .strip_suffix(SNAPSHOT_SUFFIX)
}

fn redis_string(v: &redis::Value) -> Option<String> {
    match v {
        redis::Value::BulkString(b) => String::from_utf8(b.clone()).ok(),
        redis::Value::SimpleString(s) => Some(s.clone()),
        redis::Value::Array(items) => items.first().and_then(redis_string),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_control_snapshot() {
        let raw = r#"{
            "upstreams": [{"name": "default", "url": "http://mcp:8080", "sse_path": "/sse"}],
            "session_ttl_secs": 60,
            "fallback_policy": "default_deny",
            "fast_path": {"enabled": false, "warmup_calls": 1, "risk_threshold": 10},
            "failover": {"high_risk_fail_closed": true, "low_risk_fail_open": false, "engine_timeout_ms": 500},
            "output_review": {"enabled": false, "min_text_length": 8, "min_risk_score": 1, "fail_open": false},
            "memory": {"enabled": true, "max_entry_size": 128, "tool_patterns": ["memory_"]}
        }"#;
        let policy: RuntimePolicy = serde_json::from_str(raw).unwrap();
        assert_eq!(policy.upstreams[0].url.as_deref(), Some("http://mcp:8080"));
        assert_eq!(policy.fallback(), FallbackPolicy::DefaultDeny);
        assert!(!policy.fast_path.enabled);
        assert!(policy.memory.enabled);
        assert_eq!(policy.memory.tool_patterns, vec!["memory_".to_string()]);
    }

    #[test]
    fn missing_tenant_is_unset() {
        let reg = PolicyRegistry::empty();
        assert!(reg.get("acme").is_none());
        reg.put(
            "acme".into(),
            serde_json::from_str(r#"{"fallback_policy":"audit_only"}"#).unwrap(),
        );
        assert_eq!(
            reg.get("acme").unwrap().fallback(),
            FallbackPolicy::AuditOnly
        );
        assert!(reg.get("other").is_none());
    }

    #[test]
    fn sanitize_drops_dynamic_stdio_by_default() {
        let raw = r#"{
            "upstreams": [
                {"name": "remote", "url": "http://mcp:8080"},
                {"name": "local", "transport": "stdio", "command": "npx", "args": ["-y", "s"]}
            ]
        }"#;
        let policy: RuntimePolicy = serde_json::from_str(raw).unwrap();
        let out = sanitize_policy(policy, &UpstreamGates::default(), "acme");
        assert_eq!(out.upstreams.len(), 1);
        assert_eq!(out.upstreams[0].name, "remote");
    }

    #[test]
    fn sanitize_allows_dynamic_stdio_with_gate_and_allowlist() {
        let raw = r#"{
            "upstreams": [
                {"name": "local", "transport": "stdio", "command": "npx"}
            ]
        }"#;
        let gates = UpstreamGates {
            allow_dynamic_stdio: true,
            allowed_stdio_commands: vec!["npx".into()],
            ..Default::default()
        };
        let policy: RuntimePolicy = serde_json::from_str(raw).unwrap();
        assert_eq!(sanitize_policy(policy, &gates, "acme").upstreams.len(), 1);

        let gates_deny = UpstreamGates {
            allow_dynamic_stdio: true,
            allowed_stdio_commands: vec!["uvx".into()],
            ..Default::default()
        };
        let policy: RuntimePolicy = serde_json::from_str(raw).unwrap();
        assert_eq!(
            sanitize_policy(policy, &gates_deny, "acme").upstreams.len(),
            0
        );
    }

    #[test]
    fn sanitize_rejects_dynamic_headers_and_invalid_entries() {
        let raw = r#"{
            "upstreams": [
                {"name": "with-headers", "url": "http://mcp:8080", "headers": {"Authorization": "Bearer x"}},
                {"name": "no-target"}
            ]
        }"#;
        let policy: RuntimePolicy = serde_json::from_str(raw).unwrap();
        let out = sanitize_policy(policy, &UpstreamGates::default(), "acme");
        assert!(out.upstreams.is_empty());
    }
}
