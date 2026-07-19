# VirbiusAgent Roadmap — ROADMAP

| Project | Description |
|---------|-------------|
| Doc Version | v3.6 |
| Status | Draft |
| Related | [DESIGN.md](DESIGN.md) (Index) · [ARCHITECTURE.md](ARCHITECTURE.md) |

> This document contains §11 Roadmap (P0/P1/P2 phased plan) + Changelog.

---

## 11. Roadmap

### P0 — Core Security Pipeline (Identity + Observability + HTTP Blocking + Prompt Gateway)

| Task | Component | Estimate |
|------|-----------|----------|
| Runtime License issuance + verification + revocation | control + all layers | 3w |
| Prompt Gateway basic (constitution injection + PII desensitization) | virbius-core | 3w |
| Enterprise AI Agent Constitution v1 (rule definition + compilation) | control + compiler | 2w |
| Edge layer precheck (arg validation + allowlist + JSON Schema) | virbius-core | 2w |
| Edge MCP Server integration (PyO3 / napi-rs / subprocess) | virbius-core | 3w |
| MCP Proxy (stdio/SSE proxy + security pipeline + session management) | virbius-mcp-proxy | 3w |
| Gateway layer Higress WASM plugin (allowlist + counters + engine call) | virbius-gateway | 3w |
| Gateway Higress route config auto-generation (control -> compiler) | control + compiler | 2w |
| Cloud layer Redis session state (history + risk + counts) | engine | 3w |
| Cloud layer Groovy L3 Agent rules (tool chain detection + scenario matching) | engine | 2w |
| Cloud layer Groovy ctx extensions (sessionHistory / riskScore, memory preload) | engine | 2w |
| Control plane Agent rule CRUD + rollout | control | 2w |
| Kernel layer Falco deployment + eBPF driver (standard node pool) | virbius-kernel | 2w | ✅ Done |
| Kernel layer Falco plugin mode (serverless fallback: k8saudit + filetail) | virbius-kernel | ~~2w~~ | ❌ Removed (Plan A) |
| Kernel layer PID→trace_id mapping + audit reporting | virbius-kernel | 1w | ✅ Done |
| Kernel layer Falco http_output → Engine FalcoAlertController | virbius-kernel + engine | 1w | ✅ Done |
| End-to-end integration tests | All components | 3w | ✅ Done |
| **P0 Total** | | **~36w** |

### P1 — Enhanced Observability + Memory Control

| Task | Description | Status |
|------|-------------|--------|
| Edge fast path (low-risk tools skip cloud layer) | Latency optimization | ✅ Done |
| Custom virbius-audit Falco plugin | Consumes Redis Stream, Agent-specific rules | ❌ Removed (Plan A) |
| Audit dashboard | session risk + tool calls + alert visualization | ✅ Done |
| STI semantic audit (Taint dimension with small model) | Tool return value injection detection | ✅ Done |
| Prompt injection detection (prompt runtime repositioned) | User input jailbreak/injection detection, shares qwen3guard with STI Taint | ✅ Done |
| Output PII desensitization (edge, before tool return) | Reuses virbius-core dlp/engine.rs | ✅ Done |
| Falco rule library expansion + custom rule management | Control plane unified Falco rule management, canary deployment | ✅ Done |
| Falco http_output path + 3-level correlation chain (pid→cgroup→ppid) | Engine FalcoAlertController + Redis pidmap/cgroup lookup | ✅ Done |
| High-risk tool human approval flow | engine → approval UI → timeout deny | ✅ Done |
| Session risk adaptive model | Upgraded from rule threshold to weighted accumulation | ✅ Done |
| Audit integrity (hash chain) | Tamper-proof | ✅ Done |
| Cumulative counter Engine-side Ingest (A1) | Config-driven tool call frequency circuit breaking | ✅ Done |
| Memory control (Memory Interceptor) | Agent memory read/write interception + desensitization + injection detection | ✅ Done |
| Agent decision trace | input → reasoning → tool_call → tool_result → output full chain trace | ✅ Done |

### P2 — Enforcement (Hands) + TEE

| Task | Description |
|------|-------------|
| Landlock + drop caps sandbox | File path restriction + capabilities dropping + ABI version adaptation |
| gVisor warm pool | Untrusted code execution sandbox |
| eBPF custom observability programs (execveat + IPv6) | Supplement Falco built-in rules |
| End-to-end red team testing | Security verification |

### Phase Comparison

| Phase | Observability (Eyes) | Enforcement (Hands) | New Capabilities |
|-------|---------------------|--------------------|-----------------|
| P0 | Falco + access log + Redis audit + STI + Prompt Gateway | HTTP 403 + License + allowlist + counters + schema + risk disconnect | Identity control + prompt enhancement |
| P1 | STI Taint + Prompt injection detection + Falco http_output 3-level correlation + audit integrity + decision trace | Human approval + adaptive risk + memory control | Memory control + prompt jailbreak detection + decision trace visualization |
| P2 | eBPF custom observability | Landlock + gVisor + TEE | syscall-level isolation |

---

## Changelog

### v3.3 (2026-07-08)

**New Features**

- **Agent decision trace system**: Full-chain recording of every Agent decision from input to output, supporting session-level timeline, trace-level causal chain, and tool-dimension search
  - DB: `tb_agent_trace` table (V6 migration) + `tb_trace_ingest_checkpoint` checkpoint table
  - Proxy: `trace_collector.rs` module (TraceEvent + TraceCollector + Redis XADD), `session.rs` adds `step_seq` / `last_step_id` step tracking fields, `router.rs` collects trace events at `tool_call` and `tool_result` key points
  - Control: `TraceIngestService` consumes Redis Stream and writes to DB, `TraceQueryService` provides session timeline / trace chain / search queries, REST API mounted at `/api/v1/admin/tenants/{tenantId}/trace/*`
  - Ops console: New "Decision Trace" panel with search + timeline visualization + Ingest health status
