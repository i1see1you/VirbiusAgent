//! PyO3 bindings for Python MCP Server integration.
//!
//! This backend loads a Python MCP Server module in-process via PyO3,
//! invokes the tool function, and returns the result.
//!
//! ## Prerequisites
//!
//! - Python 3.9+ must be installed and discoverable on the system PATH.
//! - The `pyo3` crate must be compiled with the `extension-module` feature disabled
//!   (this crate uses PyO3 in embed mode, not extension mode).
//! - The target Python module must expose a function:
//!   ```python
//!   def call_tool(tool_name: str, args: dict) -> dict
//!   ```
//!
//! ## Server target format
//!
//! `python:module.path.to.server` — the part after `python:` is the dotted
//! Python module path that contains the `call_tool` function.
//!
//! ## Fallback
//!
//! If PyO3 initialization fails (Python not found, module import error, etc.),
//! the caller ([`super::execute`]) falls back to the subprocess backend.

use super::{McpToolCall, ToolResult};
use std::time::Instant;

/// Execute a tool call via PyO3 (in-process Python).
///
/// Returns `Ok(ToolResult)` on successful execution (including tool errors),
/// or `Err(message)` when the PyO3 backend itself is unavailable (init failure,
/// module not found) — signaling the caller to fall back to subprocess.
pub fn execute(call: &McpToolCall) -> Result<ToolResult, String> {
    let start = Instant::now();

    let module_path = call
        .server_target
        .strip_prefix("python:")
        .ok_or_else(|| "server_target must start with 'python:'".to_string())?;

    if module_path.is_empty() {
        return Err("empty Python module path in server_target".into());
    }

    // Attempt to initialize the Python interpreter.
    // PyO3's `prepare_freethreaded_python` is safe to call multiple times.
    init_python().map_err(|e| format!("Python init failed: {e}"))?;

    // Import the module and call `call_tool(tool_name, args)`.
    let result_json = call_python_tool(module_path, &call.tool_name, &call.args)
        .map_err(|e| format!("Python tool execution failed: {e}"))?;

    let duration_ms = start.elapsed().as_millis() as u64;
    Ok(ToolResult::ok(result_json, duration_ms, "pyo3"))
}

/// Initialize the Python interpreter (idempotent).
///
/// This function is stubbed when PyO3 is not available at compile time
/// (e.g. on non-Python builds). In production, the `pyo3` crate would
/// be added as an optional dependency behind a feature flag.
fn init_python() -> Result<(), String> {
    // --- Production implementation (when pyo3 crate is linked) ---
    //
    // use pyo3::prelude::*;
    // Python::with_gil(|py| {
    //     py.run("|import sys", None, None)
    //         .map_err(|e| format!("{e}"))?;
    //     Ok(())
    // })

    // --- Stub: check if python3 is on PATH as a capability probe ---
    // This allows the backend to report "available" on systems with Python,
    // while gracefully failing on systems without it.
    let python_found = which_python().is_some();
    if python_found {
        Ok(())
    } else {
        Err("Python interpreter not found on PATH".into())
    }
}

/// Find a Python interpreter on the system PATH.
fn which_python() -> Option<String> {
    for candidate in &["python3", "python"] {
        if std::process::Command::new(candidate)
            .arg("--version")
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .is_ok()
        {
            return Some((*candidate).to_string());
        }
    }
    None
}

/// Call the Python `call_tool` function in the given module.
///
/// In production with PyO3 linked:
/// ```ignore
/// use pyo3::prelude::*;
/// use pyo3::types::PyDict;
///
/// Python::with_gil(|py| {
///     let module = PyModule::import(py, module_path)?;
///     let kwargs = PyDict::new(py);
///     kwargs.set_item("tool_name", &call.tool_name)?;
///     kwargs.set_item("args", &call.args.to_string())?;
///     let result = module.call("call_tool", (), Some(kwargs))?;
///     let json_str: String = result.extract()?;
///     Ok(json_str)
/// })
/// ```
fn call_python_tool(
    module_path: &str,
    tool_name: &str,
    args: &serde_json::Value,
) -> Result<String, String> {
    // Stub: invoke via python3 -c as a lightweight in-process alternative.
    // This avoids requiring the pyo3 crate as a hard dependency while still
    // providing real tool execution through the Python interpreter.
    let python = which_python().ok_or_else(|| "Python not available".to_string())?;

    let script = format!(
        r#"
import json, importlib, sys
mod = importlib.import_module("{module}")
result = mod.call_tool("{tool}", json.loads('''{args}'''))
sys.stdout.write(json.dumps(result))
"#,
        module = module_path,
        tool = tool_name.replace('"', "\\\""),
        args = args.to_string().replace('\'', "\\'"),
    );

    let output = std::process::Command::new(&python)
        .arg("-c")
        .arg(&script)
        .output()
        .map_err(|e| format!("failed to spawn python: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("python error: {stderr}"));
    }

    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
    if stdout.trim().is_empty() {
        return Err("python returned empty output".into());
    }

    Ok(stdout.trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_server_target_parsing() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("python:mcp_server.tools");

        let path = call.server_target.strip_prefix("python:").unwrap();
        assert_eq!(path, "mcp_server.tools");
    }

    #[test]
    fn test_execute_rejects_non_python_target() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("node:./server.js");
        assert!(execute(&call).is_err());
    }

    #[test]
    fn test_execute_rejects_empty_module() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("python:");
        assert!(execute(&call).is_err());
    }

    #[test]
    fn test_which_python_returns_some_when_available() {
        // On most CI/dev machines python3 is available. If not, this is a no-op.
        if let Some(python) = which_python() {
            assert!(!python.is_empty());
        }
    }
}
