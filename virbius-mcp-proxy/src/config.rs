/// Configuration loading (TOML file + environment variable overrides).
use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
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
    /// Redis address used only to subscribe to Control's policy stream.
    /// Falls back to `audit.redis_url` when empty.
    #[serde(default)]
    pub policy: PolicyRedisSection,
    /// Delegated credential plane (v2.5-min). Disabled by default; not
    /// hot-reloadable (restart to change).
    #[serde(default)]
    pub delegated: DelegatedSection,
    /// Evaluate-token authentication (P0-min). Enabled when control_base_url
    /// is set; the proxy exchanges the license for a short-lived token.
    #[serde(default)]
    pub auth: AuthSection,
}

/// Evaluate-token auth configuration (P0-min).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuthSection {
    /// Control-plane base URL hosting the exchange endpoint. Empty = disabled.
    #[serde(default)]
    pub control_base_url: String,
    #[serde(default = "default_auth_exchange_path")]
    pub exchange_path: String,
    #[serde(default = "default_auth_timeout_ms")]
    pub exchange_timeout_ms: u64,
    /// Refresh a cached token once this percentage of its TTL has elapsed.
    #[serde(default = "default_auth_refresh_margin")]
    pub refresh_margin_percent: u64,
}

fn default_auth_exchange_path() -> String {
    "/api/v1/auth/evaluate-token".to_string()
}
fn default_auth_timeout_ms() -> u64 {
    3000
}
fn default_auth_refresh_margin() -> u64 {
    50
}

impl Default for AuthSection {
    fn default() -> Self {
        Self {
            control_base_url: String::new(),
            exchange_path: default_auth_exchange_path(),
            exchange_timeout_ms: default_auth_timeout_ms(),
            refresh_margin_percent: default_auth_refresh_margin(),
        }
    }
}

/// Delegated credential plane configuration (v2.5-min, A+B1 contract).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct DelegatedSection {
    /// Master switch. Default false = byte-identical behavior (except the
    /// local-exec ban below, which applies whenever enabled).
    #[serde(default)]
    pub enabled: bool,
    /// Deployment-pinned tenant. Must be non-empty and not "default".
    #[serde(default)]
    pub tenant_id: String,
    /// Tenant KEK, base64 (standard, padded) of exactly 32 bytes.
    /// Prefer `kek_file` (0400) over inline config.
    #[serde(default)]
    pub kek: String,
    #[serde(default)]
    pub kek_file: String,
    /// Snapshot Redis URL; falls back to `audit.redis_url` when empty.
    #[serde(default)]
    pub redis_url: String,
    #[serde(default = "default_delegated_poll")]
    pub poll_interval_secs: u64,
    #[serde(default = "default_delegated_ttl")]
    pub ttl_secs: u64,
    #[serde(default = "default_delegated_max_body_mb")]
    pub max_body_mb: usize,
    /// Explicit, audited acknowledgement of a non-loopback bind.
    #[serde(default)]
    pub allow_external_bind: bool,
}

fn default_delegated_poll() -> u64 {
    30
}
fn default_delegated_ttl() -> u64 {
    300
}
fn default_delegated_max_body_mb() -> usize {
    4
}

/// Where the proxy listens for hot-reloaded policy. Not itself hot-reloaded.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct PolicyRedisSection {
    #[serde(default)]
    pub redis_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ProxySection {
    #[serde(default = "default_listen")]
    pub listen: String,
    #[serde(default = "default_upstream")]
    pub upstream_url: String,
    #[serde(default = "default_upstream_sse_path")]
    pub upstream_sse_path: String,
    #[serde(default)]
    pub upstreams: Vec<UpstreamEntry>,
    #[serde(default = "default_session_ttl_secs")]
    pub session_ttl_secs: u64,
    /// Upstream transports enabled in this deployment. Entries using a
    /// disabled transport are rejected at load time (static config) or
    /// filtered out (Control-pushed policy). Empty = all enabled.
    #[serde(default)]
    pub enabled_upstream_transports: Vec<String>,
    /// Allow Control-pushed tenant policies to define stdio upstreams.
    /// Static TOML stdio entries are always allowed. Default: `false`.
    #[serde(default)]
    pub allow_dynamic_stdio_upstreams: bool,
    /// Command allowlist for stdio upstreams (binary basename or absolute
    /// path). Empty = unrestricted (defense-in-depth, not a sandbox).
    #[serde(default)]
    pub allowed_stdio_commands: Vec<String>,
    /// Deny upstream URLs that resolve to private/loopback addresses.
    /// Default: `false` (internal deployments run on private networks).
    /// Link-local and cloud-metadata addresses are always denied.
    #[serde(default)]
    pub upstream_deny_private: bool,
}

