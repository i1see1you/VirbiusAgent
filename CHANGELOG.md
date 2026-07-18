# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Route→Tool migration: replaced `route` bind_scope with `tool` scope for Agent security rules.

### Architecture — Falco 退回纯系统级 + http_output 路径（方案 A）

**背景**：移除自定义 `virbius-audit` Falco 插件（Go C-shared library），Falco 退回纯系统级 syscall 观测角色。跨层关联（syscall 事件 ↔ Agent session 上下文）由 Engine 的 `FalcoAlertController` 通过 Redis pidmap 反查完成，不再依赖插件在 Falco 引擎内注入上下文字段。

**移除的组件**：
- `virbius-kernel/plugin/` 目录（Go virbius-audit 插件源码）
- `virbius-kernel/src/falco_plugin.rs` 模块
- `detect.rs` 中 `KernelMode::FalcoPlugin` 枚举变体（降级路径改为 `Disabled`）
- `lib.rs` 中 `falco_plugin` 模块声明和导出

### P0 — 打通 syscall 路径（已完成）

- **`virbius-engine/.../FalcoAlertController.java`**：新增 `POST /api/internal/falco-alert` 端点，接收 Falco `http_output` 原生 JSON，解析 `output_fields["proc.pid"]`，通过 Redis `pid_trace:{host_pid}` 反查 session_id，调用 `SessionRiskManager.onFalcoAlert(sessionId)` 递增 `falco_pending` 计数器。包含 ppid fallback 逻辑。
- **`virbius-kernel/deploy/falco-config.yaml`**：`program_output` → `http_output`，URL 指向 Engine 内部端点，支持 `connection_keepalive` + `retry_wait_seconds`。
- **`virbius-kernel/deploy/falco-daemonset.yaml`**：新增 `config-subscriber` sidecar 容器，消费 Redis Stream 热重载 Falco 规则文件。
- **`virbius-kernel/deploy/kustomization.yaml`**：移除 plugin 专用资源引用，新增 `engine-service.yaml`。
- **`virbius-kernel/deploy/engine-service.yaml`**：新增，定义 Engine K8s Service。
- **`virbius-engine/.../config/PolicyRedisConfig.java`**：修复 Spring 依赖注入 bug — `@Bean Optional<JedisPool>` 改为 `@Bean JedisPool` + `@ConditionalOnProperty`，确保 `Optional<JedisPool>` 注入点能正确解析。
- **`scripts/test-falco-cross-layer.sh`**：新增 macOS 模拟测试脚本，用 curl 模拟 Falco http_output JSON 验证 Engine 关联逻辑。

### P1 — cgroup 关联路径（已完成）

- **`virbius-kernel/src/pidmap.rs`**：`redis_backup_async()` 增加 `cgroup_trace:{cgroup_id}` Redis 反向索引写入。Agent 注册时同时写 `pid_trace:{host_pid}`（主索引）和 `cgroup_trace:{cgroup_id}`（反向索引），两个 key 指向同一 JSON value，共享 TTL 3600s。cgroup_id=0 时跳过（macOS / cgroup v1 优雅降级）。
- **`virbius-engine/.../FalcoAlertController.java`**：增加 `lookupSessionByCgroup()` 方法，实现三级关联链 `host_pid → cgroup_id → ppid`。新增 `resolved_by` 返回字段标识命中路径（`pid` / `cgroup` / `ppid`）。cgroup 优先于 ppid：cgroup 是容器级身份（跨 fork 层稳定），ppid 是进程级身份（深度>1 或 setsid 后断链）。
- **`scripts/test-falco-cross-layer.sh`**：新增场景4（孙子进程，ppid 链断，cgroup 命中）和场景5（setsid detach，ppid=1，cgroup 命中），共 5 个场景覆盖三级关联链全部路径。

### Bug 修复

- **`virbius-policy` 测试编译错误**：`BindScopeTest.java` 和 `ValueResolverVarDimensionTest.java` 中 `MatchContext.withBind()` 调用传了多余的 `bindType` 参数（旧签名残留），对齐当前 7 参数签名。

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
- **Prompt Injection Detection**: multi-LLM detection with dynamic risk scoring.
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
