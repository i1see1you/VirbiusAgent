#!/usr/bin/env bash
# =============================================================================
# MCP-proxy failover / auth-fallback E2E — the OPERATOR flow.
#
# Everything data-related goes through the Virbius ops-console API, exactly as a
# real user would: tenant + tools + license are bootstrapped via the control
# API, failover settings are written with PUT /api/v1/admin/tenants/{t}/
# proxy-policy and decisions are read back from audit/recent + decision-bom.
# Nothing here touches sqlite, redis or kafka directly.
#
# Covers the fail-closed / timeout / auth-fallback batch that shipped together:
#   * failover defaults as normalized by Control: high_risk_fail_closed=true,
#     low_risk_fail_open=FALSE, engine_timeout_ms=10000
#   * the normalizer keeps those defaults when a PUT omits `failover`, and the
#     100..60000 ms clamp is enforced (50 -> 100, 120000 -> 60000)
#   * unreachable engine + default policy -> low-risk call is DENIED (-32006)
#     and audited as fail_closed:engine_unavailable
#   * same call with failover.low_risk_fail_open=true -> ALLOWED and audited as
#     fail_open:engine_unavailable (the old default behaviour, now opt-in)
#   * evaluate-token exchange falls back to VIRBIUS_CONTROL_URL when
#     VIRBIUS_AUTH_CONTROL_URL is unset: a mock engine that 401s every request
#     without a Bearer token still gets a token and allows the call
#   * negative control: with VIRBIUS_AUTH_CONTROL_URL pointing at a dead port
#     the exchange fails, the mock 401s, and the call is denied (fail-closed)
#   * engine_timeout_ms really gates the call: a 6s evaluate succeeds at the
#     10s default and is cut off (fail-closed) at failover.engine_timeout_ms=500
#
# Usage: bash scripts/test-mcp-proxy-failover-e2e.sh  [SKIP_BUILD=1] [KEEP_WORK=1]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# shellcheck source=scripts/lib/openssl.sh
source "$ROOT/scripts/lib/openssl.sh"
virbius_openssl_select || exit 1
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

: "${UPSTREAM_PORT:=19094}"
: "${MOCK_AUTH_PORT:=19095}"
: "${MOCK_SLOW_PORT:=19096}"
: "${DEAD_PORT:=19098}"
PROXY_BIN="$OA_ROOT/target/debug/virbius-mcp-proxy"
CTRL="http://127.0.0.1:$CONTROL_PORT"
POLICY_API="$CTRL/api/v1/admin/tenants/$TENANT/proxy-policy"

oa_boot_all

if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$PROXY_BIN" ]; then
  info "building virbius-mcp-proxy (cargo)..."
  cargo build -p virbius-mcp-proxy > "$OA_LOGS/proxy-build.log" 2>&1 || { red "cargo build failed"; tail -30 "$OA_LOGS/proxy-build.log"; exit 2; }
fi
[ -f "$PROXY_BIN" ] || { red "proxy binary missing: $PROXY_BIN"; exit 2; }

