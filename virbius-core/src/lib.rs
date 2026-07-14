#![allow(
    clippy::not_unsafe_ptr_arg_deref,
    clippy::too_many_arguments
)]

mod api;
mod audit;
pub mod bootstrap;
mod dlp;
mod enforce;
mod engine;
mod manifest;
mod matcher;
mod runtime;
mod sync;
pub mod trace;
mod upload;
pub mod license;
pub mod mcp;
pub mod precheck;
pub mod prompt_gateway;
#[cfg(target_os = "linux")]
mod sandbox;

pub use api::{
    DesensitizeInResult, DesensitizeOutResult, DlpHit, EffectiveAction, OutputMaskResult, RuleHit,
    ScanContext, ScanOutcome, TraceIdSource, VirbiusEdge, VirbiusError,
    mask_pii_output,
};
pub use license::{License, LicenseClaims, LicenseError};
pub use manifest::ToolPolicy;
pub use precheck::{precheck, PrecheckResult, ToolCall};
pub use mcp::{execute as mcp_execute, McpToolCall, ToolResult as McpToolResult};
pub use sync::EdgeInitConfig;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int, c_uint};
use std::path::PathBuf;
use std::ptr;

use engine::ScanRequest;
use manifest::EdgeRule;

#[repr(C)]
pub struct VirbiusScanCtx {
    pub user_id: *const c_char,
    pub device_id: *const c_char,
    pub scene: *const c_char,
    pub trace_id: *const c_char,
}

#[repr(C)]
pub enum VirbiusAction {
    Allow = 0,
    Block = 1,
}

#[repr(C)]
pub struct VirbiusScanResult {
    pub action: VirbiusAction,
    pub rule_id: *const c_char,
    pub rule_revision: c_int,
    pub reason_code: *const c_char,
    pub layer: *const c_char,
    pub trace_id: *const c_char,
}

fn cstr_opt(p: *const c_char) -> Option<String> {
    if p.is_null() {
        return None;
    }
    Some(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
}

fn into_c_string(s: &str) -> *const c_char {
    CString::new(s).expect("nul in c string").into_raw()
}

fn write_scan_result(out: *mut VirbiusScanResult, trace_id: &str, block_rule: Option<&EdgeRule>) {
    unsafe {
        (*out).trace_id = into_c_string(trace_id);
        if let Some(rule) = block_rule {
            (*out).action = VirbiusAction::Block;
            (*out).rule_id = into_c_string(&rule.rule_id);
            (*out).rule_revision = rule.rule_revision;
            (*out).reason_code = into_c_string(&rule.reason_code);
            (*out).layer = into_c_string("edge");
        } else {
            (*out).action = VirbiusAction::Allow;
            (*out).rule_id = ptr::null();
            (*out).rule_revision = 0;
            (*out).reason_code = ptr::null();
            (*out).layer = ptr::null();
        }
    }
}

#[no_mangle]
pub extern "C" fn virbius_init(manifest_url: *const c_char) -> c_int {
    match init_from_legacy_url(manifest_url) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("virbius-core: virbius_init failed: {e}");
            -1
        }
    }
}

/// Production C ABI: JSON matching [`EdgeInitConfig`] (see `virbius.h`).
#[no_mangle]
pub extern "C" fn virbius_init_config_json(json: *const c_char) -> c_int {
    if json.is_null() {
        return -1;
    }
    let raw = unsafe { CStr::from_ptr(json) }.to_string_lossy();
    match serde_json::from_str::<EdgeInitConfig>(&raw) {
        Ok(cfg) => match bootstrap::bootstrap(&cfg) {
            Ok(()) => 0,
            Err(e) => {
                eprintln!("virbius-core: virbius_init_config_json failed: {e}");
                -1
            }
        },
        Err(e) => {
            eprintln!("virbius-core: invalid init JSON: {e}");
            -1
        }
    }
}

