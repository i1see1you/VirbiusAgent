use pyo3::exceptions::PyValueError;
use pyo3::prelude::*;
use virbius_core::{
    license, precheck, License, MemoryContext, MemoryInterceptor, MemoryPolicies, PrecheckResult,
    ToolCall,
};

/// Python module for VirbiusAgent MCP integration.
#[pymodule]
fn virbius_mcp_python(_py: Python<'_>, m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(precheck_tool, m)?)?;
    m.add_function(wrap_pyfunction!(verify_license, m)?)?;
    m.add_function(wrap_pyfunction!(desensitize, m)?)?;
    m.add_function(wrap_pyfunction!(enhance_prompt, m)?)?;
    m.add_function(wrap_pyfunction!(intercept_memory_write, m)?)?;
    m.add_function(wrap_pyfunction!(intercept_memory_read, m)?)?;
    m.add_function(wrap_pyfunction!(is_memory_write_tool, m)?)?;
    m.add_function(wrap_pyfunction!(is_memory_read_tool, m)?)?;
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
        let dict = pyo3::types::PyDict::new_bound(py);
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
        let dict = pyo3::types::PyDict::new_bound(py);
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

/// Enhance a prompt with trust directive and PII desensitization.
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
        .map_err(|e| PyValueError::new_err(format!("Invalid messages JSON: {}", e)))?;

    prompt_gateway
        .enhance(&mut messages, &enhance_ctx)
        .map_err(|e| PyValueError::new_err(e))?;

    serde_json::to_string(&messages)
        .map_err(|e| PyValueError::new_err(format!("Serialization error: {}", e)))
}

/// Intercept a memory write operation: size check + credential detection + PII desensitization.
///
/// This is the Python-callable wrapper for `MemoryInterceptor::intercept_write`.
/// Framework integrations (LangChain, OpenAI SDK) call this before saving content
/// to long-term memory.
///
/// Returns a dict with:
/// - `allowed` (bool): whether the write is permitted
/// - `sanitized_content` (str): PII-desensitized content (if allowed)
/// - `block_reason` (str|None): reason for blocking
/// - `pii_found` (bool): whether PII was detected and masked
/// - `credential_detected` (bool): whether a credential pattern was found
/// - `need_llm_check` (bool): whether the caller should invoke Engine for LLM injection detection
#[pyfunction]
fn intercept_memory_write(
    content: String,
    session_id: String,
    trace_id: String,
    tool_name: String,
) -> PyResult<PyObject> {
    let interceptor = MemoryInterceptor::from_manifest();
    let ctx = MemoryContext {
        session_id,
        trace_id,
        tool_name,
    };
    let result = interceptor.intercept_write(&content, &ctx);

    Python::with_gil(|py| {
        let dict = pyo3::types::PyDict::new_bound(py);
        dict.set_item("allowed", result.allowed)?;
        dict.set_item("sanitized_content", &result.sanitized_content)?;
        dict.set_item("block_reason", result.block_reason)?;
        dict.set_item("pii_found", result.pii_found)?;
        dict.set_item("credential_detected", result.credential_detected)?;
        dict.set_item("need_llm_check", result.need_llm_check)?;
        Ok(dict.into())
    })
}

/// Intercept a memory read operation: size check + credential leak detection.
///
/// This is the Python-callable wrapper for `MemoryInterceptor::intercept_read`.
/// Framework integrations (LangChain, OpenAI SDK) call this after loading content
/// from long-term memory, before injecting it into the Agent context.
///
/// Defense against T3 (cross-session) memory poisoning: a payload planted in
/// session A is retrieved in session B.
///
/// Returns a dict with:
/// - `allowed` (bool): whether the read is permitted
/// - `filtered_content` (str): content to return to Agent (if allowed)
/// - `block_reason` (str|None): reason for blocking
/// - `credential_detected` (bool): whether a credential pattern was found
/// - `content_filtered` (bool): whether content was filtered
/// - `need_llm_check` (bool): whether the caller should invoke Engine for LLM injection detection
#[pyfunction]
fn intercept_memory_read(
    content: String,
    session_id: String,
    trace_id: String,
    tool_name: String,
) -> PyResult<PyObject> {
    let interceptor = MemoryInterceptor::from_manifest();
    let ctx = MemoryContext {
        session_id,
        trace_id,
        tool_name,
    };
    let result = interceptor.intercept_read(&content, &ctx);

    Python::with_gil(|py| {
        let dict = pyo3::types::PyDict::new_bound(py);
        dict.set_item("allowed", result.allowed)?;
        dict.set_item("filtered_content", &result.filtered_content)?;
        dict.set_item("block_reason", result.block_reason)?;
        dict.set_item("credential_detected", result.credential_detected)?;
        dict.set_item("content_filtered", result.content_filtered)?;
        dict.set_item("need_llm_check", result.need_llm_check)?;
        Ok(dict.into())
    })
}

/// Check if a tool name is a memory write operation.
///
/// Returns True for tools like `memory_save`, `vector_store`, `embedding_add`, etc.
#[pyfunction]
fn is_memory_write_tool(tool_name: String) -> bool {
    let interceptor = MemoryInterceptor::new(MemoryPolicies::default());
    interceptor.is_memory_write_tool(&tool_name)
}

/// Check if a tool name is a memory read operation.
///
/// Returns True for tools like `memory_search`, `memory_load`, `vector_query`, `recall`, etc.
#[pyfunction]
fn is_memory_read_tool(tool_name: String) -> bool {
    let interceptor = MemoryInterceptor::new(MemoryPolicies::default());
    interceptor.is_memory_read_tool(&tool_name)
}
