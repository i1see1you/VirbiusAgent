#!/usr/bin/env bash
# =============================================================================
# MCP-proxy AUDIT full-flow E2E — the REAL proxy binary -> Kafka -> control.
#
# Guards the proxy<->control audit field contract (F1). The proxy used to emit
# action/reason/timestamp/session_risk_score while virbius-control (and the
# engine AuditWriter) read effective_action/reason_code/intercepted_at/
# max_risk_score, so every PROXIED audit row landed with empty decision fields.
# It never worked since inception, because every existing harness bypasses the
# proxy serializer: test-guardrails-e2e.sh injects canonical JSON straight to
# Kafka (the AUDIT_TOPIC there is only used to create the topic) and
# test-trace-chain-e2e.sh simulates the proxy wire format with a heredoc. No
# script launched the real proxy. This one does.
#
#   real target/debug/virbius-mcp-proxy (SSE transport)
#     -> Kafka(virbius-audit-events)                    [assert WIRE keys]
#     -> control AuditEventIngestor -> tb_audit_events
#     -> admin API /audit/decision-bom/{traceId}        [assert INGESTED row]
#
# All verification is via the control HTTP API + the Kafka topic (a bus read).
# The script never opens the control SQLite file and never runs redis-cli.
#
# An isolated control + engine pair is booted on temp ports with a temp data
# dir (same hermetic convention as test-trace-chain-e2e.sh), so the assertions
# are scoped to this run's tenant.
#
# Requires: java, cargo, mvn, curl, python3, nc, docker (kafka console consumer).
#
# Usage:        bash scripts/test-mcp-proxy-audit-e2e.sh
# Skip builds:  SKIP_BUILD=1 bash scripts/test-mcp-proxy-audit-e2e.sh
# Keep logs:    KEEP_WORK=1  bash scripts/test-mcp-proxy-audit-e2e.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
ENGINE_PORT="${ENGINE_PORT:-18082}"
PROXY_PORT="${PROXY_PORT:-19090}"
UPSTREAM_PORT="${UPSTREAM_PORT:-19091}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"
KAFKA_HOST="${KAFKA_HOST:-127.0.0.1}"; KAFKA_PORT="${KAFKA_PORT:-9092}"
TOPIC="virbius-audit-events"
# Unique per run: Kafka retains every earlier run's audit events on the shared
# topic, and a fixed tenant would make the --from-beginning read pick up stale
# trace_ids that this run's fresh control DB never ingested.
TENANT="${TENANT:-audit-e2e-$$}"
APP_ID="audit-e2e-app"
ALLOW_TOOL="read_doc"
BLOCK_TOOL="danger_exec"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-proxyaudit.XXXXXX")"
LOGS="$WORK/logs"; DATA="$WORK/data"
mkdir -p "$LOGS" "$DATA"

