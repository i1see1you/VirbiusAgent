/// JSON-RPC method router: routes MCP protocol methods to handlers.
///
/// In single-upstream mode, behavior is identical to the original 1:1 proxy.
/// In multi-upstream mode:
/// - `initialize` is forwarded to all upstreams concurrently
/// - `tools/list` merges tools from all upstreams, prefixes conflicting names
/// - `tools/call` routes to the correct upstream via `tool_routes`
use dashmap::DashMap;
use serde_json::Value;
use tracing::{debug, info, warn};

use crate::egress::EgressClient;
use crate::error::{jsonrpc_error_simple, VirbiusErrorCode};
use crate::pipeline::{OutputReviewResponse, PipelineResult, SecurityPipeline, SharedPipeline};
use crate::session::{Session, SessionManager};
use crate::trace_collector::{SharedTraceCollector, TraceEvent};
use crate::upstream::UpstreamManager;
use virbius_core::{
    mask_pii_output, MemoryContext, MemoryInterceptor, TrustTagInput, TrustTagResult, TrustTagger,
};

/// Separator used for prefixed tool names in multi-upstream mode.
/// Only applied when the same tool name exists on multiple upstreams.
const TOOL_PREFIX_SEP: &str = "__";

// ── Node-level local-exec settings (P1d) ────────────────────────────────────
//
// Local code execution is gvisor-only and fail-closed: without an explicit
// node `local_exec.mode = "gvisor"` it is disabled, and no unsandboxed /
// Landlock fallback exists. Initialized once at startup from config; tests
// call `init_local_exec_settings` to opt in.

/// Local execution mode. `Disabled` is the safe default.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalExecMode {
    Disabled,
    Gvisor,
}

impl LocalExecMode {
    pub fn as_str(&self) -> &'static str {
        match self {
            Self::Disabled => "disabled",
            Self::Gvisor => "gvisor",
        }
    }
}

#[derive(Debug, Clone)]
pub struct LocalExecSettings {
    pub mode: LocalExecMode,
    /// Maximum accepted code/command payload (bytes).
    pub code_bytes: usize,
    /// Maximum concurrent local executions on this node (reject above).
    pub concurrency: usize,
}

impl Default for LocalExecSettings {
    fn default() -> Self {
        Self {
            mode: LocalExecMode::Disabled,
            code_bytes: 64 * 1024,
            concurrency: 8,
        }
    }
}

static LOCAL_EXEC_SETTINGS: std::sync::OnceLock<LocalExecSettings> = std::sync::OnceLock::new();
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
static LOCAL_EXEC_INFLIGHT: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Initialize node-level local-exec settings from config. Called once at
/// startup; later calls are ignored (first wins, matching OnceLock).
pub fn init_local_exec_settings(settings: LocalExecSettings) {
    let _ = LOCAL_EXEC_SETTINGS.set(settings);
}

fn local_exec_settings() -> LocalExecSettings {
    LOCAL_EXEC_SETTINGS.get().cloned().unwrap_or_default()
}

