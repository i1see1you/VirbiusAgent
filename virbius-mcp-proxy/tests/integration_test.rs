/// Integration tests for virbius-mcp-proxy.
///
/// These tests start a mock upstream MCP server (implementing the MCP SSE
/// protocol) and exercise the proxy's routing, security pipeline, session
/// management, and upstream connection management end-to-end.
use std::sync::Arc;
use std::time::Duration;

use axum::{
    extract::{Query, State},
    http::{HeaderMap, StatusCode},
    response::{sse::Event, IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use dashmap::DashMap;
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::mpsc;
use tokio_stream::wrappers::ReceiverStream;
use tokio_stream::StreamExt as _;
use tracing::debug;

use virbius_mcp_proxy::audit::{AuditBackend, AuditSink};
use virbius_mcp_proxy::config::UpstreamEntry;
use virbius_mcp_proxy::config::{
    FailoverConfig, FallbackPolicy, FastPathConfig, OutputReviewConfig,
};
use virbius_mcp_proxy::egress::EgressClient;
use virbius_mcp_proxy::pipeline::SecurityPipeline;
use virbius_mcp_proxy::router;
use virbius_mcp_proxy::session::{Session, SessionManager};
use virbius_mcp_proxy::trace_collector::{SharedTraceCollector, TraceBackend, TraceCollector};
use virbius_mcp_proxy::upstream::{UpstreamError, UpstreamManager};

// ═══════════════════════════════════════════════════════════════
//  Mock MCP Server (implements MCP SSE protocol)
// ═══════════════════════════════════════════════════════════════

#[derive(Clone)]
struct MockMcpState {
    /// SSE session_id → sender for pushing JSON-RPC responses
    sse_senders: Arc<DashMap<String, mpsc::Sender<Value>>>,
    last_call_args: Arc<std::sync::Mutex<Option<Value>>>,
}

struct MockUpstream {
    url: String,
    last_call_args: Arc<std::sync::Mutex<Option<Value>>>,
}

#[derive(Deserialize)]
struct MockQuery {
    session_id: String,
}

/// GET /sse — mock MCP SSE endpoint
async fn mock_sse_handler(State(state): State<MockMcpState>) -> Response {
    let session_id = uuid::Uuid::new_v4().to_string();
    let (tx, rx) = mpsc::channel::<Value>(64);
    state.sse_senders.insert(session_id.clone(), tx);

    let endpoint_url = format!("/messages/?session_id={}", session_id);
    debug!("mock SSE: endpoint for session {}", session_id);

    let endpoint_stream = futures_util::stream::once(async {
        Ok::<_, std::io::Error>(Event::default().event("endpoint").data(endpoint_url))
    });

    let message_stream = ReceiverStream::new(rx).map(|msg| {
        let json_str = serde_json::to_string(&msg).unwrap_or_default();
        Ok::<_, std::io::Error>(Event::default().event("message").data(json_str))
    });

    let stream = endpoint_stream.chain(message_stream);
    axum::response::sse::Sse::new(stream).into_response()
}

/// POST /messages/?session_id=xxx — mock MCP message endpoint
async fn mock_post_handler(
    State(state): State<MockMcpState>,
    Query(query): Query<MockQuery>,
    Json(req): Json<Value>,
) -> StatusCode {
    let session_id = query.session_id;
    let method = req.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let id = req.get("id").cloned().unwrap_or(Value::Null);

    let response = match method {
        "initialize" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "serverInfo": { "name": "mock-mcp", "version": "0.1.0" }
            }
        }),
        "tools/list" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "tools": [
                    { "name": "read_file", "description": "Read a file" },
                    { "name": "search", "description": "Search" },
                    { "name": "execute_python", "description": "Execute Python" },
                    { "name": "delete_file", "description": "Delete a file" }
                ]
            }
        }),
        "tools/call" => {
            let args = req
                .get("params")
                .and_then(|p| p.get("arguments"))
                .cloned()
                .unwrap_or(Value::Null);
            *state.last_call_args.lock().unwrap() = Some(args);
            json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "content": [{ "type": "text", "text": "ok" }],
                    "isError": false
                }
            })
        }
        _ => json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": { "code": -32601, "message": "method not found" }
        }),
    };

    if let Some(sender) = state.sse_senders.get(&session_id) {
        let _ = sender.send(response).await;
    }

    StatusCode::ACCEPTED
}

/// Start a mock MCP server on a random port, return its base URL.
async fn start_mock_mcp() -> String {
    start_mock_mcp_capture().await.url
}

async fn start_mock_mcp_capture() -> MockUpstream {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let url = format!("http://{}", addr);
    let last_call_args = Arc::new(std::sync::Mutex::new(None));

    let state = MockMcpState {
        sse_senders: Arc::new(DashMap::new()),
        last_call_args: last_call_args.clone(),
    };

    let app = Router::new()
        .route("/sse", get(mock_sse_handler))
        .route("/messages/", post(mock_post_handler))
        .with_state(state);

    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });

    MockUpstream {
        url,
        last_call_args,
    }
}

// ═══════════════════════════════════════════════════════════════
//  Proxy test environment
// ═══════════════════════════════════════════════════════════════

struct ProxyEnv {
    session_mgr: Arc<SessionManager>,
    upstream_mgr: Arc<UpstreamManager>,
    pipeline: Arc<SecurityPipeline>,
    egress_client: EgressClient,
    egress_hosts: Vec<String>,
    pubkey: String,
    trace_collector: SharedTraceCollector,
    conn_to_session: Arc<DashMap<String, String>>,
}

async fn setup_proxy(upstream_url: &str) -> ProxyEnv {
    setup_proxy_with_fallback(upstream_url, FallbackPolicy::MinimumPrivilege).await
}

async fn setup_proxy_with_fallback(upstream_url: &str, fallback: FallbackPolicy) -> ProxyEnv {
    let session_mgr = Arc::new(SessionManager::new());

    let upstream_mgr = Arc::new(UpstreamManager::new(
        vec![UpstreamEntry {
            name: "default".to_string(),
            url: Some(upstream_url.to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
        }],
        10,
    ));

    let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0)); // No Redis
    let pipeline = Arc::new(SecurityPipeline::new(
        String::new(),            // No public key (License verification will fail gracefully)
        "http://127.0.0.1:59999", // Non-existent engine (triggers failover)
        FastPathConfig::default(),
        FailoverConfig::default(),
        fallback,
        audit,
        OutputReviewConfig::default(),
    ));

    let egress_client = EgressClient::new(30, 50);
    let egress_hosts = Vec::new();
    let pubkey = String::new();
    let trace_collector = Arc::new(TraceCollector::new(TraceBackend::Disabled));
    let conn_to_session = Arc::new(DashMap::new());

    ProxyEnv {
        session_mgr,
        upstream_mgr,
        pipeline,
        egress_client,
        egress_hosts,
        pubkey,
        trace_collector,
        conn_to_session,
    }
}

/// Helper to call route_request with the proxy environment.
async fn route(env: &ProxyEnv, request: &Value, session_id: &str) -> Option<Value> {
    router::route_request(
        request,
        session_id,
        env.session_mgr.as_ref(),
        env.upstream_mgr.as_ref(),
        &env.pipeline,
        &env.egress_client,
        &env.egress_hosts,
        &env.pubkey,
        "",
        &env.trace_collector,
        &env.conn_to_session,
    )
    .await
}

/// Build a fake JWT with the given allowed_tools (no signature, just payload).
fn make_fake_jwt(allowed_tools: Vec<&str>) -> String {
    use base64::Engine;
    let payload = json!({
        "app_id": "test-app",
        "allowed_tools": allowed_tools,
        "risk_quota": 60,
        "exp": 9999999999i64,
    });
    let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(serde_json::to_vec(&payload).unwrap());
    format!("header.{}.sig", payload_b64)
}

