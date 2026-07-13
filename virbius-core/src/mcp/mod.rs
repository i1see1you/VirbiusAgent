//! MCP Server integration: transport-agnostic tool execution.
//!
//! Three backends are provided:
//! - [`pyo3`] — PyO3 in-process bindings for Python MCP Servers
//! - [`napi`] — napi-rs in-process bindings for Node.js MCP Servers
//! - [`subprocess`] — subprocess (stdio JSON-RPC) fallback, always available
//!
//! ## Selection logic
//!
//! ```text
//! backend_preference = manifest.tool_policies[tool].mcp_backend
//! match backend_preference:
//!   "pyo3"      → Pyo3Backend::execute (falls back to Subprocess on init failure)
//!   "napi"      → NapiBackend::execute (falls back to Subprocess on init failure)
//!   "subprocess" → SubprocessBackend::execute
//!   _           → SubprocessBackend::execute (default, always works)
//! ```
//!
//! All backends share the same [`ToolResult`] contract and produce audit-compatible
//! JSON. The caller (virbius-core precheck → execute pipeline) does not need to know
//! which backend is active.

pub mod napi;
pub mod pyo3;
pub mod subprocess;

use serde::{Deserialize, Serialize};

/// Maximum time to wait for a single tool call to complete.
const DEFAULT_TIMEOUT_MS: u64 = 30_000;

/// Result of executing an MCP tool call.
///
/// This struct is backend-agnostic — all three backends (PyO3, napi, subprocess)
/// produce the same result shape.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolResult {
    /// Whether the tool executed successfully (no transport/execution errors).
    pub success: bool,
    /// The tool return value as a JSON string, or an error message on failure.
    pub result_json: String,
    /// Execution time in milliseconds.
    pub duration_ms: u64,
    /// Which backend handled this call.
    pub backend: String,
    /// Optional error type on failure (e.g. "timeout", "subprocess_exit", "python_exception").
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error_type: Option<String>,
}

impl ToolResult {
    pub fn ok(result_json: impl Into<String>, duration_ms: u64, backend: &str) -> Self {
        Self {
            success: true,
            result_json: result_json.into(),
            duration_ms,
            backend: backend.to_string(),
            error_type: None,
        }
    }

    pub fn err(
        message: impl Into<String>,
        error_type: impl Into<String>,
        duration_ms: u64,
        backend: &str,
    ) -> Self {
        Self {
            success: false,
            result_json: message.into(),
            duration_ms,
            backend: backend.to_string(),
            error_type: Some(error_type.into()),
        }
    }
}

/// A request to execute a tool via an MCP Server backend.
#[derive(Debug, Clone)]
pub struct McpToolCall {
    pub tool_name: String,
    pub args: serde_json::Value,
    /// Path or identifier for the MCP Server (e.g. "python:my_server.main",
    /// "node:./server.js", "/usr/local/bin/mcp-server").
    pub server_target: String,
    /// Timeout in milliseconds.
    pub timeout_ms: u64,
}

impl McpToolCall {
    pub fn new(tool_name: impl Into<String>, args: serde_json::Value) -> Self {
        Self {
            tool_name: tool_name.into(),
            args,
            server_target: String::new(),
            timeout_ms: DEFAULT_TIMEOUT_MS,
        }
    }

    pub fn with_server(mut self, target: impl Into<String>) -> Self {
        self.server_target = target.into();
        self
    }

    pub fn with_timeout(mut self, ms: u64) -> Self {
        self.timeout_ms = ms;
        self
    }
}

/// Execute a tool call using the best available backend.
///
/// Selection order:
/// 1. If `server_target` starts with `python:` → try PyO3, fallback to subprocess
/// 2. If `server_target` starts with `node:` → try napi, fallback to subprocess
/// 3. Otherwise → use subprocess directly
pub fn execute(call: &McpToolCall) -> ToolResult {
    let start = std::time::Instant::now();

    if call.server_target.starts_with("python:") {
        match pyo3::execute(call) {
            Ok(result) => return result,
            Err(e) => {
                eprintln!("virbius-core: PyO3 backend failed ({e}), falling back to subprocess");
            }
        }
    } else if call.server_target.starts_with("node:") {
        match napi::execute(call) {
            Ok(result) => return result,
            Err(e) => {
                eprintln!("virbius-core: napi backend failed ({e}), falling back to subprocess");
            }
        }
    }

    subprocess::execute(call, start)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_tool_result_ok() {
        let r = ToolResult::ok(r#"{"content":"hello"}"#, 42, "subprocess");
        assert!(r.success);
        assert_eq!(r.duration_ms, 42);
        assert_eq!(r.backend, "subprocess");
        assert!(r.error_type.is_none());
    }

    #[test]
    fn test_tool_result_err() {
        let r = ToolResult::err("timeout", "timeout", 5000, "pyo3");
        assert!(!r.success);
        assert_eq!(r.result_json, "timeout");
        assert_eq!(r.error_type.as_deref(), Some("timeout"));
    }

    #[test]
    fn test_mcp_tool_call_builder() {
        let call = McpToolCall::new("read_file", serde_json::json!({"path": "/tmp"}))
            .with_server("python:my_server.main")
            .with_timeout(10000);
        assert_eq!(call.tool_name, "read_file");
        assert_eq!(call.server_target, "python:my_server.main");
        assert_eq!(call.timeout_ms, 10000);
    }
}
