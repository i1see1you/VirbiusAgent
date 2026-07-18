use pyo3::exceptions::PyValueError;
use pyo3::prelude::*;
use virbius_core::{license, precheck, License, PrecheckResult, ToolCall};

/// Python module for VirbiusAgent MCP integration.
#[pymodule]
fn virbius_mcp_python(_py: Python<'_>, m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(precheck_tool, m)?)?;
    m.add_function(wrap_pyfunction!(verify_license, m)?)?;
    m.add_function(wrap_pyfunction!(desensitize, m)?)?;
    m.add_function(wrap_pyfunction!(enhance_prompt, m)?)?;
    Ok(())
}

/// Pre-check a tool call against License allowlist and args schema.
#[pyfunction]
fn precheck_tool(
    tool_name: String,
    args_json: String,
    license_jwt: String,
    public_key_pem: String,
    app_id: String,
) -> PyResult<PyObject> {
    let license = License::verify(&license_jwt, &public_key_pem, &app_id)
        .map_err(|e| PyValueError::new_err(format!("License error: {:?}", e)))?;

    let args: serde_json::Value = serde_json::from_str(&args_json)
        .map_err(|e| PyValueError::new_err(format!("Invalid args JSON: {}", e)))?;

    let call = ToolCall {
        tool_name,
        args,
        session_id: String::new(),
    };

    let result = precheck::precheck(&license, &call);

    Python::with_gil(|py| {
        let dict = pyo3::types::PyDict::new(py);
        dict.set_item("allowed", result.allowed)?;
        dict.set_item("reason", result.reason)?;
        dict.set_item("fast_path", result.fast_path)?;
        dict.set_item("sandbox_type", &result.sandbox_type)?;
        Ok(dict.into())
    })
}

/// Verify a JWT License token.
#[pyfunction]
fn verify_license(jwt: String, public_key_pem: String, app_id: String) -> PyResult<PyObject> {
    let license = License::verify(&jwt, &public_key_pem, &app_id)
        .map_err(|e| PyValueError::new_err(format!("License error: {:?}", e)))?;

    Python::with_gil(|py| {
        let dict = pyo3::types::PyDict::new(py);
        dict.set_item("app_id", &license.claims.app_id)?;
        dict.set_item("tenant_id", &license.claims.tenant_id)?;
        dict.set_item("allowed_tools", license.claims.allowed_tools.clone())?;
        dict.set_item("risk_quota", license.claims.risk_quota)?;
        dict.set_item("expiry", license.claims.exp)?;
        Ok(dict.into())
    })
}

/// Desensitize PII in text using Virbius DLP rules.
#[pyfunction]
fn desensitize(text: String, trace_id: String, rules_json: Option<String>) -> PyResult<String> {
    let manifest = virbius_core::manifest::load();
    let result = virbius_core::dlp::desensitize_in(
        &text,
        &trace_id,
        &manifest.dlp_rules,
        std::time::Duration::from_secs(1800),
        Some(&trace_id),
    );
    Ok(result.text)
}

/// Enhance a prompt with constitution and context.
#[pyfunction]
fn enhance_prompt(messages_json: String, context_json: String) -> PyResult<String> {
    let prompt_gateway = virbius_core::prompt_gateway::PromptGateway::new();
    let ctx: serde_json::Value = serde_json::from_str(&context_json)
        .map_err(|e| PyValueError::new_err(format!("Invalid context JSON: {}", e)))?;

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
        scene: ctx
            .get("scene")
            .and_then(|v| v.as_str())
            .unwrap_or("default")
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
        constitution_version: ctx
            .get("constitution_version")
            .and_then(|v| v.as_str())
            .unwrap_or("v1")
            .to_string(),
    };

    let mut messages: Vec<String> = serde_json::from_str(&messages_json)
        .map_err(|e| PyValueError::new_err(format!("Invalid messages JSON: {}", e)))?;

    prompt_gateway
        .enhance(&mut messages, &enhance_ctx)
        .map_err(|e| PyValueError::new_err(e))?;

    serde_json::to_string(&messages)
        .map_err(|e| PyValueError::new_err(format!("Serialization error: {}", e)))
}
