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

Optional tool policy (per canonical name) for timeout/schema/fast-path:
```json
{ "tool_name": "virbius__shell", "sandbox_type": "gvisor", "timeout_ms": 30000 }
```
`none` / `landlock` on a **local** tool is refused (`sandbox_unavailable`), not
downgraded. Omitting the policy is fine (node mode decides).

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
  "limits": { "codeBytes": 65536, "wallMs": 30000 }
}
```

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
