use serde::Deserialize;
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
    pub rule_revision: i32,
    pub reason_code: String,
    pub risk_score: i32,
    pub intent_action: String,
    pub enforce_mode: String,
    #[serde(default)]
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
}

#[derive(Debug, Clone)]
pub struct EdgeManifest {
    pub tenant_id: String,
    pub app_id: String,
    pub rules: Vec<EdgeRule>,
    pub dlp_rules: Vec<DlpRule>,
    pub sdk_config: SdkConfig,
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

pub fn manifest_path() -> std::path::PathBuf {
    crate::sync::EdgeInitConfig::resolve().manifest_path()
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
