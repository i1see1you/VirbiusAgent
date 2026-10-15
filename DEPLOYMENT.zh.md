# VirbiusAgent 部署视图 — DEPLOYMENT

[English](DEPLOYMENT.md)

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.6 |
| 状态 | 草案 |
| 关联 | [DESIGN.zh.md](DESIGN.zh.md)（索引） · [ARCHITECTURE.zh.md](ARCHITECTURE.zh.md) |

> 本文件包含 §8 部署视图（组件端口 + 部署拓扑 + 接入方式对比 + 四层全覆盖组合部署）。

---

## 8. 部署视图

### 8.1 组件端口

| 组件 | 端口 | 部署位置 | 流量方向 |
|------|------|---------|---------|
| **Agent 应用** | 动态 | 用户侧 / Serverless 容器 | — |
| **MCP Proxy** (端层 Sidecar) | localhost:9090 | 与 Agent 同 Pod / 同主机 | 东西向 |
| **virbius-core** (端层嵌入) | 嵌入 MCP Server 进程 | 与 MCP Server 同进程 | 东西向 |
| **Higress** (管层 Ingress) | 80/443 | 独立部署 / K8s | 南北向（入站） |
| **Higress** (管层 Egress) | 8081 | 独立部署 / K8s | 南北向（出站） |
| **MCP Server** (Python/Node) | 8080+ | 独立部署 / K8s | — |