fn init_from_legacy_url(manifest_url: *const c_char) -> Result<(), VirbiusError> {
    if manifest_url.is_null() {
        if EdgeInitConfig::is_installed() {
            bootstrap::bootstrap(&EdgeInitConfig::resolve())?;
            return Ok(());
        }
        return Err(VirbiusError::InvalidInitConfig(
            "call virbius_init_config_json or pass control URL / offline manifest path".into(),
        ));
    }
    let s = unsafe { CStr::from_ptr(manifest_url) }.to_string_lossy();
    if s.is_empty() {
        return init_from_legacy_url(ptr::null());
    }
    let mut cfg = EdgeInitConfig::default();
    if s.starts_with("http://") || s.starts_with("https://") {
        cfg.control_base_url = Some(s.into_owned());
    } else {
        cfg.offline_manifest_path = Some(PathBuf::from(s.as_ref()));
    }
    bootstrap::bootstrap(&cfg)
}

#[no_mangle]
pub extern "C" fn virbius_scan(
    ctx: *const VirbiusScanCtx,
    text: *const c_char,
    out: *mut VirbiusScanResult,
) -> c_int {
    if out.is_null() || text.is_null() {
        return -1;
    }
    let content = unsafe { CStr::from_ptr(text) }.to_string_lossy();
    if content.is_empty() {
        return -1;
    }
    let (scene, trace_id_raw, user_id, device_id) = if ctx.is_null() {
        ("default".to_string(), String::new(), None, None)
    } else {
        let c = unsafe { &*ctx };
        (
            cstr_opt(c.scene).unwrap_or_else(|| "default".into()),
            cstr_opt(c.trace_id).unwrap_or_default(),
            cstr_opt(c.user_id),
            cstr_opt(c.device_id),
        )
    };
    let (trace_id, trace_id_source) = if trace_id_raw.is_empty() {
        (
            trace::generate_trace_id(),
            trace::TraceIdSource::SdkGenerated,
        )
    } else if trace::valid_trace_id(&trace_id_raw) {
        (trace_id_raw, trace::TraceIdSource::Client)
    } else {
        return -1;
    };
    let result = engine::scan_once(ScanRequest {
        user_id: user_id.as_deref(),
        device_id: device_id.as_deref(),
        scene: &scene,
        trace_id: &trace_id,
        trace_id_source,
        content: content.as_ref(),
    });
    let block_rule = if crate::enforce::is_enforced_block(&result.merged.effective_action) {
        engine::primary_rule(&result.merged)
    } else {
        None
    };
    write_scan_result(out, &trace_id, block_rule);
    0
}

#[no_mangle]
pub extern "C" fn virbius_reload() -> c_int {
    bootstrap::reload_synced();
    0
}

/// Frees strings returned in [`VirbiusScanResult`] (`trace_id`, and on block: `rule_id`, `reason_code`, `layer`).
/// Also frees strings returned by [`virbius_enhance_prompt`] and fields of [`VirbiusPrecheckResult`] / [`VirbiusLicenseInfo`].
#[no_mangle]
pub extern "C" fn virbius_free_string(p: *mut c_char) {
    if p.is_null() {
        return;
    }
    unsafe {
        drop(CString::from_raw(p));
    }
}

// =========================================================================
// C ABI: Tool pre-check (License + allowlist + JSON Schema)
// =========================================================================

/// C ABI result for `virbius_precheck`.
///
/// `reason` and `sandbox_type` are heap-allocated C strings.
/// The caller MUST free them with `virbius_free_string` when non-NULL.
/// `reason` is NULL when `allowed == 1`.
#[repr(C)]
pub struct VirbiusPrecheckResult {
    /// 1 if the tool call is allowed, 0 if denied.
    pub allowed: c_int,
    /// NULL when allowed; otherwise a human-readable denial reason.
    pub reason: *const c_char,
    /// 1 if the call qualifies for the fast path (skip engine).
    pub fast_path: c_int,
    /// Sandbox type string ("none", "landlock", etc.). Never NULL.
    pub sandbox_type: *const c_char,
}

