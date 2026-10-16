#!/usr/bin/env bash
# =============================================================================
# Set up the Python environment for the OSS-agent target (examples/oss-agent-target).
#
# Creates a Python 3.11 venv and installs:
#   - maturin + the built virbius-mcp-python extension (PyO3 binding of virbius-core)
#   - openai-agents (the target Agent SDK)
#   - virbius-review-sdk (editable, local)
#
# 3.11 is required because openai-agents needs >=3.10 while the prebuilt extension
# in virbius-mcp-python/.venv is cpython-39; we rebuild it for 3.11 here.
#
# On slow networks set a mirror, e.g.:
#   PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple bash scripts/oss-agent-setup.sh
# =============================================================================
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

PYBIN="${VIRBIUS_PYTHON:-python3.11}"
VENV="$ROOT/examples/oss-agent-target/.venv"
IDX="${PIP_INDEX_URL:-https://pypi.org/simple}"

command -v "$PYBIN" >/dev/null 2>&1 || { echo "need $PYBIN (set VIRBIUS_PYTHON)"; exit 2; }
command -v cargo  >/dev/null 2>&1 || { echo "need cargo (for the PyO3 binding)"; exit 2; }

echo "[setup] venv: $VENV (index: $IDX)"
[ -d "$VENV" ] || "$PYBIN" -m venv "$VENV"
"$VENV/bin/pip" install -q -U pip maturin -i "$IDX"

echo "[setup] building virbius-mcp-python extension (cp311)..."
( cd "$ROOT/virbius-mcp-python" && VIRTUAL_ENV="$VENV" "$VENV/bin/maturin" develop --release )

echo "[setup] installing openai-agents + langchain-core + virbius-review-sdk..."
"$VENV/bin/pip" install -q -i "$IDX" openai-agents langchain-core
"$VENV/bin/pip" install -q -i "$IDX" -e "$ROOT/virbius-review-sdk"

echo "[setup] verifying imports..."
"$VENV/bin/python" - <<'PY'
import agents, openai, requests, virbius_review, virbius_mcp_python
import langchain_core.tools
from agents.mcp import MCPServerSse
print("ok: agents", agents.__version__, "| openai", openai.__version__, "| virbius_review", virbius_review.__version__)
PY
echo "[setup] done."
