//! Streamable HTTP upstream client (MCP 2025-06-18 session model).
//!
//! One POST endpoint carries JSON-RPC messages. A request's reply arrives as
//! either a single JSON object (`application/json`) or a request-scoped SSE
//! stream (`text/event-stream`); notifications are answered with 202.
//!
//! Session lifecycle:
//! - `initialize` POST → `Mcp-Session-Id` response header (optional; a
//!   session-less server is valid) → `notifications/initialized`
//! - subsequent requests carry `Mcp-Session-Id` and `MCP-Protocol-Version`
//! - a 404 means the session expired → re-initialize once and retry
//! - `DELETE` on drop / session close
//!
//! An optional `GET` stream receives server-initiated notifications; servers
//! answering 404/405 simply have it disabled.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

use dashmap::DashMap;
use serde_json::{json, Value};
use tokio::sync::{mpsc, oneshot, Mutex as TokioMutex};
use tokio_util::sync::CancellationToken;
use tracing::{debug, warn};

use futures_util::StreamExt;

use crate::sse_util::SseParser;
use crate::upstream::UpstreamError;

/// Protocol revision this client advertises upstream.
pub const CLIENT_PROTOCOL_VERSION: &str = "2025-06-18";

const GET_STREAM_BACKOFF: [u64; 4] = [1, 2, 5, 10];

/// A server→client stream that stays silent this long is considered stale and
/// reconnected. Must not be the request timeout: legitimate streams can be
/// long-lived, they just cannot be *silent* forever.
const GET_STREAM_IDLE_TIMEOUT: Duration = Duration::from_secs(120);

/// Apply custom headers to a request builder.
///
/// Every request path (initialize, message POST, GET stream, DELETE) must go
/// through this so configured auth headers are never silently dropped.
fn apply_headers(
    headers: &BTreeMap<String, String>,
    mut req: reqwest::RequestBuilder,
) -> reqwest::RequestBuilder {
    for (k, v) in headers {
        req = req.header(k, v);
    }
    req
}

struct SessionState {
    session_id: Option<String>,
    protocol_version: String,
    /// `false` once the server answered GET with 404/405.
    get_stream_allowed: bool,
    get_stream_started: bool,
}

pub struct StreamableHttpUpstream {
    base_url: String,
    http_path: String,
    headers: BTreeMap<String, String>,
    /// Client without a total timeout, for responses that may stream.
    stream_http: reqwest::Client,
    /// Client with a total timeout, for short requests (DELETE).
    http: reqwest::Client,
    timeout: Duration,
    state: TokioMutex<SessionState>,
    init_lock: TokioMutex<()>,
    pending: DashMap<Value, oneshot::Sender<Value>>,
    cancels: DashMap<Value, CancellationToken>,
    notify: Option<mpsc::Sender<Value>>,
    ready: AtomicBool,
    closed: AtomicBool,
}

impl StreamableHttpUpstream {
    pub fn new(
        base_url: String,
        http_path: String,
        headers: BTreeMap<String, String>,
        stream_http: reqwest::Client,
        http: reqwest::Client,
        timeout: Duration,
        notify: Option<mpsc::Sender<Value>>,
    ) -> Self {
        Self {
            base_url,
            http_path,
            headers,
            stream_http,
            http,
            timeout,
            state: TokioMutex::new(SessionState {
                session_id: None,
                protocol_version: CLIENT_PROTOCOL_VERSION.to_string(),
                get_stream_allowed: true,
                get_stream_started: false,
            }),
            init_lock: TokioMutex::new(()),
            pending: DashMap::new(),
            cancels: DashMap::new(),
            notify,
            ready: AtomicBool::new(false),
            closed: AtomicBool::new(false),
        }
    }

    pub fn url(&self) -> &str {
        &self.base_url
    }

    pub fn is_connected(&self) -> bool {
        self.ready.load(Ordering::Relaxed) && !self.closed.load(Ordering::Relaxed)
    }

    fn endpoint(&self) -> String {
        format!("{}{}", self.base_url.trim_end_matches('/'), self.http_path)
    }