/// Upstream MCP Server entry. One schema for static TOML and the
/// Control-pushed policy, so both sources cannot drift.
///
/// `transport`:
/// - `"auto"` (default) — try Streamable HTTP, fall back to legacy HTTP+SSE
/// - `"streamable-http"` — single-endpoint Streamable HTTP
/// - `"sse"` — legacy HTTP+SSE (2024-11-05 revision)
/// - `"stdio"` — spawn a local MCP server subprocess
///
/// HTTP-family entries use `url` (+ `http_path` / `sse_path`); stdio entries
/// use `command` (+ `args` / `env` / `cwd`). The two are mutually exclusive.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamEntry {
    pub name: String,
    #[serde(default = "default_upstream_transport")]
    pub transport: String,
    #[serde(default)]
    pub url: Option<String>,
    #[serde(default = "default_http_path")]
    pub http_path: String,
    #[serde(default = "default_upstream_sse_path")]
    pub sse_path: String,
    /// Static extra request headers (e.g. upstream `Authorization`).
    /// Static TOML only — dynamic policy entries carrying headers are rejected.
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    #[serde(default)]
    pub command: Option<String>,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    #[serde(default)]
    pub cwd: Option<String>,
    #[serde(default = "default_upstream_timeout_ms")]
    pub timeout_ms: u64,
}

pub const UPSTREAM_TRANSPORTS: [&str; 4] = ["auto", "sse", "streamable-http", "stdio"];

impl Default for UpstreamEntry {
    fn default() -> Self {
        Self {
            name: String::new(),
            transport: default_upstream_transport(),
            url: None,
            http_path: default_http_path(),
            sse_path: default_upstream_sse_path(),
            headers: BTreeMap::new(),
            command: None,
            args: Vec::new(),
            env: BTreeMap::new(),
            cwd: None,
            timeout_ms: default_upstream_timeout_ms(),
        }
    }
}

impl UpstreamEntry {
    /// HTTP-family entry using the default transport (`auto`).
    pub fn http(
        name: impl Into<String>,
        url: impl Into<String>,
        sse_path: impl Into<String>,
    ) -> Self {
        Self {
            name: name.into(),
            url: Some(url.into()),
            sse_path: sse_path.into(),
            ..Self::default()
        }
    }

    pub fn is_stdio(&self) -> bool {
        self.transport == "stdio"
    }

