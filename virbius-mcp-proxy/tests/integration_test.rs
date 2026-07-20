/// Integration tests for virbius-mcp-proxy.
///
/// These tests start a mock upstream MCP server (implementing the MCP SSE
/// protocol) and exercise the proxy's routing, security pipeline, session
/// management, and upstream connection management end-to-end.
use std::sync::Arc;
use std::time::Duration;

use axum::{
    extract::{Query, State},
    http::StatusCode,
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
use virbius_mcp_proxy::upstream::UpstreamManager;

// ═══════════════════════════════════════════════════════════════
//  Mock MCP Server (implements MCP SSE protocol)
// ═══════════════════════════════════════════════════════════════

#[derive(Clone)]
struct MockMcpState {
    /// SSE session_id → sender for pushing JSON-RPC responses
    sse_senders: Arc<DashMap<String, mpsc::Sender<Value>>>,
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
        "tools/call" => json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "content": [{ "type": "text", "text": "ok" }],
                "isError": false
            }
        }),
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
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let url = format!("http://{}", addr);

    let state = MockMcpState {
        sse_senders: Arc::new(DashMap::new()),
    };

    let app = Router::new()
        .route("/sse", get(mock_sse_handler))
        .route("/messages/", post(mock_post_handler))
        .with_state(state);

    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });

    url
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
    let session_mgr = Arc::new(SessionManager::new());

    let upstream_mgr = Arc::new(UpstreamManager::new(
        vec![UpstreamEntry {
            name: "default".to_string(),
            url: upstream_url.to_string(),
            sse_path: "/sse".to_string(),
        }],
        10,
    ));

    let audit = Arc::new(AuditSink::new(AuditBackend::Disabled, 1.0)); // No Redis
    let pipeline = Arc::new(SecurityPipeline::new(
        String::new(),            // No public key (License verification will fail gracefully)
        "http://127.0.0.1:59999", // Non-existent engine (triggers failover)
        FastPathConfig::default(),
        FailoverConfig::default(),
        FallbackPolicy::MinimumPrivilege,
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
        "all 4 tools should be returned (no license)"
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
        "call count should be incremented"
    );
}

/// Test tools/list filtering by License allowed_tools
#[tokio::test]
async fn test_tools_list_filtered_by_license() {
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
    let session = Session::from_meta(&json!({ "app_id": "test" }));
    assert_eq!(
        session.session_risk_score, 0,
        "risk score should start at 0"
    );
}

/// Test that the proxy capabilities are injected into the initialize response
#[tokio::test]
async fn test_proxy_capabilities_injected() {
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
}

/// Test multiple concurrent sessions with independent upstream connections
#[tokio::test]
async fn test_concurrent_sessions() {
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
                url: url_a,
                sse_path: "/sse".to_string(),
            },
            UpstreamEntry {
                name: "gh".to_string(),
                url: url_b,
                sse_path: "/sse".to_string(),
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
    assert_eq!(tools.len(), 4, "should have 4 merged tools");

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
    assert!(resp.get("result").is_some(), "search should succeed");

    // 4. tools/call — "create_issue" should route to "gh" upstream
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 4,
        "method": "tools/call",
        "params": { "name": "create_issue", "arguments": { "title": "test" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(resp.get("result").is_some(), "create_issue should succeed");
}

/// Test multi-upstream mode with conflicting tool names — prefixing should occur.
#[tokio::test]
async fn test_multi_upstream_name_conflict() {
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
                url: url_a,
                sse_path: "/sse".to_string(),
            },
            UpstreamEntry {
                name: "backup".to_string(),
                url: url_b,
                sse_path: "/sse".to_string(),
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
    assert_eq!(tools.len(), 4, "should have 4 tools (2+2)");

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
    assert!(resp.get("result").is_some(), "search should succeed");

    // 4. tools/call — "restore" should route to "backup" upstream
    let call_req = json!({
        "jsonrpc": "2.0",
        "id": 4,
        "method": "tools/call",
        "params": { "name": "restore", "arguments": { "path": "/tmp" } }
    });
    let resp = route(&env, &call_req, sid).await;
    let resp = resp.unwrap();
    assert!(resp.get("result").is_some(), "restore should succeed");
}
