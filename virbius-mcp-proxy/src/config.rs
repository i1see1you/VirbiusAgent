/// Configuration loading (TOML file + environment variable overrides).
use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
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
    /// Path to a file containing a License JWT (Ed25519-signed).
    /// Used as fallback when Agent does not pass `_meta.license_jwt` in `initialize`.
    #[serde(default)]
    pub license_file: String,
    #[serde(default = "default_fallback")]
    pub fallback_policy: String,
    #[serde(default)]
    pub fast_path: FastPathConfig,
    #[serde(default)]
    pub failover: FailoverConfig,
    #[serde(default)]
    pub output_review: OutputReviewConfig,
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
            license_file: String::new(),
            fallback_policy: default_fallback(),
            fast_path: FastPathConfig::default(),
            failover: FailoverConfig::default(),
            output_review: OutputReviewConfig::default(),
        }
    }
}

/// Output review configuration: controls LLM content safety review of tool results.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OutputReviewConfig {
    /// Master switch for output content safety review.
    #[serde(default = "default_or_enabled")]
    pub enabled: bool,
    /// Minimum text length (in characters) to trigger LLM review.
    /// Texts shorter than this are only reviewed if risk score is high.
    #[serde(default = "default_or_min_text_length")]
    pub min_text_length: usize,
    /// Minimum session risk score to trigger LLM review regardless of text length.
    #[serde(default = "default_or_min_risk_score")]
    pub min_risk_score: u32,
    /// If true, allow tool result to pass through when engine is unavailable.
    /// If false, block the result on engine failure (fail-closed).
    #[serde(default = "default_or_fail_open")]
    pub fail_open: bool,
}

fn default_or_enabled() -> bool {
    true
}
fn default_or_min_text_length() -> usize {
    512
}
fn default_or_min_risk_score() -> u32 {
    50
}
fn default_or_fail_open() -> bool {
    true
}

