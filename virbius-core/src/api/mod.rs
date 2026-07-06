mod context;
mod error;
mod result;

pub use context::ScanContext;
pub use error::VirbiusError;
pub use result::{EffectiveAction, RuleHit, ScanOutcome};

pub use crate::trace::TraceIdSource;

use crate::bootstrap;
use crate::dlp;
use crate::engine::{self, ScanRequest};
use crate::manifest;
use crate::sync::EdgeInitConfig;
use std::time::Duration;

pub use crate::dlp::{DesensitizeInResult, DesensitizeOutResult, DlpHit};

/// Edge L0 SDK entry point (idiomatic Rust).
#[derive(Debug, Default, Clone, Copy)]
pub struct VirbiusEdge;

impl VirbiusEdge {
    /// Production entry: host app supplies [`EdgeInitConfig`] (no environment variables).
    pub fn init(cfg: EdgeInitConfig) -> Result<Self, VirbiusError> {
        bootstrap::bootstrap(&cfg)?;
        Ok(Self)
    }

    /// Examples / CI only: reads `VIRBIUS_*` environment variables.
    #[doc(hidden)]
    pub fn new_from_env() -> Result<Self, VirbiusError> {
        bootstrap::bootstrap(&EdgeInitConfig::from_env())?;
        Ok(Self)
    }

    /// Convenience for unit tests and local demos without env; loads empty rules if not configured.
    pub fn new() -> Self {
        Self::new_from_env().unwrap_or_else(|_| {
            let _ = bootstrap::bootstrap(&EdgeInitConfig::default());
            Self
        })
    }

    pub fn reload(&self) {
        bootstrap::reload_synced();
    }

    pub fn scan(&self, content: &str) -> Result<ScanOutcome, VirbiusError> {
        self.scan_with(ScanContext::default(), content)
    }

    pub fn scan_with(&self, ctx: ScanContext, content: &str) -> Result<ScanOutcome, VirbiusError> {
        if content.is_empty() {
            return Err(VirbiusError::EmptyContent);
        }
        let (trace_id, trace_id_source) = ctx.resolve_trace_id()?;
        let scene = ctx.scene_or_default();
        let engine_result = engine::scan_once(ScanRequest {
            user_id: ctx.user_id.as_deref(),
            device_id: ctx.device_id.as_deref(),
            scene: &scene,
            trace_id: &trace_id,
            trace_id_source,
            content,
        });
        Ok(to_outcome(engine_result))
    }

    /// Mask PII before cloud upload; dry_run rules detect only (plaintext returned).
    pub fn desensitize_in(&self, content: &str) -> Result<DesensitizeInResult, VirbiusError> {
        self.desensitize_in_with(ScanContext::default(), content)
    }

    pub fn desensitize_in_with(
        &self,
        ctx: ScanContext,
        content: &str,
    ) -> Result<DesensitizeInResult, VirbiusError> {
        if content.is_empty() {
            return Err(VirbiusError::EmptyContent);
        }
        let (trace_id, _) = ctx.resolve_trace_id()?;
        Ok(run_desensitize_in(&ctx, &trace_id, content))
    }

    /// Restore placeholders in model output using the trace-scoped vault.
    pub fn desensitize_out(&self, trace_id: &str, content: &str) -> DesensitizeOutResult {
        self.desensitize_out_with(trace_id, content, ScanContext::default())
    }

    pub fn desensitize_out_with(
        &self,
        trace_id: &str,
        content: &str,
        ctx: ScanContext,
    ) -> DesensitizeOutResult {
        let session_id = session_id_from_context(&ctx);
        dlp::desensitize_out(content, trace_id, session_id.as_deref())
    }
}

fn run_desensitize_in(ctx: &ScanContext, trace_id: &str, content: &str) -> DesensitizeInResult {
    let manifest = manifest::load();
    let session_id = session_id_from_context(ctx);
    let ttl = Duration::from_millis(manifest.sdk_config.dlp_vault_ttl_ms);
    dlp::desensitize_in(
        content,
        trace_id,
        &manifest.dlp_rules,
        ttl,
        session_id.as_deref(),
    )
}

fn session_id_from_context(ctx: &ScanContext) -> Option<String> {
    let cfg = manifest::load().sdk_config;
    manifest::session_key_value(
        &cfg.canary_session_key,
        ctx.user_id.as_deref(),
        ctx.device_id.as_deref(),
        None,
    )
    .map(|s| s.to_string())
}

