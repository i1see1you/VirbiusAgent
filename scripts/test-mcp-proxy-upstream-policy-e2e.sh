#!/usr/bin/env bash
# =============================================================================
# MCP-proxy upstream-transport E2E — the OPERATOR flow.
#
# Everything data-related goes through the Virbius ops-console API, exactly as
# a real user would: tenant + tools + license are bootstrapped via the control
# API, upstream entries are configured via PUT /api/v1/admin/tenants/{t}/
# proxy-policy (never by touching sqlite or redis), and audit results are read
# back via the audit/decision-bom + audit/recent console endpoints.
#
# Covers the R1 upstream-transport feature end to end:
#   * console-side validation: bad transport / headers-in-policy / stdio-vs-http
#     field mixes / out-of-range timeout are rejected with HTTP 400 and are NOT
#     persisted
#   * policy-pushed Streamable HTTP upstream (transport omitted -> auto probe)
#   * policy-pushed legacy SSE upstream (transport=sse, no probing)
#   * hot reload: PUT a new policy, the NEXT session on the SAME proxy process
#     uses the new upstreams (no restart)
#   * deployment gates: policy-pushed stdio entries are dropped while
#     VIRBIUS_ALLOW_DYNAMIC_STDIO_UPSTREAMS is off, survive once the gate is on
#     and the command is allowlisted (proxy restarts driven by the driver),
#     and are dropped again when the command is not allowlisted or the
#     transport is gated off by VIRBIUS_ENABLED_UPSTREAM_TRANSPORTS
#   * stdio upstream actually SPAWNS the configured server and serves a call
#   * audit trail readable via console API (decision-bom + audit/recent)
#
# Usage: bash scripts/test-mcp-proxy-upstream-policy-e2e.sh  [SKIP_BUILD=1] [KEEP_WORK=1]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# shellcheck source=scripts/lib/openssl.sh
source "$ROOT/scripts/lib/openssl.sh"
virbius_openssl_select || exit 1
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

: "${UPSTREAM_PORT:=19093}"
PROXY_BIN="$OA_ROOT/target/debug/virbius-mcp-proxy"
FIXTURE="$ROOT/virbius-mcp-proxy/tests/fixtures/echo_mcp_stdio.py"
CTRL="http://127.0.0.1:$CONTROL_PORT"
POLICY_API="$CTRL/api/v1/admin/tenants/$TENANT/proxy-policy"

oa_boot_all

[ -f "$FIXTURE" ] || { red "stdio fixture missing: $FIXTURE"; exit 2; }
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$PROXY_BIN" ]; then
  info "building virbius-mcp-proxy (cargo)..."
  cargo build -p virbius-mcp-proxy > "$OA_LOGS/proxy-build.log" 2>&1 || { red "cargo build failed"; tail -30 "$OA_LOGS/proxy-build.log"; exit 2; }
fi
[ -f "$PROXY_BIN" ] || { red "proxy binary missing: $PROXY_BIN"; exit 2; }

# ── dual mock upstream: Streamable HTTP (2025-06-18) AND legacy HTTP+SSE ─────
# The same URL serves /mcp (streamable) and /sse + /messages (legacy). Tool
# results carry a transport marker ("via-streamable:read_doc" vs "via-sse:
# read_doc") so assertions can tell WHICH path the proxy actually used.
cat > "$OA_WORK/mock_upstream.py" <<'PY'
import json, queue, sys, threading, urllib.parse, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SSE_SESSIONS, LOCK = {}, threading.Lock()

def sse_q(sid):
    with LOCK:
        it = SSE_SESSIONS.get(sid)
        if it is None:
            it = queue.Queue()
            SSE_SESSIONS[sid] = it
        return it

