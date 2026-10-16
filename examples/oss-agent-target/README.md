# oss-agent-target — an open-source Agent as the integration 靶

A small but real Agent built on the **OpenAI Agents SDK** (`openai-agents`, MIT),
used to exercise VirbiusAgent's integration modes against the same target.

VirbiusAgent ships **three integration modes**; this rig exercises the **first**
(proxy) and the **third** (SDK embed) end-to-end. The **second** (Higress gateway)
is enforced by a WASM plugin on the ingress and has its own e2e in
`scripts/test-rule-session-cnt-higress.sh` (it does not run an agent here).

| mode | enforcement point | how |
|---|---|---|
| `proxy` (rigged here) | the Rust **virbius-mcp-proxy** (separate process) | the Agent's tools are served over MCP SSE (`MCPServerSse`); allowlist filtering, policy and audit happen in the proxy |
| `gateway` (elsewhere) | **Higress + WASM plugin** (remote ingress) | requests route through the gateway, which pre-checks each tool call on-path — see `scripts/test-rule-session-cnt-higress.sh` |
| `sdk` (rigged here) | **in-process** (soft boundary) | the Agent's own tools are wrapped with `virbius_mcp_python` — the framework-agnostic `govern(fn)` and the OpenAI / LangChain adapters — plus content review with `virbius-review-sdk` (engine `/v1/evaluate`) |

## Files

- `target_agent.py` — the Agent and its mode adapters (CLI below)
- `error_wording_ab.py` — A/B: does the blocked-tool error wording change model behaviour (drives a real `VirbiusGuard` denial; no hand-copied wording)
- `requirements.txt` — Python deps (the binding is built via maturin, see setup)
- `.venv/` — local virtualenv (gitignored)

## Setup

```bash
# creates examples/oss-agent-target/.venv (Python 3.11) and installs everything,
# including a rebuild of the virbius-mcp-python extension for 3.11.
bash scripts/oss-agent-setup.sh
# slow network? use a mirror:
#   PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple bash scripts/oss-agent-setup.sh
```

## Run

```bash
# deterministic (no LLM needed)
.venv/bin/python target_agent.py proxy list        # tools the proxy exposes (allowlist-filtered)
.venv/bin/python target_agent.py sdk   probe       # precheck + govern allow/deny + input/output review
.venv/bin/python target_agent.py sdk   langchain   # LangChain adapter allow/deny

# real ReAct loop (needs an OpenAI-compatible model)
OPENAI_API_KEY=sk-... OPENAI_BASE_URL=https://api.deepseek.com .venv/bin/python \
  target_agent.py proxy run "Read /tmp/report.txt, then run 'rm -rf /'"
```

End-to-end wrappers (boot the whole rig, run the above, assert):

```bash
bash scripts/oss-agent-e2e-proxy.sh     # MODE: MCP-Proxy
bash scripts/oss-agent-e2e-sdk.sh       # MODE: SDK embed
bash scripts/test-error-wording-ab.sh --trials 6   # A/B: SDK default wording vs Virbius wording
```

The wording A/B is informational (never gates): it reports how often the model
retries a denied tool under each message, and SKIPs (exit 77) without a key;
`--self-test` checks its logic with no API calls.

The LLM step is skipped when no `OPENAI_API_KEY` / `DEEPSEEK_API_KEY` is set; the
rest of the wiring is still asserted. The LLM credential is env-injected and
never committed.

## Environment variables

| var | mode | purpose |
|---|---|---|
| `PROXY_SSE_URL` | proxy | e.g. `http://127.0.0.1:19090/sse` |
| `VIRBIUS_ENGINE_URL` | sdk | engine base URL (review-sdk) |
| `VIRBIUS_LICENSE_FILE` (or `VIRBIUS_LICENSE_JWT`), `VIRBIUS_LICENSE_PUBLIC_KEY`, `VIRBIUS_APP_ID`, `VIRBIUS_TENANT_ID` | sdk | license material — the same names the MCP proxy reads, so one rigged environment serves both modes (`VirbiusGuard.from_env`) |
| `OPENAI_API_KEY` / `DEEPSEEK_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL` | run | driving model |
