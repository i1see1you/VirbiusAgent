#![allow(
    dead_code,
    clippy::not_unsafe_ptr_arg_deref,
    clippy::too_many_arguments
)]

mod api;
mod audit;
mod bootstrap;
mod dlp;
mod enforce;
mod engine;
mod manifest;
mod matcher;
mod runtime;
mod sync;
mod trace;
mod upload;
mod license;
mod prompt_gateway;
#[cfg(target_os = "linux")]
mod sandbox;

pub use api::{
    DesensitizeInResult, DesensitizeOutResult, DlpHit, EffectiveAction, RuleHit, ScanContext,
    ScanOutcome, TraceIdSource, VirbiusEdge, VirbiusError,
};
pub use sync::EdgeInitConfig;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};
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
#[no_mangle]
pub extern "C" fn virbius_free_string(p: *mut c_char) {
    if p.is_null() {
        return;
    }
    unsafe {
        drop(CString::from_raw(p));
    }
}

#[cfg(test)]
mod c_abi_tests {
    use super::*;
    use std::ffi::CString;

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
}
