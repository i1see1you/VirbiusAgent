# VirbiusAgent 协议设计 — PROTOCOL

| 项目 | 描述 |
|------|------|
| 文档版本 | v3.6 |
| 状态 | Draft / 草案 |
| 相关文档 | [DESIGN.md](DESIGN.md)（索引）· [ARCHITECTURE.md](ARCHITECTURE.md) |
| 参考项目 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

> 本文档包含 §2.6 MCP 服务器集成（含 §2.6.1 MCP 代理完整技术方案）。
> 整体架构设计请参见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

### 2.6 MCP 服务器集成

边缘 virbius-core 需要集成到 MCP 服务器（Python/Node）中，包装工具执行：

| 框架 | 集成方式 | 拦截点 |
|------|---------|--------|
| **Python MCP Server** | virbius-core 编译为 PyO3 扩展，在工具处理器内调用 virbius_core.precheck() + sandbox_execute() | 工具处理器内部 |
| **Node MCP Server** | virbius-core 编译为 napi-rs 扩展，同上 | 工具处理器内部 |
| **通用子进程** | MCP Server 启动子进程 spawn("virbius-sandbox", ...) 来启动工具 | 进程启动前 |
| **LangChain** | SandboxedTool\<T\> 包装器，包装 Tool::call() | Tool::call() 内部 |
| **OpenAI SDK** | SandboxedOpenAIClient 代理，拦截 tool_calls | 请求序列化前 |
| **通用 MCP 代理** | 边缘启动本地 MCP 服务器作为 Agent 与真实工具之间的中间代理 | tools/call 请求 |

通用 MCP 代理模式：

```
Agent <-> Local MCP Proxy (virbius-core sandbox)
              |
              +-- allow -> forward to remote MCP Server
              +-- deny  -> return ToolError
```

#### 2.6.1 MCP 代理完整技术方案

MCP 代理是一种框架无关的集成方式：它作为独立进程运行在 Agent 和 MCP 服务器之间，拦截 MCP 协议的 `tools/call` 请求，执行安全预检 + 云端最终裁决，决定放行或拦截。任何支持 MCP 协议的 Agent 框架（Claude Desktop、LangChain MCP Adapter、自定义 Agent）均可零代码变更集成。

**设计目标**：
- 零修改 Agent 框架（透明 MCP 协议代理）
- 单进程支持多个 Agent 会话（stdio 多路复用 / SSE 长连接）
- 预检延迟 <2ms，全链路（含引擎）延迟 <50ms
- 引擎不可用时支持故障开放（低风险）或故障关闭（高风险）

**架构**：

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

**MCP 协议处理**：

MCP 使用 JSON-RPC 2.0，传输层支持 stdio 和 SSE。代理对各 JSON-RPC 方法的处理策略：

| 方法 | 策略 | 描述 |
|--------|---------|------|
| `initialize` | 透传 + 注入 | 单上游：转发到上游 MCP 服务器。多上游：并发转发到所有上游，取第一个成功响应。在响应中注入代理能力声明（含 `multiUpstream: true`） |
| `tools/list` | 透传 + 合并 + 过滤 | 单上游：转发获取工具列表。多上游：并发获取所有上游工具列表，合并后对冲突名称添加前缀 `{upstream}__{tool}`。返回 Agent 前按 License `allowed_tools` 过滤 |
| `tools/call` | **拦截** | 执行完整安全流水线，放行则转发，拒绝则返回错误。多上游模式下通过工具名路由表解析目标上游 |
| `resources/*` | 透传 | 不拦截 |
| `prompts/*` | 透传 | 不拦截 |
| `notifications/*` | 透传 | 单向通知，不拦截 |

`tools/call` 拦截流程（核心）：

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

**会话管理**：

在 MCP stdio 模式下，每个 Agent 进程有独立的连接，没有显式的 session_id。代理从连接 + 初始化参数中派生出会话：

```rust
// virbius-mcp-proxy/src/session.rs

pub struct SessionManager {
    sessions: DashMap<ConnectionId, Session>,
}

pub struct Session {
    pub session_id: String,           // UUID 或由 Agent 传入
    pub app_id: String,               // 来自 initialize 参数
    pub tenant_id: String,
    pub license_jwt: String,          // 来自 initialize 参数._meta
    pub trace_id: String,             // 每个请求或每个会话
    pub tool_call_count: u32,         // 冷启动 warmup 计数
    pub upstream_initialized: HashMap<String, bool>, // 各上游 MCP Server 是否已 init（key=upstream_name）
    pub session_risk_score: u32,      // 会话累积风险分
    pub allowed_tools: Vec<String>,   // License 允许的工具列表
}
```

