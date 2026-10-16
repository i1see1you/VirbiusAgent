/// Transport layer: stdio + axum-based HTTP/SSE server.
///
/// Two transport modes:
/// - **stdio**: newline-delimited JSON-RPC over stdin/stdout (for Claude Desktop, etc.)
/// - **SSE/HTTP**: axum HTTP server supporting both MCP SSE protocol and simple HTTP POST
///
/// The HTTP server exposes three routes:
/// - `GET /sse` — MCP SSE server: establishes SSE long connection, sends `endpoint` event
/// - `POST /messages/?session_id=xxx` — MCP SSE protocol: receives JSON-RPC, returns 202
/// - `POST /` — Simple HTTP: JSON-RPC request → JSON response (for non-SSE clients)
///
/// Session lifecycle is decoupled from TCP connections:
/// - `session_id` (UUID) is the logical session key throughout the proxy
/// - Sessions persist across TCP reconnects (within TTL)
/// - Upstream connections are per-session (via UpstreamManager)
use std::sync::Arc;
use std::time::Duration;

use axum::{
    extract::{Query, State},
    http::StatusCode,
    response::{
        sse::{Event, Sse},
        IntoResponse, Response,
    },
    routing::{get, post},
    Json, Router,
};
use dashmap::DashMap;
use serde::Deserialize;
use serde_json::Value;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::StreamExt as _;
use tracing::{debug, warn};

use crate::egress::EgressClient;
use crate::pipeline::SharedPipeline;
use crate::router;
use crate::session::SessionManager;
use crate::trace_collector::SharedTraceCollector;
use crate::upstream::UpstreamManager;

// ─── Stdio Transport ─────────────────────────────────────────

/// stdio transport: reads JSON-RPC messages from stdin (newline-delimited),
/// writes to stdout.
pub struct StdioTransport {
    stdin_rx: tokio::sync::mpsc::Receiver<Value>,
    stdout_tx: tokio::sync::mpsc::Sender<String>,
}

impl StdioTransport {
    pub fn new() -> (Self, tokio::task::JoinHandle<()>) {
        let (stdin_tx, stdin_rx) = tokio::sync::mpsc::channel::<Value>(64);
        let (stdout_tx, stdout_rx) = tokio::sync::mpsc::channel::<String>(64);

        // Spawn stdin reader
        tokio::spawn(async move {
            let stdin = tokio::io::stdin();
            let reader = BufReader::new(stdin);
            let mut lines = reader.lines();

            loop {
                match lines.next_line().await {
                    Ok(Some(line)) => {
                        if line.trim().is_empty() {
                            continue;
                        }
                        match serde_json::from_str::<Value>(&line) {
                            Ok(v) => {
                                if stdin_tx.send(v).await.is_err() {
                                    break;
                                }
                            }
                            Err(e) => {
                                warn!("stdin parse error: {e}");
                            }
                        }
                    }
                    Ok(None) => {
                        debug!("stdin EOF, transport closed");
                        break;
                    }
                    Err(e) => {
                        warn!("stdin read error: {e}");
                        break;
                    }
                }
            }
        });

        // Spawn stdout writer
        let writer_handle = tokio::spawn(async move {
            let mut stdout = tokio::io::stdout();
            let mut rx = stdout_rx;
            while let Some(line) = rx.recv().await {
                if stdout.write_all(line.as_bytes()).await.is_err() {
                    break;
                }
                if stdout.write_all(b"\n").await.is_err() {
                    break;
                }
                let _ = stdout.flush().await;
            }
        });

        let transport = Self {
            stdin_rx,
            stdout_tx,
        };

        (transport, writer_handle)
    }

    pub async fn send(&self, message: &Value) {
        let json = serde_json::to_string(message).unwrap_or_default();
        let _ = self.stdout_tx.send(json).await;
    }

    pub async fn recv(&mut self) -> Option<Value> {
        self.stdin_rx.recv().await
    }
}

// ─── HTTP/SSE Server (axum-based) ────────────────────────────

