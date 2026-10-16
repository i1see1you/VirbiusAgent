# OSS-Agent integration test report

**Target (靶):** an open-source Agent built on the **OpenAI Agents SDK**
(`openai-agents` 0.24, MIT). Same agent, two Virbius integration modes.

**Status:** ✅ both modes verified end-to-end (deterministic paths). Real
LLM-driven ReAct loops are wired and gated on an env-injected model credential
(skipped when absent).

## What was built

| artifact | role |
|---|---|
| `examples/oss-agent-target/target_agent.py` | the Agent + `proxy` / `sdk` mode adapters |
| `scripts/oss-agent-setup.sh` | provisions the Python 3.11 venv (maturin build of `virbius-mcp-python`, `openai-agents`, `virbius-review-sdk`) |
| `scripts/oss-agent-common.sh` | shared isolated control+engine rig + license bootstrap |
| `scripts/oss-agent-e2e-proxy.sh` | MODE: MCP-Proxy |
| `scripts/oss-agent-e2e-sdk.sh` | MODE: SDK embed |
| `examples/oss-agent-target/error_wording_ab.py` | A/B: does the blocked-tool error wording change model behaviour (drives a real `VirbiusGuard` denial; no hand-copied wording) |
| `scripts/test-error-wording-ab.sh` | runs that A/B (informational; SKIPs with exit 77 without a key) |

## Mode 1 — MCP-Proxy (`scripts/oss-agent-e2e-proxy.sh`)

The Agent connects to the real Rust `virbius-mcp-proxy` over MCP SSE
(`MCPServerSse`). The proxy is the enforcement point; the Agent only speaks MCP.

Rig: isolated control + engine → upstream MCP SSE server → **virbius-mcp-proxy**
→ Agent (`MCPServerSse`).

Verified:

| check | result |
|---|---|
| Agent connects via `MCPServerSse` and lists tools | PASS |
| proxy exposes **only** the allowlisted tool (`read_doc`) — `danger_exec` filtered out | PASS |
| non-allowlisted tool (`danger_exec`) is not visible to the Agent | PASS |
| Agent ReAct loop through the proxy (needs LLM key) | SKIP (no key) |

## Mode 2 — SDK embed (`scripts/oss-agent-e2e-sdk.sh`)

The Agent runs its tools **in-process**, wrapped with the Virbius SDK口径:
the framework-agnostic `govern(fn)` / `guard.precheck` (license allowlist + args
schema; denials raise `VirbiusDenied`) plus the OpenAI / LangChain adapters, and
`virbius-review-sdk` (engine `/v1/evaluate` input/output review).

Verified:

| check | result |
|---|---|
| `govern`/`guard.precheck` allows the allowlisted tool (`read_doc`) | PASS |
| `govern`/`guard.precheck` denies the non-allowlisted tool (`danger_exec`) | PASS |
| denial reason names the license allowlist | PASS |
| `review_output` blocks an XSS payload (seeded engine rule) | PASS |
| output `disposal_action` is `reject`/`omit` | PASS |
| `review_input` returns a verdict from `/v1/evaluate` | PASS |
| Agent ReAct loop with guarded tools (needs LLM key) | SKIP (no key) |

## Running the LLM-driven paths

```bash
export OPENAI_API_KEY=sk-...                 # or DEEPSEEK_API_KEY
export OPENAI_BASE_URL=https://api.deepseek.com   # any OpenAI-compatible endpoint
export OPENAI_MODEL=deepseek-chat
bash scripts/oss-agent-e2e-proxy.sh
bash scripts/oss-agent-e2e-sdk.sh
```

The credential is env-injected and never committed.

## Notes / findings

### Post-integration UX improvements (implemented after the first round)

The friction found during this integration was fixed directly:

