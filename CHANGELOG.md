# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Route→Tool migration: replaced `route` bind_scope with `tool` scope for Agent security rules.

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
