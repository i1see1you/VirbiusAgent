# VirbiusAgent Architecture Design — ARCHITECTURE

[中文版](ARCHITECTURE.zh.md)

| Project | Description |
|------|------|
| Document version | v3.6 |
| Status | Active |
| Related | [DESIGN.md](DESIGN.md) (index) · [PROTOCOL.md](PROTOCOL.md) · [DEPLOYMENT.md](DEPLOYMENT.md) · [CHANGELOG.md](CHANGELOG.md) |
| Reference project | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

> This document contains §1 Overall Architecture · §2 Edge Layer · §3 Gateway Layer · §4 Kernel Layer · §5 Cloud Layer.
> §2.6 MCP Proxy complete technical solution has been split into [PROTOCOL.md](PROTOCOL.md).

---

## 1. Overall Architecture

### 1.1 Four-Layer Overview

```
Agent Framework (LangChain / OpenAI SDK / AutoGen / ...)
  | tool_call
  v
[1] Edge - virbius-core (extended)
    precheck: args + allowlist + JSON Schema
    execute:  P0 in-process / Landlock + drop caps + gVisor
  |
[2] Gateway - Higress + virbius-gateway WASM plugin
    TLS/rate-limit/long-conn + allowlist + counter + engine call + HTTP block
  |
[3] Kernel - Falco observer (observation layer)
    eBPF driver (standard node); unprivileged env -> Disabled (plugin mode removed)
    observe: syscall/net/file + audit stream + session risk
    enforce: Landlock + drop caps (edge) / gVisor (edge)
  |
[4] Cloud - virbius-engine + virbius-control
    engine: Groovy L3 + STI audit + tool-chain detect
    session: Redis (tool history + risk score + counters)
    control: rule CRUD + rollout + unified delivery
```

**Traffic topology—North-South vs East-West**:

The four layers (Edge, Gateway, Kernel, Cloud) are divided into two categories by traffic direction, requiring clear distinction to avoid topology conflicts:

```
┌─────────────────────────────────────────────────────────────┐
│  East-West Traffic (local/same Pod)                        │
│                                                              │
│  Agent ──MCP/JSON-RPC──> [Edge] MCP Proxy (Sidecar)          │
│    localhost:9090, stdio or SSE                              │
│    Responsibility: License check + precheck + security pipeline + tool execution │
│                                                              │
│  Features: Agent and Proxy in the same process group, traffic stays within Pod, does not pass through Gateway │
├─────────────────────────────────────────────────────────────┤
│  North-South Traffic (cross-network)                        │
│                                                              │
│  Remote Agent ──HTTPS──> [Gateway] Higress (Ingress) ──> MCP Server  │
│                         TLS/rate-limit/allowlist/engine call         │
│                                                              │
│  Agent/curl tool ──HTTP──> [Gateway] Higress (Egress) ──> External API  │
│                         or Edge Egress interception (Sidecar mode)     │
│                                                              │
│  Features: Non-Sidecar cross-network traffic must pass through Gateway; Sidecar mode Egress │
│  is proxied by Edge Proxy (see §3.5)                          │
└─────────────────────────────────────────────────────────────┘
```

| Traffic Type | Direction | Interception Layer | Deployment Mode |
|---------|------|--------|---------|
| MCP tool call (Sidecar mode) | East-West | Edge MCP Proxy | Agent and Proxy in same Pod |
| MCP tool call (remote mode) | North-South | Gateway Higress (Ingress) | Agent remote connection |
| Agent external HTTP request (curl etc.) | North-South (Egress) | Edge Egress interception / Gateway Egress Proxy | See §3.5 |

