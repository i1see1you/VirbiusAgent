# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- **Audit & trace integrity (outline A)**: per-session Agent-trace hash chaining (`tb_agent_trace.chain_seq/prev_hash/curr_hash` + `tb_trace_chain_state`, migration `V14`) with session- and tenant-level verification (`GET /api/v1/admin/tenants/{tenantId}/audit/trace-chain/verify`); Merkle inclusion proofs over `tb_audit_events` (`GET .../audit/proof/{eventId}`, `POST .../audit/verify-proof`); a daily per-tenant Merkle anchor sealing the day's session chain heads (`tb_audit_daily_anchor`, `DailyAnchorJob`, `POST .../audit/daily-anchor/seal`); retention archival in copy-only `archive` mode (`tb_tenant_retention_policy`, `*_archive` mirrors, `AuditRetentionJob`); **Decision BOM** reconstruction from a single `trace_id` in four phases — audit / session / policy / trace (`GET .../audit/decision-bom/{traceId}`, `DecisionBomService`); and a permission-controlled encrypted export with its own ledger (`GET|POST .../audit/export`, `GET .../audit/export/{jobId}`, `GET .../audit/export/{jobId}/file`; AES-256-GCM, **fail-closed** without `virbius.audit.export.key`). Also persists `tool_result_preview` on trace rows (migration `V13`). New console page **审计完整性 / Audit Integrity** (6 tabs).
- **Output safety (outline B)**: the OpenAI-compatible chat proxy (`/v1/chat/completions`) now reviews the assistant answer through the existing evaluate pipeline (`role=output`) when the request carries a `virbius` extension object — whole response for non-streaming, sliding-window (`StreamReviewWindow`) for streaming. Disposal action resolver (`reject|omit|redact|warn`; override via `virbius.output-review.action-map`, safe `reject` default), in-place PII/credential masking (`OutputMasker`), NeMo-style regex content rules (code / SQL / template injection, XSS, credential leak) needing no extra dependency, per-model skip list (`virbius.output-review.skip-models`) and configurable refusal text, plus output-review false-positive statistics (`GET .../audit/output-review-stats`). The engine also emits `input`/`output` trace steps (`TraceStepPublisher`) so the chat-side trajectory is visible alongside proxy-observed tool steps.
- **Dynamic session risk (outline C)**: temporal behaviour-chain detection (read-sensitive→external egress, write→execute, monotonic privilege escalation READ→WRITE→EXEC→NETWORK) with argument-level conditions to avoid the "any read then any network" false positive, and retry-escape detection (at least the 3rd attempt after a block within the window, at least one attempt changed tool/args, with a ≥3-consecutive-allowed wash-out guard). Both are `dry_run` by default — enforced only with `virbius.chain-patterns.enforce=true` / `virbius.retry-escape.enforce=true`. Script rules gain side-effect APIs `ctx.setIntent(action)` and `ctx.incrementRiskScore(delta)` (sync rules only).
- **Alerts & session checkpoints (C4/C5)**: `tb_alert` populated by Control from ingested high-risk audit events (`risk_score >= virbius.alert.risk-threshold`, enforcing action), with an ack flow and single-alert→Decision-BOM expansion (`GET|POST .../alerts`, `GET .../alerts/{id}`, `POST .../alerts/{id}/ack`, `GET .../alerts/{id}/bom`); `tb_session_checkpoint` keeps per-session context across a Redis eviction. New console page **告警 / Alerts**.
- **Audit insert-only grants (A0)**: `deploy/scripts/audit-insert-only-grants.sql` — optional privilege-layer append-only hardening for evidence tables (tamper *prevention* on top of the hash chain's tamper *detection*), applied manually in production.
- **`virbius-integrate` startup handshake & first-flush summary log**: on wiring, the observer verifies control reachability + token + tenant/app route (optionally `handshake_deep` ingest round-trip) and logs a one-line result — `strict=True` runs it synchronously and raises, the default runs it in the background and warns without blocking; the emitter now logs a first-flush line (`flushed`/`accepted`/`dropped`/`sampled_out`) and a periodic summary that escalates to WARNING only on data loss. Library logging uses `logging.getLogger("virbius_integrate")` with no `NullHandler`, so WARNING+ (e.g. a failed handshake) is visible via `logging.lastResort` even when the app has not configured logging, while INFO stays quiet.
- **Native tool integration (Mode A observe / Mode B enforce)**: `virbius-integrate` LangChain `BaseCallbackHandler` plugin emitting two-point (`started`/`completed`/`error`) tool-call observations over an independent channel to Control (`tb_tool_observation` / `tb_tool_inventory` / `tb_obs_app_liveness`, migration `V12`, best-effort, advisory-grade — not hash-chained, not a session-risk input), with a rule-based tool classifier emitting `risk_hint` + `recommended_mode` and console coverage warnings for tools recommended `B` but bound `A`; integrated the `virbius-mcp-python` PyO3 binding of `virbius-core` `precheck` (License allowlist + args schema; offline / control / cloud rule load) as the Mode B path. New guide: `NATIVE_TOOL_INTEGRATION.md` / `NATIVE_TOOL_INTEGRATION.zh.md`.
- **Delegated credential plane (v2.5)**: proxy-side injection of tenant-scoped third-party API credentials into outbound egress for exact-host matches. Plaintext never leaves the proxy process; tenant authorization only (no per-subject). New proxy module `delegated.rs` (vault cold/loaded, AES-256-GCM envelope with all-field AAD, uniform opaque deny `-32014`, redaction funnel for 2xx plaintext responses (raw + percent/base64/base64url literal variants), local-exec ban `-32015` while enabled, loopback bind startup check, Redis snapshot polling with TTL clear); Control adds `V11__delegated_cred_plane.sql` (single table), `DelegatedCredEnvelope` (HKDF tenant KEK), CRUD + snapshot publish (retry + 60s self-heal republish) + one-time KEK reveal with request-scoped redaction. Config-data flow (B1): not part of the rule rollout pipeline. Java↔Rust interop test vector pinned byte-identical; 11 end-to-end hit-branch tests. Snapshot polling uses exponential backoff (30s→10min, ±10% jitter, reset on success) with the TTL-clear check on every loop; KEK reveal responds `Cache-Control: no-store`. See PROTOCOL.zh.md §2.6.1 and DEPLOYMENT.zh.md §9.6.

### Fixed
- **Alert status naming inconsistency**: `AlertService` wrote `status='ack'` while the console filtered and rendered `acked`, so the `?status=acked` filter was always empty, the ack button never greyed out, and a repeat ack still returned `acknowledged=false`. Unified on `acked`.
- **Dev schema drift**: `tb_agent_trace` was missing `chain_seq` / `tool_result_preview` / `prev_hash` / `curr_hash` on databases created before migrations `V13`/`V14` (dev disables Flyway), which made `/audit/trace-chain/verify`, `/audit/decision-bom/{id}` and `/alerts/{id}/bom` return 500. Resolved by rebuilding the dev DB (`VIRBIUS_REBUILD_DB=1`); `db/schema.sql` now carries these columns for fresh dev databases.
- **`virbius-integrate` `strict=True` crashed with `AttributeError`**: `VirbiusAuditCallback.__init__` called a `_startup_check` method that was never implemented, so strict startup mode failed immediately. It is now a real synchronous startup handshake (reachability + token + route; `handshake_deep` adds an ingest round-trip) that raises `VirbiusIntegrateError` with a reason.
- **Delegated credential plane — delete-to-zero snapshot cleanup**: deleting the last credential while Redis was unavailable left a stale snapshot, so the deleted credential stayed injected. Control's 60s self-heal now reconciles the union of tenants with DB rows and tenants still holding a Redis snapshot key, republishing each tenant's current active set — so the deleted-to-zero case gets an authoritative empty doc that clears the proxy vault (no extra bookkeeping table).
- **Delegated value validation ↔ proxy alignment**: `value` now rejects all control characters (0x00–0x1F, 0x7F), matching the proxy's `HeaderValue` acceptance, so a value the Control accepts can no longer fail at injection time; obs-text (≥0x80) is still allowed.

### Changed
- **Delegated redaction covers encoding variants**: body/location redaction now also replaces percent/base64/base64url literal forms of the credential (literal replacement only — the body is never decoded/rewritten); a response whose raw body contains the credential raises a `hit_credential_echo` alert.
- **Proxy test seam**: added a `test-harness` cargo feature (compile-time only) so the full-chain E2E harness can drive the hit branch against a local HTTP server; a production binary cannot disable the https+443 gate via any runtime variable.

### Removed
- **`virbius-engine` gRPC decision path (port 50051)**: the gRPC `EvaluateService` had no in-repo consumer — the MCP proxy, the compiled gateway `engine_url`, and the Control facades all use the HTTP/JSON `POST /v1/evaluate` path. Removed `EvaluateGrpcService` / `GrpcServerLifecycle` / `ProtoMapper`, `src/main/proto/evaluate.proto`, the `io.grpc`/`protobuf-java`/`javax.annotation-api` dependencies and the `protobuf-maven-plugin`/`os-maven-plugin` build config, the `virbius.grpc.port` setting, and the Helm gRPC service port / `engine.grpc.port` value. `/v1/evaluate` is now documented and commented as the single authoritative internal decision path.

## [0.2.0] - 2026-09-15

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
