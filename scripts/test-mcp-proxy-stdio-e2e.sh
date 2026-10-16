#!/usr/bin/env bash
# =============================================================================
# MCP-proxy STDIO-mod E2E — the deployment shape most MCP clients actually use
# (Claude Desktop & friends spawn the proxy and speak newline-delimited
# JSON-RPC on stdin/stdout). This closes the "stdio 模式没评估" gap: same
# isolation rig as the other harnesses, but the real proxy binary is driven over
# stdio instead of SSE.
#
# Checks:
#   * initialize / tools/list / tools/call work over stdio
#   * allowlisted tool -> ok; non-allowlisted tool -> not_in_allowlist
#   * the `virbius/status` JSON-RPC method returns the license summary + recent
#     decisions (stdio has no HTTP GET /status, so the operator surface rides
#     the same wire)
#   * the emitted audit events still carry the canonical field names (Kafka)
#     and land in control (decision-bom via the ops API)
#
# Usage: bash scripts/test-mcp-proxy-stdio-e2e.sh  [SKIP_BUILD=1] [KEEP_WORK=1]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

: "${UPSTREAM_PORT:=19091}"
PROXY_BIN="$OA_ROOT/target/debug/virbius-mcp-proxy"

oa_boot_all

# ── upstream MCP SSE server (the tools the proxy fronts) ────────────────────
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
[ -f "$PROXY_BIN" ] || { red "proxy binary missing"; exit 2; }

oa_free_port "$UPSTREAM_PORT"
info "starting upstream MCP SSE server on :$UPSTREAM_PORT..."
"$PY" "$OA_WORK/upstream.py" "$UPSTREAM_PORT" > "$OA_LOGS/upstream.log" 2>&1 &
# read indirectly via ${!v} in oa_cleanup
# shellcheck disable=SC2034
OA_UPSTREAM_PID=$!
for _ in $(seq 1 20); do nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null && break; sleep 0.5; done
nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null || { red "upstream failed to start"; tail -20 "$OA_LOGS/upstream.log"; exit 2; }

# ── drive the REAL proxy over stdio (newline-delimited JSON-RPC) ────────────
info "E2E: driving the real proxy over stdio (initialize -> read_doc -> danger_exec -> virbius/status)"
CLIENT_OUT="$(
  VIRBIUS_UPSTREAM_URL="http://127.0.0.1:$UPSTREAM_PORT" \
  VIRBIUS_UPSTREAM_TRANSPORT="sse" VIRBIUS_UPSTREAM_SSE_PATH="/sse" \
  VIRBIUS_LICENSE_FILE="$LICENSE_JWT_FILE" VIRBIUS_LICENSE_PUBLIC_KEY="$LICENSE_PUB_FILE" \
  VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT" \
  VIRBIUS_CONTROL_URL="http://127.0.0.1:$CONTROL_PORT" \
  VIRBIUS_AUDIT_BACKEND="kafka" VIRBIUS_AUDIT_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT" \
  VIRBIUS_AUDIT_KAFKA_TOPIC="virbius-audit-events" \
  VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
  PROXY_STDERR="$OA_LOGS/proxy.log" \
  "$PY" - "$PROXY_BIN" <<'PY'
import json, os, subprocess, sys, threading

proxy = subprocess.Popen([sys.argv[1]], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=open(os.environ.get("PROXY_STDERR", "/dev/null"), "w"), text=True, bufsize=1)
out = {}

def send(obj):
    proxy.stdin.write(json.dumps(obj) + "\n")
    proxy.stdin.flush()

def read_until(want_id, limit=200):
    for _ in range(limit):
        line = proxy.stdout.readline()
        if not line:
            break
        line = line.strip()
        if not line.startswith("{"):
            continue
        msg = json.loads(line)
        if msg.get("id") == want_id:
            return msg
    return {"error": "timeout awaiting id %s" % want_id}

send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
      "params": {"capabilities": {}, "protocolVersion": "2024-11-05",
                 "clientInfo": {"name": "stdio-e2e", "version": "0.1"}}})
out["initialize"] = read_until(1)
send({"jsonrpc": "2.0", "method": "notifications/initialized"})

