# VirbiusAgent Deployment View — DEPLOYMENT

[中文版](DEPLOYMENT.zh.md)

| Item | Description |
|------|------|
| Document Version | v3.6 |
| Status | Released (1.0.0) |
| Related | [DESIGN.md](DESIGN.md) (Index) · [ARCHITECTURE.md](ARCHITECTURE.md) |

> This document contains §8 Deployment View (Component Ports + Deployment Topology + Integration Approach Comparison + Four-Layer Full Coverage Combined Deployment).

---

## 8. Deployment View

### 8.1 Component Ports

| Component | Port | Deployment Location | Traffic Direction |
|------|------|---------|---------|
| **Agent Application** | Dynamic | User-side / Serverless Container | — |
| **MCP Proxy** (Edge Layer Sidecar) | localhost:9090 | Same Pod as Agent / Same Host | East-West |
| **virbius-core** (Edge Layer Embedded) | Embedded in MCP Server Process | Same Process as MCP Server | East-West |
| **Higress** (Gateway Layer Ingress) | 80/443 | Standalone / K8s | North-South (Inbound) |
| **Higress** (Gateway Layer Egress) | 8081 | Standalone / K8s | North-South (Outbound) |
| **MCP Server** (Python/Node) | 8080+ | Standalone / K8s | — |
| **virbius-engine** | 8082 | Cloud Side | — |
| **virbius-control** | 8080 | Cloud Side | — |
| **Falco** (Kernel Layer Observation) | None (DaemonSet) | Agent Host Machine | Bypass |
| **virbius-kernel-daemon** | 9090 | Agent Host Machine | Bypass |
| **Redis** | 6379 | Cloud Side | — |
| **Database** | — | Cloud Side | — |

