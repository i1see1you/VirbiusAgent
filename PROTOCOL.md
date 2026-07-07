# VirbiusAgent 协议设计 — PROTOCOL

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.2 |
| 状态 | 草案 |
| 关联 | [DESIGN.md](DESIGN.md)（索引） · [ARCHITECTURE.md](ARCHITECTURE.md) |
| 参考项目 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

> 本文件包含 §2.6 MCP Server 集成（含 §2.6.1 MCP Proxy 完整技术方案）。
> 架构总体设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。

---

### 2.6 MCP Server 集成

端层 virbius-core 需要集成到 MCP Server(Python/Node)中，包裹工具执行：

| 框架 | 集成方式 | 拦截点 |
|------|---------|--------|
| **Python MCP Server** | virbius-core 编译为 PyO3 扩展，在 tool handler 内调 virbius_core.precheck() + sandbox_execute() | tool handler 内 |
| **Node MCP Server** | virbius-core 编译为 napi-rs 扩展，同理 | tool handler 内 |
| **通用 subprocess** | MCP Server 启动工具时 spawn("virbius-sandbox", ...) | 进程启动前 |
| **LangChain** | SandboxedTool<T> wrapper，包装 Tool::call() | Tool::call() 内 |
| **OpenAI SDK** | SandboxedOpenAIClient 代理，拦截 tool_calls | 请求序列化前 |
| **通用 MCP proxy** | 端层启动本地 MCP Server 作为 Agent 和真实工具的中间代理 | tools/call 请求 |

通用 MCP proxy 模式：

```
Agent <-> 本地 MCP Proxy (virbius-core sandbox)
              |
              +-- allow -> 转发到远端 MCP Server
              +-- deny  -> 返回 ToolError
```

#### 2.6.1 MCP Proxy 完整技术方案

MCP Proxy 是 Agent 框架无关的接入方式：作为独立进程运行在 Agent 和 MCP Server 之间，拦截 MCP 协议的 `tools/call` 请求，执行安全预检 + 云层终判后决定放行或阻断。任何支持 MCP 协议的 Agent 框架（Claude Desktop、LangChain MCP Adapter、自定义 Agent）均可零代码接入。

**设计目标**：
- Agent 框架零改造（MCP 协议透明代理）
- 单进程支撑多 Agent 会话（stdio 多路复用 / SSE 长连接）
- 预检延迟 <2ms，全链路（含 engine）延迟 <50ms
- Engine 不可用时 fail-open（低风险）或 fail-closed（高风险）

**架构**：

```
Agent (任意 MCP Client)
  |
  | MCP 协议 (JSON-RPC 2.0)
  |   - stdio (本地进程)
  |   - SSE  (远程连接)
  v
+---------------------------------------------------+
|  virbius-mcp-proxy                                |
|                                                   |
|  +-----------+    +--------------------+          |
|  | MCP 传输层 |    |  会话管理器         |          |
|  | (stdio/SSE)|--->|  session_id -> ctx |          |
|  +-----+-----+    +--------------------+          |
|        |                                          |
|  +-----v----------------------------+             |
|  | JSON-RPC 路由                    |             |
|  |  initialize  -> 透传 + 注入      |             |
|  |  tools/list  -> 透传 + 过滤      |             |
|  |  tools/call  -> 拦截 -> 预检     |             |
|  |  *            -> 透传            |             |
|  +-----+----------------------------+             |
|        |                                          |
|  +-----v----------------------------+             |
|  | 安全管线                         |             |
|  |  1. License 校验 (virbius-core)  |             |
|  |  2. 端层预检 (allowlist+schema)  |             |
|  |  3. 快速通道判断                  |             |
|  |     +- 命中 -> allow (跳过engine) |             |
|  |     +- 未命中 -> 调 engine 终判   |             |
|  |  4. 输出审查 (可选, P1)          |             |
|  +-----+----------------------------+             |
|        |                                          |
|  +-----v----------+  +------------------+        |
|  | 上游 MCP Client |  | Engine Client     |        |
|  | (转发到真实     |  | (POST /v1/evaluate|        |
|  |  MCP Server)    |  |  HTTP)        |        |
|  +-----------------+  +------------------+        |
+---------------------------------------------------+
  |                    |
  v                    v
真实 MCP Server     virbius-engine (:8082)
(GitHub/Slack/...)  virbius-control (:8080)
```

**MCP 协议处理**：

MCP 使用 JSON-RPC 2.0，传输层支持 stdio 和 SSE。Proxy 对每个 JSON-RPC method 的处理策略：

