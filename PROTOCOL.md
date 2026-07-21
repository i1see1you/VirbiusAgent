# VirbiusAgent Protocol Design — PROTOCOL

| Item | Description |
|------|------|
| Document Version | v3.6 |
| Status | Draft |
| Related | [DESIGN.md](DESIGN.md) (index) · [ARCHITECTURE.md](ARCHITECTURE.md) |
| Reference Project | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

> This document contains §2.6 MCP Server Integration (including §2.6.1 MCP Proxy Full Technical Solution).
> See [ARCHITECTURE.md](ARCHITECTURE.md) for the overall architecture design.

---

### 2.6 MCP Server Integration

Edge virbius-core needs to be integrated into MCP Server (Python/Node), wrapping tool execution:

| Framework | Integration Method | Interception Point |
|------|---------|--------|
| **Python MCP Server** | virbius-core compiled as PyO3 extension, calls virbius_core.precheck() + sandbox_execute() within tool handler | inside tool handler |
| **Node MCP Server** | virbius-core compiled as napi-rs extension, same as above | inside tool handler |
| **Generic subprocess** | MCP Server spawns("virbius-sandbox", ...) when launching tool | before process start |
| **LangChain** | SandboxedTool\<T\> wrapper, wrapping Tool::call() | inside Tool::call() |
| **OpenAI SDK** | SandboxedOpenAIClient proxy, intercepting tool_calls | before request serialization |
| **Generic MCP proxy** | Edge starts a local MCP Server as an intermediary proxy between Agent and real tools | tools/call request |

Generic MCP proxy mode:

```
Agent <-> Local MCP Proxy (virbius-core sandbox)
              |
              +-- allow -> forward to remote MCP Server
              +-- deny  -> return ToolError
```

#### 2.6.1 MCP Proxy Full Technical Solution

MCP Proxy is a framework-agnostic integration method: it runs as an independent process between the Agent and the MCP Server, intercepts the `tools/call` request of the MCP protocol, performs security precheck + cloud final judgment, and decides to allow or block. Any Agent framework that supports the MCP protocol (Claude Desktop, LangChain MCP Adapter, custom Agent) can integrate with zero code changes.

**Design goals**:
- Zero modification to Agent framework (transparent MCP protocol proxy)
- Single process supporting multiple Agent sessions (stdio multiplexing / SSE long connections)
- Precheck latency <2ms, full chain (including engine) latency <50ms
- Fail-open (low risk) or fail-closed (high risk) when Engine is unavailable

**Architecture**:

```
Agent (any MCP Client)
  |
  | MCP Protocol (JSON-RPC 2.0)
  |   - stdio (local process)
  |   - SSE  (remote connection)
  v
+---------------------------------------------------+
|  virbius-mcp-proxy                                |
|                                                   |
|  +-----------+    +--------------------+          |
|  | MCP Transport |  | Session Manager    |          |
|  | (stdio/SSE)|--->|  session_id -> ctx |          |
|  +-----+-----+    +--------------------+          |
|        |                                          |
|  +-----v----------------------------+             |
|  | JSON-RPC Router                  |             |
|  |  initialize  -> passthrough + inject           |
|  |  tools/list  -> passthrough + filter           |
|  |  tools/call  -> intercept -> precheck          |
|  |  *            -> passthrough                   |
|  +-----+----------------------------+             |
|        |                                          |
|  +-----v----------------------------+             |
|  | Security Pipeline                |             |
|  |  1. License verification (virbius-core)        |
|  |  2. Edge precheck (allowlist+schema)           |
|  |  3. Fast path decision                         |
|  |     +- hit -> allow (skip engine)              |
|  |     +- miss -> call engine final judgment      |
|  |  4. Output review (optional, P1)               |
|  +-----+----------------------------+             |
|        |                                          |
|  +-----v----------+  +------------------+         |
|  | Upstream MCP Client |  | Engine Client  |         |
|  | (forward to real    |  | (POST /v1/evaluate     |
|  |  MCP Server)       |  |  HTTP)                  |
|  +-----------------+  +------------------+         |
+---------------------------------------------------+
  |                    |
  v                    v
Real MCP Server     virbius-engine (:8082)
(GitHub/Slack/...)  virbius-control (:8080)
```

**MCP Protocol Handling**:

MCP uses JSON-RPC 2.0, the transport layer supports stdio and SSE. The Proxy's handling strategy for each JSON-RPC method:

| Method | Strategy | Description |
|--------|---------|------|
| `initialize` | passthrough + inject | Single upstream: forward to upstream MCP Server. Multiple upstreams: concurrently forward to all upstreams, take the first successful response. Inject Proxy capability declaration (including `multiUpstream: true`) into the response |
| `tools/list` | passthrough + merge + filter | Single upstream: forward to get tool list. Multiple upstreams: concurrently fetch all upstream tool lists, add prefix `{upstream}__{tool}` to conflicting names after merging. Filter by License `allowed_tools` before returning to Agent |
| `tools/call` | **Intercept** | Execute the full Security Pipeline, forward if allow, return error if deny. In multi-upstream mode, resolve the target upstream through the tool name routing table |
| `resources/*` | passthrough | not intercepted |
| `prompts/*` | passthrough | not intercepted |
| `notifications/*` | passthrough | unidirectional notification, not intercepted |

`tools/call` interception flow (core):

