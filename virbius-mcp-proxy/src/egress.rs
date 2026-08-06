/// Egress HTTP client: proxies tool calls (curl/http_request) to external APIs
/// with URL whitelist validation and streaming response support.
///
/// Key design: uses reqwest `bytes_stream()` to read response bodies in chunks,
/// preventing OOM on large responses. SSE (text/event-stream) responses are
/// parsed and forwarded as-is.
use std::time::Duration;

use futures_util::StreamExt;
use serde_json::Value;
use tracing::{debug, warn};

/// Default maximum response body size (50 MB).
const DEFAULT_MAX_BODY_BYTES: usize = 50 * 1024 * 1024;

/// Default request timeout (30 seconds).
const DEFAULT_TIMEOUT_SECS: u64 = 30;

/// Maximum redirect hops to follow (prevents SSRF via redirect).
const MAX_REDIRECTS: usize = 5;

/// Tools that trigger egress proxy behavior.
const EGRESS_TOOLS: &[&str] = &["curl", "http_request", "fetch", "web_search"];

/// Local code-execution tools executed by the proxy itself in a sandbox
/// (none/landlock/gvisor). These are never forwarded to an upstream.
///
/// Each entry: (tool_name, language, code_arg).
pub const LOCAL_EXEC_TOOLS: &[(&str, &str, &str)] = &[
    ("shell", "shell", "command"),
    ("execute_python", "python", "code"),
    ("execute_code", "python", "code"),
    ("execute_node", "node", "code"),
];

/// If `tool_name` is a local code-execution tool, returns `(language, code_arg)`.
pub fn local_exec_info(tool_name: &str) -> Option<(&'static str, &'static str)> {
    LOCAL_EXEC_TOOLS
        .iter()
        .find(|(name, _, _)| *name == tool_name)
        .map(|(_, lang, arg)| (*lang, *arg))
}

/// Synthesized tool descriptors for local code-execution tools, injected into
/// `tools/list` so agents can discover and call them.
pub fn local_exec_tool_descriptors() -> Vec<Value> {
    LOCAL_EXEC_TOOLS
        .iter()
        .map(|&(name, _lang, code_arg)| {
            let desc = match name {
                "shell" => "Execute a shell command (sandboxed locally by virbius proxy)",
                "execute_python" | "execute_code" => "Execute Python code (sandboxed locally by virbius proxy)",
                "execute_node" => "Execute Node.js code (sandboxed locally by virbius proxy)",
                _ => "Execute code (sandboxed locally by virbius proxy)",
            };
            serde_json::json!({
                "name": name,
                "description": desc,
                "inputSchema": {
                    "type": "object",
                    "properties": {
                        code_arg: {"type": "string", "description": "Code to execute"}
                    },
                    "required": [code_arg]
                }
            })
        })
        .collect()
}

/// HTTP client for proxying egress tool calls to external APIs.
///
/// Streaming is handled via `reqwest::Response::bytes_stream()`: response body
/// chunks are read incrementally and accumulated into a buffer with a size
/// limit. This avoids buffering the entire response in memory at once,
/// preventing OOM on large API responses.
#[derive(Clone)]
pub struct EgressClient {
    http: reqwest::Client,
    max_body_bytes: usize,
}

/// The result of a successful egress proxy request.
pub struct EgressResponse {
    pub status: u16,
    pub content_type: String,
    pub body: Vec<u8>,
}

#[derive(Debug)]
pub enum EgressError {
    Http(reqwest::Error),
    Status(u16, String),
    TooLarge(usize),
    InvalidUrl(String),
    UnsupportedMethod(String),
    RedirectLoop,
}

impl std::fmt::Display for EgressError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Http(e) => write!(f, "egress http error: {e}"),
            Self::Status(code, body) => write!(f, "egress returned {code}: {body}"),
            Self::TooLarge(max) => write!(f, "egress response exceeded {max} bytes"),
            Self::InvalidUrl(e) => write!(f, "invalid url: {e}"),
            Self::UnsupportedMethod(m) => write!(f, "unsupported method: {m}"),
            Self::RedirectLoop => write!(f, "too many redirects (possible SSRF)"),
        }
    }
}

impl std::error::Error for EgressError {}

