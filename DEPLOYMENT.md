# VirbiusAgent Deployment View — DEPLOYMENT

| Item | Description |
|------|------|
| Document Version | v3.3 |
| Status | Draft |
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

> **Multi-Upstream Support**: MCP Proxy supports connecting to multiple MCP Servers simultaneously (added in P1), routing through tool name via `tools/call`.
> Single-upstream mode is backward compatible (legacy `upstream_url` config is auto-normalized); conflicting tool names in multi-upstream mode are automatically prefixed with `{upstream}__{tool}`.
> See [PROTOCOL.md §2.6.2](PROTOCOL.md#262-multi-upstream) for details.
| **virbius-engine** | 8082 | Cloud Side | — |
| **virbius-control** | 8080 | Cloud Side | — |
| **Falco** (Kernel Layer Observation) | None (DaemonSet) | Agent Host Machine | Bypass |
| **virbius-kernel-daemon** | 9090 | Agent Host Machine | Bypass |
| **Redis** | 6379 | Cloud Side | — |
| **Database** | — | Cloud Side | — |

> **Removed original AgentGateway (9080)**: MCP routing is handled by Higress.
> **Removed original virbius-gateway-agent (9070)**: Security precheck is handled by the Higress WASM plugin.

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
|  +-- eBPF driver / plugin mode                             |
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
> External HTTP requests (curl) are proxied by the Proxy, which validates URL allowlist at the application layer ([§3.5](ARCHITECTURE.md)).

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
|  +-- eBPF driver / plugin mode                             |
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
| **Graceful Degradation** | When eBPF is unavailable, Kernel Layer degrades to plugin mode (not blind); when engine is unavailable, Edge Layer fail-open/fail-closed; when Gateway Layer is unavailable, Edge Layer independently provides fallback |
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
| **Resource Overhead** | Additional ~20MB per Agent Pod (Proxy) + ~50MB per node (Falco) + Higress cluster + Engine cluster | Lightweight scenarios can degrade to 2 layers (Edge + Cloud); Falco supports optional plugin mode to reduce overhead |
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

---