// ═══════════════════════════════════════════════════════════════
//  Integration Tests
// ═══════════════════════════════════════════════════════════════

/// Test the full flow: initialize → tools/list → tools/call
#[tokio::test]
async fn test_initialize_tools_list_and_call() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-full-flow";

    // 1. Initialize (provide session_id in _meta so logical_sid == transport_sid)
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "session_id": sid, "app_id": "test-app" } }
    });
    let resp = route(&env, &init_req, sid).await;
    assert!(resp.is_some(), "initialize should return a response");
    let resp = resp.unwrap();
    assert_eq!(resp["jsonrpc"], "2.0");
    assert!(resp.get("result").is_some(), "initialize should succeed");
    assert!(resp.get("error").is_none());

    // Verify session was created
    let session = env.session_mgr.get(sid).expect("session should exist");
    assert_eq!(session.app_id, "test-app");
    assert!(session.is_upstream_initialized("default"));

    // 2. tools/list
    let list_req = json!({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/list"
    });
    let resp = route(&env, &list_req, sid).await;
    let resp = resp.unwrap();
    assert!(resp.get("result").is_some(), "tools/list should succeed");
    let tools = resp["result"]["tools"].as_array().unwrap();
    assert_eq!(
        tools.len(),
        4,
        "4 upstream tools; local tools are not advertised without an explicit canonical license grant"
    );

    // 3. tools/call — "search" is not high-risk, allowed by fallback policy
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": {
            "name": "search",
            "arguments": { "query": "hello" }
        }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    // Should be allowed and forwarded to upstream
    assert!(
        resp.get("result").is_some() || resp.get("error").is_some(),
        "tools/call should return a result or error"
    );

    // Verify call count was incremented
    let session = env.session_mgr.get(sid).unwrap();
    assert!(
        session.tool_call_count >= 1,
        "call count should be incremented (got {}) resp={}",
        session.tool_call_count,
        resp
    );
}

/// Test tools/list filtering by License allowed_tools
#[tokio::test]
async fn test_tools_list_filtered_by_license() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-filter";

    let jwt = make_fake_jwt(vec!["read_file", "search"]);

    // Initialize with license
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "_meta": { "app_id": "test-app", "license_jwt": jwt }
        }
    });
    let _ = route(&env, &init_req, sid).await;

    // tools/list — should be filtered to only allowed tools
    let list_req = json!({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/list"
    });
    let resp = route(&env, &list_req, sid).await;
    let resp = resp.unwrap();
    let tools = resp["result"]["tools"].as_array().unwrap();
    assert_eq!(tools.len(), 2, "only 2 tools should remain after filtering");
    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert!(names.contains(&"read_file"));
    assert!(names.contains(&"search"));
    assert!(!names.contains(&"execute_python"));
    assert!(!names.contains(&"delete_file"));
}

/// Test high-risk tool denial without License
#[tokio::test]
async fn test_high_risk_tool_denied_without_license() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-deny";

    // Initialize without license
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "app_id": "test-app" } }
    });
    let _ = route(&env, &init_req, sid).await;

    // tools/call — "delete_file" is high-risk, should be denied
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {
            "name": "delete_file",
            "arguments": { "path": "/tmp/test" }
        }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(
        resp.get("error").is_some(),
        "high-risk tool should be denied"
    );
    let code = resp["error"]["code"].as_i64().unwrap();
    assert_eq!(code, -32003, "should be HighRiskNoLicense error code");
}

/// Test that tools/call without initialize returns an error
#[tokio::test]
async fn test_tools_call_without_initialize() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-no-init";

    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": { "name": "search", "arguments": {} }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(resp.get("error").is_some());
    let code = resp["error"]["code"].as_i64().unwrap();
    assert_eq!(code, -32600, "should be invalid request error");
}

/// Test that tools/list without initialize returns an error
#[tokio::test]
async fn test_tools_list_without_initialize() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-no-init-list";

    let list_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/list"
    });
    let resp = route(&env, &list_req, sid).await;
    let resp = resp.unwrap();
    assert!(resp.get("error").is_some());
    let code = resp["error"]["code"].as_i64().unwrap();
    assert_eq!(code, -32600, "should be invalid request error");
}

/// Test session TTL expiry and cleanup
#[tokio::test]
async fn test_session_ttl_expiry() {
    let _lock = SUITE_LOCK.lock().await;
    let session_mgr = Arc::new(SessionManager::with_ttl(Duration::from_secs(1)));

    let session = Session::from_meta(&json!({ "app_id": "test" }));
    let sid = session.session_id.clone();
    session_mgr.insert(sid.clone(), session);

    assert!(session_mgr.is_valid(&sid));

    // Wait for expiry
    tokio::time::sleep(Duration::from_secs(2)).await;

    let expired = session_mgr.cleanup_expired();
    assert_eq!(expired.len(), 1);
    assert_eq!(expired[0], sid);
    assert!(!session_mgr.is_valid(&sid));
}

/// Test that session touch extends TTL
#[tokio::test]
async fn test_session_touch_extends_ttl() {
    let _lock = SUITE_LOCK.lock().await;
    let session_mgr = Arc::new(SessionManager::with_ttl(Duration::from_secs(2)));

    let session = Session::from_meta(&json!({ "app_id": "test" }));
    let sid = session.session_id.clone();
    session_mgr.insert(sid.clone(), session);

    // Touch after 1 second
    tokio::time::sleep(Duration::from_secs(1)).await;
    session_mgr.touch(&sid);

    // Wait another 1.5 seconds (2.5 total, but only 1.5 since touch)
    tokio::time::sleep(Duration::from_millis(1500)).await;

    // Should still be valid (touched at 1s, TTL is 2s, only 1.5s passed)
    assert_eq!(session_mgr.cleanup_expired().len(), 0);
    assert!(session_mgr.is_valid(&sid));
}

/// Test upstream manager cleanup of disconnected connections
#[tokio::test]
async fn test_upstream_cleanup_disconnected() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-cleanup";

    // Initialize — creates an upstream connection
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "app_id": "test-app" } }
    });
    let _ = route(&env, &init_req, sid).await;

    // Upstream should have at least 1 connection
    assert!(
        !env.upstream_mgr.is_empty(),
        "upstream should have a connection after initialize"
    );

    // cleanup_disconnected should NOT remove connected clients
    env.upstream_mgr.cleanup_disconnected();
    // Connection should still be there (mock server is running)
    assert!(
        !env.upstream_mgr.is_empty(),
        "connected upstream should not be cleaned up"
    );
}

/// Test session risk score is accessible and initialized to 0
#[tokio::test]
async fn test_session_risk_score_initialization() {
    let _lock = SUITE_LOCK.lock().await;
    let session = Session::from_meta(&json!({ "app_id": "test" }));
    assert_eq!(
        session.session_risk_score, 0,
        "risk score should start at 0"
    );
}

/// Test that the proxy capabilities are injected into the initialize response
#[tokio::test]
async fn test_proxy_capabilities_injected() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let sid = "itest-caps";

    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "app_id": "test-app" } }
    });
    let resp = route(&env, &init_req, sid).await;
    let resp = resp.unwrap();

    // The proxy should inject virbiusProxy capabilities
    let caps = &resp["result"]["capabilities"]["virbiusProxy"];
    assert!(
        caps.get("securityPipeline").is_some(),
        "virbiusProxy.securityPipeline should be injected"
    );
    assert!(
        caps.get("fastPath").is_some(),
        "virbiusProxy.fastPath should be injected"
    );
    // Local-exec contract skeleton (P1): clients key off these fields.
    let le = &caps["localExec"];
    assert_eq!(le["namespace"], "virbius");
    assert_eq!(le["canonical"][0], "virbius__shell");
    assert_eq!(le["sandbox"][0], "gvisor");
    assert!(le["grant"].is_string(), "grant rule must be advertised");
}