/// Static, config-derived facts served on `GET /status` (operator surface:
/// "what is this proxy and how is it wired"), plus process start time.
#[derive(Clone)]
pub struct StatusInfo {
    pub started_at: std::time::Instant,
    pub listen: String,
    /// (upstream name, url)
    pub upstreams: Vec<(String, String)>,
    pub engine_url: String,
    pub control_url: String,
    /// Empty when the evaluate-token exchange is disabled.
    pub auth_control_url: String,
    pub audit_backend: String,
    pub trace_backend: String,
}

/// Shared state for the axum HTTP server, passed to all handlers via `State`.
#[derive(Clone)]
pub struct AppState {
    pub session_mgr: Arc<SessionManager>,
    pub pipeline: SharedPipeline,
    pub upstream_mgr: Arc<UpstreamManager>,
    pub egress_client: EgressClient,
    pub egress_hosts: Arc<Vec<String>>,
    pub public_key_pem: Arc<String>,
    /// Fallback License JWT loaded from config file (used when Agent doesn't
    /// pass `_meta.license_jwt` in `initialize`).
    pub fallback_license_jwt: Arc<String>,
    pub trace_collector: SharedTraceCollector,
    /// SSE sessions: session_id -> channel to push JSON-RPC responses
    pub sse_sessions: Arc<DashMap<String, mpsc::Sender<Value>>>,
    /// Transport connection ID -> logical session ID mapping
    pub conn_to_session: Arc<DashMap<String, String>>,
    /// Config snapshot + start time for `GET /status`.
    pub status: Arc<StatusInfo>,
}

/// Create the axum router with all routes.
pub fn create_router(state: AppState) -> Router {
    Router::new()
        // Health check endpoint (for Docker HEALTHCHECK and orchestration)
        .route("/health", get(handle_health))
        // Operator surface: config summary + fallback license + recent decisions
        .route("/status", get(handle_status))
        // MCP SSE protocol
        .route("/sse", get(handle_sse))
        .route("/messages/", post(handle_post_message))
        // Simple HTTP POST (for non-SSE clients and testing)
        .route("/", post(handle_simple_post))
        .with_state(state)
}

/// GET /health — Health check endpoint.
///
/// Returns 200 OK with a simple JSON body. Used by Docker HEALTHCHECK
/// and orchestrators (docker-compose, Kubernetes) to determine liveness.
async fn handle_health() -> impl IntoResponse {
    Json(serde_json::json!({"status": "ok"}))
}

/// GET /status — operator-facing snapshot: how the proxy is wired, whether the
/// fallback license verifies, and the most recent decisions (including allows
/// the audit sampling dropped). Read-only; never exposes secrets (no JWT, no
/// key material, no broker credentials).
async fn handle_status(State(state): State<AppState>) -> impl IntoResponse {
    let s = &state.status;
    let license = fallback_license_summary(&state);
    Json(serde_json::json!({
        "version": env!("CARGO_PKG_VERSION"),
        "uptime_secs": s.started_at.elapsed().as_secs(),
        "transport": s.listen,
        "upstreams": s.upstreams.iter()
            .map(|(name, url)| serde_json::json!({"name": name, "url": url}))
            .collect::<Vec<_>>(),
        "engine_url": s.engine_url,
        "control_url": s.control_url,
        "evaluate_token_auth": {
            "enabled": !s.auth_control_url.is_empty(),
            "control_url": s.auth_control_url,
        },
        "audit_backend": s.audit_backend,
        "trace_backend": s.trace_backend,
        "sessions_active": state.session_mgr.len(),
        "fallback_license": license,
        "recent_decisions": crate::audit::recent_decisions(),
    }))
}