    /// Single validation entry point, shared by static config loading and
    /// policy parsing so the two sources cannot drift.
    pub fn validate(&self) -> Result<(), String> {
        if self.name.trim().is_empty() {
            return Err("upstream name must not be empty".to_string());
        }
        if !UPSTREAM_TRANSPORTS.contains(&self.transport.as_str()) {
            return Err(format!(
                "upstream '{}': unknown transport '{}' (expected one of {:?})",
                self.name, self.transport, UPSTREAM_TRANSPORTS
            ));
        }
        if self.timeout_ms == 0 {
            return Err(format!("upstream '{}': timeout_ms must be > 0", self.name));
        }
        match self.transport.as_str() {
            "stdio" => {
                if self.url.is_some() {
                    return Err(format!(
                        "upstream '{}': stdio entries must not set url",
                        self.name
                    ));
                }
                if !self.headers.is_empty() {
                    return Err(format!(
                        "upstream '{}': stdio entries must not set headers",
                        self.name
                    ));
                }
                if self
                    .command
                    .as_deref()
                    .map(str::trim)
                    .unwrap_or("")
                    .is_empty()
                {
                    return Err(format!(
                        "upstream '{}': stdio requires a non-empty command",
                        self.name
                    ));
                }
            }
            _ => {
                if !self.args.is_empty()
                    || !self.env.is_empty()
                    || self.cwd.is_some()
                    || self.command.is_some()
                {
                    return Err(format!(
                        "upstream '{}': transport '{}' does not accept command/args/env/cwd",
                        self.name, self.transport
                    ));
                }
                if self.url.as_deref().map(str::trim).unwrap_or("").is_empty() {
                    return Err(format!(
                        "upstream '{}': transport '{}' requires url",
                        self.name, self.transport
                    ));
                }
                if !self.http_path.starts_with('/') || !self.sse_path.starts_with('/') {
                    return Err(format!(
                        "upstream '{}': http_path and sse_path must start with '/'",
                        self.name
                    ));
                }
            }
        }
        Ok(())
    }
}

fn default_listen() -> String {
    "stdio".to_string()
}
fn default_upstream() -> String {
    "http://localhost:8080".to_string()
}
fn default_upstream_transport() -> String {
    "auto".to_string()
}
fn default_http_path() -> String {
    "/mcp".to_string()
}
fn default_upstream_timeout_ms() -> u64 {
    30_000
}

fn comma_list(raw: &str) -> Vec<String> {
    raw.split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect()
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
            upstream_sse_path: default_upstream_sse_path(),
            upstreams: Vec::new(),
            session_ttl_secs: default_session_ttl_secs(),
            enabled_upstream_transports: Vec::new(),
            allow_dynamic_stdio_upstreams: false,
            allowed_stdio_commands: Vec::new(),
            upstream_deny_private: false,
        }
    }
}

/// Gate settings for upstream entries, derived from [`ProxySection`].
/// Handed to the upstream manager so Control-pushed policies are filtered
/// with the same rules as static config.
#[derive(Debug, Clone, Default)]
pub struct UpstreamGates {
    pub enabled_transports: Vec<String>,
    pub allow_dynamic_stdio: bool,
    pub allowed_stdio_commands: Vec<String>,
    pub deny_private: bool,
}

impl UpstreamGates {
    pub fn transport_enabled(&self, transport: &str) -> bool {
        self.enabled_transports.is_empty() || self.enabled_transports.iter().any(|t| t == transport)
    }

    /// Returns `true` when a stdio command is permitted by the allowlist.
    /// Empty allowlist = unrestricted.
    pub fn command_allowed(&self, command: &str) -> bool {
        if self.allowed_stdio_commands.is_empty() {
            return true;
        }
        let base = std::path::Path::new(command)
            .file_name()
            .and_then(|s| s.to_str())
            .unwrap_or(command);
        self.allowed_stdio_commands
            .iter()
            .any(|allowed| allowed == command || allowed == base)
    }
}

