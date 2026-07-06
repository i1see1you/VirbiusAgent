use crate::audit;
use crate::enforce::{self, EnforceResult};
use crate::manifest::{self, EdgeRule, SdkConfig};
use crate::matcher;
use crate::trace::TraceIdSource;

pub struct ScanRequest<'a> {
    pub user_id: Option<&'a str>,
    pub device_id: Option<&'a str>,
    pub scene: &'a str,
    pub trace_id: &'a str,
    pub trace_id_source: TraceIdSource,
    pub content: &'a str,
}

pub struct ScanEngineResult {
    pub merged: EnforceResult,
    pub trace_id: String,
    pub trace_id_source: TraceIdSource,
}

pub fn scan_once(req: ScanRequest<'_>) -> ScanEngineResult {
    let cfg = sdk_config();
    let manifest = manifest::load();
    let session_id =
        manifest::session_key_value(&cfg.canary_session_key, req.user_id, req.device_id, None);
    let hits = matcher::match_rules(req.content, &manifest.rules);
    let merged = enforce::merge(&hits, session_id);
    audit::maybe_record(
        &cfg,
        req.trace_id,
        req.trace_id_source.as_str(),
        req.scene,
        &merged,
        session_id,
        req.user_id,
        req.device_id,
    );
    ScanEngineResult {
        merged,
        trace_id: req.trace_id.to_string(),
        trace_id_source: req.trace_id_source,
    }
}

fn sdk_config() -> SdkConfig {
    manifest::effective_sdk_config()
}

pub fn primary_rule(merged: &EnforceResult) -> Option<&EdgeRule> {
    merged.primary.as_ref()
}
