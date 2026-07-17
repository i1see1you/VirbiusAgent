/// JSON-RPC method router: routes MCP protocol methods to handlers.
///
/// In single-upstream mode, behavior is identical to the original 1:1 proxy.
/// In multi-upstream mode:
/// - `initialize` is forwarded to all upstreams concurrently
/// - `tools/list` merges tools from all upstreams, prefixes conflicting names
/// - `tools/call` routes to the correct upstream via `tool_routes`

use serde_json::Value;
use tracing::{debug, warn};

use crate::egress::EgressClient;
use crate::error::{jsonrpc_error_simple, VirbiusErrorCode};
use crate::pipeline::{PipelineResult, SecurityPipeline, SharedPipeline};
use crate::session::{Session, SessionManager};
use crate::trace_collector::{SharedTraceCollector, TraceEvent};
use crate::upstream::UpstreamManager;
use virbius_core::{mask_pii_output, MemoryInterceptor, MemoryContext, MemoryWriteResult, TrustTagger, TrustTagInput, TrustTagResult};

/// Separator used for prefixed tool names in multi-upstream mode.
/// Only applied when the same tool name exists on multiple upstreams.
const TOOL_PREFIX_SEP: &str = "__";

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
    trace_collector: &SharedTraceCollector,
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
                trace_collector,
            )
            .await
        }
        _ => {
            // Transparent forward for all other methods
            if is_notification {
                if upstream_mgr.is_single_upstream() {
                    match upstream_mgr.get_or_connect_single(session_id).await {
                        Ok(upstream) => {
                            let _ = upstream.forward_notification(request).await;
                        }
                        Err(e) => {
                            warn!("upstream connect failed for notification: {e}");
                        }
                    }
                } else {
                    // Multi-upstream: forward to all (best-effort)
                    for name in upstream_mgr.upstream_names() {
                        if let Ok(upstream) = upstream_mgr.get_or_connect(session_id, name).await {
                            let _ = upstream.forward_notification(request).await;
                        }
                    }
                }
                None
            } else {
                if upstream_mgr.is_single_upstream() {
                    match upstream_mgr.get_or_connect_single(session_id).await {
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
                } else {
                    // Multi-upstream: forward to first upstream that succeeds
                    let mut last_err = None;
                    for name in upstream_mgr.upstream_names() {
                        match upstream_mgr.get_or_connect(session_id, name).await {
                            Ok(upstream) => match upstream.forward(request).await {
                                Ok(resp) => return Some(resp),
                                Err(e) => {
                                    last_err = Some(format!("upstream {name}: {e}"));
                                }
                            },
                            Err(e) => {
                                last_err = Some(format!("upstream {name} connect: {e}"));
                            }
                        }
                    }
                    Some(jsonrpc_error(
                        -32603,
                        &id,
                        &format!("all upstreams failed: {}", last_err.unwrap_or_default()),
                    ))
                }
            }
        }
    }
}

