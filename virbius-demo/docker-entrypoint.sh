#!/usr/bin/env bash
# virbiusDemo container entrypoint.
#
# 1. Starts per-lab FastMCP SSE upstreams (the "upstream" of the Rust mcp-proxy).
# 2. Launches the Flask demo app in the foreground.
#
# The Rust virbius-mcp-proxy is spawned by the demo app itself (cwd = /app),
# and reads ./virbius-mcp-proxy.toml. Its engine/control/upstream addresses
# are overridden by the VIRBIUS_* environment variables provided by compose.
set -e

cd /app

echo "[entrypoint] starting FastMCP SSE labs on 127.0.0.1:9091-9097 ..."
python -m mcp_runtime.serve &
SSE_PID=$!

# Give the SSE servers a moment to bind before the app starts.
sleep 2
if ! kill -0 "$SSE_PID" 2>/dev/null; then
  echo "[entrypoint] ERROR: FastMCP SSE supervisor failed to start" >&2
  exit 1
fi

if [ ! -f virbius-mcp-proxy.toml ]; then
  echo "[entrypoint] WARNING: virbius-mcp-proxy.toml not found; proxy will use defaults."
fi

echo "[entrypoint] starting demo app on port ${PORT:-8000} ..."
exec python app.py