> **Multi-Upstream Support**: MCP Proxy supports connecting to multiple MCP Servers simultaneously (added in P1), routing through tool name via `tools/call`.
> Single-upstream mode is backward compatible (legacy `upstream_url` config is auto-normalized); conflicting tool names in multi-upstream mode are automatically prefixed with `{upstream}__{tool}`.
> See [PROTOCOL.md §2.6.2](PROTOCOL.md#262-multi-upstream) for details.

> **Removed original AgentGateway (9080)**: MCP routing is handled by Higress.
> **Removed original virbius-gateway-agent (9070)**: Security precheck is handled by the Higress WASM plugin.

> **Prompt Protection Chain**: LLM prompt security (prompt injection detection, constitutional injection, PII desensitization, output review) is not executed on the MCP tool-call path. It offers two integration modes, both reusing the virbius-engine cloud prompt rules (`runtime="prompt"`, recommend `bind_scope=global`) and Qwen3Guard semantic detection, independent of MCP Proxy tool-call security (the tool_call chain):
>
> - **Mode 1: VirbiusLLM gateway (zero code)**: The APISIX/Higress `virbius-guard` plugin intercepts `POST /v1/chat/completions`, extracts the user prompt, and calls engine `POST /v1/evaluate` via `virbius-gateway-agent` (:9070); hits return 403. Only requires deploying the APISIX plugin, configuring engine rules, and pointing the Agent's LLM baseUrl at the gateway. For production set `fail_mode="close"` so requests are rejected when the engine is unavailable rather than passed through, preventing bypass.
> - **Mode 2: Application-side code integration (self-developed Agent)**: The application calls engine `POST /v1/evaluate` directly from code (snake_case JSON, fields aligned with `EvaluateRequestDto`), no gateway deployment needed:
>   - **Inbound prompt check** (before user prompt → LLM): `content=user prompt`, `role="input"`; intercept when the returned `effective_action` is `block`/`deny`;
>   - **Output review** (before LLM response → user): `content=LLM generated text`, `role="output"`; on `block`/`deny`, apply the response's `disposal_action` (`reject|omit|redact|warn`; `redact` carries `sanitized_content`).
>   - Response fields: `effective_action` (`allow`/`block`/`deny`/`challenge`/`review`), `max_risk_score`, `reason_code`, `rule_id`, `trace_id`. When the engine is unreachable requests default to pass-through (fail-open); fail-close is implemented by the application catching timeout/connection errors itself.
>
> > Note: `role` is a closed enum `input | output | tool_call` (blank defaults to `tool_call` semantics; any other value returns `400 unsupported_role`). The role selects the side-effect semantics (see the role side-effect matrix in PROTOCOL §2.6.5) but does **not** affect prompt-rule selection — inbound/output reuse the same prompt rule set; engine-side role filtering is not implemented. For streaming output review, integrate the official `virbius-review-sdk` (`wrap_stream` windowing; 429 always fails closed).

### 8.2 Deployment Topology

**Mode A: Sidecar Deployment (Inside K8s Pod, primarily East-West)**

```
┌─── K8s Pod ──────────────────────────────────────────────┐
|                                                          |
|  ┌──────────────┐         ┌──────────────────────────┐   |
|  | Agent        |  MCP    | MCP Proxy (Edge Layer)   |   |
|  |              |──JSON-RPC──> localhost:9090        |   |
|  |              |  stdio  | +-- License Verification  |   |
|  |              | /SSE    | +-- Precheck + Engine Final Judgment |
|  |              |         | +-- Proxy Forwarding (Egress)    |   |
|  └──────────────┘         └─────────────┬────────────┘   |
|  East-West (bypasses Gateway Layer)      |               |
└──────────────────────────────────────────┼───────────────┘
                                           | North-South
                                           v
┌───────────────────────────────────────────────────────────┐
|  MCP Server (Python/Node) (:8080+)                        |
|  +-- Receives tools/call, executes tool logic             |
|  +-- virbius-core (Edge Layer Embedded: precheck + P0 in-process execution) |
|  +-- P2: Landlock + drop caps sandbox                    |
└───────────────────────────────────────────────────────────┘

┌─── Host ──────────────────────────────────────────────────┐
|  Falco DaemonSet (Kernel Layer Bypass)                     |
|  +-- eBPF driver (no plugin fallback)                      |
|  +-- Events -> Redis Audit Stream                          |
└───────────────────────────────────────────────────────────┘

┌─── Cloud Side ────────────────────────────────────────────┐
|  +-- virbius-engine (:8082) — Groovy L3 Final Judgment     |
|  +-- virbius-control (:8080) — Rule Management + Publishing|
|  +-- Redis (:6379) — Session State + Audit Stream          |
|  +-- Database — Rule Persistence                           |
└───────────────────────────────────────────────────────────┘
```

> In Sidecar mode, MCP tool calls go through localhost (East-West), bypassing the Gateway Layer Higress.
> External HTTP requests (curl) are proxied by the Proxy, which validates URL allowlist at the application layer ([§3.5](ARCHITECTURE.md#35-egress-traffic-control)).

**Mode B: Remote Deployment (North-South, Gateway Layer Ingress)**

```
Remote Agent Client
  | MCP / JSON-RPC over HTTPS (North-South)
  v
+----------------------------------------------------------+
|  Higress (:443) — Gateway Layer Ingress Gateway        |
|  +-- TLS Termination                                      |
|  +-- Rate Limiting / Long Connections / SSE Forwarding    |
|  +-- virbius-gateway Lua Plugin (Security Precheck)       |
|  +-- Higress MCP route -> MCP Server Routing              |
+----------------------------------------------------------+
  | allow -> Forward; block -> 403
  v
+----------------------------------------------------------+
|  MCP Server (Python/Node) (:8080+)                       |
|  +-- virbius-core (Edge Layer Precheck + P0 in-process execution) |
|  +-- P2: Landlock + drop caps sandbox                    |
+----------------------------------------------------------+

┌─── Host ──────────────────────────────────────────────────┐
|  Falco DaemonSet (Kernel Layer Bypass)                     |
|  +-- eBPF driver (no plugin fallback)                      |
|  +-- Events -> Redis Audit Stream                          |
└───────────────────────────────────────────────────────────┘

┌─── Cloud Side ────────────────────────────────────────────┐
|  +-- virbius-engine (:8082) — Groovy L3 Final Judgment     |
|  +-- virbius-control (:8080) — Rule Management + Publishing|
|  +-- Redis (:6379) — Session State + Audit Stream          |
|  +-- Database — Rule Persistence                           |
└───────────────────────────────────────────────────────────┘
```

> In remote mode, both Agent MCP calls and external HTTP requests go through the Gateway Layer (North-South).
> The Gateway Layer Higress handles both Ingress (MCP routing) and Egress (external HTTP proxy) responsibilities.

**Mode C: SDK Embedding (In-Process, No Standalone Proxy)**

```
┌─── Agent Process ─────────────────────────────────────────┐
│                                                           │
│  Agent Business Logic                                     │
│    │                                                      │
│    │ 1. Before sending LLM request                        │
│    │    prompt_gateway.enhance(&mut messages, &ctx)       │
│    │    → Constitutional Constraint Injection + PII Input Desensitization |
│    │                                                      │
│    │ 2. Before tool invocation                            │
│    │    precheck(&license, &tool_call)                    │
│    │    → allowlist + JSON Schema Validation + fast_path  │
│    │    → If fast path not hit, call engine for final judgment |
│    │                                                      │
│    │ 3. After tool returns                                │
│    │    output_reviewer.review(&content, &ctx)            │
│    │    → PII Output Desensitization + Credential Leak Detection |
│    v                                                      │
│  virbius-core (Rust library, linked into Agent process)   │
│    +-- License::verify() (Ed25519 JWT)                    │
│    +-- precheck() (allowlist + schema)                    │
│    +-- PromptGateway::enhance() (Constitutional Injection + DLP) |
│    +-- DlpEngine (PII Desensitization, In-Process)        │
│    +-- P2: Landlock + drop caps (Agent can sandbox directly) |
│                                                           │
│  C ABI (virbius_init / virbius_scan / virbius_reload)     │
│  → Callable by Python / Go / Java / Node.js via FFI       │
└──────────────────────────────┬────────────────────────────┘
                               │ HTTP (call engine, optional)
                               v
┌─── Cloud Side ────────────────────────────────────────────┐
│  +-- virbius-engine (:8082) — Groovy L3 Final Judgment     │
│  +-- virbius-control (:8080) — Rule Management + Publishing│
│  +-- Redis (:6379) — Session State + Audit Stream          │
└───────────────────────────────────────────────────────────┘
```

> In SDK mode, security precheck is completed within the Agent process, with no additional process overhead.
> `virbius-core` exports C ABI via Rust FFI (`virbius_init` / `virbius_scan` / `virbius_reload`), callable by non-Rust languages.
> Lacks Gateway Layer (no rate limiting/TLS) and Kernel Layer (no Falco observation); combine with Mode 1 or Mode 2 when needed.

---

### 8.3 Integration Approach Comparison

#### 8.3.1 Three-Mode Panorama

| Dimension | Mode 1: MCP Proxy (Sidecar) | Mode 2: Higress (Remote) | Mode 3: SDK Embedding |
|------|---------------------------|------------------------|----------------|
| **Deployment Form** | Agent + Proxy in same Pod, separate processes | Agent remote, Higress in-cluster | `virbius-core` linked into Agent process |
| **Traffic Direction** | East-West (localhost) | North-South (HTTPS) | No network traffic (in-process calls) |
| **Agent Changes** | **Zero code**——change MCP Server URL | **Zero code**——change MCP Server URL | **Code changes needed**——integrate SDK API |
| **Language Constraints** | None (any MCP Client) | None (any HTTP Client) | Rust native / other languages need C ABI FFI |
| **Protocol** | MCP (JSON-RPC 2.0) | HTTP/HTTPS | Function calls (`precheck()` / `enhance()`) |

#### 8.3.2 Four-Layer Security Coverage

| Layer | Mode 1 (MCP Proxy) | Mode 2 (Higress) | Mode 3 (SDK) |
|------|:------------------:|:------------------:|:------------:|
| **Edge Layer** | ✅ Full pipeline embedded in Proxy | ❌ Remote Agent has no `virbius-core` | ✅ Direct in-process calls |
| **Gateway Layer** | ❌ East-West bypasses Higress | ✅ Higress intercepts North-South | ❌ No gateway |
| **Kernel Layer** | ✅ Falco DaemonSet observation | ❌ Remote Agent not on node | ❌ Agent not on cluster node |
| **Cloud Layer** | ✅ engine final judgment | ✅ engine final judgment | ✅ engine final judgment (optionally skipped) |
| **Coverage Layers** | **3/4** | **2/4** | **2/4 + Edge Layer Depth** |
| **Mitigation** | NetworkPolicy + Edge Layer embedded rate limiting | HTTP blocking + risk accumulation | No Kernel Layer observation, no Gateway Layer rate limiting |

#### 8.3.3 Security Capability Comparison

| Security Capability | Mode 1 (MCP Proxy) | Mode 2 (Higress) | Mode 3 (SDK) |
|---------|:------------------:|:------------------:|:------------:|
| **License Verification** | ✅ `license::verify()` in Proxy | ✅ Higress WASM signature verification | ✅ `License::verify()` in-process |
| **Tool Allowlist** | ✅ `precheck()` | ✅ WASM allowlist | ✅ `precheck()` |
| **JSON Schema Validation** | ✅ `precheck::validate_args()` | ⚠️ Weak WASM implementation | ✅ `precheck::validate_args()` |
| **Fast Path** | ✅ Skip engine, <2ms | ✅ Skip engine | ✅ Skip engine, zero network |
| **engine Final Judgment** | ✅ HTTP call | ✅ HTTP call | ✅ HTTP call (optionally skipped) |
| **Prompt Enhancement** | ⚠️ Requires Agent cooperation | ❌ Not in interception scope | ✅ **Direct in-process** `enhance()` |
| **PII Input Desensitization** | ⚠️ Output only | ❌ Not supported | ✅ Input + Output desensitization |
| **DLP Detection** | ✅ `dlp_engine` in Proxy | ❌ Not supported | ✅ `dlp_engine` in-process |
| **Rate Limiting** | ✅ Fallback rate_limit | ✅ Envoy rate limit + Redis | ❌ Must implement yourself |
| **TLS Encryption** | ❌ localhost does not need TLS | ✅ Higress terminates TLS | N/A (in-process) |
| **Falco Observation** | ✅ syscall/net/file | ❌ | ❌ |
| **Sandbox Isolation (P2)** | ✅ Proxy can posix_spawn + Landlock | ❌ | ✅ Agent can Landlock directly |

> **Unique value of Mode 3**: SDK mode is the only approach that can intercept at the **prompt level**——before the Agent sends an LLM request, `PromptGateway::enhance()` is called in-process for constitutional constraint injection and PII desensitization. Mode 1 and Mode 2 can only intercept `tools/call`, not prompts. If security requirements include prompt injection protection and PII desensitization, SDK mode is the only choice, or Mode 3 must be layered on top of Mode 1/7.

#### 8.3.4 Performance Comparison

| Performance Metric | Mode 1 (MCP Proxy) | Mode 2 (Higress) | Mode 3 (SDK) |
|---------|:------------------:|:------------------:|:------------:|
| **Precheck Latency** | ~1-2ms (including IPC) | ~3-5ms (including HTTP + WASM) | **<0.5ms** (in-memory function call) |
| **Fast Path Latency** | ~2ms | ~5ms | **<0.5ms** |
| **Full-Chain Latency** | ~10-50ms | ~20-50ms | ~10-50ms (engine RPC) |
| **Agent Startup Overhead** | Requires starting Proxy process | No additional process | **None** (library already linked) |
| **Memory Overhead** | Proxy separate process ~20MB | Higress shared | **~0** (shares Agent process memory) |
| **Network Hops** | 1 hop (localhost) | 2 hops (Agent→Higress→MCP) | 0 hops (in-process) + 1 hop (engine) |

#### 8.3.5 Pros and Cons Summary

**Mode 1: MCP Proxy (Sidecar)**

| Pros | Cons |
|------|------|
| Zero code changes for Agent | Extra process overhead (~20MB memory) |
| Complete security pipeline (License + precheck + engine) | Bypasses Gateway Layer, no TLS/global rate limiting |
| Kernel Layer Falco observable | Only available in K8s Sidecar deployment |
| Framework agnostic (any MCP Client) | Prompt enhancement requires Agent cooperation |
| P2 can add sandbox isolation | Egress requires additional NetworkPolicy |

**Mode 2: Higress (Remote)**

| Pros | Cons |
|------|------|
| Zero code changes for Agent | Only 2-layer coverage (Gateway + Cloud) |
| TLS termination + global rate limiting | No Edge Layer protection (no sandbox/in-process precheck) |
| Production-grade gateway (Higress mature and stable) | No Kernel Layer observation (remote Agent not on node) |
| Suitable for remote/SaaS Agent | Weak WASM Schema validation capability |
| Strong Egress control | No prompt enhancement/PII desensitization |

**Mode 3: SDK Embedding**

| Pros | Cons |
|------|------|
| **Lowest latency** (in-process <0.5ms) | **Requires Agent code changes** |
| **Deepest security**——Prompt enhancement + PII desensitization + DLP | No Gateway Layer (no rate limiting/TLS/network isolation) |
| No extra process/IPC overhead | No Kernel Layer observation (Agent not in cluster) |
| Fast path zero network overhead | Language constraints (Rust native / others need FFI) |
| P2 can directly Landlock sandbox | License verification in Agent process (can be tampered) |
| C ABI cross-language (Python/Go/Java/C++) | Rate limiting must be implemented yourself |

#### 8.3.6 Decision Tree

```
Is Agent in K8s cluster?
├── Yes
│   ├── Is Agent self-developed (code modifiable)?
│   │   ├── Yes → Agent language?
│   │   │   ├── Rust → Mode 3 (SDK) ← Zero latency + Deepest security
│   │   │   └── Other → Mode 1 (MCP Proxy) ← Zero code + Kernel Layer observation
│   │   └── No (existing framework) → Mode 1 (MCP Proxy) ← Zero code integration
│   └── Need full four-layer coverage?
│       └── Yes → Mode 1 + Mode 2 combined ← Defense in depth (see §8.4)
└── No (Remote/SaaS)
    ├── Need TLS + rate limiting → Mode 2 (Higress) ← Only choice
    └── Self-developed Agent can modify code → Mode 3 (SDK) ← Edge Layer deep security
                              + Mode 2 (Higress) ← Supplement Gateway Layer capabilities
```

#### 8.3.7 Rule Execution Flow by Mode

> [§6.1](DESIGN.md#61-tool-call-request-path) describes the combined four-layer flow. This section uses a comparison table to illustrate the rule execution differences across the three independent deployment modes.

**Rule Execution Comparison by Mode**

| Rule Type | Mode 1 (Proxy) | Mode 2 (Higress) | Mode 3 (SDK) |
|---------|:---:|:---:|:---:|
| **Edge Layer** | | | |
| Edge keyword rules (`lua-dsl`) | ❌ Proxy doesn't run edge keywords | ❌ | ✅ `engine::scan_once` |
| Edge DLP desensitization | ❌ | ❌ | ✅ `DlpEngine` in-process |
| Prompt enhancement (constitutional injection) | ❌ | ❌ | ✅ SDK only |
| License verification | ✅ In Proxy | ✅ In WASM | ✅ In-process |
| JSON Schema validation | ✅ In Proxy | ❌ WASM Schema weak | ✅ In-process |
| **Gateway Layer** | | | |
| Gateway expression rules (WASM) | ❌ Bypassed | ✅ `virbius-expr` | ❌ |
| Gateway rate limiting | ❌ Bypassed | ✅ Redis INCR | ❌ |
| **Cloud Layer** | | | |
| Cloud Groovy rules | ✅ Engine | ✅ Engine | ✅ Engine (optional) |
| Cloud Prompt rules (1B model) | ✅ Engine | ✅ Engine | ✅ Engine (optional) |
| Prompt injection detection | ✅ Engine | ✅ Engine | ✅ Engine |
| Session Risk | ✅ Engine | ✅ Engine | ✅ Engine |
| Challenge human approval | ✅ Proxy→Engine | ✅ WASM→Engine | ✅ Engine |
| **Kernel Layer** | | | |
| Kernel Falco observation | ✅ DaemonSet | ❌ | ❌ |
| **Other** | | | |
| Output review (PII leak) | ✅ Proxy→Engine | ❌ | ✅ `output_reviewer` |
| P2 sandbox (Landlock) | ✅ MCP Server side | ✅ MCP Server side | ✅ Agent direct sandbox |

> **Combined deployment**: When a single mode cannot meet security requirements, combined deployment is available. Mode 1 + Mode 2 achieves four-layer full coverage (see [§8.4](#84-four-layer-full-coverage-combined-deployment)); Mode 1/2 + Mode 3 supplements Prompt enhancement capability.

---

### 8.4 Four-Layer Full Coverage (Combined Deployment)

#### 8.4.1 Topology

When security requirements demand full coverage of all four layers (Edge, Gateway, Kernel, Cloud), Mode 1 (MCP Proxy Sidecar) and Mode 2 (Higress Ingress) must be combined——remote traffic enters the cluster through the Gateway Layer, reaches the Edge Layer Sidecar Agent Pod, the Kernel Layer observes on the node, and the Cloud Layer provides unified final judgment:

```
Remote Agent (outside cluster)
  │
  │ HTTPS (North-South)
  v
┌─────────────────────────────────────────────────────────────┐
│ [Gateway Layer] Higress (:443) — Ingress Gateway           │
│   +-- TLS Termination                                        │
│   +-- Global Rate Limiting (Envoy rate limit)                │
│   +-- License Signature Verification                         │
│   +-- tool allowlist (WASM)                                   │
│   +-- Forward to Agent Pod in cluster                        │
└────────────────────────┬────────────────────────────────────┘
                         │ ClusterIP (in-cluster)
                         v
┌─── K8s Pod ──────────────────────────────────────────────────┐
│                                                              │
│  ┌──────────────┐         ┌──────────────────────────┐       │
│  | Agent        |  MCP    | [Edge Layer] MCP Proxy   |       │
│  |              |──JSON-RPC──> localhost:9090        |       │
│  |              |  stdio  | +-- License Verification  |       │
│  |              | /SSE    | +-- precheck (schema)     |       │
│  |              |         | +-- engine Final Judgment  |       │
│  |              |         | +-- Prompt Enhancement (optional) |       │
│  └──────────────┘         └─────────────┬────────────┘       │
│  East-West (localhost)                   |                   │
└──────────────────────────────────────────┼───────────────────┘
                                           │
                     ┌─────────────────────┐│
                     │ [Kernel Layer] Falco││
                     │ DaemonSet           ││
                     │ +-- eBPF Observation││
                     │ +-- syscall/net/file││
                     │ +-- audit stream    ││
                     └─────────────────────┘│
                                            v
┌─── Cloud Side ───────────────────────────────────────────────┐
│ [Cloud Layer]                                                │
│   +-- virbius-engine (:8082) — Groovy L3 Final Judgment      │
│   +-- virbius-control (:8080) — Rule Management + Publishing │
│   +-- Redis (:6379) — Session State + Audit Stream           │
└──────────────────────────────────────────────────────────────┘
                                           │
                                           v
                                      MCP Server
```

#### 8.4.2 Advantages

| Advantage | Description |
|------|------|
| **Complete Defense in Depth** | Four layers operate independently; if any layer is bypassed, others still provide coverage. Edge Layer precheck fails → Gateway Layer allowlist blocks; Gateway Layer bypassed → Edge Layer License verification still active; Kernel Layer observes anomalies → risk_score increased → Cloud Layer blocks subsequent requests |
| **North-South/East-West Separation** | Remote traffic goes through Gateway Layer (TLS/rate limiting/Ingress security), in-cluster traffic goes through Edge Layer (deep precheck/schema/Prompt enhancement), each serves its purpose |
| **Runtime Observation** | Kernel Layer Falco provides syscall/net/file-level observation, capturing anomalies undetectable by both Edge and Gateway Layers (e.g., container escape, SSRF internal network scanning) |
| **Single Policy Source** | All four layers share `virbius-control` as the single source of truth for policies, share Redis for session/risk_score storage, ensuring consistent policies and interoperable risk scores |
| **Graceful Degradation** | When eBPF is unavailable, Kernel Layer observation is Disabled (plugin mode removed in Plan A) while Edge Layer precheck + License remain active; when engine is unavailable, Edge Layer fail-open/fail-closed; when Gateway Layer is unavailable, Edge Layer independently provides fallback |
| **Sandbox Isolation (P2)** | Edge Layer Proxy can posix_spawn + Landlock; Gateway and Cloud Layers do not participate in execution, providing clear isolation boundaries |
| **Full-Chain Auditing** | Gateway Layer records HTTP-level audit, Edge Layer records MCP protocol-level audit, Kernel Layer records syscall-level audit, Cloud Layer aggregates final judgment audit——four-layer complementary auditing with no blind spots |

#### 8.4.3 Disadvantages

| Disadvantage | Description | Mitigation |
|------|------|---------|
| **Double Interception Latency** | Remote traffic goes through Gateway Layer → Edge Layer double security checks, full-chain latency ~60-100ms (including two engine calls) | Divide by capability: Gateway Layer degrades to TLS + rate limiting + routing, security final judgment converges to Edge Layer (see §8.4.4) |
| **High Deployment Complexity** | Requires simultaneous deployment of Higress + MCP Proxy + Falco DaemonSet + Engine + Control + Redis, 6+ components | Provide Helm Chart for one-click deployment; non-production environments can deploy only Edge Layer + Cloud Layer |
| **Double Engine Calls** | Gateway Layer and Edge Layer each call `/v1/evaluate` once, doubling engine load | Configure `evaluate=false` on Gateway Layer; only Edge Layer calls engine |
| **Double Counter Conflict** | Gateway Layer WASM Redis and Edge Layer Fallback rate limiting each count once, causing rate_limit semantics confusion | Unify rate limiting to Gateway Layer; Edge Layer removes Fallback rate_limit |
| **Operations Cost** | Four-layer components require independent monitoring, logging, and alerting; fault diagnosis requires cross-layer trace_id correlation | Unified trace_id connects four-layer auditing; operations console provides cross-layer call chain visualization |
| **Resource Overhead** | Additional ~20MB per Agent Pod (Proxy) + ~50MB per node (Falco) + Higress cluster + Engine cluster | Lightweight scenarios can degrade to 2 layers (Edge + Cloud) |
| **Tandem Configuration Risk** | Behavior unpredictable when Gateway and Edge Layer policies are inconsistent (e.g., Gateway allows but Edge denies) | Single policy source (`virbius-control` unified distribution); Gateway Layer allowlist ⊆ Edge Layer allowlist (Edge is stricter) |

#### 8.4.4 Tandem Division of Responsibilities

To avoid latency doubling and conflicts caused by double interception, divide responsibilities by capability when deploying in combination:

| Security Capability | Responsible Party | The Other Party's Behavior | Reason |
|---------|---------|-----------|------|
| TLS Termination | Gateway Layer Higress | Edge Layer Proxy does not do TLS (internal HTTP) | TLS is a network boundary capability |
| Global Rate Limiting | Gateway Layer Higress (Envoy rate limit) | Edge Layer removes Fallback rate_limit | Rate limiting is a network boundary capability |
| tool allowlist | **Only once**——Edge Layer Proxy | Gateway Layer skips allowlist | Edge Layer schema validation is more complete |
| Counter | **Only once**——Gateway Layer Higress | Edge Layer does not check Redis count | Avoid double counting |
| License Verification | Gateway Layer Higress (ingress) + Edge Layer Proxy (deep) | Both layers do it | Gateway Layer verifies signature, Edge Layer verifies allowed_tools |
| JSON Schema Validation | Edge Layer MCP Proxy | Gateway Layer does not do it | WASM Schema library is weak, Rust implementation is complete |
| engine Final Judgment | **Only once**——Edge Layer MCP Proxy | Gateway Layer configures `evaluate=false` | Avoid double engine calls |
| Fast Path | Edge Layer MCP Proxy | Gateway Layer does not determine fast path | Edge Layer has SessionStateCache |
| Auditing | Both do it (different dimensions) | Gateway Layer records HTTP layer, Edge Layer records MCP protocol layer | Complementary auditing |
| Kernel Layer Observation | Falco DaemonSet (bypass) | — | Non-intrusive bypass |
| Sandbox Isolation (P2) | Edge Layer Proxy | — | Gateway Layer does not participate in execution |

Gateway Layer Higress effective JSON configuration:

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

#### 8.4.5 Applicable Scenarios

| Scenario | Recommended for Combined Deployment | Reason |
|------|:---------------:|------|
| **Finance/Healthcare etc. Strong Compliance** | ✅ Recommended | Regulatory requires defense in depth + full-chain auditing |
| **High-Security SaaS Platform** | ✅ Recommended | Multi-tenant isolation + TLS + rate limiting + deep precheck |
| **Internal Tool Agent** | ❌ Overkill | Edge Layer + Cloud Layer suffice, no Gateway Layer needed |
| **Dev/Test Environment** | ❌ Overkill | SDK Mode (Mode 3) fastest iteration |
| **Existing Agent In-Cluster Deployment** | ⚠️ Optional | Mode 1 already covers 3 layers, add Gateway Layer as needed |

### 8.5 K8s Helm Deployment

One Helm chart installs the range, edge, cloud, control, operator login (virbius-auth) and MySQL / Kafka / Redis / Ollama (including the VirbiusGuard model import) into an **existing cluster**. It does not install the Ingress Controller, Higress, or Falco.

Chart: `deploy/helm/virbius`. Scripts: `deploy/scripts/k8s-build-push.sh`, `deploy/scripts/k8s-deploy.sh`.

#### Prerequisites

- An image registry you can `docker login` to
- An Ingress Controller already installed in the cluster (default class `nginx`)
- `docker`, `helm`, `kubectl` on the local machine
- A local values file with the secrets filled in (do not commit it to Git)

```bash
cp deploy/helm/virbius/values.example.yaml deploy/helm/virbius/values-prod.yaml
# edit values-prod.yaml: global.imageRegistry, secrets.*, ingress.hosts, imagePullSecrets
```

#### One-shot Build, Push, Install

```bash
./deploy/scripts/k8s-deploy.sh \
  --registry registry.example.com/virbius \
  --tag v1.0.3 \
  --namespace virbius \
  --values deploy/helm/virbius/values-prod.yaml
```

Build and push images only:

```bash
./deploy/scripts/k8s-build-push.sh --registry registry.example.com/virbius --tag v1.0.3
```

Images already in the registry, install the chart only:

```bash
./deploy/scripts/k8s-deploy.sh --skip-build \
  --registry registry.example.com/virbius \
  --tag v1.0.3 \
  --values deploy/helm/virbius/values-prod.yaml
```

#### Four Independent Ingress Hosts (Default)

| Side | Host | Health Check |
|----|------|----------|
| Range | `range.virbius.example.com` | `GET /` |
| Control | `control.virbius.example.com` | `GET /api/v1/health` (liveness); the readiness probe uses `GET /api/v1/ready` (requires the Kafka consumers to have joined and to hold partitions; Helm does this by default) |
| Cloud | `engine.virbius.example.com` | only `/v1` is routed externally; liveness `GET /admin/health` and readiness `GET /admin/ready` (Redis reachable and the cache-reload consumer group established) are **pod-internal** — probes hit the pod IP, not the Ingress |
| Edge | `proxy.virbius.example.com` | `GET /health` |

Point these four names at the Ingress external IP. TLS is off by default; enable `ingress.tls` in values. `/login` on the control host routes to `virbius-auth` (same domain as `/ui/`, so the login cookie is not lost). Default account `admin` / `secrets.authBootstrapPassword`.

The engine host forwards **only `/v1`**: `/actuator/*` (metrics, health) and `/admin/*` (readiness detail, cache reload) are not reachable through the Ingress. Probes target the pod IP, so nothing outside the cluster needs those paths; if you must scrape `/actuator/prometheus` externally, do it from inside the cluster (or add an explicit NetworkPolicy-guarded Ingress rule) rather than re-opening `/`.

In-cluster DNS: `virbius-control:8080`, `virbius-auth:8083`, `virbius-engine:8082`, `virbius-mcp-proxy:9090`, `virbius-demo:8000` (SSE `:9091`). Auth uses MySQL (same database as Control, separate Flyway history table `auth_schema_history`); the JWT key lives on a PVC, so do not scale replicas. Setting `auth.enabled=false` skips deploying Auth, and Control does not enable operator JWT either.

#### Scaling to multiple replicas (HA)

**Multiple replicas (HA)**: engine/control are statelessness-hardened and can safely be scaled. Key points:

1. **engine** — Helm injects a unique `VIRBIUS_INSTANCE_ID` per replica (from `metadata.name`), driving per-instance Redis consumer groups (`engine-cache-*`/`engine-deploy-*`, broadcast semantics). Do **not** give several replicas the same `VIRBIUS_INSTANCE_ID`, or the groups merge and broadcast messages get split. The chart also applies a soft `topologySpreadConstraints` rule (`maxSkew: 1`, `kubernetes.io/hostname`, `ScheduleAnyway`), so replicas land on different nodes when possible while single-node clusters still schedule.
2. **control** — scaling requires moving its local files to shared NFS first: edge manifests and audit exports are written to each replica's local `VIRBIUS_DATA_DIR` (an RWO PVC in Helm); unshared, replicas diverge — after a publish another replica keeps serving the **old manifest** (HTTP 200 with the old sha, silently stale), and an export fails to download from any replica other than its creator. How: mount the **same NFS at the same absolute path** as `VIRBIUS_DATA_DIR` on every replica (writable by the container uid); point `control.auditAllowPath` (renders `VIRBIUS_AUDIT_ALLOW_PATH`) at a **per-replica local** path (logback rolling logs must not be shared); tune NFS `actimeo` down or use `noac` when the stale-read window matters. Helm guard: `control.replicaCount > 1` without `control.sharedStorageEnabled=true` fails outright (setting it true asserts the shared storage above is in place); the follow-up file-externalization design (content into the DB) removes the NFS dependency once it lands.
3. **rate limiting** — `/v1/evaluate` limiting is per-replica (see §10.2).

The range agent checkpoint still launches `virbius-mcp-proxy` via stdio inside the pod. The edge host on the Ingress is the TCP entry point for MCP clients outside the cluster; its upstream defaults to `http://virbius-demo:9091`.

**Switching an existing install to HA (PVC `accessMode` is immutable)**: the guard above requires `control.persistence.accessMode=ReadWriteMany`, but Kubernetes does not allow patching `accessModes` on an existing PVC — `helm upgrade` fails with `spec is immutable`. Plan for it on first install (pick an RWX StorageClass) or migrate the volume:

1. `kubectl scale deploy/virbius-control -n virbius --replicas=0`
2. Copy `/data` **off the old volume first** (`kubectl cp virbius-control-data:/data ./control-data-backup` via a debug pod). `helm uninstall` keeps PVCs, but an explicit `kubectl delete pvc` honours the StorageClass reclaim policy and can destroy the data.
3. `kubectl delete pvc virbius-control-data -n virbius`, then `helm upgrade` with `control.persistence.accessMode=ReadWriteMany` (+ `storageClass` on your RWX class) and `control.sharedStorageEnabled=true`; restore the backed-up `/data` into `virbius-control-data`.
4. Set `control.auditAllowPath` to a per-replica local path before scaling out (see above), then `kubectl scale deploy/virbius-control --replicas=2`.

In-cluster Ollama is enabled by default: a Job downloads the VirbiusGuard GGUF from HuggingFace (V2.0 F16, ~1.5GB, cached on a PVC, not re-downloaded on upgrade) and runs `ollama create virbiusguard`. Leave `engine.promptLlm.baseUrl` empty to point at `http://{release}-ollama:11434`. In mainland China, switch `ollama.ggufUrl` to ModelScope:

`https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/gguf/virbiusguard-v2.0-f16.gguf`

When `ollama.enabled=false` you must fill in `engine.promptLlm.baseUrl`, otherwise `helm` fails outright. When disabling in-cluster MySQL / Kafka / Redis, fill in `mysql.jdbcUrl`, `kafka.bootstrapServers`, `redis.url` respectively, and engine/control will no longer wait on the in-cluster Services. GPU is off by default; set `ollama.gpu.enabled=true` on machines with NVIDIA devices.

`helm uninstall virbius -n virbius` does **not** delete the PVCs.

### 8.6 On-Prem Three-Host Deployment (customer site, no K8s)

For customer-site deployments where **MySQL / Kafka / Redis / vLLM come from the customer's shared platform** and only the VirbiusAgent components (control + auth, engine, mcp-proxy) are installed on three hosts, use the ready-made per-host compose assets and the field runbook under [`deploy/onprem/`](deploy/onprem/README.md) ([中文现场版](deploy/onprem/README.zh.md)): host roles, cross-host firewall/port matrix, offline image tar bundles (`deploy/scripts/offline-bundle.sh`), TLS termination, prompt-model options (local Ollama GGUF via hf-mirror/ModelScope, or customer vLLM with safetensors weights), boot order, backup and the on-site acceptance checklist. The same directory works on K8s (external-infra values snippet in the runbook appendix).

---

## 9. P0 Credential & Datastore Security Baseline

Applies to `docker-compose.prod.yml` and the Helm chart (prod/staging profiles).
The control plane runs `SecretPolicyValidator` at startup and **fails fast**
on any violation below.

### 9.1 Required environment variables

| Variable | Scope | Notes |
|---|---|---|
| `VIRBIUS_LICENSE_MASTER_KEY` | always, every profile | ≥32 chars; blanks/known defaults fail startup |
| `VIRBIUS_API_KEY_PEPPER` | when API-key auth is on (prod default) | ≥32 chars; HMAC pepper for tenant API keys |
| `REDIS_PASSWORD` | compose prod / Helm (`secrets.redisPassword`) | embedded in `VIRBIUS_REDIS_URL` as `redis://:<password>@host:port` |
| `KAFKA_SASL_USERNAME` / `KAFKA_SASL_PASSWORD` | prod | SCRAM-SHA-256 credentials (`KAFKA_SECURITY_PROTOCOL=SASL_PLAINTEXT`) |
| `MYSQL_PASSWORD` / `MYSQL_ROOT_PASSWORD` | prod | |
| `VIRBIUS_STRICT_SECRETS` | optional | `false` only for local prod-profile dev setups (e.g. `.vscode/launch.json`) |

`run-local.sh` auto-generates a dev master key into the gitignored data dir.

### 9.2 MySQL TLS (self-signed, compose-generated)

`docker-compose.prod.yml` runs a one-shot `cert-gen` service
(`deploy/certs/gen-mysql-certs.sh`) that generates a CA + server certificate
(SAN `DNS:mysql`) into the `mysql-certs` volume. Control connects with
`sslMode=VERIFY_IDENTITY&serverSslCert=/certs/ca.pem`. In Helm, set
`mysql.tls.enabled=true` and provide `mysql-server-cert` / `mysql-server-key`
/ `mysql-ca` (values or `mysql.tls.existingSecret`).

**Rotation runbook**: delete `server-cert.pem`/`server-key.pem` (keep the CA),
restart `cert-gen` + `mysql`, then roll `virbius-control` (CA unchanged →
clients keep working). Full CA rotation requires rolling both together.
Track expiry (default 825 days; alert 30 days ahead).

### 9.3 Kafka SASL (SCRAM-SHA-256 + PLAINTEXT) — accepted risk

Clients authenticate with SCRAM (challenge-response; the password never
crosses the wire in clear). The client listener transport is PLAINTEXT, which
means **audit/trace payloads are unencrypted inside the cluster network**.
Accepted for P0 with these conditions:

1. Kafka reachable only inside the trust domain (NetworkPolicy / compose
   internal network; the loopback `INTERNAL` listener is for broker internals
   and SCRAM provisioning only).
2. Payload redaction stays effective (no credentials in audit events by design).
3. **Upgrade triggers** (whichever comes first): Kafka spans hosts/segments,
   or a compliance audit requires transport encryption → switch to
   `SASL_SSL` (P1; reuse the MySQL CA or a dedicated one).

### 9.4 Auth defaults

- `VIRBIUS_SECURITY_API_KEY_ENABLED` is the canonical env name
  (`VIRBIUS_API_KEY_ENABLED` still works, deprecated).
- Prod/staging start with API-key auth **on**; running with every auth
  mechanism disabled fails startup (the implicit dev admin principal is
  dev-profile only).
- `virbius-auth` is the **only implemented operator login today** (local password + self-signed JWT); the dev compose keeps a commented `admin/admin` convenience default (test environments only — never set these in non-dev deployments). Production should run auth as a **single replica** (its signing key is a per-pod file — see §8.5) or keep the console on an isolated network.
- **SSO is not implemented yet**: there is no SSO code in the repo (`virbius-auth` contains none either). Corporate OAuth2/OIDC integration is design-finalized, pending implementation — see `SSO_INTEGRATION.md` (v0.2, auth-broker route).

### 9.5 Upgrade notes

- Existing API keys keep working: legacy SHA-256 rows upgrade to
  peppered HMAC on first use (`hash_algo` column, Flyway `V8`).
- Existing `private_key_enc` rows decrypt via the legacy derivation;
  re-issued keys use the `v1:` HKDF envelope.
- Legacy local dev databases may set `virbius.license.allow-insecure-dev-key=true`
  (**dev profile only**) to keep booting with a weak master key while rotating.
- **Evaluate-token issuer de-duplication (`V19`)** — upgrading a database that
  carries more than one `active` issuer row collapses them to a single row
  (`active_guard` + unique index); the keeper is the row with the earliest
  `created_at` (tie-break: smallest `kid`). Tokens minted by a dropped issuer stop
  verifying, so callers holding one see `401` until they re-exchange — bounded by
  the token TTL (`virbius.evaluate-token.ttl-seconds`, default `900` = 15 min).
  If your callers do not re-exchange on a `401` automatically, force one right
  before the upgrade (or drain past the TTL) to avoid a 15-minute denial window.
  Dev databases are rebuilt from `db/schema.sql`; see §10.1.

### 9.6 Delegated credential plane (v2.5) deployment & operations

See [PROTOCOL.md](PROTOCOL.md) §2.6.1 "Egress Delegated Credential Injection" for the full spec. Highlights:

**Control side**:

| Env var | Description |
|---------|------|
| `VIRBIUS_DELEGATED_MASTER_KEY` | ≥32B, separated from the license master key (domain separation), fail-fast |
| `VIRBIUS_REDIS_URL` | Snapshot publish channel (reuses the existing one) |

- Migration: `V11__delegated_cred_plane.sql` (credentials single table). (Post-delete/revoke self-heal is handled by Control's 60s union reconcile — no extra state table.) **Builds must be clean**: the stale slice artifact `target/classes/db/migration/V10__delegated_creds.sql` conflicts with the new migration; environments that applied the old V10 run `flyway repair` + manually DROP the three legacy tables.
- Issuance flow: `POST /api/v1/admin/tenants/{t}/delegated-credentials` (handle/host/header_name/header_prefix/value) → `POST /kek/reveal` to obtain the KEK once.

**Proxy side** (`[delegated]` config section, all overridable via `VIRBIUS_DELEGATED_*` env vars; not hot-reloadable — restart to change):

| Key | Default | Description |
|----|------|------|
| `enabled` | `false` | Master switch; off = non-local egress byte-identical + local-exec unrestricted |
| `tenant_id` | — | Deploy-pinned tenant (trim+lowercase; empty/"default" refuses startup) |
| `kek` / `kek_file` | — | Tenant KEK (b64 32B); **prefer a 0400 file**; never in argv/logs/shell history |
| `redis_url` | audit fallback | Snapshot polling channel |
| `poll_interval_secs` | `30` | Must be < ttl |
| `ttl_secs` | `300` | Continuous failure beyond TTL → clear vault → cold (injection stops) |
| `max_body_mb` | `4` | Hit-branch response buffer cap |
| `allow_external_bind` | `false` | Explicit waiver for non-loopback binds (startup warn + audit) |

> Compile-time test seam: only a build with the `cargo` feature `test-harness` reads `VIRBIUS_DELEGATED_TEST_RELAX_ENDPOINT` (relaxes the https+443 gate for full-chain tests). **Never enable it in a release build**; a production binary cannot disable the gate via any runtime variable.

**Go-live checklist**:

1. `VIRBIUS_EGRESS_HOSTS` must contain every credential host (vault apply alerts `host_not_in_allowlist` for misses).
2. **LLM traffic must not enter the vault** (no credentials for llm-proxy / LLM vendor domains): the phase-1 hit branch has no streaming (SSE buffers up to the cap and is then denied).
3. Startup premise check: binds must be stdio/loopback (both families + hostname resolution), otherwise startup is refused — this is the only local enforcement of the D1⑥ "tenant-only reachable" premise.
4. Revocation/deletion latency: effective on the next snapshot poll (≤30s); a Control-side authoritative clear (rows=[]) clears the proxy vault → cold. **Deleting the last row whose publish failed** (e.g. a Redis blip): the 60s union reconcile (DB tenants with rows ∪ tenants still holding a Redis key) re-publishes an empty doc → the proxy clears its vault (the deleted credential no longer lingers indefinitely).
5. Alerts: `tracing::error` (`delegated_cred_alert{code,state}`, 60s throttle) is always available, independent of Kafka; watch `snapshot_key_missing`, `snapshot_unreadable_rows`, `host_not_in_allowlist`, `ttl_breach_cleared`, `tenant_mismatch`, `hit_credential_echo` (upstream echoed the credential).
6. Poll backoff: read failures back off exponentially `30s → 60s → … → 600s` (10min cap, ±10% jitter) and reset on success; the **TTL clear check runs every loop**, so backoff never weakens the offline fallback.
7. KEK delivery: `POST /kek/reveal` responds with `Cache-Control: no-store` + `Pragma: no-cache` (no intermediate caching); Control logs no request/response bodies, so the KEK never reaches the access log — still land it in a 0400 file, never echo it through a terminal/CI log.

---

## 10. Audit Integrity / Output Safety / Dynamic Risk (A/B/C)

This section covers the datastore, configuration and ops actions for the evaluation outline's A (audit logging, 1.2), B (output safety, 1.5) and C (dynamic risk, 3.1/3.2).

### 10.1 New database migrations (Flyway, prod/staging)

| Migration | Contents |
|---|---|
| `V13__trace_result_preview.sql` | `tb_agent_trace.tool_result_preview` (tool output preview, so decision reconstruction can show what the tool returned) |
| `V14__audit_integrity_retention_export.sql` | per-session trace hash-chain columns (`chain_seq`/`prev_hash`/`curr_hash`) + `tb_trace_chain_state`; daily anchor table `tb_audit_daily_anchor`; retention policy table `tb_tenant_retention_policy`; archive mirrors `tb_audit_events_archive`/`tb_agent_trace_archive`; export ledger `tb_audit_export_job` |
| `V15__alerts_and_session_checkpoints.sql` | alerts `tb_alert`, session checkpoints `tb_session_checkpoint` |
| `V16__evaluate_token_auth.sql` | evaluate-token auth: `tb_evaluate_token_issuers`, `tb_evaluate_tokens`; audit + archive gain `agent_aid`/`app_id`/`subject_source`/`token_iss`; `tb_license_revocations` gains `agent_aid`/`expires_at` |
| `V17__proxy_policy.sql` | `tb_proxy_policy` (previously SQLite-only; the proxy-policy admin API failed with "table doesn't exist" on MySQL) |
| `V18__trace_tool_args.sql` | `tb_agent_trace.tool_args` + `tb_agent_trace_archive.tool_args` (previously SQLite-only; trace ingest failed on MySQL) |
| `V19__evaluate_token_issuer_unique.sql` | single-active issuer (multi-replica): `tb_evaluate_token_issuers` gains a generated column `active_guard` + a unique index, so storage allows at most one `active` issuer; the migration first de-duplicates existing rows (keeps the earliest, tie-broken by smallest kid) |
| `virbius-auth` `V1__baseline.sql` | auth MySQL baseline (`tb_operator_user`, `tb_auth_code`); prod uses Flyway with a distinct history table `auth_schema_history` |

A `SchemaDialectParityTest` (in both `virbius-control` and `virbius-auth`) fails the build if `db/schema.sql` (SQLite/dev) and `db/migration/*.sql` (MySQL/prod) drift in tables or columns.

Dev disables Flyway and builds from `db/schema.sql`: **an older dev database lacks these new columns/tables**, so rebuild with `VIRBIUS_REBUILD_DB=1 ./scripts/run-local.sh` (deletes the DB and `FLUSHDB`). A running control against an old DB fails at index creation (V19 `active_guard`) — that failure is the "rebuild needed" signal.

### 10.2 New configuration keys

| Key | Default | Description |
|---|---|---|
| `virbius.admin.ready-latch` (engine) | `true` | once `/admin/ready` has been observed ready, latch it so a transient Redis blip cannot pull every replica from the Service; `false` re-evaluates on each probe (a Redis outage then flips the pod NotReady — stronger fail-closed posture, at the cost of mass eviction on a blip) |
| `virbius.audit.hash-chain.enabled` | `true` | audit-event hash chain |
| `virbius.audit.hash-chain.verify-enabled` | `true` | scheduled full-chain verification |
| `virbius.audit.hash-chain.verify-interval-ms` | `3600000` | verification interval (1h) |
| `virbius.audit.hash-chain.verify-batch-size` | `10000` | rows per verification batch |
| `virbius.audit.daily-anchor.enabled` | `true` | daily Merkle anchor sealing |
| `virbius.audit.retention.enabled` | `true` | retention archival job |
| `virbius.audit.export.key` | empty | AES-256-GCM key for export artifacts (base64, must decode to 32B); when set, exports are always encrypted (the plaintext flag is ignored) |
| `virbius.audit.export.allow-plaintext` | `true` | when no key is configured, write a plaintext JSONL export (default). Set `false` for fail-closed (no key → job lands `failed`) |
| `virbius.alert.risk-threshold` | `80` | alert generation risk threshold (and action must be block/deny/challenge); **alerts aggregate per answer (`trace_id`)** — the window events of one blocked streaming answer open a single alert |
| `virbius.output-review.action-map` | `PII=redact,PROMPT_INJECTION=omit,OUTPUT_CREDENTIAL_LEAK=redact` | reason-code→action (`reject|omit|redact|warn`) map; unlisted codes fall back to keyword matching — **resolved once in the engine, identical for every caller** |
| `virbius.evaluate.rate-limit.enabled` | `true` | `/v1/evaluate` rate limiting (abuse bound for the unauthenticated endpoint) |
| `virbius.evaluate.rate-limit.per-session-per-min` | `60` | answers/requests per session per minute (streaming windows share one unit per trace) |
| `virbius.evaluate.rate-limit.per-ip-per-min` | `600` | total calls per IP per minute (backstop; also applies pre-auth, covering 401 traffic) |
| `virbius.evaluate.rate-limit.per-agent-per-min` | `600` | per verified `agent_aid` per minute (**backstop** above the session quota; prevents bypass by rotating sessions; ≈ sessions×60) |
| `virbius.evaluate.rate-limit.max-windows-per-trace` | `64` | per-trace streaming window cap (bounds a bogus trace streaming forever) |
| `virbius.evaluate.auth.enabled` | `true` | caller authentication. When on, `/v1/evaluate*` requires a short token; **401/403 fail-closed** |
| `virbius.evaluate.auth.bootstrap-pem-file` | empty | **N1 bootstrap trust anchor**: path to control's platform public PEM (baked at deploy). Auth on + no anchor and no Redis issuer → **the engine refuses to start** |
| `virbius.evaluate.auth.bootstrap-kid` | `bootstrap` | anchor key id |
| `virbius.evaluate.auth.issuer-redis-key` | `virbius:engine:issuers` | issuer set (control republishes idempotently on boot) + 30s refresh |
| `virbius.evaluate.auth.revocation-redis-key` | `virbius:engine:revocations` | revocation snapshot (boot load + 30s reconciliation) |
| `virbius.evaluate.auth.revocation-channel` | `virbius:license:revoked` | instant revocation push channel |
| `virbius.evaluate.auth.verify-cache-ttl-seconds` | `900` | signature-verify cache (**revocation is NOT cached — checked every request**) |
| `virbius.evaluate-token.ttl-seconds` (control) | `900` | short-token TTL |
| `virbius.evaluate-token.ledger-retention-hours` (control) | `24` | issuance-ledger (`tb_evaluate_tokens`) retention; enables jti-level revocation |
| `virbius.evaluate-token.exchange.rate-limit-per-license-per-min` (control) | `10` | exchange-endpoint rate limit |
| `auth.control_base_url` (proxy) | empty | non-empty enables proxy-side exchange (once per session + proactive refresh) |
| `virbius.review.input-risk-dedup-ttl-ms` | `600000` | input-review risk dedup window (once per trace, 10 min) |
| `virbius.prompt-judge.default-backend` (engine) | `neohorse-4b` | judge backend for non-built-in `runtime=prompt` rule ids; the ten `prompt-*` category rules keep the guard-LLM path |
| `virbius.prompt-judge.timeout-ms` (engine) | `2000` | per-batch judge timeout; a timeout follows the rule's `on_error` policy |
| `virbius.prompt-judge.backends.<id>.base-url` (engine) | `http://127.0.0.1:8091` (`neohorse-4b`) | judge backend endpoint (`VIRBIUS_NEOHORSE_URL` for the default backend) |
| `virbius.prompt-judge.max-rules-per-batch` (engine) | `8` | rules per judge call; the effective size is `min(backend-declared capability, per-backend override or this value)` |
| `virbius.prompt-judge.backends.<id>.max-rules-per-batch` (engine) | `0` = inherit | per-backend override; `neohorse-4b` = 1 (declared single-question), `startlux-4b` = 8, `laya-zh` = 1 |
| `virbius.async-action.enabled` (engine) | `true` | async action delivery switch; `false` only counts `unknown/skipped` and never calls out |
| `virbius.async-action.delivery-threads` (engine) | `2` | dedicated delivery threads (isolated from the evaluation pool) |
| `virbius.async-action.delivery-queue-max` (engine) | `1000` | delivery queue bound; a full queue drops the action and counts `skipped` (never back-pressures evaluation) |
| `virbius.async-action.content-max-chars` (engine) | `2000` | render cap for `{{content}}` |
| `virbius.async-action.field-max-chars` (engine) | `500` | render cap for other placeholders; `{{content_preview}}` is fixed at 512 |
| `virbius.async-action.webhook.timeout-ms` (engine) | `5000` | webhook connect+read budget; non-2xx counts `failure` |
| `virbius.async-action.webhook.allowed-hosts` (engine) | empty | comma-separated `host[:port]`; empty = allow all; redirects are never followed |
| `virbius.async-action.kafka.topic` (engine) | `virbius-rule-actions` | default Kafka topic (env `VIRBIUS_ASYNC_KAFKA_TOPIC`); pre-create it when the broker disables auto-creation |
| `virbius.async-action.kafka.max-block-ms` (engine) | `1000` | `send()` metadata wait bound; both sync throws and callback failures count `failure` |
| `virbius.chain-patterns.enforce` | `false` | temporal chain-pattern detection: `false`=dry-run (audit only), `true`=enforce |
| `virbius.retry-escape.enforce` | `false` | retry-escape detection likewise (dry-run by default) |
| `VIRBIUS_JDBC_INIT_SQL` (control/auth · prod/staging) | `SET time_zone = '+00:00'` | per-connection init SQL; pins MySQL/MariaDB sessions to UTC so `NOW()`/`CURRENT_TIMESTAMP` match the ISO-UTC strings the app writes (SQLite is already UTC) |

> These keys currently get their defaults from code (`@Value`); `application*.yml` does not declare them. Override with a startup argument or the same-named `VIRBIUS_*` env var. Exception: `virbius.prompt-judge.*` and `virbius.async-action.*` are declared in the engine `application.yml`.
> **Async actions are at-most-once**: no retries, no outbox; failures are logged (target `scheme://host` only) and counted in `virbius.async_action{type,result}` (Prometheus `virbius_async_action_total`); delivery never affects the decision. Async rules must be `intent=allow`. Control validates `async_action_config` on upsert and re-validates at publish (an invalid config is dropped with a warning while the rule's enforcement is kept). Webhook host allow-listing is enforced by the engine at runtime; Kafka topics must be pre-created.
> **Multi-replica semantics (rate limits)**: the `/v1/evaluate` limiter and the control evaluate-token exchange limit (`virbius.evaluate-token.exchange.rate-limit-per-license-per-min`) both count **within each process** (best-effort abuse bounds, not exact quotas). With N replicas the values above are **per-replica**; the effective global quota = configured value × replica count. For exact global quotas, divide the config by the replica count first, or adopt a Redis-backed limiter.
> **Operational env vars (not in the table above)**: `VIRBIUS_DATA_DIR` (local file-state root: edge manifests, audit exports, the allow log) and `VIRBIUS_AUDIT_ALLOW_PATH` (allow-audit log path, default `${virbius.data-dir}/audit-allow.jsonl`; Helm renders it from `control.auditAllowPath`); multi-replica handling in §8.5.

### 10.2.1 Caller-auth operations (P0-min)

- **Trust-anchor delivery**: bake control's platform public PEM into the engine via `virbius.evaluate.auth.bootstrap-pem-file` (recommended), and/or let control publish the issuer set to Redis (`virbius:engine:issuers`) for enrichment. **With auth on, if neither exists the engine refuses to start (fail-fast — no silent unauthenticated mode).**
- **Boot order**: Redis → control (publishes issuers/revocations) → engine. If Redis loses the issuer hash, control's republish endpoint rebuilds it idempotently (or restart control).
- **`control` becomes a runtime dependency (proxy path)**: the proxy exchanges the license once per **session** (not per request); a control outage blocks **new** sessions and existing ones fall to 401 after the token TTL. Prefer multi-instance control + weigh the short-token TTL (default 15min).
- **Revocation latency**: sub-second normally (channel + per-request check); only a broken channel degrades the worst case to the token TTL.
- **Residual risk (explicitly registered)**: engine **unreachable → caller `fail_open` (unreviewed content passes)**, whereas **auth rejected 401/403 → `fail_closed` (blocked)**. Two opposite postures for "engine-side trouble"; the "engine unreachable" branch needs gateway/alert coverage in production.
- **Out-of-repo integrations (release checklist)**: the APISIX/Higress gateway and `gateway-agent` calling `/v1/evaluate` must carry a **per-purpose service token** (`POST /api/v1/admin/evaluate-token/service`; `*` tools only for cross-tenant purposes).
> **Moved to the SDK** (config of the removed chat-embedded review): `skip-models`, `refusal-text`, `fail-closed`, `streaming.{window-chars,context-chars,stream-first}` — see the `virbius-review-sdk` README. `virbius.trace.*` was removed together with `TraceStepPublisher`.

### 10.3 Audit export key (must be provided explicitly)

```bash
# generate a 32B key and hand it to control (startup argument or env)
export VIRBIUS_AUDIT_EXPORT_KEY="$(openssl rand -base64 32)"
# or --virbius.audit.export.key=<base64>
```

- Semantics: the export is audit-event JSONL (with `user_id`/`device_id`/`reason_code`), written to `data/exports/<jobId>.bin`. With a key it is `12B IV || ciphertext+tag` (AAD bound to `jobId`); in plaintext mode the file is raw JSONL, unchanged.
- Control only produces ciphertext and offers no decrypt endpoint — the operator decrypts offline with the same key.
- Unset key: with `allow-plaintext=true` (default) → the job lands `done` with plaintext JSONL; with `allow-plaintext=false` → the job lands `failed` with the reason in the `error` field. A key is always honored over the plaintext flag.

### 10.4 Audit evidence tables append-only grants (A0, tamper prevention)

The hash chain detects tampering; the script below prevents it: it revokes `UPDATE`/`DELETE` on the evidence tables from the application account, so audit/trace/challenge rows cannot be altered at the privilege layer.

```bash
# apply manually in production (adjust the account; not managed by Flyway)
mysql -u root -p < deploy/scripts/audit-insert-only-grants.sql
```

The `DELETE` needed for retention archival is held only by a separate ops account, and only for rows outside the retention window whose daily anchor is already sealed.

### 10.5 New console pages

- `/alerts` (Alerts): list + status filter (`open`/`acked`) + acknowledge (ack) + expand Decision BOM.
- `/audit-integrity` (Audit Integrity, 4 tabs): integrity verification (incl. daily-anchor seal / run verification), Decision BOM, audit export, session checkpoints.