```
Agent sends tools/call { name, arguments }
  |
  v
1. Parse request
   - tool_name = request.params.name
   - args = request.params.arguments
   - session_id = extracted from MCP session or _meta
  |
  v
2. License verification (virbius-core::license)
   - verify(jwt, pubkey, app_id)
   - License present and valid -> continue to step 3
   - No License -> apply Fallback strategy (§2.6.1 Fallback)
     - minimum_privilege: high-risk tool deny / low-risk tool allow (rate-limited)
     - default_deny: deny (license_required)
     - audit_only: allow + audit (debug only)
   - License invalid (expired/revoked/bad signature) -> return JSON-RPC error: license_invalid
  |
  v
3. Edge precheck (virbius-core::precheck)
   - Check if tool_name is in License allowed_tools
   - ToolPolicy JSON Schema validation of args
   - Failure -> return JSON-RPC error: tool_blocked / schema_violation
  |
  v
4. Fast path decision
   - sandbox_type=="none" && fast_path && session_risk<30 && tool in fast_allowlist
   - Hit -> skip step 5, allow directly
   - Cold start: first N calls force full chain
  |
  v
5. Cloud final judgment (virbius-engine POST /v1/evaluate)
    Request body:
    {
      "trace_id": "...",
      "session_id": "...",
      "app_id": "...",
      "tool_name": "read_file",
      "args": { "path": "/etc/passwd" },
      "license_risk_quota": 60
    }
    Response:
    {
      "effective_action": "allow" | "block" | "review",
      "rule_id": "agent-tool-chain-detect",
      "reason": "dangerous chain",
      "risk_score_delta": 20,
      "session_risk_score": 45
    }
    - block -> return JSON-RPC error: engine_blocked
    - allow/review -> continue
  |
  v
6. Forward to upstream MCP Server
   - JSON-RPC passthrough tools/call request
   - Timeout 30s (configurable)
  |
  v
7. Output review (optional, P1)
   - STI Taint check tool return value
   - PII redaction
  |
  v
8. Return result to Agent
```

**Session Management**:

In MCP stdio mode, each Agent process has an independent connection with no explicit session_id. The Proxy derives the session from the connection + initialization parameters:

```rust
// virbius-mcp-proxy/src/session.rs

pub struct SessionManager {
    sessions: DashMap<ConnectionId, Session>,
}

pub struct Session {
    pub session_id: String,           // UUID or passed by Agent
    pub app_id: String,               // from initialize params
    pub tenant_id: String,
    pub license_jwt: String,          // from initialize params._meta
    pub trace_id: String,             // per-request or per-session
    pub tool_call_count: u32,         // 冷启动 warmup 计数
    pub upstream_initialized: HashMap<String, bool>, // 各上游 MCP Server 是否已 init（key=upstream_name）
    pub session_risk_score: u32,      // 会话累积风险分
    pub allowed_tools: Vec<String>,   // License 允许的工具列表
}
```

The Agent passes identity information in the `_meta` field of the `initialize` request:

```json
{
  "jsonrpc": "2.0",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": { "name": "my-agent", "version": "1.0" },
    "_meta": {
      "app_id": "code-review-agent",
      "tenant_id": "CompanyA",
      "license_jwt": "eyJ...",
      "session_id": "sess_abc"
    }
  }
}
```

> **No License Access (Fallback Policy)**: If there is no `license_jwt` in `_meta`, the Proxy does **not allow in audit-only mode** (otherwise an attacker could bypass all blocking by intentionally not passing a License). Instead, it applies the Fallback default policy, selecting a security posture based on configuration:
>
> | Fallback Mode | Behavior | Applicable Scenario | Config Default |
> |--------------|---------|-----------|------|
> | `minimum_privilege` | Only allow low-risk read-only tools (search/calculator/format), block high-risk tools (shell/execute_python/read_file/curl), DLP + schema validation still active, rate_limit reduced to 10/min, risk_quota reduced to 30 | **Default**, trial + gradual onboarding | **Default** |
> | `default_deny` | Block all tool calls, only return `license_required` error | Production environment requiring License | Should be set to this value for production deployment |
> | `audit_only` | Only audit, no blocking (original design, deprecated — requires explicit enabling) | Internal debugging only, **prohibited in production** | Requires explicit configuration |
>
> ```rust
> pub enum FallbackPolicy {
>     MinimumPrivilege,   // 默认：低风险工具放行 + 高风险阻断 + DLP/schema 生效
>     DefaultDeny,         // 生产：全部阻断，返回 license_required
>     AuditOnly,           // 调试：只审计不阻断（需显式配置，禁止生产）
> }
>
> impl FallbackPolicy {
>     fn check(&self, tool_name: &str, args: &Value) -> FallbackResult {
>         match self {
>             FallbackPolicy::MinimumPrivilege => {
>                 // 1. 高风险工具直接 deny
>                 if HIGH_RISK_TOOLS.contains(&tool_name) {
>                     return FallbackResult::deny("high_risk_tool_without_license");
>                 }
>                 // 2. 低风险工具 allow，但 DLP + schema 仍校验
>                 // 3. rate_limit 10/min, risk_quota 30
>                 FallbackResult::allow_with_limits(Limits {
>                     rate_limit: 10,
>                     risk_quota: 30,
>                 })
>             }
>             FallbackPolicy::DefaultDeny => {
>                 FallbackResult::deny("license_required")
>             }
>             FallbackPolicy::AuditOnly => {
>                 // 仅审计，不阻断（调试用）
>                 FallbackResult::allow_with_audit()
>             }
>         }
>     }
> }
>
> const HIGH_RISK_TOOLS: &[&str] = &[
>     "shell", "execute_python", "execute_code",
>     "read_file", "write_file", "delete_file",
>     "curl", "http_request", "fetch",
>     "read_secret", "write_secret",
>     "sql_query", "database_query",
> ];
> ```
>
> **Security Guarantees**: Regardless of the Fallback mode, the following security checks are **always active** and not affected by the Fallback policy:
> - DLP redaction (input + output)
> - JSON Schema parameter validation (if ToolPolicy exists)
> - Audit reporting (sample_rate=1.0)
> - session_risk_score accumulation (blocks upon reaching risk_quota)