/// Regression: an upstream tool whose name collides with a LEGACY local name
/// is no longer hijacked — the bare name is forwarded upstream, and only the
/// canonical `virbius__*` name can reach the local executor (and even then it
/// must pass the license hard gate).
#[tokio::test]
async fn legacy_name_is_never_hijacked_by_local_exec() {
    let _lock = SUITE_LOCK.lock().await;
    let _manifest = install_offline_manifest(&json!({
        "tenant_id": "default",
        "app_id": "test-app",
        "tool_policies": [{
            "tool_name": "execute_python",
            "risk_class": "high",
            "sandbox_type": "none",
            "timeout_ms": 5000,
            "fast_path": true
        }]
    }));
    // The mock upstream advertises `execute_python`, an ordinary upstream tool name.
    let mock = start_mock_mcp_capture().await;
    // License grants the bare name only — enough to forward it upstream.
    let (pub_pem, jwt) = sign_license("test-app", &["execute_python"]);
    let env = setup_proxy_fast_path(&mock.url, pub_pem).await;
    let sid = "itest-legacy-hijack";

    let _ = route(
        &env,
        &json!({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": { "_meta": { "session_id": sid, "app_id": "test-app", "license_jwt": jwt } }
        }),
        sid,
    )
    .await;
    let _ = route(
        &env,
        &json!({"jsonrpc": "2.0", "id": 2, "method": "tools/list"}),
        sid,
    )
    .await;

    // 1. Bare name: forwarded to the upstream (granted for forwarding).
    let resp = route(
        &env,
        &json!({
            "jsonrpc": "2.0", "id": 3, "method": "tools/call",
            "params": { "name": "execute_python", "arguments": { "code": "print(1)" } }
        }),
        sid,
    )
    .await
    .unwrap();
    assert!(
        mock.last_call_args.lock().unwrap().is_some(),
        "bare 'execute_python' must be forwarded upstream, not executed locally: {resp}"
    );

    // 2. Canonical name with no canonical grant: hard gate rejects, never
    //    forwarded (the bare grant must not imply the canonical grant).
    *mock.last_call_args.lock().unwrap() = None;
    let resp = route(
        &env,
        &json!({
            "jsonrpc": "2.0", "id": 4, "method": "tools/call",
            "params": { "name": "virbius__python", "arguments": { "code": "print(1)" } }
        }),
        sid,
    )
    .await
    .unwrap();
    assert_eq!(
        resp["error"]["code"], -32016,
        "bare grant must not authorize the canonical local tool: {resp}"
    );
    assert!(
        mock.last_call_args.lock().unwrap().is_none(),
        "canonical local tool must never be forwarded upstream"
    );
}

/// Regression (P1c): one transport cannot be re-bound to a different logical
/// session (closes the "switch license on one connection" hole).
#[tokio::test]
async fn session_identity_switch_on_same_transport_is_rejected() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;
    let transport = "itest-identity-transport";

    let first = route(
        &env,
        &json!({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": { "_meta": { "session_id": "itest-identity-a", "app_id": "app-a" } }
        }),
        transport,
    )
    .await
    .unwrap();
    assert!(first.get("result").is_some(), "first initialize: {first}");

    let second = route(
        &env,
        &json!({
            "jsonrpc": "2.0", "id": 2, "method": "initialize",
            "params": { "_meta": { "session_id": "itest-identity-b", "app_id": "app-b" } }
        }),
        transport,
    )
    .await
    .unwrap();
    assert_eq!(
        second["error"]["code"], -32020,
        "re-binding one transport to another logical session must be rejected: {second}"
    );
}

/// Test multiple concurrent sessions with independent upstream connections
#[tokio::test]
async fn test_concurrent_sessions() {
    let _lock = SUITE_LOCK.lock().await;
    let upstream_url = start_mock_mcp().await;
    let env = setup_proxy(&upstream_url).await;

    let sid1 = "itest-concurrent-1";
    let sid2 = "itest-concurrent-2";

    // Initialize both sessions
    for sid in [&sid1, &sid2] {
        let init_req = json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": { "_meta": { "session_id": *sid, "app_id": "test-app" } }
        });
        let resp = route(&env, &init_req, sid).await;
        assert!(
            resp.unwrap().get("result").is_some(),
            "initialize should succeed for {}",
            sid
        );
    }

    // Both sessions should exist
    assert!(env.session_mgr.get(sid1).is_some());
    assert!(env.session_mgr.get(sid2).is_some());

    // tools/list on both sessions
    for sid in [&sid1, &sid2] {
        let list_req = json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/list"
        });
        let resp = route(&env, &list_req, sid).await;
        let resp = resp.unwrap();
        assert!(
            resp.get("result").is_some(),
            "tools/list should succeed for {}",
            sid
        );
    }

    // Upstream should have 2 connections (one per session)
    assert!(
        env.upstream_mgr.len() >= 2,
        "should have at least 2 upstream connections"
    );
}

// ═══════════════════════════════════════════════════════════════
//  Multi-upstream Tests
// ═══════════════════════════════════════════════════════════════

/// Start a mock MCP server that returns a custom set of tools.
async fn start_mock_mcp_with_tools(tools: Vec<(&str, &str)>) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let url = format!("http://{}", addr);

    let tools_json: Vec<Value> = tools
        .iter()
        .map(|(name, desc)| json!({ "name": name, "description": desc }))
        .collect();

    let state = MockMcpState {
        sse_senders: Arc::new(DashMap::new()),
        last_call_args: Arc::new(std::sync::Mutex::new(None)),
    };

    // We need to pass the tools to the handler. Use a shared state.
    let tools_state = Arc::new(tools_json);

    let app = Router::new()
        .route(
            "/sse",
            get({
                let s = state.clone();
                move || {
                    let s = s.clone();
                    async move { mock_sse_handler(State(s)).await }
                }
            }),
        )
        .route(
            "/messages/",
            post({
                let ts = tools_state.clone();
                move |State(state): State<MockMcpState>,
                      query: Query<MockQuery>,
                      Json(req): Json<Value>| {
                    let ts = ts.clone();
                    async move {
                        mock_post_handler_with_tools(State(state), query, Json(req), &ts).await
                    }
                }
            }),
        )
        .with_state(state);

    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });

    url
}

/// Mock POST handler that returns a custom tools list.
async fn mock_post_handler_with_tools(
    State(state): State<MockMcpState>,
    Query(query): Query<MockQuery>,
    Json(req): Json<Value>,
    tools: &[Value],
) -> StatusCode {
    let session_id = query.session_id;
    let method = req.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let id = req.get("id").cloned().unwrap_or(Value::Null);

    let response = match method {
        "initialize" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "serverInfo": { "name": "mock-mcp", "version": "0.1.0" }
            }
        }),
        "tools/list" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": { "tools": tools }
        }),
        "tools/call" => {
            let tool_name = req
                .get("params")
                .and_then(|p| p.get("name"))
                .and_then(|n| n.as_str())
                .unwrap_or("unknown");
            json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "content": [{ "type": "text", "text": format!("ok: {}", tool_name) }],
                    "isError": false
                }
            })
        }
        _ => json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": { "code": -32601, "message": "method not found" }
        }),
    };

    if let Some(sender) = state.sse_senders.get(&session_id) {
        let _ = sender.send(response).await;
    }

    StatusCode::ACCEPTED
}

