/// Upstream MCP client: connects to upstream MCP Server via SSE transport.
///
/// Architecture:
/// - `UpstreamClient` — one instance per downstream session, manages a single
///   SSE connection to the upstream MCP Server.
/// - `UpstreamManager` — owns a `DashMap<session_id, UpstreamClient>`, creating
///   and cleaning up per-session upstream connections.
///
/// SSE client protocol:
/// 1. GET /sse to establish SSE long connection
/// 2. Read `endpoint` event to get POST URL (with session_id)
/// 3. Start background SSE reader to receive responses
/// 4. POST JSON-RPC requests to the endpoint URL
/// 5. Responses are received via SSE stream and matched by JSON-RPC id

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::{oneshot, Mutex as TokioMutex};
use tracing::{debug, warn};

use futures_util::StreamExt;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct UpstreamConfig {
    pub url: String,
    pub transport: String,
    #[serde(default = "default_timeout")]
    pub timeout_secs: u64,
    #[serde(default = "default_sse_path")]
    pub sse_path: String,
}

fn default_timeout() -> u64 {
    30
}

fn default_sse_path() -> String {
    "/sse".to_string()
}

struct UpstreamState {
    /// POST endpoint URL (set after SSE connect, includes session_id)
    endpoint_url: std::sync::Mutex<Option<String>>,
    /// Pending requests waiting for SSE responses: JSON-RPC id -> sender
    pending: DashMap<Value, oneshot::Sender<Value>>,
    /// Whether SSE connection is established
    connected: AtomicBool,
    /// Lock to serialize connect attempts
    connect_lock: TokioMutex<()>,
}

/// Client for forwarding JSON-RPC requests to an upstream MCP Server via SSE.
///
/// Each instance maintains a single SSE connection to the upstream.
/// Created by `UpstreamManager` on a per-session basis.
#[derive(Clone)]
pub struct UpstreamClient {
    base_url: String,
    sse_path: String,
    http: reqwest::Client,
    timeout: Duration,
    state: Arc<UpstreamState>,
}

impl UpstreamClient {
    pub fn new(config: UpstreamConfig) -> Self {
        Self::with_http(config, reqwest::Client::new())
    }

    /// Create with a shared HTTP client (connection pool reuse).
    fn with_http(config: UpstreamConfig, http: reqwest::Client) -> Self {
        let timeout = Duration::from_secs(config.timeout_secs);
        Self {
            base_url: config.url,
            sse_path: config.sse_path,
            http,
            timeout,
            state: Arc::new(UpstreamState {
                endpoint_url: std::sync::Mutex::new(None),
                pending: DashMap::new(),
                connected: AtomicBool::new(false),
                connect_lock: TokioMutex::new(()),
            }),
        }
    }

    /// Ensure SSE connection is established.
    async fn ensure_connected(&self) -> Result<(), UpstreamError> {
        if self.state.connected.load(Ordering::Relaxed) {
            let url = self.state.endpoint_url.lock().unwrap();
            if url.is_some() {
                return Ok(());
            }
        }

        // Acquire connect lock to avoid multiple simultaneous connect attempts
        let _lock = self.state.connect_lock.lock().await;

        // Double-check after acquiring lock
        if self.state.connected.load(Ordering::Relaxed) {
            let url = self.state.endpoint_url.lock().unwrap();
            if url.is_some() {
                return Ok(());
            }
        }

        self.connect_sse().await
    }

