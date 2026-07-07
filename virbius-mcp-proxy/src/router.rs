/// JSON-RPC method router: routes MCP protocol methods to handlers.

use serde_json::Value;
use tracing::{debug, warn};

use crate::egress::EgressClient;
use crate::error::{jsonrpc_error_simple, VirbiusErrorCode};
use crate::pipeline::{PipelineResult, SharedPipeline};
use crate::session::{Session, SessionManager};
use crate::upstream::UpstreamManager;

/// Process a single JSON-RPC request and return a response.
///
/// `session_id` identifies the logical session (decoupled from TCP connection).
/// Returns `Some(response)` for requests (have `id`), `None` for notifications.
pub async fn route_request(
    request: &Value,
    session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    pipeline: &SharedPipeline,
    egress_client: &EgressClient,
    egress_hosts: &[String],
    public_key_pem: &str,
) -> Option<Value> {
    let method = request.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or(Value::Null);

    // Notifications (no `id`) are forwarded but don't get a response
    let is_notification = request.get("id").is_none();

    debug!(
        "routing method={} id={:?} session={}",
        method, id, session_id
    );

    match method {
        "initialize" => {
            handle_initialize(&id, &params, session_id, session_mgr, upstream_mgr, public_key_pem)
                .await
        }
        "tools/list" => handle_tools_list(&id, session_id, session_mgr, upstream_mgr).await,
        "tools/call" => {
            handle_tools_call(
                &id,
                &params,
                session_id,
                session_mgr,
                upstream_mgr,
                pipeline,
                egress_client,
                egress_hosts,
            )
            .await
        }
        _ => {
            // Transparent forward for all other methods
            if is_notification {
                match upstream_mgr.get_or_connect(session_id).await {
                    Ok(upstream) => {
                        let _ = upstream.forward_notification(request).await;
                    }
                    Err(e) => {
                        warn!("upstream connect failed for notification: {e}");
                    }
                }
                None
            } else {
                match upstream_mgr.get_or_connect(session_id).await {
                    Ok(upstream) => match upstream.forward(request).await {
                        Ok(resp) => Some(resp),
                        Err(e) => Some(jsonrpc_error(
                            -32603,
                            &id,
                            &format!("upstream error: {e}"),
                        )),
                    },
                    Err(e) => Some(jsonrpc_error(
                        -32603,
                        &id,
                        &format!("upstream connect error: {e}"),
                    )),
                }
            }
        }
    }
}

/// Handle `initialize`: forward to upstream, inject Proxy capabilities, extract session.
async fn handle_initialize(
    id: &Value,
    params: &Value,
    session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    _public_key_pem: &str,
) -> Option<Value> {
    // Extract session info from _meta
    let meta = params.get("_meta").unwrap_or(&Value::Null);
    let mut session = Session::from_meta(meta);
    // Use the session_id assigned by the transport layer
    session.session_id = session_id.to_string();
    debug!(
        "initialize: app_id={}, session_id={}, has_license={}",
        session.app_id,
        session.session_id,
        session.has_license()
    );
    session_mgr.insert(session_id.to_string(), session);

    // Get or create upstream connection for this session
    let upstream = match upstream_mgr.get_or_connect(session_id).await {
        Ok(u) => u,
        Err(e) => {
            return Some(jsonrpc_error(
                -32603,
                id,
                &format!("upstream connect failed: {e}"),
            ))
        }
    };

    // Forward initialize to upstream MCP Server
    let forward_req = serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "initialize",
        "params": params,
    });

    match upstream.forward(&forward_req).await {
        Ok(resp) => {
            // Mark session as upstream-initialized
            if let Some(mut s) = session_mgr.get(session_id) {
                s.upstream_initialized = true;
                session_mgr.update(session_id.to_string(), s);
            }

            // Inject Proxy capabilities into the response
            let mut resp = resp;
            if let Some(result) = resp.get_mut("result").and_then(|r| r.as_object_mut()) {
                if let Some(caps) = result
                    .get_mut("capabilities")
                    .and_then(|c| c.as_object_mut())
                {
                    caps.insert(
                        "virbiusProxy".to_string(),
                        serde_json::json!({
                            "securityPipeline": true,
                            "licenseVerification": true,
                            "engineEvaluate": true,
                            "fastPath": true,
                        }),
                    );
                }
            }
            Some(resp)
        }
        Err(e) => Some(jsonrpc_error(
            -32603,
            id,
            &format!("upstream initialize failed: {e}"),
        )),
    }
}

/// Handle `tools/list`: forward to upstream, filter by License allowed_tools.
async fn handle_tools_list(
    id: &Value,
    session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
) -> Option<Value> {
    let session = match session_mgr.get(session_id) {
        Some(s) => s,
        None => {
            return Some(jsonrpc_error(
                -32600,
                id,
                "session not initialized (call initialize first)",
            ))
        }
    };

    let upstream = match upstream_mgr.get_or_connect(session_id).await {
        Ok(u) => u,
        Err(e) => {
            return Some(jsonrpc_error(
                -32603,
                id,
                &format!("upstream connect failed: {e}"),
            ))
        }
    };

    let forward_req = serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "method": "tools/list",
    });

    match upstream.forward(&forward_req).await {
        Ok(resp) => {
            // Filter tools by License allowed_tools
            filter_tools_list(resp, &session)
        }
        Err(e) => Some(jsonrpc_error(
            -32603,
            id,
            &format!("upstream tools/list failed: {e}"),
        )),
    }
}