PASS=0; FAIL=0
green(){ printf '\033[0;32m%s\033[0m\n' "$*"; }
red(){ printf '\033[0;31m%s\033[0m\n' "$*"; }
info(){ printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
check(){ if [ "$1" = "1" ]; then PASS=$((PASS+1)); green "  PASS  $2"; else FAIL=$((FAIL+1)); red "  FAIL  $2"; fi; }
jget(){ python3 -c "import sys,json;d=json.load(sys.stdin);$1" "${2:-}" 2>/dev/null || echo "PARSE_ERROR"; }

wait_http(){ # $1=url $2=tries
  local url="$1" tries="${2:-40}" i
  for i in $(seq 1 "$tries"); do
    curl -sf --max-time 2 "$url" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

# A stale process from an earlier manual run can hold our port: our freshly
# spawned proxy then dies with "Address already in use" while wait_http is
# happily satisfied by the FOREIGN listener, and the assertions run against the
# wrong (pre-fix) binary. So clear the port first, and afterwards verify OUR pid
# is still alive rather than trusting the port alone.
free_port(){ # $1=port
  local port="$1" pids
  pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"
  [ -z "$pids" ] && return 0
  info "freeing port $port (stale listener pid(s): $pids)"
  kill $pids 2>/dev/null || true
  for _ in $(seq 1 20); do
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break
    sleep 0.5
  done
  lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && { red "port $port still occupied"; exit 2; }
  return 0
}
assert_alive(){ # $1=pid $2=what
  if ! kill -0 "$1" 2>/dev/null; then
    red "$2 exited right after startup — log tail:"
    return 1
  fi
  return 0
}

cleanup() {
  info "shutting down..."
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null || true
  [ -n "${CONTROL_PID:-}" ] && kill "$CONTROL_PID" 2>/dev/null || true
  [ -n "${ENGINE_PID:-}" ] && kill "$ENGINE_PID" 2>/dev/null || true
  [ -n "${UPSTREAM_PID:-}" ] && kill "$UPSTREAM_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${KEEP_WORK:-0}" = "1" ]; then info "KEEP_WORK=1: logs at $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT INT TERM

for bin in java cargo mvn curl python3 nc docker; do
  command -v "$bin" >/dev/null 2>&1 || { red "missing required tool: $bin"; exit 2; }
done
if pgrep -f 'virbius-control-.*\.jar' >/dev/null 2>&1; then
  red "another virbius-control process is already running:"
  pgrep -fl 'virbius-control-.*\.jar' >&2 || true
  red "stop it first — it shares the Redis audit chain head and the Kafka consumer group."
  exit 2
fi
nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null || { red "Redis not reachable at $REDIS_HOST:$REDIS_PORT"; exit 2; }
if ! nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null; then
  info "Kafka not on $KAFKA_HOST:$KAFKA_PORT — starting via docker-compose.infra.yml..."
  docker compose -p virbius-infra -f "$ROOT/docker-compose.infra.yml" up -d --wait kafka >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null && break; sleep 1; done
  nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null || { red "Kafka not reachable"; exit 2; }
fi
KAFKA_CID="$(docker ps --format '{{.Names}}' | grep -m1 kafka || true)"
[ -n "$KAFKA_CID" ] || { red "no kafka container found"; exit 2; }
docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic "$TOPIC" --partitions 3 --replication-factor 1 >/dev/null 2>&1 || true

wait_group_drained() { # $1=group $2=timeout_secs
  local group="$1" timeout="${2:-90}" i n
  for i in $(seq 1 "$timeout"); do
    n=$(docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
         --group "$group" --describe --members 2>/dev/null | awk -v g="$group" '$1==g' | wc -l | tr -d ' ')
    [ "${n:-0}" -eq 0 ] && return 0
    sleep 1
  done
  return 1
}
wait_group_drained "virbius-audit-ingest" 90 \
  || { red "consumer group virbius-audit-ingest still has a live member — stop the other control first"; exit 2; }

APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
CONTROL_JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
ENGINE_JAR="$ROOT/virbius-engine/target/virbius-engine-${APP_VER}.jar"
PROXY_BIN="$ROOT/target/debug/virbius-mcp-proxy"
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$CONTROL_JAR" ] || [ ! -f "$ENGINE_JAR" ]; then
  info "building control + engine (skip tests)..."
  mvn -q -pl virbius-engine,virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm \
    > "$LOGS/build.log" 2>&1 || { red "maven build failed"; tail -30 "$LOGS/build.log"; exit 2; }
fi
[ -f "$CONTROL_JAR" ] || { red "control jar missing: $CONTROL_JAR"; exit 2; }
[ -f "$ENGINE_JAR" ] || { red "engine jar missing: $ENGINE_JAR"; exit 2; }
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$PROXY_BIN" ]; then
  info "building virbius-mcp-proxy (cargo)..."
  cargo build -p virbius-mcp-proxy > "$LOGS/proxy-build.log" 2>&1 || { red "cargo build failed"; tail -30 "$LOGS/proxy-build.log"; exit 2; }
fi
[ -f "$PROXY_BIN" ] || { red "proxy binary missing: $PROXY_BIN"; exit 2; }

export VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
export VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"

free_port "$ENGINE_PORT"
info "starting engine on :$ENGINE_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!
wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed to start"; tail -n 30 "$LOGS/engine.log"; exit 2; }
assert_alive "$ENGINE_PID" "virbius-engine" || { tail -n 30 "$LOGS/engine.log"; exit 2; }
info "engine up."

free_port "$CONTROL_PORT"
info "starting control on :$CONTROL_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" \
      --spring.kafka.consumer.auto-offset-reset=latest > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!
wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/ready" || { red "control failed to start"; tail -n 30 "$LOGS/control.log"; exit 2; }
assert_alive "$CONTROL_PID" "virbius-control" || { tail -n 30 "$LOGS/control.log"; exit 2; }
info "control up (pid $CONTROL_PID)."

C="http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/$TENANT"

# ── bootstrap: tenant + tools + license (the proxy's fallback credential) ────
info "bootstrapping tenant $TENANT + license ($APP_ID)..."
curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants" \
  -H 'Content-Type: application/json' -d "{\"tenant_id\":\"$TENANT\",\"name\":\"audit e2e\"}" >/dev/null
for tool in "$ALLOW_TOOL" "$BLOCK_TOOL"; do
  curl -s -X POST "$C/tools" -H 'Content-Type: application/json' \
    -d "{\"tool_name\":\"$tool\",\"risk_class\":\"low\",\"sandbox_type\":\"none\",\"timeout_ms\":30000,\"fast_path\":true,\"approval_mode\":\"lax\"}" >/dev/null
done
LICENSE_JWT="$(curl -s -X POST "$C/licenses/issue" -H 'Content-Type: application/json' \
  -d "{\"app_id\":\"$APP_ID\",\"agent_name\":\"audit-e2e-agent\",\"allowed_tools\":[\"$ALLOW_TOOL\"],\"risk_quota\":100}" \
  | jget 'print(d.get("data",{}).get("jwt") or "")')"
[ -n "$LICENSE_JWT" ] || { red "license issue returned no jwt"; tail -30 "$LOGS/control.log"; exit 2; }
printf '%s' "$LICENSE_JWT" > "$WORK/license.jwt"
# The proxy loads the PEM file RAW (main.rs load_public_key, no trim) and hands
# it to ed25519-dalek's from_public_key_pem, which rejects a trailing blank
# line. Normalise to exactly one trailing newline (the API value already ends
# with one, so a naive `print` would double it and every verify -> InvalidSignature).
curl -s "$C/licenses/public-key" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); sys.stdout.write((d.get("data",{}).get("public_key_pem") or "").strip()+"\n")' \
  > "$WORK/license_pub.pem"
grep -q "BEGIN PUBLIC KEY" "$WORK/license_pub.pem" || { red "public-key fetch failed"; exit 2; }
info "license issued ($APP_ID, allowlist=$ALLOW_TOOL)."

# ── upstream MCP SSE server (stands in for a real MCP Server) ────────────────
cat > "$WORK/mcp.py" <<'PY'
#!/usr/bin/env python3
"""Minimal MCP SSE server + client, aligned with the Rust proxy handshake.

  server <port>                     upstream MCP SSE server (GET /sse, POST /messages)
  client <port> <allow_tool> <blk>  drives the PROXY: initialize -> tools/call x2
"""
import json, queue, sys, threading, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    {"name": "read_doc", "description": "read a document",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "danger_exec", "description": "exec something dangerous",
     "inputSchema": {"type": "object", "properties": {}}},
]


def serve(port):
    sessions, lock = {}, threading.Lock()

    def get_queue(sid):
        with lock:
            q = sessions.get(sid)
            if q is None:
                q = queue.Queue(); sessions[sid] = q
            return q

    def dispatch(req):
        if not isinstance(req, dict):
            return {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}}
        method = req.get("method"); rid = req.get("id"); params = req.get("params") or {}
        if isinstance(method, str) and method.startswith("notifications/"):
            return None
        if method == "initialize":
            return {"jsonrpc": "2.0", "id": rid, "result": {
                "protocolVersion": "2024-11-05", "capabilities": {},
                "serverInfo": {"name": "audit-e2e-upstream", "version": "0.1.0"}}}
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}}
        if method == "tools/call":
            return {"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "ok:" + str(params.get("name"))}]}}
        return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": "method not found"}}

    class H(BaseHTTPRequestHandler):
        def log_message(self, *a): pass
        def sse(self, ev, data):
            self.wfile.write(("event: %s\ndata: %s\n\n" % (ev, data)).encode()); self.wfile.flush()
        def do_GET(self):
            p = urllib.parse.urlparse(self.path)
            if p.path.rstrip("/") != "/sse":
                return self.send_error(404)
            sid = urllib.parse.parse_qs(p.query).get("session_id", [""])[0] or ("sess-" + uuid.uuid4().hex)
            q = get_queue(sid)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()
            self.sse("endpoint", "/messages/?session_id=" + urllib.parse.quote(sid))
            while True:
                try:
                    msg = q.get(timeout=30)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n"); self.wfile.flush(); continue
                self.sse("message", msg)
        def do_POST(self):
            p = urllib.parse.urlparse(self.path)
            if p.path.rstrip("/") != "/messages":
                return self.send_error(404)
            sid = urllib.parse.parse_qs(p.query).get("session_id", ["default"])[0]
            body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
            try:
                req = json.loads(body)
            except ValueError:
                req = None
            resp = dispatch(req)
            if resp is not None:
                get_queue(sid).put(json.dumps(resp))
            self.send_response(202); self.end_headers(); self.wfile.write(b"")

    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()


def client(port, allow_tool, block_tool):
    """Drive the proxy over its SSE transport; print one JSON line per call."""
    import urllib.request
    import http.client

    result = {}
    endpoint = {}
    out = queue.Queue()

    def reader():
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        conn.request("GET", "/sse", headers={"Accept": "text/event-stream"})
        resp = conn.getresponse()
        event, data = None, []
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\n").rstrip("\r")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data.append(line[5:].strip())
            elif line == "":
                if event == "endpoint":
                    endpoint["url"] = "".join(data)
                elif event == "message":
                    out.put(json.loads("".join(data)))
                event, data = None, []

    t = threading.Thread(target=reader, daemon=True); t.start()
    for _ in range(50):
        if endpoint.get("url"): break
        import time; time.sleep(0.2)
    if not endpoint.get("url"):
        print(json.dumps({"error": "no endpoint event from proxy"})); return
    ep = endpoint["url"]

    counter = [0]
    def call(payload):
        counter[0] += 1
        payload = dict(payload); payload["id"] = counter[0]; payload["jsonrpc"] = "2.0"
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, ep),
                                     data=json.dumps(payload).encode(),
                                     method="POST", headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=60).read()
        except Exception:
            pass
        want = payload["id"]
        for _ in range(300):
            try:
                msg = out.get(timeout=0.5)
            except queue.Empty:
                continue
            if msg.get("id") == want:
                return msg
        return {"error": "timeout waiting for id %s" % want}

    init = call({"method": "initialize", "params": {"capabilities": {}, "protocolVersion": "2024-11-05",
                                                    "clientInfo": {"name": "audit-e2e", "version": "0.1"}}})
    result["initialize_error"] = init.get("error")
    try:
        urllib.request.urlopen(urllib.request.Request(
            "http://127.0.0.1:%d%s" % (port, ep),
            data=json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}).encode(),
            method="POST", headers={"Content-Type": "application/json"}), timeout=30).read()
    except Exception:
        pass
    result["allow"] = call({"method": "tools/call", "params": {"name": allow_tool, "arguments": {}}})
    result["block"] = call({"method": "tools/call", "params": {"name": block_tool, "arguments": {}}})
    print(json.dumps(result))


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "server":
        serve(int(sys.argv[2]))
    else:
        client(int(sys.argv[2]), sys.argv[3], sys.argv[4])