/// Handle `initialize`: forward to upstream(s), inject Proxy capabilities, extract session.
///
/// - Single-upstream: forward to the sole upstream (original behavior).
/// - Multi-upstream: forward to ALL upstreams concurrently, merge capabilities.
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

    if upstream_mgr.is_single_upstream() {
        // ── Single-upstream mode (original behavior) ──
        let upstream = match upstream_mgr.get_or_connect_single(session_id).await {
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
            "method": "initialize",
            "params": params,
        });

        match upstream.forward(&forward_req).await {
            Ok(resp) => {
                if let Some(mut s) = session_mgr.get(session_id) {
                    s.mark_upstream_initialized("default");
                    session_mgr.update(session_id.to_string(), s);
                }
                Some(inject_proxy_capabilities(resp))
            }
            Err(e) => Some(jsonrpc_error(
                -32603,
                id,
                &format!("upstream initialize failed: {e}"),
            )),
        }
    } else {
        // ── Multi-upstream mode ──
        let upstream_names = upstream_mgr.upstream_names();

        // Forward initialize to all upstreams concurrently
        let mut tasks = Vec::new();
        for name in &upstream_names {
            let name = name.to_string();
            let sid = session_id.to_string();
            let id_clone = id.clone();
            let params_clone = params.clone();
            let mgr_ref = upstream_mgr;
            // We can't easily spawn async tasks that borrow upstream_mgr,
            // so we connect sequentially (connections are cached after first call)
            let connect_result = mgr_ref.get_or_connect(&sid, &name).await;
            match connect_result {
                Ok(upstream) => {
                    let forward_req = serde_json::json!({
                        "jsonrpc": "2.0",
                        "id": &id_clone,
                        "method": "initialize",
                        "params": &params_clone,
                    });
                    tasks.push((name, upstream, forward_req));
                }
                Err(e) => {
                    warn!("upstream {} connect failed during initialize: {}", name, e);
                }
            }
        }

        if tasks.is_empty() {
            return Some(jsonrpc_error(
                -32603,
                id,
                "all upstreams failed to connect during initialize",
            ));
        }

        // Forward to each upstream and collect results
        let mut first_ok: Option<Value> = None;
        let mut last_err: Option<String> = None;

        for (name, upstream, forward_req) in &tasks {
            match upstream.forward(forward_req).await {
                Ok(resp) => {
                    if let Some(mut s) = session_mgr.get(session_id) {
                        s.mark_upstream_initialized(name);
                        session_mgr.update(session_id.to_string(), s);
                    }
                    if first_ok.is_none() {
                        first_ok = Some(resp);
                    }
                }
                Err(e) => {
                    warn!("upstream {} initialize failed: {}", name, e);
                    last_err = Some(format!("{}: {}", name, e));
                }
            }
        }

        match first_ok {
            Some(resp) => Some(inject_proxy_capabilities(resp)),
            None => Some(jsonrpc_error(
                -32603,
                id,
                &format!("all upstreams failed: {}", last_err.unwrap_or_default()),
            )),
        }
    }
}

/// Handle `tools/list`: forward to upstream(s), filter by License allowed_tools.
///
/// - Single-upstream: forward and filter (original behavior).
/// - Multi-upstream: fetch from ALL upstreams concurrently, merge tool lists,
///   prefix conflicting names, register routes, then filter.
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

    if upstream_mgr.is_single_upstream() {
        // ── Single-upstream mode (original behavior) ──
        let upstream = match upstream_mgr.get_or_connect_single(session_id).await {
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
            Ok(resp) => filter_tools_list(resp, &session),
            Err(e) => Some(jsonrpc_error(
                -32603,
                id,
                &format!("upstream tools/list failed: {e}"),
            )),
        }
    } else {
        // ── Multi-upstream mode ──
        let upstream_names = upstream_mgr.upstream_names();

        // Fetch tools/list from all upstreams, tracking which upstream
        // each tool came from.
        let mut tools_by_upstream: Vec<(String, Vec<Value>)> = Vec::new();
        let mut last_err: Option<String> = None;

        for name in &upstream_names {
            let upstream = match upstream_mgr.get_or_connect(session_id, name).await {
                Ok(u) => u,
                Err(e) => {
                    warn!("upstream {} connect failed for tools/list: {}", name, e);
                    last_err = Some(format!("{}: {}", name, e));
                    continue;
                }
            };

            let forward_req = serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "tools/list",
            });

            match upstream.forward(&forward_req).await {
                Ok(resp) => {
                    if let Some(tools) = resp
                        .get("result")
                        .and_then(|r| r.get("tools"))
                        .and_then(|t| t.as_array())
                    {
                        tools_by_upstream.push((name.to_string(), tools.clone()));
                    }
                }
                Err(e) => {
                    warn!("upstream {} tools/list failed: {}", name, e);
                    last_err = Some(format!("{}: {}", name, e));
                }
            }
        }

        if tools_by_upstream.is_empty() && last_err.is_some() {
            return Some(jsonrpc_error(
                -32603,
                id,
                &format!("all upstreams failed: {}", last_err.unwrap()),
            ));
        }

        // Merge tools from all upstreams, prefix conflicting names,
        // and register routes in upstream_mgr.
        let merged_tools = merge_tools_from_upstreams(tools_by_upstream, upstream_mgr);

        let merged = serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "tools": merged_tools
            }
        });

        filter_tools_list(merged, &session)
    }
}

