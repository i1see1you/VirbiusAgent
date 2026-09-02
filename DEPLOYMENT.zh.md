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
>   - **入站 Prompt 检测**（用户 prompt → LLM 前）：`content=用户提示词`、`role="user"`，返回 `effective_action` 为 `block`/`deny` 时拦截；
>   - **输出审查**（LLM 响应 → 用户前）：`content=LLM 生成文本`、`role="output"`，命中 `block`/`deny` 时替换为安全提示或丢弃。
>   - 响应字段：`effective_action`（`allow`/`block`/`deny`/`challenge`/`review`）、`max_risk_score`、`reason_code`、`rule_id`、`trace_id`。引擎不可达默认放行（fail-open），fail-close 由应用方自行捕获超时/连接异常。
>
> > 注：`role` 字段当前**不影响**规则选择——入站/出站复用同一套 prompt 规则；若需区分规则集，需在 engine 侧增加 role 过滤（尚未实现）。

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

用一份 Helm Chart 把靶场、端侧、云侧、管侧和 MySQL / Kafka / Redis / Ollama（含 VirbiusGuard 模型导入）装进**已有集群**。不代装 Ingress Controller、Higress、Falco。

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

把这四个名字指到 Ingress 的外部 IP。TLS 默认关闭，在 values 里打开 `ingress.tls`。

集群内 DNS：`virbius-control:8080`、`virbius-engine:8082`、`virbius-mcp-proxy:9090`、`virbius-demo:8000`（SSE `:9091`）。

靶场 Agent 关卡仍在 Pod 内用 stdio 拉起 `virbius-mcp-proxy`。Ingress 上的端侧是给集群外 MCP 客户端的 TCP 入口，upstream 默认 `http://virbius-demo:9091`。

默认启用集群内 Ollama：Job 从 HuggingFace 下载 VirbiusGuard GGUF（约 484MB，PVC 缓存，升级不重复下）并 `ollama create virbiusguard`。`engine.promptLlm.baseUrl` 留空即指向 `http://{release}-ollama:11434`。国内把 `ollama.ggufUrl` 换成 ModelScope：

`https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/virbiusguard-v13-q4_k_m.gguf`

设 `ollama.enabled=false` 则仍用外部 LLM 地址。GPU 默认关；有 NVIDIA 设备时设 `ollama.gpu.enabled=true`。

`helm uninstall virbius -n virbius` **不会**删除 PVC。

---