PY
chmod +x "$WORK/mcp.py"

free_port "$UPSTREAM_PORT"
info "starting upstream MCP SSE server on :$UPSTREAM_PORT..."
python3 "$WORK/mcp.py" server "$UPSTREAM_PORT" > "$LOGS/upstream.log" 2>&1 &
UPSTREAM_PID=$!
for _ in $(seq 1 20); do nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null && break; sleep 0.5; done
nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null || { red "upstream MCP server failed to start"; tail -20 "$LOGS/upstream.log"; exit 2; }
assert_alive "$UPSTREAM_PID" "upstream MCP server" || { tail -20 "$LOGS/upstream.log"; exit 2; }

# ── the REAL proxy ───────────────────────────────────────────────────────────
free_port "$PROXY_PORT"
info "starting real virbius-mcp-proxy on :$PROXY_PORT..."
env VIRBIUS_TRANSPORT="tcp://127.0.0.1:$PROXY_PORT" \
    VIRBIUS_UPSTREAM_URL="http://127.0.0.1:$UPSTREAM_PORT" \
    VIRBIUS_UPSTREAM_TRANSPORT="sse" \
    VIRBIUS_UPSTREAM_SSE_PATH="/sse" \
    VIRBIUS_LICENSE_FILE="$WORK/license.jwt" \
    VIRBIUS_LICENSE_PUBLIC_KEY="$WORK/license_pub.pem" \
    VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT" \
    VIRBIUS_CONTROL_URL="http://127.0.0.1:$CONTROL_PORT" \
    VIRBIUS_AUDIT_BACKEND="kafka" \
    VIRBIUS_AUDIT_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT" \
    VIRBIUS_AUDIT_KAFKA_TOPIC="$TOPIC" \
    VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
    "$PROXY_BIN" > "$LOGS/proxy.log" 2>&1 &