**tools/list filtering**: The Proxy forwards `tools/list` to the upstream, then filters the response by License `allowed_tools`, and injects structured `annotations` (without modifying the `description` text):

```rust
fn filter_tools_list(response: &mut Value, session: &Session) {
    let license = License::verify(&session.license_jwt, &PUBKEY, &session.app_id)?;
    let allowed = &license.claims.allowed_tools;
    if let Some(tools) = response.get_mut("tools").and_then(|t| t.as_array_mut()) {
        tools.retain(|tool| {
            let name = tool.get("name").and_then(|n| n.as_str()).unwrap_or("");
            allowed.is_empty() || allowed.contains(&name.to_string())
        });
        // 注入结构化 annotations，不修改 description 文本
        for tool in tools.iter_mut() {
            let name = tool.get("name").and_then(|n| n.as_str()).unwrap_or("");
            if let Some(annotations) = build_tool_annotations(name, &license) {
                tool["annotations"] = annotations;
            }
        }
    }
}

/// 构建工具注解：MCP 标准字段 + Virbius 扩展字段
fn build_tool_annotations(tool_name: &str, license: &License) -> Option<Value> {
    let policy = manifest::tool_policy(tool_name)?;
    let mut ann = serde_json::json!({
        // MCP 标准注解（2025-03-26 spec）
        "readOnlyHint": policy.sandbox_type == "none" && !tool_name.starts_with("write"),
        "destructiveHint": tool_name.starts_with("write") || tool_name == "shell",
        "openWorldHint": tool_name == "curl" || tool_name == "shell",
    });
    // Virbius 扩展注解（x- 前缀，非标准，MCP 客户端可选择性消费）
    if let Some(constraints) = manifest::tool_constraints(tool_name) {
        ann["x-virbius-risk-level"] = Value::String(constraints.risk_level);
        if !constraints.allowed_hosts.is_empty() {
            ann["x-virbius-allowed-hosts"] = serde_json::to_value(&constraints.allowed_hosts).ok()?;
        }
        if !constraints.allowed_paths.is_empty() {
            ann["x-virbius-allowed-paths"] = serde_json::to_value(&constraints.allowed_paths).ok()?;
        }
    }
    Some(ann)
}
```

> **Design Decision: Do not inject constraint text into description**
>
> The early design concatenated security constraints (e.g. "only allow connecting to api.internal:443") into each tool's `description` field. This caused:
> 1. **Token bloat**: N tools × ~50 tokens of constraint text per tool = 500+ tokens extra overhead, potentially exceeding LLM context window when many tools exist
> 2. **Redundant repetition**: Common constraints (e.g. "shall not bypass security controls") repeated in every tool description
> 3. **Maintenance difficulty**: Constraint changes require modifying all tools' descriptions
>
> **New approach**: Constraints delivered in two layers —
> - **Structured annotations**: Injected into the `annotations` field (MCP standard compatible) of the `tools/list` response, consumed by MCP client UI and local precheck logic, **not entered into the LLM prompt**
> - **Centralized system prompt injection**: All tool constraints are rendered by the Prompt Gateway ([§2.8](ARCHITECTURE.md#28-prompt-gateway-prompt-enhancement)) into the "### Tool Usage Rules" section of the system prompt, **appearing only once** instead of repeating for each tool

**Error Response Format**:

Following the JSON-RPC 2.0 specification, using the reserved `-32000` ~ `-32099` range (implementation-defined server errors) to define VirbiusAgent-specific error codes. Transport layer agnostic (stdio / SSE / WebSocket all applicable):

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32004,
    "message": "not_in_allowlist",
    "data": {
      "tool_name": "execute_python",
      "rule_id": null,
      "trace_id": "uuid",
      "session_risk_score": 45,
      "http_analog": 403
    }
  }
}
```

> `http_analog` is for reference display only on the Operation Dashboard frontend, not part of protocol logic. No HTTP semantics under stdio / WebSocket transport.

**VirbiusAgent JSON-RPC Error Code Definitions** (-32000 ~ -32099):

| Code | message | Description | http_analog |
|------|---------|------|-------------|
| -32001 | `license_invalid` | License expired/revoked/bad signature | 401 |
| -32002 | `license_required` | No License and fallback=default_deny | 401 |
| -32003 | `high_risk_without_license` | No License and fallback=minimum_privilege, tool is high-risk | 403 |
| -32004 | `not_in_allowlist` | Tool not in License allowed_tools | 403 |
| -32005 | `schema_violation` | Arguments do not conform to JSON Schema | 400 |
| -32006 | `engine_blocked` | Cloud Groovy L3 final judgment deny | 403 |
| -32007 | `rate_exceeded` | Tool call frequency exceeded limit | 429 |
| -32008 | `risk_threshold` | session_risk_score exceeded License risk_quota | 403 |
| -32009 | `output_review_blocked` | Output review blocked (P1) | 403 |
| -32010 | `fallback_blocked` | Fallback policy generic block | 403 |
| -32011 | `challenge_required` | High-risk operation requires human approval (challenge flow) | 403 |

```rust
// virbius-mcp-proxy/src/error.rs