/// Pre-check a tool call: License verification + allowlist + JSON Schema validation.
///
/// Returns 0 on success (result written to `out`), -1 on error (invalid input or
/// License verification failure). On success, check `out.allowed`.
///
/// # Safety
/// `tool_name`, `args_json`, `license_jwt`, `public_key_pem`, `app_id` must be
/// valid nul-terminated C strings (or NULL for optional fields). `out` must be a
/// valid pointer to [`VirbiusPrecheckResult`].
#[no_mangle]
pub extern "C" fn virbius_precheck(
    tool_name: *const c_char,
    args_json: *const c_char,
    license_jwt: *const c_char,
    public_key_pem: *const c_char,
    app_id: *const c_char,
    out: *mut VirbiusPrecheckResult,
) -> c_int {
    if out.is_null() {
        return -1;
    }
    let tool_name = match cstr_opt(tool_name) {
        Some(s) => s,
        None => return -1,
    };
    let args_json = match cstr_opt(args_json) {
        Some(s) => s,
        None => return -1,
    };
    let license_jwt = match cstr_opt(license_jwt) {
        Some(s) => s,
        None => return -1,
    };
    let public_key_pem = match cstr_opt(public_key_pem) {
        Some(s) => s,
        None => return -1,
    };
    let app_id = match cstr_opt(app_id) {
        Some(s) => s,
        None => return -1,
    };

    let lic = match License::verify(&license_jwt, &public_key_pem, &app_id) {
        Ok(l) => l,
        Err(_) => return -1,
    };

    let args: serde_json::Value = match serde_json::from_str(&args_json) {
        Ok(v) => v,
        Err(_) => return -1,
    };

    let call = ToolCall {
        tool_name,
        args,
        session_id: String::new(),
    };
    let result = precheck::precheck(&lic, &call);

    unsafe {
        (*out).allowed = if result.allowed { 1 } else { 0 };
        (*out).reason = match result.reason {
            Some(r) => into_c_string(&r),
            None => ptr::null(),
        };
        (*out).fast_path = if result.fast_path { 1 } else { 0 };
        (*out).sandbox_type = into_c_string(&result.sandbox_type);
    }
    0
}

// =========================================================================
// C ABI: License verification
// =========================================================================

/// C ABI result for `virbius_verify_license`.
///
/// `app_id`, `tenant_id`, `allowed_tools_json` are heap-allocated C strings.
/// The caller MUST free them with `virbius_free_string` when non-NULL.
/// `allowed_tools_json` is a JSON array string (e.g. `["read_file","search"]`).
#[repr(C)]
pub struct VirbiusLicenseInfo {
    pub app_id: *const c_char,
    pub tenant_id: *const c_char,
    /// JSON array of allowed tool names, e.g. `["read_file","search"]`.
    pub allowed_tools_json: *const c_char,
    pub risk_quota: c_uint,
    pub expiry: i64,
}

/// Verify a License JWT and extract claims.
///
/// Returns 0 on success (claims written to `out`), -1 on error (invalid input
/// or License verification failure).
///
/// # Safety
/// `jwt`, `public_key_pem`, `app_id` must be valid nul-terminated C strings.
/// `out` must be a valid pointer to [`VirbiusLicenseInfo`].
#[no_mangle]
pub extern "C" fn virbius_verify_license(
    jwt: *const c_char,
    public_key_pem: *const c_char,
    app_id: *const c_char,
    out: *mut VirbiusLicenseInfo,
) -> c_int {
    if out.is_null() {
        return -1;
    }
    let jwt = match cstr_opt(jwt) {
        Some(s) => s,
        None => return -1,
    };
    let public_key_pem = match cstr_opt(public_key_pem) {
        Some(s) => s,
        None => return -1,
    };
    let app_id = match cstr_opt(app_id) {
        Some(s) => s,
        None => return -1,
    };

    let lic = match License::verify(&jwt, &public_key_pem, &app_id) {
        Ok(l) => l,
        Err(_) => return -1,
    };

    let tools_json = serde_json::to_string(&lic.claims.allowed_tools).unwrap_or_else(|_| "[]".into());

    unsafe {
        (*out).app_id = into_c_string(&lic.claims.app_id);
        (*out).tenant_id = into_c_string(&lic.claims.tenant_id);
        (*out).allowed_tools_json = into_c_string(&tools_json);
        (*out).risk_quota = lic.claims.risk_quota as c_uint;
        (*out).expiry = lic.claims.exp;
    }
    0
}