    fn next_id() -> u64 {
        static NEXT: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);
        NEXT.fetch_add(1, Ordering::Relaxed)
    }

    async fn request_headers(&self) -> (Option<String>, String) {
        let st = self.state.lock().await;
        (st.session_id.clone(), st.protocol_version.clone())
    }

    /// Apply configured custom headers to an outgoing request.
    fn with_headers(&self, req: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        apply_headers(&self.headers, req)
    }

    /// Establish the session once (single-flight under `init_lock`).
    pub async fn ensure_initialized(self: &Arc<Self>) -> Result<(), UpstreamError> {
        if self.ready.load(Ordering::Relaxed) {
            return Ok(());
        }
        let _guard = self.init_lock.lock().await;
        if self.ready.load(Ordering::Relaxed) {
            return Ok(());
        }

        let req = json!({
            "jsonrpc": "2.0",
            "id": Self::next_id(),
            "method": "initialize",
            "params": {
                "protocolVersion": CLIENT_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {
                    "name": "virbius-mcp-proxy",
                    "version": env!("CARGO_PKG_VERSION")
                }
            }
        });

        let (resp, session_id) = self.post_initialize(&req).await?;
        let negotiated = resp
            .get("result")
            .and_then(|r| r.get("protocolVersion"))
            .and_then(Value::as_str)
            .unwrap_or(CLIENT_PROTOCOL_VERSION)
            .to_string();

        // MCP handshake continues with a notification.
        self.post_message(&json!({
            "jsonrpc": "2.0",
            "method": "notifications/initialized"
        }))
        .await?;

        {
            let mut st = self.state.lock().await;
            st.session_id = session_id;
            st.protocol_version = negotiated.clone();
        }
        self.ready.store(true, Ordering::Relaxed);
        debug!(
            "streamable upstream initialized: {} (protocol {})",
            self.base_url, negotiated
        );
        self.spawn_get_stream();
        Ok(())
    }

    async fn invalidate(&self) {
        self.ready.store(false, Ordering::Relaxed);
        let mut st = self.state.lock().await;
        st.session_id = None;
    }

    async fn post_initialize(&self, req: &Value) -> Result<(Value, Option<String>), UpstreamError> {
        let builder = self
            .stream_http
            .post(self.endpoint())
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json");
        // `stream_http` has no total timeout; bound the header phase explicitly.
        let resp = tokio::time::timeout(self.timeout, self.with_headers(builder).json(req).send())
            .await
            .map_err(|_| UpstreamError::Timeout)?
            .map_err(UpstreamError::Http)?;

        let status = resp.status();
        if !status.is_success() {
            let body = tokio::time::timeout(self.timeout, resp.text())
                .await
                .map_err(|_| UpstreamError::Timeout)?
                .unwrap_or_default();
            warn!("streamable initialize returned {}: {}", status, body);
            return Err(UpstreamError::Status(status.as_u16(), body));
        }

        let session_id = resp
            .headers()
            .get("mcp-session-id")
            .and_then(|v| v.to_str().ok())
            .map(str::to_string);

        let value = read_single_message(resp, self.timeout).await?;
        Ok((value, session_id))
    }

    /// POST a message and return the raw response for the caller to consume.
    async fn post_raw(&self, body: &Value) -> Result<reqwest::Response, UpstreamError> {
        let (session_id, version) = self.request_headers().await;
        let mut req = self
            .stream_http
            .post(self.endpoint())
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", version);
        if let Some(sid) = session_id {
            req = req.header("Mcp-Session-Id", sid);
        }
        tokio::time::timeout(self.timeout, self.with_headers(req).json(body).send())
            .await
            .map_err(|_| UpstreamError::Timeout)?
            .map_err(UpstreamError::Http)
    }

    async fn post_message(&self, body: &Value) -> Result<(), UpstreamError> {
        let resp = self.post_raw(body).await?;
        let status = resp.status();
        if !status.is_success() {
            let text = tokio::time::timeout(self.timeout, resp.text())
                .await
                .map_err(|_| UpstreamError::Timeout)?
                .unwrap_or_default();
            warn!("streamable notification returned {}: {}", status, text);
            return Err(UpstreamError::Status(status.as_u16(), text));
        }
        // Notifications are acknowledged with 202 and no body; ignore any body.
        Ok(())
    }

    /// Forward a notification (no response expected; 202 accepted).
    pub async fn forward_notification(
        self: &Arc<Self>,
        request: &Value,
    ) -> Result<(), UpstreamError> {
        self.ensure_initialized().await?;
        self.post_message(request).await
    }

    pub async fn forward(self: &Arc<Self>, request: &Value) -> Result<Value, UpstreamError> {
        self.ensure_initialized().await?;

        let id = request.get("id").cloned().unwrap_or(Value::Null);
        let token = CancellationToken::new();
        self.cancels.insert(id.clone(), token.clone());
        let (tx, rx) = oneshot::channel();
        self.pending.insert(id.clone(), tx);

        let result = self.forward_once(request, &id, &token, rx).await;

        self.cancels.remove(&id);
        self.pending.remove(&id);
        result
    }

    async fn forward_once(
        self: &Arc<Self>,
        request: &Value,
        id: &Value,
        token: &CancellationToken,
        mut rx: oneshot::Receiver<Value>,
    ) -> Result<Value, UpstreamError> {
        for attempt in 0..2 {
            let resp = self.post_raw(request).await?;
            let status = resp.status();

            if status.as_u16() == 404 && attempt == 0 {
                // Session expired (or server restarted): re-initialize once.
                debug!("streamable session invalid (404); re-initializing");
                self.invalidate().await;
                self.ensure_initialized().await?;
                continue;
            }
            if !status.is_success() {
                let body = tokio::time::timeout(self.timeout, resp.text())
                    .await
                    .map_err(|_| UpstreamError::Timeout)?
                    .unwrap_or_default();
                warn!("streamable forward returned {}: {}", status, body);
                return Err(UpstreamError::Status(status.as_u16(), body));
            }

            if status.as_u16() == 202 {
                // Accepted without a body — the reply may arrive on the GET
                // stream, so wait for the pending response.
                return tokio::select! {
                    _ = token.cancelled() => Err(UpstreamError::Cancelled),
                    r = tokio::time::timeout(self.timeout, &mut rx) => {
                        match r {
                            Ok(Ok(value)) => Ok(value),
                            _ => Err(UpstreamError::Timeout),
                        }
                    }
                };
            }

            let content_type = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .to_string();

            if content_type.contains("text/event-stream") {
                return self.consume_sse_response(resp, id, token, &mut rx).await;
            }

            // Single JSON message.
            let bytes = tokio::time::timeout(self.timeout, resp.bytes())
                .await
                .map_err(|_| UpstreamError::Timeout)?
                .map_err(UpstreamError::Http)?;
            let value: Value = serde_json::from_slice(&bytes)
                .map_err(|e| UpstreamError::Parse(format!("streamable json response: {e}")))?;
            return Ok(value);
        }
        Err(UpstreamError::NotConnected)
    }

    async fn consume_sse_response(
        &self,
        resp: reqwest::Response,
        id: &Value,
        token: &CancellationToken,
        rx: &mut oneshot::Receiver<Value>,
    ) -> Result<Value, UpstreamError> {
        let mut stream = resp.bytes_stream();
        let mut parser = SseParser::new();
        let deadline = tokio::time::Instant::now() + self.timeout;

        loop {
            tokio::select! {
                _ = token.cancelled() => return Err(UpstreamError::Cancelled),
                _ = tokio::time::sleep_until(deadline) => return Err(UpstreamError::Timeout),
                chunk = stream.next() => {
                    match chunk {
                        Some(Ok(bytes)) => {
                            for event in parser.push(&bytes) {
                                if event.event != "message" {
                                    continue;
                                }
                                let Ok(msg) = serde_json::from_str::<Value>(&event.data) else {
                                    warn!("streamable sse: unparsable message");
                                    continue;
                                };
                                match msg.get("id").cloned() {
                                    Some(msg_id) if &msg_id == id => return Ok(msg),
                                    Some(msg_id) => self.deliver(&msg_id, msg),
                                    None => self.notify(msg),
                                }
                            }
                        }
                        Some(Err(e)) => return Err(UpstreamError::Http(e)),
                        None => break,
                    }
                }
            }
        }

        // Stream closed before our reply: the reply may still arrive on the
        // GET stream.
        if let Ok(Ok(value)) = tokio::time::timeout(self.timeout, rx).await {
            return Ok(value);
        }
        Err(UpstreamError::Timeout)
    }

    fn deliver(&self, id: &Value, msg: Value) {
        if let Some((_, tx)) = self.pending.remove(id) {
            let _ = tx.send(msg);
        }
    }

    fn notify(&self, msg: Value) {
        if let Some(tx) = &self.notify {
            if tx.try_send(msg.clone()).is_err() {
                debug!("streamable notification dropped (sink full/closed)");
            }
        } else {
            debug!("streamable upstream notification (no sink): {msg}");
        }
    }

    /// Abort an in-flight request (downstream `notifications/cancelled`).
    pub fn cancel(&self, id: &Value) {
        if let Some((_, token)) = self.cancels.remove(id) {
            token.cancel();
        }
    }

    /// Optional server→client stream. Servers answering 404/405 disable it.
    fn spawn_get_stream(self: &Arc<Self>) {
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            return;
        };
        {
            let Ok(mut st) = self.state.try_lock() else {
                return;
            };
            if st.get_stream_started || !st.get_stream_allowed {
                return;
            }
            st.get_stream_started = true;
        }

        let weak = Arc::downgrade(self);
        handle.spawn(async move {
            let Some(this) = weak.upgrade() else { return };
            let mut attempt = 0usize;
            loop {
                let (sid, version) = this.request_headers().await;
                let Some(sid) = sid else { return };

                let req = this
                    .stream_http
                    .get(this.endpoint())
                    .header("Accept", "text/event-stream")
                    .header("Mcp-Session-Id", sid)
                    .header("MCP-Protocol-Version", version);
                let req = apply_headers(&this.headers, req);

                let sent = tokio::time::timeout(this.timeout, req.send()).await;
                match sent {
                    Err(_) => debug!("streamable GET stream connect timeout"),
                    Ok(Err(e)) => debug!("streamable GET stream error: {e}"),
                    Ok(Ok(resp)) if matches!(resp.status().as_u16(), 404 | 405) => {
                        debug!(
                            "streamable GET stream not supported ({}); disabled",
                            resp.status()
                        );
                        this.state.lock().await.get_stream_allowed = false;
                        return;
                    }
                    Ok(Ok(resp)) if !resp.status().is_success() => {
                        debug!("streamable GET stream status {}", resp.status());
                    }
                    Ok(Ok(resp)) => {
                        attempt = 0;
                        let mut stream = resp.bytes_stream();
                        let mut parser = SseParser::new();
                        loop {
                            let next =
                                tokio::time::timeout(GET_STREAM_IDLE_TIMEOUT, stream.next()).await;
                            match next {
                                Err(_) => {
                                    debug!("streamable GET stream idle timeout");
                                    break;
                                }
                                Ok(Some(Ok(bytes))) => {
                                    for event in parser.push(&bytes) {
                                        if event.event != "message" {
                                            continue;
                                        }
                                        let Ok(msg) = serde_json::from_str::<Value>(&event.data)
                                        else {
                                            continue;
                                        };
                                        match msg.get("id").cloned() {
                                            Some(msg_id) => this.deliver(&msg_id, msg),
                                            None => this.notify(msg),
                                        }
                                    }
                                }
                                Ok(Some(Err(_))) | Ok(None) => break,
                            }
                        }
                    }
                }

                if !this.state.lock().await.get_stream_allowed {
                    return;
                }
                let delay = GET_STREAM_BACKOFF[attempt.min(GET_STREAM_BACKOFF.len() - 1)];
                attempt += 1;
                tokio::time::sleep(Duration::from_secs(delay)).await;

                if weak.upgrade().is_none() {
                    return;
                }
            }
        });
    }

    /// Best-effort session termination (spawns `DELETE` when a runtime exists).
    pub fn shutdown(&self) {
        if self.closed.swap(true, Ordering::Relaxed) {
            return;
        }
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            return;
        };
        let Ok(st) = self.state.try_lock() else {
            return;
        };
        let Some(sid) = st.session_id.clone() else {
            return;
        };
        let version = st.protocol_version.clone();
        drop(st);

        let http = self.http.clone();
        let url = self.endpoint();
        let headers = self.headers.clone();
        handle.spawn(async move {
            let mut req = http
                .delete(&url)
                .header("Mcp-Session-Id", sid)
                .header("MCP-Protocol-Version", version);
            req = apply_headers(&headers, req);
            if let Err(e) = req.send().await {
                debug!("streamable DELETE failed: {e}");
            }
        });
    }
}

