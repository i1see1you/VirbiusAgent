use napi::bindgen_prelude::*;
use napi_derive::napi;
use virbius_core::{precheck, License, ToolCall};

/// Pre-check a tool call.
#[napi(object)]
pub struct PrecheckOutput {
    pub allowed: bool,
    pub reason: Option<String>,
    pub fast_path: bool,
    pub sandbox_type: String,
}

/// Verify a License JWT.
#[napi(object)]
pub struct LicenseInfo {
    pub app_id: String,
    pub tenant_id: String,
    pub allowed_tools: Vec<String>,
    pub risk_quota: u32,
    pub expiry: i64,
}

#[napi]
pub fn precheck_tool(
    tool_name: String,
    args_json: String,
    license_jwt: String,
    public_key_pem: String,
    app_id: String,
) -> Result<PrecheckOutput> {
    let lic = License::verify(&license_jwt, &public_key_pem, &app_id)
        .map_err(|e| Error::from_reason(format!("License error: {:?}", e)))?;

    let args: serde_json::Value = serde_json::from_str(&args_json)
        .map_err(|e| Error::from_reason(format!("Invalid args JSON: {}", e)))?;

    let call = ToolCall {
        tool_name,
        args,
        session_id: String::new(),
    };
    let result = precheck::precheck(&lic, &call);

    Ok(PrecheckOutput {
        allowed: result.allowed,
        reason: result.reason,
        fast_path: result.fast_path,
        sandbox_type: result.sandbox_type,
    })
}

#[napi]
pub fn verify_license(jwt: String, public_key_pem: String, app_id: String) -> Result<LicenseInfo> {
    let lic = License::verify(&jwt, &public_key_pem, &app_id)
        .map_err(|e| Error::from_reason(format!("License error: {:?}", e)))?;

    Ok(LicenseInfo {
        app_id: lic.claims.app_id,
        tenant_id: lic.claims.tenant_id,
        allowed_tools: lic.claims.allowed_tools,
        risk_quota: lic.claims.risk_quota,
        expiry: lic.claims.exp,
    })
}

/// Configure the edge SDK from an EdgeInitConfig JSON string.
///
/// Mirrors the Python binding's `configure_rules`: when `offline_manifest_path`
/// is set the manifest is loaded from that file; otherwise rules are pulled
/// from `control_base_url` and cached under `cache_dir`.
#[napi]
pub fn configure_rules(cfg_json: String) -> Result<String> {
    let cfg: virbius_core::EdgeInitConfig = serde_json::from_str(&cfg_json)
        .map_err(|e| Error::from_reason(format!("Invalid EdgeInitConfig JSON: {}", e)))?;
    virbius_core::VirbiusEdge::init(cfg)
        .map_err(|e| Error::from_reason(format!("init failed: {:?}", e)))?;
    Ok(r#"{"ok":true}"#.into())
}

#[napi]
pub fn desensitize(text: String, trace_id: String) -> String {
    let manifest = virbius_core::manifest::load();
    let ttl = std::time::Duration::from_millis(manifest.sdk_config.dlp_vault_ttl_ms);
    let result = virbius_core::dlp::desensitize_in(
        &text,
        &trace_id,
        &manifest.dlp_rules,
        ttl,
        Some(&trace_id),
    );
    result.text
}

/// Restore DLP tokens in model output back to plaintext.
///
/// `session_id` must match the session used during `desensitize` for the
/// tokens to be restored (tokens stored without a session are always
/// restorable).
#[napi(object)]
pub struct RestoreOutput {
    pub text: String,
    pub unresolved_tokens: Vec<String>,
}

#[napi]
pub fn restore(trace_id: String, session_id: String, text: String) -> RestoreOutput {
    let session = if session_id.is_empty() {
        None
    } else {
        Some(session_id.as_str())
    };
    let result = virbius_core::dlp::desensitize_out(&text, &trace_id, session);
    RestoreOutput {
        text: result.text,
        unresolved_tokens: result.unresolved_tokens,
    }
}

/// Enhance a prompt with trust boundary directive and PII desensitization.
///
/// `messages_json` is a JSON array of message strings (e.g. `["{...system...}", "{...user...}"]`).
/// `context_json` contains enhancement context: `{ app_id, session_id, scene, risk_score, license_tools }`.
/// Returns the enhanced messages as a JSON array string.
#[napi]
pub fn enhance_prompt(messages_json: String, context_json: String) -> Result<String> {
    let prompt_gateway = virbius_core::prompt_gateway::PromptGateway::new();
    let ctx: serde_json::Value = serde_json::from_str(&context_json)
        .map_err(|e| Error::from_reason(format!("Invalid context JSON: {}", e)))?;

    let enhance_ctx = virbius_core::prompt_gateway::EnhanceContext {
        app_id: ctx
            .get("app_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        session_id: ctx
            .get("session_id")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
        risk_score: ctx.get("risk_score").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
        recent_tools: vec![],
        license_tools: ctx
            .get("license_tools")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|v| v.as_str().map(String::from))
                    .collect()
            })
            .unwrap_or_default(),
    };

    let mut messages: Vec<String> = serde_json::from_str(&messages_json)
        .map_err(|e| Error::from_reason(format!("Invalid messages JSON: {}", e)))?;

    prompt_gateway
        .enhance(&mut messages, &enhance_ctx)
        .map_err(|e| Error::from_reason(e))?;

    serde_json::to_string(&messages)
        .map_err(|e| Error::from_reason(format!("Serialization error: {}", e)))
}