/// Merge tools from multiple upstreams, prefixing conflicting names.
///
/// Returns the merged tool list and registers routes in `upstream_mgr`.
fn merge_tools_from_upstreams(
    tools_by_upstream: Vec<(String, Vec<Value>)>,
    upstream_mgr: &UpstreamManager,
) -> Vec<Value> {
    use std::collections::HashMap;

    // Count occurrences of each original tool name across upstreams
    let mut name_counts: HashMap<String, u32> = HashMap::new();
    for (upstream_name, tools) in &tools_by_upstream {
        // Use a set to count each name only once per upstream
        let mut seen_in_this_upstream = std::collections::HashSet::new();
        for tool in tools {
            let name = tool
                .get("name")
                .and_then(|n| n.as_str())
                .unwrap_or("")
                .to_string();
            if !name.is_empty() && seen_in_this_upstream.insert(name.clone()) {
                *name_counts.entry(name).or_insert(0) += 1;
            }
        }
        let _ = upstream_name; // suppress unused warning
    }

    // Clear old routes
    upstream_mgr.clear_tool_routes();

    let mut merged: Vec<Value> = Vec::new();

    for (upstream_name, tools) in tools_by_upstream {
        for tool in tools {
            let original_name = tool
                .get("name")
                .and_then(|n| n.as_str())
                .unwrap_or("")
                .to_string();

            if original_name.is_empty() {
                continue;
            }

            let has_conflict = name_counts.get(&original_name).copied().unwrap_or(0) > 1;
            let displayed_name = if has_conflict {
                format!("{}{}{}", upstream_name, TOOL_PREFIX_SEP, original_name)
            } else {
                original_name.clone()
            };

            // Register route: displayed_name → (upstream_name, original_name)
            upstream_mgr.register_tool_route(&displayed_name, &upstream_name, &original_name);

            // Clone tool and update its name
            let mut tool = tool;
            if let Some(obj) = tool.as_object_mut() {
                obj.insert("name".to_string(), Value::String(displayed_name.clone()));
            }

            // Inject upstream annotation for debugging
            if let Some(obj) = tool.as_object_mut() {
                obj.insert(
                    "x-virbius-upstream".to_string(),
                    Value::String(upstream_name.clone()),
                );
            }

            merged.push(tool);
        }
    }

    merged
}

/// Filter the tools/list response by License allowed_tools.
///
/// If the session has no License or `allowed_tools` is empty (meaning all tools
/// are permitted), no filtering is applied. Otherwise, only tools whose `name`
/// appears in `allowed_tools` are retained.
///
/// In multi-upstream mode, tool names may be prefixed (e.g., `fs__read_file`).
/// The filter checks the **original** tool name (after stripping prefix) against
/// the License allowlist.
fn filter_tools_list(mut resp: Value, session: &Session) -> Option<Value> {
    // If session has no License, we still return tools (fallback policy will filter at call time)
    if !session.has_license() {
        return Some(resp);
    }

    // Empty allowed_tools means all tools are allowed (License wildcard)
    if session.allowed_tools.is_empty() {
        return Some(resp);
    }

    if let Some(result) = resp.get_mut("result").and_then(|r| r.as_object_mut()) {
        if let Some(tools) = result.get_mut("tools").and_then(|t| t.as_array_mut()) {
            let allowed = &session.allowed_tools;
            tools.retain(|tool| {
                let displayed_name = tool
                    .get("name")
                    .and_then(|n| n.as_str())
                    .unwrap_or("");

                // Check both the displayed name and the original name (strip prefix).
                // In single-upstream mode there's no prefix, so displayed == original.
                let original_name = strip_tool_prefix(displayed_name);

                let matched = allowed.contains(&displayed_name.to_string())
                    || allowed.contains(&original_name.to_string());

                if matched {
                    return true;
                }

                // Also check x-virbius-upstream annotation for debugging
                debug!(
                    "tools/list filtered out: {} (original: {})",
                    displayed_name, original_name
                );
                false
            });
            debug!(
                "tools/list filtered: {} tools remaining after License allowlist",
                tools.len()
            );
        }
    }
    Some(resp)
}

/// Strip the upstream prefix from a tool name, if present.
///
/// `fs__read_file` → `read_file`
/// `read_file` → `read_file` (no prefix)
fn strip_tool_prefix(name: &str) -> &str {
    match name.find(TOOL_PREFIX_SEP) {
        Some(pos) => &name[pos + TOOL_PREFIX_SEP.len()..],
        None => name,
    }
}

