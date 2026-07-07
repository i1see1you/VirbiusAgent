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

/// Shared state for the axum HTTP server, passed to all handlers via `State`.
#[derive(Clone)]
pub struct AppState {
    pub session_mgr: Arc<SessionManager>,
    pub pipeline: SharedPipeline,
    pub upstream_mgr: Arc<UpstreamManager>,
    pub egress_client: EgressClient,
    pub egress_hosts: Arc<Vec<String>>,
    pub public_key_pem: Arc<String>,
    /// SSE sessions: session_id → channel to push JSON-RPC responses
    pub sse_sessions: Arc<DashMap<String, mpsc::Sender<Value>>>,
}

/// Create the axum router with all routes.
pub fn create_router(state: AppState) -> Router {
    Router::new()
        // MCP SSE protocol
        .route("/sse", get(handle_sse))
        .route("/messages/", post(handle_post_message))
        // Simple HTTP POST (for non-SSE clients and testing)
        .route("/", post(handle_simple_post))
        .with_state(state)
}

/// GET /sse — Establish SSE connection (MCP SSE server protocol).
///
/// Returns a `text/event-stream` response:
/// 1. First event: `endpoint` with the POST URL (`/messages/?session_id=xxx`)
/// 2. Subsequent events: `message` with JSON-RPC responses
async fn handle_sse(State(state): State<AppState>) -> Response {
    let session_id = uuid::Uuid::new_v4().to_string();

    let (tx, rx) = mpsc::channel::<Value>(64);

    state.sse_sessions.insert(session_id.clone(), tx);

    let endpoint_url = format!("/messages/?session_id={}", session_id);

    debug!("SSE session created: {}", session_id);

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

    // Touch session to update last_active (if session exists)
    state.session_mgr.touch(&session_id);

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
        )
        .await;

        if let Some(resp) = response {
            if sender.send(resp).await.is_err() {
                warn!(
                    "SSE session {} disconnected, response dropped",
                    session_id
                );
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
async fn handle_simple_post(
    State(state): State<AppState>,
    Json(request): Json<Value>,
) -> Response {
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
    )
    .await;

    match response {
        Some(resp) => Json(resp).into_response(),
        None => StatusCode::OK.into_response(),
    }
}