/// Read one JSON-RPC message from a response that may be JSON or an SSE stream
/// (initialize replies are normally JSON).
async fn read_single_message(
    resp: reqwest::Response,
    timeout: Duration,
) -> Result<Value, UpstreamError> {
    let content_type = resp
        .headers()
        .get(reqwest::header::CONTENT_TYPE)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();

    if content_type.contains("text/event-stream") {
        let mut stream = resp.bytes_stream();
        let mut parser = SseParser::new();
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            tokio::select! {
                _ = tokio::time::sleep_until(deadline) => return Err(UpstreamError::Timeout),
                chunk = stream.next() => {
                    match chunk {
                        Some(Ok(bytes)) => {
                            for event in parser.push(&bytes) {
                                if event.event == "message" && !event.data.is_empty() {
                                    return serde_json::from_str::<Value>(&event.data)
                                        .map_err(|e| UpstreamError::Parse(format!("sse message: {e}")));
                                }
                            }
                        }
                        Some(Err(e)) => return Err(UpstreamError::Http(e)),
                        None => return Err(UpstreamError::NotConnected),
                    }
                }
            }
        }
    }

    let bytes = tokio::time::timeout(timeout, resp.bytes())
        .await
        .map_err(|_| UpstreamError::Timeout)?
        .map_err(UpstreamError::Http)?;
    serde_json::from_slice(&bytes).map_err(|e| UpstreamError::Parse(format!("json response: {e}")))
}
