/// Configuration loading (TOML file + environment variable overrides).

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxyConfig {
    #[serde(default)]
    pub proxy: ProxySection,
    #[serde(default)]
    pub security: SecuritySection,
    #[serde(default)]
    pub audit: AuditSection,
    #[serde(default)]
    pub trace: TraceSection,
    #[serde(default)]
    pub memory: MemorySection,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxySection {
    #[serde(default = "default_listen")]
    pub listen: String,
    #[serde(default = "default_upstream")]
    pub upstream_url: String,
    #[serde(default = "default_upstream_transport")]
    pub upstream_transport: String,
    #[serde(default = "default_upstream_sse_path")]
    pub upstream_sse_path: String,
    #[serde(default)]
    pub upstreams: Vec<UpstreamEntry>,
    #[serde(default = "default_session_ttl_secs")]
    pub session_ttl_secs: u64,
}

/// A single upstream MCP Server entry for multi-upstream mode.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamEntry {
    pub name: String,
    pub url: String,
    #[serde(default = "default_upstream_sse_path")]
    pub sse_path: String,
}

fn default_listen() -> String {
    "stdio".to_string()
}
fn default_upstream() -> String {
    "http://localhost:8080".to_string()
}
fn default_upstream_transport() -> String {
    "sse".to_string()
}
fn default_upstream_sse_path() -> String {
    "/sse".to_string()
}
fn default_session_ttl_secs() -> u64 {
    1800 // 30 minutes
}