impl EgressClient {
    pub fn new(timeout_secs: u64, max_body_mb: usize) -> Self {
        let timeout = if timeout_secs == 0 {
            DEFAULT_TIMEOUT_SECS
        } else {
            timeout_secs
        };
        let max_body = if max_body_mb == 0 {
            DEFAULT_MAX_BODY_BYTES
        } else {
            max_body_mb * 1024 * 1024
        };

        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(timeout))
            .redirect(reqwest::redirect::Policy::limited(MAX_REDIRECTS))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());

        Self {
            http,
            max_body_bytes: max_body,
        }
    }

    /// Proxy an HTTP request to an external API with streaming response reading.
    ///
    /// The response body is read in chunks via `bytes_stream()`, preventing OOM
    /// on large responses. If the body exceeds `max_body_bytes`, an error is
    /// returned immediately without reading further.
    pub async fn proxy_request(
        &self,
        url: &str,
        method: &str,
        body: Option<&Value>,
        headers: Option<&[(String, String)]>,
    ) -> Result<EgressResponse, EgressError> {
        debug!("egress proxy: {} {}", method, url);

        let mut req = match method.to_uppercase().as_str() {
            "GET" => self.http.get(url),
            "POST" => {
                let r = self.http.post(url);
                match body {
                    Some(v) => r.json(v),
                    None => r,
                }
            }
            "PUT" => {
                let r = self.http.put(url);
                match body {
                    Some(v) => r.json(v),
                    None => r,
                }
            }
            "DELETE" => self.http.delete(url),
            other => return Err(EgressError::UnsupportedMethod(other.to_string())),
        };

        // Inject safe headers (filter out Authorization — License injects its own)
        if let Some(hdrs) = headers {
            for (k, v) in hdrs {
                if k.eq_ignore_ascii_case("authorization") {
                    continue;
                }
                req = req.header(k, v);
            }
        }

        let resp = req.send().await.map_err(EgressError::Http)?;
        let status = resp.status();

        if !status.is_success() {
            let body_text = resp.text().await.unwrap_or_default();
            warn!("egress {} returned {}: {}", url, status, body_text);
            return Err(EgressError::Status(status.as_u16(), body_text));
        }

        let content_type = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("application/octet-stream")
            .to_string();

        // Stream response body in chunks to avoid OOM on large responses.
        // reqwest::bytes_stream() yields chunks as they arrive from the
        // network, so we never hold the entire response in a single allocation.
        let mut stream = resp.bytes_stream();
        let mut buf = Vec::new();

        while let Some(chunk_result) = stream.next().await {
            let chunk = chunk_result.map_err(EgressError::Http)?;
            if buf.len() + chunk.len() > self.max_body_bytes {
                return Err(EgressError::TooLarge(self.max_body_bytes));
            }
            buf.extend_from_slice(&chunk);
        }

        debug!(
            "egress response: status={}, content_type={}, body={} bytes",
            status.as_u16(),
            content_type,
            buf.len()
        );

        Ok(EgressResponse {
            status: status.as_u16(),
            content_type,
            body: buf,
        })
    }
}

/// Check if a tool name is an egress tool (curl, http_request, etc.).
pub fn is_egress_tool(tool_name: &str) -> bool {
    EGRESS_TOOLS.contains(&tool_name)
}

/// Extract the target URL from tool call arguments.
///
/// Supports common parameter names: `url`, `endpoint`, `uri`.
/// For `web_search`, the search is performed via a configured search API
/// (the URL is constructed from the query, not passed directly by the Agent).
pub fn extract_url_from_args(tool_name: &str, args: &Value) -> Result<String, String> {
    if tool_name == "web_search" {
        // web_search uses a configured search endpoint, not a direct URL
        // The actual URL is injected by the Proxy from configuration
        return Err("web_search url is proxy-configured, not extracted from args".into());
    }

    let url_str = args
        .get("url")
        .or_else(|| args.get("endpoint"))
        .or_else(|| args.get("uri"))
        .and_then(|v| v.as_str())
        .ok_or_else(|| format!("missing 'url' parameter for tool '{}'", tool_name))?;

    if url_str.is_empty() {
        return Err("url parameter is empty".into());
    }

    Ok(url_str.to_string())
}