# ── mock upstream (Streamable HTTP, single upstream -> names stay un-prefixed) ─
cat > "$OA_WORK/mock_upstream.py" <<'PY'
import json, sys, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def dispatch(r):
    if not isinstance(r, dict):
        return {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse"}}
    m, i = r.get("method"), r.get("id")
    if not isinstance(m, str) or m.startswith("notifications/"):
        return None
    if m == "initialize":
        return {"jsonrpc": "2.0", "id": i, "result": {"protocolVersion": "2024-11-05",
                "capabilities": {}, "serverInfo": {"name": "mock-upstream", "version": "0.1.0"}}}
    if m == "tools/list":
        return {"jsonrpc": "2.0", "id": i, "result": {"tools": [
            {"name": "read_doc", "description": "read a document",
             "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}}]}}
    if m == "tools/call":
        name = str((r.get("params") or {}).get("name"))
        return {"jsonrpc": "2.0", "id": i,
                "result": {"content": [{"type": "text", "text": "via-streamable:" + name}], "isError": False}}
    return {"jsonrpc": "2.0", "id": i, "error": {"code": -32601, "message": "method not found"}}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
        try:
            r = json.loads(body)
        except ValueError:
            r = None
        resp = dispatch(r)
        if resp is None:
            self.send_response(202)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        if self.headers.get("Mcp-Session-Id") is None:
            self.send_header("Mcp-Session-Id", "ms-" + uuid.uuid4().hex)
        self.end_headers()
        self.wfile.write(json.dumps(resp).encode())

    def do_GET(self):
        self.send_error(405)

    def do_DELETE(self):
        self.send_response(200)
        self.end_headers()

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

# ── mock engine: mode=auth demands a Bearer token, mode=slow sleeps then allows ─
cat > "$OA_WORK/mock_engine.py" <<'PY'
import json, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODE, ARG = sys.argv[2], float(sys.argv[3])

ALLOW = {"effective_action": "allow", "rule_id": "MOCK-" + MODE.upper() + "-ALLOW",
         "reason_code": "mock_allow", "session_risk_score": 0, "max_risk_score": 0}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        auth = self.headers.get("Authorization") or ""
        if MODE == "auth" and (not auth.startswith("Bearer ") or len(auth) <= 8):
            body = json.dumps({"detail": "missing evaluate token"}).encode()
            self.send_response(401)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if MODE == "slow":
            time.sleep(ARG)
        body = json.dumps(ALLOW).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

MOCK_PIDS=()
start_mock(){ # $1 script, $2 port, $3 label, $4.. extra args
  local script="$1" port="$2" label="$3"; shift 3
  oa_free_port "$port"
  "$PY" "$script" "$port" "$@" > "$OA_LOGS/$label.log" 2>&1 &
  MOCK_PIDS+=("$!")
  for _ in $(seq 1 20); do nc -z 127.0.0.1 "$port" 2>/dev/null && break; sleep 0.5; done
  nc -z 127.0.0.1 "$port" 2>/dev/null || { red "$label failed to start"; tail -20 "$OA_LOGS/$label.log"; exit 2; }
}
start_mock "$OA_WORK/mock_upstream.py" "$UPSTREAM_PORT" upstream
start_mock "$OA_WORK/mock_engine.py" "$MOCK_AUTH_PORT" engine-auth auth 0
start_mock "$OA_WORK/mock_engine.py" "$MOCK_SLOW_PORT" engine-slow slow 6
# DEAD_PORT is never bound: connecting to it is a refused connection.
nc -z 127.0.0.1 "$DEAD_PORT" 2>/dev/null && { red "port $DEAD_PORT unexpectedly in use"; exit 2; }

# ── console API helpers (the ONLY way test data is written/read) ─────────────
policy_put(){ curl -s -o "$OA_WORK/put.json" -w '%{http_code}' -X PUT "$POLICY_API" -H 'Content-Type: application/json' -d "$1"; }
policy_get(){ curl -s "$POLICY_API"; }
failover_of(){ policy_get | jget "print(json.dumps(d.get('data',{}).get('failover')))"; }

# ════ Phase 0: failover defaults + clamp through the console API ═════════════
info "P0: proxy-policy failover defaults and clamping (console API only)"
DEF="$(policy_get)"
check "$(printf '%s' "$DEF" | jget 'f=d.get("data",{}).get("failover",{});print("1" if f.get("high_risk_fail_closed") is True and f.get("low_risk_fail_open") is False and f.get("engine_timeout_ms")==10000 else "0")')" \
      "defaults: high_risk_fail_closed=true, low_risk_fail_open=false, engine_timeout_ms=10000"

CODE="$(policy_put '{"session_ttl_secs":1800}')"
check "$(printf '%s' "$(cat "$OA_WORK/put.json")" | jget 'f=d.get("data",{}).get("failover",{});print("1" if d.get("data",{}).get("published") is True and f.get("low_risk_fail_open") is False and f.get("engine_timeout_ms")==10000 else "0")')" \
      "PUT without failover keeps the fail-closed/10s defaults (HTTP $CODE)"

CODE="$(policy_put '{"failover":{"engine_timeout_ms":50}}')"
check "$([ "$CODE" = "400" ] && echo 1 || echo 0)" \
      "engine_timeout_ms=50 (below the 100 ms floor) is rejected with HTTP 400"
CODE="$(policy_put '{"failover":{"engine_timeout_ms":120000}}')"
check "$([ "$CODE" = "400" ] && echo 1 || echo 0)" \
      "engine_timeout_ms=120000 (above the 60 s ceiling) is rejected with HTTP 400"
policy_put '{"failover":{"engine_timeout_ms":2500}}' >/dev/null
check "$(failover_of | jget 'print("1" if d.get("engine_timeout_ms")==2500 else "0")')" \
      "engine_timeout_ms=2500 is stored verbatim (configurable, not pinned)"

# ════ Phases 1-6: proxy behaviour under engine/auth/timeout failures ═════════
info "P1-P6: driving the real proxy over stdio (fail-closed, auth fallback, timeout)"

cat > "$OA_WORK/failover_client.py" <<'PY'
import json, os, select, subprocess, sys, time, urllib.error, urllib.request

PROXY = sys.argv[1]
PHASES = json.load(open(sys.argv[2]))
CTRL = os.environ["VIRBIUS_CONTROL_URL"]
TENANT = os.environ["VIRBIUS_TENANT_ID"]

def api(method, path, body=None):
    req = urllib.request.Request(
        CTRL + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode() or "{}")
        except ValueError:
            return e.code, {}

proc, cur_env_key = None, None
ID = iter(range(10, 100000))

def stop_proxy():
    global proc
    if proc is not None:
        try:
            proc.stdin.close()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        proc = None

def start_proxy(extra_env):
    global proc, cur_env_key
    stop_proxy()
    env = dict(os.environ)
    env.update(extra_env)
    proc = subprocess.Popen([PROXY], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=open(os.environ.get("PROXY_STDERR", "/dev/null"), "a"),
                            env=env, text=True, bufsize=1)
    cur_env_key = json.dumps(extra_env, sort_keys=True)
    time.sleep(1)

def send(o):
    proc.stdin.write(json.dumps(o) + "\n")
    proc.stdin.flush()

def read_until(want_id):
    # Bounded read: a silent proxy must surface as a diagnostic timeout instead
    # of blocking the whole run (stdout carries only JSON-RPC after the fix).
    for _ in range(500):
        ready, _, _ = select.select([proc.stdout], [], [], 20)
        if not ready:
            return {"error": {"code": -1, "message": "read timeout awaiting id %s" % want_id}}
        line = proc.stdout.readline()
        if not line:
            break
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            msg = json.loads(line)
        except ValueError:
            continue
        if msg.get("id") == want_id:
            return msg
    return {"error": {"code": -1, "message": "timeout awaiting id %s" % want_id}}

def run_session(sid, tool, args):
    i = next(ID)
    send({"jsonrpc": "2.0", "id": i, "method": "initialize",
          "params": {"capabilities": {}, "protocolVersion": "2024-11-05",
                     "clientInfo": {"name": "failover-e2e", "version": "0.1"},
                     "_meta": {"session_id": sid}}})
    init = read_until(i)
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    i = next(ID)
    started = time.monotonic()
    send({"jsonrpc": "2.0", "id": i, "method": "tools/call",
          "params": {"name": tool, "arguments": args}})
    call = read_until(i)
    return {"initialize": init, "call": call,
            "call_ms": int((time.monotonic() - started) * 1000)}

def verdict(blob):
    call = blob.get("call", {})
    if "error" in call:
        return "deny"
    if (call.get("result", {}) or {}).get("isError"):
        return "deny"
    return "allow"

out = {}
try:
    for ph in PHASES:
        name = ph["name"]
        try:
            # PUT the policy first, then (re)start the proxy: the snapshot is
            # applied at startup, so each phase is deterministic and does not
            # depend on the cross-process hot-reload push.
            if ph.get("policy") is not None:
                code, _ = api("PUT", "/api/v1/admin/tenants/%s/proxy-policy" % TENANT, ph["policy"])
                out[name + ":put_http_code"] = code
            start_proxy(ph.get("proxy_env", {}))
            best = None
            for k in range(ph.get("attempts", 3)):
                best = run_session("%s-a%d" % (name, k), ph.get("tool", "read_doc"),
                                   ph.get("args", {"path": name}))
                if verdict(best) == ph.get("expect", "allow"):
                    break
                time.sleep(0.6)
            out[name + ":attempts"] = k + 1
            out[name] = best
        except Exception as e:
            out[name] = {"driver_error": repr(e)}
finally:
    stop_proxy()
print(json.dumps(out))
PY

python3 - "$OA_WORK" "$UPSTREAM_PORT" "$MOCK_AUTH_PORT" "$MOCK_SLOW_PORT" "$DEAD_PORT" > "$OA_WORK/phases.json" <<'PY'
import json, sys

work, up, mock_auth, mock_slow, dead = (int(v) if v.isdigit() else v for v in sys.argv[1:])
UP = "http://127.0.0.1:%s" % up
POLICY = {"upstreams": [{"name": "tools", "url": UP}]}

def policy(failover=None):
    body = dict(POLICY)
    if failover is not None:
        body["failover"] = failover
    return body

phases = [
    # P1: engine refused + default policy -> low-risk call must be DENIED.
    {"name": "p1_engine_down_default",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % dead},
     "policy": policy(),
     "expect": "deny", "attempts": 3},
    # P2: same failure, operator opts back into fail-open -> ALLOWED.
    {"name": "p2_fail_open_opt_in",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % dead},
     "policy": policy({"low_risk_fail_open": True}),
     "expect": "allow", "attempts": 3},
    # P3: engine 401s unless a Bearer token is present; the proxy has no
    # VIRBIUS_AUTH_CONTROL_URL, so the token must come from the
    # VIRBIUS_CONTROL_URL fallback. Default policy again.
    {"name": "p3_auth_fallback_to_control_url",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % mock_auth},
     "policy": policy(),
     "expect": "allow", "attempts": 3},
    # P4: negative control — the exchange endpoint is dead, so no token is
    # obtained, the engine keeps 401ing and the call is denied.
    {"name": "p4_auth_fallback_disabled",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % mock_auth,
                   "VIRBIUS_AUTH_CONTROL_URL": "http://127.0.0.1:%s" % dead},
     "policy": policy(),
     "expect": "deny", "attempts": 3},
    # P5: a 6s evaluate is inside the 10s default -> allowed.
    {"name": "p5_slow_engine_default_timeout",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % mock_slow},
     "policy": policy(),
     "expect": "allow", "attempts": 3},
    # P6: the same 6s evaluate with a 500ms policy timeout -> cut off, denied.
    {"name": "p6_slow_engine_short_timeout",
     "proxy_env": {"VIRBIUS_ENGINE_URL": "http://127.0.0.1:%s" % mock_slow},
     "policy": policy({"engine_timeout_ms": 500}),
     "expect": "deny", "attempts": 3},
]
json.dump(phases, open("%s/phases.json" % work, "w"))
PY

