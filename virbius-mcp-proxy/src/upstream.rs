/// Upstream MCP client: connects to upstream MCP Server(s) via SSE transport.
///
/// Architecture:
/// - `LegacySseUpstream` — one instance per (session, upstream), manages a single
///   SSE connection to one upstream MCP Server.
/// - `UpstreamManager` — owns:
///   - `entries: Vec<UpstreamEntry>` — static upstream configs
///   - `connections: DashMap<(session_id, upstream_name), LegacySseUpstream>`
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

use crate::config::{UpstreamEntry, UpstreamGates};
use crate::net_guard;
use crate::policy::PolicyRegistry;
use crate::sse_util::SseParser;
use crate::upstream_stdio::StdioUpstream;
use crate::upstream_streamable::StreamableHttpUpstream;

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
pub struct LegacySseUpstream {
    base_url: String,
    sse_path: String,
    http: reqwest::Client,
    timeout: Duration,
    deny_private: bool,
    state: Arc<UpstreamState>,
}

impl LegacySseUpstream {
    pub fn new(config: UpstreamConfig) -> Self {
        Self::with_http(config, reqwest::Client::new(), false)
    }

    /// Create with a shared HTTP client (connection pool reuse).
    fn with_http(config: UpstreamConfig, http: reqwest::Client, deny_private: bool) -> Self {
        let timeout = Duration::from_secs(config.timeout_secs);
        Self {
            base_url: config.url,
            sse_path: config.sse_path,
            http,
            timeout,
            deny_private,
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
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .map_err(UpstreamError::Http)?;
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
        let deny_private = self.deny_private;
        tokio::spawn(async move {
            sse_reader(state, base_url, deny_private, resp).await;
        });

        // Wait for endpoint URL to be set by the background reader
        let deadline = tokio::time::Instant::now() + Duration::from_secs(10).min(self.timeout);
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

/// One connected upstream, regardless of transport.
#[derive(Clone)]
pub enum UpstreamHandle {
    LegacySse(LegacySseUpstream),
    Streamable(Arc<StreamableHttpUpstream>),
    Stdio(Arc<StdioUpstream>),
}

impl UpstreamHandle {
    pub async fn forward(&self, request: &Value) -> Result<Value, UpstreamError> {
        match self {
            Self::LegacySse(c) => c.forward(request).await,
            Self::Streamable(c) => c.forward(request).await,
            Self::Stdio(c) => c.forward(request).await,
        }
    }

    pub async fn forward_notification(&self, request: &Value) -> Result<(), UpstreamError> {
        match self {
            Self::LegacySse(c) => c.forward_notification(request).await,
            Self::Streamable(c) => c.forward_notification(request).await,
            Self::Stdio(c) => c.forward_notification(request).await,
        }
    }

    pub fn is_connected(&self) -> bool {
        match self {
            Self::LegacySse(c) => c.is_connected(),
            Self::Streamable(c) => c.is_connected(),
            Self::Stdio(c) => c.is_connected(),
        }
    }

    pub fn url(&self) -> &str {
        match self {
            Self::LegacySse(c) => c.url(),
            Self::Streamable(c) => c.url(),
            Self::Stdio(c) => c.url(),
        }
    }

    /// Effective transport of this handle (`sse` / `streamable-http`).
    pub fn transport(&self) -> &'static str {
        match self {
            Self::LegacySse(_) => "sse",
            Self::Streamable(_) => "streamable-http",
            Self::Stdio(_) => "stdio",
        }
    }

    /// Abort an in-flight request; only Streamable supports cancellation.
    pub fn cancel(&self, request_id: &Value) {
        match self {
            Self::Streamable(c) => c.cancel(request_id),
            Self::Stdio(c) => c.cancel(request_id),
            Self::LegacySse(_) => {}
        }
    }

    /// Best-effort session termination.
    pub fn shutdown(&self) {
        match self {
            Self::Streamable(c) => c.shutdown(),
            Self::Stdio(c) => c.shutdown(),
            Self::LegacySse(_) => {}
        }
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
    /// Process-local upstreams from toml. Used when the tenant has no pushed policy.
    entries: Vec<UpstreamEntry>,
    policies: Arc<PolicyRegistry>,
    /// session_id → tenant_id, so a new connection picks that tenant's upstream list.
    session_tenant: DashMap<String, String>,
    /// Shared HTTP client (connection pool reuse across sessions).
    http: reqwest::Client,
    /// Client without a total request timeout, for long-lived upstream
    /// streams (Streamable HTTP POST/GET). `http` would kill them at
    /// `timeout` because reqwest's timeout covers the response body.
    stream_http: reqwest::Client,
    timeout: Duration,
    /// Deployment gates (transport allowlist, SSRF policy, stdio restrictions).
    gates: UpstreamGates,
    /// Single-flight locks so concurrent callers cannot spawn two upstreams.
    connect_locks: DashMap<(String, String), Arc<TokioMutex<()>>>,
    /// Optional sink for upstream-initiated notifications.
    notify_tx: Option<tokio::sync::mpsc::Sender<Value>>,
    /// Detection outcome counters (`auto` transport).
    detected_sse: std::sync::atomic::AtomicU64,
    detected_streamable: std::sync::atomic::AtomicU64,
    /// (session_id, upstream_name) → connected handle
    connections: DashMap<(String, String), UpstreamHandle>,
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
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());
        let stream_http = reqwest::Client::builder()
            .connect_timeout(timeout)
            .redirect(reqwest::redirect::Policy::none())
            .build()
            .unwrap_or_else(|_| reqwest::Client::new());

        Self {
            entries,
            policies: Arc::new(PolicyRegistry::empty()),
            session_tenant: DashMap::new(),
            http,
            stream_http,
            timeout,
            gates: UpstreamGates::default(),
            connect_locks: DashMap::new(),
            notify_tx: None,
            detected_sse: std::sync::atomic::AtomicU64::new(0),
            detected_streamable: std::sync::atomic::AtomicU64::new(0),
            connections: DashMap::new(),
            tool_routes: DashMap::new(),
        }
    }

    /// Share the registry filled by the Redis policy subscriber.
    pub fn use_policies(&mut self, policies: Arc<PolicyRegistry>) {
        self.policies = policies;
    }

    /// Apply deployment gates (also used for SSRF checks before connecting).
    pub fn set_gates(&mut self, gates: UpstreamGates) {
        self.gates = gates;
    }

    /// Route upstream-initiated notifications to a logging drain task.
    /// Keeps legacy parity (notifications are observed, not forwarded to
    /// clients yet) while giving Streamable a real sink.
    pub fn enable_notification_logging(&mut self) {
        let (tx, mut rx) = tokio::sync::mpsc::channel::<Value>(64);
        self.notify_tx = Some(tx);
        if tokio::runtime::Handle::try_current().is_ok() {
            tokio::spawn(async move {
                while let Some(msg) = rx.recv().await {
                    debug!("upstream notification: {msg}");
                }
            });
        }
    }

    /// (legacy-SSE detections, Streamable-HTTP detections).
    pub fn detection_counts(&self) -> (u64, u64) {
        (
            self.detected_sse.load(std::sync::atomic::Ordering::Relaxed),
            self.detected_streamable
                .load(std::sync::atomic::Ordering::Relaxed),
        )
    }

    /// Remember which tenant a session belongs to before the first upstream connect.
    pub fn bind_session(&self, session_id: &str, tenant_id: &str) {
        if !tenant_id.is_empty() {
            self.session_tenant
                .insert(session_id.to_string(), tenant_id.to_string());
        }
    }

    fn entries_for(&self, session_id: &str) -> Vec<UpstreamEntry> {
        let tenant = self
            .session_tenant
            .get(session_id)
            .map(|t| t.clone())
            .unwrap_or_default();
        if let Some(policy) = self.policies.get(&tenant) {
            return policy.upstreams;
        }
        self.entries.clone()
    }

    /// Single-upstream check against the session's tenant policy, else the local toml.
    pub fn is_single_for(&self, session_id: &str) -> bool {
        self.entries_for(session_id).len() == 1
    }

    /// Upstream names for this session's tenant policy, else the local toml.
    pub fn names_for(&self, session_id: &str) -> Vec<String> {
        self.entries_for(session_id)
            .into_iter()
            .map(|e| e.name)
            .collect()
    }

    /// Backward-compatible constructor: create from a single `UpstreamConfig`.
    ///
    /// Internally wraps as a single-entry `Vec<UpstreamEntry>` with name "default".
    pub fn new_single(config: &UpstreamConfig) -> Self {
        let entry = UpstreamEntry {
            name: "default".to_string(),
            url: Some(config.url.clone()),
            sse_path: config.sse_path.clone(),
            ..Default::default()
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

    /// Get or create an upstream connection for a specific upstream.
    ///
    /// Single-flight: concurrent callers for the same (session, upstream) wait
    /// on one per-key lock, so a stdio child or an `initialize` handshake is
    /// never started twice.
    pub async fn get_or_connect(
        &self,
        session_id: &str,
        upstream_name: &str,
    ) -> Result<UpstreamHandle, UpstreamError> {
        let key = (session_id.to_string(), upstream_name.to_string());

        // Fast path: already exists and connected.
        if let Some(handle) = self.connections.get(&key) {
            if handle.is_connected() {
                return Ok(handle.clone());
            }
        }

        let lock = self
            .connect_locks
            .entry(key.clone())
            .or_insert_with(|| Arc::new(TokioMutex::new(())))
            .clone();
        let _guard = lock.lock().await;

        // Double-check under the lock (another caller may have connected).
        if let Some(handle) = self.connections.get(&key) {
            if handle.is_connected() {
                return Ok(handle.clone());
            }
        }

        // Find the upstream entry in the tenant policy (or local toml).
        // An already-connected handle above is left alone when the list changes.
        let entries = self.entries_for(session_id);
        let entry = entries
            .iter()
            .find(|e| e.name == upstream_name)
            .cloned()
            .ok_or_else(|| UpstreamError::UnknownUpstream(upstream_name.to_string()))?;

        let handle = self.build_handle(&entry).await?;
        self.connections.insert(key, handle.clone());
        debug!(
            "upstream connection created for session={}, upstream={}, transport={}",
            session_id,
            upstream_name,
            handle.transport()
        );
        Ok(handle)
    }

    /// Build and connect one handle according to the entry's transport.
    async fn build_handle(&self, entry: &UpstreamEntry) -> Result<UpstreamHandle, UpstreamError> {
        entry.validate().map_err(UpstreamError::UrlRejected)?;
        if !self.gates.transport_enabled(&entry.transport) {
            return Err(UpstreamError::UnsupportedTransport(format!(
                "{} (disabled by enabled_upstream_transports)",
                entry.transport
            )));
        }

        match entry.transport.as_str() {
            "stdio" => {
                let handle = self.build_stdio(entry).await?;
                Ok(UpstreamHandle::Stdio(handle))
            }
            "sse" => {
                self.detected_sse
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                Ok(UpstreamHandle::LegacySse(self.build_legacy(entry).await?))
            }
            "streamable-http" => {
                let handle = self.build_streamable(entry).await?;
                handle.ensure_initialized().await?;
                self.detected_streamable
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                Ok(UpstreamHandle::Streamable(handle))
            }
            "auto" => {
                // Try Streamable first; fall back to legacy SSE only when the
                // server clearly is not Streamable (404/405 or non-JSON body).
                let streamable = self.build_streamable(entry).await?;
                match streamable.ensure_initialized().await {
                    Ok(()) => {
                        self.detected_streamable
                            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        debug!("auto-detected streamable-http upstream '{}'", entry.name);
                        Ok(UpstreamHandle::Streamable(streamable))
                    }
                    Err(UpstreamError::Status(404 | 405, _) | UpstreamError::Parse(_)) => {
                        debug!(
                            "auto: '{}' is not Streamable HTTP, falling back to legacy SSE",
                            entry.name
                        );
                        self.detected_sse
                            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                        Ok(UpstreamHandle::LegacySse(self.build_legacy(entry).await?))
                    }
                    Err(e) => Err(e),
                }
            }
            other => Err(UpstreamError::UnsupportedTransport(other.to_string())),
        }
    }

    async fn build_legacy(
        &self,
        entry: &UpstreamEntry,
    ) -> Result<LegacySseUpstream, UpstreamError> {
        let url = entry.url.clone().ok_or_else(|| {
            UpstreamError::UnsupportedTransport(format!("{} (no url)", entry.transport))
        })?;
        net_guard::validate_url(&url, self.gates.deny_private)
            .await
            .map_err(UpstreamError::UrlRejected)?;
        let config = UpstreamConfig {
            url,
            transport: "sse".to_string(),
            timeout_secs: self
                .entry_timeout(entry)
                .map(|d| d.as_secs())
                .unwrap_or_else(|| self.timeout.as_secs()),
            sse_path: entry.sse_path.clone(),
        };
        let client =
            LegacySseUpstream::with_http(config, self.http.clone(), self.gates.deny_private);
        client.ensure_connected().await?;
        Ok(client)
    }

    async fn build_streamable(
        &self,
        entry: &UpstreamEntry,
    ) -> Result<Arc<StreamableHttpUpstream>, UpstreamError> {
        let url = entry.url.clone().ok_or_else(|| {
            UpstreamError::UnsupportedTransport(format!("{} (no url)", entry.transport))
        })?;
        net_guard::validate_url(&url, self.gates.deny_private)
            .await
            .map_err(UpstreamError::UrlRejected)?;
        Ok(Arc::new(StreamableHttpUpstream::new(
            url,
            entry.http_path.clone(),
            entry.headers.clone(),
            self.stream_http.clone(),
            self.http.clone(),
            self.entry_timeout(entry).unwrap_or(self.timeout),
            self.notify_tx.clone(),
        )))
    }

    async fn build_stdio(
        &self,
        entry: &UpstreamEntry,
    ) -> Result<Arc<StdioUpstream>, UpstreamError> {
        let command = entry.command.clone().ok_or_else(|| {
            UpstreamError::Spawn(format!("upstream '{}': no command", entry.name))
        })?;
        let handle = Arc::new(StdioUpstream::new(
            entry.name.clone(),
            command,
            entry.args.clone(),
            entry.env.clone(),
            entry.cwd.clone(),
            self.entry_timeout(entry).unwrap_or(self.timeout),
        ));
        handle.ensure_started().await?;
        Ok(handle)
    }

    fn entry_timeout(&self, entry: &UpstreamEntry) -> Option<Duration> {
        (entry.timeout_ms > 0).then(|| Duration::from_millis(entry.timeout_ms))
    }

    /// Convenience for single-upstream mode: connect to the sole upstream.
    ///
    /// In multi-upstream mode, returns an error.
    pub async fn get_or_connect_single(
        &self,
        session_id: &str,
    ) -> Result<UpstreamHandle, UpstreamError> {
        let entries = self.entries_for(session_id);
        if entries.len() != 1 {
            return Err(UpstreamError::MultiUpstreamMode);
        }
        let name = entries[0].name.clone();
        self.get_or_connect(session_id, &name).await
    }

    /// Connect to all upstreams concurrently for a session.
    ///
    /// Used during `initialize` in multi-upstream mode.
    pub async fn connect_all(
        &self,
        session_id: &str,
    ) -> Vec<Result<UpstreamHandle, UpstreamError>> {
        let names = self.names_for(session_id);
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
        self.route_tool_for("", displayed_name)
    }

    /// Like `route_tool`, but a pushed tenant policy can switch single/multi mode.
    pub fn route_tool_for(
        &self,
        session_id: &str,
        displayed_name: &str,
    ) -> Option<(String, String)> {
        let entries = self.entries_for(session_id);
        if entries.len() == 1 {
            return Some((entries[0].name.clone(), displayed_name.to_string()));
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

        self.session_tenant.remove(session_id);
        for key in &keys_to_remove {
            if let Some((_, handle)) = self.connections.remove(key) {
                handle.shutdown();
            }
            debug!(
                "upstream connection removed for session={}, upstream={}",
                key.0, key.1
            );
        }
        self.connect_locks.retain(|key, _| key.0 != session_id);
    }

    /// Abort an in-flight request after a downstream `notifications/cancelled`.
    pub fn cancel_request(&self, session_id: &str, request_id: &Value) {
        for entry in self.connections.iter() {
            if entry.key().0 == session_id {
                entry.value().cancel(request_id);
            }
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
async fn sse_reader(
    state: Arc<UpstreamState>,
    base_url: String,
    deny_private: bool,
    resp: reqwest::Response,
) {
    debug!("SSE reader started for {}", base_url);

    let mut stream = resp.bytes_stream();
    let mut parser = SseParser::new();

    while let Some(chunk_result) = stream.next().await {
        match chunk_result {
            Ok(chunk) => {
                for event in parser.push(&chunk) {
                    match event.event.as_str() {
                        "endpoint" => match construct_endpoint_url(&base_url, &event.data) {
                            Ok(full_url) => {
                                match net_guard::validate_url(&full_url, deny_private).await {
                                    Ok(()) => {
                                        debug!("SSE endpoint received: {}", full_url);
                                        let mut url = state.endpoint_url.lock().unwrap();
                                        *url = Some(full_url);
                                    }
                                    Err(e) => {
                                        warn!("SSE endpoint rejected ({e}): {full_url}");
                                    }
                                }
                            }
                            Err(e) => warn!("SSE endpoint rejected: {e}"),
                        },
                        "message" => {
                            if event.data.is_empty() {
                                continue;
                            }
                            // Parse JSON-RPC response and dispatch to pending request
                            match serde_json::from_str::<Value>(&event.data) {
                                Ok(json) => {
                                    let id = json.get("id").cloned().unwrap_or(Value::Null);
                                    if let Some((_, sender)) = state.pending.remove(&id) {
                                        let _ = sender.send(json);
                                    } else {
                                        debug!("SSE message with unmatched id: {:?}", id);
                                    }
                                }
                                Err(e) => {
                                    warn!("SSE message parse error: {} - data: {}", e, event.data);
                                }
                            }
                        }
                        _ => {
                            debug!("SSE unknown event type: {}", event.event);
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

/// Validate and normalize the POST endpoint URI announced by the upstream.
///
/// The `endpoint` event data may be a relative path (e.g.
/// `/messages/?session_id=abc123`) or an absolute URI, but it must stay on the
/// origin of the configured SSE URL: a hostile or compromised upstream must not
/// be able to redirect JSON-RPC payloads to another host.
fn construct_endpoint_url(base_url: &str, data: &str) -> Result<String, String> {
    if data.trim().is_empty() {
        return Err("empty endpoint uri".to_string());
    }
    let base =
        url::Url::parse(base_url).map_err(|e| format!("invalid base url '{base_url}': {e}"))?;
    let full = base
        .join(data)
        .map_err(|e| format!("invalid endpoint uri '{data}': {e}"))?;

    let same_origin = full.scheme() == base.scheme()
        && full.host_str() == base.host_str()
        && full.port_or_known_default() == base.port_or_known_default()
        && full.username() == base.username()
        && full.password() == base.password();
    if !same_origin {
        return Err(format!(
            "endpoint uri '{data}' is not same-origin with '{base_url}'"
        ));
    }
    if !matches!(full.scheme(), "http" | "https") {
        return Err(format!("unsupported endpoint scheme '{}'", full.scheme()));
    }
    Ok(full.to_string())
}

#[derive(Debug)]
pub enum UpstreamError {
    Http(reqwest::Error),
    Status(u16, String),
    Parse(String),
    UnsupportedTransport(String),
    Timeout,
    NotConnected,
    UrlRejected(String),
    Spawn(String),
    Cancelled,
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
            Self::UrlRejected(e) => write!(f, "upstream url rejected: {e}"),
            Self::Spawn(e) => write!(f, "upstream spawn failed: {e}"),
            Self::Cancelled => write!(f, "upstream request cancelled"),
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
        let full = construct_endpoint_url("http://localhost:9091", "/messages/?session_id=abc123")
            .expect("relative endpoint should be accepted");
        assert_eq!(full, "http://localhost:9091/messages/?session_id=abc123");
    }

    #[test]
    fn test_construct_endpoint_url_trailing_slash() {
        let full = construct_endpoint_url("http://localhost:9091/", "/messages/?session_id=abc")
            .expect("relative endpoint should be accepted");
        assert_eq!(full, "http://localhost:9091/messages/?session_id=abc");
    }

    #[test]
    fn test_construct_endpoint_url_absolute_same_origin() {
        let full = construct_endpoint_url(
            "http://localhost:9091",
            "http://localhost:9091/messages/?session_id=abc",
        )
        .expect("absolute same-origin endpoint should be accepted");
        assert_eq!(full, "http://localhost:9091/messages/?session_id=abc");
    }

    #[test]
    fn test_construct_endpoint_url_rejects_cross_origin() {
        for bad in [
            "http://evil.example/messages",
            "//evil.example/messages",
            "http://localhost:9092/messages",
            "https://localhost:9091/messages",
            "http://localhost:9091@evil.example/messages",
        ] {
            assert!(
                construct_endpoint_url("http://localhost:9091", bad).is_err(),
                "{bad} should have been rejected"
            );
        }
    }

    #[test]
    fn test_construct_endpoint_url_rejects_empty() {
        assert!(construct_endpoint_url("http://localhost:9091", "").is_err());
        assert!(construct_endpoint_url("http://localhost:9091", "   ").is_err());
    }

    #[test]
    fn test_cleanup_disconnected_removes_stale_connections() {
        let entries = vec![UpstreamEntry {
            name: "default".to_string(),
            url: Some("http://127.0.0.1:59999".to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
        }];
        let mgr = UpstreamManager::new(entries, 1);

        // Manually insert a client whose `connected` flag is false.
        let client_config = UpstreamConfig {
            url: "http://127.0.0.1:59999".to_string(),
            transport: "sse".to_string(),
            timeout_secs: 1,
            sse_path: "/sse".to_string(),
        };
        let disconnected_client = LegacySseUpstream::new(client_config);
        assert!(!disconnected_client.is_connected());

        mgr.connections.insert(
            ("stale-session".to_string(), "default".to_string()),
            UpstreamHandle::LegacySse(disconnected_client),
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
            url: Some("http://127.0.0.1:59999".to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
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
            url: Some("http://localhost:8080".to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
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
                url: Some("http://localhost:8081".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: Some("http://localhost:8082".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
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
                url: Some("http://localhost:8081".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: Some("http://localhost:8082".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
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
                url: Some("http://127.0.0.1:59999".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: Some("http://127.0.0.1:59998".to_string()),
                sse_path: "/sse".to_string(),
                ..Default::default()
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
            UpstreamHandle::LegacySse(LegacySseUpstream::new(cfg1)),
        );
        mgr.connections.insert(
            ("s1".to_string(), "gh".to_string()),
            UpstreamHandle::LegacySse(LegacySseUpstream::new(cfg2)),
        );
        mgr.connections.insert(
            ("s2".to_string(), "fs".to_string()),
            UpstreamHandle::LegacySse(LegacySseUpstream::new(cfg1_clone)),
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