PROXY_PID=$!
wait_http "http://127.0.0.1:$PROXY_PORT/health" 30 || { red "proxy failed to start"; tail -30 "$LOGS/proxy.log"; exit 2; }
assert_alive "$PROXY_PID" "virbius-mcp-proxy" || { tail -30 "$LOGS/proxy.log"; exit 2; }
info "proxy up (pid $PROXY_PID)."

# ── drive the real proxy: allow + block ──────────────────────────────────────
info "driving MCP session against the proxy (initialize -> $ALLOW_TOOL -> $BLOCK_TOOL)..."
CLIENT_OUT="$(python3 "$WORK/mcp.py" client "$PROXY_PORT" "$ALLOW_TOOL" "$BLOCK_TOOL")" || true
printf '%s\n' "$CLIENT_OUT" > "$WORK/client.json"
echo "$CLIENT_OUT" | python3 -m json.tool 2>/dev/null > "$WORK/client.pretty" || true

ALLOW_ERR="$(printf '%s' "$CLIENT_OUT" | jget 'print(d.get("allow",{}).get("error"))')"
BLOCK_ERR="$(printf '%s' "$CLIENT_OUT" | jget 'print(d.get("block",{}).get("error"))')"
BLOCK_CODE="$(printf '%s' "$CLIENT_OUT" | jget 'print((d.get("block",{}).get("error") or {}).get("code",""))')"

