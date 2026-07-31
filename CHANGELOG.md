# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Route→Tool migration: replaced `route` bind_scope with `tool` scope for Agent security rules.
- Falco reverted to a pure system-level syscall observation role (Plan A): cross-layer correlation (syscall events ↔ Agent session context) is now resolved by the Engine's `FalcoAlertController` via Redis pidmap lookup, instead of injecting context fields inside the Falco engine.
- `virbius-kernel/deploy/falco-config.yaml`: switched from `program_output` to `http_output`, pointing at the Engine internal endpoint, with `connection_keepalive` + `retry_wait_seconds` support.
- `virbius-kernel/deploy/kustomization.yaml`: removed plugin-specific resource references, added `engine-service.yaml`.

### Added
- **`virbius-engine/.../FalcoAlertController.java`**: new `POST /api/internal/falco-alert` endpoint that receives Falco `http_output` native JSON, parses `output_fields["proc.pid"]`, resolves session_id via Redis `pid_trace:{host_pid}` reverse lookup, and calls `SessionRiskManager.onFalcoAlert(sessionId)` to increment the `falco_pending` counter. Includes ppid fallback logic.
- **`FalcoAlertController.lookupSessionByCgroup()`** (P1): three-level correlation chain `host_pid → cgroup_id → ppid`, with a new `resolved_by` response field identifying the hit path (`pid` / `cgroup` / `ppid`). cgroup takes precedence over ppid: cgroup is a container-level identity (stable across fork layers), while ppid is a process-level identity (breaks at depth > 1 or after setsid).
- **`virbius-kernel/src/pidmap.rs`** (P1): `redis_backup_async()` now writes a `cgroup_trace:{cgroup_id}` Redis reverse index. Agent registration writes both `pid_trace:{host_pid}` (primary index) and `cgroup_trace:{cgroup_id}` (reverse index); both keys point to the same JSON value and share a 3600s TTL. Skipped when cgroup_id = 0 (graceful degradation on macOS / cgroup v1).
- **`virbius-kernel/deploy/engine-service.yaml`**: new Engine K8s Service definition.
- **`virbius-kernel/deploy/falco-daemonset.yaml`**: new `config-subscriber` sidecar container that consumes Redis Stream to hot-reload Falco rule files.
- **`scripts/test-falco-cross-layer.sh`**: new macOS simulation test script covering 5 scenarios — pid direct hit, ppid fallback, cgroup grandchild process (ppid chain broken, cgroup hit), cgroup setsid detach (ppid=1, cgroup hit), and non-Agent filtering.

### Removed
- Custom `virbius-audit` Falco plugin (Go C-shared library):
  - `virbius-kernel/plugin/` directory (Go virbius-audit plugin source)
  - `virbius-kernel/src/falco_plugin.rs` module
  - `KernelMode::FalcoPlugin` enum variant in `detect.rs` (fallback path changed to `Disabled`)
  - `falco_plugin` module declaration and exports in `lib.rs`

### Fixed
- **`virbius-engine/.../config/PolicyRedisConfig.java`**: fixed Spring dependency injection — `@Bean Optional<JedisPool>` changed to `@Bean JedisPool` + `@ConditionalOnProperty`, so `Optional<JedisPool>` injection points resolve correctly.
- **`virbius-policy` test compilation errors**: `BindScopeTest.java` and `ValueResolverVarDimensionTest.java` passed a stale extra `bindType` argument to `MatchContext.withBind()`; aligned with the current 7-parameter signature.

## [0.1.0] - 2026-07-14

### Added
- **MCP Secure Proxy**: stdio/SSE proxy with security pipeline (License verification, allowlist, engine adjudication, multi-upstream routing).
- **Agent Decision Trace**: full-chain tool_call/tool_result tracing with session timeline and causal chain visualization.
- **High-risk Tool Human Approval**: engine challenge → console approve → token-gated execution flow.
- **Cumulative Counter Engine-side Ingest**: automatic counter ingestion during tool evaluation, driven by configuration.
- **SessionStatePreloader Hash Storage**: HGETALL-based tool count tracking with unified TTL management.
- **Falco Custom Rule Management**: eBPF rules managed via console with canary deployment (PENDING → CANARY → FULL → FINALIZED).
- **Go virbius-audit Falco Plugin**: C-shared library consuming Redis Stream audit events with PID map association.
- **Rust config_subscriber**: Redis Stream → `/etc/falco/falco_rules.d/` → SIGHUP reload.
- **Prompt Injection Detection**: single-model (Qwen3Guard) semantic detection with dynamic risk scoring.
- **STI Taint Tracking**: cross-tool untrusted output propagation detection.
- **Hash Chain Audit Integrity**: SHA-256 hash chain for tamper-proof audit logs (Oracle + Redis Lua CAS).
- **Agent Runtime License System**: Ed25519-signed JWT licenses with revocation support.
- **Audit Dashboard**: session risk, tool calls, alerts, approval queue, decision trace visualization.
- **Edge Manifest Compilation**: per-app manifest partitioning with global/service/tool bind scopes.
- **Falco Plugin K8s Deployment**: DaemonSet + ConfigMap + config-subscriber sidecar.

### Security
- API key authentication with SHA-256 hashed credentials and role-based access control.
- Three-level fallback policy: MinimumPrivilege, DefaultDeny, AuditOnly with fail-open/fail-closed.
- DLP/PII desensitization for phone numbers, ID cards, email, bank cards.

### Documentation
- Architecture design document (DESIGN.md) with cross-layer data flows and risk assessment framework.
- Deployment topology document (DEPLOYMENT.md) covering sidecar/remote/SDK modes.
- MCP proxy protocol specification (PROTOCOL.md).
- Seven-dimensional Agent security risk assessment framework.

[Unreleased]: https://github.com/i1see1you/VirbiusAgent/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/i1see1you/VirbiusAgent/releases/tag/v0.1.0