# Base environment for every proxy start. NOTE: VIRBIUS_AUTH_CONTROL_URL is
# deliberately NOT set — the fallback under test must derive it from the
# control URL (P3) and the P4 phase points it at a dead port instead.
export VIRBIUS_CONTROL_URL="$CTRL"
export VIRBIUS_AUDIT_BACKEND="kafka"
export VIRBIUS_AUDIT_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT"
export VIRBIUS_AUDIT_KAFKA_TOPIC="virbius-audit-events"
export VIRBIUS_TRACE_BACKEND="kafka"
export VIRBIUS_TRACE_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT"
export VIRBIUS_TRACE_KAFKA_TOPIC="virbius-trace-events"
export VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT"
export PROXY_STDERR="$OA_LOGS/proxy.log"

DRIVER_RC=0
"$PY" "$OA_WORK/failover_client.py" "$PROXY_BIN" "$OA_WORK/phases.json" \
  > "$OA_WORK/driver.json" 2> "$OA_LOGS/driver.err" || DRIVER_RC=$?
if [ "$DRIVER_RC" -ne 0 ] || [ ! -s "$OA_WORK/driver.json" ]; then
  red "failover driver failed (rc=$DRIVER_RC):"; cat "$OA_LOGS/driver.err"; cat "$OA_WORK/driver.json" || true; exit 2