/// VirbiusAgent JSON-RPC 错误码（-32000 ~ -32099，JSON-RPC 2.0 保留区间）
pub enum VirbiusErrorCode {
    LicenseInvalid       = -32001,
    LicenseRequired      = -32002,
    HighRiskNoLicense    = -32003,
    NotInAllowlist       = -32004,
    SchemaViolation      = -32005,
    EngineBlocked        = -32006,
    RateExceeded         = -32007,
    RiskThreshold        = -32008,
    OutputReviewBlocked  = -32009,
    FallbackBlocked      = -32010,
    ChallengeRequired    = -32011,
}

pub fn jsonrpc_error(code: VirbiusErrorCode, id: i64, data: Value) -> Value {
    serde_json::json!({
        "jsonrpc": "2.0",
        "id": id,
        "error": {
            "code": code as i32,
            "message": code.message(),
            "data": data
        }
    })
}
```

---

### Challenge Flow (-32011 `challenge_required`)

When the Engine returns `effective_action = "challenge"`, it indicates that the tool call requires human approval before execution. Challenge supports two paths: **Inline** (instant confirmation) and **Dashboard** (asynchronous Operation Dashboard approval).

#### Flow Sequence

```
Agent                 Proxy/Gateway          Engine                Control Dashboard
  |                        |                    |                        |
  |--- tools/call -------->|                    |                        |
  |                        |--- /v1/evaluate -->|                        |
  |                        |<-- {action:        |                        |
  |                        |      "challenge",  |                        |
  |                        |      challenge_id, |                        |
  |                        |      args_hash} ---|                        |
  |<-- error -32011 -------|                    |                        |
  |    {challenge_id,      |                    |                        |
  |     args_hash}         |                    |                        |
  |                        |                    |                        |
  | (Agent polls challenge status)              |                        |
  |--- GET /v1/challenge/{id}/status ---------->|                        |
  |<-- {status: "pending"} ---------------------|                        |
  |                        |                    |                        |
  |                        |                    |<-- approve/reject ----|
  |                        |                    |    (Dashboard approval)|
  |                        |                    |                        |
  |--- GET /v1/challenge/{id}/status ---------->|                        |
  |<-- {status: "approved",                     |                        |
  |     token: "vct_xxx"} ----------------------|                        |
  |                        |                    |                        |
  |--- tools/call -------->|                    |                        |
  |   (_meta.challenge_token: "vct_xxx")        |                        |
  |                        |--- /v1/challenge/verify -->|                |
  |                        |<-- {valid: true} ---------|                 |
  |                        |--- (forward to upstream)   |                |
  |<-- result -------------|                    |                        |
```

#### Error Response Format (-32011)

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "error": {
    "code": -32011,
    "message": "challenge_required",
    "data": {
      "tool_name": "delete_file",
      "challenge_id": "ch_a1b2c3d4e5f6a7b8",
      "args_hash": "sha256:abc123...",
      "rule_id": "RL_HIGH_RISK_FILE_OPS",
      "reason": "high_risk_operation",
      "trace_id": "uuid-xxx",
      "session_risk_score": 75,
      "http_analog": 403
    }
  }
}
```

#### Retry Format (with challenge token)

After receiving approval, the Agent retries the original `tools/call` request, including `challenge_token` in `_meta`:

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "method": "tools/call",
  "params": {
    "name": "delete_file",
    "arguments": { "path": "/data/important.txt" },
    "_meta": {
      "challenge_token": "vct_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"
    }
  }
}
```

When Proxy/Gateway receives a request with `challenge_token`:
1. Call Engine `POST /v1/challenge/verify` to validate the token (single-use)
2. If valid, forward to upstream MCP Server (remove `_meta.challenge_token`)
3. If validation fails, return `-32011` error

> **Gateway (WASM) Path**: Token is passed via HTTP Header `X-Virbius-Challenge-Token` instead of `_meta`.

#### Engine Challenge API

| Method | Path | Description |
|--------|------|------|
| `GET` | `/v1/challenge/{id}/status` | Query challenge status (pending/approved/rejected/expired) |
| `POST` | `/v1/challenge/{id}/approve` | Approve, generate single-use token (Dashboard call) |
| `POST` | `/v1/challenge/{id}/reject` | Reject approval (Dashboard call) |
| `POST` | `/v1/challenge/verify` | Validate token validity (Proxy/Gateway call) |
| `GET` | `/v1/challenges?tenant_id=default&status=pending` | List pending challenges (Dashboard queue) |

#### Path Selection Configuration

The Challenge path (Inline vs Dashboard) is determined by the following configuration:

1. **ToolPolicy** (`virbius-core/src/manifest.rs`): Each tool can declare `challenge_method` (`inline` | `dashboard` | `auto`)
2. **Rule body_json**: Rules can override `challenge_method`
3. **Agent `initialize._meta`**: Agent can declare `challenge_methods: ["inline", "dashboard"]` to indicate supported confirmation methods
4. **Engine config** (`application.yml`): `virbius.challenge.ttl-seconds` and `virbius.challenge.token-ttl-seconds`

Default `auto`: Engine selects the path based on Agent's declared capabilities. If Agent supports `inline`, Inline is preferred; otherwise Dashboard is used.

#### Security Guarantees

- **Single-use Token**: Immediately marked as `used` after verification, second use returns `valid=false`
- **Args Binding**: Token is bound to the original request's `tool_name` + `args_hash`, cannot be reused across requests
- **TTL Limits**: Challenge record expires after 300s by default, Token expires after 600s by default
- **Audit Trail**: All challenge operations (create/approve/reject/verify) are recorded in audit logs

**Configuration**:

```toml
# virbius-mcp-proxy.toml