def dispatch(r, mark):
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
             "inputSchema": {"type": "object", "properties": {"path": {"type": "string"}}}},
            {"name": "danger_exec", "description": "execute a shell command",
             "inputSchema": {"type": "object", "properties": {"cmd": {"type": "string"}}}},
        ]}}
    if m == "tools/call":
        name = str((r.get("params") or {}).get("name"))
        return {"jsonrpc": "2.0", "id": i,
                "result": {"content": [{"type": "text", "text": mark + ":" + name}], "isError": False}}
    return {"jsonrpc": "2.0", "id": i, "error": {"code": -32601, "message": "method not found"}}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
        try:
            r = json.loads(body)
        except ValueError:
            r = None
        if path == "/mcp":  # Streamable HTTP (2025-06-18)
            resp = dispatch(r, "via-streamable")
            if resp is None:
                self.send_response(202)
                self.end_headers()
                self.wfile.write(b"")
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            if self.headers.get("Mcp-Session-Id") is None:  # initialize / probe
                self.send_header("Mcp-Session-Id", "ms-" + uuid.uuid4().hex)
            self.end_headers()
            self.wfile.write(json.dumps(resp).encode())
            return
        if path == "/messages":  # legacy HTTP+SSE
            sid = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("session_id", ["default"])[0]
            resp = dispatch(r, "via-sse")
            if resp is not None:
                sse_q(sid).put(json.dumps(resp))
            self.send_response(202)
            self.end_headers()
            self.wfile.write(b"")
            return
        self.send_error(404)

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path.rstrip("/")
        if path == "/mcp":  # server-initiated stream disabled -> 405
            self.send_error(405)
            return
        if path == "/sse":
            sid = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("session_id", [""])[0] \
                or "s-" + uuid.uuid4().hex
            qq = sse_q(sid)
            self.send_response(200)
            for k, v in (("Content-Type", "text/event-stream"), ("Cache-Control", "no-cache"),
                         ("Connection", "keep-alive")):
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(("event: endpoint\ndata: /messages/?session_id="
                              + urllib.parse.quote(sid) + "\n\n").encode())
            self.wfile.flush()
            while True:
                try:
                    msg = qq.get(timeout=30)
                except queue.Empty:
                    self.wfile.write(b": ping\n\n")
                    self.wfile.flush()
                    continue
                self.wfile.write(("event: message\ndata: %s\n\n" % msg).encode())
                self.wfile.flush()

    def do_DELETE(self):
        if urllib.parse.urlparse(self.path).path.rstrip("/") == "/mcp":
            self.send_response(200)
        else:
            self.send_error(404)
        self.end_headers()

ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY

oa_free_port "$UPSTREAM_PORT"
info "starting dual mock upstream (streamable + legacy SSE) on :$UPSTREAM_PORT..."
"$PY" "$OA_WORK/mock_upstream.py" "$UPSTREAM_PORT" > "$OA_LOGS/upstream.log" 2>&1 &
# read indirectly via ${!v} in oa_cleanup
# shellcheck disable=SC2034
OA_UPSTREAM_PID=$!
for _ in $(seq 1 20); do nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null && break; sleep 0.5; done
nc -z 127.0.0.1 "$UPSTREAM_PORT" 2>/dev/null || { red "mock upstream failed to start"; tail -20 "$OA_LOGS/upstream.log"; exit 2; }

# ── console API helpers (the ONLY way test data is written/read) ─────────────
policy_put(){ curl -s -o "$OA_WORK/put.json" -w '%{http_code}' -X PUT "$POLICY_API" -H 'Content-Type: application/json' -d "$1"; }
policy_put_raw(){ curl -s -X PUT "$POLICY_API" -H 'Content-Type: application/json' -d "$1"; }
policy_get(){ curl -s "$POLICY_API"; }

# ════ Phase 0: console-side validation (HTTP 400s, nothing persisted) ════════
info "P0: proxy-policy console API validation"
DEF="$(policy_get)"
check "$(printf '%s' "$DEF" | jget 'print("1" if d.get("data",{}).get("upstreams")==[] and d.get("data",{}).get("session_ttl_secs")==1800 and d.get("data",{}).get("fallback_policy")=="minimum_privilege" and d.get("data",{}).get("fast_path",{}).get("enabled") is True else "0")')" \
      "GET proxy-policy returns normalized defaults (empty upstreams, ttl=1800, min-privilege fallback)"

VALID='{"upstreams":[{"name":"check","url":"http://127.0.0.1:1"}]}'
CODE="$(policy_put "$VALID")"
check "$([ "$CODE" = "200" ] && echo 1 || echo 0)" \
      "PUT valid http upstream accepted (HTTP 200)"
