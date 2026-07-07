/// Upstream MCP client: forwards JSON-RPC requests to the real MCP Server.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tracing::{debug, warn};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamConfig {
    pub url: String,
    pub transport: String,
    #[serde(default = "default_timeout")]
    pub timeout_secs: u64,
}

fn default_timeout() -> u64 {
    30
}

/// Client for forwarding JSON-RPC requests to an upstream MCP Server.
#[derive(Clone)]
pub struct UpstreamClient {
    config: UpstreamConfig,
    http: reqwest::Client,
}

impl UpstreamClient {
    pub fn new(config: UpstreamConfig) -> Self {
        let http = reqwest::Client::builder()
            .timeout(Duration::from_secs(config.timeout_secs))
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        Self { config, http }
    }

    /// Forward a JSON-RPC request to the upstream MCP Server via SSE transport.
    ///
    /// For SSE transport, we POST the JSON-RPC request to the upstream URL
    /// and parse the response. For simple request/response (non-streaming),
    /// this returns the full JSON-RPC response.
    pub async fn forward(&self, request: &Value) -> Result<Value, UpstreamError> {
        debug!("forwarding to upstream {}: {}", self.config.url, request);

        if self.config.transport == "stdio" {
            // stdio upstream is not supported via HTTP — this would require
            // spawning the upstream as a child process. For P0, we support SSE.
            return Err(UpstreamError::UnsupportedTransport(
                self.config.transport.clone(),
            ));
        }

        // SSE / HTTP transport: POST JSON-RPC and read response
        let resp = self
            .http
            .post(&self.config.url)
            .json(request)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .send()
            .await
            .map_err(UpstreamError::Http)?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            warn!("upstream returned {}: {}", status, body);
            return Err(UpstreamError::Status(status.as_u16(), body));
        }

        // Check content type — SSE responses need event parsing
        let content_type = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("");

        if content_type.contains("text/event-stream") {
            // Parse SSE: look for a `data:` line containing the JSON-RPC response
            let body = resp.text().await.unwrap_or_default();
            let json = parse_sse_body(&body)?;
            Ok(json)
        } else {
            // Plain JSON response
            let json = resp.json::<Value>().await.map_err(UpstreamError::Http)?;
            Ok(json)
        }
    }

    /// Forward a notification (no response expected).
    pub async fn forward_notification(&self, request: &Value) -> Result<(), UpstreamError> {
        debug!("forwarding notification to upstream: {}", request);

        if self.config.transport == "stdio" {
            return Err(UpstreamError::UnsupportedTransport(
                self.config.transport.clone(),
            ));
        }

        let _ = self
            .http
            .post(&self.config.url)
            .json(request)
            .send()
            .await
            .map_err(UpstreamError::Http)?;

        Ok(())
    }

    pub fn url(&self) -> &str {
        &self.config.url
    }
}

/// Parse an SSE response body and extract the first JSON-RPC `data:` payload.
fn parse_sse_body(body: &str) -> Result<Value, UpstreamError> {
    for line in body.lines() {
        let line = line.trim();
        if let Some(data) = line.strip_prefix("data:") {
            let data = data.trim();
            if data.is_empty() || data == "[DONE]" {
                continue;
            }
            if let Ok(v) = serde_json::from_str::<Value>(data) {
                return Ok(v);
            }
        }
    }
    // If no SSE data: line found, try parsing the whole body as JSON
    serde_json::from_str::<Value>(body).map_err(|e| UpstreamError::Parse(e.to_string()))
}

#[derive(Debug)]
pub enum UpstreamError {
    Http(reqwest::Error),
    Status(u16, String),
    Parse(String),
    UnsupportedTransport(String),
    Timeout,
}

impl std::fmt::Display for UpstreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Http(e) => write!(f, "upstream http error: {e}"),
            Self::Status(code, body) => write!(f, "upstream returned {code}: {body}"),
            Self::Parse(e) => write!(f, "upstream parse error: {e}"),
            Self::UnsupportedTransport(t) => write!(f, "unsupported upstream transport: {t}"),
            Self::Timeout => write!(f, "upstream timeout"),
        }
    }
}

impl std::error::Error for UpstreamError {}