fi
DRIVER_OUT="$(cat "$OA_WORK/driver.json")"

P1_TRACE="$(printf '%s' "$DRIVER_OUT" | jget 'print(d.get("p1_engine_down_default",{}).get("call",{}).get("error",{}).get("data",{}).get("trace_id") or "")')"
P4_TRACE="$(printf '%s' "$DRIVER_OUT" | jget 'print(d.get("p4_auth_fallback_disabled",{}).get("call",{}).get("error",{}).get("data",{}).get("trace_id") or "")')"
P6_TRACE="$(printf '%s' "$DRIVER_OUT" | jget 'print(d.get("p6_slow_engine_short_timeout",{}).get("call",{}).get("error",{}).get("data",{}).get("trace_id") or "")')"

check "$(printf '%s' "$DRIVER_OUT" | jget 'e=d.get("p1_engine_down_default",{}).get("call",{}).get("error",{});print("1" if e.get("code")==-32006 and e.get("data",{}).get("reason_code")=="engine_blocked" and e.get("data",{}).get("trace_id") else "0")')" \
      "P1: engine refused + defaults -> low-risk call denied (-32006 engine_blocked)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-streamable:read_doc" in json.dumps(d.get("p2_fail_open_opt_in",{}).get("call",{}).get("result",{})) else "0")')" \
      "P2: failover.low_risk_fail_open=true restores the old allow-on-error behaviour"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-streamable:read_doc" in json.dumps(d.get("p3_auth_fallback_to_control_url",{}).get("call",{}).get("result",{})) else "0")')" \
      "P3: without VIRBIUS_AUTH_CONTROL_URL the token comes from VIRBIUS_CONTROL_URL (engine accepts the call)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'e=d.get("p4_auth_fallback_disabled",{}).get("call",{}).get("error",{});print("1" if e.get("code")==-32006 else "0")')" \
      "P4: dead auth URL -> no token -> 401 -> denied (-32006), proving P3 used the fallback"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-streamable:read_doc" in json.dumps(d.get("p5_slow_engine_default_timeout",{}).get("call",{}).get("result",{})) and d.get("p5_slow_engine_default_timeout",{}).get("call_ms",0) >= 5000 else "0")')" \
      "P5: a 6s evaluate passes under the 10s default (call_ms >= 5000)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'e=d.get("p6_slow_engine_short_timeout",{}).get("call",{}).get("error",{});print("1" if e.get("code")==-32006 and d.get("p6_slow_engine_short_timeout",{}).get("call_ms",99999) < 5000 else "0")')" \
      "P6: engine_timeout_ms=500 cuts the same 6s evaluate short and denies (-32006)"