check "$(printf '%s' "$(cat "$OA_WORK/put.json")" | jget 'print("1" if d.get("data",{}).get("published") is True else "0")')" \
      "PUT response reports published=true"
ROW="$(policy_get)"
check "$(printf '%s' "$ROW" | jget 'print("1" if d.get("data",{}).get("upstreams",[{}])[0].get("transport")=="auto" and d.get("data",{}).get("upstreams",[{}])[0].get("http_path")=="/mcp" and d.get("data",{}).get("upstreams",[{}])[0].get("sse_path")=="/sse" and d.get("data",{}).get("upstreams",[{}])[0].get("timeout_ms")==30000 else "0")')" \
      "GET returns the stored upstream normalized: transport=auto (omitted), /mcp + /sse defaults, timeout_ms=30000"

expect_reject(){ # $1 body, $2 message fragment
  local code msg
  code="$(policy_put "$1")"
  msg="$(printf '%s' "$(cat "$OA_WORK/put.json")" | jget 'print(d.get("message",""))')"
  if [ "$code" = "400" ] && printf '%s' "$msg" | grep -q "$2"; then
    check 1 "PUT rejected: $2 (HTTP 400)"
  else
    check 0 "PUT rejected: $2 (got code=$code msg=$msg)"
  fi
}
expect_reject '{"upstreams":[{"name":"h","url":"http://h:1","headers":{"Authorization":"Bearer x"}}]}' "static-TOML"
expect_reject '{"upstreams":[{"name":"s","transport":"stdio"}]}' "needs a command"
expect_reject '{"upstreams":[{"name":"m","url":"http://h:1","command":"npx"}]}' "must not set command"
expect_reject '{"upstreams":[{"name":"s","transport":"stdio","command":"npx","url":"http://h:1"}]}' "must not set url"
expect_reject '{"upstreams":[{"name":"b","transport":"grpc","url":"http://h:1"}]}' "transport must be one of"
expect_reject '{"upstreams":[{"name":"t","url":"http://h:1","timeout_ms":100}]}' "between"
expect_reject '{"fallback_policy":"yolo"}' "fallback_policy"
check "$(printf '%s' "$(policy_get)" | jget 'print("1" if d.get("data",{}).get("upstreams",[{}])[0].get("name")=="check" else "0")')" \
      "rejected PUTs did NOT persist (last valid body still stored)"

# ════ Phase 1-6: policy-driven upstreams through ONE driver process ══════════
info "P1-P6: driving the real proxy over stdio (policy hot reload + gate restarts)"

cat > "$OA_WORK/policy_client.py" <<'PY'
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

MIRROR = open(os.environ.get("PROXY_STDOUT_MIRROR", "/dev/null"), "a")

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
    # of blocking the whole run (stdout carries both logs and JSON-RPC).
    for _ in range(500):
        ready, _, _ = select.select([proc.stdout], [], [], 10)
        if not ready:
            return {"error": {"code": -1, "message": "read timeout awaiting id %s" % want_id}}
        line = proc.stdout.readline()
        if not line:
            break
        MIRROR.write(line)
        MIRROR.flush()
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

def run_session(sid, actions):
    res = {}
    i = next(ID)
    send({"jsonrpc": "2.0", "id": i, "method": "initialize",
          "params": {"capabilities": {}, "protocolVersion": "2024-11-05",
                     "clientInfo": {"name": "policy-e2e", "version": "0.1"},
                     "_meta": {"session_id": sid}}})
    res["initialize"] = read_until(i)
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    for a in actions:
        i = next(ID)
        if a[0] == "list":
            send({"jsonrpc": "2.0", "id": i, "method": "tools/list"})
            res["tools"] = read_until(i)
        elif a[0] == "call":
            send({"jsonrpc": "2.0", "id": i, "method": "tools/call",
                  "params": {"name": a[1], "arguments": a[2]}})
            res["call:" + a[1]] = read_until(i)
    return res

def satisfied(expect, blob):
    for frag in expect.get("contains", []):
        if frag not in blob:
            return False
    for frag in expect.get("not_contains", []):
        if frag in blob:
            return False
    return True

