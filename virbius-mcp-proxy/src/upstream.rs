/// Upstream MCP client: connects to upstream MCP Server(s) via SSE transport.
///
/// Architecture:
/// - `UpstreamClient` — one instance per (session, upstream), manages a single
///   SSE connection to one upstream MCP Server.
/// - `UpstreamManager` — owns:
///   - `entries: Vec<UpstreamEntry>` — static upstream configs
///   - `connections: DashMap<(session_id, upstream_name), UpstreamClient>`
///   - `tool_routes: DashMap<displayed_tool_name, (upstream_name, original_tool_name)>`
///
/// Single-upstream mode (`entries.len() == 1`) is a fast path:
/// - No tool name prefixing
/// - `get_or_connect_single(session_id)` delegates to the sole upstream
/// - `route_tool()` always returns the single upstream
///
/// Multi-upstream mode (`entries.len() > 1`):
/// - `tools/list` merges tools from all upstreams
/// - Conflicting tool names are prefixed: `{upstream_name}__{tool_name}`
/// - `tools/call` routes via `tool_routes` to the correct upstream
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

use crate::config::UpstreamEntry;

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
/// Created by `UpstreamManager` on a per-(session, upstream) basis.
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
        let sse_url = format!("{}{}", self.base_url.trim_end_matches('/'), self.sse_path);
        debug!("connecting to upstream SSE: {}", sse_url);

        // SSE connections are long-lived; use a separate client without a
        // total request timeout so the stream is not killed after N seconds.
        let sse_http = reqwest::Client::builder()
            .build()
            .map_err(|e| UpstreamError::Http(e))?;
        let resp = sse_http
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

/// Manages per-(session, upstream) connections and tool routing.
///
/// In single-upstream mode (`entries.len() == 1`), behavior is identical
/// to the previous 1:1 model — no prefixing, no route lookups.
///
/// In multi-upstream mode, `tools/list` results are merged and conflicting
/// tool names are prefixed with `{upstream_name}__`. `tools/call` resolves
/// the tool name via `tool_routes` and forwards to the correct upstream.
pub struct UpstreamManager {
    /// Static upstream configurations.
    entries: Vec<UpstreamEntry>,
    /// Shared HTTP client (connection pool reuse across sessions).
    http: reqwest::Client,
    timeout: Duration,
    /// (session_id, upstream_name) → UpstreamClient
    connections: DashMap<(String, String), UpstreamClient>,
    /// displayed_tool_name → (upstream_name, original_tool_name)
    ///
    /// Populated during `tools/list` merge. Used by `tools/call` to route
    /// to the correct upstream and strip any prefix before forwarding.
    tool_routes: DashMap<String, (String, String)>,
}

impl UpstreamManager {
    /// Create a multi-upstream manager from a list of entries.
    pub fn new(entries: Vec<UpstreamEntry>, timeout_secs: u64) -> Self {
        let timeout = Duration::from_secs(timeout_secs);
        let http = reqwest::Client::builder()
            .timeout(timeout)
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());

