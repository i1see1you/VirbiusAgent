#!/usr/bin/env bash
# =============================================================================
# OSS-Agent integration E2E — MODE: SDK (in-process embed).
#
# Drives a real OpenAI Agents SDK agent whose tools run *in-process*, wrapped by
# the Virbius SDK口径:
#   * virbius-mcp-python  -> `precheck_tool` (license allowlist + args schema)
#   * virbius-review-sdk  -> input/output review over the engine /v1/evaluate
#
# The Agent keeps its own tool functions; Virbius is enforced in-process (soft
# boundary / defense-in-depth) rather than in the proxy.
#
# Checks: the allowlisted tool passes precheck, the non-allowlisted tool is
# denied, and output review flags an XSS payload (seeded engine rule). With an
# LLM key present a real ReAct loop runs through the guarded tools.
#
# Usage: bash scripts/oss-agent-e2e-sdk.sh   [SKIP_BUILD=1] [KEEP_WORK=1]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

oa_boot_all

export VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT"
export TENANT_ID="$TENANT"
export SESSION_ID="oss-sdk-$$"

AGENT="$OA_ROOT/examples/oss-agent-target/target_agent.py"

# ── deterministic: in-process precheck + review (no LLM) ────────────────────
info "E2E: SDK口径 — precheck_tool + review-sdk (in-process, no engine chat proxy)"
PROBE="$(VIRBIUS_ENGINE_URL="$VIRBIUS_ENGINE_URL" "$PY" "$AGENT" sdk probe 2> "$OA_LOGS/sdk-probe.err")" || {
  red "sdk probe failed:"; cat "$OA_LOGS/sdk-probe.err"; exit 2; }
printf '%s\n' "$PROBE" > "$OA_WORK/sdk-probe.json"
echo "$PROBE" | "$PY" -m json.tool >/dev/null 2>&1 && info "  probe output captured"

check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("precheck",{}).get("read_doc",{}).get("allowed") is True else "0")')" \
      "virbius_mcp_python precheck: allowlisted tool (read_doc) allowed"
check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("precheck",{}).get("danger_exec",{}).get("allowed") is False else "0")')" \
      "virbius_mcp_python precheck: non-allowlisted tool (danger_exec) denied"
check "$(printf '%s' "$PROBE" | jget 'print("1" if "allowlist" in (d.get("precheck",{}).get("danger_exec",{}).get("reason") or "") else "0")')" \
      "precheck denial reason names the license allowlist"

# framework-agnostic govern(fn) — wrap any callable, no framework needed
check "$(printf '%s' "$PROBE" | jget 'print("1" if (d.get("govern",{}).get("framework_agnostic_allow") or "").startswith("contents") else "0")')" \
      "govern(fn): allowlisted callable runs (framework-agnostic)"
check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("govern",{}).get("danger_exec_denied") is True else "0")')" \
      "govern(fn): non-allowlisted callable raises VirbiusDenied"
check "$(printf '%s' "$PROBE" | jget 'print("1" if "blocked by policy" in (d.get("govern",{}).get("denied_message") or "") else "0")')" \
      "govern denial message is the model-visible policy refusal"

check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("review_output",{}).get("blocked") is True else "0")')" \
      "virbius-review-sdk output review blocks the XSS payload"
check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("review_output",{}).get("disposal") in ("reject","omit") else "0")')" \
      "output review disposal_action is reject/omit (engine rule applied)"
check "$(printf '%s' "$PROBE" | jget 'print("1" if d.get("review_input",{}).get("risky_action") else "0")')" \
      "review_input returns a verdict from the engine /v1/evaluate"

# ── LangChain adapter (no LLM) ──────────────────────────────────────────────
info "E2E: LangChain adapter — guarded StructuredTool allow/deny"
LC="$(VIRBIUS_ENGINE_URL="$VIRBIUS_ENGINE_URL" "$PY" "$AGENT" sdk langchain 2> "$OA_LOGS/sdk-langchain.err" || true)"
if [ -z "$LC" ] || printf '%s' "$LC" | jget 'print("SKIP" if d.get("skipped") else "RUN")' | grep -q SKIP; then
  skip "LangChain adapter skipped — install langchain-core (pip install langchain-core)"
else
  check "$(printf '%s' "$LC" | jget 'print("1" if (d.get("read_doc_allowed") or "").startswith("contents") else "0")')" \
        "langchain_tool: allowlisted tool runs through the adapter"
  check "$(printf '%s' "$LC" | jget 'print("1" if "blocked by policy" in (d.get("danger_exec_message") or "") else "0")')" \
        "langchain_tool: denial surfaces the policy message (model-visible)"
fi

# ── optional real run: the Agent drives the guarded tools via ReAct ─────────
if oa_llm_available; then
  info "E2E: Agent ReAct loop with guarded tools (LLM key present)"
  RUN_OUT="$("$PY" "$AGENT" sdk run \
    "Read /tmp/report.txt with read_doc, then try danger_exec with 'rm -rf /'." 2> "$OA_LOGS/sdk-run.err")" || {
    red "sdk run failed:"; cat "$OA_LOGS/sdk-run.err"; exit 2; }
  printf '%s\n' "$RUN_OUT" > "$OA_WORK/sdk-run.json"
  echo "$RUN_OUT" | jget 'print("  final_output:", (d.get("final_output") or "")[:160])' || true
  check "$(printf '%s' "$RUN_OUT" | jget 'print("1" if d.get("final_output") else "0")')" \
        "Agent completed a run with SDK-guarded tools (non-empty final output)"
else
  skip "Agent ReAct loop skipped — set OPENAI_API_KEY (or DEEPSEEK_API_KEY) to run it"
fi

oa_summary
