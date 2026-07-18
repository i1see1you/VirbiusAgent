use serde::{Deserialize, Serialize};
use std::{
    fs,
    sync::{OnceLock, RwLock},
};

#[derive(Debug, Clone, Default, Deserialize)]
pub struct DlpRuleBody {
    #[serde(default, rename = "entity_type")]
    pub entity_type: String,
    #[serde(default)]
    pub pattern: Option<String>,
    #[serde(default, rename = "mask_template")]
    pub mask_template: Option<String>,
    #[serde(default)]
    pub priority: Option<i32>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DlpRule {
    pub rule_id: String,
    #[allow(dead_code)]
    pub rule_revision: i32,
    #[allow(dead_code)]
    pub reason_code: String,
    #[allow(dead_code)]
    pub risk_score: i32,
    #[allow(dead_code)]
    pub intent_action: String,
    pub enforce_mode: String,
    #[serde(default)]
    #[allow(dead_code)]
    pub rollout_state: String,
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub body: DlpRuleBody,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct RuleBody {
    #[serde(default)]
    pub keywords: Vec<String>,
    #[serde(default, rename = "list_type")]
    pub list_type: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct EdgeRule {
    pub rule_id: String,
    pub rule_revision: i32,
    pub reason_code: String,
    pub risk_score: i32,
    pub intent_action: String,
    pub enforce_mode: String,
    #[serde(default)]
    pub rollout_state: String,
    pub canary_percent: Option<i32>,
    #[serde(default)]
    pub body: RuleBody,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct SdkConfig {
    #[serde(default)]
    pub audit_ingest_url: String,
    #[serde(default)]
    pub audit_ingest_token: String,
    #[serde(default = "default_sample_allow")]
    pub audit_sample_rate_allow: f64,
    #[serde(default = "default_sample_hit")]
    pub audit_sample_rate_hit: f64,
    #[serde(default = "default_flush_ms")]
    pub audit_flush_interval_ms: u64,
    #[serde(default = "default_queue_max")]
    pub audit_queue_max: usize,
    #[serde(default = "default_session_key")]
    pub canary_session_key: String,
    #[serde(default = "default_dlp_vault_ttl")]
    pub dlp_vault_ttl_ms: u64,
    /// Whether output PII masking is enabled for tool return values.
    #[serde(default)]
    pub output_pii_masking_enabled: bool,
    /// Tools that are exempt from output PII masking (e.g. query_idcard, kyc_verify).
    #[serde(default)]
    pub pii_exempt_tools: Vec<String>,
    /// Whether Memory Interceptor is enabled.
    #[serde(default)]
    pub memory_interceptor_enabled: bool,
    /// Whether to desensitize PII when writing to Agent memory.
    #[serde(default = "default_true")]
    pub memory_desensitize_on_write: bool,
    /// Whether to call Engine for LLM-based injection detection on memory writes.
    #[serde(default = "default_true")]
    pub memory_detect_injection_on_write: bool,
    /// Maximum size (in bytes) for a single memory entry.
    #[serde(default = "default_memory_max_entry_size")]
    pub memory_max_entry_size: usize,
    /// Tool name prefixes that are recognized as memory write operations.
    #[serde(default = "default_memory_tool_patterns")]
    pub memory_tool_patterns: Vec<String>,
    /// Whether explicit trust layering is enabled for high/network risk tools.
    /// When enabled, the MCP proxy wraps high/network risk tool results in
    /// `<trust_boundary>` tags before returning them to the Agent.
    #[serde(default = "default_true")]
    pub trust_layering_enabled: bool,
    /// Risk classes that require explicit trust boundary wrapping.
    /// Defaults to ["high", "network"].
    #[serde(default = "default_trust_tagged_risk_classes")]
    pub trust_tagged_risk_classes: Vec<String>,
}

fn default_dlp_vault_ttl() -> u64 {
    1_800_000
}

fn default_sample_allow() -> f64 {
    0.1
}
fn default_sample_hit() -> f64 {
    1.0
}
fn default_flush_ms() -> u64 {
    30000
}
fn default_queue_max() -> usize {
    500
}
fn default_session_key() -> String {
    "device_id".into()
}

fn default_true() -> bool {
    true
}

fn default_memory_max_entry_size() -> usize {
    4096
}

fn default_memory_tool_patterns() -> Vec<String> {
    vec![
        "memory_save".into(),
        "memory_write".into(),
        "memory_store".into(),
        "vector_store".into(),
        "vector_add".into(),
        "embedding_store".into(),
        "embedding_add".into(),
        "save_memory".into(),
        "store_memory".into(),
        "recall_save".into(),
    ]
}

fn default_trust_tagged_risk_classes() -> Vec<String> {
    vec!["high".into(), "network".into()]
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ToolPolicy {
    pub tool_name: String,
    /// Risk class for session risk scoring: low(1) / medium(3) / high(5) / network(4).
    /// Configured via operational console (rule bind_scope=tool → bind_ref.risk_class).
    #[serde(default = "default_risk_class")]
    pub risk_class: String,
    #[serde(default)]
    pub allowed_args_schema: Option<serde_json::Value>,
    #[serde(default)]
    pub fast_path: bool,
    #[serde(default)]
    pub sandbox_type: String,
    #[serde(default)]
    pub timeout_ms: u64,
}

fn default_risk_class() -> String {
    "low".into()
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct LandlockProfile {
    pub tool_name: String,
    #[serde(default)]
    pub read_paths: Vec<String>,
    #[serde(default)]
    pub write_paths: Vec<String>,
    #[serde(default)]
    pub exec_paths: Vec<String>,
}

/// gVisor sandbox configuration for untrusted code execution (P2).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct GvisorConfig {
    /// Path to the runsc binary.
    #[serde(default = "default_runsc_path")]
    pub runsc_path: String,
    /// Root filesystem path for the container.
    #[serde(default = "default_rootfs_path")]
    pub rootfs_path: String,
    /// Minimum number of warm containers per language.
    #[serde(default = "default_min_warm")]
    pub min_warm: usize,
    /// Maximum number of idle containers per language.
    #[serde(default = "default_max_idle")]
    pub max_idle: usize,
    /// Memory limit per container in bytes.
    #[serde(default = "default_gvisor_mem_limit")]
    pub memory_limit_bytes: u64,
    /// CPU quota (number of CPUs).
    #[serde(default = "default_cpu_quota")]
    pub cpu_quota: f64,
    /// Whether network access is disabled inside the container.
    #[serde(default = "default_true")]
    pub network_disabled: bool,
    /// Execution timeout in milliseconds.
    #[serde(default = "default_gvisor_timeout_ms")]
    pub exec_timeout_ms: u64,
}

fn default_runsc_path() -> String {
    "/usr/local/bin/runsc".into()
}
fn default_rootfs_path() -> String {
    "/opt/virbius/rootfs".into()
}
fn default_min_warm() -> usize {
    2
}
fn default_max_idle() -> usize {
    5
}
fn default_gvisor_mem_limit() -> u64 {
    256 * 1024 * 1024
}
fn default_cpu_quota() -> f64 {
    1.0
}
fn default_gvisor_timeout_ms() -> u64 {
    30000
}

#[derive(Debug, Clone, Deserialize)]
struct EdgeManifestFile {
    #[serde(default)]
    tenant_id: String,
    #[serde(default)]
    app_id: String,
    #[serde(default)]
    rules: Vec<EdgeRule>,
    #[serde(default)]
    dlp_rules: Vec<DlpRule>,
    #[serde(default)]
    sdk_config: SdkConfig,
    #[serde(default)]
    tool_policies: Vec<ToolPolicy>,
    #[serde(default)]
    landlock_profiles: Vec<LandlockProfile>,
    #[serde(default)]
    gvisor_config: GvisorConfig,
}

#[derive(Debug, Clone)]
pub struct EdgeManifest {
    pub tenant_id: String,
    pub app_id: String,
    pub rules: Vec<EdgeRule>,
    pub dlp_rules: Vec<DlpRule>,
    pub sdk_config: SdkConfig,
    pub tool_policies: Vec<ToolPolicy>,
    #[allow(dead_code)]
    pub landlock_profiles: Vec<LandlockProfile>,
    #[allow(dead_code)]
    pub gvisor_config: GvisorConfig,
}

static MANIFEST: OnceLock<RwLock<EdgeManifest>> = OnceLock::new();

fn manifest_lock() -> &'static RwLock<EdgeManifest> {
    MANIFEST.get_or_init(|| {
        RwLock::new(EdgeManifest {
            tenant_id: "default".into(),
            app_id: String::new(),
            rules: Vec::new(),
            dlp_rules: Vec::new(),
            sdk_config: SdkConfig::default(),
            tool_policies: Vec::new(),
            landlock_profiles: Vec::new(),
            gvisor_config: GvisorConfig::default(),
        })
    })
}

pub fn load() -> EdgeManifest {
    manifest_lock().read().expect("manifest lock").clone()
}

pub fn reload() {
    *manifest_lock().write().expect("manifest lock") = read_manifest();
}

fn read_manifest() -> EdgeManifest {
    let cfg = crate::sync::EdgeInitConfig::resolve();
    let path = cfg.manifest_path();
    if let Ok(raw) = fs::read_to_string(&path) {
        if let Ok(parsed) = serde_json::from_str::<EdgeManifestFile>(&raw) {
            let mut rules = parsed.rules;
            let mut dlp_rules = parsed.dlp_rules;
            if !app_id_matches(&parsed.app_id, &cfg) {
                eprintln!("virbius-core: manifest app_id mismatch; refusing load");
                rules = Vec::new();
                dlp_rules = Vec::new();
            }
            return EdgeManifest {
                tenant_id: if parsed.tenant_id.is_empty() {
                    cfg.tenant_id.clone()
                } else {
                    parsed.tenant_id
                },
                app_id: resolve_app_id(&parsed.app_id, &cfg),
                rules,
                dlp_rules,
                sdk_config: parsed.sdk_config,
                tool_policies: parsed.tool_policies,
                landlock_profiles: parsed.landlock_profiles,
                gvisor_config: parsed.gvisor_config,
            };
        }
        eprintln!(
            "virbius-core: failed to parse edge manifest at {}",
            path.display()
        );
    } else {
        eprintln!(
            "virbius-core: edge manifest not found at {}",
            path.display()
        );
    }
    EdgeManifest {
        tenant_id: cfg.tenant_id.clone(),
        app_id: resolve_app_id("", &cfg),
        rules: Vec::new(),
        dlp_rules: Vec::new(),
        sdk_config: SdkConfig::default(),
        tool_policies: Vec::new(),
        landlock_profiles: Vec::new(),
        gvisor_config: GvisorConfig::default(),
    }
}

fn resolve_app_id(manifest_app_id: &str, cfg: &crate::sync::EdgeInitConfig) -> String {
    if !manifest_app_id.is_empty() {
        return manifest_app_id.to_string();
    }
    cfg.app_id.clone()
}

fn app_id_matches(manifest_app_id: &str, cfg: &crate::sync::EdgeInitConfig) -> bool {
    if cfg.offline_manifest_path.is_some() {
        return true;
    }
    let expected = &cfg.app_id;
    !expected.is_empty() && !manifest_app_id.is_empty() && expected == manifest_app_id
}

pub fn effective_sdk_config() -> SdkConfig {
    load().sdk_config
}

pub fn session_key_value<'a>(
    key: &str,
    user_id: Option<&'a str>,
    device_id: Option<&'a str>,
    install_id: Option<&'a str>,
) -> Option<&'a str> {
    match key {
        "install_id" => install_id.filter(|s| !s.is_empty()),
        "user_id" => user_id.filter(|s| !s.is_empty()),
        _ => device_id.filter(|s| !s.is_empty()),
    }
}

#[allow(dead_code)]
pub fn tenant_id() -> String {
    load().tenant_id
}

#[allow(dead_code)]
pub fn app_id() -> String {
    load().app_id
}

pub fn tool_policy(name: &str) -> Option<ToolPolicy> {
    load()
        .tool_policies
        .into_iter()
        .find(|t| t.tool_name == name)
}

/// Look up the risk_class for a tool. Returns "low" if not configured.
pub fn tool_risk_class(name: &str) -> String {
    tool_policy(name)
        .map(|p| {
            if p.risk_class.is_empty() {
                "low".to_string()
            } else {
                p.risk_class
            }
        })
        .unwrap_or_else(|| "low".to_string())
}