    /// Establish SSE connection with upstream MCP Server.
    ///
    /// GET {base_url}{sse_path} → SSE stream
    /// Read first `endpoint` event → get POST URL with session_id
    /// Start background reader to process subsequent SSE events.
    async fn connect_sse(&self) -> Result<(), UpstreamError> {
        let sse_url = format!(
            "{}{}",
            self.base_url.trim_end_matches('/'),
            self.sse_path
        );
        debug!("connecting to upstream SSE: {}", sse_url);

        let resp = self
            .http
            .get(&sse_url)
            .header("Accept", "text/event-stream")
            .send()
            .await
            .map_err(UpstreamError::Http)?;

        let status = resp.status();
        if !status.is_success() {
            let body = resp.text().await.unwrap_or_default();
            warn!("upstream SSE returned {}: {}", status, body);
            return Err(UpstreamError::Status(status.as_u16(), body));
        }

        // Reset state
        {
            let mut url = self.state.endpoint_url.lock().unwrap();
            *url = None;
        }
        self.state.connected.store(false, Ordering::Relaxed);

        // Start background SSE reader
        let state = self.state.clone();
        let base_url = self.base_url.clone();
        tokio::spawn(async move {
            sse_reader(state, base_url, resp).await;
        });

        // Wait for endpoint URL to be set by the background reader
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10);
        loop {
            {
                let url = self.state.endpoint_url.lock().unwrap();
                if url.is_some() {
                    break;
                }
            }
            if tokio::time::Instant::now() >= deadline {
                return Err(UpstreamError::Timeout);
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }

        self.state.connected.store(true, Ordering::Relaxed);
        let endpoint = self.state.endpoint_url.lock().unwrap().clone();
        debug!("upstream SSE connected, endpoint: {:?}", endpoint);
        Ok(())
    }

    /// Forward a JSON-RPC request to the upstream MCP Server.
    ///
    /// POSTs the request to the SSE endpoint URL and waits for the response
    /// via the SSE stream (matched by JSON-RPC id).
    pub async fn forward(&self, request: &Value) -> Result<Value, UpstreamError> {
        self.ensure_connected().await?;

        let endpoint_url = {
            let url = self.state.endpoint_url.lock().unwrap();
            url.clone().ok_or(UpstreamError::NotConnected)?
        };

        let id = request.get("id").cloned().unwrap_or(Value::Null);

        // Register pending request before POSTing
        let (tx, rx) = oneshot::channel();
        self.state.pending.insert(id.clone(), tx);

        // POST the request to the upstream message endpoint
        debug!("forwarding to upstream {}: {}", endpoint_url, request);
        let resp = self
            .http
            .post(&endpoint_url)
            .json(request)
            .header("Content-Type", "application/json")
            .send()
            .await;

        match resp {
            Ok(resp) => {
                let status = resp.status();
                if !status.is_success() {
                    self.state.pending.remove(&id);
                    let body = resp.text().await.unwrap_or_default();
                    warn!("upstream POST returned {}: {}", status, body);
                    return Err(UpstreamError::Status(status.as_u16(), body));
                }
                debug!("upstream POST accepted: {}", status);
            }
            Err(e) => {
                self.state.pending.remove(&id);
                // Mark as disconnected so next call will reconnect
                self.state.connected.store(false, Ordering::Relaxed);
                return Err(UpstreamError::Http(e));
            }
        }

        // Wait for SSE response with timeout
        match tokio::time::timeout(self.timeout, rx).await {
            Ok(Ok(response)) => Ok(response),
            Ok(Err(_)) => {
                self.state.pending.remove(&id);
                Err(UpstreamError::Timeout)
            }
            Err(_) => {
                self.state.pending.remove(&id);
                Err(UpstreamError::Timeout)
            }
        }
    }

    /// Forward a notification (no response expected).
    pub async fn forward_notification(&self, request: &Value) -> Result<(), UpstreamError> {
        self.ensure_connected().await?;

        let endpoint_url = {
            let url = self.state.endpoint_url.lock().unwrap();
            url.clone().ok_or(UpstreamError::NotConnected)?
        };

        debug!("forwarding notification to upstream: {}", request);
        let _ = self
            .http
            .post(&endpoint_url)
            .json(request)
            .send()
            .await
            .map_err(UpstreamError::Http)?;

        Ok(())
    }