        Self {
            entries,
            http,
            timeout,
            connections: DashMap::new(),
            tool_routes: DashMap::new(),
        }
    }

    /// Backward-compatible constructor: create from a single `UpstreamConfig`.
    ///
    /// Internally wraps as a single-entry `Vec<UpstreamEntry>` with name "default".
    pub fn new_single(config: &UpstreamConfig) -> Self {
        let entry = UpstreamEntry {
            name: "default".to_string(),
            url: config.url.clone(),
            sse_path: config.sse_path.clone(),
        };
        Self::new(vec![entry], config.timeout_secs)
    }

    /// Whether this manager is in single-upstream mode.
    pub fn is_single_upstream(&self) -> bool {
        self.entries.len() == 1
    }

    /// List of upstream names.
    pub fn upstream_names(&self) -> Vec<&str> {
        self.entries.iter().map(|e| e.name.as_str()).collect()
    }

    /// Get the sole upstream name (single-upstream mode only).
    fn single_name(&self) -> &str {
        &self.entries[0].name
    }

    /// Get or create an upstream connection for a specific upstream.
    ///
    /// On first call for a (session, upstream) pair, creates a new
    /// `UpstreamClient` and establishes the SSE connection.
    pub async fn get_or_connect(
        &self,
        session_id: &str,
        upstream_name: &str,
    ) -> Result<UpstreamClient, UpstreamError> {
        let key = (session_id.to_string(), upstream_name.to_string());

        // Fast path: already exists and connected
        if let Some(client) = self.connections.get(&key) {
            if client.is_connected() {
                return Ok(client.clone());
            }
        }

        // Find the upstream entry
        let entry = self
            .entries
            .iter()
            .find(|e| e.name == upstream_name)
            .ok_or_else(|| UpstreamError::UnknownUpstream(upstream_name.to_string()))?;

        // Slow path: create or reconnect
        let config = UpstreamConfig {
            url: entry.url.clone(),
            transport: "sse".to_string(),
            timeout_secs: self.timeout.as_secs(),
            sse_path: entry.sse_path.clone(),
        };

        let client = UpstreamClient::with_http(config, self.http.clone());
        client.ensure_connected().await?;
        self.connections.insert(key, client.clone());
        debug!(
            "upstream connection created for session={}, upstream={}",
            session_id, upstream_name
        );
        Ok(client)
    }

    /// Convenience for single-upstream mode: connect to the sole upstream.
    ///
    /// In multi-upstream mode, returns an error.
    pub async fn get_or_connect_single(
        &self,
        session_id: &str,
    ) -> Result<UpstreamClient, UpstreamError> {
        if !self.is_single_upstream() {
            return Err(UpstreamError::MultiUpstreamMode);
        }
        self.get_or_connect(session_id, self.single_name()).await
    }

    /// Connect to all upstreams concurrently for a session.
    ///
    /// Used during `initialize` in multi-upstream mode.
    pub async fn connect_all(
        &self,
        session_id: &str,
    ) -> Vec<Result<UpstreamClient, UpstreamError>> {
        let names: Vec<String> = self.entries.iter().map(|e| e.name.clone()).collect();
        let mut results = Vec::with_capacity(names.len());
        for name in &names {
            let r = self.get_or_connect(session_id, name).await;
            results.push(r);
        }
        results
    }

    /// Register a tool route: maps the displayed tool name to its upstream
    /// and original name.
    pub fn register_tool_route(
        &self,
        displayed_name: &str,
        upstream_name: &str,
        original_name: &str,
    ) {
        self.tool_routes.insert(
            displayed_name.to_string(),
            (upstream_name.to_string(), original_name.to_string()),
        );
    }

    /// Look up the route for a tool name.
    ///
    /// Returns `(upstream_name, original_tool_name)`.
    /// In single-upstream mode, always returns the sole upstream with the
    /// tool name unchanged.
    pub fn route_tool(&self, displayed_name: &str) -> Option<(String, String)> {
        if self.is_single_upstream() {
            return Some((self.single_name().to_string(), displayed_name.to_string()));
        }
        self.tool_routes.get(displayed_name).map(|r| r.clone())
    }

    /// Clear all tool routes (e.g., before re-fetching tools/list).
    pub fn clear_tool_routes(&self) {
        self.tool_routes.clear();
    }

    /// Remove all upstream connections for a session.
    ///
    /// Called when a session expires (TTL cleanup) or is explicitly closed.
    pub fn remove(&self, session_id: &str) {
        let keys_to_remove: Vec<(String, String)> = self
            .connections
            .iter()
            .filter(|entry| entry.key().0 == session_id)
            .map(|entry| entry.key().clone())
            .collect();

        for key in &keys_to_remove {
            self.connections.remove(key);
            debug!(
                "upstream connection removed for session={}, upstream={}",
                key.0, key.1
            );
        }
    }

    /// Number of active upstream connections (across all sessions and upstreams).
    pub fn len(&self) -> usize {
        self.connections.len()
    }

    /// Check if there are no active upstream connections.
    pub fn is_empty(&self) -> bool {
        self.connections.is_empty()
    }

    /// Remove all upstream connections whose SSE connection has been lost.
    ///
    /// Called by the background cleanup task to free resources from upstream
    /// SSE connections that have been dropped by the upstream MCP Server.
    pub fn cleanup_disconnected(&self) {
        let to_remove: Vec<(String, String)> = self
            .connections
            .iter()
            .filter(|entry| !entry.value().is_connected())
            .map(|entry| entry.key().clone())
            .collect();

        for key in &to_remove {
            self.connections.remove(key);
            debug!(
                "cleaned up disconnected upstream for session={}, upstream={}",
                key.0, key.1
            );
        }
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
    format!("{}{}", base_url.trim_end_matches('/'), relative_path)
}

#[derive(Debug)]
pub enum UpstreamError {
    Http(reqwest::Error),
    Status(u16, String),
    Parse(String),
    UnsupportedTransport(String),
    Timeout,
    NotConnected,
    UnknownUpstream(String),
    MultiUpstreamMode,
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
            Self::UnknownUpstream(name) => write!(f, "unknown upstream: {name}"),
            Self::MultiUpstreamMode => write!(f, "operation requires single-upstream mode"),
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

    #[test]
    fn test_cleanup_disconnected_removes_stale_connections() {
        let entries = vec![UpstreamEntry {
            name: "default".to_string(),
            url: "http://127.0.0.1:59999".to_string(),
            sse_path: "/sse".to_string(),
        }];
        let mgr = UpstreamManager::new(entries, 1);

        // Manually insert a client whose `connected` flag is false.
        let client_config = UpstreamConfig {
            url: "http://127.0.0.1:59999".to_string(),
            transport: "sse".to_string(),
            timeout_secs: 1,
            sse_path: "/sse".to_string(),
        };
        let disconnected_client = UpstreamClient::new(client_config);
        assert!(!disconnected_client.is_connected());

        mgr.connections.insert(
            ("stale-session".to_string(), "default".to_string()),
            disconnected_client,
        );
        assert_eq!(mgr.len(), 1);

        // Cleanup should remove the disconnected client
        mgr.cleanup_disconnected();
        assert_eq!(mgr.len(), 0);
    }