impl Default for ProxySection {
    fn default() -> Self {
        Self {
            listen: default_listen(),
            upstream_url: default_upstream(),
            upstream_transport: default_upstream_transport(),
            upstream_sse_path: default_upstream_sse_path(),
            upstreams: Vec::new(),
            session_ttl_secs: default_session_ttl_secs(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecuritySection {
    #[serde(default = "default_control_url")]
    pub control_base_url: String,
    #[serde(default = "default_engine_url")]
    pub engine_url: String,
    #[serde(default)]
    pub license_public_key: String,
    #[serde(default = "default_fallback")]
    pub fallback_policy: String,
    #[serde(default)]
    pub fast_path: FastPathConfig,
    #[serde(default)]
    pub failover: FailoverConfig,
}

fn default_control_url() -> String {
    "http://localhost:8080".to_string()
}
fn default_engine_url() -> String {
    "http://localhost:8082".to_string()
}
fn default_fallback() -> String {
    "minimum_privilege".to_string()
}

impl Default for SecuritySection {
    fn default() -> Self {
        Self {
            control_base_url: default_control_url(),
            engine_url: default_engine_url(),
            license_public_key: String::new(),
            fallback_policy: default_fallback(),
            fast_path: FastPathConfig::default(),
            failover: FailoverConfig::default(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FastPathConfig {
    #[serde(default = "default_fp_enabled")]
    pub enabled: bool,
    #[serde(default = "default_warmup")]
    pub warmup_calls: u32,
    #[serde(default = "default_risk_threshold")]
    pub risk_threshold: u32,
}

fn default_fp_enabled() -> bool {
    true
}
fn default_warmup() -> u32 {
    5
}
fn default_risk_threshold() -> u32 {
    30
}

impl Default for FastPathConfig {
    fn default() -> Self {
        Self {
            enabled: default_fp_enabled(),
            warmup_calls: default_warmup(),
            risk_threshold: default_risk_threshold(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FailoverConfig {
    #[serde(default = "default_fail_closed")]
    pub high_risk_fail_closed: bool,
    #[serde(default = "default_fail_open")]
    pub low_risk_fail_open: bool,
    #[serde(default = "default_engine_timeout")]
    pub engine_timeout_ms: u64,
}

fn default_fail_closed() -> bool {
    true
}
fn default_fail_open() -> bool {
    true
}
fn default_engine_timeout() -> u64 {
    3000
}

impl Default for FailoverConfig {
    fn default() -> Self {
        Self {
            high_risk_fail_closed: default_fail_closed(),
            low_risk_fail_open: default_fail_open(),
            engine_timeout_ms: default_engine_timeout(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditSection {
    #[serde(default)]
    pub backend: String,
    #[serde(default)]
    pub redis_url: String,
    #[serde(default)]
    pub kafka_brokers: String,
    #[serde(default = "default_audit_kafka_topic")]
    pub kafka_topic: String,
    #[serde(default = "default_sample_rate")]
    pub sample_rate: f64,
}

fn default_audit_kafka_topic() -> String {
    "virbius-audit-events".to_string()
}

fn default_sample_rate() -> f64 {
    1.0
}

impl Default for AuditSection {
    fn default() -> Self {
        Self {
            backend: String::new(),
            redis_url: String::new(),
            kafka_brokers: String::new(),
            kafka_topic: default_audit_kafka_topic(),
            sample_rate: default_sample_rate(),
        }
    }
}

impl Default for ProxyConfig {
    fn default() -> Self {
        Self {
            proxy: ProxySection::default(),
            security: SecuritySection::default(),
            audit: AuditSection::default(),
            trace: TraceSection::default(),
            memory: MemorySection::default(),
        }
    }
}

impl ProxyConfig {
    /// Load from TOML file if it exists, then apply environment variable overrides.
    pub fn load() -> Self {
        let mut cfg = Self::load_from_file();

        // Environment variable overrides
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAM_URL") {
            cfg.proxy.upstream_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAM_TRANSPORT") {
            cfg.proxy.upstream_transport = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAM_SSE_PATH") {
            cfg.proxy.upstream_sse_path = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_SESSION_TTL_SECS") {
            if let Ok(secs) = v.parse::<u64>() {
                cfg.proxy.session_ttl_secs = secs;
            }
        }
        if let Ok(v) = std::env::var("VIRBIUS_CONTROL_URL") {
            cfg.security.control_base_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_ENGINE_URL") {
            cfg.security.engine_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_LICENSE_PUBLIC_KEY") {
            cfg.security.license_public_key = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_FALLBACK_POLICY") {
            cfg.security.fallback_policy = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_REDIS_URL") {
            cfg.audit.redis_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_AUDIT_BACKEND") {
            cfg.audit.backend = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_AUDIT_KAFKA_BROKERS") {
            cfg.audit.kafka_brokers = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_AUDIT_KAFKA_TOPIC") {
            cfg.audit.kafka_topic = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_REDIS_URL") {
            cfg.trace.redis_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_BACKEND") {
            cfg.trace.backend = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_KAFKA_BROKERS") {
            cfg.trace.kafka_brokers = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_KAFKA_TOPIC") {
            cfg.trace.kafka_topic = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRANSPORT") {
            cfg.proxy.listen = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAMS") {
            if let Ok(entries) = serde_json::from_str::<Vec<UpstreamEntry>>(&v) {
                cfg.proxy.upstreams = entries;
            }
        }

        // Normalize: if upstreams array is empty, synthesize from single upstream fields
        if cfg.proxy.upstreams.is_empty() && !cfg.proxy.upstream_url.is_empty() {
            cfg.proxy.upstreams = vec![UpstreamEntry {
                name: "default".to_string(),
                url: cfg.proxy.upstream_url.clone(),
                sse_path: cfg.proxy.upstream_sse_path.clone(),
            }];
        }

        cfg
    }

    fn load_from_file() -> Self {
        // Try config file paths
        let candidates = [
            PathBuf::from("virbius-mcp-proxy.toml"),
            PathBuf::from("/etc/virbius/mcp-proxy.toml"),
        ];
        for path in &candidates {
            if path.exists() {
                if let Ok(raw) = std::fs::read_to_string(path) {
                    if let Ok(cfg) = toml::from_str::<ProxyConfig>(&raw) {
                        return cfg;
                    }
                }
            }
        }
        Self::default()
    }

    /// Parse the fallback policy string into the enum.
    pub fn fallback_policy(&self) -> FallbackPolicy {
        match self.security.fallback_policy.as_str() {
            "default_deny" => FallbackPolicy::DefaultDeny,
            "audit_only" => FallbackPolicy::AuditOnly,
            _ => FallbackPolicy::MinimumPrivilege,
        }
    }

    /// Return effective trace redis URL (falls back to audit redis_url).
    pub fn trace_redis_url(&self) -> &str {
        if self.trace.redis_url.is_empty() {
            &self.audit.redis_url
        } else {
            &self.trace.redis_url
        }
    }

    /// Check if audit should use Kafka backend.
    pub fn audit_use_kafka(&self) -> bool {
        self.audit.backend == "kafka" && !self.audit.kafka_brokers.is_empty()
    }

    /// Check if trace should use Kafka backend.
    pub fn trace_use_kafka(&self) -> bool {
        self.trace.backend == "kafka" && !self.trace.kafka_brokers.is_empty()
    }
}

/// Fallback strategy when no License is provided.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FallbackPolicy {
    MinimumPrivilege,
    DefaultDeny,
    AuditOnly,
}

/// High-risk tools that are always blocked without a License.
pub const HIGH_RISK_TOOLS: &[&str] = &[
    "shell",
    "execute_python",
    "execute_code",
    "read_file",
    "write_file",
    "delete_file",
    "curl",
    "http_request",
    "fetch",
    "read_secret",
    "write_secret",
    "sql_query",
    "database_query",
];

/// Trace collection configuration (decision chain audit).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TraceSection {
    #[serde(default)]
    pub backend: String,
    /// Redis URL for trace stream. If empty, reuses audit.redis_url.
    /// Format: host:port (raw TCP, same as audit sink).
    #[serde(default)]
    pub redis_url: String,
    #[serde(default)]
    pub kafka_brokers: String,
    #[serde(default = "default_trace_kafka_topic")]
    pub kafka_topic: String,
    /// Enable/disable trace collection.
    #[serde(default = "default_trace_enabled")]
    pub enabled: bool,
}

fn default_trace_kafka_topic() -> String {
    "virbius-trace-events".to_string()
}

fn default_trace_enabled() -> bool {
    true
}

impl Default for TraceSection {
    fn default() -> Self {
        Self {
            backend: String::new(),
            redis_url: String::new(),
            kafka_brokers: String::new(),
            kafka_topic: default_trace_kafka_topic(),
            enabled: default_trace_enabled(),
        }
    }
}

/// Memory Interceptor configuration (proxy-side override).
///
/// This section is optional; the primary configuration is in the
/// edge manifest (virbius-core SdkConfig).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MemorySection {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default = "default_memory_max_entry_size")]
    pub max_entry_size: usize,
    #[serde(default)]
    pub tool_patterns: Vec<String>,
}

fn default_memory_max_entry_size() -> usize {
    4096
}

impl Default for MemorySection {
    fn default() -> Self {
        Self {
            enabled: false,
            max_entry_size: default_memory_max_entry_size(),
            tool_patterns: vec![],
        }
    }
}