    pub fn url(&self) -> &str {
        &self.base_url
    }

    /// Check if the SSE connection is still alive.
    pub fn is_connected(&self) -> bool {
        self.state.connected.load(Ordering::Relaxed)
    }
}

/// Manages per-session upstream connections.
///
/// Each downstream session gets its own `UpstreamClient` with an independent
/// SSE connection to the upstream MCP Server. This ensures:
/// - JSON-RPC id namespaces are isolated per session
/// - One client's disconnect doesn't affect another
/// - Upstream session_id (from FastMCP) is per-connection
pub struct UpstreamManager {
    base_url: String,
    sse_path: String,
    timeout: Duration,
    /// Shared HTTP client (connection pool reuse across sessions)
    http: reqwest::Client,
    /// session_id → UpstreamClient
    connections: DashMap<String, UpstreamClient>,
}

impl UpstreamManager {
    pub fn new(config: &UpstreamConfig) -> Self {
        let timeout = Duration::from_secs(config.timeout_secs);
        let http = reqwest::Client::builder()
            .timeout(timeout)
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());

        Self {
            base_url: config.url.clone(),
            sse_path: config.sse_path.clone(),
            timeout,
            http,
            connections: DashMap::new(),
        }
    }

    /// Get an existing upstream client for the session, or create a new one.
    ///
    /// On first call for a session, this creates a new `UpstreamClient` and
    /// establishes the SSE connection (GET /sse handshake).
    pub async fn get_or_connect(
        &self,
        session_id: &str,
    ) -> Result<UpstreamClient, UpstreamError> {
        // Fast path: already exists and connected
        if let Some(client) = self.connections.get(session_id) {
            if client.is_connected() {
                return Ok(client.clone());
            }
        }

        // Slow path: create or reconnect
        let config = UpstreamConfig {
            url: self.base_url.clone(),
            transport: "sse".to_string(),
            timeout_secs: self.timeout.as_secs(),
            sse_path: self.sse_path.clone(),
        };

        let client = UpstreamClient::with_http(config, self.http.clone());
        client.ensure_connected().await?;
        self.connections
            .insert(session_id.to_string(), client.clone());
        debug!("upstream connection created for session: {}", session_id);
        Ok(client)
    }

    /// Remove and drop the upstream connection for a session.
    ///
    /// Called when a session expires (TTL cleanup) or is explicitly closed.
    pub fn remove(&self, session_id: &str) {
        if let Some((_, _client)) = self.connections.remove(session_id) {
            debug!("upstream connection removed for session: {}", session_id);
        }
    }

    /// Number of active upstream connections.
    pub fn len(&self) -> usize {
        self.connections.len()
    }
}