/// Test multi-upstream mode: two upstreams with different tools, no conflicts.
#[tokio::test]
async fn test_multi_upstream_no_conflict() {
    let _lock = SUITE_LOCK.lock().await;
    let url_a =
        start_mock_mcp_with_tools(vec![("read_file", "Read a file"), ("search", "Search")]).await;
    let url_b = start_mock_mcp_with_tools(vec![
        ("create_issue", "Create issue"),
        ("list_repos", "List repos"),
    ])
    .await;

    let session_mgr = Arc::new(SessionManager::new());
    let upstream_mgr = Arc::new(UpstreamManager::new(
        vec![
            UpstreamEntry {
                name: "fs".to_string(),
                url: Some(url_a),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: Some(url_b),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
        ],
        10,
    ));

    let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0));
    let pipeline = Arc::new(SecurityPipeline::new(
        String::new(),
        "http://127.0.0.1:59999",
        FastPathConfig::default(),
        FailoverConfig::default(),
        FallbackPolicy::MinimumPrivilege,
        audit,
        OutputReviewConfig::default(),
    ));

    let trace_collector = Arc::new(TraceCollector::new(TraceBackend::Disabled));
    let conn_to_session = Arc::new(DashMap::new());

    let env = ProxyEnv {
        session_mgr,
        upstream_mgr,
        pipeline,
        egress_client: EgressClient::new(30, 50),
        egress_hosts: Vec::new(),
        pubkey: String::new(),
        trace_collector,
        conn_to_session,
    };

    let sid = "itest-multi-no-conflict";

    // 1. Initialize
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "session_id": sid, "app_id": "test-app" } }
    });
    let resp = route(&env, &init_req, sid).await;
    assert!(
        resp.unwrap().get("result").is_some(),
        "initialize should succeed"
    );

    // Both upstreams should be initialized
    let session = env.session_mgr.get(sid).unwrap();
    assert!(session.is_upstream_initialized("fs"));
    assert!(session.is_upstream_initialized("gh"));

    // 2. tools/list — should merge tools from both upstreams
    let list_req = json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/list" });
    let resp = route(&env, &list_req, sid).await;
    let resp = resp.unwrap();
    let tools = resp["result"]["tools"].as_array().unwrap();
    assert_eq!(
        tools.len(),
        4,
        "4 merged upstream tools (no local tools without a canonical grant)"
    );

    // No conflicts — no prefixing
    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert!(names.contains(&"read_file"));
    assert!(names.contains(&"search"));
    assert!(names.contains(&"create_issue"));
    assert!(names.contains(&"list_repos"));

    // 3. tools/call — "search" should route to "fs" upstream
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": { "name": "search", "arguments": { "query": "hello" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(
        resp.get("result").is_some(),
        "search should succeed: {resp}"
    );

    // 4. tools/call — "create_issue" should route to "gh" upstream
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 4,
        "method": "tools/call",
        "params": { "name": "create_issue", "arguments": { "title": "test" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(
        resp.get("result").is_some(),
        "create_issue should succeed: {resp}"
    );
}

/// Test multi-upstream mode with conflicting tool names — prefixing should occur.
#[tokio::test]
async fn test_multi_upstream_name_conflict() {
    let _lock = SUITE_LOCK.lock().await;
    let url_a =
        start_mock_mcp_with_tools(vec![("read_file", "Read from fs"), ("search", "Search")]).await;
    let url_b = start_mock_mcp_with_tools(vec![
        ("read_file", "Read from backup"),
        ("restore", "Restore"),
    ])
    .await;

    let session_mgr = Arc::new(SessionManager::new());
    let upstream_mgr = Arc::new(UpstreamManager::new(
        vec![
            UpstreamEntry {
                name: "fs".to_string(),
                url: Some(url_a),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
            UpstreamEntry {
                name: "backup".to_string(),
                url: Some(url_b),
                sse_path: "/sse".to_string(),
                ..Default::default()
            },
        ],
        10,
    ));

    let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0));
    let pipeline = Arc::new(SecurityPipeline::new(
        String::new(),
        "http://127.0.0.1:59999",
        FastPathConfig::default(),
        FailoverConfig::default(),
        FallbackPolicy::MinimumPrivilege,
        audit,
        OutputReviewConfig::default(),
    ));

    let trace_collector = Arc::new(TraceCollector::new(TraceBackend::Disabled));
    let conn_to_session = Arc::new(DashMap::new());

    let env = ProxyEnv {
        session_mgr,
        upstream_mgr,
        pipeline,
        egress_client: EgressClient::new(30, 50),
        egress_hosts: Vec::new(),
        pubkey: String::new(),
        trace_collector,
        conn_to_session,
    };

    let sid = "itest-multi-conflict";

    // 1. Initialize
    let init_req = json!({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": { "_meta": { "app_id": "test-app" } }
    });
    let _ = route(&env, &init_req, sid).await;

    // 2. tools/list — conflicting "read_file" should be prefixed
    let list_req = json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/list" });
    let resp = route(&env, &list_req, sid).await;
    let resp = resp.unwrap();
    let tools = resp["result"]["tools"].as_array().unwrap();
    assert_eq!(
        tools.len(),
        4,
        "4 merged upstream tools (no local tools without a canonical grant)"
    );

    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    // Conflicting read_file should be prefixed
    assert!(
        names.contains(&"fs__read_file"),
        "should have fs__read_file"
    );
    assert!(
        names.contains(&"backup__read_file"),
        "should have backup__read_file"
    );
    // Non-conflicting tools should NOT be prefixed
    assert!(names.contains(&"search"), "search should not be prefixed");
    assert!(names.contains(&"restore"), "restore should not be prefixed");

    // 3. tools/call — "fs__read_file" should route to "fs" upstream
    // (read_file is high-risk, will be denied without license — but the routing
    //  should still work. Let's test with "search" which is low-risk.)
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": { "name": "search", "arguments": { "query": "hello" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(
        resp.get("result").is_some(),
        "search should succeed: {resp}"
    );

    // 4. tools/call — "restore" should route to "backup" upstream
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 4,
        "method": "tools/call",
        "params": { "name": "restore", "arguments": { "path": "/tmp" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(
        resp.get("result").is_some(),
        "restore should succeed: {resp}"
    );
}

// ═══════════════════════════════════════════════════════════════
//  arg_transforms: initialize → allow → apply → forward / local shell
// ═══════════════════════════════════════════════════════════════

/// Serializes every test in this binary. They share process-global state in
/// `virbius_core` (the edge manifest installed by `install_offline_manifest`
/// and `EdgeInitConfig`) which any `tools/call` reads via `rewrite_tool_args`;
/// running them in parallel lets one test's manifest leak into another and
/// intermittently apply the wrong arg transforms. Hold this for the whole body.
static SUITE_LOCK: std::sync::LazyLock<tokio::sync::Mutex<()>> =
    std::sync::LazyLock::new(|| tokio::sync::Mutex::new(()));

struct OfflineManifestGuard {
    path: std::path::PathBuf,
}

impl Drop for OfflineManifestGuard {
    fn drop(&mut self) {
        let _ = std::fs::write(&self.path, "{}");
        virbius_core::manifest::reload();
        virbius_core::EdgeInitConfig::default().install();
        let _ = std::fs::remove_file(&self.path);
    }
}

fn install_offline_manifest(body: &Value) -> OfflineManifestGuard {
    let path = std::env::temp_dir().join(format!("virbius-at-{}.json", uuid::Uuid::new_v4()));
    std::fs::write(&path, serde_json::to_vec_pretty(body).unwrap()).unwrap();
    virbius_core::EdgeInitConfig {
        offline_manifest_path: Some(path.clone()),
        cache_dir: std::env::temp_dir().join("virbius-itest-edge"),
        tenant_id: "default".into(),
        app_id: "test-app".into(),
        ..Default::default()
    }
    .install();
    virbius_core::manifest::reload();
    OfflineManifestGuard { path }
}

fn sign_license(app_id: &str, allowed_tools: &[&str]) -> (String, String) {
    use base64::Engine;
    use ed25519_dalek::pkcs8::EncodePublicKey;
    use ed25519_dalek::{Signer, SigningKey};
    use rand::rngs::OsRng;
    let signing_key = SigningKey::generate(&mut OsRng);
    let pub_pem = signing_key
        .verifying_key()
        .to_public_key_pem(Default::default())
        .unwrap();
    let claims = json!({
        "app_id": app_id,
        "tenant_id": "tenant-1",
        "allowed_tools": allowed_tools,
        "risk_quota": 60,
        "tool_rate_limit": 50,
        "exp": 9999999999i64,
        "iat": 1700000000i64,
    });
    let header = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9";
    let payload = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .encode(serde_json::to_vec(&claims).unwrap());
    let message = format!("{}.{}", header, payload);
    let sig = signing_key.sign(message.as_bytes());
    let jwt = format!(
        "{}.{}.{}",
        header,
        payload,
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(sig.to_bytes())
    );
    (pub_pem, jwt)
}

async fn setup_proxy_fast_path(upstream_url: &str, pubkey: String) -> ProxyEnv {
    let session_mgr = Arc::new(SessionManager::new());
    let upstream_mgr = Arc::new(UpstreamManager::new(
        vec![UpstreamEntry {
            name: "default".to_string(),
            url: Some(upstream_url.to_string()),
            sse_path: "/sse".to_string(),
            ..Default::default()
        }],
        10,
    ));
    let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0));
    let pipeline = Arc::new(SecurityPipeline::new(
        pubkey.clone(),
        "http://127.0.0.1:59999",
        FastPathConfig {
            enabled: true,
            warmup_calls: 0,
            risk_threshold: 100,
        },
        FailoverConfig::default(),
        FallbackPolicy::MinimumPrivilege,
        audit,
        OutputReviewConfig::default(),
    ));
    ProxyEnv {
        session_mgr,
        upstream_mgr,
        pipeline,
        egress_client: EgressClient::new(30, 50),
        egress_hosts: Vec::new(),
        pubkey,
        trace_collector: Arc::new(TraceCollector::new(TraceBackend::Disabled)),
        conn_to_session: Arc::new(DashMap::new()),
    }
}

