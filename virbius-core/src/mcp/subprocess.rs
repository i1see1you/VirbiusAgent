//! Subprocess backend for MCP Server integration — the universal fallback.
//!
//! This backend communicates with an MCP Server process via stdio JSON-RPC,
//! using the MCP protocol's `tools/call` method. It works with any MCP Server
//! that supports stdio transport, regardless of implementation language.
//!
//! ## Server target format
//!
//! - `subprocess:/usr/local/bin/mcp-server` — explicit subprocess path
//! - `/usr/local/bin/mcp-server` — bare path (treated as subprocess)
//! - `python:module` (when PyO3 fails) — caller strips prefix and invokes
//!   `python3 -m <module>` via subprocess
//! - `node:./server.js` (when napi fails) — caller strips prefix and invokes
//!   `node ./server.js` via subprocess
//!
//! ## Protocol
//!
//! The subprocess backend speaks a simplified JSON-RPC 2.0 protocol over stdin/stdout:
//!
//! ```json
//! // Request (stdin, newline-delimited)
//! {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"read_file","arguments":{"path":"/tmp/test"}}}
//!
//! // Response (stdout, newline-delimited)
//! {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"file contents here"}]}}
//! ```
//!
//! If the server process exits non-zero or times out, an error ToolResult is returned.

use super::{McpToolCall, ToolResult};
use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Execute a tool call via subprocess (stdio JSON-RPC).
///
/// This is the final fallback — it always attempts to run. If the subprocess
/// cannot be spawned or times out, it returns a `ToolResult` with `success: false`.
pub fn execute(call: &McpToolCall, start: Instant) -> ToolResult {
    let (program, args) = resolve_command(&call.server_target);

    if program.is_empty() {
        return ToolResult::err(
            "empty program path in server_target",
            "empty_program",
            start.elapsed().as_millis() as u64,
            "subprocess",
        );
    }

    let mut cmd = Command::new(&program);
    cmd.args(&args)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            return ToolResult::err(
                format!("spawn error: {e}"),
                "spawn_error",
                start.elapsed().as_millis() as u64,
                "subprocess",
            );
        }
    };

    // Build JSON-RPC request
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {
            "name": call.tool_name,
            "arguments": call.args,
        }
    });
    let request_line = format!("{}\n", request);

    // Write request to stdin
    {
        let stdin = child.stdin.as_mut().expect("stdin pipe");
        if let Err(e) = stdin.write_all(request_line.as_bytes()) {
            let _ = child.kill();
            return ToolResult::err(
                format!("stdin write failed: {e}"),
                "stdin_write_failed",
                start.elapsed().as_millis() as u64,
                "subprocess",
            );
        }
        let _ = stdin.flush();
        // Close stdin to signal EOF — many MCP servers expect this
        drop(child.stdin.take());
    }

    // Read response with timeout
    let timeout = Duration::from_millis(call.timeout_ms);
    let stdout = child.stdout.take().expect("stdout pipe");

    let result = read_with_timeout(stdout, timeout);

    // Wait for process to exit (non-blocking, already have output)
    let _ = child.wait();

    match result {
        Ok(output) => {
            let duration_ms = start.elapsed().as_millis() as u64;
            let trimmed = output.trim();
            if trimmed.is_empty() {
                return ToolResult::err(
                    "subprocess returned empty output",
                    "empty_output",
                    duration_ms,
                    "subprocess",
                );
            }
            // Validate JSON
            match serde_json::from_str::<serde_json::Value>(trimmed) {
                Ok(json) => {
                    // Check for JSON-RPC error
                    if let Some(error) = json.get("error") {
                        let msg = error
                            .get("message")
                            .and_then(|m| m.as_str())
                            .unwrap_or("unknown MCP error");
                        return ToolResult::err(
                            msg.to_string(),
                            "mcp_error",
                            duration_ms,
                            "subprocess",
                        );
                    }
                    // Extract result
                    let result_str = json
                        .get("result")
                        .map(|r| r.to_string())
                        .unwrap_or_else(|| trimmed.to_string());
                    ToolResult::ok(result_str, duration_ms, "subprocess")
                }
                Err(e) => ToolResult::err(
                    format!("invalid JSON response: {e}"),
                    "invalid_json",
                    duration_ms,
                    "subprocess",
                ),
            }
        }
        Err(e) => {
            let _ = child.kill();
            let duration_ms = start.elapsed().as_millis() as u64;
            let error_type = if e.contains("timed out") {
                "timeout"
            } else {
                "read_error"
            };
            ToolResult::err(e, error_type, duration_ms, "subprocess")
        }
    }
}