/// Handle `tools/call`: run the security pipeline, then either:
/// - For egress tools (curl/http_request/fetch): Proxy the HTTP request directly
///   with streaming response support (reqwest bytes_stream).
/// - For other tools: Forward to upstream MCP Server.
///
/// In multi-upstream mode, the tool name is resolved via `tool_routes` to
/// determine the correct upstream and the original tool name.
async fn handle_tools_call(
    id: &Value,
    params: &Value,
    session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    pipeline: &SharedPipeline,
    egress_client: &EgressClient,
    egress_hosts: &[String],
    trace_collector: &SharedTraceCollector,
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

    let displayed_tool_name = params
        .get("name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let args = params
        .get("arguments")
        .cloned()
        .unwrap_or(Value::Object(serde_json::Map::new()));

    if displayed_tool_name.is_empty() {
        return Some(jsonrpc_error_simple(
            VirbiusErrorCode::SchemaViolation,
            id.clone(),
            "",
            &session.trace_id,
            session.session_risk_score,
            Some("missing tool name"),
        ));
    }

    // Resolve tool route: determine upstream_name and original_tool_name.
    // In single-upstream mode, route_tool always returns the sole upstream.
    let (upstream_name, original_tool_name) = match upstream_mgr.route_tool(displayed_tool_name) {
        Some(route) => route,
        None => {
            // Tool not in routes. In multi-upstream mode, this means tools/list
            // wasn't called or the tool doesn't exist. Try to use the displayed
            // name as-is and pick the first upstream as best-effort.
            if upstream_mgr.is_single_upstream() {
                (upstream_mgr.upstream_names()[0].to_string(), displayed_tool_name.to_string())
            } else {
                // Try stripping a prefix in case the route wasn't registered
                let stripped = strip_tool_prefix(displayed_tool_name);
                if stripped != displayed_tool_name {
                    // Has a prefix — try to find the upstream by the prefix
                    let prefix_end = displayed_tool_name
                        .find(TOOL_PREFIX_SEP)
                        .unwrap_or(0);
                    let possible_upstream = &displayed_tool_name[..prefix_end];
                    let names = upstream_mgr.upstream_names();
                    if names.iter().any(|n| *n == possible_upstream) {
                        (possible_upstream.to_string(), stripped.to_string())
                    } else {
                        return Some(jsonrpc_error(
                            -32602,
                            id,
                            &format!(
                                "unknown tool '{}' — call tools/list first to discover available tools",
                                displayed_tool_name
                            ),
                        ));
                    }
                } else {
                    return Some(jsonrpc_error(
                        -32602,
                        id,
                        &format!(
                            "unknown tool '{}' — call tools/list first to discover available tools",
                            displayed_tool_name
                        ),
                    ));
                }
            }
        }
    };

    // Check for challenge token in _meta (retry after approval)
    let meta = params.get("_meta").unwrap_or(&Value::Null);
    let challenge_token = SecurityPipeline::extract_challenge_token(meta);

    // ── P1.3: Memory Interceptor (write-only) ──
    let memory_interceptor = MemoryInterceptor::from_manifest();
    if memory_interceptor.is_enabled() && memory_interceptor.is_memory_write_tool(&original_tool_name) {
        // Extract content from args (assume there's a "content" field)
        let content = args.get("content").and_then(|v| v.as_str()).unwrap_or("");
        
        let mem_ctx = MemoryContext {
            session_id: session.session_id.clone(),
            trace_id: session.trace_id.clone(),
            tool_name: original_tool_name.clone(),
        };
        
        let mem_result = memory_interceptor.intercept_write(content, &mem_ctx);
        
        if !mem_result.allowed {
            // Local check failed (size, credentials, PII)
            let reason = mem_result.block_reason.unwrap_or_else(|| "memory_write_blocked".into());
            return Some(jsonrpc_error_simple(
                VirbiusErrorCode::MemoryWriteBlocked,
                id.clone(),
                &original_tool_name,
                &session.trace_id,
                session.session_risk_score,
                Some(&reason),
            ));
        }
        
        // Local checks passed — now perform LLM-based injection detection if needed
        if mem_result.need_llm_check {
            match pipeline.check_memory(&session, &original_tool_name, &mem_result.sanitized_content).await {
                Ok(resp) if !resp.allowed => {
                    // LLM detected injection
                    let reason = resp.block_reason.unwrap_or_else(|| "prompt_injection_detected".into());
                    return Some(jsonrpc_error_simple(
                        VirbiusErrorCode::MemoryWriteBlocked,
                        id.clone(),
                        &original_tool_name,
                        &session.trace_id,
                        session.session_risk_score,
                        Some(&reason),
                    ));
                }
                Err(e) => {
                    // Engine unavailable — fail-closed for memory writes
                    warn!("memory check failed (engine unavailable): {}", e);
                    return Some(jsonrpc_error_simple(
                        VirbiusErrorCode::MemoryWriteBlocked,
                        id.clone(),
                        &original_tool_name,
                        &session.trace_id,
                        session.session_risk_score,
                        Some("fail_closed:engine_unavailable"),
                    ));
                }
                _ => {
                    // All checks passed — write allowed
                    // (Note: the content in args is already sanitized; we don't modify args here)
                }
            }
        }
    }

    // If a challenge token is present, verify it before running the pipeline
    if let Some(token) = &challenge_token {
        match pipeline.verify_challenge_token(token, &original_tool_name, &args, &session).await {
            Ok(true) => {
                // Token verified — bypass pipeline, allow directly
                debug!("challenge token verified, allowing tool call: tool={}", original_tool_name);
                session.increment_calls();
                session_mgr.update(session_id.to_string(), session.clone());

                // Forward to upstream (same as Allow path)
                if crate::egress::is_egress_tool(&original_tool_name) {
                    return Some(
                        proxy_egress_tool(id, &original_tool_name, &args, egress_client, egress_hosts).await,
                    );
                }

                let upstream = match upstream_mgr.get_or_connect(session_id, &upstream_name).await {
                    Ok(u) => u,
                    Err(e) => {
                        return Some(jsonrpc_error(
                            -32603,
                            id,
                            &format!("upstream connect failed: {e}"),
                        ))
                    }
                };

                let forward_params = if displayed_tool_name != original_tool_name {
                    let mut p = params.clone();
                    if let Some(obj) = p.as_object_mut() {
                        obj.insert("name".to_string(), Value::String(original_tool_name.clone()));
                    }
                    p
                } else {
                    params.clone()
                };

                // Strip challenge_token from _meta before forwarding
                let mut forward_req = serde_json::json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "method": "tools/call",
                    "params": forward_params,
                });
                if let Some(obj) = forward_req.get_mut("params").and_then(|p| p.as_object_mut()) {
                    if let Some(m) = obj.get_mut("_meta").and_then(|m| m.as_object_mut()) {
                        m.remove("challenge_token");
                    }
                }

                match upstream.forward(&forward_req).await {
                    Ok(resp) => return Some(resp),
                    Err(e) => return Some(jsonrpc_error(
                        -32603,
                        id,
                        &format!("upstream tools/call failed: {e}"),
                    )),
                }
            }
            Ok(false) => {
                return Some(jsonrpc_error_simple(
                    VirbiusErrorCode::ChallengeRequired,
                    id.clone(),
                    &original_tool_name,
                    &session.trace_id,
                    session.session_risk_score,
                    Some("challenge token invalid or expired"),
                ));
            }
            Err(e) => {
                warn!("challenge token verify error: {e}");
                // Fail-closed: deny if verify endpoint is unavailable
                return Some(jsonrpc_error_simple(
                    VirbiusErrorCode::ChallengeRequired,
                    id.clone(),
                    &original_tool_name,
                    &session.trace_id,
                    session.session_risk_score,
                    Some("challenge verify service unavailable"),
                ));
            }
        }
    }

    // ── Trace: record tool_call event (before pipeline) ──
    let tool_call_step_id = uuid::Uuid::new_v4().to_string();
    let parent_step_id = session.last_step_id.clone();
    let step_seq = session.next_step_seq();
    let trace_event = TraceEvent::tool_call(
        &session,
        &tool_call_step_id,
        parent_step_id.as_deref(),
        step_seq,
        &original_tool_name,
        &args,
    );
    trace_collector.record(trace_event).await;
    let trace_start = std::time::Instant::now();

    // Run security pipeline with the ORIGINAL tool name (before any prefix stripping).
    // The License allowed_tools contains original names, not prefixed ones.
    let result = pipeline.check_tool_call(&session, &original_tool_name, &args).await;

    match result {
        PipelineResult::Allow { reason: _, risk_score, .. } => {
            // Write back risk score from engine (if evaluated)
            if let Some(score) = risk_score {
                session.session_risk_score = score;
            }
            // Increment call count
            session.increment_calls();
            session.set_last_step_id(tool_call_step_id.clone());
            session_mgr.update(session_id.to_string(), session.clone());

            // ── Trace: update tool_call with decision ──
            let tc_event = TraceEvent::tool_call(
                &session, &tool_call_step_id, parent_step_id.as_deref(), step_seq,
                &original_tool_name, &args,
            ).with_decision("allow", None, None, risk_score);
            trace_collector.record(tc_event).await;

            // Egress tools: Proxy HTTP request directly with streaming response.
            // Check against the original tool name.
            if crate::egress::is_egress_tool(&original_tool_name) {
                let mut resp = proxy_egress_tool(id, &original_tool_name, &args, egress_client, egress_hosts).await;
                // ── Output PII masking ──
                mask_pii_in_response(&mut resp, &original_tool_name, &session.session_id);
                // ── Trust boundary tagging (high/network risk only) ──
                tag_tool_result(&mut resp, &original_tool_name, false);
                // ── Trace: record tool_result ──
                let duration_ms = trace_start.elapsed().as_millis() as u64;
                let result_step_id = uuid::Uuid::new_v4().to_string();
                let result_seq = session.next_step_seq();
                let tr_event = TraceEvent::tool_result(
                    &session, &result_step_id, &tool_call_step_id, result_seq,
                    "success", duration_ms, &resp,
                );
                trace_collector.record(tr_event).await;
                session.set_last_step_id(result_step_id);
                session_mgr.update(session_id.to_string(), session);
                return Some(resp);
            }

            // Non-egress tools: Forward to the resolved upstream.
            // Use the ORIGINAL tool name (not prefixed) when forwarding.
            let upstream = match upstream_mgr.get_or_connect(session_id, &upstream_name).await {
                Ok(u) => u,
                Err(e) => {
                    return Some(jsonrpc_error(
                        -32603,
                        id,
                        &format!("upstream connect failed: {e}"),
                    ))
                }
            };

            // Build forwarded request with original tool name
            let forward_params = if displayed_tool_name != original_tool_name {
                // Replace the tool name in params with the original name
                let mut p = params.clone();
                if let Some(obj) = p.as_object_mut() {
                    obj.insert("name".to_string(), Value::String(original_tool_name.clone()));
                }
                p
            } else {
                params.clone()
            };

            let forward_req = serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "method": "tools/call",
                "params": forward_params,
            });

            match upstream.forward(&forward_req).await {
                Ok(mut resp) => {
                    // ── Output PII masking ──
                    mask_pii_in_response(&mut resp, &original_tool_name, &session.session_id);
                    // ── Trust boundary tagging (high/network risk only) ──
                    tag_tool_result(&mut resp, &original_tool_name, false);
                    // ── Trace: record tool_result ──
                    let duration_ms = trace_start.elapsed().as_millis() as u64;
                    let result_step_id = uuid::Uuid::new_v4().to_string();
                    let result_seq = session.next_step_seq();
                    let tr_event = TraceEvent::tool_result(
                        &session, &result_step_id, &tool_call_step_id, result_seq,
                        "success", duration_ms, &resp,
                    );
                    trace_collector.record(tr_event).await;
                    session.set_last_step_id(result_step_id);
                    session_mgr.update(session_id.to_string(), session);
                    Some(resp)
                }
                Err(e) => {
                    // ── Trace: record tool_result error ──
                    let duration_ms = trace_start.elapsed().as_millis() as u64;
                    let result_step_id = uuid::Uuid::new_v4().to_string();
                    let result_seq = session.next_step_seq();
                    let err_val = serde_json::json!({"error": e.to_string()});
                    let tr_event = TraceEvent::tool_result(
                        &session, &result_step_id, &tool_call_step_id, result_seq,
                        "error", duration_ms, &err_val,
                    );
                    trace_collector.record(tr_event).await;
                    session.set_last_step_id(result_step_id);
                    session_mgr.update(session_id.to_string(), session);
                    Some(jsonrpc_error(
                        -32603,
                        id,
                        &format!("upstream tools/call failed: {e}"),
                    ))
                }
            }
        }
        PipelineResult::Deny { code, reason, risk_score, .. } => {
            // Write back risk score from engine (if evaluated)
            if let Some(score) = risk_score {
                session.session_risk_score = score;
            }
            session_mgr.update(session_id.to_string(), session.clone());

            // ── Trace: update tool_call with deny decision ──
            let tc_event = TraceEvent::tool_call(
                &session, &tool_call_step_id, parent_step_id.as_deref(), step_seq,
                &original_tool_name, &args,
            ).with_decision("block", None, Some(&reason), risk_score);
            trace_collector.record(tc_event).await;

            Some(jsonrpc_error_simple(
                code,
                id.clone(),
                &original_tool_name,
                &session.trace_id,
                session.session_risk_score,
                Some(&reason),
            ))
        }
        PipelineResult::Challenge { challenge_id, args_hash, rule_id, reason, risk_score } => {
            // Write back risk score from engine
            session.session_risk_score = risk_score;
            session_mgr.update(session_id.to_string(), session.clone());

            // ── Trace: update tool_call with challenge decision ──
            let tc_event = TraceEvent::tool_call(
                &session, &tool_call_step_id, parent_step_id.as_deref(), step_seq,
                &original_tool_name, &args,
            ).with_decision("challenge", rule_id.as_deref(), Some(&reason), Some(risk_score));
            trace_collector.record(tc_event).await;

            // Return JSON-RPC error -32011 with challenge details
            let data = serde_json::json!({
                "tool_name": original_tool_name,
                "rule_id": rule_id,
                "trace_id": session.trace_id,
                "session_risk_score": risk_score,
                "http_analog": 403,
                "challenge_id": challenge_id,
                "args_hash": args_hash,
                "reason": reason,
            });
            Some(serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "error": {
                    "code": VirbiusErrorCode::ChallengeRequired as i32,
                    "message": VirbiusErrorCode::ChallengeRequired.message(),
                    "data": data
                }
            }))
        }
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

