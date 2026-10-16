#!/usr/bin/env bash
# =============================================================================
# OSS-Agent integration E2E — MODE: MCP-Proxy.
#
# Drives a real OpenAI Agents SDK agent whose tools are served by the Rust
# `virbius-mcp-proxy` (MCPServerSse). The proxy is the enforcement point:
# license allowlist filtering (tools/list), policy decisions and audit all
# happen in the proxy process — the Agent only speaks MCP.
#
# Flow: control+engine (isolated) -> upstream MCP SSE server -> virbius-mcp-proxy
#       -> Agent (MCPServerSse) -> tools/list + (LLM) tool calls.
#
# Checks: the proxy exposes ONLY the allowlisted tool; and, when an LLM key is
# present, the Agent runs a real ReAct loop through the proxy. Without a key the
# LLM step is SKIPped (deterministic wiring is still asserted).
#
# Usage: bash scripts/oss-agent-e2e-proxy.sh   [SKIP_BUILD=1] [KEEP_WORK=1]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

: "${PROXY_PORT:=19090}"
: "${UPSTREAM_PORT:=19091}"
PROXY_BIN="$OA_ROOT/target/debug/virbius-mcp-proxy"

oa_boot_all

# ── upstream MCP SSE server (the tools the Agent ultimately calls) ──────────
cat > "$OA_WORK/upstream.py" <<'PY'
import json, queue, threading, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
TOOLS = [
    {"name": "read_doc", "description": "read a document", "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}},
    {"name": "danger_exec", "description": "execute a shell command", "inputSchema": {"type": "object", "properties": {"cmd": {"type": "string"}}}},
]
sessions, lock = {}, threading.Lock()
def q(sid):
    with lock:
        it = sessions.get(sid)
        if it is None: it = queue.Queue(); sessions[sid] = it
        return it
def dispatch(r):
    if not isinstance(r, dict): return {"jsonrpc":"2.0","id":None,"error":{"code":-32700,"message":"parse"}}
    m=r.get("method"); i=r.get("id"); p=r.get("params") or {}
    if isinstance(m,str) and m.startswith("notifications/"): return None
    if m=="initialize": return {"jsonrpc":"2.0","id":i,"result":{"protocolVersion":"2024-11-05","capabilities":{},"serverInfo":{"name":"oss-upstream","version":"0.1.0"}}}
    if m=="tools/list": return {"jsonrpc":"2.0","id":i,"result":{"tools":TOOLS}}
    if m=="tools/call": return {"jsonrpc":"2.0","id":i,"result":{"content":[{"type":"text","text":"ok:"+str(p.get("name"))}]}}
    return {"jsonrpc":"2.0","id":i,"error":{"code":-32601,"message":"method not found"}}
class H(BaseHTTPRequestHandler):
    def log_message(self,*a): pass
    def sse(self,e,d): self.wfile.write(("event: %s\ndata: %s\n\n"%(e,d)).encode()); self.wfile.flush()
    def do_GET(self):
        if urllib.parse.urlparse(self.path).path.rstrip("/")!="/sse": return self.send_error(404)
        sid=urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("session_id",[""])[0] or "s-"+uuid.uuid4().hex
        qq=q(sid); self.send_response(200)
        for k,v in (("Content-Type","text/event-stream"),("Cache-Control","no-cache"),("Connection","keep-alive")): self.send_header(k,v)
        self.end_headers(); self.sse("endpoint","/messages/?session_id="+urllib.parse.quote(sid))
        while True:
            try: msg=qq.get(timeout=30)
            except queue.Empty: self.wfile.write(b": ping\n\n"); self.wfile.flush(); continue
            self.sse("message",msg)
    def do_POST(self):
        if urllib.parse.urlparse(self.path).path.rstrip("/")!="/messages": return self.send_error(404)
        sid=urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("session_id",["default"])[0]
        body=self.rfile.read(int(self.headers.get("Content-Length",0))).decode("utf-8","replace")
        try: r=json.loads(body)
        except ValueError: r=None
        resp=dispatch(r)
        if resp is not None: q(sid).put(json.dumps(resp))
        self.send_response(202); self.end_headers(); self.wfile.write(b"")
ThreadingHTTPServer(("127.0.0.1", int(__import__("sys").argv[1])), H).serve_forever()
PY

if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$PROXY_BIN" ]; then
  info "building virbius-mcp-proxy (cargo)..."
  cargo build -p virbius-mcp-proxy > "$OA_LOGS/proxy-build.log" 2>&1 || { red "cargo build failed"; tail -30 "$OA_LOGS/proxy-build.log"; exit 2; }
fi
[ -f "$PROXY_BIN" ] || { red "proxy binary missing: $PROXY_BIN"; exit 2; }

oa_free_port "$UPSTREAM_PORT"
info "starting upstream MCP SSE server on :$UPSTREAM_PORT..."
"$PY" "$OA_WORK/upstream.py" "$UPSTREAM_PORT" > "$OA_LOGS/upstream.log" 2>&1 &
# read indirectly via ${!v} in oa_cleanup
# shellcheck disable=SC2034
OA_UPSTREAM_PID=$!
for _ in $(seq 1 20); do nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null && break; sleep 0.5; done
nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null || { red "upstream failed to start"; tail -20 "$OA_LOGS/upstream.log"; exit 2; }

oa_free_port "$PROXY_PORT"
info "starting real virbius-mcp-proxy on :$PROXY_PORT..."
env VIRBIUS_TRANSPORT="tcp://127.0.0.1:$PROXY_PORT" \
    VIRBIUS_UPSTREAM_URL="http://127.0.0.1:$UPSTREAM_PORT" \
    VIRBIUS_UPSTREAM_TRANSPORT="sse" VIRBIUS_UPSTREAM_SSE_PATH="/sse" \
    VIRBIUS_LICENSE_FILE="$LICENSE_JWT_FILE" \
    VIRBIUS_LICENSE_PUBLIC_KEY="$LICENSE_PUB_FILE" \
    VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT" \
    VIRBIUS_CONTROL_URL="http://127.0.0.1:$CONTROL_PORT" \
    VIRBIUS_AUDIT_BACKEND="kafka" VIRBIUS_AUDIT_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT" \
    VIRBIUS_AUDIT_KAFKA_TOPIC="virbius-audit-events" \
    VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
    "$PROXY_BIN" > "$OA_LOGS/proxy.log" 2>&1 &
OA_PROXY_PID=$!
oa_wait_http "http://127.0.0.1:$PROXY_PORT/health" 30 || { red "proxy failed to start"; tail -30 "$OA_LOGS/proxy.log"; exit 2; }
oa_assert_alive "$OA_PROXY_PID" || { red "proxy died"; tail -30 "$OA_LOGS/proxy.log"; exit 2; }
info "proxy up."

export PROXY_SSE_URL="http://127.0.0.1:$PROXY_PORT/sse"

# ── deterministic wiring: the proxy filters tools/list by the license ───────
info "E2E: OpenAI Agents SDK connects to the proxy via MCPServerSse"
LIST_OUT="$("$PY" "$OA_ROOT/examples/oss-agent-target/target_agent.py" proxy list 2> "$OA_LOGS/agent-list.err")" || {
  red "agent proxy list failed:"; cat "$OA_LOGS/agent-list.err"; exit 2; }
printf '%s\n' "$LIST_OUT" > "$OA_WORK/proxy-list.json"
echo "$LIST_OUT" | jget 'print("  tools:", d.get("tools"))' || true
check "$(printf '%s' "$LIST_OUT" | jget 'print("1" if d.get("tools")==["read_doc"] else "0")')" \
      "proxy exposes ONLY the allowlisted tool (read_doc); danger_exec filtered out"
check "$(printf '%s' "$LIST_OUT" | jget 'print("1" if "danger_exec" not in (d.get("tools") or []) else "0")')" \
      "non-allowlisted tool (danger_exec) is NOT visible to the agent"

# ── operator surface: GET /status (config summary + license + decisions) ────
info "E2E: proxy operator surface GET /status"
STATUS="$(curl -s --max-time 5 "http://127.0.0.1:$PROXY_PORT/status")"
check "$(printf '%s' "$STATUS" | jget 'print("1" if d.get("version") else "0")')" \
      "/status reports the proxy version"
check "$(printf '%s' "$STATUS" | jget 'print("1" if d.get("fallback_license",{}).get("verified",{}).get("ok") is True else "0")')" \
      "/status verifies the fallback license (app_id/tenant/tools summary)"
check "$(printf '%s' "$STATUS" | jget 'print("1" if isinstance(d.get("recent_decisions"),list) else "0")')" \
      "/status exposes the recent-decisions ring"

# ── error → LLM propagation: a denied call yields a model-usable message ─────
info "E2E: denied tool call surfaces a model-usable message (error→LLM)"
DENY="$("$PY" "$OA_ROOT/examples/oss-agent-target/target_agent.py" proxy deny --tool "$BLOCK_TOOL" 2> "$OA_LOGS/agent-deny.err")" || {
  red "agent proxy deny failed:"; cat "$OA_LOGS/agent-deny.err"; exit 2; }
printf '%s\n' "$DENY" > "$OA_WORK/proxy-deny.json"
echo "$DENY" | jget 'print("  model_visible:", d.get("model_visible"))' || true
check "$(printf '%s' "$DENY" | jget 'print("1" if d.get("denied") is True else "0")')" \
      "denied call raised through the MCP client (the SDK failure path)"
check "$(printf '%s' "$DENY" | jget 'print("1" if "allowlist" in (d.get("error") or "") else "0")')" \
      "the raw MCP error message states the policy (mentions the license allowlist)"
check "$(printf '%s' "$DENY" | jget 'print("1" if "blocked by policy" in (d.get("model_visible") or "") else "0")')" \
      "the model-visible message is the proxy's human denial (not the SDK generic retry)"

# ── optional real run: the Agent drives the proxy through a ReAct loop ──────
if oa_llm_available; then
  info "E2E: Agent ReAct loop through the proxy (LLM key present)"
  RUN_OUT="$("$PY" "$OA_ROOT/examples/oss-agent-target/target_agent.py" proxy run \
    "Read the document at /tmp/report.txt, then try to run the shell command 'rm -rf /'." 2> "$OA_LOGS/agent-run.err")" || {
    red "agent proxy run failed:"; cat "$OA_LOGS/agent-run.err"; exit 2; }
  printf '%s\n' "$RUN_OUT" > "$OA_WORK/proxy-run.json"
  echo "$RUN_OUT" | jget 'print("  final_output:", (d.get("final_output") or "")[:160])' || true
  check "$(printf '%s' "$RUN_OUT" | jget 'print("1" if d.get("final_output") else "0")')" \
        "Agent completed a run through the proxy (non-empty final output)"
else
  skip "Agent ReAct loop skipped — set OPENAI_API_KEY (or DEEPSEEK_API_KEY) to run it"
fi

oa_summary