/// Low-risk upstream tool: original args go to evaluate/fallback; Apply clamps before forward.
#[tokio::test]
async fn arg_transform_search_restrict_clamped_before_upstream() {
    let _lock = SUITE_LOCK.lock().await;
    let _manifest = install_offline_manifest(&json!({
        "tenant_id": "default",
        "app_id": "test-app",
        "tool_policies": [{
            "tool_name": "search",
            "risk_class": "low",
            "sandbox_type": "none",
            "timeout_ms": 5000,
            "fast_path": false,
            "arg_transforms": {
                "phase": "pre_tool_call",
                "mutations": [{
                    "path": "$.limit",
                    "op": "restrict",
                    "to": { "max": 10 },
                    "on_violation": "clamp"
                }]
            }
        }]
    }));
    let mock = start_mock_mcp_capture().await;
    let env = setup_proxy(&mock.url).await;
    let sid = "itest-at-search";
    let _ = route(
        &env,
        &json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": { "_meta": { "session_id": sid, "app_id": "test-app" } }
        }),
        sid,
    )
    .await;

    let resp = route(
        &env,
        &json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": { "name": "search", "arguments": { "query": "q", "limit": 99 } }
        }),
        sid,
    )
    .await
    .unwrap();
    assert!(resp.get("result").is_some(), "search allow+apply: {resp}");
    assert_eq!(
        resp["result"]["_meta"]["arg_transform"]["applied"][0]["op"],
        "restrict"
    );
    let forwarded = mock.last_call_args.lock().unwrap().clone().unwrap();
    assert_eq!(forwarded["limit"], 10);
    assert_eq!(forwarded["query"], "q");
}

/// Local-exec authorization matrix (P1d): the hard gate is independent of
/// the pipeline's allowlist logic.
///
/// - canonical grant   → passes the gate, then fails closed at the sandbox
///   (`sandbox_unavailable`: no runsc in the unit-test env) and is never
///   forwarded upstream
/// - bare-name grant   → `local_exec_not_granted` (a bare name never grants
///   local execution)
/// - wildcard license  → `local_exec_not_granted`
/// - no license        → `local_exec_not_granted` (the hard gate is fallback-
///   independent: no license means no explicit canonical grant, so an
///   `audit_only` fallback cannot authorize local code execution)
#[tokio::test]
async fn local_exec_gate_matrix() {
    let _lock = SUITE_LOCK.lock().await;
    let _manifest = install_offline_manifest(&json!({
        "tenant_id": "default",
        "app_id": "test-app",
        "tool_policies": [{
            "tool_name": "virbius__shell",
            "risk_class": "high",
            "sandbox_type": "none",
            "timeout_ms": 5000,
            "fast_path": true
        }]
    }));
    // Opt this test process into gvisor mode so the gate path is observable
    // on any OS. Execution itself still fails closed without runsc.
    virbius_mcp_proxy::router::init_local_exec_settings(
        virbius_mcp_proxy::router::LocalExecSettings {
            mode: virbius_mcp_proxy::router::LocalExecMode::Gvisor,
            code_bytes: 64 * 1024,
            concurrency: 8,
        },
    );

    async fn init_and_call(env: &ProxyEnv, sid: &str, jwt: Option<&str>) -> Value {
        let mut meta = json!({ "session_id": sid, "app_id": "test-app" });
        if let Some(jwt) = jwt {
            meta["license_jwt"] = json!(jwt);
        }
        let _ = route(
            env,
            &json!({
                "jsonrpc": "2.0", "id": 1, "method": "initialize",
                "params": { "_meta": meta }
            }),
            sid,
        )
        .await;
        route(
            env,
            &json!({
                "jsonrpc": "2.0", "id": 2, "method": "tools/call",
                "params": { "name": "virbius__shell", "arguments": { "command": "echo hi" } }
            }),
            sid,
        )
        .await
        .unwrap()
    }

    let mock = start_mock_mcp_capture().await;

    // Audit-truth helper: entries in the global recent-decisions ring for one
    // session (unique trace_id), immune to parallel-test interference.
    fn ring_for(trace_id: &str) -> Vec<(String, String)> {
        virbius_mcp_proxy::audit::recent_decisions()
            .into_iter()
            .filter(|d| d.trace_id == trace_id)
            .map(|d| {
                (
                    d.effective_action.clone(),
                    d.reason_code.clone().unwrap_or_default(),
                )
            })
            .collect()
    }

    // A. Canonical grant: passes the hard gate, then fails closed (no runsc).
    let (pub_a, jwt_a) = sign_license("test-app", &["virbius__shell"]);
    let env_a = setup_proxy_fast_path(&mock.url, pub_a).await;
    let resp = init_and_call(&env_a, "itest-gate-canonical", Some(&jwt_a)).await;
    assert_eq!(
        resp["error"]["code"], -32018,
        "canonical grant should reach the sandbox and fail closed there: {resp}"
    );
    assert!(
        mock.last_call_args.lock().unwrap().is_none(),
        "local exec must never be forwarded upstream"
    );

    // B. Bare-name grant alone: hard gate rejects.
    let (pub_b, jwt_b) = sign_license("test-app", &["shell"]);
    let env_b = setup_proxy_fast_path(&mock.url, pub_b).await;
    let resp = init_and_call(&env_b, "itest-gate-bare", Some(&jwt_b)).await;
    assert_eq!(
        resp["error"]["code"], -32016,
        "bare-name grant must not authorize local execution: {resp}"
    );

    // C. Wildcard license (empty allowed_tools): hard gate rejects.
    let (pub_c, jwt_c) = sign_license("test-app", &[]);
    let env_c = setup_proxy_fast_path(&mock.url, pub_c).await;
    let resp = init_and_call(&env_c, "itest-gate-wildcard", Some(&jwt_c)).await;
    assert_eq!(
        resp["error"]["code"], -32016,
        "wildcard license must not grant local execution: {resp}"
    );

    // Audit truthfulness (B + C): a hard-gate rejection must leave exactly
    // one block row — never an allow row — and must not count the rejected
    // call toward the session's tool_call_count (fast-path warmup).
    for (env, sid) in [(&env_b, "itest-gate-bare"), (&env_c, "itest-gate-wildcard")] {
        let session = env.session_mgr.get(sid).unwrap();
        let trace_id = session.trace_id.clone();
        let entries = ring_for(&trace_id);
        assert!(
            entries.iter().all(|(a, _)| a == "block"),
            "hard-gate rejection must not leave an allow audit row: {entries:?}"
        );
        assert!(
            entries.iter().any(|(_, r)| r == "local_exec_not_granted"),
            "hard-gate rejection must be audited as local_exec_not_granted: {entries:?}"
        );
        assert_eq!(
            session.tool_call_count, 0,
            "a rejected call must not increment tool_call_count"
        );
    }

    // D. No license at all: the hard gate rejects independently of the
    // pipeline's fallback policy.
    let env_d = setup_proxy(&mock.url).await;
    let resp = init_and_call(&env_d, "itest-gate-nolicense", None).await;
    assert_eq!(
        resp["error"]["code"], -32016,
        "no license must be denied as local_exec_not_granted: {resp}"
    );
    let session = env_d.session_mgr.get("itest-gate-nolicense").unwrap();
    let entries = ring_for(&session.trace_id);
    assert!(
        entries
            .iter()
            .any(|(a, r)| a == "block" && r == "local_exec_not_granted"),
        "no-license denial must be a hard-gate block: {entries:?}"
    );
    assert_eq!(
        session.tool_call_count, 0,
        "a rejected call must not increment tool_call_count"
    );

    // E. No license + audit_only fallback: an audit-only policy must NOT
    // authorize local code execution. The hard gate fires before the pipeline
    // can turn this into allow("audit_only").
    let env_e = setup_proxy_with_fallback(&mock.url, FallbackPolicy::AuditOnly).await;
    let resp = init_and_call(&env_e, "itest-gate-nolicense-audit-only", None).await;
    assert_eq!(
        resp["error"]["code"], -32016,
        "audit_only without a license must not grant local execution: {resp}"
    );
    let session = env_e
        .session_mgr
        .get("itest-gate-nolicense-audit-only")
        .unwrap();
    let entries = ring_for(&session.trace_id);
    assert!(
        entries.iter().all(|(a, _)| a == "block"),
        "audit_only local-exec rejection must never leave an allow audit row: {entries:?}"
    );
    assert!(
        entries
            .iter()
            .any(|(a, r)| a == "block" && r == "local_exec_not_granted"),
        "audit_only local-exec rejection must be a hard-gate block: {entries:?}"
    );
    assert_eq!(
        session.tool_call_count, 0,
        "a rejected call must not increment tool_call_count"
    );

    assert!(
        mock.last_call_args.lock().unwrap().is_none(),
        "no local-exec attempt may reach the upstream"
    );
}

