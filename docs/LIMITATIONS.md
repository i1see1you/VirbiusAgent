# Limitations & security boundaries

Transparency is a feature. This page consolidates what VirbiusAgent does **not**
do, so you can make architecture decisions before integrating. It complements
the per-module boundaries in
[virbius-integrate/LIMITATIONS.md](../virbius-integrate/LIMITATIONS.md) and the
benchmark notes in the [README](../README.md#benchmarks).

## Enforcement boundaries

- **Edge is a soft boundary.** `virbius-core` / Mode B SDK precheck runs
  in-process; a compromised process, or a direct API call that skips the SDK /
  proxy, bypasses it. It must not be the sole defense line.
  (README "Edge: lightweight pre-execution protection".)
- **Mode A is observe-only (advisory).** `virbius-integrate` never blocks. Its
  events are claim-level, do not enter the hash chain, and are never an input to
  session risk or blocking decisions. See
  [virbius-integrate/LIMITATIONS.md](../virbius-integrate/LIMITATIONS.md).
- **Mode B is in-process enforcement.** `virbius-mcp-python` / `virbius-mcp-node`
  `govern()` prechecks before execution but shares the agent's process boundary.
  For high-risk tools use the **MCP proxy** (separate process, strong isolation).
- **Kernel is passive by default.** Falco/eBPF observes syscalls and does not
  block the request path; only Landlock/gVisor sandbox rules block, at syscall
  level. Kernel observation cannot be bypassed by the agent process, but it
  requires Linux with eBPF support.
- **Gateway is on-path but optional.** If you deploy without the Higress gateway,
  you lose the mandatory HTTP checkpoint and must rely on edge + cloud.

## Platform prerequisites

- **MCP proxy requires a license** and a reachable upstream MCP server.
- **Kernel layer requires Linux / eBPF**; Landlock/gVisor availability depends on
  the host kernel and runtime.
- **SSO / OAuth2-OIDC is design-finalized, not implemented**
  ([SSO_INTEGRATION.md](../SSO_INTEGRATION.md)). Operator login today is local
  password + JWT.
- **Production database is MySQL**; SQLite is for development only.

## Model & benchmark caveats

- VirbiusGuard numbers use a **locked auditor prompt, greedy decoding, and parse
  failures counted as misses**. They are not a guarantee against novel attacks.
- The AgentDojo result (ASR 1.58% → 0%) is for a **single agent
  (`deepseek-flash`, already injection-robust), one fixed attack set, 949 pairs**.
  It does not generalize to all agents or attack sets.
- **Quantized Q4 builds did not pass the same gates**; use f16/bf16 for the
  reported numbers.
- `gold_1000` is an **internal** bilingual set; WildGuardTest / XSTest are public
  and reproducible via [`scripts/benchmarks/`](../scripts/benchmarks/).
- LLM detection is probabilistic. Rules catch known patterns; models generalize
  but can miss or over-refuse. Neither alone is sufficient.

## Scope boundaries

- VirbiusAgent protects **your self-developed / self-hosted agents** (server-side
  runtime security). It is **not** endpoint DLP for employee AI usage.
- **Third-party desktop/IDE agents (Cursor, Claude Code, Continue) are not
  intercepted.** For LLM entry/exit review only, route the client's
  OpenAI-compatible base URL through the companion project
  [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM); tool execution, approval,
  sandbox and syscall observation remain out of scope there.
- **OWASP ASI07–ASI09 are explicitly out of scope** (inter-agent communication,
  cascading failures, human-agent trust). See the honest coverage table in the
  [README](../README.md#owasp-agentic-top-10-coverage) and
  [sales/Customer_Scenarios](sales/VirbiusAgent_Customer_Scenarios.md).
- **VirbiusEvaluation is a separately licensed, closed-source module.** This
  open-source repository neither contains nor depends on it.
- Supply-chain controls **limit what a poisoned tool can do**; they do not
  prevent poisoning, and there is no AIBOM / pre-install integrity enforcement.

## Audit semantics

- The **hash chain** covers the platform audit pipeline. Mode A observation
  events are deliberately excluded from it.
- Mode A delivery is **best-effort** (bounded queue, graded dropping, visible
  drop counters); it is not a durable guarantee. See
  [virbius-integrate/LIMITATIONS.md](../virbius-integrate/LIMITATIONS.md).

## MCP proxy upstream transports

- **HTTP-family upstreams are not per-session isolated.** One upstream MCP
  session is shared by all downstream clients of a tenant for the same
  upstream entry; server-side state (and anything the server caches per
  session) is therefore visible across those clients. Only stdio upstreams get
  one child process per downstream session.
- **stdio upstreams have no auto-restart.** A crashed child fails in-flight
  requests and is respawned on the next initialize. Supervision/backoff is not
  implemented.
- **stdio upstreams are not sandboxed by the proxy.** The proxy constrains how
  the child is spawned (no shell, filtered environment, `kill_on_drop`, own
  process group) but the child runs with the proxy's OS user. Use
  container/sandbox isolation for untrusted servers.
- **stdio on Windows is untested.** Process-group teardown is Unix-only.
- **OAuth 2.1 upstream authorization is not implemented.** Remote Streamable
  HTTP servers that require interactive authorization cannot be used yet;
  static `headers` (TOML only) is the current mechanism.
- **Upstream URLs are validated against link-local/metadata addresses before
  connecting, but DNS can change between validation and connection.** Address
  pinning is applied where practical (reqwest `resolve`), and revalidation
  happens on reconnect, but this is not a complete DNS-rebinding defense.
- **Upstream-initiated notifications are observed, not routed to downstream
  clients.** A server notification (e.g. `tools/list_changed`) is logged, not
  forwarded. Routing upstream notifications to a downstream client is a
  follow-up; the current sink logs at debug level.
- **Transport protocol version:** upstream clients implement the MCP
  2025-06-18 session model; 2026-07-28 stateless semantics are passed through
  as opaque `_meta` fields but not interpreted.

## Recommended layered defense

No single layer is sufficient. Combine edge precheck + on-path gateway + cloud
adjudication, add kernel observation/sandbox for high-risk execution, and keep
humans in the loop for irreversible actions. See the README
[rule-selection guide](../README.md#edgegatewaykernelcloud-rule-selection) and
[ARCHITECTURE.md](../ARCHITECTURE.md).