| fix | where |
|---|---|
| `LicenseError` split into `AppIdMismatch` / `KeyParseFailed` / `SignatureInvalid` with full context in the message | `virbius-core/src/license.rs` |
| public-key PEM auto-trimmed before parsing (trailing blank line no longer fails verification) | `virbius-core/src/license.rs` |
| `--config <path>` (explicit file must parse; search-path parse failures now warn instead of being silent) | proxy CLI |
| `virbius-mcp-proxy doctor` — one-command pre-flight: config summary, key/license validation, connectivity probes | proxy CLI |
| `virbius-mcp-proxy enroll` — one-command credential fetch (env-only admin key, 0600 files, JWT never printed, `--revoke` flow) | proxy CLI |
| `GET /status` — runtime operator surface: wiring, fallback-license verification, last 50 decisions (incl. sampled-out allows) | proxy HTTP |
| `VIRBIUS_BASE_URL` opt-in single-base derivation (control + token exchange + engine `:8082`) | proxy config |
| `virbius-mcp-python` — framework-agnostic `govern(fn)` (the headline: wrap **any** callable, no framework required) plus first-class OpenAI (`openai_tool`/`openai_tools`) and LangChain (`langchain_tool`/`langchain_tools`) adapters, all over one enforcement core; stateful `VirbiusGuard` reads the proxy's env names | `virbius-mcp-python` |
| **stdio mode validated** — new `scripts/test-mcp-proxy-stdio-e2e.sh` drives the real proxy over NDJSON (the Claude-Desktop shape); plus `virbius/status` JSON-RPC method giving stdio the same operator surface as `GET /status` | proxy |
| **error → LLM propagation** — every Virbius error now carries a **human `message`** + machine `data.reason_code`; the Agents SDK default hides it, so `virbius_tool_error` is a **pass-through** formatter (proxy mode) and `VirbiusDenied` is the type-based denial for in-process tools (SDK mode). No fragile string matching. | proxy + `virbius-mcp-python` |
| **refusal wording is single-sourced** — `policy_blocked_message(reason)` is the one place the "blocked by policy" text is built (used by `guard()`; the A/B harness drives that same guard); a test ties it to the real Rust deny reason so wording can't drift | `virbius-mcp-python/guard.py` |
| deny reason capped at 64 chars on the audit wire (control `reason_code` is `VARCHAR(64)`; long reasons would make MySQL strict mode drop the audit row) | `virbius-mcp-proxy/src/audit.rs` |

The target agent (`examples/oss-agent-target/target_agent.py`) now dogfoods the SDK口径 three ways: the framework-agnostic `govern(fn)` (`sdk probe`), the OpenAI adapter (`sdk run`), and the LangChain adapter (`sdk langchain`). The SDK e2e asserts those paths (see the demo README and `scripts/oss-agent-e2e-sdk.sh`).

### A/B: does the blocked-tool error wording change model behaviour?

`scripts/test-error-wording-ab.sh` (+ `examples/oss-agent-target/error_wording_ab.py`)
runs the same agent, tools, and prompt at temperature 0 with the `delete_files`
tool deterministically denied, varying **only** the `failure_error_function` text
the model sees:

- **generic** — the Agents SDK default (`"An error occurred while running the tool. Please try again."`), the strawman Virbius overrides;
- **human** — a real `VirbiusGuard.openai_tool` denial (fixture license), so the wording *and* its reason come from the real Rust precheck.

Both texts are produced by the real implementations at runtime — no hand-copied
wording, so the harness can't drift from production.

Result — `deepseek-flash`, 6 trials/arm:

| condition | avg delete attempts | retried the blocked call |
|---|---|---|
| generic (SDK default) | 1.67 | 4/6 |
| human (Virbius wording) | 1.00 | 0/6 |

The SDK default's `"Please try again."` nudge makes the model retry a **policy
refusal**; the terse Virbius wording does not. This is the empirical basis for
surfacing denials with a human message instead of the SDK default.

Observational, not a gate (behaviour is model-dependent). Reproduce with
`bash scripts/test-error-wording-ab.sh --trials 6` (needs `DEEPSEEK_API_KEY` or
`OPENAI_API_KEY`; SKIPs with exit 77 otherwise). `--self-test` checks the logic
with no API calls.

### Earlier findings

- **Python version fork.** `openai-agents` requires Python ≥3.10, but the
  checked-in `virbius_mcp_python` extension was cpython-39. Resolved by
  rebuilding the binding for 3.11 (`maturin develop`) so one venv hosts the
  Agent SDK and both Virbius SDKs.
- **Engine evaluate auth.** A `/v1/evaluate` call needs an evaluate token; the
  harness boots the engine with `--virbius.evaluate.auth.enabled=false` (as the
  guardrails E2E does) so the SDK review path works without token plumbing.
- **Proxy PEM gotcha (fixed).** The proxy used to read the license public-key
  file raw and hand it to `ed25519-dalek::from_public_key_pem`, which rejects a
  trailing blank line → misleading `InvalidSignature`. **Now resolved at the
  source**: `License::verify` trims the PEM, and the error taxonomy split
  (`KeyParseFailed`) names the real failure. The harness also normalises the PEM
  it writes.
- **Enforcement semantics differ by mode by design:** proxy = strong isolation
  (separate process, can't be bypassed by the Agent); SDK = defense-in-depth
  (in-process soft boundary). High-risk tools should use the proxy.