[proxy]
listen = "stdio"                    # stdio | tcp://0.0.0.0:9090

# ── Single upstream mode (backward compatible) ──
# The following three fields are equivalent to upstreams = [{ name = "default", url = "...", sse_path = "/sse" }]
upstream_url = "http://mcp-server:8080"  # Real MCP Server address
upstream_transport = "sse"          # stdio | sse
upstream_sse_path = "/sse"

# ── Multi-upstream mode (P1 addition, mutually exclusive with upstream_url) ──
# When upstreams array is configured, the upstream_url field is ignored
# Each upstream must have a unique name, used for tool name prefix and routing
upstreams = [
    { name = "filesystem", url = "http://fs-mcp:8081", sse_path = "/sse" },
    { name = "github",     url = "http://gh-mcp:8082", sse_path = "/sse" },
    { name = "database",   url = "http://db-mcp:8083", sse_path = "/sse" },
]

[security]
control_base_url = "http://virbius-control:8080"
engine_url = "http://virbius-engine:8082"
license_public_key = "/etc/virbius/ed25519-pub.pem"
fallback_policy = "minimum_privilege"  # Policy when no License: minimum_privilege | default_deny | audit_only

[security.fast_path]
enabled = true
warmup_calls = 5                    # First 5 calls force full chain
risk_threshold = 30

[security.failover]
high_risk_fail_closed = true        # Deny when engine unavailable for high-risk tools
low_risk_fail_open = true           # Allow + audit when engine unavailable for low-risk tools
engine_timeout_ms = 3000

[audit]
redis_url = "redis://127.0.0.1:6379"
sample_rate = 1.0                   # Audit sample rate
```

**Deployment Modes**:

| Mode | Applicable Scenario | Deployment Method | Traffic Topology |
|------|---------|---------|---------|
| **Sidecar** | Inside K8s Pod, Agent and Proxy in the same Pod | Agent container + Proxy container, sharing localhost | East-west (MCP calls do not pass through Gateway) |
| **Local Process** | Development environment / single-machine deployment | Agent process spawns Proxy child process (stdio) | East-west |
| **Standalone Service** | Multiple Agents share one Proxy | Proxy deployed independently, Agent connects via SSE | North-south (through Gateway Ingress) |

> **Egress Traffic Control in Sidecar Mode**
>
> In Sidecar mode, the Agent's MCP tool calls go through localhost directly to the Proxy (east-west), without passing through the Gateway. However, external HTTP requests (Egress) initiated by the Agent through tools such as `curl` do not go through the MCP protocol and require additional control.
>
> **Key Design Decision: Tool-Level Control Rather Than Process-Level Network Disconnection**
>
> Proxy proxying only applies to business tools explicitly defined in the MCP protocol (`curl`/`web_search`/`http_request` etc.). Implicit network requests at the Agent framework level (LangChain config fetching, SDK model downloads, heartbeat detection, telemetry reporting, etc.) are **not proxied by the Proxy** but initiated by the Agent itself, restricted by K8s NetworkPolicy to the minimum whitelist targets.
>
> This decision is based on the following considerations:
> 1. **Backward compatibility**: Frameworks like LangChain, AutoGen, OpenAI SDK implicitly initiate network requests (pulling config, downloading models, heartbeat detection). Direct network disconnection would prevent many existing Agents from running
> 2. **Full-proxy cost**: Full proxying (proxying all Agent network traffic) requires supporting all HTTP semantics such as WebSocket duplex, large file chunked upload, complex Header passthrough, HTTP/2 multiplexing, etc., with extremely high development cost; proxying only MCP business tools greatly reduces complexity. Tool-level proxying only needs to support GET/POST + streaming response passthrough (chunked/SSE), achievable with reqwest `bytes_stream()`
> 3. **Threat model match**: Security threats come from **controllable external requests** initiated by the Agent through business tools (curl/execute_python/shell), not from framework-level **fixed-target** network calls. The former requires Security Pipeline validation, the latter only needs NetworkPolicy to restrict targets
>
> | Traffic Type | Source | P0 Control Method | Description |
> |---------|------|-----------|------|
> | **Business tool requests** | Explicit tools in MCP `tools/call` such as `curl`/`web_search` | Proxy proxy + URL whitelist | Agent passes `tools/call` to Proxy for execution, Proxy validates URL whitelist then initiates HTTP request |
> | **Framework implicit requests** | Agent framework level (config fetching, model download, heartbeat, telemetry) | K8s NetworkPolicy restricts targets | Initiated by Agent itself, NetworkPolicy only allows whitelist targets (e.g. `*.openai.com`, `registry.internal`) |
> | **Process-level full outbound** | All TCP connections | P2: eBPF/iptables transparent interception | Process-level fallback, captures direct TCP connections bypassing MCP protocol |
>
> **P0 Solution — Tool-Level Proxy Proxying**:
>
> ```
> Agent ──tools/call(curl, url)──> MCP Proxy
>                                     |
>                                     +-- URL whitelist validation (License allowed_hosts)
>                                     +-- precheck pass -> Proxy initiates HTTP request -> External API
>                                     +-- precheck fail -> deny
>                                     |
>                                     v
>                                   External API
>
> Agent ──implicit HTTP (SDK/framework level)──> External targets (restricted by NetworkPolicy)
>                                     |
>                                     +-- NetworkPolicy only allows whitelist targets
>                                     +-- Non-whitelist targets -> silently dropped by K8s CNI
> ```
>
> Business tool calls explicitly defined in the MCP protocol (`curl`/`web_search`/`http_request` etc.) are handed over to Proxy via `tools/call` for proxying. The Proxy validates URL whitelist at the application layer, then decides to allow or block. This solution requires no kernel-level network interception and can be implemented at P0. Implicit network requests at the Agent framework level are not proxied by the Proxy and are restricted to the minimum whitelist targets via NetworkPolicy.
>
> **NetworkPolicy Configuration Example** (restrict Agent container implicit outbound):
>
> ```yaml
> apiVersion: networking.k8s.io/v1
> kind: NetworkPolicy
> metadata:
>   name: agent-egress-restrict
> spec:
>   podSelector:
>     matchLabels:
>       app: agent
>   policyTypes:
>   - Egress
>   egress:
>   # Allow access to same-Pod Proxy (localhost:9090)
>   - to:
>     - podSelector:
>         matchLabels:
>           app: virbius-mcp-proxy
>   # Allow access to LLM API (e.g. OpenAI/Anthropic)
>   - to:
>     - namespaceSelector: {}
>     ports:
>     - protocol: TCP
>       port: 443
>     # In practice, use IPBlock CIDR to restrict to specific LLM API endpoints
>   # Allow access to internal image/model registry
>   - to:
>     - podSelector:
>         matchLabels:
>           app: model-registry
>   # Allow DNS resolution
>   - to:
>     - namespaceSelector:
>         matchLabels:
>           kubernetes.io/metadata.name: kube-system
>     ports:
>     - protocol: UDP
>       port: 53
> ```
>
> **HTTP capability boundary of Proxy proxying**:
>
> Since proxying is limited to MCP business tools only, the Proxy's HTTP client capabilities can be implemented on demand without covering all HTTP semantics:
>
> | Capability | P0 Support | P1 Enhancement | Description |
> |------|---------|---------|------|
> | GET/POST | ✅ | — | Basic HTTP methods |
> | Custom Header | ✅ (whitelist passthrough) | — | Only pass through safe headers, filter `Authorization` (injected by License) |
> | Streaming response passthrough (chunked/SSE) | ✅ | — | reqwest `bytes_stream()` streaming read, avoid OOM for large responses; passthrough SSE events one by one |
> | Large file download | ✅ (streaming, 50MB limit) | ✅ (chunked write to temp file) | P0 streaming read + memory limit protection, return 413 on exceed |
> | Timeout control | ✅ (30s) | — | Return 504 on timeout |
> | Redirect following | ✅ (max 5 hops) | — | Prevent SSRF via redirect |
> | HTTPS | ✅ | — | Proxy initiates TLS, Agent does not touch certificates |

Sidecar deployment (K8s) — single upstream:

```yaml
spec:
  containers:
  - name: agent
    image: my-agent:latest
    env:
    - name: MCP_SERVER_URL
      value: "http://localhost:9090"  # Points to same-Pod Proxy
  - name: virbius-mcp-proxy
    image: virbius-mcp-proxy:latest
    env:
    - name: VIRBIUS_UPSTREAM_URL
      value: "http://mcp-server.default.svc:8080"
    - name: VIRBIUS_CONTROL_URL
      value: "http://virbius-control.default.svc:8080"
    - name: VIRBIUS_ENGINE_URL
      value: "http://virbius-engine.default.svc:8082"