impl ProxySection {
    pub fn gates(&self) -> UpstreamGates {
        UpstreamGates {
            enabled_transports: self.enabled_upstream_transports.clone(),
            allow_dynamic_stdio: self.allow_dynamic_stdio_upstreams,
            allowed_stdio_commands: self.allowed_stdio_commands.clone(),
            deny_private: self.upstream_deny_private,
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

/// `scheme://host[:anyport]` -> `scheme://host:<port>` (IPv6 literals with
/// brackets keep their brackets; a bare IPv6 without brackets is not supported).
fn same_host_port(base: &str, port: &str) -> Option<String> {
    let (scheme, rest) = base.split_once("://")?;
    let host = if let Some(after) = rest.strip_prefix('[') {
        // "[ipv6]:port" — keep the bracketed literal, drop the port.
        let close = after.find(']')? + 2; // ']' position within `rest`, +1 for it
        rest.get(..close)?
    } else {
        rest.split(':').next().unwrap_or(rest)
    };
    if scheme.is_empty() || host.is_empty() {
        return None;
    }
    Some(format!("{scheme}://{host}:{port}"))
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
    /// Shared Redis endpoint (set via `VIRBIUS_REDIS_URL`). The audit/trace
    /// bus is Kafka-only; this URL now only backs the policy hot-reload stream
    /// and the delegated-credential snapshot (each may override with its own
    /// `redis_url`).
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
        Self::load_from(None)
    }

    /// `explicit` is the `--config <path>` file: when given it MUST be readable
    /// and parse — a typo'd path or malformed TOML exits(2) instead of silently
    /// falling back to defaults. (The legacy search paths keep their old
    /// best-effort behaviour, but now warn when a file exists yet fails to
    /// parse, instead of quietly ignoring it.)
    pub fn load_from(explicit: Option<&std::path::Path>) -> Self {
        let mut cfg = Self::load_from_file(explicit);

        // Environment variable overrides
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAM_URL") {
            cfg.proxy.upstream_url = v;
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
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_BACKEND") {
            cfg.trace.backend = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_KAFKA_BROKERS") {
            cfg.trace.kafka_brokers = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRACE_KAFKA_TOPIC") {
            cfg.trace.kafka_topic = v;
        }
        if let Ok(v) = std::env::var("KAFKA_BOOTSTRAP_SERVERS") {
            if cfg.audit.kafka_brokers.is_empty() {
                cfg.audit.kafka_brokers = v.clone();
            }
            if cfg.trace.kafka_brokers.is_empty() {
                cfg.trace.kafka_brokers = v;
            }
        }
        if cfg.audit.backend.is_empty() && !cfg.audit.kafka_brokers.is_empty() {
            cfg.audit.backend = "kafka".to_string();
        }
        if cfg.trace.backend.is_empty() && !cfg.trace.kafka_brokers.is_empty() {
            cfg.trace.backend = "kafka".to_string();
        }
        if let Ok(v) = std::env::var("VIRBIUS_TRANSPORT") {
            cfg.proxy.listen = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAMS") {
            match serde_json::from_str::<Vec<UpstreamEntry>>(&v) {
                Ok(entries) => cfg.proxy.upstreams = entries,
                Err(e) => eprintln!(
                    "warning: VIRBIUS_UPSTREAMS is not a valid upstream array ({e}); ignoring"
                ),
            }
        }
        if let Ok(v) = std::env::var("VIRBIUS_ENABLED_UPSTREAM_TRANSPORTS") {
            cfg.proxy.enabled_upstream_transports = comma_list(&v);
        }
        if let Ok(v) = std::env::var("VIRBIUS_ALLOW_DYNAMIC_STDIO_UPSTREAMS") {
            cfg.proxy.allow_dynamic_stdio_upstreams = v == "1" || v.eq_ignore_ascii_case("true");
        }
        if let Ok(v) = std::env::var("VIRBIUS_ALLOWED_STDIO_COMMANDS") {
            cfg.proxy.allowed_stdio_commands = comma_list(&v);
        }
        if let Ok(v) = std::env::var("VIRBIUS_UPSTREAM_DENY_PRIVATE") {
            cfg.proxy.upstream_deny_private = v == "1" || v.eq_ignore_ascii_case("true");
        }
        if let Ok(v) = std::env::var("VIRBIUS_POLICY_REDIS_URL") {
            cfg.policy.redis_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_AUTH_CONTROL_URL") {
            cfg.auth.control_base_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_ENABLED") {
            cfg.delegated.enabled = v == "1" || v.eq_ignore_ascii_case("true");
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_TENANT_ID") {
            cfg.delegated.tenant_id = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_KEK") {
            cfg.delegated.kek = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_KEK_FILE") {
            cfg.delegated.kek_file = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_REDIS_URL") {
            cfg.delegated.redis_url = v;
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_POLL_INTERVAL_SECS") {
            if let Ok(n) = v.parse::<u64>() {
                cfg.delegated.poll_interval_secs = n;
            }
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_TTL_SECS") {
            if let Ok(n) = v.parse::<u64>() {
                cfg.delegated.ttl_secs = n;
            }
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_MAX_BODY_MB") {
            if let Ok(n) = v.parse::<usize>() {
                cfg.delegated.max_body_mb = n;
            }
        }
        if let Ok(v) = std::env::var("VIRBIUS_DELEGATED_ALLOW_EXTERNAL_BIND") {
            cfg.delegated.allow_external_bind = v == "1" || v.eq_ignore_ascii_case("true");
        }

        // Explicit entries are an operator decision: a disabled transport or an
        // invalid entry must fail fast, never be silently ignored.
        if let Err(e) = cfg.validate_explicit_upstreams() {
            eprintln!("error: {e}");
            std::process::exit(2);
        }

        // Normalize: with no explicit list, the legacy single-upstream fields
        // synthesize one implicit `default` entry (gate-aware).
        cfg.synthesize_implicit_upstream();

        Self::apply_base_url(&mut cfg);

        cfg
    }

    /// Validate explicitly-configured static upstreams and fail fast on a
    /// disabled transport or an invalid entry.
    fn validate_explicit_upstreams(&self) -> Result<(), String> {
        let gates = self.proxy.gates();
        for entry in &self.proxy.upstreams {
            entry
                .validate()
                .map_err(|e| format!("invalid upstream configuration: {e}"))?;
            if !gates.transport_enabled(&entry.transport) {
                return Err(format!(
                    "upstream '{}' uses transport '{}' which is disabled (enabled: {:?})",
                    entry.name, entry.transport, self.proxy.enabled_upstream_transports
                ));
            }
        }
        Ok(())
    }

    /// Synthesize the implicit `default` upstream from the legacy
    /// single-upstream fields when no explicit upstream list is configured.
    ///
    /// Unlike an explicit entry this is a fallback, not an operator decision:
    /// when deployment gates disable its transport (e.g. a policy-driven
    /// deployment restricted to `sse`), skip it with a warning instead of
    /// aborting startup — Control-pushed tenant policies are authoritative in
    /// that setup, and the built-in default would otherwise make
    /// `VIRBIUS_ENABLED_UPSTREAM_TRANSPORTS` unusable there.
    fn synthesize_implicit_upstream(&mut self) {
        if !self.proxy.upstreams.is_empty() || self.proxy.upstream_url.is_empty() {
            return;
        }
        let entry = UpstreamEntry::http(
            "default",
            self.proxy.upstream_url.clone(),
            self.proxy.upstream_sse_path.clone(),
        );
        if self.proxy.gates().transport_enabled(&entry.transport) {
            self.proxy.upstreams.push(entry);
        } else {
            // Startup diagnostic: the doctor/enroll paths call `load_from`
            // before tracing is initialized, so write to stderr directly.
            eprintln!(
                "warning: built-in default upstream 'default' uses transport '{}' which is \
                 disabled (enabled: {:?}); continuing without a static upstream \
                 (policy-provided upstreams only)",
                entry.transport, self.proxy.enabled_upstream_transports
            );
        }
    }

    /// Opt-in single-base endpoint derivation (`VIRBIUS_BASE_URL`).
    ///
    /// Setting `VIRBIUS_BASE_URL=<control base>` lets one variable stand in for
    /// three in the common single-host deployment:
    ///
    ///   - `security.control_base_url` = the base itself
    ///   - `auth.control_base_url`     = the base itself (enables evaluate-token
    ///     exchange — otherwise a separate `VIRBIUS_AUTH_CONTROL_URL`)
    ///   - `security.engine_url`       = `{scheme}://{host}:8082`
    ///
    /// Purely additive defaults: an explicit `VIRBIUS_CONTROL_URL`,
    /// `VIRBIUS_AUTH_CONTROL_URL`, `VIRBIUS_ENGINE_URL` or config-file value
    /// always wins; derivation only replaces values that are still the
    /// built-in defaults.
    fn apply_base_url(cfg: &mut Self) {
        let Ok(base) = std::env::var("VIRBIUS_BASE_URL") else {
            return;
        };
        let base = base.trim().trim_end_matches('/').to_string();
        if base.is_empty() {
            return;
        }
        if std::env::var_os("VIRBIUS_CONTROL_URL").is_none()
            && cfg.security.control_base_url == default_control_url()
        {
            cfg.security.control_base_url = base.clone();
        }
        if std::env::var_os("VIRBIUS_AUTH_CONTROL_URL").is_none()
            && cfg.auth.control_base_url.is_empty()
        {
            cfg.auth.control_base_url = base.clone();
        }
        if std::env::var_os("VIRBIUS_ENGINE_URL").is_none()
            && cfg.security.engine_url == default_engine_url()
        {
            if let Some(engine) = same_host_port(&base, "8082") {
                cfg.security.engine_url = engine;
            }
        }
    }

    fn load_from_file(explicit: Option<&std::path::Path>) -> Self {
        if let Some(path) = explicit {
            let shown = path.display();
            let raw = match std::fs::read_to_string(path) {
                Ok(raw) => raw,
                Err(e) => {
                    eprintln!("error: --config {shown}: cannot read: {e}");
                    std::process::exit(2);
                }
            };
            return match toml::from_str::<ProxyConfig>(&raw) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("error: --config {shown}: parse failed: {e}");
                    std::process::exit(2);
                }
            };
        }
        // Try config file paths
        let candidates = [
            PathBuf::from("virbius-mcp-proxy.toml"),
            PathBuf::from("/etc/virbius/mcp-proxy.toml"),
        ];
        for path in &candidates {
            if path.exists() {
                if let Ok(raw) = std::fs::read_to_string(path) {
                    match toml::from_str::<ProxyConfig>(&raw) {
                        Ok(cfg) => return cfg,
                        Err(e) => eprintln!(
                            "warning: config file {} exists but failed to parse ({e}); continuing with defaults",
                            path.display()
                        ),
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

    /// Check if audit should use Kafka backend.
    pub fn audit_use_kafka(&self) -> bool {
        self.audit.backend == "kafka" && !self.audit.kafka_brokers.is_empty()
    }

    /// Check if trace should use Kafka backend.
    pub fn trace_use_kafka(&self) -> bool {
        self.trace.backend == "kafka" && !self.trace.kafka_brokers.is_empty()
    }

    /// Redis URL for the Control policy stream. Empty disables hot reload.
    pub fn policy_redis_url(&self) -> &str {
        if self.policy.redis_url.is_empty() {
            &self.audit.redis_url
        } else {
            &self.policy.redis_url
        }
    }

    /// Redis URL for the delegated credential snapshot. Empty falls back to
    /// `audit.redis_url` (same default as the policy stream).
    pub fn delegated_redis_url(&self) -> &str {
        if self.delegated.redis_url.is_empty() {
            &self.audit.redis_url
        } else {
            &self.delegated.redis_url
        }
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
    fn test_same_host_port() {
        assert_eq!(
            same_host_port("http://127.0.0.1:8080", "8082").as_deref(),
            Some("http://127.0.0.1:8082")
        );
        assert_eq!(
            same_host_port("https://virbius.example.com", "8082").as_deref(),
            Some("https://virbius.example.com:8082")
        );
        assert_eq!(
            same_host_port("http://[::1]:9000", "8082").as_deref(),
            Some("http://[::1]:8082")
        );
        assert_eq!(same_host_port("garbage", "8082"), None);
    }

    // Single test: it mutates process env. (Explicit env vars are applied by
    // load_from()'s override section BEFORE apply_base_url runs; here we set
    // the field directly to emulate that, then assert derivation never
    // overwrites an explicitly-configured value.)
    #[test]
    fn test_apply_base_url_derives_and_respects_explicit() {
        // Derived: all three endpoints follow the base when still at defaults.
        std::env::set_var("VIRBIUS_BASE_URL", "http://10.0.0.9:8080/");
        let mut cfg = ProxyConfig::default();
        ProxyConfig::apply_base_url(&mut cfg);
        assert_eq!(cfg.security.control_base_url, "http://10.0.0.9:8080");
        assert_eq!(cfg.auth.control_base_url, "http://10.0.0.9:8080");
        assert_eq!(cfg.security.engine_url, "http://10.0.0.9:8082");

        // Explicit env wins over derivation: the override section already
        // applied the values, and apply_base_url must not touch them.
        std::env::set_var("VIRBIUS_ENGINE_URL", "http://elsewhere:9999");
        std::env::set_var("VIRBIUS_AUTH_CONTROL_URL", "http://auth:1234");
        let mut cfg = ProxyConfig::default();
        cfg.security.engine_url = "http://elsewhere:9999".to_string(); // as load() would
        cfg.auth.control_base_url = "http://auth:1234".to_string(); // as load() would
        ProxyConfig::apply_base_url(&mut cfg);
        assert_eq!(cfg.security.engine_url, "http://elsewhere:9999");
        assert_eq!(cfg.auth.control_base_url, "http://auth:1234");
        assert_eq!(cfg.security.control_base_url, "http://10.0.0.9:8080");

        // Explicit config-file value (non-default) wins too.
        let mut cfg = ProxyConfig::default();
        cfg.security.control_base_url = "http://from-file:7777".to_string();
        ProxyConfig::apply_base_url(&mut cfg);
        assert_eq!(cfg.security.control_base_url, "http://from-file:7777");

        // No base set -> nothing derived.
        std::env::remove_var("VIRBIUS_BASE_URL");
        std::env::remove_var("VIRBIUS_ENGINE_URL");
        std::env::remove_var("VIRBIUS_AUTH_CONTROL_URL");
        let mut cfg = ProxyConfig::default();
        ProxyConfig::apply_base_url(&mut cfg);
        assert_eq!(cfg.security.control_base_url, default_control_url());
        assert!(cfg.auth.control_base_url.is_empty());
    }

    #[test]
    fn test_default_config_values() {
        let cfg = ProxyConfig::default();
        assert_eq!(cfg.proxy.listen, "stdio");
        assert_eq!(cfg.proxy.upstream_url, "http://localhost:8080");
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
    fn test_implicit_default_upstream_respects_transport_gates() {
        // All transports enabled: legacy single-upstream fields synthesize the
        // implicit default entry as before.
        let mut cfg = ProxyConfig::default();
        cfg.synthesize_implicit_upstream();
        assert_eq!(cfg.proxy.upstreams.len(), 1);
        assert_eq!(cfg.proxy.upstreams[0].name, "default");
        assert_eq!(cfg.proxy.upstreams[0].transport, "auto");

        // sse-only deployment gate: the implicit default (auto) is skipped
        // instead of aborting startup, so policy-driven deployments (which
        // receive upstreams from Control) still boot.
        let mut cfg = ProxyConfig::default();
        cfg.proxy.enabled_upstream_transports = vec!["sse".to_string()];
        cfg.synthesize_implicit_upstream();
        assert!(cfg.proxy.upstreams.is_empty());

        // No single-upstream fallback configured -> nothing synthesized.
        let mut cfg = ProxyConfig::default();
        cfg.proxy.upstream_url = String::new();
        cfg.synthesize_implicit_upstream();
        assert!(cfg.proxy.upstreams.is_empty());

        // Explicit upstream lists are never touched by synthesis.
        let mut cfg = ProxyConfig::default();
        cfg.proxy.upstreams = vec![UpstreamEntry::http("mine", "http://127.0.0.1:1", "/sse")];
        cfg.synthesize_implicit_upstream();
        assert_eq!(cfg.proxy.upstreams.len(), 1);
        assert_eq!(cfg.proxy.upstreams[0].name, "mine");
    }

    #[test]
    fn test_explicit_upstream_with_disabled_transport_is_rejected() {
        let mut cfg = ProxyConfig::default();
        cfg.proxy.enabled_upstream_transports = vec!["sse".to_string()];
        cfg.proxy.upstreams = vec![UpstreamEntry::http("mine", "http://127.0.0.1:1", "/sse")];

        let err = cfg
            .validate_explicit_upstreams()
            .expect_err("auto entry must be rejected by an sse-only gate");
        assert!(err.contains("disabled"), "unexpected error: {err}");

        cfg.proxy.enabled_upstream_transports = vec!["auto".to_string()];
        assert!(cfg.validate_explicit_upstreams().is_ok());
    }

    #[test]
    fn test_fallback_policy_enum_debug_clone() {
        let p = FallbackPolicy::DefaultDeny;
        assert_eq!(format!("{:?}", p), "DefaultDeny");
        let cloned = p;
        assert_eq!(cloned, FallbackPolicy::DefaultDeny);
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
            url: Some("http://localhost:8001".to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
        };
        assert_eq!(entry.name, "test");
        assert_eq!(entry.sse_path, "/sse");
        assert_eq!(entry.transport, "auto");
        assert_eq!(entry.http_path, "/mcp");
        assert_eq!(entry.timeout_ms, 30_000);
        entry.validate().expect("valid http entry");
    }

    #[test]
    fn test_upstream_entry_validation_matrix() {
        // HTTP entry needs a url.
        let missing_url = UpstreamEntry {
            name: "x".into(),
            ..Default::default()
        };
        assert!(missing_url.validate().is_err());

        // HTTP entry must not carry stdio fields.
        let mixed = UpstreamEntry {
            name: "x".into(),
            url: Some("http://mcp:8080".into()),
            command: Some("npx".into()),
            ..Default::default()
        };
        assert!(mixed.validate().is_err());

        // stdio needs a command and must not set url.
        let stdio_ok = UpstreamEntry {
            name: "fs".into(),
            transport: "stdio".into(),
            command: Some("npx".into()),
            args: vec!["-y".into(), "server-filesystem".into()],
            ..Default::default()
        };
        stdio_ok.validate().expect("valid stdio entry");

        let stdio_no_cmd = UpstreamEntry {
            name: "fs".into(),
            transport: "stdio".into(),
            ..Default::default()
        };
        assert!(stdio_no_cmd.validate().is_err());

        let stdio_with_url = UpstreamEntry {
            name: "fs".into(),
            transport: "stdio".into(),
            command: Some("npx".into()),
            url: Some("http://mcp:8080".into()),
            ..Default::default()
        };
        assert!(stdio_with_url.validate().is_err());

        // Unknown transport is rejected.
        let unknown = UpstreamEntry {
            name: "x".into(),
            transport: "websocket".into(),
            url: Some("http://mcp:8080".into()),
            ..Default::default()
        };
        assert!(unknown.validate().is_err());
    }

    #[test]
    fn test_upstream_gates() {
        let gates = UpstreamGates {
            enabled_transports: vec!["sse".into()],
            allow_dynamic_stdio: false,
            allowed_stdio_commands: vec!["npx".into(), "/usr/bin/node".into()],
            deny_private: false,
        };
        assert!(gates.transport_enabled("sse"));
        assert!(!gates.transport_enabled("streamable-http"));
        assert!(gates.command_allowed("npx"));
        assert!(gates.command_allowed("/usr/local/bin/npx"));
        assert!(gates.command_allowed("/usr/bin/node"));
        assert!(!gates.command_allowed("bash"));

        let open = UpstreamGates::default();
        assert!(open.transport_enabled("stdio"));
        assert!(open.command_allowed("anything"));
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