| Method | 处理策略 | 说明 |
|--------|---------|------|
| `initialize` | 透传 + 注入 | 转发到上游 MCP Server，响应中注入 Proxy 能力声明 |
| `tools/list` | 透传 + 过滤 | 转发获取工具列表，按 License allowed_tools 过滤后返回 Agent |
| `tools/call` | **拦截** | 执行完整安全管线，allow 则转发，deny 则返回错误 |
| `resources/*` | 透传 | 不拦截 |
| `prompts/*` | 透传 | 不拦截 |
| `notifications/*` | 透传 | 单向通知，不拦截 |

`tools/call` 拦截流程（核心）：

```
Agent 发送 tools/call { name, arguments }
  |
  v
1. 解析请求
   - tool_name = request.params.name
   - args = request.params.arguments
   - session_id = 从 MCP 会话或 _meta 中提取
  |
  v
2. License 校验 (virbius-core::license)
   - verify(jwt, pubkey, app_id)
   - 有 License 且有效 -> 继续 step 3
   - 无 License -> 应用 Fallback 策略 (§2.6.1 Fallback)
     - minimum_privilege: 高风险工具 deny / 低风险工具 allow(限流)
     - default_deny: deny (license_required)
     - audit_only: allow + 审计 (仅调试)
   - License 无效(过期/吊销/签名错) -> 返回 JSON-RPC error: license_invalid
  |
  v
3. 端层预检 (virbius-core::precheck)
   - License allowed_tools 是否包含 tool_name
   - ToolPolicy JSON Schema 校验 args
   - 失败 -> 返回 JSON-RPC error: tool_blocked / schema_violation
  |
  v
4. 快速通道判断
   - sandbox_type=="none" && fast_path && session_risk<30 && tool in fast_allowlist
   - 命中 -> 跳过 step 5，直接 allow
   - 冷启动：前 N 次调用强制全链路
  |
  v
5. 云层终判 (virbius-engine POST /v1/evaluate)
   请求体:
   {
     "trace_id": "...",
     "session_id": "...",
     "app_id": "...",
     "tool_name": "read_file",
     "args": { "path": "/etc/passwd" },
     "license_risk_quota": 60
   }
   响应:
   {
     "effective_action": "allow" | "block" | "review",
     "rule_id": "agent-tool-chain-detect",
     "reason": "dangerous chain",
     "risk_score_delta": 20,
     "session_risk_score": 45
   }
   - block -> 返回 JSON-RPC error: engine_blocked
   - allow/review -> 继续
  |
  v
6. 转发到上游 MCP Server
   - JSON-RPC 透传 tools/call 请求
   - 超时 30s（可配置）
  |
  v
7. 输出审查（可选，P1）
   - STI Taint 检查工具返回值
   - PII 脱敏
  |
  v
8. 返回结果给 Agent
```

**会话管理**：

MCP stdio 模式下每个 Agent 进程独立连接，无显式 session_id。Proxy 通过连接 + 初始化参数推导 session：

```rust
// virbius-mcp-proxy/src/session.rs

pub struct SessionManager {
    sessions: DashMap<ConnectionId, Session>,
}

pub struct Session {
    pub session_id: String,           // UUID 或 Agent 传入
    pub app_id: String,               // from initialize params
    pub tenant_id: String,
    pub license_jwt: String,          // from initialize params._meta
    pub trace_id: String,             // per-request 或 per-session
    pub tool_call_count: u32,         // 冷启动 warmup 计数
    pub upstream_initialized: bool,   // 上游 MCP Server 是否已 init
}
```