/// Prefix restrict: scalar miss fails the call; nothing is forwarded.
#[tokio::test]
async fn arg_transform_search_restrict_prefix_mismatch_fails() {
    let _lock = SUITE_LOCK.lock().await;
    let _manifest = install_offline_manifest(&json!({
        "tenant_id": "default",
        "app_id": "test-app",
        "tool_policies": [{
            "tool_name": "search",
            "risk_class": "low",
            "sandbox_type": "none",
            "timeout_ms": 5000,
            "arg_transforms": {
                "phase": "pre_tool_call",
                "mutations": [{
                    "path": "$.query",
                    "op": "restrict",
                    "to": { "prefixes": ["ok-"] },
                    "on_violation": "clamp"
                }]
            }
        }]
    }));
    let mock = start_mock_mcp_capture().await;
    let env = setup_proxy(&mock.url).await;
    let sid = "itest-at-prefix-fail";
    let _ = route(
        &env,
        &json!({
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": { "_meta": { "session_id": sid, "app_id": "test-app" } }
        }),
        sid,
    )
    .await;

    let resp = route(
        &env,
        &json!({
            "jsonrpc": "2.0",
            "id": 2,
            "method": "tools/call",
            "params": { "name": "search", "arguments": { "query": "evil" } }
        }),
        sid,
    )
    .await
    .unwrap();
    assert_eq!(resp["error"]["code"], -32013);
    assert!(mock.last_call_args.lock().unwrap().is_none());

    // Audit truthfulness: the pipeline allow verdict is followed by a block
    // row for the failed pre_tool_call transform, so the trail ends on the
    // actual outcome instead of a dangling "allow".
    let session = env.session_mgr.get(sid).unwrap();
    let entries: Vec<(String, String)> = virbius_mcp_proxy::audit::recent_decisions()
        .into_iter()
        .filter(|d| d.trace_id == session.trace_id)
        .map(|d| (d.effective_action, d.reason_code.unwrap_or_default()))
        .collect();
    assert!(
        entries
            .iter()
            .any(|(a, r)| a == "block" && r == "arg_transform_failed"),
        "transform failure must be audited as block/arg_transform_failed: {entries:?}"
    );
    assert_eq!(
        entries.last().map(|(a, _)| a.as_str()),
        Some("block"),
        "audit trail must end on the blocking outcome: {entries:?}"
    );
    assert_eq!(
        session.tool_call_count, 0,
        "a call that failed its transform must not increment tool_call_count"
    );
}

// ═══════════════════════════════════════════════════════════════
//  Mock MCP Server (Streamable HTTP)
// ═══════════════════════════════════════════════════════════════

#[derive(Clone)]
struct MockStreamableState {
    init_count: Arc<std::sync::atomic::AtomicUsize>,
    initialized_notifications: Arc<std::sync::atomic::AtomicUsize>,
    init_api_key: Arc<std::sync::Mutex<Option<String>>>,
    require_api_key: Arc<std::sync::atomic::AtomicBool>,
    /// Delay before a non-initialize reply (ms).
    respond_after_ms: Arc<std::sync::atomic::AtomicU64>,
    /// Reply with 202 and deliver the result on the GET SSE stream instead.
    deliver_on_get: Arc<std::sync::atomic::AtomicBool>,
    /// Session id → sender used by the mock GET stream.
    get_stream_senders: Arc<DashMap<String, mpsc::Sender<Value>>>,
    sse_mode: bool,
    expire_next: Arc<std::sync::atomic::AtomicBool>,
}