// =========================================================================
// C ABI: Prompt enhancement (constitution injection + PII desensitization)
// =========================================================================

/// Enhance a prompt with constitution injection and PII desensitization.
///
/// `messages_json` is a JSON array of message strings.
/// `context_json` contains enhancement context:
/// `{ "app_id": "...", "session_id": "...", "scene": "...", "risk_score": 0, "license_tools": [...], "constitution_version": "v1" }`
///
/// Returns a heap-allocated C string containing the enhanced messages JSON array,
/// or NULL on error. The caller MUST free the returned string with `virbius_free_string`.
///
/// # Safety
/// `messages_json` and `context_json` must be valid nul-terminated C strings.
#[no_mangle]
pub extern "C" fn virbius_enhance_prompt(
    messages_json: *const c_char,
    context_json: *const c_char,
) -> *const c_char {
    let messages_raw = match cstr_opt(messages_json) {
        Some(s) => s,
        None => return ptr::null(),
    };
    let context_raw = match cstr_opt(context_json) {
        Some(s) => s,
        None => return ptr::null(),
    };

    let ctx: serde_json::Value = match serde_json::from_str(&context_raw) {
        Ok(v) => v,
        Err(_) => return ptr::null(),
    };

    let enhance_ctx = prompt_gateway::EnhanceContext {
        app_id: ctx.get("app_id").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        session_id: ctx.get("session_id").and_then(|v| v.as_str()).unwrap_or("").to_string(),
        scene: ctx.get("scene").and_then(|v| v.as_str()).unwrap_or("default").to_string(),
        risk_score: ctx.get("risk_score").and_then(|v| v.as_u64()).unwrap_or(0) as u32,
        recent_tools: vec![],
        license_tools: ctx.get("license_tools").and_then(|v| v.as_array())
            .map(|arr| arr.iter().filter_map(|v| v.as_str().map(String::from)).collect())
            .unwrap_or_default(),
        constitution_version: ctx.get("constitution_version").and_then(|v| v.as_str()).unwrap_or("v1").to_string(),
    };

    let mut messages: Vec<String> = match serde_json::from_str(&messages_raw) {
        Ok(v) => v,
        Err(_) => return ptr::null(),
    };

    let gateway = prompt_gateway::PromptGateway::new();
    if gateway.enhance(&mut messages, &enhance_ctx).is_err() {
        return ptr::null();
    }

    let result_json = match serde_json::to_string(&messages) {
        Ok(s) => s,
        Err(_) => return ptr::null(),
    };

    into_c_string(&result_json)
}

#[cfg(test)]
mod c_abi_tests {
    use super::*;
    use crate::license::LicenseClaims;
    use base64::Engine;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    use ed25519_dalek::Signer;
    use ed25519_dalek::SigningKey;
    use rand::rngs::OsRng;
    use std::ffi::CString;