> **多上游支持**：MCP Proxy 支持同时连接多个 MCP Server（P1 新增），通过工具名路由 `tools/call`。
> 单上游模式向下兼容（旧 `upstream_url` 配置自动归一化），多上游时冲突工具名自动加前缀 `{upstream}__{tool}`。
> 详细方案见 [PROTOCOL.md §2.6.2](PROTOCOL.md#262-multi-upstream)（英文）。
| **virbius-engine** | 8082 | 云侧 | — |
| **virbius-control** | 8080 | 云侧 | — |
| **Falco** (核层观测) | 无（DaemonSet） | Agent 所在宿主机 | 旁路 |
| **virbius-kernel-daemon** | 9090 | Agent 所在宿主机 | 旁路 |
| **Redis** | 6379 | 云侧 | — |
| **Database** | — | 云侧 | — |

> **删除原设计的 AgentGateway (9080)**：MCP 路由由 Higress 承担。
> **删除原设计的 virbius-gateway-agent (9070)**：安全预检由 Higress WASM 插件承担。

> **Prompt 防护链路**：LLM 提示词安全（提示词注入检测、宪法注入、PII 脱敏、输出审查）不在 MCP 工具调用链路上执行，提供两种接入方式，均复用 virbius-engine 云层 prompt 规则（`runtime="prompt"`，建议 `bind_scope=global`）与 Qwen3Guard 语义检测，与 MCP Proxy 工具调用安全（tool_call 链路）相互独立、互不影响：
>
> - **方式一：VirbiusLLM 网关（零代码）**：APISIX/Higress `virbius-guard` 插件拦截 `POST /v1/chat/completions`，提取 user 提示词，经 `virbius-gateway-agent`(:9070) 调用 engine `POST /v1/evaluate`，命中即 403。仅需部署 APISIX 插件、配置引擎规则、将 Agent 的 LLM baseUrl 指向网关。生产建议 `fail_mode="close"`，引擎不可用时拒绝而非放行，防止绕过。
> - **方式二：应用方代码集成（自研 Agent）**：应用代码直接调用 engine `POST /v1/evaluate`（snake_case JSON，字段对齐 `EvaluateRequestDto`），无需部署网关：
>   - **入站 Prompt 检测**（用户 prompt → LLM 前）：`content=用户提示词`、`role="input"`，返回 `effective_action` 为 `block`/`deny` 时拦截；
>   - **输出审查**（LLM 响应 → 用户前）：`content=LLM 生成文本`、`role="output"`，命中 `block`/`deny` 时按响应中的 `disposal_action`（`reject|omit|redact|warn`，`redact` 附 `sanitized_content`）处置。
>   - 响应字段：`effective_action`（`allow`/`block`/`deny`/`challenge`/`review`）、`max_risk_score`、`reason_code`、`rule_id`、`trace_id`。引擎不可达默认放行（fail-open），fail-close 由应用方自行捕获超时/连接异常。
>
> > 注：`role` 为封闭枚举 `input | output | tool_call`（空白缺省= `tool_call` 语义，其他值一律 `400 unsupported_role`）。`role` 决定副作用语义（见 PROTOCOL §2.6.5 role 副作用矩阵）但**不影响 prompt 规则选择**——入站/出站复用同一套 prompt 规则；若需区分规则集，需在 engine 侧增加 role 过滤（尚未实现）。流式输出审查建议直接接入官方 `virbius-review-sdk`（`wrap_stream` 窗口化，429 恒 fail-closed）。

### 8.2 部署拓扑

**模式 A：Sidecar 部署（K8s Pod 内，东西向为主）**

```
┌─── K8s Pod ──────────────────────────────────────────────┐
|                                                          |
|  ┌──────────────┐         ┌──────────────────────────┐   |
|  | Agent        |  MCP    | MCP Proxy (端层)         |   |
|  |              |──JSON-RPC──> localhost:9090        |   |
|  |              |  stdio  | +-- License 校验          |   |
|  |              | /SSE    | +-- 预检 + engine 终判     |   |
|  |              |         | +-- curl 代发 (Egress)    |   |
|  └──────────────┘         └─────────────┬────────────┘   |
|  东西向（不经管层）                      |               |
└──────────────────────────────────────────┼───────────────┘
                                           | 南北向
                                           v
┌───────────────────────────────────────────────────────────┐
|  MCP Server (Python/Node) (:8080+)                        |
|  +-- 接收 tools/call，执行工具逻辑                          |
|  +-- virbius-core (端层嵌入: 预检 + P0 同进程执行)           |
|  +-- P2: Landlock + drop caps 沙箱                        |
└───────────────────────────────────────────────────────────┘

┌─── 宿主机 ────────────────────────────────────────────────┐
|  Falco DaemonSet (核层旁路)                                |
|  +-- eBPF 驱动（无特权环境 Disabled）                     |
|  +-- 事件 -> Redis Audit Stream                           |
└───────────────────────────────────────────────────────────┘

┌─── 云侧 ──────────────────────────────────────────────────┐
|  +-- virbius-engine (:8082) — Groovy L3 终判               |
|  +-- virbius-control (:8080) — 规则管理 + 发布              |
|  +-- Redis (:6379) — session 状态 + 审计流                 |
|  +-- Database — 规则持久化                                  |
└───────────────────────────────────────────────────────────┘
```

> Sidecar 模式下 MCP 工具调用走 localhost（东西向），不经管层 Higress。
> 外部 HTTP 请求（curl）由 Proxy 代发，在应用层校验 URL 白名单（[§3.5](ARCHITECTURE.zh.md#35-egress-流量管控)）。

**模式 B：远程部署（南北向，管层 Ingress）**

```
远程 Agent Client
  | MCP / JSON-RPC over HTTPS (南北向)
  v
+----------------------------------------------------------+
|  Higress (:443) — 管层 Ingress Gateway                 |
|  +-- TLS 终止                                             |
|  +-- 限流 / 长连接 / SSE 转发                              |
|  +-- virbius-gateway Lua 插件 (安全预检)                   |
|  +-- Higress MCP route -> MCP Server 路由                    |
+----------------------------------------------------------+
  | allow -> 转发；block -> 403
  v
+----------------------------------------------------------+
|  MCP Server (Python/Node) (:8080+)                       |
|  +-- virbius-core (端层预检 + P0 同进程执行)               |
|  +-- P2: Landlock + drop caps 沙箱                        |
+----------------------------------------------------------+

┌─── 宿主机 ────────────────────────────────────────────────┐
|  Falco DaemonSet (核层旁路)                                |
|  +-- eBPF 驱动（无特权环境 Disabled）                     |
|  +-- 事件 -> Redis Audit Stream                           |
└───────────────────────────────────────────────────────────┘

┌─── 云侧 ──────────────────────────────────────────────────┐
|  +-- virbius-engine (:8082) — Groovy L3 终判               |
|  +-- virbius-control (:8080) — 规则管理 + 发布              |
|  +-- Redis (:6379) — session 状态 + 审计流                 |
|  +-- Database — 规则持久化                                  |
└───────────────────────────────────────────────────────────┘
```

> 远程模式下 Agent 的 MCP 调用和外部 HTTP 请求均经过管层（南北向）。
> 管层 Higress 同时承担 Ingress（MCP 路由）和 Egress（外部 HTTP 代理）职责。

**模式 C：SDK 嵌入（进程内，无独立代理）**

```
┌─── Agent 进程 ───────────────────────────────────────────┐
│                                                           │
│  Agent 业务代码                                           │
│    │                                                      │
│    │ 1. 发送 LLM 请求前                                    │
│    │    prompt_gateway.enhance(&mut messages, &ctx)       │
│    │    → 宪法约束注入 + PII 输入脱敏                       │
│    │                                                      │
│    │ 2. 工具调用前                                         │
│    │    precheck(&license, &tool_call)                    │
│    │    → allowlist + JSON Schema 校验 + fast_path        │
│    │    → 未命中快速通道时调 engine 终判                    │
│    │                                                      │
│    │ 3. 工具返回后                                         │
│    │    output_reviewer.review(&content, &ctx)            │
│    │    → PII 输出脱敏 + 凭据泄露检测                       │
│    v                                                      │
│  virbius-core (Rust 库, 链接进 Agent 进程)                │
│    +-- License::verify() (Ed25519 JWT)                    │
│    +-- precheck() (allowlist + schema)                    │
│    +-- PromptGateway::enhance() (宪法注入 + DLP)           │
│    +-- DlpEngine (PII 脱敏, 进程内)                        │
│    +-- P2: Landlock + drop caps (Agent 可直接沙箱)         │
│                                                           │
│  C ABI (virbius_init / virbius_scan / virbius_reload)     │
│  → 可被 Python / Go / Java / Node.js 通过 FFI 调用         │
└──────────────────────────────┬────────────────────────────┘
                               │ HTTP (调 engine, 可选)
                               v
┌─── 云侧 ──────────────────────────────────────────────────┐
│  +-- virbius-engine (:8082) — Groovy L3 终判               │
│  +-- virbius-control (:8080) — 规则管理 + 发布              │
│  +-- Redis (:6379) — session 状态 + 审计流                 │
└───────────────────────────────────────────────────────────┘
```

> SDK 模式下安全预检在 Agent 进程内完成，无额外进程开销。
> `virbius-core` 通过 Rust FFI 导出 C ABI（`virbius_init` / `virbius_scan` / `virbius_reload`），可被非 Rust 语言调用。
> 缺少管层（无限流/TLS）和核层（无 Falco 观测），需在需要时与方式 1 或方式 2 组合使用。

---

### 8.3 接入方式对比

#### 8.3.1 三种方式全景

| 维度 | 方式 1：MCP Proxy (Sidecar) | 方式 2：Higress (远程) | 方式 3：SDK 嵌入 |
|------|---------------------------|------------------------|----------------|
| **部署形态** | Agent + Proxy 同 Pod，独立进程 | Agent 远程，Higress 集群内 | `virbius-core` 链接进 Agent 进程 |
| **流量方向** | 东西向（localhost） | 南北向（HTTPS） | 无网络流量（进程内调用） |
| **Agent 改造量** | **零代码**——改 MCP Server URL | **零代码**——改 MCP Server URL | **需改代码**——集成 SDK API |
| **语言限制** | 无（任何 MCP Client） | 无（任何 HTTP Client） | Rust 原生 / 其他语言需 C ABI FFI |
| **协议** | MCP (JSON-RPC 2.0) | HTTP/HTTPS | 函数调用（`precheck()` / `enhance()`） |

#### 8.3.2 四层安全覆盖

| 层级 | 方式 1 (MCP Proxy) | 方式 2 (Higress) | 方式 3 (SDK) |
|------|:------------------:|:------------------:|:------------:|
| **端层 (Edge)** | ✅ Proxy 内嵌完整管线 | ❌ 远程 Agent 无 `virbius-core` | ✅ 进程内直接调用 |
| **管层 (Gateway)** | ❌ 东西向不经 Higress | ✅ Higress 拦截南北向 | ❌ 无网关 |
| **核层 (Kernel)** | ✅ Falco DaemonSet 观测 | ❌ 远程 Agent 不在节点 | ❌ Agent 不在集群节点 |
| **云层 (Cloud)** | ✅ engine 终判 | ✅ engine 终判 | ✅ engine 终判（可选跳过） |
| **覆盖层数** | **3/4** | **2/4** | **2/4 + 端层深度** |
| **缺失补偿** | NetworkPolicy + 端层内嵌限流 | HTTP 阻断 + risk 累积 | 无核层观测，无管层限流 |

#### 8.3.3 安全能力对比

| 安全能力 | 方式 1 (MCP Proxy) | 方式 2 (Higress) | 方式 3 (SDK) |
|---------|:------------------:|:------------------:|:------------:|
| **License 校验** | ✅ Proxy 内 `license::verify()` | ✅ Higress WASM 校验签名 | ✅ 进程内 `License::verify()` |
| **工具白名单** | ✅ `precheck()` | ✅ WASM allowlist | ✅ `precheck()` |
| **JSON Schema 校验** | ✅ `precheck::validate_args()` | ⚠️ WASM 实现较弱 | ✅ `precheck::validate_args()` |
| **快速通道** | ✅ 跳过 engine，<2ms | ✅ 跳过 engine | ✅ 跳过 engine，零网络 |
| **engine 终判** | ✅ HTTP 调用 | ✅ HTTP 调用 | ✅ HTTP 调用（可选跳过） |
| **Prompt 增强** | ⚠️ 需 Agent 配合 | ❌ 不在拦截范围 | ✅ **进程内直接调** `enhance()` |
| **PII 输入脱敏** | ⚠️ 仅输出脱敏 | ❌ 不支持 | ✅ 输入+输出脱敏 |
| **DLP 检测** | ✅ Proxy 内 `dlp_engine` | ❌ 不支持 | ✅ `dlp_engine` 进程内 |
| **限流** | ✅ Fallback rate_limit | ✅ Envoy rate limit + Redis | ❌ 需自行实现 |
| **TLS 加密** | ❌ localhost 无需 TLS | ✅ Higress 终止 TLS | N/A（进程内） |
| **Falco 观测** | ✅ syscall/net/file | ❌ | ❌ |
| **沙箱隔离 (P2)** | ✅ Proxy 可 posix_spawn + Landlock | ❌ | ✅ Agent 可直接 Landlock |

> **方式 3 的独特价值**：SDK 方式是唯一能在 **prompt 层面** 拦截的方式——在 Agent 发送 LLM 请求前，进程内调用 `PromptGateway::enhance()` 完成宪法约束注入和 PII 脱敏。方式 1 和方式 2 只能拦截 `tools/call`，无法拦截 prompt。如果安全需求包含 Prompt 注入防护和 PII 脱敏，SDK 方式是唯一选择，或需在方式 1/7 基础上叠加方式 3。

#### 8.3.4 性能对比

| 性能指标 | 方式 1 (MCP Proxy) | 方式 2 (Higress) | 方式 3 (SDK) |
|---------|:------------------:|:------------------:|:------------:|
| **预检延迟** | ~1-2ms（含 IPC） | ~3-5ms（含 HTTP + WASM） | **<0.5ms**（内存函数调用） |
| **快速通道延迟** | ~2ms | ~5ms | **<0.5ms** |
| **全链路延迟** | ~10-50ms | ~20-50ms | ~10-50ms（engine RPC） |
| **Agent 启动开销** | 需启动 Proxy 进程 | 无额外进程 | **无**（库已链接） |
| **内存开销** | Proxy 独立进程 ~20MB | Higress 共享 | **~0**（共享 Agent 进程内存） |
| **网络跳数** | 1 跳（localhost） | 2 跳（Agent→Higress→MCP） | 0 跳（进程内）+ 1 跳（engine） |

#### 8.3.5 优缺点总结

**方式 1：MCP Proxy (Sidecar)**

| 优点 | 缺点 |
|------|------|
| Agent 零代码改造 | 额外进程开销（~20MB 内存） |
| 完整安全管线（License + 预检 + engine） | 不经管层，无 TLS/全局限流 |
| 核层 Falco 可观测 | 仅限 K8s Sidecar 部署 |
| 框架无关（任何 MCP Client） | Prompt 增强需 Agent 配合 |
| P2 可叠加沙箱隔离 | Egress 需额外 NetworkPolicy |

**方式 2：Higress (远程)**

| 优点 | 缺点 |
|------|------|
| Agent 零代码改造 | 仅 2 层覆盖（管层 + 云层） |
| TLS 终止 + 全局限流 | 无端层防护（无沙箱/无进程内预检） |
| 生产级网关（Higress 成熟稳定） | 无核层观测（远程 Agent 不在节点） |
| 适合远程/SaaS Agent | WASM Schema 校验能力弱 |
| Egress 管控能力强 | 不支持 Prompt 增强/PII 脱敏 |

**方式 3：SDK 嵌入**

| 优点 | 缺点 |
|------|------|
| **最低延迟**（进程内 <0.5ms） | **需改 Agent 代码** |
| **最深安全能力**——Prompt 增强 + PII 脱敏 + DLP | 无管层（无限流/TLS/网络隔离） |
| 无额外进程/无 IPC 开销 | 无核层观测（Agent 不在集群） |
| 快速通道零网络开销 | 语言限制（Rust 原生 / 其他需 FFI） |
| P2 可直接 Landlock 沙箱 | License 校验在 Agent 进程内（可被篡改） |
| C ABI 可跨语言（Python/Go/Java/C++） | 限流需自行实现 |

#### 8.3.6 选型决策树

```
Agent 是否在 K8s 集群内？
├── 是
│   ├── Agent 是否自研（可改代码）？
│   │   ├── 是 → Agent 语言？
│   │   │   ├── Rust → 方式 3 (SDK) ← 零延迟 + 最深安全
│   │   │   └── 其他 → 方式 1 (MCP Proxy) ← 零代码 + 核层观测
│   │   └── 否（存量框架）→ 方式 1 (MCP Proxy) ← 零代码接入
│   └── 是否需要四层全覆盖？
│       └── 是 → 方式 1 + 方式 2 组合 ← 纵深防御（见 §8.4）
└── 否（远程/SaaS）
    ├── 需要 TLS + 限流 → 方式 2 (Higress) ← 唯一选择
    └── 自研 Agent 可改代码 → 方式 3 (SDK) ← 端层深度安全
                              + 方式 2 (Higress) ← 补管层能力
```

#### 8.3.7 各方式规则执行流程

> [§6.1](DESIGN.zh.md#61-工具调用请求路径) 描述了四层全覆盖的组合流程。本节以对比表格形式说明三种独立部署方式的规则执行差异。

**三种方式规则执行对比**

| 规则类型 | 方式 1 (Proxy) | 方式 2 (Higress) | 方式 3 (SDK) |
|---------|:---:|:---:|:---:|
| **端层** | | | |
| 端层关键词规则 (`lua-dsl`) | ❌ Proxy 不跑端层关键词 | ❌ | ✅ `engine::scan_once` |
| 端层 DLP 脱敏 | ❌ | ❌ | ✅ `DlpEngine` 进程内 |
| Prompt 增强（宪法注入） | ❌ | ❌ | ✅ 仅 SDK |
| License 校验 | ✅ Proxy 内 | ✅ WASM 内 | ✅ 进程内 |
| JSON Schema 校验 | ✅ Proxy 内 | ❌ WASM Schema 弱 | ✅ 进程内 |
| **管层** | | | |
| 管层表达式规则 (WASM) | ❌ 绕过 | ✅ `virbius-expr` | ❌ |
| 管层限流 | ❌ 绕过 | ✅ Redis INCR | ❌ |
| **云层** | | | |
| 云层 Groovy 规则 | ✅ Engine | ✅ Engine | ✅ Engine（可选） |
| 云层 Prompt 规则 (1B 模型) | ✅ Engine | ✅ Engine | ✅ Engine（可选） |
| Prompt 注入检测 | ✅ Engine | ✅ Engine | ✅ Engine |
| Session Risk | ✅ Engine | ✅ Engine | ✅ Engine |
| Challenge 人工审批 | ✅ Proxy→Engine | ✅ WASM→Engine | ✅ Engine |
| **核层** | | | |
| 核层 Falco 观测 | ✅ DaemonSet | ❌ | ❌ |
| **其他** | | | |
| 输出审查（PII 泄露） | ✅ Proxy→Engine | ❌ | ✅ `output_reviewer` |
| P2 沙箱 (Landlock) | ✅ MCP Server 侧 | ✅ MCP Server 侧 | ✅ Agent 直接沙箱 |

> **组合部署**：当单一方式无法满足安全需求时，可组合部署。方式 1 + 方式 2 实现四层全覆盖（见 [§8.4](#84-四层全覆盖组合部署)）；方式 1/2 + 方式 3 补齐 Prompt 增强能力。

---

### 8.4 四层全覆盖（组合部署）

#### 8.4.1 拓扑

当安全需求要求端管核云四层全部覆盖时，需将方式 1（MCP Proxy Sidecar）与方式 2（Higress Ingress）组合部署——远程流量经管层入集群，到达端层 Sidecar Agent Pod，核层在节点上观测，云层统一终判：

```
远程 Agent (集群外)
  │
  │ HTTPS (南北向)
  v
┌─────────────────────────────────────────────────────────────┐
│ [管层] Higress (:443) — Ingress Gateway                    │
│   +-- TLS 终止                                               │
│   +-- 全局限流 (Envoy rate limit)                                    │
│   +-- License 签名校验                                        │
│   +-- tool allowlist (WASM)                                   │
│   +-- 转发到集群内 Agent Pod                                  │
└────────────────────────┬────────────────────────────────────┘
                         │ ClusterIP (集群内)
                         v
┌─── K8s Pod ──────────────────────────────────────────────────┐
│                                                              │
│  ┌──────────────┐         ┌──────────────────────────┐       │
│  | Agent        |  MCP    | [端层] MCP Proxy         |       │
│  |              |──JSON-RPC──> localhost:9090        |       │
│  |              |  stdio  | +-- License 校验          |       │
│  |              | /SSE    | +-- precheck (schema)     |       │
│  |              |         | +-- engine 终判            |       │
│  |              |         | +-- Prompt 增强 (可选)     |       │
│  └──────────────┘         └─────────────┬────────────┘       │
│  东西向（localhost）                     |                   │
└──────────────────────────────────────────┼───────────────────┘
                                           │
                     ┌─────────────────────┐│
                     │ [核层] Falco        ││
                     │ DaemonSet           ││
                     │ +-- eBPF 观测       ││
                     │ +-- syscall/net/file││
                     │ +-- audit stream    ││
                     └─────────────────────┘│
                                            v
┌─── 云侧 ──────────────────────────────────────────────────────┐
│ [云层]                                                       │
│   +-- virbius-engine (:8082) — Groovy L3 终判                 │
│   +-- virbius-control (:8080) — 规则管理 + 发布                │
│   +-- Redis (:6379) — session 状态 + 审计流                   │
└──────────────────────────────────────────────────────────────┘
                                           │
                                           v
                                      MCP Server
```

#### 8.4.2 优点

| 优点 | 说明 |
|------|------|
| **纵深防御完整** | 四层独立运作，任一层被绕过仍有其他层兜底。端层预检失败 → 管层 allowlist 拦截；管层被绕过 → 端层 License 校验仍在；核层观测异常 → 提升 risk_score → 云层阻断后续请求 |
| **南北东西分离** | 远程流量经管层（TLS/限流/Ingress 安全），集群内流量经端层（深度预检/schema/Prompt 增强），各司其职 |
| **运行时观测** | 核层 Falco 提供 syscall/net/file 级观测，捕获端层和管层都无法检测的异常（如容器逃逸、SSRF 内网扫描） |
| **策略同源** | 四层共享 `virbius-control` 作为策略真源，共享 Redis 存储 session/risk_score，策略一致、风险分互通 |
| **渐进降级** | eBPF 不可用时核层观测 Disabled（方案 A 起无 plugin 兜底），端层预检 + License 仍生效；engine 不可用时端层 fail-open/fail-closed；管层不可用时端层独立兜底 |
| **沙箱隔离 (P2)** | 端层 Proxy 可 posix_spawn + Landlock，管层和云层不参与执行，隔离边界清晰 |
| **全链路审计** | 管层记 HTTP 层审计，端层记 MCP 协议层审计，核层记 syscall 级审计，云层汇总终判审计——四层审计互补无死角 |

#### 8.4.3 缺点

| 缺点 | 说明 | 缓解方案 |
|------|------|---------|
| **双重拦截延迟** | 远程流量经管层 → 端层两次安全校验，全链路延迟 ~60-100ms（含两次 engine 调用） | 按能力分工：管层退化为 TLS + 限流 + 路由，安全终判收敛到端层（见 §8.4.4） |
| **部署复杂度高** | 需同时部署 Higress + MCP Proxy + Falco DaemonSet + Engine + Control + Redis，组件数 6+ | 提供 Helm Chart 一键部署；非生产环境可仅部署端层 + 云层 |
| **双重 engine 调用** | 管层和端层各调一次 `/v1/evaluate`，engine 负载翻倍 | 管层配置 `evaluate=false`，仅端层调 engine |
| **双重计数器冲突** | 管层 WASM Redis 和端层 Fallback 限流各计一次，rate_limit 语义混乱 | 限流统一收敛到管层，端层移除 Fallback rate_limit |
| **运维成本** | 四层组件需独立监控、日志、告警，故障排查需跨层关联 trace_id | 统一 trace_id 串联四层审计；运营台提供跨层调用链可视化 |
| **资源开销** | 每个 Agent Pod 额外 ~20MB（Proxy）+ 每节点 ~50MB（Falco）+ Higress 集群 + Engine 集群 | 轻量场景可降级为 2 层（端层 + 云层） |
| **串联配置风险** | 管层和端层策略不一致时行为不可预测（如管层 allow 但端层 deny） | 策略同源（`virbius-control` 统一下发）；管层 allowlist ⊆ 端层 allowlist（端层更严格） |

#### 8.4.4 串联分工方案

为避免双重拦截导致的延迟翻倍和冲突，组合部署时需按能力分工：

| 安全能力 | 由谁负责 | 另一方行为 | 原因 |
|---------|---------|-----------|------|
| TLS 终止 | 管层 Higress | 端层 Proxy 不做 TLS（内网 HTTP） | TLS 是网络边界能力 |
| 全局限流 | 管层 Higress (Envoy rate limit) | 端层移除 Fallback rate_limit | 限流是网络边界能力 |
| tool allowlist | **只做一次**——端层 Proxy | 管层跳过 allowlist | 端层 schema 校验更完整 |
| 计数器 | **只做一次**——管层 Higress | 端层不查 Redis 计数 | 避免双重计数 |
| License 校验 | 管层 Higress（入口） + 端层 Proxy（深度） | 两层都做 | 管层验签名，端层验 allowed_tools |
| JSON Schema 校验 | 端层 MCP Proxy | 管层不做 | WASM Schema 库弱，Rust 实现完整 |
| engine 终判 | **只做一次**——端层 MCP Proxy | 管层配置 `evaluate=false` | 避免双重 engine 调用 |
| 快速通道 | 端层 MCP Proxy | 管层不判断快速通道 | 端层有 SessionStateCache |
| 审计 | 两者都做（不同维度） | 管层记 HTTP 层，端层记 MCP 协议层 | 审计互补 |
| 核层观测 | Falco DaemonSet（旁路） | — | 旁路无侵入 |
| 沙箱隔离 (P2) | 端层 Proxy | — | 管层不参与执行 |

管层 Higress effective JSON 配置：

```json
{
  "virbius": {
    "evaluate": false,
    "tool_precheck": false,
    "rate_limit": true,
    "tls": true,
    "license_verify": true
  }
}
```

#### 8.4.5 适用场景

| 场景 | 是否推荐组合部署 | 原因 |
|------|:---------------:|------|
| **金融/医疗等强合规** | ✅ 推荐 | 监管要求纵深防御 + 全链路审计 |
| **高安全 SaaS 平台** | ✅ 推荐 | 多租户隔离 + TLS + 限流 + 深度预检 |
| **内部工具 Agent** | ❌ 过度 | 端层 + 云层即可，无需管层 |
| **开发/测试环境** | ❌ 过度 | SDK 模式（方式 3）最快迭代 |
| **存量 Agent 集群内部署** | ⚠️ 可选 | 方式 1 已覆盖 3 层，按需叠加管层 |

---

### 8.5 K8s Helm 部署

用一份 Helm Chart 把靶场、端侧、云侧、管侧、运营登录（virbius-auth）和 MySQL / Kafka / Redis / Ollama（含 VirbiusGuard 模型导入）装进**已有集群**。不代装 Ingress Controller、Higress、Falco。

Chart：`deploy/helm/virbius`。脚本：`deploy/scripts/k8s-build-push.sh`、`deploy/scripts/k8s-deploy.sh`。

#### 前置

- 能 `docker login` 的镜像仓库
- 集群已装 Ingress Controller（默认 class `nginx`）
- 本机有 `docker`、`helm`、`kubectl`
- 本地 values 文件填写密钥（不要提交 Git）

```bash
cp deploy/helm/virbius/values.example.yaml deploy/helm/virbius/values-prod.yaml
# 编辑 values-prod.yaml：global.imageRegistry、secrets.*、ingress.hosts、imagePullSecrets
```

#### 一键构建、推送、安装

```bash
./deploy/scripts/k8s-deploy.sh \
  --registry registry.example.com/virbius \
  --tag v0.1.0 \
  --namespace virbius \
  --values deploy/helm/virbius/values-prod.yaml
```

只构建推送镜像：

```bash
./deploy/scripts/k8s-build-push.sh --registry registry.example.com/virbius --tag v0.1.0
```

镜像已在仓库、只装 Chart：

```bash
./deploy/scripts/k8s-deploy.sh --skip-build \
  --registry registry.example.com/virbius \
  --tag v0.1.0 \
  --values deploy/helm/virbius/values-prod.yaml
```

#### Ingress 四个独立 host（默认）

| 侧 | Host | 健康检查 |
|----|------|----------|
| 靶场 | `range.virbius.example.com` | `GET /` |
| 管侧 | `control.virbius.example.com` | `GET /api/v1/health` |
| 云侧 | `engine.virbius.example.com` | `GET /admin/health` |
| 端侧 | `proxy.virbius.example.com` | `GET /health` |

把这四个名字指到 Ingress 的外部 IP。TLS 默认关闭，在 values 里打开 `ingress.tls`。管侧 host 上的 `/login` 转到 `virbius-auth`（与 `/ui/` 同域，避免登录 cookie 丢失）。默认账号 `admin` / `secrets.authBootstrapPassword`。

集群内 DNS：`virbius-control:8080`、`virbius-auth:8083`、`virbius-engine:8082`、`virbius-mcp-proxy:9090`、`virbius-demo:8000`（SSE `:9091`）。Auth 用 MySQL（与 Control 同库，独立 Flyway 历史表 `auth_schema_history`），JWT 密钥落在 PVC 上，不要扩副本。设 `auth.enabled=false` 则不部署 Auth、Control 也不开 operator JWT。

靶场 Agent 关卡仍在 Pod 内用 stdio 拉起 `virbius-mcp-proxy`。Ingress 上的端侧是给集群外 MCP 客户端的 TCP 入口，upstream 默认 `http://virbius-demo:9091`。

默认启用集群内 Ollama：Job 从 HuggingFace 下载 VirbiusGuard GGUF（V2.0 F16，约 1.5GB，PVC 缓存，升级不重复下）并 `ollama create virbiusguard`。`engine.promptLlm.baseUrl` 留空即指向 `http://{release}-ollama:11434`。国内把 `ollama.ggufUrl` 换成 ModelScope：

`https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/gguf/virbiusguard-v2.0-f16.gguf`

设 `ollama.enabled=false` 时必须填写 `engine.promptLlm.baseUrl`，否则 `helm` 会直接失败。关掉集群内 MySQL / Kafka / Redis 时分别填 `mysql.jdbcUrl`、`kafka.bootstrapServers`、`redis.url`，engine/control 不会再死等集群内 Service。GPU 默认关；有 NVIDIA 设备时设 `ollama.gpu.enabled=true`。

`helm uninstall virbius -n virbius` **不会**删除 PVC。

---

## 9. P0 凭证与数据通道安全基线

适用于 `docker-compose.prod.yml` 与 Helm（prod/staging profile）。控制面启动时执行
`SecretPolicyValidator`，出现下列任一违规即**启动失败**。

### 9.1 必填环境变量

| 变量 | 范围 | 说明 |
|---|---|---|
| `VIRBIUS_LICENSE_MASTER_KEY` | 所有 profile | ≥32 字符；空值/已知默认值直接失败 |
| `VIRBIUS_API_KEY_PEPPER` | 开启 API-Key 认证时（prod 默认开） | ≥32 字符；租户 API Key 的 HMAC pepper |
| `REDIS_PASSWORD` | compose prod / Helm（`secrets.redisPassword`） | 写进 `VIRBIUS_REDIS_URL`：`redis://:<password>@host:port` |
| `KAFKA_SASL_USERNAME` / `KAFKA_SASL_PASSWORD` | prod | SCRAM-SHA-256 凭证（`KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT`） |
| `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | prod | |
| `VIRBIUS_STRICT_SECRETS` | 可选 | 仅本地 prod-profile 开发环境设 `false`（如 `.vscode/launch.json`） |

`run-local.sh` 会自动在 gitignore 的数据目录生成开发用 master key。

### 9.2 MySQL TLS（自签证书，compose 生成）

`docker-compose.prod.yml` 的 `cert-gen` 一次性服务（`deploy/certs/gen-mysql-certs.sh`）
生成 CA + 服务端证书（SAN 含 `DNS:mysql`）到 `mysql-certs` 卷；control 以
`sslMode=VERIFY_IDENTITY&serverSslCert=/certs/ca.pem` 连接。Helm 侧设
`mysql.tls.enabled=true` 并提供 `mysql-server-cert` / `mysql-server-key` /
`mysql-ca`（values 或 `mysql.tls.existingSecret`）。

**证书轮换 runbook**：只删 `server-cert.pem`/`server-key.pem`（保留 CA），重启
`cert-gen` + `mysql`，再滚动重启 `virbius-control`（CA 不变，客户端无感）。CA 轮换
需两者同步滚动。默认有效期 825 天，到期前 30 天告警。

### 9.3 Kafka SASL（SCRAM-SHA-256 + PLAINTEXT）——已接受风险

客户端用 SCRAM（挑战-响应，口令不明文过网）；但客户端 listener 传输层为
PLAINTEXT，即 **审计/trace 载荷在集群内网明文传输**。P0 接受该风险的条件：

1. Kafka 仅在可信域内可达（NetworkPolicy / compose 内部网络；loopback 的
   `INTERNAL` listener 仅用于 broker 内部与 SCRAM  provisioning）。
2. 载荷脱敏持续有效（设计上审计事件不含凭证）。
3. **升级触发条件**（先到先触发）：Kafka 跨主机/跨网段部署，或合规审计要求
   传输加密 → 切换 `SASL_SSL`（P1，可复用 MySQL CA 或独立 CA）。

### 9.4 认证默认值

- 规范 env 名为 `VIRBIUS_SECURITY_API_KEY_ENABLED`（旧名
  `VIRBIUS_API_KEY_ENABLED` 兼容一版，已标记废弃）。
- prod/staging 默认开启 API-Key 认证；全部认证机制关闭时启动失败
  （隐式 dev 管理员仅限 dev profile）。
- `virbius-auth` 仅测试环境使用：dev compose 保留带注释的 `admin/admin`
  便利默认值（仅限测试环境——任何非 dev 部署都不要设置）；线上接用户 SSO
  且不部署该模块（见 SSO_INTEGRATION.md）。

### 9.5 升级说明

- 存量 API Key 无需重发：legacy SHA-256 行在首次认证时透明升级为
  peppered HMAC（`hash_algo` 列，Flyway `V8`）。
- 存量 `private_key_enc` 仍按旧派生解密；新签发密钥使用 `v1:` HKDF 信封。
- 旧本地开发库可用 `virbius.license.allow-insecure-dev-key=true`
  （**仅 dev profile**）在换强密钥前继续启动。

### 9.6 代客凭证平面（v2.5）部署与运维

详见 [PROTOCOL.zh.md](PROTOCOL.zh.md) §2.6.1「Egress 代客凭证注入」。要点：

**Control 侧**：

| 环境变量 | 说明 |
|---------|------|
| `VIRBIUS_DELEGATED_MASTER_KEY` | ≥32B，与 license master 分钥（域分离），fail-fast |
| `VIRBIUS_REDIS_URL` | 快照发布通道（复用现有） |

- 迁移：`V11__delegated_cred_plane.sql`（凭据单表）。（删除/吊销后的自愈由 Control 每 60s 的并集对账完成，无需额外状态表。）**构建须 clean**：旧切片产物 `target/classes/db/migration/V10__delegated_creds.sql` 与新迁移冲突；曾应用旧 V10 的环境执行 `flyway repair` + 手工 DROP 旧三表。
- 签发流程：`POST /api/v1/admin/tenants/{t}/delegated-credentials`（handle/host/header_name/header_prefix/value）→ `POST /kek/reveal` 一次性取 KEK。

**Proxy 侧**（`[delegated]` 配置段，均可用 `VIRBIUS_DELEGATED_*` 环境变量覆盖；非热更，改动需重启）：

| 键 | 默认 | 说明 |
|----|------|------|
| `enabled` | `false` | 开关；关=非本地 egress 逐字节一致 + 本地执行不受限 |
| `tenant_id` | — | 部署固定租户（trim+小写；空/"default" 拒启） |
| `kek` / `kek_file` | — | 租户 KEK（b64 32B）；**优先 0400 文件**，禁入 argv/日志/shell 历史 |
| `redis_url` | 回退 audit | 快照轮询通道 |
| `poll_interval_secs` | `30` | 须 < ttl |
| `ttl_secs` | `300` | 连续超时→清库→cold（注入停止） |
| `max_body_mb` | `4` | 命中响应缓冲上限 |
| `allow_external_bind` | `false` | 非 loopback 绑定的显式豁免（启动告警+审计） |

> 构建期测试缝：仅 `cargo` 编译特性 `test-harness` 打开时才读 `VIRBIUS_DELEGATED_TEST_RELAX_ENDPOINT`（放宽 https+443 闸门供整链测试）。**发布构建不得启用**；生产二进制无法通过运行时变量关闭该闸门。

**上线检查单**：

1. `VIRBIUS_EGRESS_HOSTS` 必须包含全部凭据 host（vault 应用时会对缺失项告警 `host_not_in_allowlist`）。
2. **LLM 流量不入 vault**（llm-proxy/LLM 厂商域名不配置凭据）：一期命中分支无流式（SSE 会缓冲到上限后拒绝）。
3. 启动前提校验：绑定须 stdio/loopback（两族+hostname 解析），否则拒启——这是 D1⑥「仅租户可达」前提的唯一本地落点。
4. 吊销/删除时效：下一轮快照轮询（≤30s）生效；Control 全量清空（rows=[]）→ proxy 清库转 cold。**删到最后一行若发布失败**（如 Redis 抖动）：60s 并集对账（DB 有行租户 ∪ Redis 仍持 key 租户）会重发空 doc → proxy 清库（不再永久残留旧凭证）。
5. 告警：`tracing::error`（`delegated_cred_alert{code,state}`，60s 节流）始终可用，不依赖 Kafka；关注 `snapshot_key_missing`、`snapshot_unreadable_rows`、`host_not_in_allowlist`、`ttl_breach_cleared`、`tenant_mismatch`、`hit_credential_echo`（上游回显凭证）。
6. 轮询退避：读取失败按 `30s→60s→…→600s`（10min 封顶，±10% 抖动）指数退避，成功即复位；**TTL 清库判断每轮都执行**，退避不影响离线兜底。
7. KEK 交付：`POST /kek/reveal` 响应带 `Cache-Control: no-store`+`Pragma: no-cache`（防中间层缓存）；Control 不记录请求/响应体，KEK 不落 access log——仍须运维以 0400 文件落地，勿经终端/CI 回显。

---

## 10. 审计完整性 / 输出安全 / 动态风险（A/B/C）

本节覆盖评测大纲 A（日志审计 1.2）、B（输出安全 1.5）、C（动态风险 3.1/3.2）相关的库表、配置与运维动作。

### 10.1 新增数据库迁移（Flyway，prod/staging）

| 迁移 | 内容 |
|---|---|
| `V13__trace_result_preview.sql` | `tb_agent_trace.tool_result_preview`（工具结果预览，供决策重建回看） |
| `V14__audit_integrity_retention_export.sql` | 每会话 trace 哈希链列（`chain_seq`/`prev_hash`/`curr_hash`）+ `tb_trace_chain_state`；每日锚点表 `tb_audit_daily_anchor`；保留策略表 `tb_tenant_retention_policy`；归档镜像 `tb_audit_events_archive`/`tb_agent_trace_archive`；导出账本 `tb_audit_export_job` |
| `V15__alerts_and_session_checkpoints.sql` | 告警表 `tb_alert`、会话检查点表 `tb_session_checkpoint` |
| `V16__evaluate_token_auth.sql` | 评估令牌鉴权：`tb_evaluate_token_issuers`、`tb_evaluate_tokens`；审计表与归档表加 `agent_aid`/`app_id`/`subject_source`/`token_iss`；`tb_license_revocations` 加 `agent_aid`/`expires_at` |
| `V17__proxy_policy.sql` | `tb_proxy_policy`（此前仅存在于 SQLite；MySQL 上代理策略接口报 "table doesn't exist"） |
| `V18__trace_tool_args.sql` | `tb_agent_trace.tool_args` + `tb_agent_trace_archive.tool_args`（此前仅存在于 SQLite；MySQL 上 trace 入库失败） |
| `virbius-auth` `V1__baseline.sql` | auth 的 MySQL 基线（`tb_operator_user`、`tb_auth_code`）；prod 用 Flyway，独立历史表 `auth_schema_history` |

`SchemaDialectParityTest`（`virbius-control` 与 `virbius-auth` 各一份）在 `db/schema.sql`（SQLite/dev）与 `db/migration/*.sql`（MySQL/prod）表或列不一致时令构建失败。

dev 环境关闭 Flyway，用 `db/schema.sql` 建表：**旧库不含上述新列/新表**，需以 `VIRBIUS_REBUILD_DB=1 ./scripts/run-local.sh` 重建（会删库并 `FLUSHDB`）。

### 10.2 新增配置项

| 键 | 默认 | 说明 |
|---|---|---|
| `virbius.audit.hash-chain.enabled` | `true` | 审计事件哈希链开关 |
| `virbius.audit.hash-chain.verify-enabled` | `true` | 定时全链校验 |
| `virbius.audit.hash-chain.verify-interval-ms` | `3600000` | 校验周期（1h） |
| `virbius.audit.hash-chain.verify-batch-size` | `10000` | 每批校验行数 |
| `virbius.audit.daily-anchor.enabled` | `true` | 每日 Merkle 锚点封存 |
| `virbius.audit.retention.enabled` | `true` | 保留归档任务 |
| `virbius.audit.export.key` | 空 | 导出产物 AES-256-GCM 密钥（base64，解码须 32B）；**不配则导出落 `failed`（fail-closed）** |
| `virbius.audit.export.allow-plaintext` | `false` | 显式允许明文导出（不推荐，仅调试） |
| `virbius.alert.risk-threshold` | `80` | 生成告警的风险阈值（且动作须为 block/deny/challenge）；**告警按答案（trace_id）聚合**——流式被拦答案的多个窗口事件只开一条告警 |
| `virbius.output-review.action-map` | `PII=redact,PROMPT_INJECTION=omit,OUTPUT_CREDENTIAL_LEAK=redact` | 原因码→处置（`reject|omit|redact|warn`）映射，未列出走关键字兜底；**统一在引擎解析，所有调用方一致** |
| `virbius.evaluate.rate-limit.enabled` | `true` | `/v1/evaluate` 限流开关（无鉴权端点的滥用防护） |
| `virbius.evaluate.rate-limit.per-session-per-min` | `60` | 每 session 每分钟**回答/请求数**（流式窗口按 trace 合并计 1 次） |
| `virbius.evaluate.rate-limit.per-ip-per-min` | `600` | 每 IP 每分钟调用总数（兜底；认证前也生效，覆盖 401 刷量） |
| `virbius.evaluate.rate-limit.per-agent-per-min` | `600` | 每已验 `agent_aid` 每分钟调用上限（会话配额之上的**兜底**，防换 session 绕过；约为「会话数×60」） |
| `virbius.evaluate.rate-limit.max-windows-per-trace` | `64` | 单 trace 流式审查窗口上限（防单 trace 无限刷） |
| `virbius.evaluate.auth.enabled` | `true` | 调用者认证开关。开启后 `/v1/evaluate*` 需短 token；**401/403 fail-closed** |
| `virbius.evaluate.auth.bootstrap-pem-file` | 空 | **N1 内置信任锚**：control 平台公钥 PEM 文件路径（部署烘焙；认证开启且无内置锚又拉不到 Redis issuer → **引擎拒绝启动**） |
| `virbius.evaluate.auth.bootstrap-kid` | `bootstrap` | 内置锚的 kid |
| `virbius.evaluate.auth.issuer-redis-key` | `virbius:engine:issuers` | issuer 集（control 启动时幂等发布）+ 30s 周期刷新 |
| `virbius.evaluate.auth.revocation-redis-key` | `virbius:engine:revocations` | 吊销全量快照（启动加载 + 30s 对账） |
| `virbius.evaluate.auth.revocation-channel` | `virbius:license:revoked` | 吊销即时推送通道 |
| `virbius.evaluate.auth.verify-cache-ttl-seconds` | `900` | 验签结果缓存（**吊销不在该缓存内，每请求必查**） |
| `virbius.evaluate-token.ttl-seconds`（control） | `900` | 短 token TTL |
| `virbius.evaluate-token.ledger-retention-hours`（control） | `24` | 签发台账（`tb_evaluate_tokens`）留存；支撑 jti 级吊销 |
| `virbius.evaluate-token.exchange.rate-limit-per-license-per-min`（control） | `10` | 换取端点限速 |
| `auth.control_base_url`（proxy） | 空 | 非空即启用 proxy 端换取（每会话一次 + 主动刷新） |
| `virbius.review.input-risk-dedup-ttl-ms` | `600000` | 输入审查风险计分去重窗口（同 trace 只计一次，10 分钟） |
| `VIRBIUS_JDBC_INIT_SQL`（control/auth · prod/staging） | `SET time_zone = '+00:00'` | 连接初始化 SQL；把 MySQL/MariaDB 会话固定为 UTC，使 `NOW()`/`CURRENT_TIMESTAMP` 与应用写入的 ISO-UTC 字符串一致（SQLite 本就是 UTC） |

> 上述键目前均由代码 `@Value` 提供默认值，`application*.yml` 未显式声明；如需覆盖，用启动参数或同名 `VIRBIUS_*` env。
> **已移至 SDK 侧**（原引擎 chat 内置审查的配置）：`skip-models`、`refusal-text`、`fail-closed`、`streaming.{window-chars,context-chars,stream-first}` —— 见 `virbius-review-sdk` README。`virbius.trace.*` 已随 `TraceStepPublisher` 删除。
| `virbius.chain-patterns.enforce` | `false` | 时序链式检测：`false`=dry-run（仅审计），`true`=执行 |
| `virbius.retry-escape.enforce` | `false` | 重试逃逸检测同上（dry-run by default） |

> 上述键目前均由代码 `@Value` 提供默认值，`application*.yml` 未显式声明；如需覆盖，用启动参数或同名 `VIRBIUS_*` env。

### 10.2.1 调用者认证运维要点（P0-min）

- **信任锚交付**：首选把 control 平台公钥 PEM 落到引擎 `virbius.evaluate.auth.bootstrap-pem-file`（部署烘焙）；也可让 control 启动后把 issuer 集写入 Redis（`virbius:engine:issuers`）由引擎拉取。**认证开启时，二者皆无 → 引擎拒绝启动（fail-fast，防静默无鉴权）**。
- **启动顺序**：Redis → control（发布 issuer/吊销）→ engine。Redis 丢失 issuer hash 时，control 用「重发」端点幂等重建（或重启）。
- **`control` 成为运行期依赖（proxy 路径）**：proxy 每**会话**向 control 换取一次短 token（非每请求）；control 宕机会导致**新会话**起不来、存量会话在 token TTL 内可续。生产建议 control 多实例 + 短 token TTL 权衡（默认 15min）。
- **吊销时延**：正常情况下秒级（Redis 通道 + 每请求查名单）；通道本身故障时以 token TTL 为最坏窗口。
- **残余风险（显式登记）**：引擎**不可达 → 调用方 `fail_open`（内容未审放行）**，而**认证被拒 401/403 → `fail_closed`（拦截）**——同为“引擎侧异常”两种相反姿态，属既定策略，认证上线后更需在生产网关/告警层关注“引擎不可达”这一路。
- **仓外接入（上线检查单）**：APISIX/Higress 网关与 `gateway-agent` 调用 `/v1/evaluate` 需携带 **per-purpose 服务 token**（`POST /api/v1/admin/evaluate-token/service` 签发，跨租户用途才授予 `*` 工具）。

### 10.3 审计导出密钥（必须显式提供）

```bash
# 生成一把 32B 密钥并交给 control（与 run-local 启动参数或 env 二选一）
export VIRBIUS_AUDIT_EXPORT_KEY="$(openssl rand -base64 32)"
# 或 --virbius.audit.export.key=<base64>
```

- 语义：导出内容是审计事件的 JSONL（含 `user_id`/`device_id`/`reason_code`），以 `12B IV || 密文+tag` 落 `data/exports/<jobId>.bin`，AAD 绑定 `jobId`。
- 控制面**只产出密文**，不提供解密端点——运营方持同一把密钥离线解密。
- 不配置且未显式 `allow-plaintext=true` → 导出任务落 `failed`，`error` 字段给出原因（安全默认，非缺陷）。

### 10.4 审计证据表 append-only 授权（A0，tamper prevention）

哈希链提供**检测**，以下脚本提供**预防**：对应用账号回收证据表的 `UPDATE`/`DELETE`，使审计/trace/挑战表在权限层不可改写。

```bash
# 生产手动执行（账号名按实际调整；非 Flyway 管理）
mysql -u root -p < deploy/scripts/audit-insert-only-grants.sql
```

保留归档所需的 `DELETE` 仅由独立 ops 账号持有，且只允许删除保留窗口之外、且当日锚点已封存的行。

### 10.5 运营台新增页面

- `/alerts`（告警）：列表 + 状态过滤（`open`/`acked`）+ 确认（ack）+ 展开决策 BOM。
- `/audit-integrity`（审计完整性，6 tab）：完整性校验（含每日锚点封存/运行校验）、Merkle 证明、决策 BOM、审计导出、输出误拦统计、会话检查点。