out = {}
try:
    for ph in PHASES:
        name = ph["name"]
        try:
            if proc is None or json.dumps(ph.get("proxy_env", {}), sort_keys=True) != cur_env_key:
                start_proxy(ph.get("proxy_env", {}))
            if ph.get("policy") is not None:
                code, body = api("PUT", "/api/v1/admin/tenants/%s/proxy-policy" % TENANT, ph["policy"])
                out[name + ":put_http_code"] = code
            attempts = ph.get("attempts", 12)
            best = None
            for k in range(attempts):
                best = run_session("%s-a%d" % (name, k), ph.get("actions", []))
                if satisfied(ph.get("expect", {}), json.dumps(best)):
                    break
                time.sleep(1.5)
            out[name + ":attempts"] = k + 1
            out[name] = best
        except Exception as e:
            out[name] = {"driver_error": repr(e)}
finally:
    stop_proxy()
print(json.dumps(out))
PY

# Build the phase plan. All policy writes below happen through the console API
# from inside the driver (PUT proxy-policy) — never direct sqlite/redis access.
python3 - "$OA_WORK" "$UPSTREAM_PORT" "$FIXTURE" > "$OA_WORK/phases.json" <<'PY'
import json, os, sys

work, port, fixture = sys.argv[1], sys.argv[2], sys.argv[3]
UP = "http://127.0.0.1:%s" % port

call = lambda tool, args=None: ["call", tool, args or {}]
phases = [
    # P1: transport omitted -> auto probe -> Streamable HTTP wins
    {"name": "p1_streamable_auto",
     "policy": {"upstreams": [{"name": "mcp-remote", "url": UP}]},
     "actions": [["list"], call("read_doc", {"path": "p1"}), call("danger_exec", {"cmd": "rm -rf /"})],
     "expect": {"contains": ["via-streamable:read_doc"]},
     "attempts": 15},
    # P2: explicit legacy SSE on the SAME dual server -> no probing
    {"name": "p2_force_sse",
     "policy": {"upstreams": [{"name": "legacy", "url": UP, "transport": "sse"}]},
     "actions": [["list"], call("read_doc", {"path": "p2"})],
     "expect": {"contains": ["via-sse:read_doc"]},
     "attempts": 12},
    # P3: dynamic stdio entry in the policy -> dropped by default gates;
    # the remaining single sse entry serves read_doc WITHOUT prefixing.
    {"name": "p3_stdio_dropped",
     "policy": {"upstreams": [
         {"name": "docs", "url": UP, "transport": "sse"},
         {"name": "local-fs", "transport": "stdio", "command": "python3",
          "args": [fixture], "env": {"FS_ROOT": "/data"}}]},
     "actions": [["list"], call("read_doc", {"path": "p3"})],
     "expect": {"contains": ["via-sse:read_doc"], "not_contains": ["local-fs__"]},
     "attempts": 12},
    # P4: gates ON (deployment env) + command allowlisted -> the stdio entry
    # from the SAME policy goes live: multi-upstream merge prefixes the
    # conflicting read_doc, and the stdio call proves the child was spawned.
    {"name": "p4_stdio_enabled",
     "proxy_env": {"VIRBIUS_ALLOW_DYNAMIC_STDIO_UPSTREAMS": "1",
                   "VIRBIUS_ALLOWED_STDIO_COMMANDS": "python3"},
     "policy": {"upstreams": [
         {"name": "docs", "url": UP, "transport": "sse"},
         {"name": "local-fs", "transport": "stdio", "command": "python3",
          "args": [fixture], "env": {"FS_ROOT": "/data"}}]},
     "actions": [["list"],
                 call("docs__read_doc", {"path": "p4"}),
                 call("local-fs__read_doc", {"path": "policy-stdio"}),
                 call("danger_exec", {"cmd": "x"})],
     "expect": {"contains": ["docs__read_doc", "local-fs__read_doc",
                             "via-sse:read_doc", "policy-stdio"]},
     "attempts": 15},
    # P5: command NOT allowlisted -> stdio dropped again, sse keeps serving.
    {"name": "p5_stdio_allowlist_negative",
     "proxy_env": {"VIRBIUS_ALLOW_DYNAMIC_STDIO_UPSTREAMS": "1",
                   "VIRBIUS_ALLOWED_STDIO_COMMANDS": "node"},
     "actions": [["list"], call("read_doc", {"path": "p5"})],
     "expect": {"contains": ["via-sse:read_doc"], "not_contains": ["local-fs__"]},
     "attempts": 12},
    # P6: transport gate OFF for auto -> the auto entry is dropped -> the proxy
    # boots standalone (its own serverInfo, empty tool list).
    {"name": "p6_transport_gate",
     "proxy_env": {"VIRBIUS_ENABLED_UPSTREAM_TRANSPORTS": "sse"},
     "policy": {"upstreams": [{"name": "mcp-remote", "url": UP}]},
     "actions": [["list"]],
     "expect": {"contains": ["virbius-mcp-proxy"]},
     "attempts": 10},
]
json.dump(phases, open(os.path.join(work, "phases.json"), "w"))
PY