/// Rejection-based in-flight guard: local exec rejects overload instead of
/// queueing unboundedly (per-tenant fair queues arrive with P3).
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
struct InflightGuard;

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
impl InflightGuard {
    fn acquire(max: usize) -> Option<Self> {
        let cur = LOCAL_EXEC_INFLIGHT.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        if cur >= max {
            LOCAL_EXEC_INFLIGHT.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
            return None;
        }
        Some(Self)
    }
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
impl Drop for InflightGuard {
    fn drop(&mut self) {
        LOCAL_EXEC_INFLIGHT.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
    }
}

/// Process a single JSON-RPC request and return a response.
///
/// `transport_session_id` identifies the transport connection (SSE channel, stdio).
/// It is resolved to a logical session ID via `conn_to_session` mapping;
/// for `initialize` (no mapping yet) it falls back to the transport ID.
/// Returns `Some(response)` for requests (have `id`), `None` for notifications.
#[allow(clippy::too_many_arguments)]
pub async fn route_request(
    request: &Value,
    transport_session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    pipeline: &SharedPipeline,
    egress_client: &EgressClient,
    egress_hosts: &[String],
    public_key_pem: &str,
    fallback_license_jwt: &str,
    trace_collector: &SharedTraceCollector,
    conn_to_session: &DashMap<String, String>,
) -> Option<Value> {
    let method = request.get("method").and_then(|v| v.as_str()).unwrap_or("");
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let params = request.get("params").cloned().unwrap_or(Value::Null);

    // Resolve logical session ID from transport connection ID.
    // For initialize (no mapping yet) this falls back to transport_session_id.
    let session_id: String = conn_to_session
        .get(transport_session_id)
        .map(|e| e.value().clone())
        .unwrap_or_else(|| transport_session_id.to_string());
    session_mgr.touch(&session_id);

    // Notifications (no `id`) are forwarded but don't get a response
    let is_notification = request.get("id").is_none();

    debug!(
        "routing method={} id={:?} session={}",
        method, id, session_id
    );

    match method {
        "initialize" => {
            handle_initialize(
                &id,
                &params,
                transport_session_id,
                session_mgr,
                upstream_mgr,
                public_key_pem,
                fallback_license_jwt,
                conn_to_session,
            )
            .await
        }
        "tools/list" => handle_tools_list(&id, &session_id, session_mgr, upstream_mgr).await,
        "tools/call" => {
            handle_tools_call(
                &id,
                &params,
                &session_id,
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
            // stdio has no HTTP surface, so expose a TRIMMED runtime snapshot
            // over JSON-RPC (operator diagnostics only). License claims and
            // the recent-decisions ring are served by the admin listener, not
            // here: an agent (LLM) can call this method over stdio too.
            if method == "virbius/status" {
                return Some(virbius_status_response(&id, session_mgr));
            }
            // Transparent forward for all other methods
            if is_notification {
                // Cancellation aborts the matching in-flight request on
                // transports that support it (Streamable HTTP), then is
                // forwarded as a notification for transports that use the
                // protocol-level cancel message.
                if request.get("method").and_then(Value::as_str) == Some("notifications/cancelled")
                {
                    if let Some(request_id) = request
                        .get("params")
                        .and_then(|p| p.get("requestId"))
                        .cloned()
                    {
                        upstream_mgr.cancel_request(&session_id, &request_id);
                    }
                }
                if upstream_mgr.is_single_for(&session_id) {
                    match upstream_mgr.get_or_connect_single(&session_id).await {
                        Ok(upstream) => {
                            let _ = upstream.forward_notification(request).await;
                        }
                        Err(e) => {
                            warn!("upstream connect failed for notification: {e}");
                        }
                    }
                } else {
                    // Multi-upstream: forward to all (best-effort)
                    for name in upstream_mgr.names_for(&session_id) {
                        if let Ok(upstream) = upstream_mgr.get_or_connect(&session_id, &name).await
                        {
                            let _ = upstream.forward_notification(request).await;
                        }
                    }
                }
                None
            } else {
                if upstream_mgr.is_single_for(&session_id) {
                    match upstream_mgr.get_or_connect_single(&session_id).await {
                        Ok(upstream) => match upstream.forward(request).await {
                            Ok(resp) => Some(resp),
                            Err(e) => Some(upstream_rpc_error(&id, "upstream error", &e)),
                        },
                        Err(e) => Some(upstream_rpc_error(&id, "upstream connect error", &e)),
                    }
                } else {
                    // Multi-upstream: forward to first upstream that succeeds
                    let mut last_err = None;
                    for name in upstream_mgr.names_for(&session_id) {
                        match upstream_mgr.get_or_connect(&session_id, &name).await {
                            Ok(upstream) => match upstream.forward(request).await {
                                Ok(resp) => return Some(resp),
                                Err(e) => {
                                    warn!("upstream {name} forward failed: {e}");
                                    last_err = Some(format!("upstream {name} forward failed"));
                                }
                            },
                            Err(e) => {
                                warn!("upstream {name} connect failed: {e}");
                                last_err = Some(format!("upstream {name} connect failed"));
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
///
/// The logical session ID is determined from `_meta.session_id` (or auto-generated).
/// On reconnect (same logical session ID already in SessionManager), accumulated
/// state (risk_score, tool_call_count, trace_id, step_seq) is preserved.
#[allow(clippy::too_many_arguments)]
/// Build the `virbius/status` JSON-RPC response (stdio has no HTTP surface).
/// Trimmed operator snapshot: version, session count, local-exec mode. No
/// license claims and no recent decisions — those belong to the admin
/// listener, not to a channel any agent can speak on.
fn virbius_status_response(id: &Value, session_mgr: &SessionManager) -> Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "result": {
            "version": env!("CARGO_PKG_VERSION"),
            "sessions_active": session_mgr.len(),
            "local_exec": {
                "mode": local_exec_settings().mode.as_str(),
                "canonical": ["virbius__shell", "virbius__python", "virbius__node"],
                "sandbox": "gvisor"
            },
        }
    })
}

// Session wiring is passed as explicit params; bundling them into a struct
// would obscure the call sites more than it helps.
#[allow(clippy::too_many_arguments)]
pub(crate) async fn handle_initialize(
    id: &Value,
    params: &Value,
    transport_session_id: &str,
    session_mgr: &SessionManager,
    upstream_mgr: &UpstreamManager,
    _public_key_pem: &str,
    fallback_license_jwt: &str,
    conn_to_session: &DashMap<String, String>,
) -> Option<Value> {
    // Extract session info from _meta; session.session_id comes from
    // _meta.session_id (or auto-generated UUID if not provided).
    let meta = params.get("_meta").unwrap_or(&Value::Null);
    let mut session = Session::from_meta(meta);

    // Fallback: if Agent did not pass _meta.license_jwt, use the License JWT
    // from config file (if configured).
    if !session.has_license() && !fallback_license_jwt.is_empty() {
        session.apply_fallback_license(fallback_license_jwt);
        info!(
            "initialize: using fallback license from file, app_id={}",
            session.app_id
        );
    }

    let logical_sid = session.session_id.clone();

    // Session identity binding (P1c): once a transport is bound to a logical
    // session, a second initialize on the SAME transport may only re-confirm
    // that session — switching logical sessions (and thus identity/license)
    // on one connection is rejected.
    if let Some(existing) = conn_to_session.get(transport_session_id) {
        let bound_sid = existing.value().clone();
        if bound_sid != logical_sid {
            return Some(jsonrpc_error_simple(
                VirbiusErrorCode::SessionIdentityMismatch,
                id.clone(),
                "",
                &session.trace_id,
                0,
                Some(&format!(
                    "this connection is already bound to session '{}'; initializing a different session_id on the same connection is not allowed",
                    bound_sid
                )),
            ));
        }
    }
    // Reconnect: the original session's identity (app/tenant) must match.
    if let Some(old) = session_mgr.get(&logical_sid) {
        if old.app_id != session.app_id || old.tenant_id != session.tenant_id {
            return Some(jsonrpc_error_simple(
                VirbiusErrorCode::SessionIdentityMismatch,
                id.clone(),
                "",
                &session.trace_id,
                0,
                Some("reconnect requires the same app_id/tenant identity as the original session"),
            ));
        }
    }

    // Reconnect: preserve accumulated state from the previous connection
    let is_reconnect = session_mgr.get(&logical_sid).is_some();
    if let Some(old) = session_mgr.get(&logical_sid) {
        session.session_risk_score = old.session_risk_score;
        session.tool_call_count = old.tool_call_count;
        session.trace_id = old.trace_id;
        session.step_seq = old.step_seq;
    }

    debug!(
        "initialize: app_id={}, session_id={}, has_license={}",
        session.app_id,
        logical_sid,
        session.has_license()
    );

    // TTL is fixed at creation so a later policy change does not expire sessions already open.
    session.ttl = session_mgr.ttl_for(&session.tenant_id);
    // Map transport connection ID -> logical session ID, and store session
    conn_to_session.insert(transport_session_id.to_string(), logical_sid.clone());
    session_mgr.insert(logical_sid.clone(), session.clone());
    upstream_mgr.bind_session(&logical_sid, &session.tenant_id);

    if upstream_mgr.is_single_for(&logical_sid) {
        // ── Single-upstream mode (original behavior) ──
        let upstream = match upstream_mgr.get_or_connect_single(&logical_sid).await {
            Ok(u) => u,
            Err(e) => return Some(upstream_rpc_error(id, "upstream connect failed", &e)),
        };

        // On reconnect, skip upstream re-initialization if the connection
        // is still alive (within the 10s grace period after SSE disconnect).
        if is_reconnect && upstream.is_connected() {
            debug!(
                "initialize: session {} reconnect, upstream still alive, skipping forward",
                logical_sid
            );
            if let Some(mut s) = session_mgr.get(&logical_sid) {
                s.mark_upstream_initialized("default");
                session_mgr.update(logical_sid.clone(), s);
            }
            // Return a minimal initialize result with proxy capabilities injected
            return Some(inject_proxy_capabilities(serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": {"name": "virbius-mcp-proxy", "version": env!("CARGO_PKG_VERSION")}
                }
            })));
        }

        let forward_req = serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "initialize",
            "params": params,
        });

        match upstream.forward(&forward_req).await {
            Ok(resp) => {
                if let Some(mut s) = session_mgr.get(&logical_sid) {
                    s.mark_upstream_initialized("default");
                    session_mgr.update(logical_sid.clone(), s);
                }
                Some(inject_proxy_capabilities(resp))
            }
            Err(e) => Some(upstream_rpc_error(id, "upstream initialize failed", &e)),
        }
    } else {
        // ── Multi-upstream mode ──
        let upstream_names = upstream_mgr.names_for(&logical_sid);

        // Forward initialize to all upstreams, skipping alive ones on reconnect
        let mut tasks = Vec::new();
        for name in &upstream_names {
            let name = name.to_string();
            let sid = logical_sid.clone();
            let id_clone = id.clone();
            let params_clone = params.clone();
            let mgr_ref = upstream_mgr;
            let connect_result = mgr_ref.get_or_connect(&sid, &name).await;
            match connect_result {
                Ok(upstream) => {
                    // On reconnect, skip upstreams that are still alive
                    // (within the 10s grace period after SSE disconnect)
                    if is_reconnect && upstream.is_connected() {
                        debug!(
                            "initialize: session {} reconnect, upstream {} still alive, skipping forward",
                            logical_sid, name
                        );
                        if let Some(mut s) = session_mgr.get(&logical_sid) {
                            s.mark_upstream_initialized(&name);
                            session_mgr.update(logical_sid.clone(), s);
                        }
                        continue;
                    }
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

        // Standalone mode: no upstreams configured (local tools only)
        if upstream_names.is_empty() {
            debug!("initialize: standalone mode (no upstreams), serving local tools only");
            if let Some(mut s) = session_mgr.get(&logical_sid) {
                s.mark_upstream_initialized("__local__");
                session_mgr.update(logical_sid.clone(), s);
            }
            return Some(inject_proxy_capabilities(serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": {"name": "virbius-mcp-proxy", "version": env!("CARGO_PKG_VERSION")}
                }
            })));
        }

        // If all upstreams were alive on reconnect, return early
        if tasks.is_empty() {
            if is_reconnect {
                return Some(inject_proxy_capabilities(serde_json::json!({
                    "jsonrpc": "2.0",
                    "id": id,
                    "result": {
                        "protocolVersion": "2024-11-05",
                        "capabilities": {},
                        "serverInfo": {"name": "virbius-mcp-proxy", "version": env!("CARGO_PKG_VERSION")}
                    }
                })));
            }
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
                    if let Some(mut s) = session_mgr.get(&logical_sid) {
                        s.mark_upstream_initialized(name);
                        session_mgr.update(logical_sid.clone(), s);
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

    if upstream_mgr.is_single_for(session_id) {
        // ── Single-upstream mode (original behavior) ──
        let upstream = match upstream_mgr.get_or_connect_single(session_id).await {
            Ok(u) => u,
            Err(e) => return Some(upstream_rpc_error(id, "upstream connect failed", &e)),
        };

        let forward_req = serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "method": "tools/list",
        });

        match upstream.forward(&forward_req).await {
            Ok(resp) => {
                filter_tools_list(inject_local_tools(resp, &session.allowed_tools), &session)
            }
            Err(e) => Some(upstream_rpc_error(id, "upstream tools/list failed", &e)),
        }
    } else {
        // ── Multi-upstream mode ──
        let upstream_names = upstream_mgr.names_for(session_id);

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

        if tools_by_upstream.is_empty() {
            if let Some(err) = &last_err {
                return Some(jsonrpc_error(
                    -32603,
                    id,
                    &format!("all upstreams failed: {}", err),
                ));
            }
        }

        // Merge tools from all upstreams, prefix conflicting names,
        // and register this session's routes in upstream_mgr.
        let merged_tools = merge_tools_from_upstreams(session_id, tools_by_upstream, upstream_mgr);

        let merged = serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "result": {
                "tools": merged_tools
            }
        });

        filter_tools_list(inject_local_tools(merged, &session.allowed_tools), &session)
    }
}

/// Merge tools from multiple upstreams, prefixing conflicting names.
///
/// Routes are registered under the SESSION's key and only this session's
/// previous routes are cleared — one session's tools/list never rewrites
/// another session's routing table.
///
/// Reserved namespace: upstream tools whose original name starts with
/// `virbius__` are ALWAYS prefixed with the upstream name so they can never
/// be confused with local-exec canonical tools.
fn merge_tools_from_upstreams(
    session_id: &str,
    tools_by_upstream: Vec<(String, Vec<Value>)>,
    upstream_mgr: &UpstreamManager,
) -> Vec<Value> {
    use std::collections::HashMap;

    // Count occurrences of each original tool name across upstreams
    let mut name_counts: HashMap<String, u32> = HashMap::new();
    for (_upstream_name, tools) in &tools_by_upstream {
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
    }

    // Clear this session's old routes only (other sessions unaffected).
    upstream_mgr.clear_tool_routes(session_id);

    let mut merged: Vec<Value> = Vec::new();
    let mut registered: std::collections::HashSet<String> = std::collections::HashSet::new();

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

            // Reserved local-exec namespace: force the upstream prefix even
            // without a cross-upstream conflict.
            let reserved = original_name.starts_with("virbius__");
            let has_conflict = name_counts.get(&original_name).copied().unwrap_or(0) > 1;
            let displayed_name = if reserved || has_conflict {
                format!("{}{}{}", upstream_name, TOOL_PREFIX_SEP, original_name)
            } else {
                original_name.clone()
            };

            // Skip a displayed name that is already taken for this session
            // (two upstreams both forcing a prefix, pathological case).
            if !registered.insert(displayed_name.clone()) {
                warn!(
                    "tools/list: duplicate displayed name '{}' from upstream '{}' skipped",
                    displayed_name, upstream_name
                );
                continue;
            }

            // Register route: displayed_name → (upstream_name, original_name)
            upstream_mgr.register_tool_route(
                session_id,
                &displayed_name,
                &upstream_name,
                &original_name,
            );

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
                let displayed_name = tool.get("name").and_then(|n| n.as_str()).unwrap_or("");

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
#[allow(clippy::too_many_arguments)]
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

    let displayed_tool_name = params.get("name").and_then(|v| v.as_str()).unwrap_or("");
    let mut args = params
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

    // Detect local code-execution tools by their CANONICAL names
    // (`virbius__shell`, `virbius__python`, `virbius__node`). These are
    // executed by the proxy itself in a gVisor sandbox and never forwarded
    // to an upstream. Legacy bare names (`shell`, ...) are NOT local tools
    // anymore — they route to the upstream like any other tool.
    let local_exec = crate::egress::local_exec_info(displayed_tool_name);

    // `policy_tool_name` is the name the pipeline and all post-processing
    // run under: the legacy LOGICAL name for local-exec calls (engine rules,
    // tool policies, transforms, trace and audit stay keyed by the legacy
    // names), the original upstream tool name otherwise. The canonical wire
    // name is only used by the hard gate and the executor.
    let (upstream_name, original_tool_name) = if local_exec.is_some() {
        let logical = virbius_core::license::logical_for_canonical(displayed_tool_name)
            .unwrap_or(displayed_tool_name);
        ("__local__".to_string(), logical.to_string())
    } else {
        // Resolve tool route: determine upstream_name and original_tool_name.
        // In single-upstream mode, route_tool always returns the sole upstream.
        match upstream_mgr.route_tool_for(session_id, displayed_tool_name) {
            Some(route) => (route.0, route.1),
            None => {
                // Tool not in this session's routes.
                if upstream_mgr.is_single_for(session_id) {
                    // Single upstream has no routing ambiguity: pass through.
                    let names = upstream_mgr.names_for(session_id);
                    (names[0].to_string(), displayed_tool_name.to_string())
                } else if crate::egress::is_legacy_local_name(displayed_tool_name) {
                    // Legacy bare local-exec name with no session inventory
                    // (or no upstream owns it): give the migration hint
                    // instead of a generic unknown-tool error.
                    let canonical =
                        virbius_core::license::canonical_for_logical(displayed_tool_name)
                            .map(|c| c.to_string())
                            .unwrap_or_else(|| "virbius__*".to_string());
                    return Some(jsonrpc_error(
                        -32602,
                        id,
                        &format!(
                            "unknown tool '{}' — legacy local-exec names no longer execute locally; use '{}' (requires an explicit license grant) or call tools/list first",
                            displayed_tool_name, canonical
                        ),
                    ));
                } else {
                    // Multi-upstream with no session inventory: fail closed.
                    // No string-prefix guessing — call tools/list first.
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

    // Note: a retry after challenge approval needs no token shortcut — the
    // approve step already wrote a session-level exemption, so the retried call
    // flows through the normal pipeline (engine override → allow) and is
    // audited + traced like any other call. `_meta.challenge_token` is stripped
    // in patch_forward_params so it never leaks upstream.

    // ── P1.3: Memory Interceptor (write-only) ──
    let memory_interceptor = memory_interceptor_for(pipeline, &session.tenant_id);
    let pushed_memory = pipeline.memory_for(&session.tenant_id);
    let memory_write = memory_interceptor.is_memory_write_tool(&original_tool_name)
        || pushed_memory
            .as_ref()
            .is_some_and(|m| m.enabled && tool_pattern_hit(&m.tool_patterns, &original_tool_name));
    if memory_interceptor.is_enabled() && memory_write {
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
            let reason = mem_result
                .block_reason
                .unwrap_or_else(|| "memory_write_blocked".into());
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
            match pipeline
                .check_memory(&session, &original_tool_name, &mem_result.sanitized_content)
                .await
            {
                Ok(resp) if !resp.allowed => {
                    // LLM detected injection
                    let reason = resp
                        .block_reason
                        .unwrap_or_else(|| "prompt_injection_detected".into());
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

    // ── Trace: prepare tool_call step metadata (decision recorded after pipeline) ──
    let tool_call_step_id = uuid::Uuid::new_v4().to_string();
    let parent_step_id = session.last_step_id.clone();
    let step_seq = session.next_step_seq();
    let trace_start = std::time::Instant::now();

    // ── Local-exec hard gate (P1d), BEFORE the pipeline ──
    //
    // Local-exec authorization is a pure canonical-grant fact, independent of
    // any License or fallback policy: it runs before the pipeline so a
    // rejection never reaches the pipeline's allow audit/trace. An audit-only
    // fallback must never imply authorization to execute local code; only an
    // explicit grant of the canonical tool name does, and that grant can only
    // come from a License. No license means no grant, so the gate still fires.
    if local_exec.is_some()
        && !session
            .allowed_tools
            .iter()
            .any(|t| t == displayed_tool_name)
    {
        let reason = VirbiusErrorCode::LocalExecNotGranted.message();
        pipeline
            .audit_tool_call(&session, &original_tool_name, "block", None, Some(reason))
            .await;
        session_mgr.update(session_id.to_string(), session.clone());
        let tc_event = TraceEvent::tool_call(
            &session,
            &tool_call_step_id,
            parent_step_id.as_deref(),
            step_seq,
            &original_tool_name,
            &args,
            Some(&upstream_name),
        )
        .with_decision("block", None, Some(reason), None);
        trace_collector.record(tc_event).await;
        return Some(jsonrpc_error_simple(
            VirbiusErrorCode::LocalExecNotGranted,
            id.clone(),
            displayed_tool_name,
            &session.trace_id,
            session.session_risk_score,
            Some(&format!(
                "local execution requires allowed_tools to list '{}' explicitly (wildcard licenses do not grant local execution)",
                displayed_tool_name
            )),
        ));
    }

    // Run security pipeline with the ORIGINAL tool name (before any prefix stripping).
    // The License allowed_tools contains original names, not prefixed ones.
    let result = pipeline
        .check_tool_call(&session, &original_tool_name, &args, Some(&upstream_name))
        .await;

    match result {
        PipelineResult::Allow {
            risk_score,
            disposition,
            ..
        } => {
            let transform_note = match rewrite_tool_args(&original_tool_name, &args) {
                Ok(rewritten) => {
                    args = rewritten.args;
                    rewritten.note
                }
                Err(detail) => {
                    // The pipeline already recorded its allow verdict; the
                    // pre_tool_call transform then failed. Record the actual
                    // outcome (block) so the audit trail and trace do not end
                    // on an "allow" for a call that was never forwarded or
                    // executed.
                    let reason = VirbiusErrorCode::ArgTransformFailed.message();
                    pipeline
                        .audit_tool_call(&session, &original_tool_name, "block", None, Some(reason))
                        .await;
                    session_mgr.update(session_id.to_string(), session.clone());
                    let tc_event = TraceEvent::tool_call(
                        &session,
                        &tool_call_step_id,
                        parent_step_id.as_deref(),
                        step_seq,
                        &original_tool_name,
                        &args,
                        Some(&upstream_name),
                    )
                    .with_decision("block", None, Some(reason), None);
                    trace_collector.record(tc_event).await;
                    return Some(jsonrpc_error_simple(
                        VirbiusErrorCode::ArgTransformFailed,
                        id.clone(),
                        &original_tool_name,
                        &session.trace_id,
                        session.session_risk_score,
                        Some(&detail),
                    ));
                }
            };
            if let Some(note) = &transform_note {
                pipeline
                    .audit_arg_transform(&session, &original_tool_name, note)
                    .await;
            }
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
                &session,
                &tool_call_step_id,
                parent_step_id.as_deref(),
                step_seq,
                &original_tool_name,
                &args,
                Some(&upstream_name),
            )
            .with_decision(disposition, None, None, risk_score);
            trace_collector.record(tc_event).await;

            // Local code-execution tools: execute in sandbox with full post-processing.
            // (Authorization already passed the hard gate before the pipeline.)
            if let Some((lang, code_arg)) = local_exec {
                let mut resp =
                    execute_in_sandbox(id, displayed_tool_name, &args, lang, code_arg).await;
                // ── Output PII masking ──
                mask_pii_in_response(&mut resp, &original_tool_name, &session.session_id);
                // ── Trust boundary tagging (high/network risk only) ──
                tag_tool_result(&mut resp, &original_tool_name, false);
                // ── Memory read interception (T3 cross-session defense) ──
                intercept_memory_read(&mut resp, &session, &original_tool_name, pipeline.as_ref())
                    .await;
                // ── Output content safety review (LLM-based) ──
                review_tool_output(&mut resp, &session, &original_tool_name, pipeline.as_ref())
                    .await;
                // ── Trace: record tool_result ──
                let duration_ms = trace_start.elapsed().as_millis() as u64;
                let result_step_id = uuid::Uuid::new_v4().to_string();
                let result_seq = session.next_step_seq();
                let tr_event = TraceEvent::tool_result(
                    &session,
                    &result_step_id,
                    &tool_call_step_id,
                    result_seq,
                    "success",
                    duration_ms,
                    &resp,
                    Some(&upstream_name),
                );
                trace_collector.record(tr_event).await;
                session.set_last_step_id(result_step_id);
                session_mgr.update(session_id.to_string(), session);
                annotate_arg_transform(&mut resp, transform_note.as_ref());
                return Some(resp);
            }

            // Egress tools: Proxy HTTP request directly with streaming response.
            // Check against the original tool name.
            if crate::egress::is_egress_tool(&original_tool_name) {
                let mut resp = proxy_egress_tool(
                    id,
                    &original_tool_name,
                    &session.tenant_id,
                    &args,
                    egress_client,
                    egress_hosts,
                )
                .await;
                // ── Output PII masking ──
                mask_pii_in_response(&mut resp, &original_tool_name, &session.session_id);
                // ── Trust boundary tagging (high/network risk only) ──
                tag_tool_result(&mut resp, &original_tool_name, false);
                // ── Memory read interception (T3 cross-session defense) ──
                intercept_memory_read(&mut resp, &session, &original_tool_name, pipeline.as_ref())
                    .await;
                // ── Output content safety review (LLM-based) ──
                review_tool_output(&mut resp, &session, &original_tool_name, pipeline.as_ref())
                    .await;
                // ── Trace: record tool_result ──
                let duration_ms = trace_start.elapsed().as_millis() as u64;
                let result_step_id = uuid::Uuid::new_v4().to_string();
                let result_seq = session.next_step_seq();
                let tr_event = TraceEvent::tool_result(
                    &session,
                    &result_step_id,
                    &tool_call_step_id,
                    result_seq,
                    "success",
                    duration_ms,
                    &resp,
                    Some(&upstream_name),
                );
                trace_collector.record(tr_event).await;
                session.set_last_step_id(result_step_id);
                session_mgr.update(session_id.to_string(), session);
                annotate_arg_transform(&mut resp, transform_note.as_ref());
                return Some(resp);
            }

            // Non-egress tools: Forward to the resolved upstream.
            // Use the ORIGINAL tool name (not prefixed) when forwarding.
            let upstream = match upstream_mgr
                .get_or_connect(session_id, &upstream_name)
                .await
            {
                Ok(u) => u,
                Err(e) => return Some(upstream_rpc_error(id, "upstream connect failed", &e)),
            };

            // Build forwarded request with original tool name and rewritten args
            let forward_params =
                patch_forward_params(params, displayed_tool_name, &original_tool_name, &args);

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
                    // ── Memory read interception (T3 cross-session defense) ──
                    intercept_memory_read(
                        &mut resp,
                        &session,
                        &original_tool_name,
                        pipeline.as_ref(),
                    )
                    .await;
                    // ── Output content safety review (LLM-based) ──
                    review_tool_output(&mut resp, &session, &original_tool_name, pipeline.as_ref())
                        .await;
                    // ── Trace: record tool_result ──
                    let duration_ms = trace_start.elapsed().as_millis() as u64;
                    let result_step_id = uuid::Uuid::new_v4().to_string();
                    let result_seq = session.next_step_seq();
                    let tr_event = TraceEvent::tool_result(
                        &session,
                        &result_step_id,
                        &tool_call_step_id,
                        result_seq,
                        "success",
                        duration_ms,
                        &resp,
                        Some(&upstream_name),
                    );
                    trace_collector.record(tr_event).await;
                    session.set_last_step_id(result_step_id);
                    session_mgr.update(session_id.to_string(), session);
                    annotate_arg_transform(&mut resp, transform_note.as_ref());
                    Some(resp)
                }
                Err(e) => {
                    // ── Trace: record tool_result error ──
                    let duration_ms = trace_start.elapsed().as_millis() as u64;
                    let result_step_id = uuid::Uuid::new_v4().to_string();
                    let result_seq = session.next_step_seq();
                    let err_val = serde_json::json!({"error": e.to_string()});
                    let tr_event = TraceEvent::tool_result(
                        &session,
                        &result_step_id,
                        &tool_call_step_id,
                        result_seq,
                        "error",
                        duration_ms,
                        &err_val,
                        Some(&upstream_name),
                    );
                    trace_collector.record(tr_event).await;
                    session.set_last_step_id(result_step_id);
                    session_mgr.update(session_id.to_string(), session);
                    Some(upstream_rpc_error(id, "upstream tools/call failed", &e))
                }
            }
        }
        PipelineResult::Deny {
            code,
            reason,
            risk_score,
            ..
        } => {
            // Write back risk score from engine (if evaluated)
            if let Some(score) = risk_score {
                session.session_risk_score = score;
            }
            session_mgr.update(session_id.to_string(), session.clone());

            // ── Trace: update tool_call with deny decision ──
            let tc_event = TraceEvent::tool_call(
                &session,
                &tool_call_step_id,
                parent_step_id.as_deref(),
                step_seq,
                &original_tool_name,
                &args,
                Some(&upstream_name),
            )
            .with_decision("block", None, Some(&reason), risk_score);
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
        PipelineResult::Challenge {
            challenge_id,
            args_hash,
            rule_id,
            reason,
            risk_score,
        } => {
            // Write back risk score from engine
            session.session_risk_score = risk_score;
            session_mgr.update(session_id.to_string(), session.clone());

            // ── Trace: update tool_call with challenge decision ──
            let tc_event = TraceEvent::tool_call(
                &session,
                &tool_call_step_id,
                parent_step_id.as_deref(),
                step_seq,
                &original_tool_name,
                &args,
                Some(&upstream_name),
            )
            .with_decision(
                "challenge",
                rule_id.as_deref(),
                Some(&reason),
                Some(risk_score),
            );
            trace_collector.record(tc_event).await;

            // Return JSON-RPC error -32011 with challenge details. As with every
            // Virbius error: message/reason are human, reason_code is the machine
            // token, rule_id stays internal.
            let human = VirbiusErrorCode::ChallengeRequired.human_message(Some(&reason));
            let data = serde_json::json!({
                "tool_name": original_tool_name,
                "trace_id": session.trace_id,
                "session_risk_score": risk_score,
                "http_analog": 403,
                "challenge_id": challenge_id,
                "args_hash": args_hash,
                "reason_code": VirbiusErrorCode::ChallengeRequired.message(),
                "reason": human.clone(),
            });
            Some(serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "error": {
                    "code": VirbiusErrorCode::ChallengeRequired as i32,
                    "message": human,
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
    session_tenant: &str,
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
    let method = args.get("method").and_then(|v| v.as_str()).unwrap_or("GET");
    let body = args.get("body").or_else(|| args.get("data"));

    // 4. Extract headers from args (filter happens inside proxy_request)
    let headers: Option<Vec<(String, String)>> =
        args.get("headers").and_then(|h| h.as_object()).map(|map| {
            map.iter()
                .filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string())))
                .collect()
        });
    let headers_ref = headers.as_deref();

    // Delegated credential plane (v2.5-min): the hit branch is handled here
    // (uniform opaque deny or cleaned 2xx response); miss / plane-off / cold
    // fall through to the existing path, which stays byte-identical.
    if let crate::delegated::HitOutcome::Handled(resp) = crate::delegated::try_handle(
        id,
        &url,
        method,
        body,
        headers_ref,
        session_tenant,
        egress_client.nofollow(),
    )
    .await
    {
        return resp;
    }

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
                    // Contract skeleton (stable from P1; clients key off these
                    // fields, values must not be renamed):
                    "localExec": {
                        "namespace": "virbius",
                        "canonical": ["virbius__shell", "virbius__python", "virbius__node"],
                        "sandbox": ["gvisor"],
                        "grant": "license allowed_tools must list the canonical name explicitly; wildcard does not grant local execution"
                    }
                }),
            );
        }
    }
    resp
}

/// Build a JSON-RPC error response (internal server error).
/// Standard JSON-RPC error for an upstream/transport failure.
///
/// The client-facing `message` is a **fixed, safe** string: it must never embed
/// the raw error (`{e}`), which can carry internal hosts/URLs, because a
/// pass-through failure formatter may hand the message straight to the LLM. The
/// detail is logged for operators instead.
fn upstream_rpc_error(id: &Value, what: &str, e: &dyn std::fmt::Display) -> Value {
    warn!("{what}: {e}");
    jsonrpc_error(-32603, id, &format!("Upstream MCP server error ({what})."))
}

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
    if output_masking_skipped(tool_name) {
        return;
    }
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
        if let Some(text) = item
            .get_mut("text")
            .and_then(|t| t.as_str().map(|s| s.to_string()))
        {
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

/// Memory is a trusted store whose reads are plaintext by policy (PII egress
/// is gated at the untrusted sink in `pre_tool_call` via `arg_transform`).
/// Masking memory reads would deposit `[REDACTED]` artifacts into model
/// context — where the Agent round-trips them into later tool args and
/// legitimate restrict checks start failing — so memory read tools bypass
/// output masking entirely.
fn output_masking_skipped(tool_name: &str) -> bool {
    MemoryInterceptor::from_manifest().is_memory_read_tool(tool_name)
}

fn memory_interceptor_for(pipeline: &SecurityPipeline, tenant_id: &str) -> MemoryInterceptor {
    let base = MemoryInterceptor::from_manifest();
    match pipeline.memory_for(tenant_id) {
        Some(mem) if mem.enabled => base
            .with_enabled(true)
            .with_max_entry_size(mem.max_entry_size),
        _ => base,
    }
}

fn tool_pattern_hit(patterns: &[String], tool_name: &str) -> bool {
    let lower = tool_name.to_lowercase();
    patterns.iter().any(|p| {
        let p = p.to_lowercase();
        !p.is_empty() && (lower == p || lower.starts_with(&p))
    })
}

/// Extract concatenated text from a JSON-RPC tool call response.
///
/// Navigates `resp.result.content[]` and collects all `text`-type items.
/// Returns an empty String if the response has no text content.
fn extract_result_text(resp: &Value) -> String {
    let Some(result) = resp.get("result") else {
        return String::new();
    };
    let Some(content_arr) = result.get("content").and_then(|c| c.as_array()) else {
        return String::new();
    };
    let mut parts = Vec::new();
    for item in content_arr {
        if item.get("type").and_then(|t| t.as_str()) != Some("text") {
            continue;
        }
        if let Some(text) = item.get("text").and_then(|t| t.as_str()) {
            parts.push(text);
        }
    }
    parts.join("\n")
}

/// Replace all text content in a JSON-RPC tool call response with a safe message.
///
/// Used when the output review engine denies the content.
/// What the output-review verdict did to the tool result text.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum OutputDisposal {
    Redacted,
    Omitted,
    Rejected,
    Warned,
    Passed,
}

/// Apply the engine's `disposal_action` to the tool result (SDK canonical
/// mapping, one mapping for every caller):
///
/// - `reject`            → refusal placeholder
/// - `omit`              → empty text
/// - `redact`            → `sanitized_content` verbatim (empty included);
///   missing sanitized text falls back to reject
/// - `warn`              → content unchanged
/// - unknown / missing   → reject (fail-closed)
///
/// Only blocked verdicts (`block`/`deny`) mutate content; an unblocked verdict
/// can only be `warn` (the engine's resolver returns warn when not blocked).
fn apply_output_disposal(resp: &mut Value, verdict: &OutputReviewResponse) -> OutputDisposal {
    let blocked = matches!(
        verdict.effective_action.to_ascii_lowercase().as_str(),
        "block" | "deny"
    );
    if !blocked {
        return if verdict.disposal_action.eq_ignore_ascii_case("warn") {
            OutputDisposal::Warned
        } else {
            OutputDisposal::Passed
        };
    }

    let reason = verdict
        .reason
        .as_deref()
        .filter(|s| !s.is_empty())
        .unwrap_or("content safety violation");

    match verdict.disposal_action.to_ascii_lowercase().as_str() {
        "redact" => match verdict.sanitized_content.as_deref() {
            Some(sanitized) => {
                replace_result_text(resp, sanitized);
                OutputDisposal::Redacted
            }
            None => {
                // Contract violation: redact without sanitized text must not
                // leak the original — fail closed to the refusal placeholder.
                replace_result_text(
                    resp,
                    &format!("[Content blocked by safety review: {}]", reason),
                );
                OutputDisposal::Rejected
            }
        },
        "omit" => {
            replace_result_text(resp, "");
            OutputDisposal::Omitted
        }
        _ => {
            // reject + unknown values: fail closed.
            replace_result_text(
                resp,
                &format!("[Content blocked by safety review: {}]", reason),
            );
            OutputDisposal::Rejected
        }
    }
}

fn replace_result_text(resp: &mut Value, safe_message: &str) {
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
        if let Some(text_field) = item.get_mut("text") {
            *text_field = Value::String(safe_message.to_string());
        }
    }
}

/// Review tool output for content safety via the Engine.
///
/// Extracts text from the tool result response and sends it to the Engine's
/// `/v1/evaluate` endpoint with `role = "output"`. The Engine reuses its
/// existing rule pipeline (VirbiusGuard LLM classification + groovy pattern
/// matching) to classify the content.
///
/// Conditional trigger: only reviews when text length >= `min_text_length`
/// or session risk score >= `min_risk_score`, balancing latency and safety.
///
/// If the Engine denies the content, the response text is replaced with a
/// safe placeholder. On engine failure, the `fail_open` config determines
/// whether to allow or block the content.
async fn review_tool_output(
    resp: &mut Value,
    session: &Session,
    tool_name: &str,
    pipeline: &SecurityPipeline,
) {
    let cfg = pipeline.output_review_for(&session.tenant_id);
    if !cfg.enabled {
        return;
    }

    let text = extract_result_text(resp);
    if text.is_empty() {
        return;
    }

    // Conditional trigger: skip review for short, low-risk outputs
    if !pipeline.should_review_output_for(&session.tenant_id, &text, session.session_risk_score) {
        return;
    }

    debug!(
        "output review triggered: tool={} session={} text_len={} risk_score={}",
        tool_name,
        session.session_id,
        text.len(),
        session.session_risk_score
    );

    // B6: STI taint check first — dedicated indirect-injection detection on tool
    // returns (malicious instructions embedded in external data). A taint block
    // is terminal for this result.
    match pipeline
        .sti_check_tool_result(session, tool_name, &text)
        .await
    {
        Ok(sti) => {
            if sti.action == "block" {
                let reason = sti
                    .reason
                    .as_deref()
                    .or(sti.detected_pattern.as_deref())
                    .filter(|s| !s.is_empty())
                    .unwrap_or("tainted tool output");
                info!(
                    "STI taint blocked: tool={} session={} pattern={:?}",
                    tool_name, session.session_id, sti.detected_pattern
                );
                let safe_msg = format!("[Tool output blocked (taint): {}]", reason);
                replace_result_text(resp, &safe_msg);
                return;
            }
        }
        Err(e) => {
            warn!(
                "STI taint check error: tool={} session={} error={}",
                tool_name, session.session_id, e
            );
            if !cfg.fail_open {
                replace_result_text(resp, "[Content blocked: taint check unavailable]");
                return;
            }
        }
    }

    match pipeline.review_output(session, tool_name, &text).await {
        Ok(verdict) => {
            let outcome = apply_output_disposal(resp, &verdict);
            match outcome {
                OutputDisposal::Redacted => {
                    info!(
                        "output review redacted: tool={} session={} rule={:?} reason={:?}",
                        tool_name, session.session_id, verdict.rule_id, verdict.reason
                    );
                    pipeline
                        .audit_output_review(
                            session,
                            tool_name,
                            "block",
                            verdict.rule_id.as_deref(),
                            &verdict.disposal_action,
                        )
                        .await;
                }
                OutputDisposal::Omitted => {
                    info!(
                        "output review omitted: tool={} session={} rule={:?} reason={:?}",
                        tool_name, session.session_id, verdict.rule_id, verdict.reason
                    );
                    pipeline
                        .audit_output_review(
                            session,
                            tool_name,
                            "block",
                            verdict.rule_id.as_deref(),
                            &verdict.disposal_action,
                        )
                        .await;
                }
                OutputDisposal::Rejected => {
                    info!(
                        "output review blocked: tool={} session={} rule={:?} reason={:?}",
                        tool_name, session.session_id, verdict.rule_id, verdict.reason
                    );
                    pipeline
                        .audit_output_review(
                            session,
                            tool_name,
                            "block",
                            verdict.rule_id.as_deref(),
                            &verdict.disposal_action,
                        )
                        .await;
                }
                OutputDisposal::Warned => {
                    // Content passes unchanged; record the warn so the edge
                    // audit row exists for the same trace.
                    warn!(
                        "output review warn: tool={} session={} rule={:?} reason={:?}",
                        tool_name, session.session_id, verdict.rule_id, verdict.reason
                    );
                    if verdict.rule_id.is_some() {
                        pipeline
                            .audit_output_review(
                                session,
                                tool_name,
                                "allow",
                                verdict.rule_id.as_deref(),
                                &verdict.disposal_action,
                            )
                            .await;
                    }
                }
                OutputDisposal::Passed => {
                    debug!(
                        "output review passed: tool={} session={} action={}",
                        tool_name, session.session_id, verdict.effective_action
                    );
                }
            }
        }
        Err(e) => {
            warn!(
                "output review engine error: tool={} session={} error={}",
                tool_name, session.session_id, e
            );
            if !cfg.fail_open {
                replace_result_text(resp, "[Content blocked: safety review unavailable]");
            }
        }
    }
}

struct RewrittenArgs {
    args: Value,
    note: Option<Value>,
}

fn rewrite_tool_args(tool: &str, args: &Value) -> Result<RewrittenArgs, String> {
    let Some(policy) = virbius_core::manifest::tool_policy(tool) else {
        return Ok(RewrittenArgs {
            args: args.clone(),
            note: None,
        });
    };
    let Some(cfg) = policy.arg_transforms.filter(|c| !c.is_null()) else {
        return Ok(RewrittenArgs {
            args: args.clone(),
            note: None,
        });
    };
    let applied = virbius_core::arg_transform::apply_config(args, &cfg)
        .map_err(|e| format!("{}: {}", e.reason, e.detail))?;
    virbius_core::precheck::revalidate_tool_args(tool, &applied.args)
        .map_err(|e| format!("arg_transform_revalidation: {e}"))?;
    if applied.applied.is_empty() && applied.skipped.is_empty() {
        return Ok(RewrittenArgs {
            args: applied.args,
            note: None,
        });
    }
    let map_ops = |ops: &[virbius_core::arg_transform::AppliedOp]| {
        ops.iter()
            .map(|o| {
                serde_json::json!({
                    "path": o.path,
                    "op": o.op,
                    "count": o.count,
                })
            })
            .collect::<Vec<_>>()
    };
    Ok(RewrittenArgs {
        args: applied.args,
        note: Some(serde_json::json!({
            "applied": map_ops(&applied.applied),
            "skipped": map_ops(&applied.skipped),
        })),
    })
}

fn patch_forward_params(params: &Value, displayed: &str, original: &str, args: &Value) -> Value {
    let mut p = params.clone();
    if let Some(obj) = p.as_object_mut() {
        obj.insert("arguments".to_string(), args.clone());
        if displayed != original {
            obj.insert("name".to_string(), Value::String(original.to_string()));
        }
        // Never forward client-supplied challenge tokens upstream.
        if let Some(m) = obj.get_mut("_meta").and_then(|m| m.as_object_mut()) {
            m.remove("challenge_token");
        }
    }
    p
}

fn annotate_arg_transform(resp: &mut Value, note: Option<&Value>) {
    let Some(note) = note else {
        return;
    };
    let Some(result) = resp.get_mut("result").and_then(|r| r.as_object_mut()) else {
        return;
    };
    let meta = result
        .entry("_meta")
        .or_insert_with(|| serde_json::json!({}));
    if let Some(obj) = meta.as_object_mut() {
        obj.insert("arg_transform".to_string(), note.clone());
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

/// Intercept memory read results: local checks → LLM injection detection → filter/block.
///
/// This is the **read counterpart** of the write interceptor (P1.3). It runs
/// AFTER the upstream MCP server returns memory content, before the content
/// reaches the Agent context.
///
/// Defense against T3 (cross-session) memory poisoning:
/// - Session A: attacker plants a prompt injection payload via `memory_save`
///   (write interceptor catches obvious credentials/PII, but subtle injection
///   may pass if `need_llm_check` was false or Engine was unavailable)
/// - Session B: Agent calls `memory_search` → payload is retrieved
/// - This interceptor scans the retrieved content and blocks/filters it
///
/// Flow:
/// 1. Check if the tool is a memory read tool (via `is_memory_read_tool`)
/// 2. Extract text from the JSON-RPC response
/// 3. Local checks: size limit + credential leak detection (`intercept_read`)
/// 4. If `need_llm_check`: call Engine `/v1/memory/check` for injection detection
/// 5. If injection detected:
///    - `filter_on_read = true`: wrap content in `<untrusted_data>` tags
///    - `filter_on_read = false`: block the read entirely
/// 6. If blocked: replace response text with a safe placeholder
async fn intercept_memory_read(
    resp: &mut Value,
    session: &Session,
    tool_name: &str,
    pipeline: &SecurityPipeline,
) {
    let memory_interceptor = memory_interceptor_for(pipeline, &session.tenant_id);
    let pushed_memory = pipeline.memory_for(&session.tenant_id);
    let memory_read = memory_interceptor.is_memory_read_tool(tool_name)
        || pushed_memory
            .as_ref()
            .is_some_and(|m| m.enabled && tool_pattern_hit(&m.tool_patterns, tool_name));
    if !memory_interceptor.is_enabled() || !memory_read {
        return;
    }

    let text = extract_result_text(resp);
    if text.is_empty() {
        return;
    }

    let mem_ctx = MemoryContext {
        session_id: session.session_id.clone(),
        trace_id: session.trace_id.clone(),
        tool_name: tool_name.to_string(),
    };

    // 1. Local checks: size + credential leak detection
    let read_result = memory_interceptor.intercept_read(&text, &mem_ctx);

    if !read_result.allowed {
        let reason = read_result
            .block_reason
            .unwrap_or_else(|| "memory_read_blocked".into());
        warn!(
            "memory read blocked (local check): tool={} session={} reason={}",
            tool_name, session.session_id, reason
        );
        let safe_msg = format!("[Memory read blocked: {}]", reason);
        replace_result_text(resp, &safe_msg);
        return;
    }

    // 2. LLM-based injection detection (if needed)
    if read_result.need_llm_check {
        match pipeline
            .check_memory(session, tool_name, &read_result.filtered_content)
            .await
        {
            Ok(engine_resp) if !engine_resp.allowed => {
                // Injection detected by LLM
                let reason = engine_resp
                    .block_reason
                    .unwrap_or_else(|| "prompt_injection_detected".into());
                info!(
                    "memory read injection detected: tool={} session={} reason={}",
                    tool_name, session.session_id, reason
                );

                // Filter or block based on policy
                let filtered =
                    memory_interceptor.filter_read_content(&read_result.filtered_content);
                if filtered != read_result.filtered_content {
                    // filter_on_read = true: wrap in <untrusted_data> tags
                    debug!(
                        "memory read content filtered (wrapped in untrusted_data): tool={} session={}",
                        tool_name, session.session_id
                    );
                    replace_result_text(resp, &filtered);
                } else {
                    // filter_on_read = false: block entirely
                    let safe_msg =
                        format!("[Memory read blocked: injection detected ({})]", reason);
                    replace_result_text(resp, &safe_msg);
                }
            }
            Err(e) => {
                // Engine unavailable — fail-closed for memory reads with injection risk
                warn!(
                    "memory read check failed (engine unavailable): tool={} session={} error={}",
                    tool_name, session.session_id, e
                );
                let safe_msg = "[Memory read blocked: safety check unavailable]";
                replace_result_text(resp, safe_msg);
            }
            _ => {
                // All checks passed — read allowed
                debug!(
                    "memory read allowed: tool={} session={}",
                    tool_name, session.session_id
                );
            }
        }
    }
}

/// Inject synthesized local code-execution tool descriptors
/// (`virbius__shell`, `virbius__python`, `virbius__node`) into a `tools/list`
/// response, so agents can discover and call them.
///
/// Only tools the session's license EXPLICITLY grants are injected: a
/// wildcard license (empty `allowed_tools`) never implies local execution,
/// and an unlisted canonical tool is not advertised (list and call share one
/// authorization rule, so the model is never shown tools it cannot call).
fn inject_local_tools(mut resp: Value, granted: &[String]) -> Value {
    let new_tools = crate::egress::local_exec_tool_descriptors(granted);
    if new_tools.is_empty() {
        return resp;
    }
    if let Some(result) = resp.get_mut("result").and_then(|r| r.as_object_mut()) {
        if let Some(existing) = result.get_mut("tools").and_then(|t| t.as_array_mut()) {
            for tool in new_tools {
                let name = tool.get("name").and_then(|n| n.as_str()).unwrap_or("");
                if !existing
                    .iter()
                    .any(|t| t.get("name").and_then(|n| n.as_str()) == Some(name))
                {
                    existing.push(tool);
                }
            }
        }
    }
    resp
}

/// Execute a local code-execution tool in the configured sandbox.
///
/// Run sandbox work on a blocking thread, but always return when `wall` elapses.
/// Does not abort the blocking closure: on timeout the task is detached, so any
/// resource guard captured by `work` remains held until the real work ends
/// (callers must move the guard into `work`, not keep it in the async frame).
/// Inner waits must also be bounded so the detached task terminates on its own.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
async fn await_sandbox_wall<F>(
    work: F,
    wall: std::time::Duration,
) -> Result<Result<virbius_core::sandbox::ExecutionResult, String>, String>
where
    F: FnOnce() -> Result<virbius_core::sandbox::ExecutionResult, String> + Send + 'static,
{
    match tokio::time::timeout(wall, tokio::task::spawn_blocking(work)).await {
        Ok(Ok(inner)) => Ok(inner),
        Ok(Err(e)) => Err(format!("sandbox spawn failed: {e}")),
        Err(_) => Err("sandbox_exec_timeout".to_string()),
    }
}

/// On Linux: extract the code argument and run it in gVisor. There is no
/// unsandboxed and no Landlock path — a local tool whose policy asks for
/// `none`/`landlock`, or a node without a usable gVisor pool, is refused
/// (`sandbox_unavailable` / `local_exec_node_disabled`). The `_meta` contract
/// is attached to successful results.
#[cfg(target_os = "linux")]
async fn execute_in_sandbox(
    id: &Value,
    tool_name: &str,
    args: &Value,
    lang: &str,
    code_arg: &str,
) -> Value {
    use std::time::Duration;
    use virbius_core::manifest;
    use virbius_core::sandbox::{
        execute, mcp_wall, ExecutionRequest, GvisorPool, LandlockRules, Language, SandboxType,
    };

    // D-I (v2.5-min): while the delegated credential plane is enabled,
    // local-exec tools are denied outright regardless of sandbox config.
    if !crate::delegated::local_exec_allowed() {
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::DelegatedLocalExecDisabled,
            id.clone(),
            tool_name,
            "",
            0,
            Some(
                "local tool execution is disabled while the delegated credential plane is enabled",
            ),
        );
    }

    // 1. Extract code
    let code = match args.get(code_arg).and_then(|v| v.as_str()) {
        Some(c) => c.to_string(),
        None => {
            return jsonrpc_error(
                -32602,
                id,
                &format!(
                    "missing '{}' argument for local exec tool '{}'",
                    code_arg, tool_name
                ),
            );
        }
    };

    // 2. Node-level settings: mode (default disabled), payload size, in-flight cap.
    let settings = local_exec_settings();
    if settings.mode != LocalExecMode::Gvisor {
        warn!(
            "local exec '{}' denied: local_exec.mode is disabled on this node",
            tool_name
        );
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::LocalExecNodeDisabled,
            id.clone(),
            tool_name,
            "",
            0,
            Some("local execution is disabled on this node (local_exec.mode)"),
        );
    }
    if code.len() > settings.code_bytes {
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::CodeTooLarge,
            id.clone(),
            tool_name,
            "",
            0,
            Some(&format!(
                "code payload is {} bytes; the limit is {}",
                code.len(),
                settings.code_bytes
            )),
        );
    }
    let inflight = match InflightGuard::acquire(settings.concurrency) {
        Some(guard) => guard,
        None => {
            return crate::error::jsonrpc_error_simple(
                crate::error::VirbiusErrorCode::LocalExecBusy,
                id.clone(),
                tool_name,
                "",
                0,
                Some("too many concurrent local executions on this node"),
            );
        }
    };