/// Inject Virbius Proxy capabilities into an initialize response.
fn inject_proxy_capabilities(mut resp: Value) -> Value {
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
                    "multiUpstream": true,
                }),
            );
        }
    }
    resp
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

/// Apply output PII masking to a JSON-RPC tool call response.
///
/// Navigates `resp.result.content[]` and masks PII in any `text` field.
/// If the tool is in the exempt list, or masking is disabled, the response
/// is returned unmodified.
fn mask_pii_in_response(resp: &mut Value, tool_name: &str, session_id: &str) {
    // Navigate to resp.result.content (array)
    let Some(result) = resp.get_mut("result") else {
        return;
    };
    let Some(content_arr) = result.get_mut("content").and_then(|c| c.as_array_mut()) else {
        return;
    };

    let mut any_masked = false;
    for item in content_arr.iter_mut() {
        // Only mask text-type content items
        if item.get("type").and_then(|t| t.as_str()) != Some("text") {
            continue;
        }
        if let Some(text) = item.get_mut("text").and_then(|t| t.as_str().map(|s| s.to_string())) {
            let mask_result = mask_pii_output(&text, tool_name, Some(session_id));
            if mask_result.masked {
                any_masked = true;
                if let Some(text_field) = item.get_mut("text") {
                    *text_field = Value::String(mask_result.text);
                }
            }
        }
    }

    if any_masked {
        debug!(
            "output PII masked for tool '{}' in session '{}'",
            tool_name, session_id
        );
    }
}