# Base environment for every proxy start (the driver restarts the child when a
# phase changes gate env; all other env stays identical).
export VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT"
export VIRBIUS_CONTROL_URL="$CTRL"
export VIRBIUS_AUDIT_BACKEND="kafka"
export VIRBIUS_AUDIT_KAFKA_BROKERS="$KAFKA_HOST:$KAFKA_PORT"
export VIRBIUS_AUDIT_KAFKA_TOPIC="virbius-audit-events"
export VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT"
export PROXY_STDERR="$OA_LOGS/proxy.log"
export PROXY_STDOUT_MIRROR="$OA_LOGS/proxy-stdout-mirror.log"

DRIVER_RC=0
"$PY" "$OA_WORK/policy_client.py" "$PROXY_BIN" "$OA_WORK/phases.json" \
  > "$OA_WORK/driver.json" 2> "$OA_LOGS/driver.err" || DRIVER_RC=$?
if [ "$DRIVER_RC" -ne 0 ] || [ ! -s "$OA_WORK/driver.json" ]; then
  red "policy driver failed (rc=$DRIVER_RC):"; cat "$OA_LOGS/driver.err"; cat "$OA_WORK/driver.json" || true; exit 2
fi
DRIVER_OUT="$(cat "$OA_WORK/driver.json")"
printf '%s\n' "$DRIVER_OUT" > "$OA_WORK/driver.json"

P1_DENY_TRACE="$(printf '%s' "$DRIVER_OUT" | jget 'print(d.get("p1_streamable_auto",{}).get("call:danger_exec",{}).get("error",{}).get("data",{}).get("trace_id") or "")')"

