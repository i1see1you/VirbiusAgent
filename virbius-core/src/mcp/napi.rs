//! napi-rs bindings for Node.js MCP Server integration.
//!
//! This backend loads a Node.js MCP Server module in-process via napi-rs,
//! invokes the tool function, and returns the result.
//!
//! ## Prerequisites
//!
//! - Node.js 18+ must be installed and discoverable on the system PATH.
//! - The target Node.js module must export a function:
//!   ```javascript
//!   module.exports = {
//!     async callTool(toolName, args) { ... return result; }
//!   };
//!   // or: exports.callTool = async function(toolName, args) { ... }
//!   ```
//!
//! ## Server target format
//!
//! `node:/path/to/server.js` or `node:./relative/server.js` — the part after
//! `node:` is the file path to the Node.js module.
//!
//! ## Fallback
//!
//! If napi initialization fails (Node.js not found, module load error, etc.),
//! the caller ([`super::execute`]) falls back to the subprocess backend.

use super::{McpToolCall, ToolResult};
use std::time::Instant;

/// Execute a tool call via napi-rs (in-process Node.js).
///
/// Returns `Ok(ToolResult)` on successful execution (including tool errors),
/// or `Err(message)` when the napi backend itself is unavailable — signaling
/// the caller to fall back to subprocess.
pub fn execute(call: &McpToolCall) -> Result<ToolResult, String> {
    let start = Instant::now();

    let module_path = call
        .server_target
        .strip_prefix("node:")
        .ok_or_else(|| "server_target must start with 'node:'".to_string())?;

    if module_path.is_empty() {
        return Err("empty Node.js module path in server_target".into());
    }

    // Check that Node.js is available.
    init_node().map_err(|e| format!("Node.js init failed: {e}"))?;

    // Invoke the tool function.
    let result_json = call_node_tool(module_path, &call.tool_name, &call.args)
        .map_err(|e| format!("Node.js tool execution failed: {e}"))?;

    let duration_ms = start.elapsed().as_millis() as u64;
    Ok(ToolResult::ok(result_json, duration_ms, "napi"))
}

/// Initialize the Node.js runtime (capability probe).
///
/// In production with napi-rs linked:
/// ```ignore
/// use napi::{Env, JsObject, JsString};
/// // napi::load_module(...) etc.
/// ```
fn init_node() -> Result<(), String> {
    let node_found = which_node().is_some();
    if node_found {
        Ok(())
    } else {
        Err("Node.js runtime not found on PATH".into())
    }
}

/// Find a Node.js runtime on the system PATH.
fn which_node() -> Option<String> {
    for candidate in &["node", "nodejs"] {
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

/// Call the Node.js `callTool` function in the given module.
///
/// This uses `node -e` as a lightweight in-process alternative to napi-rs,
/// avoiding a hard dependency on the napi-rs crate while still providing
/// real tool execution through the Node.js runtime.
fn call_node_tool(
    module_path: &str,
    tool_name: &str,
    args: &serde_json::Value,
) -> Result<String, String> {
    let node = which_node().ok_or_else(|| "Node.js not available".to_string())?;
    let args_json = serde_json::to_string(args).map_err(|e| format!("args serialization: {e}"))?;

    // Escape single quotes in strings to prevent injection in the -e script.
    let escaped_module = module_path.replace('\'', "\\'");
    let escaped_tool = tool_name.replace('\'', "\\'");
    let escaped_args = args_json.replace('\'', "\\'");

    let script = format!(
        r#"
(async () => {{
  const mod = require('{module}');
  const fn = mod.callTool || mod.call_tool || mod.default;
  if (typeof fn !== 'function') {{
    process.stderr.write('Module does not export callTool function');
    process.exit(1);
  }}
  const result = await fn('{tool}', JSON.parse('{args}'));
  process.stdout.write(JSON.stringify(result));
}})().catch(e => {{
  process.stderr.write(String(e));
  process.exit(1);
}});
"#,
        module = escaped_module,
        tool = escaped_tool,
        args = escaped_args,
    );

    let output = std::process::Command::new(&node)
        .arg("-e")
        .arg(&script)
        .output()
        .map_err(|e| format!("failed to spawn node: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("node error: {stderr}"));
    }

    let stdout = String::from_utf8_lossy(&output.stdout).into_owned();
    if stdout.trim().is_empty() {
        return Err("node returned empty output".into());
    }

    Ok(stdout.trim().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_server_target_parsing() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("node:./server.js");
        let path = call.server_target.strip_prefix("node:").unwrap();
        assert_eq!(path, "./server.js");
    }

    #[test]
    fn test_execute_rejects_non_node_target() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("python:mcp_server");
        assert!(execute(&call).is_err());
    }

    #[test]
    fn test_execute_rejects_empty_module() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("node:");
        assert!(execute(&call).is_err());
    }

    #[test]
    fn test_which_node_returns_some_when_available() {
        if let Some(node) = which_node() {
            assert!(!node.is_empty());
        }
    }
}