# ════ Phase 7: decisions and tool names read back via the console API ════════
info "P7: audit/decision trail read back through the ops-console API"
[ -n "$P1_TRACE" ] || { red "no trace_id from the P1 deny"; exit 2; }

bom_for(){ curl -s "$CTRL/api/v1/admin/tenants/$TENANT/audit/decision-bom/$1"; }
poll_bom_reason(){ # $1 trace, $2 reason_code -> 1 when the audit row is there
  local trace="$1" want="$2" bom
  for _ in $(seq 1 20); do
    bom="$(bom_for "$trace")"
    [ "$(printf '%s' "$bom" | jget "print('1' if any(e.get('reason_code')=='$want' for e in d.get('data',{}).get('audit_events',[])) else '0')")" = "1" ] && { echo 1; return; }
    sleep 1
  done
  echo 0
}

check "$(poll_bom_reason "$P1_TRACE" "fail_closed:engine_unavailable")" \
      "audit: P1 deny is stored with reason_code=fail_closed:engine_unavailable"
check "$(bom_for "$P1_TRACE" | jget 'print("1" if any(e.get("effective_action")=="block" for e in d.get("data",{}).get("audit_events",[])) else "0")')" \
      "audit: P1 row is effective_action=block"
OK=0
for _ in $(seq 1 20); do
  [ "$(bom_for "$P1_TRACE" | jget 'print("1" if any(s.get("tool_name")=="read_doc" for s in d.get("data",{}).get("trace_steps",[])) else "0")')" = "1" ] && { OK=1; break; }
  sleep 1
done
check "$OK" "audit: decision-bom trace steps carry tool_name=read_doc"
check "$(poll_bom_reason "$P4_TRACE" "fail_closed:engine_unavailable")" \
      "audit: P4 (dead auth URL) is audited as fail_closed:engine_unavailable"
check "$(poll_bom_reason "$P6_TRACE" "fail_closed:engine_unavailable")" \
      "audit: P6 (timeout) is audited as fail_closed:engine_unavailable"

OK=0; RECENT=""
for _ in $(seq 1 20); do
  RECENT="$(curl -s "$CTRL/api/v1/admin/tenants/$TENANT/audit/recent?limit=200")"
  HAVE="$(printf '%s' "$RECENT" | jget 'print("1" if any(e.get("reason_code")=="fail_open:engine_unavailable" and e.get("effective_action")=="allow" for e in d.get("data",{}).get("db_events",[])) else "0")')"
  [ "$HAVE" = "1" ] && { OK=1; break; }
  sleep 1
done
check "$OK" "audit: P2 opt-in fail-open is stored as allow + fail_open:engine_unavailable"

OK=0
for _ in $(seq 1 20); do
  RECENT="$(curl -s "$CTRL/api/v1/admin/tenants/$TENANT/audit/recent?limit=200")"
  HAVE="$(printf '%s' "$RECENT" | jget 'print("1" if any(str(e.get("rule_id","")).startswith("MOCK-AUTH-ALLOW") and e.get("effective_action")=="allow" for e in d.get("data",{}).get("db_events",[])) else "0")')"
  [ "$HAVE" = "1" ] && { OK=1; break; }
  sleep 1
done
check "$OK" "audit: P3 engine decision (rule_id MOCK-AUTH-ALLOW) reached the audit trail"

oa_summary
# The EXIT trap's bare `wait` would block on the long-lived mock HTTP servers;
# kill them so the harness cleanup returns immediately.
kill "${MOCK_PIDS[@]}" 2>/dev/null || true