check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if d.get("p1_streamable_auto",{}).get("initialize",{}).get("result",{}).get("serverInfo",{}).get("name")=="mock-upstream" else "0")')" \
      "P1 auto: policy-pushed upstream answered initialize (single-upstream passthrough)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-streamable:read_doc" in d.get("p1_streamable_auto",{}).get("call:read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") else "0")')" \
      "P1 auto: transport omitted -> auto probe used Streamable HTTP (marker content)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if d.get("p1_streamable_auto",{}).get("call:danger_exec",{}).get("error",{}).get("code")==-32004 and "allowlist" in str(d.get("p1_streamable_auto",{}).get("call:danger_exec",{}).get("error",{}).get("message","")) else "0")')" \
      "P1: non-allowlisted tool denied (-32004) with trace_id in error data"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-sse:read_doc" in d.get("p2_force_sse",{}).get("call:read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") else "0")')" \
      "P2: transport=sse forced legacy HTTP+SSE on the same URL (no streamable probing)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-sse:read_doc" in d.get("p3_stdio_dropped",{}).get("call:read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") else "0")')" \
      "P3: with dynamic stdio disabled, the policy's sse entry still serves (hot reload, same proxy)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "local-fs__" not in json.dumps(d.get("p3_stdio_dropped",{})) else "0")')" \
      "P3: policy-pushed stdio entry dropped by default gates (no local-fs__ tool, no child spawn)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if d.get("p4_stdio_enabled",{}).get("tools",{}).get("result",{}).get("tools",[]) and set(t.get("name") for t in d.get("p4_stdio_enabled",{}).get("tools",{}).get("result",{}).get("tools",[]))=={"docs__read_doc","local-fs__read_doc"} else "0")')" \
      "P4: gates open -> stdio entry live; conflicting read_doc prefixed per upstream"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if all(t.get("x-virbius-upstream") in ("docs","local-fs") for t in d.get("p4_stdio_enabled",{}).get("tools",{}).get("result",{}).get("tools",[])) else "0")')" \
      "P4: each merged tool carries its x-virbius-upstream origin annotation"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "policy-stdio" in d.get("p4_stdio_enabled",{}).get("call:local-fs__read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") else "0")')" \
      "P4: stdio upstream actually spawned the fixture server and served the call"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-sse:read_doc" in d.get("p4_stdio_enabled",{}).get("call:docs__read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") else "0")')" \
      "P4: both upstreams served in one session (stdio child + legacy SSE)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if d.get("p4_stdio_enabled",{}).get("call:danger_exec",{}).get("error",{}).get("code")==-32004 and bool(d.get("p4_stdio_enabled",{}).get("call:danger_exec",{}).get("error",{}).get("data",{}).get("trace_id")) else "0")')" \
      "P4: in multi-upstream mode an unlisted tool is still policy-denied (-32004, audited)"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if "via-sse:read_doc" in d.get("p5_stdio_allowlist_negative",{}).get("call:read_doc",{}).get("result",{}).get("content",[{}])[0].get("text","") and "local-fs__" not in json.dumps(d.get("p5_stdio_allowlist_negative",{})) else "0")')" \
      "P5: stdio command not in VIRBIUS_ALLOWED_STDIO_COMMANDS -> entry dropped again"
check "$(printf '%s' "$DRIVER_OUT" | jget 'print("1" if d.get("p6_transport_gate",{}).get("initialize",{}).get("result",{}).get("serverInfo",{}).get("name")=="virbius-mcp-proxy" and d.get("p6_transport_gate",{}).get("tools",{}).get("result",{}).get("tools",[])==[] else "0")')" \
      "P6: VIRBIUS_ENABLED_UPSTREAM_TRANSPORTS=sse gates out the auto entry -> standalone proxy"

# ════ Phase 7: audit trail via console API ═══════════════════════════════════
info "P7: audit trail read back through the ops-console API"
[ -n "$P1_DENY_TRACE" ] || { red "no trace_id from the P1 deny"; exit 2; }

OK=0; BOM=""
for _ in $(seq 1 40); do
  BOM="$(curl -s "$CTRL/api/v1/admin/tenants/$TENANT/audit/decision-bom/$P1_DENY_TRACE")"
  N="$(printf '%s' "$BOM" | jget 'print(len(d.get("data",{}).get("audit_events",[])))')"
  [ "${N:-0}" -ge 1 ] 2>/dev/null && { OK=1; break; }
  sleep 1
done
check "$OK" "audit: decision-bom via API has events for the denied trace"
check "$(printf '%s' "$BOM" | grep -q 'danger_exec' && echo 1 || echo 0)" \
      "audit: decision-bom events reference the denied tool (danger_exec)"

OK=0; RECENT=""
for _ in $(seq 1 40); do
  RECENT="$(curl -s "$CTRL/api/v1/admin/tenants/$TENANT/audit/recent?limit=100")"
  HAVE="$(printf '%s' "$RECENT" | jget 'print("1" if any(e.get("effective_action")=="block" for e in d.get("data",{}).get("db_events",[])) and any(e.get("effective_action")=="allow" for e in d.get("data",{}).get("db_events",[])) else "0")')"
  [ "$HAVE" = "1" ] && { OK=1; break; }
  sleep 1
done
check "$OK" "audit: audit/recent via API contains both allow and block decisions"
check "$(printf '%s' "$RECENT" | jget 'print("1" if d.get("data",{}).get("db_events") and all("trace_id" in e and "intercepted_at" in e and "effective_action" in e and "action" not in e for e in d.get("data",{}).get("db_events",[])) else "0")')" \
      "audit: stored rows carry canonical keys (trace_id/intercepted_at/effective_action, no legacy action)"

oa_summary