```

Sidecar deployment (K8s) — multi-upstream:

```yaml
spec:
  containers:
  - name: agent
    image: my-agent:latest
    env:
    - name: MCP_SERVER_URL
      value: "http://localhost:9090"  # Points to same-Pod Proxy
  - name: virbius-mcp-proxy
    image: virbius-mcp-proxy:latest
    env:
    # Multi-upstream: JSON array format, each upstream must have a unique name
    - name: VIRBIUS_UPSTREAMS
      value: >-
        [{"name":"filesystem","url":"http://fs-mcp.default.svc:8081","sse_path":"/sse"},{"name":"github","url":"http://gh-mcp.default.svc:8082","sse_path":"/sse"}]
    - name: VIRBIUS_CONTROL_URL
      value: "http://virbius-control.default.svc:8080"
    - name: VIRBIUS_ENGINE_URL
      value: "http://virbius-engine.default.svc:8082"
```

> **Multi-upstream configuration note**: After configuring `VIRBIUS_UPSTREAMS`, `VIRBIUS_UPSTREAM_URL` is ignored.
> Each upstream must have a unique `name`, used for prefixing on tool name conflicts (e.g. `filesystem__read_file`).
> See [§2.6.2](#262-multi-upstream) for the detailed multi-upstream solution.

Local process deployment (development):

```bash
# Start Proxy (stdio mode)
export VIRBIUS_UPSTREAM_URL=http://localhost:8080
export VIRBIUS_CONTROL_URL=http://localhost:8080
export VIRBIUS_ENGINE_URL=http://localhost:8082
virbius-mcp-proxy --transport stdio

# Agent configures MCP Server as Proxy
# Claude Desktop config:
# {
#   "mcpServers": {
#     "virbius": {
#       "command": "virbius-mcp-proxy",
#       "env": { "VIRBIUS_UPSTREAM_URL": "http://localhost:8080" }
#     }
#   }
# }
```

**Implementation Structure**:

```
virbius-mcp-proxy/
├── Cargo.toml
├── src/
│   ├── main.rs              # Entry point + CLI argument parsing
│   ├── transport/
│   │   ├── mod.rs           # Transport layer trait
│   │   ├── stdio.rs         # stdio transport (line-delimited JSON-RPC)
│   │   └── sse.rs           # SSE transport (HTTP + Server-Sent Events)
│   ├── router.rs            # JSON-RPC method routing
│   ├── session.rs           # Session management (ConnectionId -> Session)
│   ├── pipeline.rs          # Security Pipeline (License -> precheck -> engine -> audit)
│   ├── upstream.rs          # Upstream MCP Client (forward requests)
│   ├── audit.rs             # Audit event reporting (Redis Stream)
│   └── config.rs            # Configuration loading (TOML + environment variables)
├── examples/
│   └── demo_agent.rs        # Simulated Agent call demo
└── tests/
    └── integration_test.rs  # End-to-end integration tests