async fn mock_streamable_post(
    State(state): State<MockStreamableState>,
    headers: HeaderMap,
    Json(req): Json<Value>,
) -> Response {
    use std::sync::atomic::Ordering;
    let method = req.get("method").and_then(Value::as_str).unwrap_or("");
    let id = req.get("id").cloned().unwrap_or(Value::Null);

    match method {
        "initialize" => {
            state.init_count.fetch_add(1, Ordering::SeqCst);
            let api_key = headers
                .get("x-api-key")
                .and_then(|v| v.to_str().ok())
                .map(str::to_string);
            *state.init_api_key.lock().unwrap() = api_key.clone();
            if state.require_api_key.load(Ordering::SeqCst) && api_key.as_deref() != Some("secret")
            {
                return StatusCode::UNAUTHORIZED.into_response();
            }
            let body = json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "serverInfo": { "name": "mock-streamable", "version": "0.1.0" }
                }
            });
            let headers = [
                (
                    axum::http::HeaderName::from_static("content-type"),
                    "application/json".to_string(),
                ),
                (
                    axum::http::HeaderName::from_static("mcp-session-id"),
                    "sess-mock-1".to_string(),
                ),
            ];
            (headers, Json(body)).into_response()
        }
        "notifications/initialized" => {
            state
                .initialized_notifications
                .fetch_add(1, Ordering::SeqCst);
            StatusCode::ACCEPTED.into_response()
        }
        _ => {
            if state.expire_next.swap(false, Ordering::SeqCst) {
                return StatusCode::NOT_FOUND.into_response();
            }
            let body = json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "tools": [ { "name": "read_file", "description": "Read a file" } ]
                }
            });
            let delay = state.respond_after_ms.load(Ordering::SeqCst);
            if state.deliver_on_get.load(Ordering::SeqCst) {
                let sid = headers
                    .get("mcp-session-id")
                    .and_then(|v| v.to_str().ok())
                    .map(str::to_string);
                let senders = state.get_stream_senders.clone();
                tokio::spawn(async move {
                    tokio::time::sleep(Duration::from_millis(delay)).await;
                    // The GET stream may still be connecting when the reply is ready.
                    for _ in 0..60 {
                        if let Some(sid) = sid.as_deref() {
                            if let Some(tx) = senders.get(sid) {
                                let _ = tx.send(body).await;
                                return;
                            }
                        }
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                });
                return StatusCode::ACCEPTED.into_response();
            }
            if delay > 0 {
                tokio::time::sleep(Duration::from_millis(delay)).await;
            }
            if state.sse_mode {
                let payload = format!(
                    "event: message\ndata: {}\n\n",
                    serde_json::to_string(&body).unwrap()
                );
                let headers = [(
                    axum::http::HeaderName::from_static("content-type"),
                    "text/event-stream".to_string(),
                )];
                (headers, payload).into_response()
            } else {
                Json(body).into_response()
            }
        }
    }
}

async fn mock_streamable_get(
    State(state): State<MockStreamableState>,
    headers: HeaderMap,
) -> Response {
    let Some(sid) = headers
        .get("mcp-session-id")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string)
    else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let (tx, rx) = mpsc::channel::<Value>(64);
    state.get_stream_senders.insert(sid, tx);
    let stream = ReceiverStream::new(rx).map(|msg: Value| {
        Ok::<_, std::io::Error>(
            Event::default()
                .event("message")
                .data(serde_json::to_string(&msg).unwrap_or_default()),
        )
    });
    axum::response::sse::Sse::new(stream).into_response()
}

struct MockStreamable {
    url: String,
    state: MockStreamableState,
}

async fn start_mock_streamable(sse_mode: bool) -> MockStreamable {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let state = MockStreamableState {
        init_count: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        initialized_notifications: Arc::new(std::sync::atomic::AtomicUsize::new(0)),
        init_api_key: Arc::new(std::sync::Mutex::new(None)),
        require_api_key: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        respond_after_ms: Arc::new(std::sync::atomic::AtomicU64::new(0)),
        deliver_on_get: Arc::new(std::sync::atomic::AtomicBool::new(false)),
        get_stream_senders: Arc::new(DashMap::new()),
        sse_mode,
        expire_next: Arc::new(std::sync::atomic::AtomicBool::new(false)),
    };
    let app = Router::new()
        .route("/mcp", post(mock_streamable_post))
        .route("/mcp", get(mock_streamable_get))
        .with_state(state.clone());
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    MockStreamable {
        url: format!("http://{}", addr),
        state,
    }
}

fn streamable_entry(url: &str, transport: &str) -> UpstreamEntry {
    UpstreamEntry {
        name: "default".to_string(),
        transport: transport.to_string(),
        url: Some(url.to_string()),
        http_path: "/mcp".to_string(),
        ..Default::default()
    }
}

