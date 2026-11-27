# Integration Guide — Single-Tenant M1 (Config-Only)

This release delivers **single-tenant M1**: point a standard MCP client at the
proxy over SSE, grant the canonical local-exec tools in the license, and the
agent can call them — **no agent code changes**.

> Scope note: this release is **single-tenant** and certifies
> **single-upstream**. Do not expose one proxy to multiple tenants. Multi-tenant
> auth, revocation streaming, the `local_exec` license claim, per-tenant fair
> queues and `tools/list_changed` are explicitly out of scope.

---

## 1. Coverage matrix

| Mode | What it is | Covered by this release |
|---|---|---|
| **M0** whole-container | Run the whole agent (OpenManus/OpenHands/AutoGPT/OpenInterpreter) under `runsc`; built-in executors are contained by the container | ❌ not included (deployment package, orthogonal) |
| **M1** config-only | Agent's MCP client connects to the proxy; local tools are `virbius__*` | ✅ **this release** (single tenant) |
| **M2** toolkit | Python package replacing framework tool lists | ❌ not included |

**Not covered, by design:** framework built-in executors
(OpenHands Runtime, OpenInterpreter `computer`, LangChain `ShellTool`/
`PythonREPLTool`, AutoGPT built-in blocks, OpenManus `Bash`/`PythonExecute`).
They never traverse the proxy; use M0 to contain them.

---

## 2. Prerequisites

1. **Linux host with gVisor** (`runsc`) installed; local execution is
   gVisor-only and fail-closed. Non-Linux hosts refuse local execution.
   **If the proxy runs in a container, that container MUST be `privileged`
   when local execution is enabled.** runsc creates the sandbox's
   user/pid/mount/net namespaces and its minimal chroot root (CAP_SYS_ADMIN),
   sets up the gofer, and — for cgroup limits — writes the cgroup tree; an
   unprivileged container fails closed (`sandbox_unavailable`) instead of
   degrading to unsandboxed execution.
2. Proxy node config enables it:
   ```toml
   [local_exec]
   mode = "gvisor"          # "disabled" (default) refuses all local execution
   code_bytes = 65536
   concurrency_global = 8
   ```
3. A license that **explicitly lists** the canonical tool names, e.g.
   ```json
   { "allowed_tools": ["virbius__shell", "virbius__python", "read_file"] }
   ```
   A wildcard license (`allowed_tools: []`) grants upstream tools only — it
   **never** grants local execution.
4. Admin surface (operator-only) on a **loopback-only** listener:
   ```toml
   [admin]
   listen = "127.0.0.1:19400"          # loopback binds only; non-loopback refuses to start
   token_file = "/etc/virbius/admin.token"   # optional; an empty file refuses to start
   ```
   This release enforces loopback-only (fail-closed): `0.0.0.0` / LAN /
   public binds exit at startup. Remote operators use `docker exec
   <container> curl http://127.0.0.1:19400/status`, `kubectl port-forward`,
   or an SSH tunnel — none of which expose the surface to the network.
5. **Rootfs contents define what sandboxed code can do.** The sandbox rootfs
   (default `/opt/virbius/rootfs`) is the entire runtime available to code:
   this release has **no network** inside the sandbox and **no persistent
   workspace**, so execution-time package installs (`pip`, `npm`, …) are not
   possible. Bake interpreters and required libraries into the rootfs image
   (or a read-only layer mounted into it) and record the inventory in your
   deployment notes. Supported execution profile: **preinstalled runtime +
   stdlib only**.
6. **Verify on a real Linux+runsc host before production**: the read-only
   rootfs, per-execution tmpfs (`/workspace`, `/tmp`), pids limit and
   `RLIMIT_NOFILE/NPROC` OCI fields must be accepted by the installed runsc
   version. A missing/incompatible runsc fails closed
   (`sandbox_unavailable`) — it does not degrade to unsandboxed execution.

Optional tool policy (per canonical name) for timeout/schema/fast-path:
```json
{ "tool_name": "virbius__shell", "sandbox_type": "gvisor", "timeout_ms": 30000 }
```
`none` / `landlock` on a **local** tool is refused (`sandbox_unavailable`), not
downgraded. Omitting the policy is fine (node mode decides).