Agent 在 `initialize` 请求的 `_meta` 字段中传入身份信息：

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
      "tenant_id": "公司A",
      "license_jwt": "eyJ...",
      "session_id": "sess_abc"
    }
  }
}
```

> **无 License 接入（Fallback 策略）**：若 `_meta` 中无 `license_jwt`，Proxy **不以 audit-only 模式放行**（否则攻击者可通过故意不传 License 绕过所有阻断）。而是应用 Fallback 默认策略，按配置选择安全姿态：
>
> | Fallback 模式 | 行为 | 适用场景 | 配置默认值 |
> |--------------|------|---------|-----------|
> | `minimum_privilege` | 仅允许低风险只读工具（search/calculator/format），阻断高风险工具（shell/execute_python/read_file/curl），DLP + schema 校验仍然生效，rate_limit 降至 10/min，risk_quota 降至 30 | **默认值**，试用 + 渐进接入 | **默认** |
> | `default_deny` | 阻断所有工具调用，仅返回 `license_required` 错误 | 生产环境强制要求 License | 生产部署应设为此值 |
> | `audit_only` | 只审计不阻断（原设计，已废弃为需显式开启） | 仅限内网调试，**禁止生产使用** | 需显式配置 |
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
> **安全保证**：无论 Fallback 模式如何，以下安全检查**始终生效**，不受 Fallback 策略影响：
> - DLP 脱敏（输入 + 输出）
> - JSON Schema 参数校验（若 ToolPolicy 存在）
> - 审计上报（sample_rate=1.0）
> - session_risk_score 累积（达到 risk_quota 后阻断）

**tools/list 过滤**：Proxy 转发 `tools/list` 到上游后，按 License `allowed_tools` 过滤响应，并注入结构化 `annotations`（不修改 `description` 文本）：

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
> 早期设计将安全约束（如"仅允许连接 api.internal:443"）拼接到每个工具的 `description` 字段中。这导致：
> 1. **Token 膨胀**：N 个工具 × 每工具 ~50 token 约束文本 = 500+ token 额外消耗，工具数量多时可能超出 LLM 上下文窗口
> 2. **冗余重复**：通用约束（如"不得绕过安全控制"）在每个工具描述中重复
> 3. **维护困难**：约束变更需修改所有工具的 description
>
> **新方案**：约束分两层交付——
> - **结构化 annotations**：注入到 `tools/list` 响应的 `annotations` 字段（MCP 标准兼容），供 MCP 客户端 UI 展示和本地预检逻辑消费，**不进入 LLM prompt**
> - **系统提示词集中注入**：所有工具约束由 Prompt Gateway（[§2.8](ARCHITECTURE.md)）渲染到系统提示词的"### 工具使用规则"段落，**只出现一次**而非每个工具重复

**错误响应格式**：

遵循 JSON-RPC 2.0 规范，使用保留的 `-32000` ~ `-32099` 区间（implementation-defined server errors）定义 VirbiusAgent 专属错误码。传输层无关（stdio / SSE / WebSocket 均适用）：

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

> `http_analog` 仅供运营台前端展示参考，不参与协议逻辑。stdio / WebSocket 传输下无 HTTP 语义。

**VirbiusAgent JSON-RPC 错误码定义**（-32000 ~ -32099）：

| Code | message | 说明 | http_analog |
|------|---------|------|-------------|
| -32001 | `license_invalid` | License 过期/吊销/签名无效 | 401 |
| -32002 | `license_required` | 无 License 且 fallback=default_deny | 401 |
| -32003 | `high_risk_without_license` | 无 License 且 fallback=minimum_privilege，工具为高风险 | 403 |
| -32004 | `not_in_allowlist` | 工具不在 License allowed_tools 中 | 403 |
| -32005 | `schema_violation` | 参数不符合 JSON Schema | 400 |
| -32006 | `engine_blocked` | 云层 Groovy L3 终判 deny | 403 |
| -32007 | `rate_exceeded` | 工具调用频率超限 | 429 |
| -32008 | `risk_threshold` | session_risk_score 超过 License risk_quota | 403 |
| -32009 | `output_review_blocked` | 输出审查阻断(P1) | 403 |
| -32010 | `fallback_blocked` | Fallback 策略通用阻断 | 403 |

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

**配置**：

```toml
# virbius-mcp-proxy.toml

[proxy]
listen = "stdio"                    # stdio | tcp://0.0.0.0:9090
upstream_url = "http://mcp-server:8080"  # 真实 MCP Server 地址
upstream_transport = "sse"          # stdio | sse

[security]
control_base_url = "http://virbius-control:8080"
engine_url = "http://virbius-engine:8082"
license_public_key = "/etc/virbius/ed25519-pub.pem"
fallback_policy = "minimum_privilege"  # 无 License 时的策略: minimum_privilege | default_deny | audit_only

[security.fast_path]
enabled = true
warmup_calls = 5                    # 前 5 次调用强制全链路
risk_threshold = 30

[security.failover]
high_risk_fail_closed = true        # 高风险工具 engine 不可用时 deny
low_risk_fail_open = true           # 低风险工具 engine 不可用时 allow + 审计
engine_timeout_ms = 3000