    // 3. Tool policy (looked up by the LOGICAL name for continuity).
    //    v4 semantics for local tools:
    //      no policy              → node mode decides (gvisor here)
    //      policy sandbox=gvisor  → gvisor
    //      policy none/landlock   → refuse (explicit unsupported intent)
    let policy_name = virbius_core::license::logical_for_canonical(tool_name).unwrap_or(tool_name);
    let policy = manifest::tool_policy(policy_name);
    let sandbox_type_str = policy
        .as_ref()
        .map(|p| p.sandbox_type.clone())
        .unwrap_or_default();
    if !sandbox_type_str.is_empty() && sandbox_type_str != "gvisor" {
        warn!(
            "local exec '{}' denied: tool policy requests sandbox_type='{}'",
            tool_name, sandbox_type_str
        );
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::SandboxUnavailable,
            id.clone(),
            tool_name,
            "",
            0,
            Some(&format!(
                "tool policy requests sandbox_type='{}' which is not executable (only gvisor is supported)",
                sandbox_type_str
            )),
        );
    }
    let timeout_ms = policy
        .as_ref()
        .map(|p| p.timeout_ms)
        .filter(|&t| t > 0)
        .unwrap_or(30_000);

    // 4. Language from whitelist string
    let lang_enum = Language::parse(lang).unwrap_or(Language::Shell);

    // 5. gVisor only — there is NO unsandboxed and NO Landlock fallback.
    GvisorPool::apply_from_manifest_if_needed();
    if !GvisorPool::global().is_available() {
        warn!(
            "local exec '{}' denied: gVisor pool not delivered or runsc missing",
            tool_name
        );
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::SandboxUnavailable,
            id.clone(),
            tool_name,
            "",
            0,
            Some("gVisor is not available on this node (runsc missing or gvisor_config not delivered)"),
        );
    }
    let req = ExecutionRequest {
        sandbox_type: SandboxType::Gvisor,
        program: code,
        args: vec![],
        landlock_rules: LandlockRules::default(),
        timeout: Duration::from_millis(timeout_ms),
        gvisor_config: None,
        gvisor_language: Some(lang_enum),
    };
    let limits = GvisorPool::global().delivered_limits();
    let acquire = limits
        .as_ref()
        .map(|c| c.acquire_timeout)
        .unwrap_or(Duration::from_secs(10));
    let exec = limits
        .as_ref()
        .map(|c| timeout_ms.min(c.exec_timeout.as_millis() as u64))
        .unwrap_or(timeout_ms);
    let wall = mcp_wall(acquire, Duration::from_millis(exec));
    // The guard travels with the blocking closure: an outer wall timeout
    // detaches the task but must not release the concurrency slot while the
    // sandbox work is still running.
    let exec_result: Result<virbius_core::sandbox::ExecutionResult, String> =
        match await_sandbox_wall(
            move || {
                let _inflight = inflight;
                execute(req)
            },
            wall,
        )
        .await
        {
            Ok(inner) => inner,
            Err(e) => {
                warn!("local exec '{}' gvisor wall: {}", tool_name, e);
                return jsonrpc_error(-32603, id, &e);
            }
        };

    // 6. Shape MCP response; the `_meta` contract is frozen here
    //    (`truncated` / `limit_reason` are wired to real values by P2's
    //    output cap; the fields exist from day one so clients never churn).
    match exec_result {
        Ok(r) => {
            let is_error = r.exit_code != 0;
            let output = if r.stderr.is_empty() {
                r.stdout.trim().to_string()
            } else {
                format!("{}\n{}", r.stdout.trim(), r.stderr.trim())
                    .trim()
                    .to_string()
            };
            let mut meta = serde_json::json!({
                "sandbox_configured": sandbox_type_str,
                "sandbox_used": r.sandbox_used.as_str(),
                "degraded": r.degraded,
                "truncated": r.truncated,
                "limit_reason": if r.truncated { serde_json::json!("output_limit") } else { serde_json::Value::Null },
                "exit_code": r.exit_code,
                "limits": {
                    "codeBytes": settings.code_bytes,
                    "wallMs": timeout_ms,
                },
            });
            if r.degraded {
                if let Some(note) = &r.degrade_note {
                    meta["degrade_note"] = serde_json::Value::String(note.clone());
                }
            }
            if r.sandbox_used == SandboxType::Gvisor {
                if let Some(limits) = GvisorPool::global().delivered_limits() {
                    meta["gvisor_memory_limit_bytes"] =
                        serde_json::json!(limits.memory_limit_bytes);
                    meta["gvisor_network_disabled"] = serde_json::json!(limits.network_disabled);
                    meta["gvisor_cpu_quota"] = serde_json::json!(limits.cpu_quota);
                    meta["gvisor_min_warm"] = serde_json::json!(limits.min_warm);
                    meta["gvisor_exec_timeout_ms"] =
                        serde_json::json!(limits.exec_timeout.as_millis() as u64);
                }
            }
            serde_json::json!({
                "jsonrpc": "2.0",
                "id": id,
                "result": {
                    "content": [{"type": "text", "text": output}],
                    "isError": is_error,
                    "_meta": meta
                }
            })
        }
        Err(e) => {
            warn!("local exec '{}' sandbox failed: {}", tool_name, e);
            jsonrpc_error(-32603, id, &format!("sandbox exec failed: {}", e))
        }
    }
}