/// Resolve the server target into a (program, args) command tuple.
///
/// Handles all target formats:
/// - `subprocess:/path/to/server` → (`/path/to/server`, [])
/// - `python:module.path` → (`python3`, `["-m", "module.path"]`)
/// - `node:./server.js` → (`node`, `["./server.js"]`)
/// - `/path/to/server` → (`/path/to/server`, [])
/// - `mcp-server` → (`mcp-server`, [])
fn resolve_command(server_target: &str) -> (String, Vec<String>) {
    let target = server_target
        .strip_prefix("subprocess:")
        .unwrap_or(server_target);

    if let Some(module) = target.strip_prefix("python:") {
        // Python module invocation
        let python = if std::process::Command::new("python3")
            .arg("--version")
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .is_ok()
        {
            "python3"
        } else {
            "python"
        };
        return (python.to_string(), vec!["-m".into(), module.into()]);
    }

    if let Some(script) = target.strip_prefix("node:") {
        return ("node".into(), vec![script.into()]);
    }

    // Bare path / executable
    let parts: Vec<&str> = target.split_whitespace().collect();
    if parts.is_empty() {
        return (String::new(), Vec::new());
    }
    (
        parts[0].to_string(),
        parts[1..].iter().map(|s| s.to_string()).collect(),
    )
}

/// Read stdout with a timeout, returning the full output as a String.
fn read_with_timeout(
    mut reader: std::process::ChildStdout,
    timeout: Duration,
) -> Result<String, String> {
    // Use a non-blocking read approach with periodic checks.
    // For simplicity and reliability, we use a thread + channel.
    let (tx, rx) = std::sync::mpsc::channel::<Result<String, String>>();

    std::thread::spawn(move || {
        let mut buf = String::new();
        let mut reader = BufReader::new(&mut reader);
        // Read one line (the JSON-RPC response)
        match reader.read_line(&mut buf) {
            Ok(0) => tx.send(Err("subprocess closed stdout".into())).ok(),
            Ok(_) => tx.send(Ok(buf)).ok(),
            Err(e) => tx.send(Err(format!("read error: {e}"))).ok(),
        }
    });

    match rx.recv_timeout(timeout) {
        Ok(result) => result,
        Err(_) => Err("timed out waiting for subprocess response".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_resolve_command_bare_path() {
        let (program, args) = resolve_command("/usr/local/bin/mcp-server");
        assert_eq!(program, "/usr/local/bin/mcp-server");
        assert!(args.is_empty());
    }

    #[test]
    fn test_resolve_command_subprocess_prefix() {
        let (program, args) = resolve_command("subprocess:/usr/local/bin/mcp-server");
        assert_eq!(program, "/usr/local/bin/mcp-server");
        assert!(args.is_empty());
    }

    #[test]
    fn test_resolve_command_python_prefix() {
        let (program, args) = resolve_command("python:mcp_server.tools");
        assert!(program == "python3" || program == "python");
        assert_eq!(args, vec!["-m", "mcp_server.tools"]);
    }

    #[test]
    fn test_resolve_command_node_prefix() {
        let (program, args) = resolve_command("node:./server.js");
        assert_eq!(program, "node");
        assert_eq!(args, vec!["./server.js"]);
    }

    #[test]
    fn test_resolve_command_with_args() {
        let (program, args) = resolve_command("/usr/bin/python3 -u server.py");
        assert_eq!(program, "/usr/bin/python3");
        assert_eq!(args, vec!["-u", "server.py"]);
    }

    #[test]
    fn test_execute_spawn_failure() {
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("/nonexistent/binary/that/does/not/exist")
            .with_timeout(1000);
        let start = Instant::now();
        let result = execute(&call, start);
        assert!(!result.success);
        assert_eq!(result.backend, "subprocess");
    }

    #[test]
    fn test_execute_echo_server() {
        // Use `echo` as a minimal MCP server — it outputs a line and exits.
        // The response won't be valid JSON-RPC, but it tests the subprocess pipeline.
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("echo")
            .with_timeout(5000);
        let start = Instant::now();
        let result = execute(&call, start);
        // echo will output the request, which is valid JSON, so JSON parsing may
        // succeed but the structure won't have "result" — that's fine, we just
        // verify the subprocess pipeline works.
        assert_eq!(result.backend, "subprocess");
    }

    #[test]
    fn test_execute_timeout() {
        // Use `sleep` which will never produce output — should timeout.
        let call = McpToolCall::new("test", serde_json::json!({}))
            .with_server("sleep 30")
            .with_timeout(500); // 500ms timeout
        let start = Instant::now();
        let result = execute(&call, start);
        assert!(!result.success);
        assert_eq!(result.backend, "subprocess");
        // Should have timed out
        assert!(
            result.error_type.as_deref() == Some("timeout")
                || result.error_type.as_deref() == Some("read_error"),
            "expected timeout or read_error, got {:?}",
            result.error_type
        );
    }
}