[audit]
redis_url = "redis://127.0.0.1:6379"
sample_rate = 1.0                   # 审计采样率
```

**部署模式**：

| 模式 | 适用场景 | 部署方式 | 流量拓扑 |
|------|---------|---------|---------|
| **Sidecar** | K8s Pod 内，Agent 与 Proxy 同 Pod | Agent 容器 + Proxy 容器，共享 localhost | 东西向（MCP 调用不经管层） |
| **本地进程** | 开发环境 / 单机部署 | Agent 进程 spawn Proxy 子进程 (stdio) | 东西向 |
| **独立服务** | 多 Agent 共享一个 Proxy | Proxy 独立部署，Agent 通过 SSE 连接 | 南北向（经管层 Ingress） |

> **Sidecar 模式下的 Egress 流量管控**
>
> Sidecar 模式中，Agent 的 MCP 工具调用走 localhost 直达 Proxy（东西向），不经过管层。但 Agent 通过 `curl` 等工具发起的外部 HTTP 请求（Egress）不经过 MCP 协议，需要额外管控。
>
> **关键设计决策：工具级管控而非进程级断网**
>
> Proxy 代发仅针对 MCP 协议中显式定义的业务工具（`curl`/`web_search`/`http_request` 等）。Agent 框架底层的隐式网络请求（LangChain 配置拉取、SDK 模型下载、心跳检测、遥测上报等）**不由 Proxy 代发**，而是由 Agent 自身发起，受 K8s NetworkPolicy 限制到所需的最小白名单目标。
>
> 这一决策基于以下考量：
> 1. **存量兼容性**：LangChain、AutoGen、OpenAI SDK 等框架会隐式发起网络请求（拉取配置、下载模型、心跳检测），直接断网会导致大量存量 Agent 无法运行
> 2. **全量代发成本**：全量代发（代理 Agent 所有网络流量）需支持 WebSocket 双工、大文件分块上传、复杂 Header 透传、HTTP/2 多路复用等全部 HTTP 语义，开发成本极高；仅代发 MCP 业务工具可大幅降低复杂度。工具级代发只需支持 GET/POST + 流式响应透传（chunked/SSE），reqwest `bytes_stream()` 即可实现
> 3. **威胁模型匹配**：安全威胁来自 Agent 通过业务工具（curl/execute_python/shell）发起的**可控外部请求**，而非框架底层的**固定目标**网络调用。前者需要安全管线校验，后者通过 NetworkPolicy 限制目标即可
>
> | 流量类型 | 来源 | P0 管控方式 | 说明 |
> |---------|------|-----------|------|
> | **业务工具请求** | MCP `tools/call` 中的 `curl`/`web_search` 等显式工具 | Proxy 代发 + URL 白名单 | Agent 通过 `tools/call` 交给 Proxy 执行，Proxy 校验 URL 白名单后发起 HTTP 请求 |
> | **框架隐式请求** | Agent 框架底层（配置拉取、模型下载、心跳、遥测） | K8s NetworkPolicy 限制目标 | Agent 自身发起，NetworkPolicy 限制仅允许白名单目标（如 `*.openai.com`、`registry.internal`） |
> | **进程级全量出站** | 所有 TCP 连接 | P2: eBPF/iptables 透明劫持 | 进程级兜底，捕获绕过 MCP 协议的直接 TCP 连接 |
>
> **P0 方案——工具级 Proxy 代发**：
>
> ```
> Agent ──tools/call(curl, url)──> MCP Proxy
>                                     |
>                                     +-- URL 白名单校验（License allowed_hosts）
>                                     +-- 预检通过 -> Proxy 发起 HTTP 请求 -> 外部 API
>                                     +-- 预检失败 -> deny
>                                     |
>                                     v
>                                  外部 API
>
> Agent ──隐式 HTTP（SDK/框架底层）──> 外部目标（受 NetworkPolicy 限制）
>                                     |
>                                     +-- NetworkPolicy 仅放行白名单目标
>                                     +-- 非白名单目标 -> 被 K8s CNI 静默 drop
> ```
>
> MCP 协议中显式定义的业务工具调用（`curl`/`web_search`/`http_request` 等）通过 `tools/call` 交给 Proxy 代发。Proxy 在应用层校验 URL 白名单后决定放行或阻断。此方案无需内核级网络劫持，P0 即可实现。Agent 框架底层的隐式网络请求不由 Proxy 代发，受 NetworkPolicy 限制到最小白名单目标。
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
>   # 允许访问同 Pod 的 Proxy（localhost:9090）
>   - to:
>     - podSelector:
>         matchLabels:
>           app: virbius-mcp-proxy
>   # 允许访问 LLM API（如 OpenAI/Anthropic）
>   - to:
>     - namespaceSelector: {}
>     ports:
>     - protocol: TCP
>       port: 443
>     # 实际中用 IPBlock CIDR 限制具体 LLM API 端点
>   # 允许访问内部镜像/模型仓库
>   - to:
>     - podSelector:
>         matchLabels:
>           app: model-registry
>   # 允许 DNS 解析
>   - to:
>     - namespaceSelector:
>         matchLabels:
>           kubernetes.io/metadata.name: kube-system
>     ports:
>     - protocol: UDP
>       port: 53
> ```
>
> **Proxy 代发的 HTTP 能力边界**：
>
> 由于代发仅限于 MCP 业务工具，Proxy 的 HTTP 客户端能力可按需实现，无需覆盖全部 HTTP 语义：
>
> | 能力 | P0 支持 | P1 增强 | 说明 |
> |------|---------|---------|------|
> | GET/POST | ✅ | — | 基础 HTTP 方法 |
> | 自定义 Header | ✅（白名单透传） | — | 仅透传安全 Header，过滤 `Authorization`（由 License 注入） |
> | 流式响应透传（chunked/SSE） | ✅ | — | reqwest `bytes_stream()` 流式读取，避免大响应 OOM；SSE 事件逐条透传 |
> | 大文件下载 | ✅（流式，上限 50MB） | ✅（分块写入临时文件） | P0 流式读取 + 内存上限保护，超限返回 413 |
> | 超时控制 | ✅（30s） | — | 超时返回 504 |
> | 重定向跟随 | ✅（最多 5 跳） | — | 防止 SSRF via redirect |
> | HTTPS | ✅ | — | Proxy 发起 TLS，Agent 不接触证书 |

