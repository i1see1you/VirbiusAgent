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
    UpstreamEntry,
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
pub fn spawn_subscriber(redis_url: String, registry: Arc<PolicyRegistry>) {
    if redis_url.is_empty() {
        info!("proxy policy subscriber disabled (no redis url)");
        return;
    }
    let url = normalize_redis_url(&redis_url);
    tokio::spawn(async move {
        loop {
            if let Err(e) = run_subscriber(&url, &registry).await {
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

async fn run_subscriber(url: &str, registry: &PolicyRegistry) -> redis::RedisResult<()> {
    use redis::AsyncCommands;

    let client = redis::Client::open(url)?;
    let mut conn = client.get_multiplexed_async_connection().await?;
    load_existing(&mut conn, registry).await;
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
                apply_tenant(&mut conn, registry, &tenant).await;
            }
        }
    }
}

async fn load_existing(conn: &mut redis::aio::MultiplexedConnection, registry: &PolicyRegistry) {
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
            store_raw(registry, tenant, &raw);
        }
    }
}

async fn apply_tenant(
    conn: &mut redis::aio::MultiplexedConnection,
    registry: &PolicyRegistry,
    tenant: &str,
) {
    use redis::AsyncCommands;
    let key = format!("{SNAPSHOT_PREFIX}{tenant}{SNAPSHOT_SUFFIX}");
    match conn.get::<_, String>(&key).await {
        Ok(raw) => store_raw(registry, tenant, &raw),
        Err(e) => warn!("proxy policy get {key} failed: {e}"),
    }
}

fn store_raw(registry: &PolicyRegistry, tenant: &str, raw: &str) {
    match serde_json::from_str::<RuntimePolicy>(raw) {
        Ok(policy) => {
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

fn tenant_from_key(key: &str) -> Option<&str> {
    key.strip_prefix(SNAPSHOT_PREFIX)?
        .strip_suffix(SNAPSHOT_SUFFIX)
}

fn redis_string(v: &redis::Value) -> Option<String> {
    match v {
        redis::Value::Data(b) => String::from_utf8(b.clone()).ok(),
        redis::Value::Status(s) => Some(s.clone()),
        redis::Value::Bulk(items) => items.first().and_then(redis_string),
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
        assert_eq!(policy.upstreams[0].url, "http://mcp:8080");
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
}