fn to_outcome(engine_result: engine::ScanEngineResult) -> ScanOutcome {
    let action = EffectiveAction::from_effective(&engine_result.merged.effective_action);
    let primary = engine::primary_rule(&engine_result.merged).map(RuleHit::from);
    ScanOutcome {
        action,
        trace_id: engine_result.trace_id,
        trace_id_source: engine_result.trace_id_source,
        max_risk_score: engine_result.merged.max_risk_score,
        primary,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace;
    use std::io::Write;
    use std::sync::{Mutex, OnceLock};
    use tempfile::NamedTempFile;

    static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

    fn test_lock() -> std::sync::MutexGuard<'static, ()> {
        TEST_LOCK.get_or_init(|| Mutex::new(())).lock().unwrap()
    }

    fn with_manifest(json: &str, f: impl FnOnce()) {
        let _guard = test_lock();
        let mut file = NamedTempFile::new().expect("temp manifest");
        file.write_all(json.as_bytes()).expect("write manifest");
        let path = file.path().to_path_buf();
        let cache_dir = path.parent().unwrap().to_path_buf();
        let cfg = EdgeInitConfig {
            offline_manifest_path: Some(path),
            cache_dir,
            tenant_id: "default".into(),
            app_id: "test".into(),
            ..Default::default()
        };
        bootstrap::bootstrap(&cfg).expect("bootstrap");
        f();
    }

    #[test]
    fn scan_blocks_deny_keyword_in_full_mode() {
        with_manifest(
            r#"{
              "tenant_id": "default",
              "rules": [{
                "rule_id": "edge_kw",
                "rule_revision": 1,
                "reason_code": "EDGE_KW",
                "risk_score": 100,
                "intent_action": "deny",
                "enforce_mode": "full",
                "body": { "list_type": "deny", "keywords": ["jailbreak"] }
              }]
            }"#,
            || {
                let edge = VirbiusEdge;
                let out = edge.scan("please jailbreak now").expect("scan");
                assert_eq!(out.action, EffectiveAction::Block);
                assert_eq!(
                    out.primary.as_ref().map(|r| r.rule_id.as_str()),
                    Some("edge_kw")
                );
                assert!(trace::valid_trace_id(&out.trace_id));
                assert_eq!(out.trace_id_source, TraceIdSource::SdkGenerated);
            },
        );
    }

    #[test]
    fn scan_review_on_dry_run_hit() {
        with_manifest(
            r#"{
              "rules": [{
                "rule_id": "edge_kw",
                "rule_revision": 1,
                "reason_code": "EDGE_KW",
                "risk_score": 100,
                "intent_action": "deny",
                "enforce_mode": "dry_run",
                "body": { "list_type": "deny", "keywords": ["jailbreak"] }
              }]
            }"#,
            || {
                let edge = VirbiusEdge;
                let out = edge.scan("jailbreak").expect("scan");
                assert_eq!(out.action, EffectiveAction::Review);
            },
        );
    }

    #[test]
    fn scan_rejects_empty_content() {
        let edge = VirbiusEdge::new();
        assert_eq!(edge.scan(""), Err(VirbiusError::EmptyContent));
    }

    #[test]
    fn desensitize_masks_phone_in_full_mode() {
        with_manifest(
            r#"{
              "dlp_rules": [{
                "rule_id": "dlp_phone",
                "rule_revision": 1,
                "reason_code": "DLP_PHONE",
                "risk_score": 0,
                "intent_action": "allow",
                "enforce_mode": "full",
                "rollout_state": "full",
                "body": { "entity_type": "phone_cn" }
              }],
              "sdk_config": { "dlp_vault_ttl_ms": 60000 }
            }"#,
            || {
                let edge = VirbiusEdge;
                let trace = "550e8400-e29b-41d4-a716-446655440000";
                let ctx = ScanContext {
                    trace_id: Some(trace.into()),
                    ..ScanContext::default()
                };
                let in_result = edge
                    .desensitize_in_with(ctx.clone(), "tel 13912345678")
                    .expect("desensitize_in");
                assert!(in_result.masked);
                assert!(in_result.text.contains("{{VIRBIUS_PHONE_CN_0}}"));

                let out_result = edge.desensitize_out_with(trace, &in_result.text, ctx);
                assert!(out_result.text.contains("13912345678"));
            },
        );
    }
}
