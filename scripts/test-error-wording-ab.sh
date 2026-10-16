#!/usr/bin/env bash
# =============================================================================
# A/B harness: does the wording of a blocked-tool error change the model's
# behaviour? Runs the target agent (OpenAI Agents SDK) with a deterministically
# denied tool under two failure-message texts (the SDK default vs Virbius' own
# refusal wording) and reports how often the model retries the blocked call.
#
# Informational, model-dependent — never gates on the result.
# Exit codes: 0 ran · 77 skipped (no key) · 2 setup/usage error.
#
# Key resolution (env first; no secret committed):
#   1. OPENAI_API_KEY / DEEPSEEK_API_KEY (with *_BASE_URL / *_MODEL)
#   2. else, if $AB_ENV_FILE exists (default: the gitignored virbius-eval/.env),
#      read ONLY the OPENAI_*/DEEPSEEK_* keys from it
#   3. else SKIP (exit 77)
#
# Usage: bash scripts/test-error-wording-ab.sh [TRIALS] [--json]
#        TRIALS=8 bash scripts/test-error-wording-ab.sh
#        bash scripts/test-error-wording-ab.sh --self-test      # no API calls
#        AB_ENV_FILE=/dev/null bash scripts/test-error-wording-ab.sh   # force SKIP
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$ROOT/examples/oss-agent-target/.venv"
PY="$VENV/bin/python"
AB_ENV_FILE="${AB_ENV_FILE:-$ROOT/virbius-eval/.env}"
TRIALS="${TRIALS:-6}"
if [[ "${1:-}" =~ ^[0-9]+$ ]]; then TRIALS="$1"; shift; fi

if [ ! -x "$PY" ]; then
  echo "[setup] examples venv missing — running scripts/oss-agent-setup.sh..."
  bash "$ROOT/scripts/oss-agent-setup.sh" || { echo "venv setup failed" >&2; exit 2; }
fi

# --self-test makes no API calls, so it needs no key.
for arg in "$@"; do
  if [ "$arg" = "--self-test" ]; then
    exec "$PY" "$ROOT/examples/oss-agent-target/error_wording_ab.py" --self-test
  fi
done

# Convenience: read a local, gitignored key file if no key is in the env.
# Whitelist only OPENAI_*/DEEPSEEK_* so we never execute arbitrary lines; strip
# optional `export `, surrounding quotes, and CR so values are used verbatim.
if [ -z "${OPENAI_API_KEY:-}${DEEPSEEK_API_KEY:-}" ] && [ -f "$AB_ENV_FILE" ]; then
  while IFS='=' read -r key val || [ -n "$key" ]; do
    key="${key#"${key%%[![:space:]]*}"}"   # strip leading whitespace
    key="${key#export }"                   # tolerate an `export ` prefix
    val="${val%$'\r'}"                     # strip a trailing CR (CRLF files)
    case "$val" in
      \"*\") val="${val#\"}"; val="${val%\"}" ;;   # strip one layer of double quotes
      \'*\') val="${val#\'}"; val="${val%\'}" ;;   # strip one layer of single quotes
    esac
    case "$key" in
      OPENAI_API_KEY|OPENAI_BASE_URL|OPENAI_MODEL|DEEPSEEK_API_KEY|DEEPSEEK_BASE_URL|DEEPSEEK_MODEL)
        export "$key=$val" ;;
    esac
  done < "$AB_ENV_FILE"
fi

if [ -z "${OPENAI_API_KEY:-}${DEEPSEEK_API_KEY:-}" ]; then
  echo "[SKIP] no LLM key — set OPENAI_API_KEY or DEEPSEEK_API_KEY (see the file header)."
  exit 77
fi

exec "$PY" "$ROOT/examples/oss-agent-target/error_wording_ab.py" --trials "$TRIALS" "$@"