Agent 在 `initialize` 请求的 `_meta` 字段中传递身份信息：

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

> **无 License 访问（回退策略）**：如果 `_meta` 中没有 `license_jwt`，代理**不会**以仅审计模式放行（否则攻击者可以通过故意不传 License 来绕过所有拦截）。而是应用回退默认策略，根据配置选择安全姿态：
>
> | 回退模式 | 行为 | 适用场景 | 配置默认值 |
> |--------------|---------|-----------|------|
> | `minimum_privilege` | 仅允许低风险只读工具（搜索/计算器/格式化），阻止高风险工具（shell/execute_python/read_file/curl），DLP + schema 校验仍然生效，rate_limit 降至 10/min，risk_quota 降至 30 | **默认**，试用 + 逐步接入 | **默认** |
> | `default_deny` | 阻止所有工具调用，仅返回 `license_required` 错误 | 需要 License 的生产环境 | 生产部署应设置为该值 |
> | `audit_only` | 仅审计，不阻断（原始设计，已弃用——需要显式启用） | 仅内部调试，**禁止用于生产** | 需要显式配置 |
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
> **安全保障**：无论回退模式如何，以下安全检查**始终生效**，不受回退策略影响：
> - DLP 编辑（输入 + 输出）
> - JSON Schema 参数校验（如 ToolPolicy 存在）
> - 审计上报（sample_rate=1.0）
> - session_risk_score 累积（达到 risk_quota 后阻断）

**tools/list 过滤**：代理将 `tools/list` 转发到上游，然后按 License `allowed_tools` 过滤响应，并注入结构化的 `annotations`（不修改 `description` 文本）：

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