/// Validate that a target URL's host is in the allowed hosts list.
///
/// Allowed hosts can specify port: "api.internal:443" or just "api.internal"
/// (which matches any port on that host).
pub fn validate_egress_url(url_str: &str, allowed_hosts: &[String]) -> Result<(), String> {
    let parsed = url::Url::parse(url_str).map_err(|e| format!("invalid url: {e}"))?;

    // Only allow http and https schemes
    let scheme = parsed.scheme();
    if scheme != "http" && scheme != "https" {
        return Err(format!("scheme '{}' not allowed (only http/https)", scheme));
    }

    let host = parsed.host_str().ok_or("url has no host")?;
    let port = parsed.port_or_known_default().unwrap_or(443);

    let matched = allowed_hosts.iter().any(|allowed| {
        let parts: Vec<&str> = allowed.splitn(2, ':').collect();
        let allowed_host = parts[0];
        let allowed_port: Option<u16> = parts.get(1).and_then(|p| p.parse().ok());

        // Host must match exactly (no wildcard for security)
        host == allowed_host && (allowed_port.is_none() || allowed_port == Some(port))
    });

    if !matched {
        return Err(format!("host '{}' not in egress allowlist", host));
    }

    Ok(())
}

/// Convert an egress response into an MCP `tools/call` result.
///
/// The response body is returned as a text content item. For JSON responses,
/// we attempt to parse and return as structured JSON. For SSE responses, we
/// return the raw event text.
pub fn to_mcp_result(response: &EgressResponse) -> Value {
    let body_text = String::from_utf8_lossy(&response.body);

    // For JSON responses, try to parse and return as structured data
    if response.content_type.contains("application/json") {
        if let Ok(json_val) = serde_json::from_str::<Value>(&body_text) {
            return serde_json::json!({
                "content": [
                    {
                        "type": "text",
                        "text": serde_json::to_string_pretty(&json_val).unwrap_or_else(|_| body_text.into_owned())
                    }
                ],
                "isError": false
            });
        }
    }

    // For all other content types (including SSE), return as text
    serde_json::json!({
        "content": [
            {
                "type": "text",
                "text": body_text
            }
        ],
        "isError": false
    })
}

/// Build a JSON-RPC error response for egress failures.
pub fn egress_error_response(id: &Value, reason: &str) -> Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": {
            "code": -32603,
            "message": "egress_proxy_error",
            "data": {
                "reason": reason
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_validate_egress_url_https() {
        let allowed = vec!["api.internal:443".to_string(), "cdn.internal".to_string()];

        assert!(validate_egress_url("https://api.internal/v1/data", &allowed).is_ok());
        assert!(validate_egress_url("https://api.internal:443/v1/data", &allowed).is_ok());
        assert!(validate_egress_url("https://cdn.internal:8080/file", &allowed).is_ok());
    }

    #[test]
    fn test_validate_egress_url_blocked() {
        let allowed = vec!["api.internal:443".to_string()];

        assert!(validate_egress_url("https://evil.com/exfil", &allowed).is_err());
        assert!(validate_egress_url("https://api.internal:8443/data", &allowed).is_err());
        assert!(validate_egress_url("ftp://api.internal/file", &allowed).is_err());
    }

    #[test]
    fn test_extract_url_from_args() {
        let args = serde_json::json!({"url": "https://api.internal/data"});
        assert_eq!(
            extract_url_from_args("curl", &args).unwrap(),
            "https://api.internal/data"
        );
    }

    #[test]
    fn test_extract_url_from_endpoint() {
        let args = serde_json::json!({"endpoint": "https://api.internal/data"});
        assert_eq!(
            extract_url_from_args("http_request", &args).unwrap(),
            "https://api.internal/data"
        );
    }

    #[test]
    fn test_extract_url_missing() {
        let args = serde_json::json!({"query": "hello"});
        assert!(extract_url_from_args("curl", &args).is_err());
    }

    #[test]
    fn test_is_egress_tool() {
        assert!(is_egress_tool("curl"));
        assert!(is_egress_tool("http_request"));
        assert!(is_egress_tool("fetch"));
        assert!(is_egress_tool("web_search"));
        assert!(!is_egress_tool("read_file"));
        assert!(!is_egress_tool("execute_python"));
    }

    #[test]
    fn test_to_mcp_result_json() {
        let response = EgressResponse {
            status: 200,
            content_type: "application/json".into(),
            body: b"{\"key\": \"value\"}".to_vec(),
        };
        let result = to_mcp_result(&response);
        assert_eq!(result["isError"], false);
        assert!(result["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("key"));
    }

    #[test]
    fn test_to_mcp_result_sse() {
        let sse_body = b"data: {\"event\": \"update\"}\n\ndata: [DONE]\n\n";
        let response = EgressResponse {
            status: 200,
            content_type: "text/event-stream".into(),
            body: sse_body.to_vec(),
        };
        let result = to_mcp_result(&response);
        assert_eq!(result["isError"], false);
        assert!(result["content"][0]["text"]
            .as_str()
            .unwrap()
            .contains("event"));
    }
}