/// Non-Linux: fail closed — local code execution requires gVisor (Linux).
#[cfg(not(target_os = "linux"))]
async fn execute_in_sandbox(
    id: &Value,
    tool_name: &str,
    _args: &Value,
    _lang: &str,
    _code_arg: &str,
) -> Value {
    // Non-Linux hosts never execute local code: there is no unsandboxed
    // fallback (that path reported `sandbox_used=none, degraded=false`,
    // which was a lie). Fail closed with a clear reason instead.
    let settings = local_exec_settings();
    if settings.mode != LocalExecMode::Gvisor {
        warn!(
            "local exec '{}' denied: local_exec.mode is disabled on this node",
            tool_name
        );
        return crate::error::jsonrpc_error_simple(
            crate::error::VirbiusErrorCode::LocalExecNodeDisabled,
            id.clone(),
            tool_name,
            "",
            0,
            Some("local execution is disabled on this node (local_exec.mode)"),
        );
    }

    crate::error::jsonrpc_error_simple(
        crate::error::VirbiusErrorCode::SandboxUnavailable,
        id.clone(),
        tool_name,
        "",
        0,
        Some(
            "local execution requires Linux + gVisor (runsc); this host cannot sandbox local code",
        ),
    )
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
        let payload_b64 = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(serde_json::to_vec(&payload).unwrap());
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
        let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
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

    #[tokio::test]
    async fn await_sandbox_wall_times_out() {
        use std::time::Duration;
        use virbius_core::sandbox::{ExecutionResult, SandboxType};

        let err = await_sandbox_wall(
            || {
                std::thread::sleep(Duration::from_millis(200));
                Ok(ExecutionResult {
                    stdout: String::new(),
                    stderr: String::new(),
                    exit_code: 0,
                    sandbox_used: SandboxType::None,
                    degraded: false,
                    degrade_note: None,
                    landlock_applied: false,
                    truncated: false,
                })
            },
            Duration::from_millis(80),
        )
        .await
        .expect_err("wall must fire");
        assert!(err.contains("sandbox_exec_timeout"), "got {err}");
    }

    #[test]
    fn patch_forward_params_rewrites_arguments() {
        let params = serde_json::json!({"name":"a__t","arguments":{"x":1},"_meta":{"k":1}});
        let out = patch_forward_params(&params, "a__t", "t", &serde_json::json!({"x":2}));
        assert_eq!(out["name"], "t");
        assert_eq!(out["arguments"]["x"], 2);
        assert_eq!(out["_meta"]["k"], 1);
    }

    #[test]
    fn rewrite_unknown_tool_is_noop() {
        let args = serde_json::json!({"a": 1});
        let r = rewrite_tool_args("zzz_no_such_tool_for_arg_transform", &args).unwrap();
        assert_eq!(r.args, args);
        assert!(r.note.is_none());
    }

    #[test]
    fn annotate_arg_transform_writes_meta() {
        let mut resp = serde_json::json!({"jsonrpc":"2.0","result":{"content":[]}});
        annotate_arg_transform(
            &mut resp,
            Some(&serde_json::json!({"applied":[{"path":"$.a","op":"truncate","count":1}]})),
        );
        assert_eq!(
            resp["result"]["_meta"]["arg_transform"]["applied"][0]["op"],
            "truncate"
        );
    }

    #[test]
    fn memory_read_tools_skip_output_masking() {
        assert!(output_masking_skipped("memory_search"));
        assert!(output_masking_skipped("vector_query"));
        assert!(output_masking_skipped("recall"));
        assert!(!output_masking_skipped("send_email"));
        assert!(!output_masking_skipped("http_get"));
    }

    // ── output-review disposal matrix ──

    fn output_review_resp(
        effective_action: &str,
        disposal: &str,
        sanitized: Option<&str>,
    ) -> OutputReviewResponse {
        OutputReviewResponse {
            effective_action: effective_action.to_string(),
            rule_id: Some("OUTPUT_CREDENTIAL_LEAK".to_string()),
            reason: Some("OUTPUT_CREDENTIAL_LEAK".to_string()),
            disposal_action: disposal.to_string(),
            sanitized_content: sanitized.map(String::from),
        }
    }

    fn tool_result(text: &str) -> serde_json::Value {
        serde_json::json!({
            "jsonrpc": "2.0",
            "result": { "content": [{ "type": "text", "text": text }] }
        })
    }

    fn result_text(resp: &serde_json::Value) -> &str {
        resp["result"]["content"][0]["text"].as_str().unwrap()
    }

    #[test]
    fn disposal_redact_delivers_sanitized_text() {
        let mut resp = tool_result("key AKIA1234567890ABCDEF leaked");
        let v = output_review_resp("deny", "redact", Some("key [REDACTED_AWS_KEY] leaked"));
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Redacted);
        assert_eq!(result_text(&resp), "key [REDACTED_AWS_KEY] leaked");
    }

    #[test]
    fn disposal_redact_sanitized_empty_string_is_delivered_verbatim() {
        // SDK parity: sanitized_content Some("") is delivered as empty, not
        // silently replaced by the refusal text.
        let mut resp = tool_result("secret");
        let v = output_review_resp("deny", "redact", Some(""));
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Redacted);
        assert_eq!(result_text(&resp), "");
    }

    #[test]
    fn disposal_redact_without_sanitized_fails_closed() {
        let mut resp = tool_result("secret");
        let v = output_review_resp("deny", "redact", None);
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Rejected);
        assert!(result_text(&resp).contains("blocked by safety review"));
    }

    #[test]
    fn disposal_omit_empties_text() {
        let mut resp = tool_result("<script>alert(1)</script>");
        let v = output_review_resp("block", "omit", None);
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Omitted);
        assert_eq!(result_text(&resp), "");
    }

    #[test]
    fn disposal_reject_replaces_with_placeholder() {
        let mut resp = tool_result("unsafe");
        let v = output_review_resp("deny", "reject", None);
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Rejected);
        assert!(result_text(&resp).contains("blocked by safety review"));
    }

    #[test]
    fn disposal_unknown_value_fails_closed() {
        let mut resp = tool_result("unsafe");
        let v = output_review_resp("deny", "quarantine_v2", None);
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Rejected);
        assert!(result_text(&resp).contains("blocked by safety review"));
    }

    #[test]
    fn disposal_warn_passes_content_unchanged() {
        let mut resp = tool_result("borderline content");
        let v = output_review_resp("allow", "warn", None);
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Warned);
        assert_eq!(result_text(&resp), "borderline content");
    }

    #[test]
    fn disposal_applies_to_every_text_block_and_leaves_non_text_alone() {
        let mut resp = serde_json::json!({
            "jsonrpc": "2.0",
            "result": { "content": [
                { "type": "text", "text": "first secret" },
                { "type": "text", "text": "second secret" },
                { "type": "image", "data": "AAA", "mimeType": "image/png" }
            ]}
        });
        let v = output_review_resp("deny", "redact", Some("redacted"));
        let outcome = apply_output_disposal(&mut resp, &v);
        assert_eq!(outcome, OutputDisposal::Redacted);
        let content = resp["result"]["content"].as_array().unwrap();
        assert_eq!(content[0]["text"], "redacted");
        assert_eq!(content[1]["text"], "redacted");
        assert_eq!(content[2]["data"], "AAA");
    }

    #[tokio::test]
    async fn inflight_guard_outlives_outer_wall_timeout() {
        use std::sync::atomic::Ordering;
        use std::time::Duration;

        let before = LOCAL_EXEC_INFLIGHT.load(Ordering::SeqCst);
        let guard = InflightGuard::acquire(usize::MAX).expect("guard must be acquired");
        assert_eq!(LOCAL_EXEC_INFLIGHT.load(Ordering::SeqCst), before + 1);

        // The outer wall fires long before the blocking work finishes. The
        // guard is captured by the closure, so the slot stays occupied while
        // the detached task is still running.
        let res = await_sandbox_wall(
            move || {
                let _inflight = guard;
                std::thread::sleep(Duration::from_millis(300));
                Err("work finished".to_string())
            },
            Duration::from_millis(50),
        )
        .await;
        assert!(matches!(res, Err(e) if e == "sandbox_exec_timeout"));
        assert_eq!(
            LOCAL_EXEC_INFLIGHT.load(Ordering::SeqCst),
            before + 1,
            "concurrency slot must not be released while blocking work runs"
        );

        tokio::time::sleep(Duration::from_millis(400)).await;
        assert_eq!(
            LOCAL_EXEC_INFLIGHT.load(Ordering::SeqCst),
            before,
            "slot must be released once the detached work really ends"
        );
    }
}