    #[test]
    fn test_cleanup_disconnected_keeps_connected() {
        let entries = vec![UpstreamEntry {
            name: "default".to_string(),
            url: "http://127.0.0.1:59999".to_string(),
            sse_path: "/sse".to_string(),
        }];
        let mgr = UpstreamManager::new(entries, 1);
        assert_eq!(mgr.len(), 0);
        mgr.cleanup_disconnected(); // Should be a no-op
        assert_eq!(mgr.len(), 0);
    }

    #[test]
    fn test_route_tool_single_upstream() {
        let entries = vec![UpstreamEntry {
            name: "default".to_string(),
            url: "http://localhost:8080".to_string(),
            sse_path: "/sse".to_string(),
        }];
        let mgr = UpstreamManager::new(entries, 30);
        assert!(mgr.is_single_upstream());

        // In single-upstream mode, route_tool always returns the sole upstream
        let route = mgr.route_tool("read_file").unwrap();
        assert_eq!(route.0, "default");
        assert_eq!(route.1, "read_file");
    }

    #[test]
    fn test_register_and_route_tool_multi_upstream() {
        let entries = vec![
            UpstreamEntry {
                name: "fs".to_string(),
                url: "http://localhost:8081".to_string(),
                sse_path: "/sse".to_string(),
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: "http://localhost:8082".to_string(),
                sse_path: "/sse".to_string(),
            },
        ];
        let mgr = UpstreamManager::new(entries, 30);
        assert!(!mgr.is_single_upstream());

        // Register routes
        mgr.register_tool_route("read_file", "fs", "read_file");
        mgr.register_tool_route("gh__read_file", "gh", "read_file");
        mgr.register_tool_route("create_issue", "gh", "create_issue");

        // Route lookups
        let r1 = mgr.route_tool("read_file").unwrap();
        assert_eq!(r1.0, "fs");
        assert_eq!(r1.1, "read_file");

        let r2 = mgr.route_tool("gh__read_file").unwrap();
        assert_eq!(r2.0, "gh");
        assert_eq!(r2.1, "read_file");

        let r3 = mgr.route_tool("create_issue").unwrap();
        assert_eq!(r3.0, "gh");
        assert_eq!(r3.1, "create_issue");

        // Unknown tool
        assert!(mgr.route_tool("unknown").is_none());

        // Clear routes
        mgr.clear_tool_routes();
        assert!(mgr.route_tool("read_file").is_none());
    }

    #[test]
    fn test_upstream_names() {
        let entries = vec![
            UpstreamEntry {
                name: "fs".to_string(),
                url: "http://localhost:8081".to_string(),
                sse_path: "/sse".to_string(),
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: "http://localhost:8082".to_string(),
                sse_path: "/sse".to_string(),
            },
        ];
        let mgr = UpstreamManager::new(entries, 30);
        let names = mgr.upstream_names();
        assert_eq!(names, vec!["fs", "gh"]);
    }

    #[test]
    fn test_remove_session_removes_all_upstreams() {
        let entries = vec![
            UpstreamEntry {
                name: "fs".to_string(),
                url: "http://127.0.0.1:59999".to_string(),
                sse_path: "/sse".to_string(),
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: "http://127.0.0.1:59998".to_string(),
                sse_path: "/sse".to_string(),
            },
        ];
        let mgr = UpstreamManager::new(entries, 1);

        // Insert disconnected clients for both upstreams
        let cfg1 = UpstreamConfig {
            url: "http://127.0.0.1:59999".to_string(),
            transport: "sse".to_string(),
            timeout_secs: 1,
            sse_path: "/sse".to_string(),
        };
        let cfg2 = UpstreamConfig {
            url: "http://127.0.0.1:59998".to_string(),
            transport: "sse".to_string(),
            timeout_secs: 1,
            sse_path: "/sse".to_string(),
        };
        let cfg1_clone = cfg1.clone();
        mgr.connections.insert(
            ("s1".to_string(), "fs".to_string()),
            UpstreamClient::new(cfg1),
        );
        mgr.connections.insert(
            ("s1".to_string(), "gh".to_string()),
            UpstreamClient::new(cfg2),
        );
        mgr.connections.insert(
            ("s2".to_string(), "fs".to_string()),
            UpstreamClient::new(cfg1_clone),
        );

        assert_eq!(mgr.len(), 3);

        // Remove session s1 — should remove 2 connections
        mgr.remove("s1");
        assert_eq!(mgr.len(), 1);

        // s2 should still have 1 connection
        mgr.remove("s2");
        assert_eq!(mgr.len(), 0);
    }
}