> **设计决策：不在 description 中注入约束文本**
>
> 早期设计将安全约束（如"只允许连接到 api.internal:443"）拼接到每个工具的 `description` 字段中。这导致：
> 1. **Token 膨胀**：N 个工具 × 每个工具约 50 token 的约束文本 = 500+ token 的额外开销，工具多时可能超出 LLM 上下文窗口
> 2. **冗余重复**：公共约束（如"不得绕过安全控制"）在每个工具描述中重复出现
> 3. **维护困难**：约束变更需要修改所有工具的 description
>
> **新方案**：约束通过两层传递 —
> - **结构化注解**：注入到 `tools/list` 响应的 `annotations` 字段（MCP 标准兼容），由 MCP 客户端的 UI 和本地预检逻辑消费，**不进入 LLM 提示词**
> - **集中式系统提示词注入**：所有工具约束由 Prompt Gateway（[§2.8](ARCHITECTURE.md#28-prompt-gateway-prompt-enhancement)）渲染到系统提示词的 "### Tool Usage Rules" 部分，**仅出现一次**而非每个工具重复

**错误响应格式**：

遵循 JSON-RPC 2.0 规范，使用保留的 `-32000` ~ `-32099` 范围（实现定义的服务器错误）来定义 VirbiusAgent 特定错误码。传输层无关（stdio / SSE / WebSocket 均适用）：

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

> `http_analog` 仅用于运营仪表盘前端的参考展示，不属于协议逻辑的一部分。在 stdio / WebSocket 传输下无 HTTP 语义。

**VirbiusAgent JSON-RPC 错误码定义**（-32000 ~ -32099）：

| 编码 | message | 描述 | http_analog |
|------|---------|------|-------------|
| -32001 | `license_invalid` | License 过期/吊销/签名错误 | 401 |
| -32002 | `license_required` | 无 License 且 fallback=default_deny | 401 |
| -32003 | `high_risk_without_license` | 无 License 且 fallback=minimum_privilege，工具为高风险 | 403 |
| -32004 | `not_in_allowlist` | 工具不在 License allowed_tools 中 | 403 |
| -32005 | `schema_violation` | 参数不符合 JSON Schema | 400 |
| -32006 | `engine_blocked` | 云端 Groovy L3 最终裁决拒绝 | 403 |
| -32007 | `rate_exceeded` | 工具调用频率超过限制 | 429 |
| -32008 | `risk_threshold` | session_risk_score 超过 License risk_quota | 403 |
| -32009 | `output_review_blocked` | 输出审查拦截（P1） | 403 |
| -32010 | `fallback_blocked` | 回退策略通用拦截 | 403 |
| -32011 | `challenge_required` | 高风险操作需要人工批准（挑战流程） | 403 |

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

### 挑战流程（-32011 `challenge_required`）

当引擎返回 `effective_action = "challenge"` 时，表示该工具调用需要人工批准后才能执行。挑战支持两种路径：**内联**（即时确认）和**仪表盘**（异步运营仪表盘审批）。

#### 流程顺序

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

#### 错误响应格式（-32011）

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

#### 重试格式（带挑战令牌）

获得批准后，Agent 重试原始的 `tools/call` 请求，在 `_meta` 中包含 `challenge_token`：

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

当代理/网关收到带有 `challenge_token` 的请求：
1. 调用引擎 `POST /v1/challenge/verify` 验证令牌（一次性使用）
2. 如果有效，转发到上游 MCP 服务器（移除 `_meta.challenge_token`）
3. 如果验证失败，返回 `-32011` 错误

> **网关（WASM）路径**：令牌通过 HTTP 头 `X-Virbius-Challenge-Token` 传递，而非 `_meta`。

#### 引擎挑战 API

| 方法 | 路径 | 描述 |
|--------|------|------|
| `GET` | `/v1/challenge/{id}/status` | 查询挑战状态（pending/approved/rejected/expired） |
| `POST` | `/v1/challenge/{id}/approve` | 批准，生成一次性令牌（仪表盘调用） |
| `POST` | `/v1/challenge/{id}/reject` | 拒绝批准（仪表盘调用） |
| `POST` | `/v1/challenge/verify` | 验证令牌有效性（代理/网关调用） |
| `GET` | `/v1/challenges?tenant_id=default&status=pending` | 列出待处理的挑战（仪表盘队列） |

#### 路径选择配置

挑战路径（内联 vs 仪表盘）由以下配置决定：

1. **ToolPolicy**（`virbius-core/src/manifest.rs`）：每个工具可以声明 `challenge_method`（`inline` | `dashboard` | `auto`）
2. **规则 body_json**：规则可以覆盖 `challenge_method`
3. **Agent `initialize._meta`**：Agent 可以声明 `challenge_methods: ["inline", "dashboard"]` 来表示支持的确认方式
4. **引擎配置**（`application.yml`）：`virbius.challenge.ttl-seconds` 和 `virbius.challenge.token-ttl-seconds`

默认 `auto`：引擎根据 Agent 声明的能力选择路径。如果 Agent 支持 `inline`，优先使用内联；否则使用仪表盘。

#### 安全保障

- **一次性令牌**：验证后立即标记为 `used`，第二次使用返回 `valid=false`
- **参数绑定**：令牌绑定到原始请求的 `tool_name` + `args_hash`，不能跨请求重用
- **TTL 限制**：挑战记录默认 300s 后过期，令牌默认 600s 后过期
- **审计追溯**：所有挑战操作（创建/批准/拒绝/验证）均记录在审计日志中

**配置**：

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

**部署模式**：

| 模式 | 适用场景 | 部署方式 | 流量拓扑 |
|------|---------|---------|---------|
| **Sidecar** | K8s Pod 内部，Agent 和代理在同一个 Pod 中 | Agent 容器 + 代理容器，共享 localhost | 东西向（MCP 调用不经过网关） |
| **本地进程** | 开发环境 / 单机部署 | Agent 进程启动代理子进程（stdio） | 东西向 |
| **独立服务** | 多个 Agent 共享一个代理 | 代理独立部署，Agent 通过 SSE 连接 | 南北向（通过网关 Ingress） |

> **Sidecar 模式下的出口流量控制**
>
> 在 Sidecar 模式下，Agent 的 MCP 工具调用通过 localhost 直接到达代理（东西向），不经过网关。然而，Agent 通过 `curl` 等工具发起的 HTTP 外部请求（Egress）不经过 MCP 协议，需要额外控制。
>
> **关键设计决策：工具级控制而非进程级网络断开**
>
> 代理代理仅适用于 MCP 协议中显式定义的业务工具（`curl`/`web_search`/`http_request` 等）。Agent 框架层面的隐式网络请求（LangChain 配置获取、SDK 模型下载、心跳检测、遥测上报等）**不由代理代理**，由 Agent 自身发起，通过 K8s NetworkPolicy 限制到最小白名单目标。
>
> 此决策基于以下考量：
> 1. **向后兼容**：LangChain、AutoGen、OpenAI SDK 等框架会隐式发起网络请求（拉取配置、下载模型、心跳检测）。直接网络断开会使大量现有 Agent 无法运行
> 2. **全代理成本**：全代理（代理所有 Agent 网络流量）需要支持 WebSocket 双工、大文件分块上传、复杂 Header 透传、HTTP/2 多路复用等所有 HTTP 语义，开发成本极高；仅代理 MCP 业务工具则大幅降低复杂度。工具级代理只需支持 GET/POST + 流式响应透传（chunked/SSE），用 reqwest `bytes_stream()` 即可实现
> 3. **威胁模型匹配**：安全威胁来自于 Agent 通过业务工具发起的**可控外部请求**（curl/execute_python/shell），而非框架级别的**固定目标**网络调用。前者需要安全流水线校验，后者仅需 NetworkPolicy 限制目标
>
> | 流量类型 | 来源 | P0 控制方式 | 描述 |
> |---------|------|-----------|------|
> | **业务工具请求** | MCP `tools/call` 中的显式工具如 `curl`/`web_search` | 代理代理 + URL 白名单 | Agent 将 `tools/call` 传递给代理执行，代理校验 URL 白名单后发起 HTTP 请求 |
> | **框架隐式请求** | Agent 框架层面（配置获取、模型下载、心跳、遥测） | K8s NetworkPolicy 限制目标 | Agent 自身发起，NetworkPolicy 仅允许白名单目标（如 `*.openai.com`、`registry.internal`） |
> | **进程级全出站** | 所有 TCP 连接 | P2：eBPF/iptables 透明拦截 | 进程级回退，捕获绕过 MCP 协议的直接 TCP 连接 |
>
> **P0 方案 — 工具级代理转发**：
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
> MCP 协议中显式定义的业务工具调用（`curl`/`web_search`/`http_request` 等）通过 `tools/call` 交给代理转发。代理在应用层校验 URL 白名单，决定放行或拦截。此方案无需内核级网络拦截，可在 P0 实现。Agent 框架层面的隐式网络请求不由代理代理，通过 NetworkPolicy 限制到最小白名单目标。
>
> **NetworkPolicy 配置示例**（限制 Agent 容器隐式出站）：
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
> **代理代理的 HTTP 能力边界**：
>
> 由于代理仅限于 MCP 业务工具，代理的 HTTP 客户端能力可以按需实现，无需覆盖所有 HTTP 语义：
>
> | 能力 | P0 支持 | P1 增强 | 描述 |
> |------|---------|---------|------|
> | GET/POST | ✅ | — | 基本 HTTP 方法 |
> | 自定义 Header | ✅（白名单透传） | — | 仅透传安全 Header，过滤 `Authorization`（由 License 注入） |
> | 流式响应透传（chunked/SSE） | ✅ | — | reqwest `bytes_stream()` 流式读取，避免大响应 OOM；逐条透传 SSE 事件 |
> | 大文件下载 | ✅（流式，50MB 限制） | ✅（分块写入临时文件） | P0 流式读取 + 内存限制保护，超限返回 413 |
> | 超时控制 | ✅（30s） | — | 超时返回 504 |
> | 重定向跟随 | ✅（最多 5 跳） | — | 防止通过重定向进行 SSRF |
> | HTTPS | ✅ | — | 代理发起 TLS，Agent 不接触证书 |

Sidecar 部署（K8s）— 单上游：

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

Sidecar 部署（K8s）— 多上游：

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

> **多上游配置说明**：配置 `VIRBIUS_UPSTREAMS` 后，`VIRBIUS_UPSTREAM_URL` 将被忽略。
> 每个上游必须有唯一的 `name`，用于工具名冲突时的前缀（如 `filesystem__read_file`）。
> 详细的多上游方案请参见 [§2.6.2](#262-multi-upstream)。

本地进程部署（开发）：

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

**实现结构**：

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

**核心依赖**：

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

**安全流水线核心实现**：

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

#### 2.6.2 多上游

代理支持同时连接到多个 MCP 服务器，通过工具名称将 `tools/call` 请求路由到正确的上游。单上游模式是多上游的特例（`len() == 1`），所有旧配置自动归一化为单条目的多上游。

**设计原则**：
- **向后兼容**：旧的 `upstream_url` / `VIRBIUS_UPSTREAM_URL` 配置自动归一化为 `upstreams = [{ name: "default", ... }]`
- **仅在冲突时加前缀**：不冲突的工具名称保持不变，仅在多个上游有同名工具时添加前缀 `{upstream_name}__{tool_name}`
- **安全流水线使用原始名称**：License `allowed_tools` 匹配原始工具名称（去除前缀后），不需要 Agent 感知前缀
- **转发时恢复原始名称**：代理在向上游转发 `tools/call` 时恢复原始工具名称，上游 MCP 服务器无感知

**架构**：

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

**工具名冲突解决**：

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

> **对 Agent 透明**：Agent 通过 `tools/list` 获取带前缀的工具名称，使用带前缀的名称调用即可。代理自动处理前缀剥离和上游路由。如果 Agent 直接调用不带前缀的工具名称且该名称没有冲突，代理也能正确路由。

**配置归一化**：

| 配置 | 等价的多上游配置 |
|---------|-------------|
| `upstream_url = "http://mcp:8080"` | `upstreams = [{ name: "default", url: "http://mcp:8080", sse_path: "/sse" }]` |
| `VIRBIUS_UPSTREAM_URL=http://mcp:8080` | 同上 |
| `upstreams = [{ name = "fs", ... }]` | 直接使用（upstream_url 被忽略） |
| `VIRBIUS_UPSTREAMS='[{"name":"fs",...}]'` | 直接使用 |

**多上游的环境变量配置**：

```bash
# JSON array format
export VIRBIUS_UPSTREAMS='[
  {"name":"filesystem","url":"http://fs-mcp:8081","sse_path":"/sse"},
  {"name":"github","url":"http://gh-mcp:8082","sse_path":"/sse"}
]'
```

**会话与连接管理**：

在多上游模式下，每个（session_id, upstream_name）对维护独立的 SSE 连接：

```rust
// connections index: (session_id, upstream_name) -> UpstreamClient
connections: DashMap<(String, String), UpstreamClient>

// Session TTL cleanup: remove(session_id) clears connections across all upstreams for that session
// Disconnect cleanup: cleanup_disconnected() scans all connections, removes disconnected SSE connections
```

**initialize 响应注入**：

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

#### 2.6.3 决策追踪 Trace Collector

MCP 代理内置 `TraceCollector` 模块，在 `tools/call` 请求生命周期的两个关键点收集追踪事件，异步写入 Redis Stream `virbius:trace`，由控制端 `TraceIngestService` 消费以实现持久化。

**收集点**：

| 收集点 | event_type | 时机 | 记录内容 |
|--------|-----------|------|---------|
| `tool_call` | `tool_call` | 安全流水线校验通过后，转发到上游前 | tool_name, arguments, step_id, parent_step_id |
| `tool_result` | `tool_result` | 上游返回后，响应 Agent 前 | tool_name, result, is_error, duration_ms |

**步骤追踪**：

每个会话维护一个 `step_seq`（递增序列号）和 `last_step_id`（上一步 ID）。新步骤的 `parent_step_id` 自动设置为 `last_step_id`，形成因果链：

```
step-001 (tool_call: read_file)
  └── step-002 (tool_result: read_file)
       └── step-003 (tool_call: write_file)
            └── step-004 (tool_result: write_file)
```

**TraceEvent 格式**：

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

**配置**（`config.toml`）：

```toml
[trace]
enabled = true          # Default true
redis_url = "redis://127.0.0.1:6379"
stream_key = "virbius:trace"
max_fields_len = 32768  # Max field length (bytes), truncated if exceeded
```

**Redis Stream 写入**：

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

**控制端消费**：

`TraceIngestService` 使用 `XREADGROUP` 消费 `virbius:trace`，通过 `JdbcTemplate` 幂等地写入 `tb_agent_trace` 表，检查点（最后投递 ID）持久化在 `tb_trace_ingest_checkpoint` 中。

**REST API**：

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/v1/admin/tenants/{tenantId}/trace/session/{sessionId}/timeline` | 会话时间线（按 step_seq 排序） |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/trace/{traceId}` | 追踪因果链（parent_step_id 递归） |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/search?toolName=&sessionId=&limit=` | 搜索 |
| GET | `/api/v1/admin/tenants/{tenantId}/trace/ingest/status` | 摄入健康状态（pending/lag） |