**Resource-limit honesty**: by default `runsc` starts with `--ignore-cgroups`
(rootless-compatible), so the OCI memory/cpu/pids limits from the gVisor rule
body are **not enforced** — every result `_meta` and the `virbius/status`
snapshot report `gvisor_limits_enforced=false` instead of pretending. Opt into
real cgroup enforcement with `VIRBIUS_GVISOR_ENFORCE_CGROUPS=1` (or
`"enforce_cgroups": true` in the gVisor rule body); a host that cannot apply
cgroups then fails closed at pool availability. Process rlimits
(RLIMIT_NOFILE/RLIMIT_NPROC) and the read-only-rootfs + per-execution tmpfs
scratch apply either way. In a container, real cgroup enforcement additionally
needs the proxy to be `privileged` (a non-privileged container mounts
`/sys/fs/cgroup` read-only) **and** to share the host cgroup namespace
(`--cgroupns=host` / Compose `cgroup: host`); without both, `enforce_cgroups`
fails closed.

---

## 3. Issue the license

The control plane accepts arbitrary tool names (no registry validation), so the
canonical names can be granted today:

```bash
curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/issue" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "app_id": "my-agent",
    "agent_name": "My Agent",
    "allowed_tools": ["virbius__shell", "read_file"],
    "risk_quota": 100,
    "expirySeconds": 604800
  }'
```

Deliver the JWT to the deployment as `license_file` (single-tenant) — the proxy
uses it as the fallback license at `initialize`.

> License default expiry remains 1 year when omitted. **Recommend a short
> expiry** for this release: there is no revocation streaming yet.

---

## 4. Register the MCP client (SSE)

The proxy's client transport is **SSE** (`GET /sse` + `POST /messages/?session_id=`),
not Streamable HTTP. There is no `POST /` endpoint by default.

> **Native-MCP spike (2026-11-27, verified)**: the framework integrations below
> were verified end-to-end against a live proxy with `VIRBIUS_LICENSE_FILE`
> fallback — tool discovery, JSON schema, error mapping (`McpError` preserves
> `-32016/-32018` reasons), concurrent calls, and disconnect semantics all work
> with **zero adapter code**. Pinned stacks:
> `langchain-mcp-adapters 0.3.2` + `langchain-core 1.8.5` + `mcp 1.30.0`;
> `openai-agents 0.26.0` (Python 3.11). Re-verify when bumping these.

### Generic `mcp.json`
```json
{
  "mcpServers": {
    "virbius": {
      "type": "sse",
      "url": "http://127.0.0.1:9090/sse"
    }
  }
}
```

### LangGraph / `langchain-mcp-adapters`
```python
from langchain_mcp_adapters.client import MultiServerMCPClient

client = MultiServerMCPClient({
    "virbius": {"transport": "sse", "url": "http://127.0.0.1:9090/sse"}
})
tools = await client.get_tools()   # includes virbius__shell when granted
```

### OpenAI Agents SDK
```python
from agents.mcp import MCPServerSse

async with MCPServerSse(params={"url": "http://127.0.0.1:9090/sse"}) as mcp:
    agent = Agent(name="demo", mcp_servers=[mcp])
```
> Verify the exact `MCPServerSse` signature against your installed SDK version.
> One `MCPServerSse` context = one persistent MCP session: audit events for the
> whole agent run share a `session_id`. If the stream dies mid-run the session
> ends with `ClosedResourceError` on the next call — recreate the context; the
> SDK never auto-reconnects or re-sends a `tools/call`.

### Integration checklist (required)

1. **Remove/disable the framework's original executors** (LangChain `ShellTool`/
   `PythonREPLTool`, custom `subprocess` tools, OpenInterpreter `computer`):
   the proxy only protects calls that reach it as `virbius__*`. A leftover
   original tool is an unsandboxed bypass.
2. **Do not add client-side retries for `tools/call`.** Disconnect semantics
   are fail-fast by design: an in-flight call whose response is lost surfaces
   as `McpError("Connection closed")` and is never re-sent (verified for both
   frameworks). An agent that retries would double-execute code.
3. **License hygiene**: the proxy holds the license via `VIRBIUS_LICENSE_FILE`;
   keep the JWT short-lived (no revocation streaming yet) and never bake it
   into the agent image.
4. **Session semantics**: `langchain-mcp-adapters` opens a NEW session per tool
   invocation (audit events do not share a `session_id` across calls);
   `openai-agents` keeps one persistent session per context. Plan audit
   correlation accordingly.
5. **Connection errors on a down proxy** surface as an `anyio` `ExceptionGroup`
   (wrapped) — treat any exception group member mentioning connection failure
   as "proxy unreachable", not as a tool denial.

