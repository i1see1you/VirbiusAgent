use napi_derive::napi;
use napi::bindgen_prelude::*;
use virbius_core::{precheck, license, License, PrecheckResult, ToolCall};

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

    let call = ToolCall { tool_name, args, session_id: String::new() };
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

#[napi]
pub fn desensitize(text: String, trace_id: String) -> String {
    let manifest = virbius_core::manifest::load();
    let result = virbius_core::dlp::desensitize_in(
        &text,
        &trace_id,
        &manifest.dlp_rules,
        std::time::Duration::from_secs(1800),
        Some(&trace_id),
    );
    result.text
}