```

**Core Dependencies**:

```toml
[dependencies]
virbius-core = { path = "../virbius-core" }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4"] }
dashmap = "6"                        # Concurrent session table
reqwest = "0.12"                     # HTTP client (call engine + upstream SSE)
toml = "0.8"                         # Config parsing
tracing = "0.1"                      # Structured logging
tokio-util = "0.7"                   # codec (line-delimited JSON)
```

**Security Pipeline Core Implementation**:

```rust
// virbius-mcp-proxy/src/pipeline.rs

pub struct SecurityPipeline {
    license_pubkey: Vec<u8>,
    engine_client: EngineClient,
    fast_path: FastPathConfig,
    failover: FailoverConfig,
    audit_sink: AuditSink,
}

impl SecurityPipeline {
    pub async fn check_tool_call(
        &self,
        session: &Session,
        tool_name: &str,
        args: &Value,
    ) -> PipelineResult {
        // 1. License verification
        let license = License::verify(
            &session.license_jwt, &self.license_pubkey, &session.app_id
        ).map_err(|e| PipelineResult::deny("license_invalid", e))?;

        // 2. Edge precheck
        let call = ToolCall { tool_name: tool_name.into(), args: args.clone(), .. };
        let pre = precheck::precheck(&license, &call);
        if !pre.allowed {
            return Ok(PipelineResult::deny("not_in_allowlist", pre.reason));
        }

        // 3. Fast path
        if self.is_fast_path(session, &pre, tool_name) {
            self.audit(Allow, session, tool_name, "fast_path").await;
            return Ok(PipelineResult::allow("fast_path"));
        }

        // 4. Cloud final judgment
        match self.engine_client.evaluate(EvaluateRequest {
            trace_id: &session.trace_id,
            session_id: &session.session_id,
            app_id: &session.app_id,
            tool_name, args,
            risk_quota: license.claims.risk_quota,
        }).await {
            Ok(resp) => {
                if resp.effective_action == "block" {
                    self.audit(Block, session, tool_name, &resp.reason).await;
                    return Ok(PipelineResult::deny("engine_blocked", resp.reason));
                }
                self.audit(Allow, session, tool_name, "engine:allow").await;
                Ok(PipelineResult::allow("engine"))
            }
            Err(e) => {
                // failover
                if pre.sandbox_type == "none" && self.failover.low_risk_fail_open {
                    self.audit(Allow, session, tool_name, "fail_open").await;
                    Ok(PipelineResult::allow("fail_open"))
                } else {
                    Ok(PipelineResult::deny("engine_unavailable", e.to_string()))
                }
            }
        }
    }
}
```

#### 2.6.2 Multi-Upstream

The Proxy supports connecting to multiple MCP Servers simultaneously, routing `tools/call` requests to the correct upstream via tool name. Single-upstream mode is a special case of multi-upstream (`len() == 1`), and all old configurations are automatically normalized to a single-entry multi-upstream.

**Design Principles**:
- **Backward compatible**: Old `upstream_url` / `VIRBIUS_UPSTREAM_URL` configurations are automatically normalized to `upstreams = [{ name: "default", ... }]`
- **Prefix on conflict only**: Non-conflicting tool names remain unchanged, prefix `{upstream_name}__{tool_name}` is added only when multiple upstreams have identically named tools
- **Security Pipeline uses original name**: License `allowed_tools` matches the original tool name (after prefix stripping), does not require Agent to be aware of prefixes
- **Forward restores original name**: Proxy restores the original tool name when forwarding `tools/call` to the upstream, upstream MCP Server is unaware

**Architecture**:

```
Agent (any MCP Client)
  |
  | MCP Protocol (JSON-RPC 2.0)
  v
+---------------------------------------------------+
|  virbius-mcp-proxy (multi-upstream mode)           |
|                                                   |
|  +--------------------+                           |
|  | JSON-RPC Router    |                           |
|  |  initialize  -> concurrently forward all upstreams             |
|  |  tools/list  -> concurrently fetch + merge + prefix handling   |
|  |  tools/call  -> routing table resolve -> forward target upstream|
|  +--------+---------+                             |
|           |                                       |
|  +--------v---------+                             |
|  | UpstreamManager   |                             |
|  |  entries: [fs, gh, db]                         |
|  |  connections: (session, upstream) -> Client    |
|  |  tool_routes: displayed_name -> (up, orig)     |
|  +----+------+------+------+                      |
|       |      |      |                             |
+-------|------|------|-----------------------------+
        |      |      |
        v      v      v
    MCP Server A  MCP Server B  MCP Server C
    (filesystem)  (github)      (database)
    read_file     read_file      sql_query
    search        create_issue   backup
```

**Tool Name Conflict Resolution**:

```
Upstream filesystem returns: [read_file, search]
Upstream github      returns: [read_file, create_issue]

