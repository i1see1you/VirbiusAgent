# Integration Quickstart

VirbiusAgent supports **three integration modes**. Pick by deployment shape —
then copy the snippet. Deeper detail stays in [PROXY_CONFIG.md](PROXY_CONFIG.md),
[USAGE_GUIDE.md](USAGE_GUIDE.md) §3 and
[virbius-mcp-python/README.md](virbius-mcp-python/README.md).

## Which mode?

| Dimension | **Mode 1: MCP Proxy** (sidecar) | **Mode 2: Higress** (remote gateway) | **Mode 3: SDK Embed** (in-process) |
|---|---|---|---|
| Integration effort | run a process; **no per-tool code** | deploy a WASM plugin on the gateway; **no tool code**, but point the agent at it | one decorator per tool |
| Infra needed | proxy process (+ optional Redis/Kafka for audit) + your upstream MCP | Higress gateway (+ reachable control/engine) | none beyond the binding |
| Where credentials live | **only in the proxy** — the agent never sees the license | in the gateway plugin config (control-issued) | in your app process (env vars) |
| Isolation | **strong** (separate process) | **strong** (in-cluster, on-path) | soft / defense-in-depth (host compromise can bypass) |
| Traffic | east-west (localhost) | north-south (HTTPS ingress) | in-process |
| Latency (fast path) | ~2ms | ~5ms | **<0.5ms** |
| Tool visibility | non-allowlisted tools **filtered out** | tools stay **visible**; a denied call returns a structured MCP error | tools stay **visible**; a denied call raises |
| Best for | most users; high-risk tools; agents you'd rather not modify | remote/SaaS agents behind a cluster ingress | fastest adoption, in-process tools, lowest latency + prompt/PII features |

Rule of thumb: **run a sidecar → Mode 1.** Traffic enters through a **cluster
ingress** → Mode 2. Only touch **code**, want the lowest latency → Mode 3.

All three need a **license**; the proxy's `enroll` fetches it for you, the gateway
gets it from control, and for SDK embed you place the license file + public key
yourself.

## Mode 1 — MCP Proxy (sidecar)

```bash
# 1. one-time: fetch credentials + self-check (admin credential via env only)
VIRBIUS_ADMIN_API_KEY=... virbius-mcp-proxy enroll \
  --control http://control:8080 --tenant acme --app-id crm-agent \
  --tools read_file,search --out-dir /etc/virbius
VIRBIUS_BASE_URL=http://control:8080 virbius-mcp-proxy doctor

# 2. run the proxy (stdio or SSE) between your agent and its MCP tools
```

```python
from agents import Agent
from agents.mcp import MCPServerSse
from virbius_mcp_python import virbius_tool_error  # reveal the proxy's refusal verbatim

server = MCPServerSse({"url": "http://127.0.0.1:9090/sse"},
                      failure_error_function=virbius_tool_error)
agent = Agent(name="app", instructions="...", model=model, mcp_servers=[server])
```

No per-tool code: allowlist, policy and audit happen in the proxy, and the model
only ever sees allowlisted tools. Full config: [PROXY_CONFIG.md](PROXY_CONFIG.md).

## Mode 2 — Higress Gateway (remote)

For remote/SaaS agents whose traffic enters through a cluster ingress. Enforcement
is the **WASM plugin** on the gateway:

```yaml
apiVersion: extensions.higress.io/v1alpha1
kind: WasmPlugin
metadata:
  name: virbius-gateway
  namespace: higress-system
spec:
  defaultConfig:
    control_base_url: "http://virbius-control:8080"
    tenant_id: "default"
    evaluate: true
    rate_limit: true
    license_verify: true
    tool_precheck: true
  url: oci://registry.example.com/virbius-gateway-wasm:v1.0.0
```

Requests route through the gateway, which pre-checks each tool call and reviews
content on-path (HTTP 403 with a structured JSON-RPC error on denial). Unlike the
proxy it does **not** rewrite the tool list, so wire the same
`failure_error_function=virbius_tool_error` on the agent's MCP client to hand the
model the readable reason instead of an opaque error. See
[USAGE_GUIDE.md](USAGE_GUIDE.md) §3.2.

## Mode 3 — SDK Embed (in-process)

```bash
# 1. install the binding (or install the wheel)
pip install maturin && ( cd virbius-mcp-python && maturin develop --release )

# 2. point it at your license material
export VIRBIUS_LICENSE_FILE=/etc/virbius/license.jwt
export VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license_pub.pem
export VIRBIUS_APP_ID=crm-agent

# 3. check env + license + tools before wiring anything
python -m virbius_mcp_python doctor
```

```python
import virbius_mcp_python as v

def read_file(path: str) -> str:
    """Read a document."""
    return open(path).read()

# Framework-agnostic — wrap ANY callable; one pre-check per call:
safe_read_file = v.govern(read_file)        # or: @v.govern on the def

# OpenAI Agents SDK adapter — same enforcement, plus the tool schema:
guard = v.VirbiusGuard.from_env()           # reads the env above
agent = Agent(..., tools=guard.openai_tools([read_file, search]))
```

`doctor` (and `v.diagnose()`) reports the 6 env vars, license validity and the
allowed tools — and never raises on a bad config, so it is safe to run exactly
when something is broken. (Rust / C-ABI / other languages: see
[USAGE_GUIDE.md](USAGE_GUIDE.md) §3.3.)

## The one behavioral difference to remember

**Mode 1 (proxy)** filters `tools/list`: a non-allowlisted tool is **not in the
model's tool list** at all, so it can never be called.

**Mode 2 (gateway)** and **Mode 3 (SDK)** keep tools **visible** — the gateway only
intercepts each call on-path (HTTP 403, JSON-RPC error `-32006` with
`data.tool_name` / `data.reason`), and the SDK raises `VirbiusDenied`. In both, wire
the matching formatter so the refusal reaches the model as a **fact** (so it
reroutes instead of retrying): `virbius_tool_error` for Mode 2's MCP client, and
the adapter's own formatter for Mode 3 (`openai_tool_error` / `langchain_tool_error`
— auto-wired by `openai_tools` / `langchain_tools`). See the error-shape section of
[PROXY_CONFIG.md](PROXY_CONFIG.md).

## See also

- [USAGE_GUIDE.md](USAGE_GUIDE.md) §3 — all three modes, step by step
- [PROXY_CONFIG.md](PROXY_CONFIG.md) — proxy configuration + `enroll` / `doctor`
- [virbius-mcp-python/README.md](virbius-mcp-python/README.md) — SDK-embed API
- [NATIVE_TOOL_INTEGRATION.md](NATIVE_TOOL_INTEGRATION.md) — observing/classifying *native* (non-Virbius) tools