/// Background SSE reader: reads events from the SSE stream and dispatches responses.
///
/// This task runs for the lifetime of the SSE connection. It:
/// - Parses `endpoint` events to extract the POST URL
/// - Parses `message` events to extract JSON-RPC responses
/// - Matches responses to pending requests by JSON-RPC id
/// - On connection loss, marks the connection as disconnected and fails pending requests
async fn sse_reader(state: Arc<UpstreamState>, base_url: String, resp: reqwest::Response) {
    debug!("SSE reader started for {}", base_url);

    let mut stream = resp.bytes_stream();
    let mut buffer = String::new();

    while let Some(chunk_result) = stream.next().await {
        match chunk_result {
            Ok(chunk) => {
                // Normalize CRLF to LF and append to buffer
                buffer.push_str(&String::from_utf8_lossy(&chunk).replace("\r\n", "\n"));

                // Parse complete SSE events (separated by \n\n)
                while let Some(pos) = buffer.find("\n\n") {
                    let raw_event = buffer[..pos].to_string();
                    buffer = buffer[pos + 2..].to_string();

                    let mut event_type = String::new();
                    let mut data = String::new();

                    for line in raw_event.lines() {
                        let line = line.trim();
                        if let Some(val) = line.strip_prefix("event:") {
                            event_type = val.trim().to_string();
                        } else if let Some(val) = line.strip_prefix("data:") {
                            data = val.trim().to_string();
                        }
                    }

                    match event_type.as_str() {
                        "endpoint" => {
                            // Construct full URL from relative path
                            let full_url = construct_endpoint_url(&base_url, &data);
                            debug!("SSE endpoint received: {}", full_url);
                            let mut url = state.endpoint_url.lock().unwrap();
                            *url = Some(full_url);
                        }
                        "message" => {
                            if data.is_empty() {
                                continue;
                            }
                            // Parse JSON-RPC response and dispatch to pending request
                            match serde_json::from_str::<Value>(&data) {
                                Ok(json) => {
                                    let id = json.get("id").cloned().unwrap_or(Value::Null);
                                    if let Some((_, sender)) = state.pending.remove(&id) {
                                        let _ = sender.send(json);
                                    } else {
                                        debug!("SSE message with unmatched id: {:?}", id);
                                    }
                                }
                                Err(e) => {
                                    warn!("SSE message parse error: {} - data: {}", e, data);
                                }
                            }
                        }
                        _ => {
                            debug!("SSE unknown event type: {}", event_type);
                        }
                    }
                }
            }
            Err(e) => {
                warn!("SSE stream error: {}", e);
                break;
            }
        }
    }

    // Connection lost — clean up
    warn!("SSE connection to {} lost", base_url);
    state.connected.store(false, Ordering::Relaxed);

    // Clear endpoint URL
    {
        let mut url = state.endpoint_url.lock().unwrap();
        *url = None;
    }

    // Fail all pending requests with an error response
    let pending_ids: Vec<Value> = state.pending.iter().map(|kv| kv.key().clone()).collect();
    for id in pending_ids {
        if let Some((_, sender)) = state.pending.remove(&id) {
            let _ = sender.send(serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "error": {
                    "code": -32603,
                    "message": "upstream SSE connection lost"
                }
            }));
        }
    }
}

/// Construct the full POST URL from a base URL and a relative path.
///
/// The `endpoint` event data is a relative path like `/messages/?session_id=abc123`.
/// This combines it with the base URL to get the full URL.
fn construct_endpoint_url(base_url: &str, relative_path: &str) -> String {
    if let Ok(base) = url::Url::parse(base_url) {
        if let Ok(full) = base.join(relative_path) {
            return full.to_string();
        }
    }
    // Fallback: simple concatenation
    format!(
        "{}{}",
        base_url.trim_end_matches('/'),
        relative_path
    )
}

#[derive(Debug)]
pub enum UpstreamError {
    Http(reqwest::Error),
    Status(u16, String),
    Parse(String),
    UnsupportedTransport(String),
    Timeout,
    NotConnected,
}

impl std::fmt::Display for UpstreamError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Http(e) => write!(f, "upstream http error: {e}"),
            Self::Status(code, body) => write!(f, "upstream returned {code}: {body}"),
            Self::Parse(e) => write!(f, "upstream parse error: {e}"),
            Self::UnsupportedTransport(t) => write!(f, "unsupported upstream transport: {t}"),
            Self::Timeout => write!(f, "upstream timeout"),
            Self::NotConnected => write!(f, "upstream not connected"),
        }
    }
}

impl std::error::Error for UpstreamError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_construct_endpoint_url() {
        let full = construct_endpoint_url("http://localhost:9091", "/messages/?session_id=abc123");
        assert_eq!(full, "http://localhost:9091/messages/?session_id=abc123");
    }

    #[test]
    fn test_construct_endpoint_url_trailing_slash() {
        let full = construct_endpoint_url("http://localhost:9091/", "/messages/?session_id=abc");
        assert_eq!(full, "http://localhost:9091/messages/?session_id=abc");
    }
}