send({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
out["tools_list"] = read_until(2)

send({"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "read_doc", "arguments": {"path": "/tmp/a"}}})
out["allow"] = read_until(3)

send({"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "danger_exec", "arguments": {"cmd": "rm -rf /"}}})
out["block"] = read_until(4)

send({"jsonrpc": "2.0", "id": 5, "method": "virbius/status"})
out["status"] = read_until(5)

proxy.stdin.close()
try:
    proxy.wait(timeout=5)
except subprocess.TimeoutExpired:
    proxy.kill()
print(json.dumps(out))
PY
)"
printf '%s\n' "$CLIENT_OUT" > "$OA_WORK/stdio_client.json"

check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("initialize",{}).get("result",{}).get("serverInfo") else "0")')" \
      "stdio: initialize returns serverInfo"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if [t.get("name") for t in d.get("tools_list",{}).get("result",{}).get("tools",[])]==["read_doc"] else "0")')" \
      "stdio: tools/list is filtered to the allowlist (read_doc only)"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("allow",{}).get("result") else "0")')" \
      "stdio: allowlisted tool (read_doc) returned a result"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("block",{}).get("error",{}).get("code")==-32004 and "allowlist" in str(d.get("block",{}).get("error",{}).get("message","")) else "0")')" \
      "stdio: non-allowlisted tool denied (-32004, message names the allowlist)"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("status",{}).get("result",{}).get("fallback_license",{}).get("verified",{}).get("ok") is True else "0")')" \
      "stdio: virbius/status verifies the fallback license"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if isinstance(d.get("status",{}).get("result",{}).get("recent_decisions"),list) and len(d.get("status",{}).get("result",{}).get("recent_decisions"))>=1 else "0")')" \
      "stdio: virbius/status exposes recent decisions"

# ── audit wiring still holds in stdio mode (Kafka wire + control ingest) ────
info "E2E: stdio mode still audits (Kafka wire + control ingest)"
sleep 3
docker exec "$(docker ps --format '{{.Names}}' | grep -m1 kafka)" /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic virbius-audit-events \
  --from-beginning --timeout-ms 8000 --max-messages 4000 > "$OA_WORK/audit.jsonl" 2>/dev/null || true
EV="$(python3 - "$OA_WORK/audit.jsonl" "$TENANT" <<'PY'
import json, sys
path, tenant = sys.argv[1], sys.argv[2]
ev = []
for line in open(path, errors="replace"):
    line = line.strip()
    if not line.startswith("{"):
        continue
    try: e = json.loads(line)
    except ValueError: continue
    if e.get("tenant_id") == tenant:
        ev.append(e)
json.dump(ev, sys.stdout)
PY
)"
printf '%s' "$EV" > "$OA_WORK/events_tenant.json"
check "$(printf '%s' "$EV" | jget 'print("1" if any(e.get("effective_action")=="allow" for e in d) and any(e.get("effective_action")=="block" for e in d) else "0")')" \
      "stdio: wire has both an allow and a block audit event"
check "$(printf '%s' "$EV" | jget 'print("1" if d and all(e.get("event_id") and e.get("intercepted_at") and e.get("tool_name") and "effective_action" in e and "action" not in e for e in d) else "0")')" \
      "stdio: audit events carry canonical keys (event_id/intercepted_at/tool_name, no legacy action)"

TRACE_ID="$(printf '%s' "$EV" | jget 'print([e.get("trace_id") for e in d if e.get("effective_action")=="block" and e.get("trace_id")][0] if any(e.get("effective_action")=="block" and e.get("trace_id") for e in d) else "")')"
BOM=""; OK=0
for _ in $(seq 1 40); do
  [ -n "$TRACE_ID" ] || break
  BOM=$(curl -s "http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/$TENANT/audit/decision-bom/$TRACE_ID")
  N=$(printf '%s' "$BOM" | jget 'print(len(d.get("data",{}).get("audit_events",[])))')
  [ "${N:-0}" -ge 1 ] 2>/dev/null && { OK=1; break; }
  sleep 1
done
check "$OK" "stdio: control ingested the proxied audit row (decision-bom)"

oa_summary