> **Design decision: Gateway does not participate in MCP tool call chain under Sidecar mode**
>
> When the Edge layer is deployed as an MCP Proxy Sidecar, the Agent's MCP tool calls go through localhost directly to the Proxy, bypassing the Gateway Higress. This is expected behavior: the Edge Proxy already embeds a complete security pipeline (License + precheck + engine final judgment), and the Gateway does not re-intervene in this scenario.
>
> The Gateway Higress responsibilities focus on:
> 1. **Ingress**: North-South traffic from remote Agents (non-Sidecar) accessing MCP Server
> 2. **Egress**: Network-layer control of external HTTP requests from Agent business tools (e.g. `curl` tool accessing external APIs). Note: only MCP business tool requests go through the Proxy; implicit network requests from the Agent framework (config fetching, model downloads, heartbeats, etc.) are restricted by NetworkPolicy and are not proxied (see [§3.5](#35-egress-traffic-control))
>
> For Egress traffic in Sidecar mode, **tool-level control** is adopted: MCP business tool requests (e.g. `curl`) are intercepted by the Edge Proxy at the `tools/call` stage for URL allowlist validation and proxying (P0); implicit network requests from the Agent framework are restricted to allowlist targets by K8s NetworkPolicy (P0); P2 can add eBPF/iptables transparent hijacking for process-level full outbound blocking.

### 1.2 Design Principles

| Principle | Description |
|------|------|
| **Unified control plane** | All layers' policy source of truth is virbius-control; each layer executes independently but configuration is from the same source |
| **Precheck before execution** | Edge precheck -> Gateway/Cloud final judgment -> Edge execution. Tools are executed only after final judgment passes |
| **Separation of observation and enforcement** | Observation (eyes) and enforcement (hands) are handled by different technology stacks. Observation degrades with environment (eBPF->ptrace); enforcement is always guaranteed by Edge Landlock + drop caps (P2) |
| **Observation first** | P0 only implements observation (Falco + HTTP layer blocking + session risk accumulation); P2 adds syscall-level blocking |
| **eBPF is enhancement, not dependency** | eBPF enhances observation precision when available; when unavailable, Edge Landlock + drop caps + gVisor still provide complete enforcement |
| **Edge as last resort** | Even if Gateway/Cloud are bypassed, Edge precheck + sandbox still restrict process behavior |
| **Fast path** | Low-risk tools skip Cloud RPC; Edge precheck + Gateway local rules directly allow; target latency <5ms |
| **Separation of concerns** | Higress handles routing + rate limiting + security precheck; security final judgment converges on virbius-engine |
| **North-South/East-West separation** | Edge (Sidecar) handles East-West MCP tool calls; Gateway (Higress) handles North-South Ingress/Egress traffic. In Sidecar mode, MCP calls do not pass through Gateway; Gateway focuses on network boundary security |
| **Progressive adoption** | Each layer can be independently enabled/disabled; supports lightweight deployments with only Edge or only Gateway |

### 1.3 Phased Planning

| Phase | Observation (eyes) | Enforcement (hands) |
|------|-----------|------------|
| **P0** | Falco (eBPF) + access log + Redis audit stream + STI audit + Prompt Gateway (constitutional injection) | HTTP 403 + allowlist + counting + schema validation + risk threshold disconnect + Runtime License validation |
| **P1** | STI Taint small model + Falco http_output three-level correlation + audit integrity | Manual approval flow + adaptive risk model + memory control (Memory Interceptor) |
| **P2** | Custom eBPF observation (execveat + IPv6) | Landlock + drop caps + gVisor (✅) + TEE (financial grade, pending) |

### 1.4 Identity System

This design inherits VirbiusLLM's `app_id` as the **Agent identity (agent_id)**, without distinguishing between Agent type and running instance.

VirbiusAgent code implementation references VirbiusLLM; detailed reuse relationships are in 10.

| Layer | Identifier | Description | Example |
|------|------|------|------|
| Tenant | `tenant_id` | Organization/tenant | "CompanyA" |
| **Agent** | `app_id` | **Agent identity (i.e. agent_id)** | "code-review-agent" |
| Session | `session_id` | Single conversation/tool call chain | "sess_abc" |
| Device | `device_id` | Client device (for canary) | "device_xxx" |
| Trace | `trace_id` | Single request trace | "uuid" |

> **Design decision**: `app_id` is `agent_id`. In the Agent security scenario, each `app_id` corresponds to a specific Agent entity (e.g. "code review agent", "data analysis agent"); there is no need for "type vs instance" separation. Runtime License, policies, and risk_score are all bound to `app_id`.

**Agent Runtime License**:

virbius-control issues a Runtime License for each `app_id`; each layer validates on the critical path:

```
virbius-control issues License (JWT signed):
{
  "app_id": "code-review-agent",      // Agent identity
  "tenant_id": "CompanyA",
  "allowed_tools": ["read_file", "search", "curl"],
  "allowed_scenes": ["code_review", "data_analysis"],
  "risk_quota": 60,                    // Maximum allowed session_risk_score
  "tool_rate_limit": 50,               // Maximum tool calls per minute
  "expiry": "2026-07-06T12:00:00Z",
  "signature": "RS256..."
}
```

| Validation Point | Validation Content |
|--------|---------|
| Gateway Higress entry | License signature + expiration + revocation status |
| Edge virbius-core | Whether License's allowed_tools includes the current tool |
| Cloud virbius-engine | Whether current session_risk_score exceeds License's risk_quota |

**License revocation**: Real-time notification to all layers via Redis pub/sub. After revocation, all subsequent requests for that `app_id` are rejected.
**In-session expiration handling**: When a License expires during an active session, the currently executing tool call is allowed to complete (maintaining atomicity), but subsequent requests are immediately rejected after completion and the Agent is notified to re-authorize. Edge virbius-core checks the remaining License validity during each precheck; a warning is issued when less than 5 minutes remain.

### 1.5 Three-Layer Security Architecture

"Edge, Gateway, Kernel, Cloud" is the **deployment topology perspective** (where components run); the three-layer security architecture is the **functional control perspective** (how security capabilities are orchestrated). They are orthogonal; the same function can span multiple layers.

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: Identity Control Layer                        │
│  Unified identity system + Agent Runtime License        │
│  (§1.4)                                                 │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Runtime Protection Layer                      │
│  ┌─────────┬─────────┬─────────┬─────────┬─────────┐    │
│  │Intent   │Prompt   │Memory   │Tool     │Output   │    │
│  │Judgment │Enhance- │Control  │Inter-   │Review   │    │
│  │         │ment     │         │ception  │         │    │
│  │§5.3+5.4 │§2.8     │§2.9     │§2.1+3.2 │§2.10    │    │
│  │+§2.8.7  │         │         │+§5.3    │         │    │
│  └─────────┴─────────┴─────────┴─────────┴─────────┘    │
├─────────────────────────────────────────────────────────┤
│  Layer 3: Infrastructure Layer                          │
│  Landlock sandbox + Namespace isolation + eBPF filtering │
│  (§2.3 + §2.4 + §4 + §2.3.1)                            │
└─────────────────────────────────────────────────────────┘
```

**Mapping to Edge/Gateway/Kernel/Cloud**:

| Three-Layer Architecture | Edge | Gateway | Kernel | Cloud |
|---------|-----------|--------------|-------------|------------|
| **Identity Control Layer** | License validation (allowed_tools) | License validation (signature/expiry/revocation) | — | License issuance + risk_quota validation |
| **Runtime Protection Layer** | Precheck + Prompt Gateway + Memory Control + Output Review | allowlist + counting + engine call | — | Groovy L3 + STI + Prompt intrusion detection |
| **Infrastructure Layer** | Landlock + gVisor + namespace isolation | — | Falco + eBPF | — |

**Layer 1: Identity Control Layer**

Establish a unified identity system (tenant_id → app_id → session_id → device_id → trace_id, §1.4), with Runtime License as the core to implement full lifecycle management of Agent identity: issuance (control), validation (Edge/Gateway/Cloud three layers), revocation (Redis pub/sub), expiration handling (atomicity guarantee). See §1.4.

**Layer 2: Runtime Protection Layer**

Using the "Enterprise AI Agent Constitution" as the guiding principle, implement full-process control of Agent runtime through five layers of policy:

| Policy | Responsibility | Design Section | Nature |
|------|------|---------|------|
| **Intent Judgment** | Determine whether Agent intent and tool call chain are safe | §5.3 Groovy L3 + §5.4 STI Suitability + §2.8.7 Prompt intrusion detection | Detection |
| **Prompt Enhancement** | Inject constitutional constraints to prevent dangerous intent generation | §2.8 Prompt Gateway | Prevention |
| **Memory Control** | Agent memory read/write interception + desensitization + injection detection | §2.9 Memory Interceptor | Prevention + Detection |
| **Tool Interception** | Parameter validation + allowlist + schema + tool chain detection | §2.1 Edge precheck + §3.2 Gateway WASM + §5.3 Cloud L3 | Detection + Blocking |
| **Output Review** | Tool result content security review + Agent final response review | §2.10 Output Review | Detection + Blocking |

Runtime protection flow:

```
User input
  → [Intent Judgment] Prompt intrusion detection (§2.8.7) + session risk assessment
  → [Prompt Enhancement] Prompt Gateway constitutional injection + PII desensitization (§2.8)
  → LLM inference
  → [Memory Control] Memory Interceptor intercepts memory reads/writes (§2.9)
  → [Tool Interception] Edge precheck → Gateway rules → Cloud L3 final judgment (§2.1 + §3.2 + §5.3)
  → Tool execution
  → [Output Review] STI Taint + final response review (§5.4 + §2.10)
  → Return to user
```

**Layer 3: Infrastructure Layer**

Build a secure and trusted Agent OS, providing syscall-level isolation and observation foundation for the Runtime Protection Layer:

| Capability | Technology | Design Section | Phase |
|------|------|---------|------|
| File path isolation | Landlock | §2.3 | P2 |
| Namespace isolation | clone3(CLONE_NEWPID \| CLONE_NEWNS \| CLONE_NEWNET) | §2.3.1 | P2 |
| Capability dropping | drop caps | §2.3 | P2 |
| Untrusted code sandbox | gVisor runsc warm pool | §2.4 | ✅ |
| Kernel observation | Falco eBPF | §4 | P0 |


---

## 2. Edge — Agent Tool Call Precheck and Execution

### 2.1 Responsibilities

| Phase | Action | Latency |
|------|------|------|
| **Precheck** | Parameter validation, tool allowlist, JSON Schema validation, local rule matching | <0.5ms |
| **Execution** | P0: in-process execution / Landlock + gVisor sandbox | P0: <0.1ms / Landlock: ~2ms / gVisor: 1-5s cold, ~50ms hot |

**Key constraint**: No tool logic is executed during the precheck phase. Only after final judgment returns allow does execution begin.

### 2.2 Layered Isolation Strategy (P0 -> P2 Progressive)

```
ToolCallRequest { name, args }
  |
  +-- sandbox_type = "none" (P0)
  |    in-process execution (precheck only, no isolation)
  |    Applicable to: all tools (P0 phase does not distinguish sandbox types)
  |    Latency: cold <0.1ms / hot <0.1ms
  |    Security: HTTP layer blocking + session risk accumulation + Falco observation
  |
  +-- sandbox_type = "subprocess" (P2)
  |    posix_spawn + Landlock + drop caps
  |    Applicable to: read_file, write_file, curl (allowlist targets)
  |    Latency: cold ~2ms / hot ~1ms
  |
  +-- sandbox_type = "gvisor"
  |    gVisor runsc container (warm pool)
  |    Applicable to: execute_python, shell, any untrusted code
  |    Latency: cold 1-5s / hot ~50ms (warm pool hit)
  |    Status: ✅ Verified on Linux host
  |
  +-- deny
        Directly rejected, not executed
```

> **P0 Security Model**: No syscall-level isolation. Security relies on:
> 1. HTTP layer blocking (Edge Proxy allow/deny or Gateway Higress allow/deny + engine final judgment, depending on deployment mode)
> 2. Parameter schema validation (path allowlist, etc.)
> 3. Session risk accumulation (Falco detects anomalies -> risk score increases -> subsequent requests blocked)
> 4. High-risk tools (execute_python, shell) require mandatory manual approval or disablement in P0 phase

### 2.3 P2: Landlock + drop caps Subprocess (Linux)

> **P2 implementation, P0 does not involve.** The following is a long-term design reference.

**Design decision**: P2 subprocess sandbox uses Landlock + drop caps, not seccomp-notify.

| Dimension | Landlock + drop caps | seccomp-notify (original plan, deprecated) |
|------|---------------------|-------------------------------|
| File path restriction | ✅ Landlock file rules | ✅ open/openat interception |
| Network IP restriction (SSRF) | ❌ Landlock v4 only restricts ports, not IPs | ✅ connect interception |
| Supervisor SPOF | ✅ No supervisor | ❌ Crash = process hang |
| TOCTOU risk | ✅ Kernel-enforced, no race conditions | ❌ Requires ioctl validation |
| Implementation complexity | ~5w | ~16w |

**SSRF protection compensation**: Landlock cannot restrict connect by IP, but the subprocess sandbox is only used for `read_file`/`write_file`/`curl (allowlist targets)`, not for `execute_python`/`shell` (which go through gVisor). `curl` URLs are already validated at the application layer with schema validation + allowlist validation (in Sidecar mode by the Edge MCP Proxy when proxying; in non-Sidecar mode by the Gateway Higress Egress, see [§3.5](#35-egress-traffic-control)); no syscall-level connect interception is needed. Network layer is backed by K8s NetworkPolicy.

**Multi-thread safety**: The Agent framework is based on the tokio async runtime and is multi-threaded. Child process creation uses `std::process::Command::spawn` (which internally does `fork+exec`), and applies Landlock restrictions via the `pre_exec` hook between fork and exec, avoiding races with long-lived child processes in multi-threaded processes.

> **Architecture change (Plan B)**: The original design used LD_PRELOAD to inject a C shared library (`libvirbius_sandbox_preload.so`) to apply Landlock before the child process `main()`. This has been changed to use `std::os::unix::process::CommandExt::pre_exec` to directly call Landlock syscalls on the Rust side between fork and exec. This provides:
> 1. Zero additional build artifacts (no need to compile/deploy `.so`)
> 2. Single build system (pure Cargo)
> 3. Observable errors (`pre_exec` returning `Err` causes `spawn()` to fail, unlike LD_PRELOAD absence which only prints a warning and continues)
> 4. All heap allocations are done in the parent process's `PreparedRules::compile`; the child process `pre_exec` closure only does read-only iteration + raw syscalls, adhering to async-signal-safety

**Landlock (P2 core)**:

```rust
// virbius-core/src/sandbox/landlock.rs (P2)
//
// All heap allocations are done in the parent process's PreparedRules::compile;
// the pre_exec closure runs in the child process after fork and before exec, using only
// async-signal-safe operations (raw syscall / open / close, no malloc / Mutex).

pub struct LandlockSandbox {
    config: SandboxConfig,
    abi: LandlockAbi,
}

pub struct LandlockRules {
    // v1 (kernel 5.13+): file paths (glob, expanded to concrete paths in parent)
    pub read_paths: Vec<String>,      // read-only paths, e.g. ["/usr/*", "/lib/*"]
    pub write_paths: Vec<String>,     // read-write paths, e.g. ["/tmp/workdir/*"]
    pub exec_paths: Vec<String>,      // executable paths, e.g. ["/usr/bin/*"]
    // v4 (kernel 6.7+): network ports (optional, skipped if unsupported)
    pub bind_ports: Vec<u16>,         // allowed bind ports
    pub connect_ports: Vec<u16>,     // allowed connect ports
}

/// Parent process pre-compilation: glob expansion + convert to CString, for read-only use by pre_exec closure
struct PreparedRules {
    abi: LandlockAbi,
    read_paths: Vec<CString>,   // concrete paths after glob expansion
    write_paths: Vec<CString>,
    exec_paths: Vec<CString>,
    bind_ports: Vec<u16>,
    connect_ports: Vec<u16>,
}

impl LandlockSandbox {
    /// spawn + pre_exec(Landlock + drop caps) -> execute child process
    pub fn execute(&self, program: &str, args: &[String]) -> Result<SandboxResult, String> {
        // Parent process: pre-compile rules (safe allocation)
        let prepared = PreparedRules::compile(&self.config.rules);
        let prepared_for_hook = prepared.clone();

        let mut child = Command::new(program)
            .args(args)
            .pre_exec(move || {
                // Inside child process, after fork, before exec. Async-signal-safe.
                // 1. landlock_create_ruleset + add_rule(path/net) + restrict_self
                // 2. capset(drop ALL) + prctl(PR_SET_NO_NEW_PRIVS)
                apply_landlock(&prepared_for_hook)
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;

        // Parent process: wait + timeout + read stdout (no supervisor, no /dev/seccomp)
        // ...
    }
}
```

**pre_exec hook operation** (all using raw syscalls, async-signal-safe):

```rust
// virbius-core/src/sandbox/landlock.rs
//
// Order: Landlock -> drop caps (two steps, no seccomp)

fn apply_landlock(rules: &PreparedRules) -> io::Result<ApplyReport> {
    if rules.abi == LandlockAbi::None {
        // Degradation: only drop caps
        return drop_caps_and_no_new_privs();
    }

    // 1. Landlock: create ruleset + add rules + restrict_self
    //    Detect ABI version: v1(5.13+, file) / v4(6.7+, network)
    //    Skip network rules if v4 not supported, only do files
    //    Landlock has no audit mode, only enforce (deny), does not generate observation events
    let fd = syscall(SYS_landlock_create_ruleset, &attr, size, 0)?;
    for path in &rules.read_paths  { add_path_rule(fd, path, ACCESS_FS_READ)?; }
    for path in &rules.write_paths { add_path_rule(fd, path, ACCESS_FS_WRITE)?; }
    for path in &rules.exec_paths  { add_path_rule(fd, path, ACCESS_FS_EXECUTE)?; }
    if rules.abi.supports_net() {
        for port in &rules.bind_ports     { add_net_rule(fd, *port, ACCESS_NET_BIND_TCP)?; }
        for port in &rules.connect_ports  { add_net_rule(fd, *port, ACCESS_NET_CONNECT_TCP)?; }
    }
    syscall(SYS_landlock_restrict_self, fd, 0)?;
    close(fd);

    // 2. Capabilities: drop all CAP_*
    //    Threat surfaces not covered by Landlock are supplemented by drop caps:
    //    - CAP_NET_RAW: prohibit raw socket (ping/packet capture)
    //    - CAP_SYS_PTRACE: prohibit ptrace injection into other processes (escape prevention)
    //    - CAP_SYS_ADMIN: prohibit mount/namespace operations
    //    - CAP_NET_ADMIN: prohibit modifying iptables/routing
    //    - CAP_SYS_MODULE: prohibit loading kernel modules
    drop_caps_and_no_new_privs()?;

    // 3. prctl(PR_SET_NO_NEW_PRIVS) is already included in the above step
    // 4. No need to clean environment variables (no longer using LD_PRELOAD / VIRBIUS_* env to pass rules)
    Ok(ApplyReport { landlock_applied: true, caps_dropped: true })
}
```

**Landlock + drop caps responsibility division**:

| Threat | Landlock covers | drop caps covers |
|------|-------------|---------------|
| Read unauthorized files | ✅ Path rules | - |
| Write unauthorized files | ✅ Path rules | - |
| Execute unauthorized binaries | ✅ Path rules | - |
| raw socket (ping/packet capture) | ❌ | ✅ Remove CAP_NET_RAW |
| ptrace injection into other processes (escape) | ❌ | ✅ Remove CAP_SYS_PTRACE |
| mount fake filesystem | ❌ | ✅ Remove CAP_SYS_ADMIN |
| Modify iptables/routing (traffic hijacking) | ❌ | ✅ Remove CAP_NET_ADMIN |
| Load kernel modules | ❌ | ✅ Remove CAP_SYS_MODULE |

**Landlock ABI version adaptation**:

```rust
pub fn detect_abi_version() -> LandlockAbi {
    // Try creating rulesets to test supported ABI version
    // v1 (5.13+): file paths
    // v2 (5.19+): file + references
    // v3 (6.2+):  file + devices
    // v4 (6.7+): file + network
    if try_create_ruleset(with_net = true)  { return LandlockAbi::V4; }
    if try_create_ruleset(with_net = false) { return LandlockAbi::V1; }
    LandlockAbi::None
}
```

> **Note**: Landlock network (v4) requires kernel 6.7+, and many kernels still won't meet this requirement in 2026. P2 will first only implement file path restrictions (v1, 5.13+); network restrictions are handled by NetworkPolicy.

**Landlock rule example** (read_file tool):

```json
{
  "tool_name": "read_file",
  "exec_env": {
    "sandbox_type": "subprocess",
    "landlock_rules": {
      "read_paths": ["/tmp/data/*", "/home/user/workdir/*", "/usr/lib/*"],
      "write_paths": [],
      "exec_paths": ["/usr/bin/cat", "/usr/bin/head"]
    },
    "drop_caps": "all",
    "timeout_ms": 5000
  }
}
```

Regarding macOS: Landlock is not supported. macOS is a development environment; P2 sandbox is not enabled and degrades to in-process execution with warning logs. Production environments are deployed on Linux/K8s where Landlock is available.

### 2.4 gVisor Subprocess + Warm Pool

> **Implemented and verified on Linux host.**

For untrusted code execution, an isolated container is started via gVisor runsc. gVisor cold start is 1-5 seconds; **a warm pool must be used**:

```rust
// virbius-core/src/sandbox/gvisor_pool.rs
pub struct GvisorPool {
    config: GvisorPoolConfig,
    warm: Arc<Mutex<HashMap<Language, Vec<WarmContainer>>>>,
    runsc_available: bool,
}

pub struct GvisorPoolConfig {
    pub runsc_path: String,
    pub rootfs_path: String,
    pub min_warm: usize,       // minimum warm containers per language
    pub max_idle: usize,       // maximum idle containers per language
    pub memory_limit_bytes: u64,
    pub cpu_quota: f64,
    pub network_disabled: bool,
    pub exec_timeout: Duration,
}
```

**Execution flow**: `execute(language, code)` → get container from warm pool (hot path ~50ms) → write to stdin → read stdout → destroy used container → background replenishment. Cold start 1-5s.

**Degradation strategy**: When gVisor is unavailable (runsc not installed), automatically degrade to Landlock subprocess + 5s timeout forced kill + memory limit.

### 2.5 Synchronization with virbius-control

The Edge layer policy reuses the existing virbius-core manifest synchronization mechanism:

```rust
// Extended EdgeManifest
struct EdgeManifest {
    #[serde(default)]
    rules: Vec<EdgeRule>,               // Existing: keyword rules
    #[serde(default)]
    dlp_rules: Vec<DlpRule>,            // Existing: DLP rules
    #[serde(default)]
    tool_policies: Vec<ToolPolicy>,     // New: tool policies (allowlist + schema + fast_path)
    #[serde(default)]
    landlock_profiles: HashMap<String, LandlockProfile>, // P2: Landlock templates
    sdk_config: SdkConfig,
}
```

> **Note**: landlock_profiles may be large; it is recommended to provide a separate fetch endpoint `/api/v1/edge/landlock-profiles`, rather than pulling it all at once with the main manifest.


### 2.6 MCP Server Integration

> **This section has been split into a separate document**: [PROTOCOL.md](PROTOCOL.md) — Contains the complete MCP Proxy technical solution (architecture, protocol handling, interception flow, session management, fallback strategy, error code definitions, configuration, deployment modes, implementation structure, core code).

---

### 2.7 Fast Path (Low-Risk Tools Skip Cloud)

For low-risk tools (search, calculator, formatting), the fast path allows skipping the Cloud RPC:

```
Low-risk tool (fast_path=true)
  -> Edge precheck (parameter validation + allowlist)
  -> Local risk cache check (< threshold?)
  <- allow (no call to virbius-engine)
  -> Edge execution (in-process)
```

**Judgment conditions (3 items, all must be satisfied to use fast path)**:

| Condition | Description | Data Source |
|------|------|---------|
| `fast_path == true` | ToolPolicy marked as fast path | Local manifest cache |
| `sandbox_type == "none"` | No sandbox isolation needed | Local manifest cache |
| `local_risk < threshold` | Locally cached session risk score below threshold (default 30) | Local SessionStateCache |

If any condition is not met, fall back to the full chain.

> **Simplification note**: The original design had 4 conditions (including `tool_name in fast_allowlist`), but `fast_path == true` is already a per-tool policy marker, semantically duplicating `fast_allowlist`, so it has been consolidated to 3 items.

**SessionStateCache — Local risk score cache**:

The core issue of the fast path is: `session_risk_score` is computed by the Cloud engine; how can the Edge obtain it with low latency? The answer is that the Edge maintains a local cache and does not query the Cloud in real time.

```
┌─── Edge Proxy / Gateway Higress ──────────────────────┐
│                                                      │
│  SessionStateCache (in-memory LRU, TTL=60s)           │
│  ┌──────────────────────────────────────────────┐    │
│  │ session_id -> { risk_score, last_updated }   │    │
│  │                                              │    │
│  │ "sess_abc" -> { risk: 15, updated: 12:00:30 }│    │
│  │ "sess_def" -> { risk: 45, updated: 12:00:15 }│    │
│  └──────────────────────────────────────────────┘    │
│                                                      │
│  Update sources (3 types, complementary):             │
│  1. Full-chain response backfill: engine /v1/evaluate │
│     response includes risk_score -> sync update local cache  │
│  2. Redis pub/sub async push: engine publishes after  │
│     computing risk to channel "risk:{session_id}" -> Edge subscribes  │
│  3. TTL expiry fallback: cache entry expires after 60s, │
│     next request forced to full chain (falls back to source 1 backfill) │
│                                                      │
│  Read: direct memory read during fast path judgment, zero network overhead │
└──────────────────────────────────────────────────────┘
```

| Update Mechanism | Latency | Trigger Condition | Description |
|---------|------|---------|------|
| Full-chain backfill | Real-time | Each full-chain call return | engine response body contains `risk_score`, Proxy writes to cache synchronously |
| Redis pub/sub | ~1ms | engine async risk update | engine publishes `risk:{session_id}` → Proxy subscribes and updates |
| TTL expiry | 60s | Cache entry timeout | After expiry, `local_risk` is treated as `None`, forcing full chain |

> **Design decision: Do not proactively query Redis/Engine for risk_score**
>
> The goal of the fast path is "zero network overhead decision making". Querying Redis (~1ms) or Engine (~10-50ms) on every call would negate the benefit of the fast path. Instead:
> - **Write-update**: backfill cache when full-chain call returns (passive update)
> - **Push update**: engine asynchronously computes then pushes via Redis pub/sub (active update)
> - **Expiry fallback**: cache TTL expiry forces full chain (safety fallback)
>
> Worst case: risk_score is delayed by 60s. During this time, if the session risk has increased but the cache hasn't been updated, the fast path might allow requests that should have been blocked. Mitigation: fast path tool audit events are fully sampled (sample_rate=1.0); engine async review detects violations and raises the risk score via pub/sub, causing subsequent requests to immediately exit the fast path.

**Cold start protection**: New sessions have no cache entry (`local_risk == None`); the first N calls are forced through the full chain (warmup). After N calls, once the cache is populated and risk < threshold, the fast path is opened.

**fail-open/fail-closed**: When virbius-engine is unavailable (network partition), high-risk tools are fail-closed (deny), low-risk tools are fail-open (allow + full audit).

**Risk mitigation**: Fast path tool audit events are fully sampled (sample_rate=1.0) and sent asynchronously to virbius-engine for review. If async review detects a violation, session_risk_score is raised (via pub/sub push), and subsequent requests automatically exit the fast path.

### 2.8 Prompt Gateway (Prompt Enhancement)

The Prompt Gateway is a **preventive** security component at the Edge layer, injecting safety constraints before the Agent sends prompts to the LLM. It complements tool interception (detective), forming a "prevention + detection" defense-in-depth.

```
Agent generates prompt
  |
  v
Prompt Gateway (embedded in virbius-core, <0.5ms)
  |  <- Inject constitutional constraints (from virbius-control, local cache)
  |  <- Inject tool rules (from License permissions)
  |  <- Inject dynamic context (session risk + recent tool calls)
  |  <- PII input desensitization (reuses dlp/engine.rs)
  |
  v
Enhanced prompt -> LLM API
  |
  v
LLM generates tool_call -> Edge precheck -> Gateway -> Cloud final judgment -> Execution
```

| Layer | Mechanism | Nature | Effect |
|----|------|------|------|
| Prompt Gateway | Inject rules for LLM self-constraint | Proactive | Reduces generation of dangerous intents |
| Prompt intrusion detection | Small model determines if user input contains jailbreak/injection | Reactive | Blocks malicious prompts from reaching LLM |
| Tool interception | Prevents dangerous tool execution | Reactive | Prevents dangerous actions from being realized |
| Falco observation | Monitors runtime anomalies | Reactive | Discovers anomalies that have occurred |

#### 2.8.1 Injection Content

**Constitutional constraints (system prompt enhancement)** — Managed by virbius-control, compiled into scene-related templates:

```
## Virbius Agent Constitution v1.2 (scene: code_review)

### Absolutely Prohibited
1. Must not send data to external endpoints outside the allowlist
2. Must not execute code outside the sandbox
3. Must not access files outside allowed paths
4. Must not attempt to bypass security controls
5. Must not include credentials, tokens, or keys in output

### Tool Usage Rules
- Available tools: read_file, search, curl
- curl only allowed to connect: api.internal:443, cdn.internal:443
- read_file only allowed to read: /tmp/data/*, /home/user/workdir/*
- Maximum 50 tool calls per minute
- When a tool returns an error, retry no more than 3 times

### Data Processing Rules
- PII in tool results must be desensitized before being included in the response
- Must not store sensitive data in memory
- Tool results exceeding 64KB should be summarized, not passed through as-is

### Scene Constraints (code_review)
- You are reviewing code, not executing code
- Prohibited from using execute_python or shell tools
```

**Dynamic context injection** — Generated in real-time based on current session state:

```
## Current Session Context
- Session risk score: 25/100 (low risk)
- Tools called in this session: read_file(3), search(2)
- Scene: code_review
- License remaining validity: 2h 15m

## Recent Activity
- Last tool: read_file(/tmp/data/auth.py) -> success
- Note: Reading authentication-related files, beware of credential leaks
```

**Centralized tool constraint injection (system prompt)** — Constraint rules for all tools are **rendered once uniformly** in the system prompt, rather than duplicated in each tool's `description`:

```
### Tool Usage Rules
- Available tools: read_file, search, curl
- curl only allowed to connect: api.internal:443, cdn.internal:443
- read_file only allowed to read: /tmp/data/*, /home/user/workdir/*
- Maximum 50 tool calls per minute
- When a tool returns an error, retry no more than 3 times
```

> **Token savings comparison** (using 10 tools as example):
>
> | Approach | Token Consumption | Description |
> |------|-----------|------|
> | ~~Old: description injection~~ | ~500 tokens | ~50 tokens per tool × 10 tools |
> | New: centralized system prompt injection | ~80 tokens | Constraint rules appear only once in system prompt |
> | Savings | ~420 tokens | **Reduction of ~84%** |
>
> Tool `description` remains original concise text unchanged; structured constraints are passed through MCP `annotations` field ([§2.6.1](PROTOCOL.md#261-mcp-proxy-full-technical-solution)), consumed by MCP client UI and precheck logic, not entering the LLM prompt.

**PII input desensitization** — Reuses existing virbius-core/src/dlp/engine.rs to desensitize user input before sending the prompt.

#### 2.8.2 Implementation

```rust
// virbius-core/src/prompt_gateway.rs

pub struct PromptGateway {
    constitution_cache: RwLock<ConstitutionTemplates>,  // local cache, synced from control
    dlp_engine: DlpEngine,                               // reuses existing
}

pub struct EnhanceContext<'a> {
    pub license: &'a LicenseContext,
    pub session_id: &'a str,
    pub scene: &'a str,
    pub risk_score: u32,
    pub recent_tools: Vec<ToolCallSummary>,
}

impl PromptGateway {
    /// Enhance prompt, return enhanced messages
    pub fn enhance(
        &self,
        messages: &mut Vec<ChatMessage>,
        ctx: &EnhanceContext,
    ) -> Result<()> {
        // 1. Constitutional constraint injection (prepend to system message)
        //    Includes: prohibition rules + tool usage rules (centrally rendered, does not modify per-tool description)
        let constitution = self.constitution_cache.read();
        let rules = constitution.select(ctx.scene, ctx.license.constitution_version);
        let system_augment = rules.render(ctx);  // render complete constitution including tool_constraints
        self.prepend_system(messages, &system_augment)?;

        // 2. Dynamic context injection (append to system message)
        let dynamic_ctx = self.render_dynamic_context(ctx);
        self.append_system(messages, &dynamic_ctx)?;

        // (Removed) Tool description enhancement — no longer modifies per-tool description to avoid token bloat
        // Tool constraints are instead centrally rendered in the constitutional system prompt (step 1) (§2.8.1)

        // 3. PII input desensitization (user/assistant messages only, not system)
        for msg in messages.iter_mut() {
            if msg.role == Role::User || msg.role == Role::Assistant {
                msg.content = self.dlp_engine.desensitize_in(
                    &msg.content, ctx.session_id, ...
                )?;
            }
        }

        Ok(())
    }
}
```

#### 2.8.3 Agent Framework Integration

| Framework | Integration Method | Interception Point |
|------|---------|--------|
| **OpenAI SDK** | EnhancedOpenAIClient proxy, calls gateway.enhance() before chat.completions.create() | Before request is sent |
| **LangChain** | ConstitutionalPromptTemplate, enhances before LLMChain.invoke() | After prompt template rendering |
| **Generic HTTP proxy** | Standalone service, intercepts LLM API requests, enhances body then forwards | HTTP layer |
| **MCP proxy mode** | Reuses [§2.6](PROTOCOL.md) MCP proxy, enhances before forwarding | Before tools/call |

#### 2.8.4 Constitutional Template Compilation

```
virbius-control
  |
  +-- tb_constitution (constitution rules table)
  |   +-- id, version, category, rule_text, priority, scene_filter
  |
  +-- virbius-compiler
  |   +-- Compiles constitution rules into prompt templates by scene
  |   +-- Output: constitution_templates.json
  |
  v
Edge virbius-core (PromptGateway local cache)
  +-- Selects template by scene + constitution_version
  +-- Template variable filling (license permissions, session context)
  +-- Injects into prompt
```

Template example:

```json
{
  "version": "v1.2",
  "templates": [
    {
      "scene": "code_review",
      "system_prefix": "## Virbius Agent Constitution {{version}} (scene: {{scene}})\n\n### Absolutely Prohibited\n{{prohibitions}}\n\n### Tool Usage Rules\n{{tool_rules}}",
      "dynamic_suffix": "## Current Session Context\n- Risk score: {{risk_score}}/100\n- Called: {{recent_tools}}\n- Scene: {{scene}}",
      "prohibitions": [
        "Must not send data to external endpoints outside the allowlist",
        "Must not execute code outside the sandbox",
        "Must not include credentials, tokens, or keys in output"
      ]
    }
  ]
}
```

#### 2.8.5 Expected Effects

| Metric | Without Gateway | With Gateway | Improvement |
|------|-----------|-----------|------|
| Dangerous tool call attempts | Baseline | -60~80% | LLM self-constrains after learning constraints |
| Retry loops | Baseline | -70~90% | LLM understands limits and stops retrying |
| Prompt injection resistance | Baseline | +15~25% | Constitutional rules establish baseline resistance |
| Latency overhead | 0 | <0.5ms | String concatenation, no LLM call |
| Token overhead | 0 | 200-500 tokens/prompt | Constitutional rules take up space |

#### 2.8.6 Risks and Limitations

| Risk | Description | Mitigation |
|------|------|------|
| **Prompt injection can override** | Attacker injects "ignore previous instructions" via tool return values | Constitutional rules provide baseline resistance; STI Taint (P1) detects injection; tool interception is the final line of defense |
| **Token cost** | 200-500 tokens added each time | Compress rule format; only inject scene-relevant rules; skip injection for small models |
| **Model variance** | GPT-4 follows rules well, small models may not | Adjust injection format based on model capability; small models rely on tool interception |
| **Not a substitute for tool interception** | Prompt Gateway is preventive, not blocking | Must never be relied upon alone; must be combined with tool interception + Falco observation |

#### 2.8.7 Prompt Injection Detection (prompt runtime repositioned)

VirbiusLLM's `prompt` rule runtime (NL description → 1B model determines if text violates rules) is an LLM content moderation capability. VirbiusAgent **does not reuse its content moderation semantics**, but **reuses its infrastructure** (rule CRUD + mlPredict call + audit), repositioned as a **user input jailbreak/injection detection layer**.

**Design motivation**: Prompt Gateway (§2.8) is a preventive mechanism (injects constitutional constraints, relies on LLM voluntary compliance); it has no detection-based judgment on user input itself. The `prompt` runtime, after repositioning, fills this gap, forming a "prevention + detection" prompt defense-in-depth:

```
User input prompt
  |
  v
[Detection] prompt runtime (small model determines jailbreak/injection)
  |     +-- Hit → block or raise session_risk_score
  |     +-- No hit → continue
  v
[Prevention] Prompt Gateway (inject constitutional constraints + PII desensitization)
  |
  v
Enhanced prompt -> LLM API
  |
  v
LLM generates tool_call -> Tool interception (Groovy L3 + schema + allowlist)
```

**Differences from VirbiusLLM prompt runtime**:

| Dimension | VirbiusLLM (original usage) | VirbiusAgent (repositioned) |
|------|---------------------|------------------------|
| Judgment target | prompt + response text | User input prompt only |
| Judgment goal | Content safety (violence/pornography/violations) | Jailbreak/injection (DAN/ignore previous/role hijacking) |
| Model | 1B content classification model | qwen3guard:0.6b (reuses same model as STI Taint) |
| Hit action | block + audit | block or raise session_risk_score + audit |
| Rule configuration | NL description (ops console prompt runtime) | Same (reuses existing rule UI) |
| Relationship with Prompt Gateway | None | Complementary: Gateway prevents, runtime detects |

**Rule configuration**: Reuses existing ops console Cloud layer `prompt` runtime rule CRUD (NL description → trigger conditions). Operators write rules such as "detect DAN jailbreak attempts", "detect ignore previous instructions injection", etc.; the engine uses mlPredict to call the small model for judgment.

**Cost control**: Shares qwen3guard:0.6b small model with STI Taint (local Ollama deployment, single call <200ms). Only triggered on user input, not on tool return values (the latter is covered by STI Taint).

**Hit strategy**:

| session_risk_score | Hit Action | Description |
|-------------------|---------|------|
| < 30 | block + audit | Low-risk session directly blocked |
| 30-60 | allow + raise risk_score + audit | Medium-risk allowed but risk accumulates |
| > 60 | block + audit | High-risk session directly blocked |

**Division of labor with STI**:

| Detection Layer | Target | Trigger Condition | Mechanism |
|--------|---------|---------|------|
| **prompt runtime (this section)** | User input prompt | Each user input | Small model determines jailbreak/injection |
| **STI Taint (§5.4)** | Tool return values | Return value >2KB or contains injection markers | Small model determines if return value contains injection instructions |

Both jointly cover the two entry points of prompt injection (user input + tool return values), forming a four-layer defense-in-depth with Prompt Gateway (prevention) and tool interception (execution blocking).

### 2.9 Memory Control (Memory Interceptor)

> **P1 implementation.** Agent memory (long-term memory / vector store) is a persistent carrier for prompt injection—attackers can write malicious instructions into memory via tool return values, which are recalled and executed in subsequent sessions. The Memory Interceptor intercepts Agent memory reads and writes, implementing desensitization + injection detection + audit.

**Interception points**:

```
Agent memory operations
  |
  +-- Write (write/save/embed)
  |    → [Desensitization] PII detection + replacement (reuses dlp/engine.rs)
  |    → [Injection detection] Small model determines if contains malicious instructions
  |    → [Audit] Record original/desensitized content + detection result
  |    → Pass → Write to memory store
  |    → Intercept → Discard + raise session_risk_score
  |
  +-- Read (read/search/recall)
       → [Injection detection] Determine if recalled content contains injection markers
       → [Audit] Record recalled content + detection result
       → Pass → Return to Agent
       → Intercept → Filter malicious segments + alert
```

**Framework integration**:

| Framework | Integration Method | Interception Point |
|------|---------|--------|
| **LangChain** | MemoryInterceptor wrapper, wrapping Memory.save_context() / Memory.load_memory_variables() | Memory read/write API |
| **OpenAI SDK** | Intercept Assistants API message create/retrieve | API call layer |
| **Generic** | Standalone memory proxy service, Agent memory operations go through proxy | HTTP/gRPC proxy |

**Data model**:

```rust
// virbius-core/src/memory_interceptor.rs (P1)

pub struct MemoryInterceptor {
    dlp_engine: DlpEngine,                              // reuses existing PII desensitization
    policies: MemoryPolicies,                           // from virbius-control
    // LLM injection detection is delegated to the engine over HTTP (triggered via need_llm_check
    // calling /v1/memory/check); no guard model is embedded locally
}

pub struct MemoryPolicies {
    pub desensitize_on_write: bool,                     // desensitize on write
    pub detect_injection_on_write: bool,                // injection detection on write
    pub detect_injection_on_read: bool,                 // injection detection on read
    pub max_memory_entry_size: usize,                   // maximum single memory entry size (default 4KB)
    pub blocked_patterns: Vec<String>,                  // patterns prohibited from writing
}

pub struct MemoryAuditEvent {
    pub trace_id: String,
    pub session_id: String,
    pub operation: MemoryOp,                            // Write / Read
    pub original_size: usize,
    pub desensitized: bool,
    pub injection_detected: bool,
    pub action: MemoryAction,                           // Allow / Filter / Block
    pub risk_delta: u32,
}
```

**Desensitization strategy**:

| Scenario | Handling |
|------|------|
| Memory write contains PII (phone number/ID number/email/bank card) | Write after desensitization (reuses dlp/engine.rs desensitize_in), store original value in vault |
| Memory write contains credential/key patterns | Direct block + audit |
| Read recalled content contains PII | No desensitization (Agent needs original values to execute tools), but audit record |

**Injection detection**:

| Detection Item | Mechanism | Hit Action |
|--------|------|---------|
| "ignore previous instructions" and other injection markers | qwen3guard small model judgment | Write: block; Read: filter segment |
| Tool return value written to memory verbatim (not summarized) | Rule: content hash matches tool return value | block + prompt Agent to summarize before writing |
| Memory content exceeds max_memory_entry_size | Rule: size check | block + prompt summarization |

**Integration with session risk**: When memory injection detection hits, session_risk_score +15. If the same session accumulates 3 memory injection hits, force disconnect.

### 2.10 Output Review

> **Tool result review is implemented; Agent final output review is a design suggestion pending application layer integration.** The actual implementation reuses the Engine `/v1/evaluate` endpoint, rather than creating a new standalone `OutputReviewer` class. Tool result review is already implemented in the MCP Proxy; Agent final output review (Plan B) requires the application layer to call `/v1/evaluate` itself; the codebase currently does not contain application layer integration code. See [DESIGN.md §13.7](DESIGN.md#137-output-review).

> STI Taint (§5.4) reviews **tool return values**; Output Review reviews the **Agent's final response to the user**—content generated by the LLM after summarizing tool results. The two cover different stages.

**Review flow**:

```
Tool returns result (egress / non-egress two paths)
  |
  v
mask_pii_in_response()    ← PII desensitization (existing)
  |
  v
tag_tool_result()          ← Trust boundary tagging (existing)
  |
  v
review_tool_output()       ← Content security review (new)
  +-- extract_result_text()        extract text from resp.result.content[].text
  +-- should_review_output()       conditional trigger: text.len() ≥ 512 || risk_score ≥ 50
  +-- pipeline.review_output()    call POST /v1/evaluate { content, role: "output" }
  |   +-- Engine reuses PromptRunner (qwen3guard) + ScriptRuleRunner (groovy) -> PolicyMerger
  +-- if deny -> replace_result_text() replace with safety notice
       if engine unavailable -> decide allow or block based on fail_open

Agent final response (Plan B: application layer call, ⏳ design suggestion/pending application layer integration)
  |
  v
Application layer POST /v1/evaluate { content: "<Agent output>", role: "output" }
  +-- Engine same pipeline classification -> deny then desensitize/block
```

**Review dimensions**:

| Dimension | Mechanism | Trigger Condition | Hit Action |
|------|------|---------|---------|
| **PII leakage** | dlp/engine.rs entity recognition (`mask_pii_in_response`) | Each tool output | Desensitize then return + audit |
| **Credential leakage** | Regex (API key/token/password patterns) + small model assistance | Each tool output | Desensitize then return + audit |
| **Content safety** | qwen3guard small model (reuses Engine `prompt` runtime) | Output >512 characters or session_risk > 50 | block + audit + raise risk_score |
| **Policy compliance** | Groovy rule engine (scene-related output constraints) | Each tool output | block or challenge + audit |

**Division of labor with STI Taint**:

| Detection Layer | Target | Phase | Mechanism |
|--------|---------|------|------|
| **STI Taint (§5.4)** | Tool return values | After tool execution, before Agent summarization | Small model determines injection |
| **Tool result review (this section)** | Tool return values | After PII desensitization + trust tagging | Reuses Engine rule pipeline (qwen3guard + groovy) |
| **Agent output review (Plan B)** | Agent final response | After Agent summarization, before returning to user | Application layer calls `/v1/evaluate` (⏳ design suggestion/pending application layer integration) |

> Three layers cover the complete review chain from tool results to final output.

**Implementation** (MCP Proxy side, not virbius-core):

```rust
// virbius-mcp-proxy/src/pipeline.rs

/// Review tool output content via the Engine (reuses POST /v1/evaluate).
pub(crate) async fn review_output(
    &self,
    session: &Session,
    tool_name: &str,
    content: &str,
) -> Result<EvaluateResponse, EngineError> {
    let req = EvaluateRequest {
        trace_id: &session.trace_id,
        session_id: &session.session_id,
        app_id: &session.app_id,
        tenant_id: &session.tenant_id,
        tool_name,
        args: &serde_json::Value::Null,
        args_json: String::new(),
        license_risk_quota: 100,
        content: Some(content),
        role: Some("output"),
    };
    self.engine.evaluate(&req).await
}

/// Check if output review should be triggered.
pub fn should_review_output(&self, text: &str, session_risk_score: u32) -> bool {
    if !self.output_review.enabled {
        return false;
    }
    text.len() >= self.output_review.min_text_length
        || session_risk_score >= self.output_review.min_risk_score
}
```

```rust
// virbius-mcp-proxy/src/router.rs

async fn review_tool_output(
    resp: &mut Value,
    session: &Session,
    tool_name: &str,
    pipeline: &SecurityPipeline,
) {
    let text = extract_result_text(resp);
    if text.is_empty() || !pipeline.should_review_output(&text, session.session_risk_score) {
        return;
    }
    match pipeline.review_output(session, tool_name, &text).await {
        Ok(eval_resp) => {
            if eval_resp.effective_action == "block" || eval_resp.effective_action == "deny" {
                replace_result_text(resp, &format!("[Content blocked: {}]", ...));
            }
        }
        Err(_) => {
            if !cfg.fail_open {
                replace_result_text(resp, "[Content blocked: safety review unavailable]");
            }
        }
    }
}
```

**Configuration**:

```toml
# virbius-mcp-proxy.toml
[security.output_review]
enabled = true
min_text_length = 512
min_risk_score = 50
fail_open = true
```

**Cost control**: PII/credential detection uses rules + regex, no LLM call. Content safety detection reuses qwen3guard small model, only triggered on high risk (output >512 characters or session_risk > 50), not on every call.


---

## 3. Gateway — Higress North-South Security Gateway

### 3.1 Responsibilities

The Gateway layer is handled by Higress, positioned as a **North-South traffic gateway** (Ingress + Egress), implementing security plugins based on Envoy/WASM:

```
=== Ingress (inbound) ===
Remote Agent -> Higress (TLS/rate-limit/security precheck) -> MCP Server (Python/Node)
              |
              +-- virbius-gateway WASM plugin
              +-- tool allowlist (WASM allowlist module)
              +-- counter (WASM Redis module)
              +-- fast path judgment
              +-- call virbius-engine (Envoy HTTP client POST /v1/evaluate)
              +-- HTTP layer blocking (Envoy direct response 403)

=== Egress (outbound, non-Sidecar mode) ===
Agent -> Higress (Egress Proxy) -> External API
           |
           +-- URL allowlist validation
           +-- Outbound rate limiting
           +-- Audit log
```

> **Topology note**: The Gateway handles North-South (cross-network) traffic. In Sidecar mode, MCP tool calls are East-West traffic and do not pass through the Gateway (§1.1). The Gateway plays a role in the following scenarios:
> - **Ingress**: Remote Agents (non-Sidecar deployment) access MCP Server via HTTPS
> - **Egress**: External HTTP requests initiated by Agents in non-Sidecar mode pass through the Gateway Egress Proxy
> - **Sidecar mode Egress**: Proxied by the Edge Proxy ([§2.6.1](PROTOCOL.md#261-mcp-proxy-full-technical-solution)); the Gateway does not participate

| Capability | Direction | Implementation |
|------|------|---------|
| TLS termination | Ingress | Higress native (Envoy) |
| MCP protocol routing | Ingress | Higress MCP Gateway (native Streamable HTTP/SSE) |
| Rate limiting | Ingress + Egress | Envoy rate limit |
| tool allowlist | Ingress | WASM plugin allowlist module |
| Counter | Ingress | WASM plugin Redis module |
| Call virbius-engine | Ingress | WASM HTTP call POST /v1/evaluate |
| HTTP blocking | Ingress | Envoy direct response 403 + JSON-RPC error |
| URL allowlist | Egress | WASM plugin egress_url_check |

### 3.2 WASM Security Precheck

Security precheck implemented based on Higress WASM plugin (Go language, proxy-wasm-go-sdk):

```go
// virbius-gateway/wasm/access.go

func (p *VirbiusPlugin) onHttpRequestHeaders(ctx wrapper.HttpContext) types.Action {
    toolName := ctx.Headers().Get("x-mcp-tool-name")
    sessionID := ctx.Headers().Get("x-mcp-session-id")

    // 1. tool allowlist (WASM allowlist module)
    if !p.allowlist.Match("tool-allowlist", toolName) {
        return p.deny(ctx, "tool_not_allowed")
    }

    // 2. Accumulated counter (WASM Redis async query)
    count, err := p.redis.Incr("tool:" + toolName + "-session:" + sessionID)
    if err != nil || count > 50 {
        return p.deny(ctx, "tool_rate_exceeded")
    }

    // 3. Fast path judgment
    if p.isFastPath(toolName) && p.getSessionRisk(sessionID) < 30 {
        return types.ActionContinue // allow, skip engine
    }

    // 4. Call virbius-engine final judgment (HTTP async call)
    decision, err := p.callEngine(ctx, toolName, sessionID)
    if err != nil {
        return p.deny(ctx, "engine_error")
    }
    if decision.Action == "block" {
        return p.deny(ctx, decision.Reason)
    }
    return types.ActionContinue
}
```

> **WASM vs Lua differences**: In WASM plugins, Redis and HTTP calls are both asynchronous callback patterns (cannot block), requiring sequential logic through callback chains. Compared to Lua cosocket's synchronous style, the code structure is slightly more complex, but gains connection-lossless hot update capability. In combined deployments, the Gateway can be configured with `evaluate=false`, only doing allowlist + rate limiting, avoiding WASM async callback complexity.

### 3.3 Higress Route Configuration Auto-Generation

MCP routes are compiled by virbius-control into Higress CRD configuration:

```
virbius-control -> mcp_routes table -> virbius-compiler -> Higress CRD -> K8s APIServer
```

Example Higress CRD configuration:

```yaml
# McpBridge — MCP Server Registration
apiVersion: networking.higress.io/v1
kind: McpBridge
metadata:
  name: mcp-github
spec:
  registries:
    - name: github-mcp
      type: static
      domain: mcp-github.internal
      port: 8080
---
# McpServer — MCP Route + WASM Plugin
apiVersion: networking.higress.io/v1
kind: McpServer
metadata:
  name: github-mcp-server
spec:
  bridgeRef: mcp-github
  pathPrefix: /mcp/github
  wasmPlugins:
    - name: virbius-gateway
```

> **Hot update**: After Higress CRD update, Envoy distributes new configuration via xDS, WASM plugin hot-reloads, **SSE long connections are not interrupted**. Compared to Nginx `nginx -s reload` (which briefly disconnects), Higress achieves true connection-lossless hot update.

### 3.4 Schema Validation and PII Desensitization Responsibility Delegation

| Capability | Location | Reason |
|------|------|------|
| schema validation | Edge virbius-core (Rust jsonschema crate) | WASM JSON Schema library capability is weak |
| Input PII desensitization | Edge virbius-core dlp/engine.rs (existing) | Desensitize before sending to LLM |
| Output PII desensitization | Edge virbius-core (before tool return) | Avoid repeated desensitization by Gateway |
| tool allowlist | Gateway Higress WASM | First line of defense at HTTP layer |
| Counter | Gateway Higress WASM | HTTP layer frequency control |
| Engine final judgment | Cloud virbius-engine | Complex semantic judgment |

> **Removed original AgentGateway**: Higress already handles MCP routing + load balancing + protocol conversion; no additional component is needed. The original §3.3 AgentGateway integration and §3.4 comparison table have been removed.

### 3.5 Egress Traffic Control

External HTTP requests initiated by the Agent belong to North-South Egress traffic, divided into two categories:

| Traffic Type | Source | Example | Control Method |
|---------|------|------|----------|
| **Business tool requests** | Explicit tools in MCP `tools/call` | `curl`/`web_search`/`http_request` | Proxy proxying + URL allowlist (§3.5 Sidecar mode) or Gateway Egress Proxy (§3.5 non-Sidecar mode) |
| **Framework implicit requests** | Agent framework/SDK underlying | Config fetching, model downloads, heartbeat detection, telemetry reporting | K8s NetworkPolicy restrict to minimal allowlist targets |

> **Design decision: tool-level control instead of process-level network disconnection**
>
> The original plan stipulated that "Agents do not have direct network outbound capability; all HTTP is proxied by Proxy". However, this breaks compatibility with existing Agent frameworks—LangChain, AutoGen, OpenAI SDK, etc., all implicitly initiate network requests; directly disconnecting the network would prevent them from running. Furthermore, full proxying (proxying all Agent network traffic) requires supporting all HTTP semantics including WebSocket duplex, large file chunked upload, HTTP/2 multiplexing, etc., with extremely high development cost.
>
> Revised plan:
> - **Business tool requests** (explicit MCP tools like `curl`/`web_search`) go through Proxy proxying + URL allowlist validation
> - **Framework implicit requests** (config fetching, model downloads, heartbeats, etc.) are initiated by the Agent itself, restricted to allowlist targets by NetworkPolicy
> - **Process-level full outbound** (P2 fallback) via eBPF/iptables transparent hijacking
>
> This tripartite approach matches the threat model: security threats come from **controllable external requests** initiated by the Agent through business tools, not from the framework's underlying **fixed-target** network calls. Tool-level proxying only needs to support GET/POST + streaming response passthrough (chunked/SSE), achievable with reqwest `bytes_stream()`, with manageable development cost. See [§2.6.1](PROTOCOL.md#261-mcp-proxy-full-technical-solution) Egress traffic control.

#### Sidecar Mode — Tool-level Proxy Proxying

In Sidecar mode, MCP business tool calls (`curl`, etc.) are handed over to the Proxy for proxying via `tools/call`. Implicit network requests from the Agent framework are not proxied by the Proxy and are restricted by NetworkPolicy:

```
Agent ──tools/call("curl", {url: "https://api.internal/..."})──> MCP Proxy
  |
  +-- 1. Parse url parameter
  +-- 2. URL allowlist validation (License allowed_hosts + ToolPolicy allowed_args_schema)
  +-- 3. Security pipeline (precheck -> engine final judgment)
  +-- 4. allow -> Proxy initiates HTTP request (reqwest) -> External API
  +-- 4. deny  -> Return JSON-RPC error
  |
  v
External API
```

```rust
// virbius-mcp-proxy/src/egress.rs

/// Validate whether the curl tool's target URL is in the allowlist
fn validate_egress_url(args: &Value, license: &License) -> Result<(), String> {
    let url_str = args.get("url")
        .and_then(|u| u.as_str())
        .ok_or("missing 'url' parameter")?;
    let url = url::Url::parse(url_str).map_err(|e| format!("invalid url: {e}"))?;
    let host = url.host_str().ok_or("url has no host")?;

    // Validate License allowed_hosts
    let allowed_hosts = license.claims.allowed_hosts
        .iter()
        .filter_map(|h| {
            let parts: Vec<&str> = h.splitn(2, ':').collect();
            Some((parts[0], parts.get(1).and_then(|p| p.parse::<u16>().ok())))
        })
        .collect::<Vec<_>>();

    let port = url.port_or_known_default().unwrap_or(443);
    let matched = allowed_hosts.iter().any(|(h, p)| {
        host == *h && (p.is_none() || *p == Some(port))
    });

    if !matched {
        return Err(format!("host '{}' not in egress allowlist", host));
    }
    Ok(())
}

/// Streaming proxy HTTP request: uses reqwest bytes_stream() to avoid OOM on large responses
///
/// Supports two response modes:
/// - Normal response (JSON/HTML/...): stream read chunks, accumulate up to limit then return
/// - SSE response (text/event-stream): parse events one by one, passthrough to Agent
async fn proxy_egress_request(
    client: &reqwest::Client,
    url: &str,
    method: &str,
    body: Option<&Value>,
    max_bytes: usize,  // default 50MB
) -> Result<EgressResponse, EgressError> {
    let mut req = match method {
        "GET" => client.get(url),
        "POST" => {
            let r = client.post(url);
            match body {
                Some(v) => r.json(v),
                None => r,
            }
        }
        _ => return Err(EgressError::UnsupportedMethod(method.into())),
    };

    let resp = req.send().await.map_err(EgressError::Http)?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(EgressError::Status(status.as_u16(), body));
    }

    let content_type = resp.headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();

    // Stream read response body to avoid OOM on large responses
    use futures_util::StreamExt;
    let mut stream = resp.bytes_stream();
    let mut buf = Vec::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(EgressError::Http)?;
        if buf.len() + chunk.len() > max_bytes {
            return Err(EgressError::TooLarge(max_bytes));
        }
        buf.extend_from_slice(&chunk);
    }

    Ok(EgressResponse {
        status: status.as_u16(),
        content_type,
        body: buf,
    })
}
```

> License needs to be extended with `allowed_hosts` field:
> ```json
> {
>   "app_id": "code-review-agent",
>   "allowed_tools": ["read_file", "search", "curl"],
>   "allowed_hosts": ["api.internal:443", "cdn.internal:443"],
>   ...
> }
> ```

#### Non-Sidecar Mode — Gateway Egress Proxy

In non-Sidecar mode (standalone service deployment), the Agent directly initiates HTTP requests, which need to go through the Gateway Egress Proxy:

```yaml
# Higress Egress route + WASM plugin
apiVersion: networking.higress.io/v1
kind: HttpRoute
metadata:
  name: egress-proxy
spec:
  parentRefs:
    - name: higress-gateway
  hostnames: ["egress.internal"]
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /egress/
      filters:
        - type: ExtensionRef
          extensionRef:
            name: virbius-gateway-egress  # WASM plugin
```

```go
// virbius-gateway/wasm/egress.go

func (p *VirbiusPlugin) checkUrl(uri, sessionID string) bool {
    // Parse target host from /egress/<host>/<path>
    parts := strings.SplitN(strings.TrimPrefix(uri, "/egress/"), "/", 2)
    if len(parts) == 0 || parts[0] == "" {
        return false
    }
    targetHost := parts[0]

    // Query Redis for egress allowlist (from License)
    allowed, err := p.redis.SIsMember("egress:allowlist:"+sessionID, targetHost)
    if err != nil || !allowed {
        return false
    }
    return true
}
```

#### P2 Enhancement — eBPF Transparent Hijacking

P0 relies on Proxy proxying for business tool requests and NetworkPolicy for framework implicit requests. If the Agent process bypasses the MCP protocol and directly initiates TCP connections (e.g., executing `curl` command via `shell` tool), and the target is within the NetworkPolicy allowlist, P0 cannot intercept. P2 provides a fallback through kernel-level traffic hijacking:

```
Agent process
  |
  +-- Normal path: tools/call -> Proxy proxying (P0 covers)
  |
  +-- Bypass path: direct TCP connect() (P2 fallback)
       |
       v
    eBPF sock_ops / TPROXY
       |
       +-- Hijack outbound TCP -> Redirect to Proxy (:9091)
       +-- or iptables REDIRECT -> Egress Gateway
       |
       v
    URL allowlist validation -> allow / deny
```

| Mechanism | Phase | Coverage | Dependency |
|------|------|---------|------|
| Tool-level Proxy proxying + URL allowlist | P0 | MCP business tool calls (`curl`/`web_search` etc.) | No kernel dependency |
| K8s NetworkPolicy | P0 | Agent framework implicit outbound (config fetching, model downloads, etc.) | K8s CNI support |
| Gateway Egress Proxy | P0 | Non-Sidecar mode external HTTP | Higress deployment |
| eBPF sock_ops transparent hijacking | P2 | Process-level all TCP outbound | Kernel 5.8+ + CAP_BPF |
| iptables TPROXY | P2 | Process-level all TCP outbound | NET_ADMIN |
| NetworkPolicy (enhanced) | P2 | Pod-level network isolation | K8s CNI support |

### 3.6 Gateway Portability — Switching to Other MCP Gateways

The Gateway layer is designed to be **pluggable**. Higress is the default implementation, but the architecture allows switching to other gateways (APISIX, Kong, Envoy, Nginx, etc.) with minimal code changes. An APISIX emitter already exists as proof of this design.

#### 3.6.1 Coupling Analysis

Higress coupling is confined to **3 points**. All other modules are completely independent:

| Coupling Point | File | Coupling Level | Description |
|---------------|------|---------------|-------------|
| ① WASM Plugin | `virbius-gateway/wasm/main.go` | Medium | Depends on `higress/plugins/wasm-go` wrapper and `proxy-wasm-go-sdk` |
| ② CRD Emitter | `virbius-compiler/.../HigressCrdEmitter.java` | Low | Generates Higress-specific CRDs (McpBridge, McpServer, WasmPlugin) |
| ③ Docs & Config | `ARCHITECTURE.md`, `DEPLOYMENT.md` | None (descriptive) | Documentation references only |

**Modules that do NOT require changes** when switching gateways:

| Module | Reason |
|--------|--------|
| `virbius-mcp-proxy` (Rust) | Sidecar MCP proxy with its own security pipeline (License + precheck + Engine call); does not go through the Gateway in Sidecar mode |
| `virbius-engine` (Java) | Exposes standard HTTP API (`POST /v1/evaluate`); agnostic to the calling gateway |
| `virbius-control` (Java) | Delivers artifacts (access-lists, scene-registry) via Redis; gateway-agnostic |
| `virbius-core` (Rust) | Edge-layer embedded precheck SDK; no gateway dependency |
| `virbius-policy` (Java) | Policy matching engine; no gateway dependency |

#### 3.6.2 Existing Multi-Gateway Support

The compiler already has a `-g` (gateway backend) flag and two emitter implementations:

```
virbius-compiler/src/main/java/io/virbius/compiler/
  ├── HigressCrdEmitter.java        ← Higress CRD generation (default)
  ├── GatewayApisixEmitter.java     ← APISIX route generation (already exists)
  └── CompilerCli.java              ← -g higress | apisix switch
```

```java
// CompilerCli.java
@Option(names = {"-g", "--gateway"}, defaultValue = "higress",
        description = "gateway backend: higress | apisix")
private String gateway;
```

#### 3.6.3 Code Required to Switch Gateways

To switch to another gateway (e.g., Kong, Nginx, or a custom gateway), implement the following **3 items**:

**① New Emitter** (~100–200 lines Java)

Create a new `GatewayXxxEmitter.java` following the existing `GatewayApisixEmitter` pattern. The emitter reads the same rule bundle JSON and outputs gateway-specific route/plugin configuration:

```java
// Example: GatewayKongEmitter.java
public final class GatewayKongEmitter {
    static int emitRoutes(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        // 1. Generate Kong Route + Service JSON from bundle gateway.routes
        // 2. Configure Kong Plugin (allowlist / rate-limit / engine-call)
    }
}
```

Register it in `CompilerCli.java` (one line):

```java
} else if ("kong".equals(gw)) {
    GatewayKongEmitter.emitRoutes(root, gwDir, json);
}
```

**② Security Plugin** (~300–400 lines Go/Lua/JS)

The current `virbius-gateway/wasm/main.go` implements 5 core functions that must be replicated in the target gateway's plugin language:

| Function | Responsibility | Engine Interaction |
|----------|---------------|-------------------|
| Request header/body interception | Extract `tool_name`, `session_id` from JSON-RPC | None |
| Tool allowlist check | Local match against config | None |
| Rate limiting | Redis INCR per tool+session | Redis |
| Fast-path bypass | Skip engine for low-risk tools | None |
| Engine evaluate | `POST /v1/evaluate` to virbius-engine | HTTP to Engine |
| HTTP 403 block | Direct response + JSON-RPC error | None |
| Challenge response | Return `-32011` with `challenge_id` | None |

Implementation effort by target gateway:

| Target Gateway | Plugin Language | Code Reuse | Effort |
|---------------|----------------|------------|--------|
| APISIX | Lua | Logic reuse, API rewrite | Medium |
| Kong | Lua | Logic reuse, API rewrite | Medium |
| Envoy (standalone) | Go WASM | High — replace wrapper layer only | Low |
| Nginx + njs | JavaScript | Logic reuse, API rewrite | Medium |
| Custom Go gateway | Go | High — direct code reuse | Low |

**③ Artifact Delivery Adaptation** (optional, ~50 lines)

If the new gateway supports pulling artifacts from Redis (access-lists + scene-registry), `GatewayDeliveryService` requires no changes. Otherwise, adapt the delivery mechanism (e.g., HTTP push to Kong Admin API).

#### 3.6.4 Work Estimation

| Work Item | Code Lines | Difficulty | Time |
|-----------|-----------|-----------|------|
| New `GatewayXxxEmitter` | ~150 | Low (templated) | 2–3 hours |
| `CompilerCli` branch | ~5 | Trivial | 5 min |
| New gateway security plugin | ~350 | Medium (well-defined interface) | 4–6 hours |
| Artifact delivery adaptation (if needed) | ~50 | Low | 1 hour |
| Deployment config & docs | — | Low | 1 hour |
| **Total** | **~550** | | **1–2 person-days** |

#### 3.6.5 Key Design Properties Enabling Portability

1. **MCP Proxy is independent of the Gateway** — In Sidecar mode, the entire security pipeline (License verification → precheck → Engine evaluation → challenge) runs inside `virbius-mcp-proxy` without any Gateway involvement
2. **Engine is a standard HTTP API** — Any gateway that can make HTTP `POST /v1/evaluate` can integrate with virbius-engine
3. **Compiler has multi-gateway architecture** — The `--gateway` flag + Emitter pattern means adding a new gateway only requires one new Emitter class
4. **Artifact delivery via Redis** — Control Plane does not directly couple to any specific gateway implementation
5. **Security plugin interface is well-defined** — The 5 core functions (allowlist, rate-limit, fast-path, engine-call, block) have clear contracts that can be reimplemented in any language

---

## 4. Kernel — Falco Observation Engine

### 4.1 Responsibilities

The Kernel layer is the runtime observation layer. P0 only implements observation (eyes), P2 adds enforcement (hands).

| Scope | P0 Observation | P2 Enforcement |
|------|---------|---------|
| Agent process syscalls | Falco eBPF observation (when available) | Landlock file path enforcement |
| Container escape detection | Falco eBPF observation (when available) | gVisor container isolation + Landlock path enforcement |
| SSRF / intranet scanning | Falco eBPF observation of connect | NetworkPolicy network enforcement |
| Infrastructure anomalies | Cloud audit logs (k8s audit / cloudtrail) | Cloud provider native enforcement |

**P0 Security Model**: Kernel layer only observes, does not enforce. Anomaly detected -> report audit stream -> raise session risk score -> Gateway HTTP layer blocks subsequent requests. This is a "detect -> accumulate risk -> block subsequent" model, effective for multi-turn Agent call scenarios.

### 4.2 Falco Driver Degradation Chain

```
detect_mode()
  |
  +-- Has CAP_BPF + kernel 5.8+ + BTF
  |    -> Falco eBPF driver (observation only)
  |
  +-- No CAP_BPF, has CAP_SYS_PTRACE
  |    -> Falco userspace driver (ptrace, 5-10x worse performance)
  |
  +-- No privileges at all
       -> Disabled (no syscall visibility)
```

> **Architecture change (Plan A)**: The `FalcoPlugin` mode and custom `virbius-audit` Go plugin have been removed. Falco is reverted to a pure system-level syscall observation role; cross-layer correlation is handled by Engine `FalcoAlertController` via Redis pidmap reverse lookup. In unprivileged environments, `detect_mode()` returns `Disabled`, with no degradation to plugin mode.

**Separation of observation and enforcement**: The Kernel layer (Falco) is only responsible for observation, not enforcement. Enforcement is handled by the Edge layer's Landlock + drop caps (file path restrictions) and gVisor (untrusted code isolation). This separation ensures that observation layer failures do not affect enforcement capability, and enforcement layer failures still retain observation visibility.

### 4.3 Falco Mode Detection Logic

```rust
// virbius-kernel/src/detect.rs

pub enum KernelMode {
    FalcoEbpf,       // eBPF observation
    FalcoUserspace,  // ptrace driver
    Disabled,         // no privileges, no syscall visibility
}

pub fn detect() -> KernelMode {
    let is_root = unsafe { libc::geteuid() } == 0;
    let cap_eff = read_cap_effective().unwrap_or(0);
    let has_sys_admin = cap_eff & (1 << 21) != 0;
    let has_bpf = cap_eff & (1 << 39) != 0;
    let has_perfmon = cap_eff & (1 << 38) != 0;
    let has_sys_ptrace = cap_eff & (1 << 19) != 0;
    let has_caps = is_root || has_sys_admin || (has_bpf && has_perfmon);

    if !has_caps && !has_sys_ptrace {
        return KernelMode::Disabled;
    }

    let kver = kernel_version().unwrap_or((0, 0));
    let btf_ok = std::fs::metadata("/sys/kernel/btf/vmlinux")
        .map(|m| m.len() > 0).unwrap_or(false);

    if kver < (5, 8) || !btf_ok {
        return if has_sys_ptrace { KernelMode::FalcoUserspace }
               else { KernelMode::Disabled };
    }

    KernelMode::FalcoEbpf
}
```

> **Change**: The `FalcoPlugin` enum variant has been removed. Unprivileged environments directly return `Disabled`, with no degradation to plugin mode.

Falco eBPF mode hard requirements:

| Check Item | Requirement | Common Failure Reasons |
|--------|------|------------|
| Kernel version | >= 5.8 (recommended 5.10+) | Old kernel |
| **BTF** (most critical) | /sys/kernel/btf/vmlinux exists and > 0 bytes | CONFIG_DEBUG_INFO_BTF not enabled |
| Kernel config | CONFIG_BPF=y, CONFIG_KPROBES=y, CONFIG_TRACING=y | Hardened kernel trimmed |
| Privilege | CAP_SYS_ADMIN or CAP_BPF+CAP_PERFMON | serverless / PSA restricted |
| tracefs | /sys/kernel/tracing/ mounted | Not mounted inside container |
| bpffs | /sys/fs/bpf/ mounted | Not mounted inside container |

> **Note**: Tetragon is no longer used for kernel-level enforcement. Enforcement is handled by the Edge layer's Landlock + drop caps (file path isolation) and gVisor (untrusted code sandbox); the Kernel layer Falco focuses on observation. This simplifies deployment dependencies (no need for CONFIG_BPF_KPROBE_OVERRIDE), and decouples observation from enforcement—Falco failures do not affect Landlock/gVisor enforcement capability.

### 4.4 eBPF Observation Programs (P2, when eBPF is available)

> **P2 implementation.** P0 uses Falco's built-in eBPF programs, not self-developed.

eBPF observation points (Falco built-in + custom supplements):

- execve / execveat monitoring (Falco already covers execve, supplement execveat)
- tcp_v4_connect + tcp_v6_connect monitoring (supplement IPv6)
- mount / nsenter / ptrace container escape detection

eBPF Maps (policy data):

| Map Name | Type | Usage |
|----------|------|------|
| exec_allowlist | BPF_MAP_TYPE_HASH | Allowed executable binary paths |
| connect_allowlist_ip | BPF_MAP_TYPE_LPM_TRIE | Allowed IP prefixes for connection |
| connect_allowlist_port | BPF_MAP_TYPE_HASH | Allowed ports for connection |
| agent_cgroups | BPF_MAP_TYPE_HASH | Current protected Agent's cgroup_id set (container-level, unchanged by fork/exec) |

> **Note**: connect_allowlist is split into IP (LPM_TRIE) and Port (HASH) two maps, because LPM_TRIE can only match IP prefixes, not IP:Port.

### 4.5 ~~Falco plugin mode~~ (Removed)

> **Architecture change (Plan A)**: The custom `virbius-audit` Go plugin and `FalcoPlugin` mode have been removed. The original plugin mode was designed to degrade to consuming logs/audit events in serverless environments, but actual deployment revealed:
> 1. Plugin mode has no syscall visibility, conflicting with Falco's core value
> 2. Cross-layer combined judgment (syscall events + Agent context in a single conditional expression) can be achieved through post-hoc correlation by Engine
> 3. Go C-shared library build and maintenance costs are high
>
> **Alternative**: Falco is reverted to pure system-level syscall observation, sending alerts to Engine `FalcoAlertController` via `http_output`, where the Engine completes pidmap reverse lookup and session correlation. Unprivileged environments return `Disabled`.

### 4.6 PID -> trace_id Mapping

The PID mapping query path is the most latency-sensitive link between the Edge and Kernel layers—an Agent process may execute its first syscall <1ms after startup, and cannot rely on Redis network I/O.

#### Host PID vs Namespace PID Issue

In container environments, there are two types of PIDs:

```
┌─── Inside container (PID namespace) ───┐     ┌─── Host (init namespace) ───┐
│                              │     │                               │
│  Agent process: PID = 42        │ ←→ │  Agent process: Host PID = 12345  │
│  Proxy process: PID = 43        │     │  Proxy process: Host PID = 12346  │
│                              │     │                               │
│  getpid() → 42              │     │  Falco/eBPF sees → 12345       │
└──────────────────────────────┘     └───────────────────────────────┘

virbius-core (inside container) calls getpid() → 42 (Namespace PID)
Falco (host) event → host_pid = 12345

If pidmap uses 42 as key, Falco looks up 12345 → miss!
```

| PID Type | Who Sees It | How to Obtain | Falco Event Field |
|---------|--------|---------|------------------|
| **Host PID** | Host/kernel/Falco/eBPF | `bpf_get_current_pid_tgid() >> 32` | `proc.vpid` (Falco) |
| **Namespace PID** | Inside container process | `getpid()` / `libc::getpid()` | `proc.pid` (Falco) |

**Solution**: pidmap uses **Host PID** as the primary key (aligned with Falco events), with **cgroup ID** as the secondary index (container-level association, unchanged by fork/exec).

```
virbius-core register_agent(ns_pid=getpid())
  |
  +-- Auto-detect Host PID: read /proc/self/status → NSpid line
  |   e.g. "NSpid:\t42\t12345" → host_pid = 12345 (last one)
  |
  +-- Auto-detect cgroup ID: read /proc/self/cgroup → "0::/kubepods/..." 
  |   → stat("/sys/fs/cgroup/kubepods/...") → st_ino = cgroup_id
  |
  +-- Write to pidmap:
  |   by_host_pid[12345] = { host_pid: 12345, ns_pid: 42, cgroup_id: 98765, ... }
  |   by_cgroup[98765]   = { ... same ... }
  |
  +-- Async Redis backup: SET pid_trace:12345 '{...}' EX 3600
  |                       SET cgroup_trace:98765 '{...}' EX 3600  (cgroup reverse index, P1)

Falco event arrives (host_pid=12345)
  → Engine FalcoAlertController three-level correlation chain:
    1. lookupSessionByHostPid(12345) → pid_trace:12345 → session_id  ✅ hit
    2. (miss) lookupSessionByCgroup(cgroup_id) → cgroup_trace:{id} → session_id
    3. (miss) lookupSessionByHostPid(ppid) → pid_trace:{ppid} → session_id (ppid fallback)

eBPF program (bpf_get_current_cgroup_id()=98765)
  → lookup_by_cgroup(98765) → hit by_cgroup[98765] → fill in trace_id
```

#### Three-Level Correlation Chain (P1 Implementation)

Engine `FalcoAlertController` performs a three-level session correlation for each Falco alert, in descending priority order:

| Priority | Correlation Key | Redis Key | Coverage Scenario | resolved_by |
|--------|--------|-----------|---------|-------------|
| 1 | `proc.pid` (Host PID) | `pid_trace:{host_pid}` | Agent main process | `pid` |
| 2 | `proc.cgroup.id` | `cgroup_trace:{cgroup_id}` | Grandchild processes, setsid detach, after exec | `cgroup` |
| 3 | `proc.ppid` | `pid_trace:{ppid}` | Direct child processes (ppid is Agent main process) | `ppid` |

**Why cgroup is preferred over ppid**: cgroup is a container-level identity (unchanged by fork/exec within the same container); ppid is a process-level identity (broken beyond depth>1 or after setsid). cgroup can cover grandchild processes and detach scenarios; ppid can only cover direct child processes.

**Degradation strategy**:
- cgroup v2 + Falco 0.37+: Three-level correlation fully available
- cgroup v1 / old Falco / macOS: `proc.cgroup.id=0` automatically skips cgroup lookup, degrades to ppid fallback
- No Redis: All degraded, alerts ignored (does not affect normal Agent operation)

#### Storage Hierarchy

| Storage | Key | Value | Lifecycle | Read Latency |
|------|-----|-------|---------|---------|
| **In-process pidmap** `by_host_pid` (in-memory HashMap, virbius-kernel) | Host PID | trace_id + session_id + ns_pid + cgroup_id | Agent lifecycle | <1μs (zero latency) |
| **In-process pidmap** `by_cgroup` (secondary index) | cgroup_id | Same as above | Same as above | <1μs |
| **eBPF agent_cgroups map** (Kernel layer, when eBPF available, P2) | cgroup_id | 1 (monitored flag) | Written at Agent startup, removed at exit | <1μs (kernel table lookup) |
| Redis `pid_trace:{host_pid}` (async backup) | Host PID | trace_id + session_id + host_pid + cgroup_id | TTL 1h | Engine FalcoAlertController query |
| Redis `cgroup_trace:{cgroup_id}` (cgroup reverse index, P1) | cgroup_id | Same as above (shared value with pid_trace) | TTL 1h | Engine FalcoAlertController cgroup correlation query |

> **eBPF map changed to cgroup_id instead of PID**: The original `agent_pids` map used PID as the key, but in container environments, Host PID changes frequently (process fork/exec). Changed to `agent_cgroups` map using cgroup_id as the key—cgroup remains unchanged during the container lifecycle; eBPF programs use `bpf_get_current_cgroup_id()` to look up the table, no PID translation needed.

**Query priority**: In-process pidmap `by_host_pid` (fastest) → `by_cgroup` (secondary) → Redis (fallback)

**Registration timing**: After Agent startup and before executing any tool, virbius-core calls `register_agent()` in `bootstrap()`. This function automatically detects Host PID and cgroup ID from `/proc/self/status` and `/proc/self/cgroup`, without requiring the caller to be aware of the container environment. Registration is a memory operation, completed in <1μs.

**fork/exec safety**:
- **cgroup_id unchanged**: fork/exec within the same container does not change cgroup_id; the `by_cgroup` index is always valid.
- **Host PID changes**: When an Agent forks a child process, the child has a new Host PID. If tracking child processes is needed, the Edge Proxy registers the child process PID immediately after `posix_spawn` (P2 sandbox scenario).
- **Race window**: `register_agent` is called after fork and before the child process executes any tool. During this window, Falco may observe unregistered child process events—these events have no trace_id association, but the Edge License/precheck are still in effect; the Kernel layer observes these as "anonymous events" (labeled `unregistered_pid`).

> **Stale mapping protection**: When an Agent crashes, PID mappings cannot be cleaned up. In-process pidmap is automatically freed when the process is destroyed; eBPF `agent_cgroups` map is automatically reclaimed by the kernel when cgroup is destroyed; Redis backup relies on TTL.

### 4.7 Deployment Modes

| Mode | Determination Condition | Observation | Enforcement |
|------|---------|------|------|
| host | Bare metal/self-managed VM + root | Falco eBPF (P2) | Landlock + gVisor |
| daemonset | K8s standard node pool + privileged | Same as above | Same as above |
| pod-observe | serverless (Fargate/Autopilot) | Cloud vendor alerts (no syscall visibility) | Edge Landlock (P2) + NetworkPolicy |
| audit-only | Early stage observation | Read-only subset of above observation | None |

> **Removed original sidecar mode**: Sidecar mode was self-contradictory—Falco also requires eBPF privileges; Landlock cannot be applied by a sidecar to other containers (must be declared in Pod spec). In serverless environments, Landlock profile is injected into Pod spec via mutating admission webhook.

### 4.8 Custom Falco Rule Management

Falco rules are centrally managed through the Cloud layer control plane, reusing `tb_rules` / `tb_rules_current` tables (`layer='falco'`, `runtime='falco'`), no separate table needed.

#### 4.8.1 Rule Format

`body` field is JSON:

```json
{
  "condition": "evt.type=open and fd.name contains /etc/shadow",
  "output": "Shadow file accessed by %proc.name (pid=%proc.pid)",
  "priority": "CRITICAL",
  "tags": ["filesystem", "security"]
}
```

| Field | Type | Description | Default Value |
|------|------|------|--------|
| `condition` | string | Falco condition expression | `evt.num > 0` |
| `output` | string | Alert output template | `Falco rule triggered (rule=...)` |
| `priority` | string | Taken from the rule line's `reason_code`, defaults to `WARNING` if not filled | `WARNING` |
| `tags` | string[] | Tag array (optional) | Empty |

#### 4.8.2 Canary Deployment

Reuses deploy-rollout state machine (`PENDING → CANARY → FULL → FINALIZED`), using node label `virbius-falco-canary=true` to distinguish canary/stable pools:

```
virbius-control
  +-- FalcoConfigBuilder    reads tb_rules_current(layer='falco') → generates Falco YAML
  +-- FalcoArtifactStore    Redis stores rule YAML + Stream notifications
  +-- DeployRolloutService  state machine orchestration
  |
  +-- Redis Stream
      +-- :canary  → canary Falco nodes
      +-- :full     → stable Falco nodes

Falco node Pod:
  config-subscriber (Rust sidecar)
    Redis Stream consumption → write /etc/falco/falco_rules.d/{tenant}-{target}.yaml → SIGHUP reload
```

#### 4.8.3 Falco http_output Configuration (Plan A)

Falco sends alerts to Engine `FalcoAlertController` via `http_output`, replacing the original `program_output` mode:

```yaml
# virbius-kernel/deploy/falco-config.yaml
http_output:
  enabled: true
  url: "http://virbius-engine.virbius-system.svc.cluster.local:8080/api/internal/falco-alert"
  user_agent: "falco/virbius"
  connection_keepalive: true
  retry_wait_seconds: 5

rules_file:
  - /etc/falco/falco_rules.d/   # config-subscriber hot-reload directory
```

**Data flow**:
```
Falco eBPF → alert triggered → http_output POST → Engine FalcoAlertController
  → three-level correlation (pid → cgroup → ppid) → SessionRiskManager.onFalcoAlert()
  → Redis INCR session:{id}:falco_pending → next updateRiskScore() consumes
```

**Falco rule output field requirement**: The rule `output` template must include `%proc.cgroup.id`, otherwise the cgroup correlation path cannot take effect (`proc.cgroup.id` requires Falco 0.37+ modern eBPF driver).

#### 4.8.4 Example

See [README.md](README.md#quick-start) or the ops.html front-end operation interface.

### 4.9 Unified Sandbox Rule Management (Falco + Landlock + gVisor)

The three types of rules in the Kernel layer—Falco observation rules, Landlock file isolation rules, and gVisor sandbox configuration—are all managed through the `tb_rules` table, reusing the same CRUD + publish + canary deployment workflow.

#### 4.9.1 Rule System Comparison

| Rule Type | `layer` | `runtime` | `body_json` Content | Target | Delivery Method |
|----------|---------|-----------|------------------|---------|---------|
| **Falco observation** | `falco` | `falco` | condition/output/priority/tags | Kernel layer Falco nodes | Redis Stream → config-subscriber → YAML |
| **Landlock isolation** | `sandbox` | `landlock` | tool_name/read_paths/write_paths/exec_paths | Edge layer EdgeManifest | REST → manifest JSON → SDK pull |
| **gVisor sandbox** | `sandbox` | `gvisor` | runsc_path/memory_limit/cpu_quota/... | Edge layer EdgeManifest | REST → manifest JSON → SDK pull |

#### 4.9.2 Landlock Rule Format

Each Landlock rule is bound to a `tool_name`, defining the path allowlist accessible to that tool in the sandbox:

```json
{
  "tool_name": "read_file",
  "read_paths": ["/tmp/data/*", "/home/user/workdir/*", "/usr/lib/*"],
  "write_paths": [],
  "exec_paths": ["/usr/bin/cat", "/usr/bin/head"]
}
```

Ops console operation flow: Create new rule → select `sandbox` layer → select `landlock` runtime → edit JSON body → save → policy publish → deploy → Edge SDK pulls manifest → takes effect during P2 sandbox execution.

#### 4.9.3 gVisor Rule Format

gVisor rules are global configuration (the first rule in `full` state takes effect), defining resource limits for untrusted code execution containers:

```json
{
  "runsc_path": "/usr/local/bin/runsc",
  "rootfs_path": "/opt/virbius/rootfs",
  "min_warm": 2,
  "max_idle": 5,
  "memory_limit_bytes": 268435456,
  "cpu_quota": 1.0,
  "network_disabled": true,
  "exec_timeout_ms": 30000
}
```

#### 4.9.4 Delivery Chain

```
virbius-control (single source of truth)
  |
  +-- tb_rules (layer='falco')    → FalcoConfigBuilder → YAML → Redis Stream → Falco nodes
  +-- tb_rules (layer='sandbox')  → ArtifactService.buildLandlockProfiles() / buildGvisorConfig()
  |                                  → EdgeManifest JSON → REST API → Edge SDK pull
  |
  +-- Ops console ops.html
      +-- Navigation: 🦅 falco / 🔒 sandbox
      +-- Rule editor: JSON body + validation + preview
      +-- Policy publish: draft → dry_run → canary → full (reuses existing state machine)
```

#### 4.9.5 Ops Console Integration

| Feature | Implementation |
|------|---------|
| Rule navigation | ops.html navigation bar adds `🔒 sandbox` button, alongside `🦅 falco` |
| Layer/runtime | `LAYER_RUNTIMES.sandbox = ['landlock', 'gvisor']`, ops console auto-adapts |
| Rule editing | JSON body editor (same experience as falco rule editing), supports landlock/gvisor templates |
| Rule validation | Parse JSON body on save, validate required fields (tool_name / read_paths, etc.) |
| Policy publish | Reuses `draft → dry_run → canary → full` state machine, consistent with falco/edge/cloud rules |
| Canary deployment | sandbox layer added to `DeployRolloutController.diff-rules` layer list |
| Manifest delivery | `ArtifactService.writeEdgeManifestFile` adds `landlock_profiles` + `gvisor_config` fields |

---

## 5. Cloud — Unified Policy Brain

### 5.1 Responsibilities

References VirbiusLLM's virbius-engine + virbius-control design with extensive extensions for Agent-specific scenarios (see [§10](DESIGN.md#10-relationship-with-virbiusllm)).

### 5.2 New Rule Types

| Rule Type | Description | Example |
|----------|------|------|
| **tool-allowlist** | Allowlist of tools an Agent is permitted to call | allow: [read_file, search, curl] |
| **tool-arg-schema** | JSON Schema validation rules for tool parameters | read_file.path must match regex |
| **tool-rate-limit** | Frequency limits per session/tool dimension | read_file: 50/min |
| **tool-chain-detect** | Dangerous tool call chain detection | read_secret -> curl = block |
| **session-risk-threshold** | Session-level risk score threshold | session_risk > 80 = disconnect |
| **ebpf-policy** | eBPF/Falco allowlist configuration for Kernel layer | exec_allowlist: [python3, node] |

### 5.3 Groovy L3 Agent Rules

> **Architecture change**: The existing virbius-engine's each /v1/evaluate call is stateless. Agent security requires cross-request session context. New Redis session storage added.

**Session State Storage (Redis)**:

| Key | Type | TTL | Usage |
|-----|------|-----|-----|
| session:{id}:tool_history | List | 1h | Last N tool call records |
| session:{id}:risk_score | String | 1h | Session risk score (0-100) |
| session:{id}:tool_count:{tool_name} | Counter | 1h | Call count per tool dimension |
| pid_trace:{host_pid} | String | 1h | Host PID -> trace_id + session_id + cgroup_id mapping |

**Redis I/O optimization**: engine preloads session context into memory at evaluate entry; Groovy ctx reads from memory, not directly from Redis. Avoids N rules = N Redis I/O operations.

```groovy
// Rule ID: agent-tool-chain-detect
def decide(ctx) {
    def history = ctx.sessionHistory(5)
    def tools = history.collect { it.tool_name }

    // Dangerous chain: first read sensitive data, then send externally (check order)
    def readSecretIdx = tools.indexOf("read_file")
    def curlIdx = tools.indexOf("curl")
    if (readSecretIdx >= 0 && curlIdx >= 0 && readSecretIdx < curlIdx) {
        def curlTarget = history[curlIdx]?.args?.url
        if (curlTarget && !isInternalHost(curlTarget)) {
            ctx.audit("dangerous chain: read_file -> curl to external")
            ctx.incrementRiskScore(20)
            return true  // block
        }
    }

    // Repeated call chain: last N tool calls are all of the same type
    if (tools.size() >= 10 && tools.every { it == "search" }) {
        ctx.audit("possible data exfiltration via repeated searches")
        ctx.incrementRiskScore(15)
        return true
    }

    return false
}
```

**Groovy ctx API**:

| Function | Description | Data Source |
|------|------|---------|
| ctx.var(name) | Read native variables (e.g. `tool_name`, `tool_session_key`) | Engine auto-injection + `vars` map passed by caller |
| ctx.vars() | Return read-only view of all native variables | Same as above |
| ctx.sessionHistory(n) | Last N tool calls | Preloaded from Redis LRANGE |
| ctx.sessionRiskScore() | Current session risk score | Preloaded from Redis GET |
| ctx.incrementRiskScore(delta) | Raise risk score | Async write Redis INCRBY |
| ctx.isInternalHost(url) | Determine if URL points to internal network | Based on CIDR/domain list configured in License or policy |

**`ctx.var()` native variable list**:

Request factors (`tb_context_bindings`) and extended factors (`tb_extended_vars`) have been removed from the code; `ctx.var()` no longer supports user-defined context bindings and expression-derived variables, retaining only the following engine auto-injected native variables:

| Variable Name | Injection Timing | Description | Example Value |
|--------|---------|------|--------|
| `tool_name` | Always injected by `EvaluateOrchestrator` | Currently called tool name | `read_file`, `curl` |
| `tool_session_key` | Injected by `EvaluateOrchestrator` when both `toolName` and `sessionId` are non-null | Unique composite key per tool per session, used for cumulative aggregation | `tool:read_file-session:sess-001` |

Other variables (such as `app_id`, `user_id`, `ip`, `command`, etc.) are explicitly passed by the caller (gateway / SDK / test script) through the request's `vars` field; the engine does not perform any automatic parsing or derivation. Rule scripts need to check for null before use in `decide(ctx)`:

```groovy
def appId = ctx.var("app_id")
if (appId != null && appId == "restricted-app") {
    return true
}
```

### 5.4 Semantic Audit — STI Protocol

Implement STI (Suitability, Taint, Integrity) semantic audit in Groovy L3, calling LLM on-demand only for high-risk scenarios:

| Dimension | Trigger Condition | LLM Call | Description |
|------|---------|---------|------|
| **Suitability** | session_risk > 50 or tool first called in this scene | No (rules) | Validate tool_name + args comply with scene's least privilege |
| **Taint** | Tool return value length > 2KB or contains injection markers | Yes (LLM) | Check if external injection instructions are present in tool return values |
| **Integrity** | Parameter type does not match schema or contains Base64/Hex | No (rules) | Validate if parameters have been tampered with |

> **Cost control**: STI LLM calls go through mlPredict using a dedicated small model (qwen3guard:0.6b), not the large model. The small model is locally deployed (Ollama), single call <200ms.

### 5.5 Unified Control Plane Delivery

```
virbius-control (single source of truth)
  |
  +-- tb_tool_policies        -> Edge layer tool policy + schema
  +-- tb_mcp_routes           -> Gateway layer Higress MCP route configuration
  +-- tb_kernel_policies      -> Kernel layer Falco rules + eBPF allowlist maps
  +-- tb_rules_current        -> Cloud layer Groovy L3 + Prompt L1 rules (existing)
  +-- tb_app_licenses         -> Agent Runtime License (app_id -> license)
  |
  +-- Runtime state (Redis, not database)
      +-- session:{id}:tool_history
      +-- session:{id}:risk_score
      +-- session:{id}:tool_count:*
      +-- pid_trace:{pid}
      +-- license:revoked:{app_id}  -> revocation flag (pub/sub notifies each layer)
```

Publishing workflow reuses existing PublishOrchestrator: draft -> dry_run -> canary -> full

Each layer independently controls rollout:
- Edge canary: by device_id hash
- Gateway canary: by tenant_id
- Kernel canary: by Agent PID hash
- Cloud canary: by session_id (existing)

Control plane delivery method:

```
virbius-control
  |
  +-- REST (existing)
  |   +-- -> virbius-engine: Groovy L3 + Prompt L1 rules
  |   +-- -> Higress: allowlist + counters (via WasmPlugin CRD)
  |
  +-- REST (new)
  |   +-- -> virbius-kernel: Falco rules + eBPF maps
  |
  +-- Higress CRD (new)
      +-- -> Higress: MCP route + WasmPlugin configuration (generated by virbius-compiler)
```

### 5.6 Audit Integrity (Hash Chain)

> **✅ Implemented.** Located at `virbius-control/src/main/java/io/virbius/control/audit/`, see [DESIGN.md §13.5](DESIGN.md#135-audit-integrity-hash-chain).

Tamper-proof audit chain: each audit event contains the SHA-256 hash of the previous event, forming a **per-tenant isolated** chain structure. Any tampering causes the chain to break, detectable by verification.

**Core components**:

| Component | Responsibility |
|------|------|
| `HashChainOrchestrator` | Attaches `audit_seq` / `prev_hash` / `curr_hash` to audit events, Redis Lua CAS atomic update + MySQL optimistic lock degradation |
| `HashChainVerifier` | Reads events from DB and validates sequentially for sequence number continuity + prev_hash chain + curr_hash recomputation |
| `HashChainVerifyTask` | `@Scheduled` hourly automatic verification of last 7 days of audit chains for all tenants |
| `AuditAdminController` | REST API: `POST /audit/verify` (manual verification) + `GET /audit/chain/status` (chain status query) |

**Data flow**:

```
Audit events from all layers
  │
  ▼
virbius-control AuditService
  ├── HashChainOrchestrator.chainBatch(tenantId, events)
  │     ├── Redis: HSET virbius:audit:chain:{tenantId} (Lua CAS, 3 retries)
  │     └── MySQL: tb_audit_chain_state (optimistic lock version, degradation)
  ▼
Write to tb_audit_events (including audit_seq, prev_hash, curr_hash)
  │
  ▼
HashChainVerifyTask (hourly) → HashChainVerifier → recompute + compare → log.error on break
```

**Hash calculation** (13 fields): `prev_hash | seq | tenant_id | trace_id | event_id | effective_action | layer | reason_code | rule_id | scene | user_id | device_id | intercepted_at`

**DB migration**: `V8__audit_hash_chain.sql` — `tb_audit_events` adds 3 columns + `tb_audit_chain_state` chain state table.

---