- **High-risk tool human approval flow (P1)**: Full-chain closed loop completed
  - Engine: `ChallengeService` Redis state machine (create → approve/reject → verify token), `EvaluateOrchestrator` auto-creates approval records on `challenge` action
  - Control: `ChallengeController` proxies Engine API to ops console
  - Proxy: `PipelineResult::Challenge` interception + `challenge_token` retry verification
  - DB: `tb_challenge_audit` audit persistence (V5 migration)
  - Ops console: Approval queue panel (5s polling + approve/reject)

**Documentation Updates**

- Added [DESIGN.md §12](DESIGN.md#12-agent-security-risk-assessment-framework) Agent Security Risk Assessment Framework: 7-dimensional risk assessment + assessment methodology (5 steps) + security assurance comparison table
- Added [DESIGN.md §13](DESIGN.md#13-p1-feature-detailed-design) P1 Feature Detailed Design: Covers 7 P1 features (Prompt injection detection, STI Taint, Session Risk adaptive, audit integrity hash chain, memory control, output review, virbius-audit Falco plugin + rule library) + implementation priority recommendations

### v3.6 (2026-07-18)

**Falco Reverted to Pure System-Level + http_output Path (Plan A)**

- **Removed custom virbius-audit Go plugin**: `virbius-kernel/plugin/` directory, `falco_plugin.rs` module, `KernelMode::FalcoPlugin` enum variant all removed. Falco returns to pure system-level syscall observation role.
- **P0 syscall path completed**: Falco `http_output` → Engine `FalcoAlertController` (`POST /api/internal/falco-alert`) → Redis pidmap lookup → `SessionRiskManager.onFalcoAlert()`. Includes ppid fallback.
- **P1 cgroup correlation path**: `pidmap.rs` adds `cgroup_trace:{cgroup_id}` Redis reverse index; `FalcoAlertController` adds 3-level correlation chain `host_pid → cgroup_id → ppid`, new `resolved_by` return field.
- **Fixed Spring dependency injection bug**: `PolicyRedisConfig` `@Bean Optional<JedisPool>` changed to `@Bean JedisPool` + `@ConditionalOnProperty`.
- **Fixed virbius-policy test compilation errors**: `BindScopeTest` / `ValueResolverVarDimensionTest` `MatchContext.withBind()` calls aligned with current 7-parameter signature.
- **Test scripts**: `scripts/test-falco-cross-layer.sh` covers 5 scenarios (pid direct hit, ppid fallback, cgroup grandchild process, cgroup setsid detach, non-Agent filtering), macOS simulation mode without Falco.

### v3.5 (2026-07-13)

**Cumulative Counter Engine-side Ingest (A1)**

- **Config-driven cumulative counter auto-write**: Engine auto-traverses `tb_cumulative` definitions after each tool call evaluation, resolves aggregation keys via `ValueResolver` and writes to `CounterStore`, peer to gateway-layer Lua ingest, zero hardcoding
  - `EvaluateOrchestrator`: Injects `tool_name` / `tool_session_key` into `vars`, calls `ingestCumulatives()` + `recordToolCall()` after rule evaluation
  - `ScriptRuleRunner`: New `ingestCumulatives()` method, traverses `PolicyDataCache` cumulative definitions and calls `CounterStore.ingest()`
  - `ScriptRuleRunner`: New `recordToolCall()` delegation method
- **SessionStatePreloader Hash storage refactor**:
  - `preload()`: Added `HGETALL session:{id}:tool_counts`, fixed `toolCounts` always returning empty Map, enabling Groovy rules `ctx.toolCallCount()` to read correctly
  - `recordToolCall()`: Changed from `INCR session:{id}:tool_count:{tool}` to `HINCRBY session:{id}:tool_counts {tool} 1`, unified TTL management
- **Documentation updates**: DESIGN.md added §13.9 Cumulative Counter Engine-side Ingest Design

### v3.4 (2026-07-13)

**Falco Custom Rule Management & Canary Deployment**

- **Go virbius-audit Falco plugin**: `virbius-kernel/plugin/` — Redis Stream audit consumption + PID map correlation + C-shared build
- **Falco rule management**: Reuses `tb_rules` + `layer='falco'`, `RuleLayer.FALCO` enum, `FalcoConfigBuilder` generates Falco YAML from `tb_rules_current`
- **Canary deployment**: `DeployRolloutService` + `FalcoArtifactStore` (Redis storage + Stream notification) reuses existing `PENDING→CANARY→FULL→FINALIZED` state machine
- **Rust config_subscriber**: `virbius-kernel/src/config_subscriber.rs` — Redis Stream consumption → write `/etc/falco/falco_rules.d/` → SIGHUP reload
- **Ops console integration**: Falco layer rule editor, Falco rollout button, Falco canary status dashboard
- **Deployment file updates**: `falco-plugin-daemonset.yaml` adds config-subscriber sidecar, `falco-plugin-config.yaml` adds `rules_file` to load rules.d directory
- **Documentation updates**: README.md adds custom Falco rule example, ARCHITECTURE.md §4.8 adds Falco rule management chapter

### v3.2

- Initial roadmap release