info "E2E: real proxy handled the MCP session"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("initialize_error") in (None,"") else "0")')" \
      "proxy initialize succeeded (fallback license used)"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("allow",{}).get("result") else "0")')" \
      "allowlisted tool ($ALLOW_TOOL) returned a result"
check "$(printf '%s' "$CLIENT_OUT" | jget 'print("1" if d.get("block",{}).get("error",{}).get("code")==-32004 and "allowlist" in str(d.get("block",{}).get("error",{}).get("message","")) else "0")')" \
      "non-allowlisted tool ($BLOCK_TOOL) denied (-32004, message names the allowlist)"

sleep 5

# ── assert WIRE contract: the proxy's emitted JSON uses canonical keys ───────
info "E2E: proxy audit WIRE schema (Kafka) uses canonical field names"
# Kafka retains the events the real proxy just produced. Reading the topic and
# filtering by this run's tenant isolates them from any history — a bus read,
# never a DB poke. (No live consumer is used: --from-latest with a fresh group
# would start AFTER our messages and see nothing.)
docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" \
  --from-beginning --timeout-ms 10000 --max-messages 5000 > "$WORK/audit_full.jsonl" 2>/dev/null || true

EV="$(python3 - "$WORK/audit_full.jsonl" "$TENANT" <<'PY'
import json, sys
path, tenant = sys.argv[1], sys.argv[2]
events = []
for line in open(path, errors="replace"):
    line = line.strip()
    if not line.startswith("{"):
        continue
    try:
        e = json.loads(line)
    except ValueError:
        continue
    if e.get("tenant_id") == tenant:
        events.append(e)