#[tokio::test]
async fn test_streamable_upstream_json_response() {
    let mock = start_mock_streamable(false).await;
    let mgr = UpstreamManager::new(vec![streamable_entry(&mock.url, "streamable-http")], 5);
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    assert_eq!(handle.transport(), "streamable-http");

    let resp = handle
        .forward(&json!({"jsonrpc":"2.0","id":7,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(resp["id"], 7);
    assert!(resp["result"]["tools"].is_array());
    assert_eq!(
        mock.state
            .initialized_notifications
            .load(std::sync::atomic::Ordering::SeqCst),
        1
    );
}

#[tokio::test]
async fn test_streamable_initialize_sends_custom_headers() {
    let mock = start_mock_streamable(false).await;
    mock.state
        .require_api_key
        .store(true, std::sync::atomic::Ordering::SeqCst);

    let mut entry = streamable_entry(&mock.url, "streamable-http");
    entry
        .headers
        .insert("x-api-key".to_string(), "secret".to_string());
    let mgr = UpstreamManager::new(vec![entry], 5);

    mgr.get_or_connect("s1", "default")
        .await
        .expect("initialize must carry configured custom headers");
    assert_eq!(
        mock.state.init_api_key.lock().unwrap().as_deref(),
        Some("secret")
    );
}

#[tokio::test]
async fn test_streamable_long_request_not_capped_by_client_timeout() {
    let mock = start_mock_streamable(false).await;
    mock.state
        .respond_after_ms
        .store(1500, std::sync::atomic::Ordering::SeqCst);

    let mut entry = streamable_entry(&mock.url, "streamable-http");
    entry.timeout_ms = 10_000;
    // The manager-wide client used to have a 1s total timeout, which also
    // capped the response body. The per-entry timeout must be the limit.
    let mgr = UpstreamManager::new(vec![entry], 1);

    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    let resp = handle
        .forward(&json!({"jsonrpc":"2.0","id":7,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(resp["id"], 7);
}

#[tokio::test]
async fn test_streamable_get_stream_not_capped_by_client_timeout() {
    let mock = start_mock_streamable(false).await;
    mock.state
        .deliver_on_get
        .store(true, std::sync::atomic::Ordering::SeqCst);
    mock.state
        .respond_after_ms
        .store(1500, std::sync::atomic::Ordering::SeqCst);

    let mut entry = streamable_entry(&mock.url, "streamable-http");
    entry.timeout_ms = 5_000;
    let mgr = UpstreamManager::new(vec![entry], 1);

    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    let resp = handle
        .forward(&json!({"jsonrpc":"2.0","id":8,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(resp["id"], 8);
}

#[tokio::test]
async fn test_streamable_upstream_sse_response() {
    let mock = start_mock_streamable(true).await;
    let mgr = UpstreamManager::new(vec![streamable_entry(&mock.url, "streamable-http")], 5);
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    let resp = handle
        .forward(&json!({"jsonrpc":"2.0","id":9,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(resp["id"], 9);
}

#[tokio::test]
async fn test_streamable_session_expiry_reinitializes() {
    let mock = start_mock_streamable(false).await;
    let mgr = UpstreamManager::new(vec![streamable_entry(&mock.url, "streamable-http")], 5);
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();

    mock.state
        .expire_next
        .store(true, std::sync::atomic::Ordering::SeqCst);
    let resp = handle
        .forward(&json!({"jsonrpc":"2.0","id":11,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(resp["id"], 11);
    // One initial handshake + one re-handshake after the 404.
    assert_eq!(
        mock.state
            .init_count
            .load(std::sync::atomic::Ordering::SeqCst),
        2
    );
}

#[tokio::test]
async fn test_streamable_concurrent_connect_is_single_flight() {
    let mock = start_mock_streamable(false).await;
    let mgr = Arc::new(UpstreamManager::new(
        vec![streamable_entry(&mock.url, "streamable-http")],
        5,
    ));
    let mut tasks = Vec::new();
    for _ in 0..10 {
        let mgr = mgr.clone();
        tasks.push(tokio::spawn(async move {
            mgr.get_or_connect("s1", "default")
                .await
                .map(|h| h.transport())
        }));
    }
    for t in tasks {
        assert_eq!(t.await.unwrap().unwrap(), "streamable-http");
    }
    assert_eq!(
        mock.state
            .init_count
            .load(std::sync::atomic::Ordering::SeqCst),
        1
    );
}

#[tokio::test]
async fn test_auto_transport_detects_streamable() {
    let mock = start_mock_streamable(false).await;
    let mgr = UpstreamManager::new(vec![streamable_entry(&mock.url, "auto")], 5);
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    assert_eq!(handle.transport(), "streamable-http");
    assert_eq!(mgr.detection_counts(), (0, 1));
}

#[tokio::test]
async fn test_auto_transport_falls_back_to_legacy_sse() {
    let url = start_mock_mcp().await;
    let mgr = UpstreamManager::new(
        vec![UpstreamEntry {
            name: "default".to_string(),
            transport: "auto".to_string(),
            url: Some(url),
            sse_path: "/sse".to_string(),
            ..Default::default()
        }],
        5,
    );
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    assert_eq!(handle.transport(), "sse");
    assert_eq!(mgr.detection_counts(), (1, 0));
}

#[tokio::test]
async fn test_ssrf_guard_rejects_metadata_address() {
    let mgr = UpstreamManager::new(
        vec![streamable_entry(
            "http://169.254.169.254",
            "streamable-http",
        )],
        2,
    );
    let err = mgr.get_or_connect("s1", "default").await.err().unwrap();
    assert!(matches!(
        err,
        virbius_mcp_proxy::upstream::UpstreamError::UrlRejected(_)
    ));
}

// ═══════════════════════════════════════════════════════════════
//  stdio upstream (subprocess fixture)
// ═══════════════════════════════════════════════════════════════

fn python3_available() -> bool {
    std::process::Command::new("python3")
        .arg("--version")
        .output()
        .map(|o| o.status.success())
        .unwrap_or(false)
}

fn stdio_entry(
    env: std::collections::BTreeMap<String, String>,
    cwd: Option<String>,
) -> UpstreamEntry {
    let fixture = concat!(
        env!("CARGO_MANIFEST_DIR"),
        "/tests/fixtures/echo_mcp_stdio.py"
    );
    UpstreamEntry {
        name: "default".to_string(),
        transport: "stdio".to_string(),
        command: Some("python3".to_string()),
        args: vec![fixture.to_string()],
        env,
        cwd,
        timeout_ms: 3000,
        ..Default::default()
    }
}

#[tokio::test]
async fn test_stdio_upstream_roundtrip_and_env_isolation() {
    if !python3_available() {
        eprintln!("skipping: python3 not available");
        return;
    }
    let mut env = std::collections::BTreeMap::new();
    env.insert("FS_ROOT".to_string(), "/data".to_string());
    let mgr = UpstreamManager::new(vec![stdio_entry(env, None)], 5);
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    assert_eq!(handle.transport(), "stdio");

    let listing = handle
        .forward(&json!({"jsonrpc":"2.0","id":1,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(listing["result"]["tools"][0]["name"], "echo");

    let call = handle
        .forward(&json!({
            "jsonrpc":"2.0","id":2,"method":"tools/call",
            "params":{"name":"echo","arguments":{"path":"/tmp/x"}}
        }))
        .await
        .unwrap();
    assert_eq!(call["id"], 2);
    assert_eq!(call["result"]["env"]["FS_ROOT"], "/data");
    assert!(call["result"]["env"]["VIRBIUS_LICENSE_FILE"].is_null());
    assert!(call["result"]["env"]["VIRBIUS_REDIS_URL"].is_null());
}

#[tokio::test]
async fn test_stdio_child_crash_reconnects_on_next_use() {
    if !python3_available() {
        eprintln!("skipping: python3 not available");
        return;
    }
    let mgr = UpstreamManager::new(
        vec![stdio_entry(std::collections::BTreeMap::new(), None)],
        5,
    );
    let handle = mgr.get_or_connect("s1", "default").await.unwrap();
    assert!(handle.is_connected());

    // Ask the fixture to exit.
    handle
        .forward_notification(&json!({"jsonrpc":"2.0","method":"crash"}))
        .await
        .unwrap();
    tokio::time::sleep(Duration::from_millis(300)).await;
    assert!(!handle.is_connected());

    // Next use spawns a fresh child and completes the handshake.
    let handle2 = mgr.get_or_connect("s1", "default").await.unwrap();
    assert!(handle2.is_connected());
    let listing = handle2
        .forward(&json!({"jsonrpc":"2.0","id":3,"method":"tools/list"}))
        .await
        .unwrap();
    assert_eq!(listing["id"], 3);
}

#[tokio::test]
async fn test_stdio_missing_cwd_is_reported() {
    if !python3_available() {
        eprintln!("skipping: python3 not available");
        return;
    }
    let mgr = UpstreamManager::new(
        vec![stdio_entry(
            std::collections::BTreeMap::new(),
            Some("/nonexistent/virbius/dir".to_string()),
        )],
        5,
    );
    let err = mgr.get_or_connect("s1", "default").await.err().unwrap();
    assert!(matches!(
        err,
        virbius_mcp_proxy::upstream::UpstreamError::Spawn(_)
    ));
}

// ═══════════════════════════════════════════════════════════════
//  Legacy SSE endpoint origin guard (H1)
// ═══════════════════════════════════════════════════════════════

/// Start a legacy-SSE upstream that announces `endpoint_data` and then idles.
///
/// `None` announces an absolute, same-origin endpoint for the server itself.
async fn start_sse_with_endpoint(endpoint_data: Option<String>) -> String {
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let endpoint_data =
        endpoint_data.unwrap_or_else(|| format!("http://{}/messages/?session_id=abc", addr));

    let app = Router::new().route(
        "/sse",
        get(move || {
            let endpoint_data = endpoint_data.clone();
            async move {
                let endpoints = futures_util::stream::once(async move {
                    Ok::<_, std::io::Error>(Event::default().event("endpoint").data(endpoint_data))
                });
                let idle = futures_util::stream::pending::<Result<Event, std::io::Error>>();
                axum::response::sse::Sse::new(endpoints.chain(idle)).into_response()
            }
        }),
    );
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    format!("http://{}", addr)
}

fn legacy_sse_entry(url: &str) -> UpstreamEntry {
    UpstreamEntry {
        name: "default".to_string(),
        transport: "sse".to_string(),
        url: Some(url.to_string()),
        sse_path: "/sse".to_string(),
        ..Default::default()
    }
}

#[tokio::test]
async fn test_sse_cross_origin_endpoint_is_rejected() {
    let upstream = start_sse_with_endpoint(Some("http://evil.example/messages".to_string())).await;
    let mut entry = legacy_sse_entry(&upstream);
    entry.timeout_ms = 1000;
    let mgr = UpstreamManager::new(vec![entry], 1);

    let err = mgr
        .get_or_connect("s1", "default")
        .await
        .err()
        .expect("cross-origin endpoint must fail closed");
    assert!(matches!(err, UpstreamError::Timeout), "unexpected: {err}");
}

#[tokio::test]
async fn test_sse_absolute_same_origin_endpoint_is_accepted() {
    let upstream = start_sse_with_endpoint(None).await;
    let mgr = UpstreamManager::new(vec![legacy_sse_entry(&upstream)], 2);

    let handle = mgr
        .get_or_connect("s1", "default")
        .await
        .expect("absolute same-origin endpoint should be accepted");
    assert_eq!(handle.transport(), "sse");
    assert_eq!(handle.url(), upstream);
}