    /// Generate a signing key pair and a valid License JWT for testing.
    fn make_test_license() -> (SigningKey, String, String, LicenseClaims) {
        let mut csprng = OsRng;
        let signing_key = SigningKey::generate(&mut csprng);
        let verifying_key = signing_key.verifying_key();
        let pub_pem = verifying_key
            .to_public_key_pem(Default::default())
            .unwrap();

        let claims = LicenseClaims {
            app_id: "test-agent".into(),
            tenant_id: "tenant-1".into(),
            allowed_tools: vec!["read_file".into(), "search".into()],
            allowed_scenes: vec![],
            risk_quota: 60,
            tool_rate_limit: 50,
            exp: 9999999999,
            iat: 1700000000,
        };

        let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
        let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&claims).unwrap());
        let message = format!("{}.{}", header, payload);
        let sig = signing_key.sign(message.as_bytes());
        let sig_b64 =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes());
        let jwt = format!("{}.{}.{}", header, payload, sig_b64);

        (signing_key, pub_pem, jwt, claims)
    }

    #[test]
    fn scan_returns_generated_trace_id() {
        let text = CString::new("hello").unwrap();
        let mut out = VirbiusScanResult {
            action: VirbiusAction::Allow,
            rule_id: ptr::null(),
            rule_revision: 0,
            reason_code: ptr::null(),
            layer: ptr::null(),
            trace_id: ptr::null(),
        };
        assert_eq!(virbius_scan(ptr::null(), text.as_ptr(), &mut out), 0);
        unsafe {
            assert!(!out.trace_id.is_null());
            let tid = CStr::from_ptr(out.trace_id).to_string_lossy().into_owned();
            assert!(trace::valid_trace_id(&tid));
            virbius_free_string(out.trace_id as *mut c_char);
        }
    }

    #[test]
    fn c_abi_verify_license_valid() {
        let (_sk, pub_pem, jwt, claims) = make_test_license();
        let c_jwt = CString::new(jwt).unwrap();
        let c_pub = CString::new(pub_pem).unwrap();
        let c_app = CString::new("test-agent").unwrap();

        let mut out = VirbiusLicenseInfo {
            app_id: ptr::null(),
            tenant_id: ptr::null(),
            allowed_tools_json: ptr::null(),
            risk_quota: 0,
            expiry: 0,
        };
        assert_eq!(
            virbius_verify_license(c_jwt.as_ptr(), c_pub.as_ptr(), c_app.as_ptr(), &mut out),
            0
        );
        unsafe {
            let app_id = CStr::from_ptr(out.app_id).to_string_lossy().into_owned();
            let tenant = CStr::from_ptr(out.tenant_id).to_string_lossy().into_owned();
            let tools = CStr::from_ptr(out.allowed_tools_json).to_string_lossy().into_owned();
            assert_eq!(app_id, "test-agent");
            assert_eq!(tenant, "tenant-1");
            assert!(tools.contains("read_file"));
            assert_eq!(out.risk_quota, 60);
            assert_eq!(out.expiry, claims.exp);

            virbius_free_string(out.app_id as *mut c_char);
            virbius_free_string(out.tenant_id as *mut c_char);
            virbius_free_string(out.allowed_tools_json as *mut c_char);
        }
    }

    #[test]
    fn c_abi_verify_license_invalid() {
        let c_jwt = CString::new("invalid.jwt.token").unwrap();
        let c_pub = CString::new("not-a-key").unwrap();
        let c_app = CString::new("test-agent").unwrap();

        let mut out = VirbiusLicenseInfo {
            app_id: ptr::null(),
            tenant_id: ptr::null(),
            allowed_tools_json: ptr::null(),
            risk_quota: 0,
            expiry: 0,
        };
        assert_eq!(
            virbius_verify_license(c_jwt.as_ptr(), c_pub.as_ptr(), c_app.as_ptr(), &mut out),
            -1
        );
    }

    #[test]
    fn c_abi_precheck_allowed_tool() {
        let (_sk, pub_pem, jwt, _claims) = make_test_license();
        let c_tool = CString::new("read_file").unwrap();
        let c_args = CString::new(r#"{"path":"/tmp/test"}"#).unwrap();
        let c_jwt = CString::new(jwt).unwrap();
        let c_pub = CString::new(pub_pem).unwrap();
        let c_app = CString::new("test-agent").unwrap();

        let mut out = VirbiusPrecheckResult {
            allowed: 0,
            reason: ptr::null(),
            fast_path: 0,
            sandbox_type: ptr::null(),
        };
        assert_eq!(
            virbius_precheck(
                c_tool.as_ptr(),
                c_args.as_ptr(),
                c_jwt.as_ptr(),
                c_pub.as_ptr(),
                c_app.as_ptr(),
                &mut out,
            ),
            0
        );
        assert_eq!(out.allowed, 1);
        assert!(out.reason.is_null());
        assert!(!out.sandbox_type.is_null());
        virbius_free_string(out.sandbox_type as *mut c_char);
    }

    #[test]
    fn c_abi_precheck_denied_tool() {
        let (_sk, pub_pem, jwt, _claims) = make_test_license();
        let c_tool = CString::new("curl").unwrap();
        let c_args = CString::new(r#"{"url":"https://evil.com"}"#).unwrap();
        let c_jwt = CString::new(jwt).unwrap();
        let c_pub = CString::new(pub_pem).unwrap();
        let c_app = CString::new("test-agent").unwrap();

        let mut out = VirbiusPrecheckResult {
            allowed: 0,
            reason: ptr::null(),
            fast_path: 0,
            sandbox_type: ptr::null(),
        };
        assert_eq!(
            virbius_precheck(
                c_tool.as_ptr(),
                c_args.as_ptr(),
                c_jwt.as_ptr(),
                c_pub.as_ptr(),
                c_app.as_ptr(),
                &mut out,
            ),
            0
        );
        assert_eq!(out.allowed, 0);
        unsafe {
            assert!(!out.reason.is_null());
            let reason = CStr::from_ptr(out.reason).to_string_lossy().into_owned();
            assert!(reason.contains("not in license"));
            assert!(!out.sandbox_type.is_null());
            virbius_free_string(out.reason as *mut c_char);
            virbius_free_string(out.sandbox_type as *mut c_char);
        }
    }

    #[test]
    fn c_abi_precheck_null_inputs() {
        let mut out = VirbiusPrecheckResult {
            allowed: 0,
            reason: ptr::null(),
            fast_path: 0,
            sandbox_type: ptr::null(),
        };
        assert_eq!(virbius_precheck(ptr::null(), ptr::null(), ptr::null(), ptr::null(), ptr::null(), &mut out), -1);
    }

    #[test]
    fn c_abi_enhance_prompt_returns_json() {
        let messages = r#"["{\"role\":\"user\",\"content\":\"hello\"}"]"#;
        let context = r#"{"app_id":"test","session_id":"s1","scene":"chat","risk_score":0}"#;
        let c_messages = CString::new(messages).unwrap();
        let c_context = CString::new(context).unwrap();

        let result_ptr = virbius_enhance_prompt(c_messages.as_ptr(), c_context.as_ptr());
        assert!(!result_ptr.is_null(), "enhance_prompt should return non-NULL on valid input");
        unsafe {
            let result_json = CStr::from_ptr(result_ptr).to_string_lossy().into_owned();
            let parsed: Vec<String> = serde_json::from_str(&result_json).unwrap();
            assert!(!parsed.is_empty(), "enhanced messages should not be empty");
            virbius_free_string(result_ptr as *mut c_char);
        }
    }

    #[test]
    fn c_abi_enhance_prompt_null_inputs() {
        assert!(virbius_enhance_prompt(ptr::null(), ptr::null()).is_null());
    }

    #[test]
    fn c_abi_enhance_prompt_invalid_json() {
        let c_bad = CString::new("not json").unwrap();
        let c_ctx = CString::new("{}").unwrap();
        assert!(virbius_enhance_prompt(c_bad.as_ptr(), c_ctx.as_ptr()).is_null());
    }
}