Merged result (read_file conflicts, add prefix):
  [
    { "name": "filesystem__read_file", "x-virbius-upstream": "filesystem" },
    { "name": "search",                "x-virbius-upstream": "filesystem" },
    { "name": "github__read_file",     "x-virbius-upstream": "github" },
    { "name": "create_issue",          "x-virbius-upstream": "github" }
  ]

Routing table:
  filesystem__read_file -> (filesystem, read_file)
  search                -> (filesystem, search)
  github__read_file     -> (github, read_file)
  create_issue          -> (github, create_issue)

Agent calls tools/call { name: "github__read_file" }:
  1. Routing table resolve: upstream=github, original_name=read_file
  2. Security Pipeline: check "read_file" against License allowed_tools
  3. Forward: restore params.name = "read_file", POST to github upstream
```

> **Transparent to Agent**: The Agent gets prefixed tool names via `tools/list` and calls them using the prefixed name. The Proxy automatically handles prefix stripping and upstream routing. If the Agent directly calls a non-prefixed tool name and that name has no conflict, the Proxy can also route it correctly.

**Configuration Normalization**:

| Configuration | Equivalent Multi-Upstream Config |
|---------|-------------|
| `upstream_url = "http://mcp:8080"` | `upstreams = [{ name: "default", url: "http://mcp:8080", sse_path: "/sse" }]` |
| `VIRBIUS_UPSTREAM_URL=http://mcp:8080` | Same as above |
| `upstreams = [{ name = "fs", ... }]` | Used directly (upstream_url ignored) |
| `VIRBIUS_UPSTREAMS='[{"name":"fs",...}]'` | Used directly |

**Environment Variable Configuration for Multi-Upstream**:

```bash
# JSON array format
export VIRBIUS_UPSTREAMS='[
  {"name":"filesystem","url":"http://fs-mcp:8081","sse_path":"/sse"},
  {"name":"github","url":"http://gh-mcp:8082","sse_path":"/sse"}
]'
```

**Session and Connection Management**:

In multi-upstream mode, each (session_id, upstream_name) pair maintains an independent SSE connection:

```rust
// connections index: (session_id, upstream_name) -> UpstreamClient
connections: DashMap<(String, String), UpstreamClient>

// Session TTL cleanup: remove(session_id) clears connections across all upstreams for that session
// Disconnect cleanup: cleanup_disconnected() scans all connections, removes disconnected SSE connections
```

**initialize Response Injection**:

```json
{
  "capabilities": {
    "virbiusProxy": {
      "securityPipeline": true,
      "licenseVerification": true,
      "engineEvaluate": true,
      "fastPath": true,
      "multiUpstream": true,
      "traceCollector": true
    }
  }
}
```

#### 2.6.3 Decision Trace Trace Collector

The MCP Proxy has a built-in `TraceCollector` module that collects trace events at two key points in the `tools/call` request lifecycle, writing them asynchronously to Redis Stream `virbius:trace`, consumed by the Control-side `TraceIngestService` for persistence.

**Collection Points**:

| Collection Point | event_type | Timing | Recorded Content |
|--------|-----------|------|---------|
| `tool_call` | `tool_call` | After Security Pipeline check passes, before forwarding to upstream | tool_name, arguments, step_id, parent_step_id |
| `tool_result` | `tool_result` | After upstream returns, before responding to Agent | tool_name, result, is_error, duration_ms |

**Step Tracing**:

Each Session maintains a `step_seq` (incrementing sequence number) and `last_step_id` (previous step ID). The new step's `parent_step_id` is automatically set to `last_step_id`, forming a causal chain:

```
step-001 (tool_call: read_file)
  └── step-002 (tool_result: read_file)
       └── step-003 (tool_call: write_file)
            └── step-004 (tool_result: write_file)
```

**TraceEvent Format**:

```json
{
  "trace_id": "550e8400-e29b-41d4-a716-446655440000",
  "session_id": "sess_abc123",
  "parent_step_id": "step-001",
  "step_id": "step-002",
  "step_seq": 2,
  "event_type": "tool_call",
  "tool_name": "read_file",
  "arguments": { "path": "/etc/hosts" },
  "result": null,
  "is_error": false,
  "error_message": null,
  "duration_ms": null,
  "tenant_id": "tenant-001",
  "timestamp": "2026-07-08T12:00:00.123Z"
}
```

**Configuration** (`config.toml`):

```toml
[trace]
enabled = true          # Default true
redis_url = "redis://127.0.0.1:6379"
stream_key = "virbius:trace"
max_fields_len = 32768  # Max field length (bytes), truncated if exceeded
```

**Redis Stream Write**:

```
XADD virbius:trace * \
  trace_id 550e8400-... \
  session_id sess_abc123 \
  step_id step-002 \
  parent_step_id step-001 \
  step_seq 2 \
  event_type tool_call \
  tool_name read_file \
  arguments '{"path":"/etc/hosts"}' \
  tenant_id tenant-001 \
  timestamp 2026-07-08T12:00:00.123Z
```

**Control Side Consumption**:

`TraceIngestService` consumes `virbius:trace` using `XREADGROUP`, writes idempotently to the `tb_agent_trace` table via `JdbcTemplate`, with checkpoint (last delivered ID) persisted in `tb_trace_ingest_checkpoint`.

**REST API**:

| Method | Path | Description |
|------|------|------|
| GET | `/api/v1/admin/tenants/{tenantId}/trace/session/{sessionId}/timeline` | Session timeline (ordered by step_seq) |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/trace/{traceId}` | Trace causal chain (parent_step_id recursive) |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/search?toolName=&sessionId=&limit=` | Search |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/ingest/status` | Ingest health status (pending/lag) |