/// Filter the tools/list response by License allowed_tools.
fn filter_tools_list(mut resp: Value, session: &Session) -> Option<Value> {
    // If session has no License, we still return tools (fallback policy will filter at call time)
    if !session.has_license() {
        return Some(resp);
    }

    if let Some(result) = resp.get_mut("result").and_then(|r| r.as_object_mut()) {
        if let Some(tools) = result.get_mut("tools").and_then(|t| t.as_array_mut()) {
            let _ = tools; // No filtering at list level for P0
        }
    }
    Some(resp)
}

/// Handle `tools/call`: run the security pipeline, then either:
/// - For egress tools (curl/http_request/fetch): Proxy the HTTP request directly
///   with streaming response support (reqwest bytes_stream).
/// - For other tools: Forward to upstream MCP Server.
async fn handle_tools_call(
    id: &Value,
    params: &Value,
    session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    pipeline: &SharedPipeline,
    egress_client: &EgressClient,
    egress_hosts: &[String],
) -> Option<Value> {
    let mut session = match session_mgr.get(session_id) {
        Some(s) => s,
        None => {
            return Some(jsonrpc_error(
                -32600,
                id,
                "session not initialized (call initialize first)",
            ))
        }
    };

    let tool_name = params
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let args = params
        .get("arguments")
        .cloned()
        .unwrap_or(Value::Object(serde_json::Map::new()));

    if tool_name.is_empty() {
        return Some(jsonrpc_error_simple(
            VirbiusErrorCode::SchemaViolation,
            id.clone(),
            "",
            &session.trace_id,
            session.session_risk_score,
            Some("missing tool name"),
        ));
    }

    // Run security pipeline
    let result = pipeline.check_tool_call(&session, tool_name, &args).await;

    match result {
        PipelineResult::Allow { reason: _, .. } => {
            // Increment call count
            session.increment_calls();
            session_mgr.update(session_id.to_string(), session.clone());

            // Egress tools: Proxy HTTP request directly with streaming response
            if crate::egress::is_egress_tool(tool_name) {
                return Some(
                    proxy_egress_tool(id, tool_name, &args, egress_client, egress_hosts).await,
                );
            }

            // Non-egress tools: Forward to upstream MCP Server
            let upstream = match upstream_mgr.get_or_connect(session_id).await {
                Ok(u) => u,
                Err(e) => {
                    return Some(jsonrpc_error(
                        -32603,
                        id,
                        &format!("upstream connect failed: {e}"),
                    ))
                }
            };

            let forward_req = serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "tools/call",
                "params": params,
            });

            match upstream.forward(&forward_req).await {
                Ok(resp) => Some(resp),
                Err(e) => Some(jsonrpc_error(
                    -32603,
                    id,
                    &format!("upstream tools/call failed: {e}"),
                )),
            }
        }
        PipelineResult::Deny { code, reason, .. } => Some(jsonrpc_error_simple(
            code,
            id.clone(),
            tool_name,
            &session.trace_id,
            session.session_risk_score,
            Some(&reason),
        )),
    }
}

/// Proxy an egress tool call (curl/http_request/fetch) to an external API.
///
/// Uses reqwest `bytes_stream()` for streaming response reading, preventing OOM
/// on large responses. SSE (text/event-stream) responses are transparently
/// passed through as text content.
async fn proxy_egress_tool(
    id: &Value,
    tool_name: &str,
    args: &Value,
    egress_client: &EgressClient,
    egress_hosts: &[String],
) -> Value {
    // 1. Extract URL from tool arguments
    let url = match crate::egress::extract_url_from_args(tool_name, args) {
        Ok(u) => u,
        Err(e) => {
            warn!("egress tool '{}' url extraction failed: {}", tool_name, e);
            return crate::egress::egress_error_response(id, &e);
        }
    };

    // 2. Validate URL against egress allowlist
    if let Err(e) = crate::egress::validate_egress_url(&url, egress_hosts) {
        warn!("egress url validation failed: {}", e);
        return crate::egress::egress_error_response(id, &e);
    }

    // 3. Extract HTTP method and body from args
    let method = args
        .get("method")
        .and_then(|v| v.as_str())
        .unwrap_or("GET");
    let body = args.get("body").or_else(|| args.get("data"));

    // 4. Extract headers from args (filter happens inside proxy_request)
    let headers: Option<Vec<(String, String)>> = args
        .get("headers")
        .and_then(|h| h.as_object())
        .map(|map| {
            map.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect()
        });
    let headers_ref = headers.as_deref();

    // 5. Proxy the request with streaming response
    match egress_client
        .proxy_request(&url, method, body, headers_ref)
        .await
    {
        Ok(response) => {
            let result = crate::egress::to_mcp_result(&response);
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": result
            })
        }
        Err(e) => {
            warn!("egress proxy failed for {}: {}", url, e);
            crate::egress::egress_error_response(id, &e.to_string())
        }
    }
}

/// Build a JSON-RPC error response (internal server error).
fn jsonrpc_error(code: i32, id: &Value, message: &str) -> Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": {
            "code": code,
            "message": message
        }
    })
}