---

## 5. Tool contract

| Canonical tool | Argument | Result |
|---|---|---|
| `virbius__shell` | `command` | stdout/stderr |
| `virbius__python` | `code` | stdout/stderr |
| `virbius__node` | `code` | stdout/stderr |

- Tool names are **only** advertised when the license grants them. Ungranted
  canonical tools are not listed (list and call share one authorization rule).
- Policy/engine/tool-policy/audit all use the **canonical** name. There is no
  legacy-name alias: bare names such as `shell` or `execute_python` are ordinary
  upstream tool names and are never executed locally.

Local result `_meta`:
```json
{
  "sandbox_used": "gvisor",
  "degraded": false,
  "truncated": false,
  "limit_reason": null,
  "exit_code": 0,
  "limits": { "codeBytes": 65536, "wallMs": 30000 },
  "gvisor_limits_enforced": false,
  "gvisor_scratch_bytes": 67108864,
  "gvisor_pids_limit": 128,
  "gvisor_nofile_limit": 512
}
```
`gvisor_limits_enforced=false` is the default (rootless-compatible) state: the
rootfs is read-only with per-execution tmpfs scratch at `/workspace` and `/tmp`,
rlimits and the output cap always apply, but cgroup-backed memory/cpu/pids are
not. The rootfs is **read-only** — files written inside a sandboxed execution
live in `/workspace` (scratch) and are discarded when the container exits.

Stable error reasons: `local_exec_not_granted`, `local_exec_node_disabled`,
`sandbox_unavailable`, `code_too_large`, `local_exec_busy`,
`high_risk_without_license`, `not_in_allowlist`, `session_identity_mismatch`.

---

## 6. Migration from the old (pre-v4) proxy

| Old | New |
|---|---|
| Local tools named `shell`/`execute_python`/... | `virbius__shell` / `virbius__python` / `virbius__node` |
| License `allowed_tools: ["shell"]` granted local exec | Must list `virbius__shell` explicitly |
| `VIRBIUS_ALLOW_UNSANDBOXED` / `sandbox_type=none` | Removed; `none` for a local tool is refused |
| `GET /status` on the client port | Admin listener `admin.listen` (default `127.0.0.1:19400`) |
| `POST /` stateless JSON-RPC | Removed by default (`http.simple_post_enabled=false`) |
| `virbius/status` full license summary | Trimmed: version / local_exec mode / session count only |

---

## 7. Known limitations (this release)

- Single tenant; no shared-proxy multi-tenancy, no `Authorization` header auth.
- No revocation streaming; license valid until `exp`.
- Sandboxed code cannot install packages or reach the network, and `/workspace`
  scratch is discarded after each execution; only the rootfs's preinstalled
  runtime and stdlib are available (see §2 item 5).
- No `tools/list_changed` refresh (multi-upstream inventories can go stale).
- No per-tenant fair queue; only a global in-flight cap.
- No M0 deployment package and no M2 toolkit.
- No Landlock execution; no unsandboxed fallback.
- Register tool names with the control plane for per-tool timeout/schema only if
  needed — canonical names are not pre-seeded.

---

## 8. Verify (doctor / acceptance)

```bash
# capabilities + tools/list (no execution)
python3 scripts/m1-doctor.py --url http://127.0.0.1:9090 \
  --license-jwt-file /path/to/license.jwt \
  --expect-canonical virbius__shell

# full acceptance: execute once and require sandbox_used=gvisor (Linux+runsc)
python3 scripts/m1-doctor.py --url http://127.0.0.1:9090 \
  --license-jwt-file /path/to/license.jwt \
  --call-shell
```

The doctor is an out-of-band operator tool; it is **not** a model-callable tool.
`--call-shell` requires a license that grants `virbius__shell`.

Automated release gate (real runsc, no control/engine needed): run
`./scripts/test-gvisor-gate.sh` (add `ENFORCE=1` for the cgroup OOM variant).
It starts the proxy `privileged` with `--cgroupns=host`, mounts real
`runsc` + a prepared rootfs + the license fixture under `scripts/gvisor-gate/`,
and asserts `sandbox_used=gvisor`, read-only rootfs, per-exec tmpfs, rlimits,
and honest `gvisor_limits_enforced`. CI runs it via
`.github/workflows/gvisor-gate.yml` (manual dispatch, `v*` tags, or the
`gvisor-gate` PR label).