/// Decode (and, when a public key is configured, verify) the fallback license
/// for display. Claims are decoded without verification first because
/// `License::verify` needs the expected `app_id` as input.
fn fallback_license_summary(state: &AppState) -> Value {
    let jwt = state.fallback_license_jwt.as_str();
    if jwt.is_empty() {
        return Value::Null;
    }
    let Some(claims) = crate::session::extract_claims_from_jwt(jwt) else {
        return serde_json::json!({"decoded": false, "error": "malformed JWT"});
    };
    let app_id = claims.get("app_id").and_then(|v| v.as_str()).unwrap_or("");
    let exp = claims.get("exp").and_then(|v| v.as_i64()).unwrap_or(0);
    let verified = match virbius_core::License::verify(jwt, &state.public_key_pem, app_id) {
        Ok(_) => serde_json::json!({"ok": true}),
        Err(e) => serde_json::json!({"ok": false, "error": e.to_string()}),
    };
    serde_json::json!({
        "decoded": true,
        "app_id": app_id,
        "tenant_id": claims.get("tenant_id").and_then(|v| v.as_str()),
        "allowed_tools": claims.get("allowed_tools").cloned().unwrap_or(serde_json::json!([])),
        "expires_at": exp,
        "verified": verified,
    })
}

/// GET /sse — Establish SSE connection (MCP SSE server protocol).
///
/// Returns a `text/event-stream` response:
/// 1. First event: `endpoint` with the POST URL (`/messages/?session_id=xxx`)
/// 2. Subsequent events: `message` with JSON-RPC responses
///
/// When the client disconnects (drops the SSE stream), a background monitoring
/// task detects the dropped receiver and proactively cleans up:
/// - SSE session channel removed from `sse_sessions`
/// - Logical session removed from `session_mgr`
/// - Upstream connection removed from `upstream_mgr`
async fn handle_sse(State(state): State<AppState>) -> Response {
    let session_id = uuid::Uuid::new_v4().to_string();

    let (tx, rx) = mpsc::channel::<Value>(64);

    state.sse_sessions.insert(session_id.clone(), tx.clone());

    let endpoint_url = format!("/messages/?session_id={}", session_id);

    debug!("SSE session created: {}", session_id);

    // Spawn disconnection monitor: detects when the SSE receiver (held by the
    // response stream) is dropped, then cleans up per-connection resources.
    // The Session itself is NOT removed - it survives for reconnect within TTL.
    {
        let mon_conn_id = session_id.clone();
        let mon_sse = state.sse_sessions.clone();
        let mon_conn_to_session = state.conn_to_session.clone();
        let mon_upstream_mgr = state.upstream_mgr.clone();
        tokio::spawn(async move {
            // tx.closed() completes when the Receiver (rx) is dropped,
            // which happens when axum drops the SSE response (client disconnect).
            tx.closed().await;
            debug!(
                "SSE connection {} disconnected, cleaning up resources",
                mon_conn_id
            );

            // Resolve logical session ID before removing mapping
            let logical = mon_conn_to_session
                .get(&mon_conn_id)
                .map(|e| e.value().clone());

            // Immediately clean up SSE channel and transport mapping
            // (old transport_id is useless after disconnect)
            mon_sse.remove(&mon_conn_id);
            mon_conn_to_session.remove(&mon_conn_id);

            // Delay upstream cleanup by 10s to allow quick reconnect.
            // If the client reconnects within the grace period, handle_initialize
            // will rebind a new transport_id -> logical_sid mapping, and the
            // upstream connection can be reused (skip re-initialize).
            // session_mgr entry stays for reconnect TTL window (30min).
            if let Some(logical) = logical {
                let delay_conn_to_session = mon_conn_to_session.clone();
                let delay_upstream_mgr = mon_upstream_mgr.clone();
                let delay_logical = logical;
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_secs(10)).await;
                    // Check if client reconnected: any new transport_id
                    // mapped to the same logical session?
                    let rebound = delay_conn_to_session
                        .iter()
                        .any(|entry| entry.value() == &delay_logical);
                    if !rebound {
                        debug!(
                            "Grace period expired for session {}, cleaning up upstream",
                            delay_logical
                        );
                        delay_upstream_mgr.remove(&delay_logical);
                    } else {
                        debug!(
                            "Session {} reconnected within grace period, keeping upstream",
                            delay_logical
                        );
                    }
                });
            }
        });
    }

    // Create stream: first the endpoint event, then message events from channel
    let endpoint_stream = futures_util::stream::once(async {
        Ok::<_, std::io::Error>(Event::default().event("endpoint").data(endpoint_url))
    });

    let message_stream = ReceiverStream::new(rx).map(|msg| {
        let json = serde_json::to_string(&msg).unwrap_or_default();
        Ok::<_, std::io::Error>(Event::default().event("message").data(json))
    });

    let stream = endpoint_stream.chain(message_stream);

    Sse::new(stream).into_response()
}