json.dump(events, sys.stdout)
PY
)"
echo "$EV" > "$WORK/events_tenant.json"
NEV="$(printf '%s' "$EV" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))' 2>/dev/null || echo 0)"

ALLOWEV="$(printf '%s' "$EV" | jget 'print("1" if any(e.get("effective_action")=="allow" for e in d) else "0")')"
BLOCKEV="$(printf '%s' "$EV" | jget 'print("1" if any(e.get("effective_action")=="block" for e in d) else "0")')"
CANONICAL="$(printf '%s' "$EV" | jget 'print("1" if d and all(e.get("event_id") and e.get("intercepted_at") and e.get("tool_name") for e in d) else "0")')"
NOLEGACY="$(printf '%s' "$EV" | jget 'print("1" if d and all(("action" not in e and "reason" not in e and "timestamp" not in e and "session_risk_score" not in e) for e in d) else "0")')"
REASON="$(printf '%s' "$EV" | jget 'print("1" if any(e.get("effective_action")=="block" and e.get("reason_code") for e in d) else "0")')"
KEYS_OK="$(printf '%s' "$EV" | jget 'print("1" if d and all(("effective_action" in e and "reason_code" in e and "max_risk_score" in e and "intercepted_at" in e) for e in d) else "0")')"

check "$(printf '%s' "$NEV" | python3 -c 'import sys;print("1" if int(sys.argv[1])>0 else "0")' "$NEV")" \
      "proxy emitted $NEV audit event(s) for tenant $TENANT on Kafka"
check "$ALLOWEV" "wire has an effective_action=allow event"
check "$BLOCKEV" "wire has an effective_action=block event"
check "$KEYS_OK" "every wire event carries canonical keys (effective_action/reason_code/max_risk_score/intercepted_at)"
check "$CANONICAL" "every wire event has non-empty event_id + intercepted_at + tool_name"
check "$NOLEGACY" "legacy keys absent (action/reason/timestamp/session_risk_score)"
check "$REASON" "block event carries a non-empty reason_code"

TRACE_ID="$(printf '%s' "$EV" | jget 'print([e.get("trace_id") for e in d if e.get("effective_action")=="block" and e.get("trace_id")][0] if any(e.get("effective_action")=="block" and e.get("trace_id") for e in d) else "")')"
[ -n "$TRACE_ID" ] || TRACE_ID="$(printf '%s' "$EV" | jget 'print(d[0].get("trace_id","") if d else "")')"
info "trace_id for ingest assertions: ${TRACE_ID:-<none>}"

# ── assert INGESTED row via control ops API ──────────────────────────────────
info "E2E: control ingested the proxied audit row (ops API)"
BOM=""; OK=0
for _ in $(seq 1 40); do
  [ -n "$TRACE_ID" ] || break
  BOM=$(curl -s "$C/audit/decision-bom/$TRACE_ID")
  N=$(printf '%s' "$BOM" | jget 'print(len(d.get("data",{}).get("audit_events",[])))')
  [ "${N:-0}" -ge 1 ] 2>/dev/null && { OK=1; break; }
  sleep 1
done
check "$OK" "decision-bom($TRACE_ID) has >=1 ingested audit event"
check "$(printf '%s' "$BOM" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[]))>0 and all(a.get("effective_action") for a in d.get("data",{}).get("audit_events",[])) else "0")')" \
      "ingested audit_events have non-empty effective_action (not blanked by the field mismatch)"
check "$(printf '%s' "$BOM" | jget 'print("1" if any(a.get("effective_action")=="block" and a.get("reason_code") for a in d.get("data",{}).get("audit_events",[])) else "0")')" \
      "ingested block row carries its reason_code"
check "$(printf '%s' "$BOM" | jget 'print("1" if any(a.get("intercepted_at") for a in d.get("data",{}).get("audit_events",[])) else "0")')" \
      "ingested row has intercepted_at populated"
check "$(printf '%s' "$BOM" | jget 'print("1" if any(a.get("event_id") for a in d.get("data",{}).get("audit_events",[])) else "0")')" \
      "ingested row has event_id (the proxy now mints one)"

echo
echo "=========== mcp-proxy audit e2e summary ==========="
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