Sidecar 部署（K8s）：

```yaml
spec:
  containers:
  - name: agent
    image: my-agent:latest
    env:
    - name: MCP_SERVER_URL
      value: "http://localhost:9090"  # 指向同 Pod 的 Proxy
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

本地进程部署（开发）：

```bash
# 启动 Proxy (stdio 模式)
export VIRBIUS_UPSTREAM_URL=http://localhost:8080
export VIRBIUS_CONTROL_URL=http://localhost:8080
export VIRBIUS_ENGINE_URL=http://localhost:8082
virbius-mcp-proxy --transport stdio

# Agent 配置 MCP Server 为 Proxy
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
│   ├── main.rs              # 入口 + CLI 参数解析
│   ├── transport/
│   │   ├── mod.rs           # 传输层 trait
│   │   ├── stdio.rs         # stdio 传输（行分隔 JSON-RPC）
│   │   └── sse.rs           # SSE 传输（HTTP + Server-Sent Events）
│   ├── router.rs            # JSON-RPC method 路由
│   ├── session.rs           # 会话管理（ConnectionId -> Session）
│   ├── pipeline.rs          # 安全管线（License -> precheck -> engine -> audit）
│   ├── upstream.rs          # 上游 MCP Client（转发请求）
│   ├── audit.rs             # 审计事件上报（Redis Stream）
│   └── config.rs            # 配置加载（TOML + 环境变量）
├── examples/
│   └── demo_agent.rs        # 模拟 Agent 调用演示
└── tests/
    └── integration_test.rs  # 端到端集成测试
```

**核心依赖**：

```toml
[dependencies]
virbius-core = { path = "../virbius-core" }
tokio = { version = "1", features = ["full"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
uuid = { version = "1", features = ["v4"] }
dashmap = "6"                        # 并发会话表
reqwest = "0.12"                     # HTTP client (调 engine + 上游 SSE)
toml = "0.8"                         # 配置解析
tracing = "0.1"                      # 结构化日志
tokio-util = "0.7"                   # codec (行分隔 JSON)
```

**安全管线核心实现**：

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
        // 1. License 校验
        let license = License::verify(
            &session.license_jwt, &self.license_pubkey, &session.app_id
        ).map_err(|e| PipelineResult::deny("license_invalid", e))?;

        // 2. 端层预检
        let call = ToolCall { tool_name: tool_name.into(), args: args.clone(), .. };
        let pre = precheck::precheck(&license, &call);
        if !pre.allowed {
            return Ok(PipelineResult::deny("not_in_allowlist", pre.reason));
        }

        // 3. 快速通道
        if self.is_fast_path(session, &pre, tool_name) {
            self.audit(Allow, session, tool_name, "fast_path").await;
            return Ok(PipelineResult::allow("fast_path"));
        }

        // 4. 云层终判
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