/// Wrap high/network risk tool results with explicit trust boundary tags.
///
/// Only applied when `trust_layering_enabled` is true in SdkConfig and the
/// tool's risk class is high or network.  The wrapping is added to the text
/// content of the JSON-RPC response after PII masking.
fn tag_tool_result(resp: &mut Value, tool_name: &str, tainted: bool) {
    let manifest = virbius_core::manifest::load();
    if !manifest.sdk_config.trust_layering_enabled {
        return;
    }
    let risk_class = virbius_core::manifest::tool_risk_class(tool_name);
    let tagged_classes: Vec<&str> = manifest
        .sdk_config
        .trust_tagged_risk_classes
        .iter()
        .map(|s| s.as_str())
        .collect::<Vec<_>>();
    if !tagged_classes.contains(&risk_class.as_str()) {
        return;
    }

    let Some(result) = resp.get_mut("result") else {
        return;
    };
    let Some(content_arr) = result.get_mut("content").and_then(|c| c.as_array_mut()) else {
        return;
    };

    for item in content_arr.iter_mut() {
        if item.get("type").and_then(|t| t.as_str()) != Some("text") {
            continue;
        }
        if let Some(text) = item
            .get_mut("text")
            .and_then(|t| t.as_str().map(|s| s.to_string()))
        {
            let tag_result = TrustTagger::tag(TrustTagInput {
                tool_name,
                risk_class: &risk_class,
                tool_result: &text,
                tainted,
            });
            if let TrustTagResult::Wrapped { tagged_text, .. } = tag_result {
                if let Some(text_field) = item.get_mut("text") {
                    *text_field = Value::String(tagged_text);
                }
                debug!(
                    "trust boundary applied for tool '{}' (risk_class={})",
                    tool_name, risk_class
                );
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::session::Session;
    use base64::Engine;

    /// Helper: build a Session with the given allowed_tools in a fake JWT.
    fn make_session_with_tools(tools: Vec<&str>) -> Session {
        let payload = serde_json::json!({
            "app_id": "test",
            "allowed_tools": tools,
        });
        let payload_b64 =
            base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(serde_json::to_vec(&payload).unwrap());
        let jwt = format!("header.{}.sig", payload_b64);
        Session::from_meta(&serde_json::json!({ "app_id": "test", "license_jwt": jwt }))
    }

    fn make_session_no_license() -> Session {
        Session::from_meta(&serde_json::json!({ "app_id": "test" }))
    }

    fn make_tools_list_response() -> Value {
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "tools": [
                    { "name": "read_file", "description": "Read" },
                    { "name": "search", "description": "Search" },
                    { "name": "execute_python", "description": "Exec" },
                    { "name": "delete_file", "description": "Delete" }
                ]
            }
        })
    }

    #[test]
    fn test_filter_tools_list_with_license() {
        let session = make_session_with_tools(vec!["read_file", "search"]);
        let resp = make_tools_list_response();
        let filtered = filter_tools_list(resp, &session).unwrap();

        let tools = filtered["result"]["tools"].as_array().unwrap();
        assert_eq!(tools.len(), 2);
        let names: Vec<&str> = tools
            .iter()
            .map(|t| t["name"].as_str().unwrap())
            .collect();
        assert!(names.contains(&"read_file"));
        assert!(names.contains(&"search"));
        assert!(!names.contains(&"execute_python"));
        assert!(!names.contains(&"delete_file"));
    }

    #[test]
    fn test_filter_tools_list_no_license() {
        let session = make_session_no_license();
        let resp = make_tools_list_response();
        let filtered = filter_tools_list(resp, &session).unwrap();

        let tools = filtered["result"]["tools"].as_array().unwrap();
        assert_eq!(tools.len(), 4); // No filtering without License
    }

    #[test]
    fn test_filter_tools_list_empty_allowed() {
        // License with empty allowed_tools = wildcard (all allowed)
        let session = make_session_with_tools(vec![]);
        let resp = make_tools_list_response();
        let filtered = filter_tools_list(resp, &session).unwrap();

        let tools = filtered["result"]["tools"].as_array().unwrap();
        assert_eq!(tools.len(), 4); // No filtering for wildcard
    }

    #[test]
    fn test_filter_tools_list_no_matching_tools() {
        let session = make_session_with_tools(vec!["nonexistent_tool"]);
        let resp = make_tools_list_response();
        let filtered = filter_tools_list(resp, &session).unwrap();

        let tools = filtered["result"]["tools"].as_array().unwrap();
        assert_eq!(tools.len(), 0); // All filtered out
    }

    #[test]
    fn test_filter_tools_list_prefixed_names() {
        // In multi-upstream mode, tools may be prefixed.
        // The filter should match against the original (stripped) name.
        let session = make_session_with_tools(vec!["read_file"]);
        let resp = serde_json::json!({
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "tools": [
                    { "name": "fs__read_file", "description": "Read from fs" },
                    { "name": "backup__read_file", "description": "Read from backup" },
                    { "name": "search", "description": "Search" }
                ]
            }
        });
        let filtered = filter_tools_list(resp, &session).unwrap();
        let tools = filtered["result"]["tools"].as_array().unwrap();
        // Both prefixed read_file variants should pass (original name matches)
        // search should be filtered out (not in allowed_tools)
        assert_eq!(tools.len(), 2);
        let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
        assert!(names.contains(&"fs__read_file"));
        assert!(names.contains(&"backup__read_file"));
    }

    #[test]
    fn test_strip_tool_prefix() {
        assert_eq!(strip_tool_prefix("read_file"), "read_file");
        assert_eq!(strip_tool_prefix("fs__read_file"), "read_file");
        assert_eq!(strip_tool_prefix("gh__create_issue"), "create_issue");
    }
}