/// POST /messages/?session_id=xxx — Receive JSON-RPC request (MCP SSE protocol).
///
/// Returns 202 Accepted immediately. The response is pushed asynchronously
/// via the SSE stream associated with the session_id.
#[derive(Deserialize)]
struct MessageQuery {
    session_id: String,
}

async fn handle_post_message(
    State(state): State<AppState>,
    Query(query): Query<MessageQuery>,
    Json(request): Json<Value>,
) -> Response {
    let session_id = query.session_id;

    // Look up SSE session
    let sender = match state.sse_sessions.get(&session_id) {
        Some(s) => s.clone(),
        None => return StatusCode::NOT_FOUND.into_response(),
    };

    // Check if this is a tools/call or tools/list without prior initialize
    // Some MCP clients (like older OpenClaw versions) skip the initialize step
    let method = request.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let needs_init = (method == "tools/call" || method == "tools/list")
        && !state.conn_to_session.contains_key(&session_id);

    if needs_init {
        // Auto-initialize the session to be compatible with clients that skip initialize
        debug!(
            "Auto-initializing session {} (client skipped initialize)",
            session_id
        );
        let init_params = serde_json::json!({
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "openclaw-auto", "version": "0.1.0"}
        });
        let init_id = Value::from(0);
        let _ = router::handle_initialize(
            &init_id,
            &init_params,
            &session_id,
            &state.session_mgr,
            &state.upstream_mgr,
            &state.public_key_pem,
            &state.fallback_license_jwt,
            &state.conn_to_session,
        )
        .await;
    }

    // Spawn task to route the request and send response via SSE
    let state_clone = state.clone();
    tokio::spawn(async move {
        let response = router::route_request(
            &request,
            &session_id,
            &state_clone.session_mgr,
            &state_clone.upstream_mgr,
            &state_clone.pipeline,
            &state_clone.egress_client,
            &state_clone.egress_hosts,
            &state_clone.public_key_pem,
            &state_clone.fallback_license_jwt,
            &state_clone.trace_collector,
            &state_clone.conn_to_session,
        )
        .await;

        if let Some(resp) = response {
            if sender.send(resp).await.is_err() {
                warn!("SSE session {} disconnected, response dropped", session_id);
                // Clean up SSE channel
                state_clone.sse_sessions.remove(&session_id);
            }
        }
    });

    StatusCode::ACCEPTED.into_response()
}

/// POST / — Simple JSON-RPC request/response (for non-SSE clients).
///
/// Returns the JSON-RPC response directly in the HTTP body.
/// Uses a per-request session_id (stateless), suitable for testing.
async fn handle_simple_post(State(state): State<AppState>, Json(request): Json<Value>) -> Response {
    // For simple HTTP POST, generate a per-request session_id.
    // This is stateless — each request gets its own session.
    // For stateful usage, clients should use the SSE protocol.
    let session_id = uuid::Uuid::new_v4().to_string();

    let response = router::route_request(
        &request,
        &session_id,
        &state.session_mgr,
        &state.upstream_mgr,
        &state.pipeline,
        &state.egress_client,
        &state.egress_hosts,
        &state.public_key_pem,
        &state.fallback_license_jwt,
        &state.trace_collector,
        &state.conn_to_session,
    )
    .await;

    match response {
        Some(resp) => Json(resp).into_response(),
        None => StatusCode::OK.into_response(),
    }
}