impl Default for OutputReviewConfig {
    fn default() -> Self {
        Self {
            enabled: default_or_enabled(),
            min_text_length: default_or_min_text_length(),
            min_risk_score: default_or_min_risk_score(),
            fail_open: default_or_fail_open(),
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
        if let Ok(v) = std::env::var("VIRBIUS_LICENSE_FILE") {
            cfg.security.license_file = v;
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
    "execute_node",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_config_values() {
        let cfg = ProxyConfig::default();
        assert_eq!(cfg.proxy.listen, "stdio");
        assert_eq!(cfg.proxy.upstream_url, "http://localhost:8080");
        assert_eq!(cfg.proxy.upstream_transport, "sse");
        assert_eq!(cfg.proxy.upstream_sse_path, "/sse");
        assert_eq!(cfg.proxy.session_ttl_secs, 1800);
        assert!(cfg.proxy.upstreams.is_empty());
    }

    #[test]
    fn test_default_security_values() {
        let cfg = ProxyConfig::default();
        assert_eq!(cfg.security.control_base_url, "http://localhost:8080");
        assert_eq!(cfg.security.engine_url, "http://localhost:8082");
        assert!(cfg.security.license_public_key.is_empty());
        assert!(cfg.security.license_file.is_empty());
        assert_eq!(cfg.security.fallback_policy, "minimum_privilege");
        assert!(cfg.security.fast_path.enabled);
        assert_eq!(cfg.security.fast_path.warmup_calls, 5);
        assert_eq!(cfg.security.fast_path.risk_threshold, 30);
        assert!(cfg.security.failover.high_risk_fail_closed);
        assert!(cfg.security.failover.low_risk_fail_open);
        assert_eq!(cfg.security.failover.engine_timeout_ms, 3000);
    }

    #[test]
    fn test_default_audit_values() {
        let cfg = ProxyConfig::default();
        assert!(cfg.audit.backend.is_empty());
        assert!(cfg.audit.redis_url.is_empty());
        assert!(cfg.audit.kafka_brokers.is_empty());
        assert_eq!(cfg.audit.kafka_topic, "virbius-audit-events");
        assert!((cfg.audit.sample_rate - 1.0).abs() < f64::EPSILON);
    }

    #[test]
    fn test_default_trace_values() {
        let cfg = ProxyConfig::default();
        assert!(cfg.trace.backend.is_empty());
        assert!(cfg.trace.redis_url.is_empty());
        assert!(cfg.trace.kafka_brokers.is_empty());
        assert_eq!(cfg.trace.kafka_topic, "virbius-trace-events");
        assert!(cfg.trace.enabled);
    }

    #[test]
    fn test_default_memory_values() {
        let cfg = ProxyConfig::default();
        assert!(!cfg.memory.enabled);
        assert_eq!(cfg.memory.max_entry_size, 4096);
        assert!(cfg.memory.tool_patterns.is_empty());
    }

    #[test]
    fn test_fallback_policy_parsing() {
        let mut cfg = ProxyConfig::default();

        cfg.security.fallback_policy = "minimum_privilege".to_string();
        assert_eq!(cfg.fallback_policy(), FallbackPolicy::MinimumPrivilege);

        cfg.security.fallback_policy = "default_deny".to_string();
        assert_eq!(cfg.fallback_policy(), FallbackPolicy::DefaultDeny);

        cfg.security.fallback_policy = "audit_only".to_string();
        assert_eq!(cfg.fallback_policy(), FallbackPolicy::AuditOnly);

        cfg.security.fallback_policy = "unknown_value".to_string();
        assert_eq!(cfg.fallback_policy(), FallbackPolicy::MinimumPrivilege);

        cfg.security.fallback_policy = "".to_string();
        assert_eq!(cfg.fallback_policy(), FallbackPolicy::MinimumPrivilege);
    }

    #[test]
    fn test_fallback_policy_enum_debug_clone() {
        let p = FallbackPolicy::DefaultDeny;
        assert_eq!(format!("{:?}", p), "DefaultDeny");
        let cloned = p;
        assert_eq!(cloned, FallbackPolicy::DefaultDeny);
    }

    #[test]
    fn test_trace_redis_url_fallback() {
        let mut cfg = ProxyConfig::default();

        // When trace.redis_url is empty, fallback to audit.redis_url
        cfg.audit.redis_url = "127.0.0.1:6379".to_string();
        assert_eq!(cfg.trace_redis_url(), "127.0.0.1:6379");

        // When trace.redis_url is set, use it
        cfg.trace.redis_url = "10.0.0.1:6380".to_string();
        assert_eq!(cfg.trace_redis_url(), "10.0.0.1:6380");
    }

    #[test]
    fn test_audit_use_kafka() {
        let mut cfg = ProxyConfig::default();
        assert!(!cfg.audit_use_kafka());

        cfg.audit.backend = "kafka".to_string();
        // backend=kafka but no brokers → false
        assert!(!cfg.audit_use_kafka());

        cfg.audit.kafka_brokers = "kafka-1:9092".to_string();
        assert!(cfg.audit_use_kafka());
    }

    #[test]
    fn test_trace_use_kafka() {
        let mut cfg = ProxyConfig::default();
        assert!(!cfg.trace_use_kafka());

        cfg.trace.backend = "kafka".to_string();
        assert!(!cfg.trace_use_kafka());

        cfg.trace.kafka_brokers = "kafka-1:9092".to_string();
        assert!(cfg.trace_use_kafka());
    }

    #[test]
    fn test_high_risk_tools_contains_shell() {
        assert!(HIGH_RISK_TOOLS.contains(&"shell"));
        assert!(HIGH_RISK_TOOLS.contains(&"curl"));
        assert!(HIGH_RISK_TOOLS.contains(&"read_file"));
        assert!(HIGH_RISK_TOOLS.contains(&"delete_file"));
        assert!(HIGH_RISK_TOOLS.contains(&"sql_query"));
    }

    #[test]
    fn test_high_risk_tools_not_contains_low_risk() {
        assert!(!HIGH_RISK_TOOLS.contains(&"read_file_info"));
        assert!(!HIGH_RISK_TOOLS.contains(&"list_files"));
        assert!(!HIGH_RISK_TOOLS.contains(&"search"));
    }

    #[test]
    fn test_upstream_entry_defaults() {
        let entry = UpstreamEntry {
            name: "test".to_string(),
            url: "http://localhost:8001".to_string(),
            sse_path: "/sse".to_string(),
        };
        assert_eq!(entry.name, "test");
        assert_eq!(entry.sse_path, "/sse");
    }

    #[test]
    fn test_config_toml_deserialize() {
        let toml_str = r#"
            [proxy]
            listen = "tcp://0.0.0.0:9090"
            upstream_url = "http://mcp:8080"
            session_ttl_secs = 3600

            [security]
            engine_url = "http://engine:8082"
            fallback_policy = "default_deny"

            [security.fast_path]
            enabled = false

            [audit]
            backend = "kafka"
            kafka_brokers = "kafka:9092"

            [trace]
            enabled = false

            [memory]
            enabled = true
            max_entry_size = 8192
        "#;
        let cfg: ProxyConfig = toml::from_str(toml_str).unwrap();
        assert_eq!(cfg.proxy.listen, "tcp://0.0.0.0:9090");
        assert_eq!(cfg.proxy.upstream_url, "http://mcp:8080");
        assert_eq!(cfg.proxy.session_ttl_secs, 3600);
        assert_eq!(cfg.security.engine_url, "http://engine:8082");
        assert_eq!(cfg.security.fallback_policy, "default_deny");
        assert!(!cfg.security.fast_path.enabled);
        assert_eq!(cfg.audit.backend, "kafka");
        assert_eq!(cfg.audit.kafka_brokers, "kafka:9092");
        assert!(!cfg.trace.enabled);
        assert!(cfg.memory.enabled);
        assert_eq!(cfg.memory.max_entry_size, 8192);
    }
}
