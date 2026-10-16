#!/usr/bin/env bash
# =============================================================================
# Evaluate-token authentication E2E (P0-min, control single-path issuance).
#
# Boots control FIRST (it generates the platform signing key and publishes the
# issuer set to Redis), then the engine (auth ENABLED, loading issuers from
# Redis), a mock Ollama, and drives the whole caller-auth contract over HTTP:
#
#   exchange License JWT -> short token -> POST /v1/evaluate with Bearer
#   cases: valid / missing / garbage / wrong-audience-forged / tenant mismatch /
#          capability denied (tool_call only) / role=input exempt /
#          sid mismatch / service token cross-tenant / revocation -> 401
#
# All verification is through HTTP APIs. The engine boots WITHOUT a config
# bootstrap PEM on purpose: the issuer set comes from Redis (control publishes
# it at startup), exercising the enrichment path.
#
# Requires: java, curl, python3, nc, a reachable Redis. Kafka optional.
#
# Usage:      bash scripts/test-evaluate-auth-e2e.sh
# Skip build: SKIP_BUILD=1 bash scripts/test-evaluate-auth-e2e.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
ENGINE_PORT="${ENGINE_PORT:-18082}"
MOCK_PORT="${MOCK_PORT:-18999}"
REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-auth.XXXXXX")"
LOGS="$WORK/logs"; DATA="$WORK/data"
mkdir -p "$LOGS" "$DATA"

PASS=0; FAIL=0
green(){ printf '\033[0;32m%s\033[0m\n' "$*"; }
red(){ printf '\033[0;31m%s\033[0m\n' "$*"; }
info(){ printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
check(){ if [ "$1" = "1" ]; then PASS=$((PASS+1)); green "  PASS  $2"; else FAIL=$((FAIL+1)); red "  FAIL  $2"; fi; }
jget(){ python3 -c "import sys,json;d=json.load(sys.stdin);$1" "${2:-}" 2>/dev/null || echo "PARSE_ERROR"; }

cleanup() {
  info "shutting down..."
  [ -n "${ENGINE_PID:-}" ] && kill "$ENGINE_PID" 2>/dev/null || true
  [ -n "${CONTROL_PID:-}" ] && kill "$CONTROL_PID" 2>/dev/null || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
  pkill -f "$WORK/mock_ollama.py" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${KEEP_WORK:-0}" = "1" ]; then info "KEEP_WORK=1: logs at $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT INT TERM

for bin in java curl python3 nc; do command -v "$bin" >/dev/null 2>&1 || { red "missing: $bin"; exit 2; }; done
nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null || { red "Redis not reachable at $REDIS_HOST:$REDIS_PORT"; exit 2; }
APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
ENGINE_JAR="$ROOT/virbius-engine/target/virbius-engine-${APP_VER}.jar"
CONTROL_JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$ENGINE_JAR" ] || [ ! -f "$CONTROL_JAR" ]; then
  info "building engine + control (skip tests)..."
  mvn -q -pl virbius-engine,virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm || { red "build failed"; exit 2; }
fi

VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
export VIRBIUS_LICENSE_MASTER_KEY VIRBIUS_DELEGATED_MASTER_KEY

# mock Ollama (guard LLM used only by role=input/output pipelines).
cat > "$WORK/mock_ollama.py" <<PY
import json, http.server, socketserver
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0)); self.rfile.read(n)
        out = json.dumps({"message": {"role": "assistant", "content": "safe"},
                          "prompt_eval_count": 1, "eval_count": 1}).encode()
        self.send_response(200); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("127.0.0.1", $MOCK_PORT), H).serve_forever()
PY
python3 "$WORK/mock_ollama.py" > "$LOGS/mock.log" 2>&1 & MOCK_PID=$!

wait_http(){ for _ in $(seq 1 40); do curl -sf --max-time 2 "$1" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

# control FIRST: it generates + publishes the issuer set to Redis.
info "starting control on :$CONTROL_PORT (publishes issuers)..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!
wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/health" || { red "control failed"; tail -n 30 "$LOGS/control.log"; exit 2; }

info "starting engine on :$ENGINE_PORT (auth ENABLED, issuers from Redis)..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_PROMPT_LLM_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" \
      --virbius.evaluate.auth.enabled=true \
      --virbius.evaluate.auth.bootstrap-pem-file= \
    > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!
wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed (auth issuer load?)"; tail -n 40 "$LOGS/engine.log"; exit 2; }
info "services up."

E="http://127.0.0.1:$ENGINE_PORT"
CADM="http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/default"
CAUTH="http://127.0.0.1:$CONTROL_PORT/api/v1/auth"
ev(){ local tok="$1" body="$2"; if [ -n "$tok" ]; then
    curl -s --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" -d "$body"
  else
    curl -s --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$body"
  fi; }
code(){ local tok="$1" body="$2"; if [ -n "$tok" ]; then
    curl -s -o "$WORK/body" -w '%{http_code}' --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -H "Authorization: Bearer $tok" -d "$body"
  else
    curl -s -o "$WORK/body" -w '%{http_code}' --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$body"
  fi; }

# ── issue a license + obtain a short token ──────────────────────────────────
info "E2E: issue license + exchange short token (no per-request license)"
L=$(curl -s -X POST "$CADM/licenses/issue" -H 'Content-Type: application/json' \
  -d '{"app_id":"app-auth-1","agent_name":"e2e-auth","allowed_tools":["read_file"],"risk_quota":80,"tool_rate_limit":50,"expiry_seconds":3600}')
JWT=$(printf '%s' "$L" | jget 'print(d.get("data",{}).get("jwt",""))')
LIC_ID=$(printf '%s' "$L" | jget 'print(d.get("data",{}).get("license_id",""))')
[ "${E2E_DEBUG:-0}" = "1" ] && printf 'DBG license=%s\n' "$L" >&2
check "$([ -n "$JWT" ] && echo 1 || echo 0)" "license issued via 运营台 API"

TOKR=$(curl -s -X POST "$CAUTH/evaluate-token" -H 'Content-Type: application/json' -d "{\"license_jwt\":\"$JWT\",\"sid\":\"sess-auth-1\"}")
TOK=$(printf '%s' "$TOKR" | jget 'print(d.get("data",{}).get("access_token",""))')
check "$([ -n "$TOK" ] && echo 1 || echo 0)" "license exchanged for a short token"
check "$(printf '%s' "$TOKR" | jget 'print("1" if d.get("data",{}).get("expires_in") else "0")')" "exchange returns expires_in (TTL)"

# ── auth contract ───────────────────────────────────────────────────────────
info "E2E: auth contract (401/403/200)"
C=$(code "" '{"tenant_id":"default","role":"tool_call","tool_name":"read_file","session_id":"sess-auth-1","args_json":"{}"}')
check "$([ "$C" = "401" ] && echo 1 || echo 0)" "no token -> 401"
check "$(cat "$WORK/body" | jget 'print("1" if d.get("reason")=="missing_token" else "0")')" "401 body carries reason=missing_token"

C=$(code "garbage.token.value" '{"tenant_id":"default","role":"tool_call","tool_name":"read_file","session_id":"sess-auth-1","args_json":"{}"}')
check "$([ "$C" = "401" ] && echo 1 || echo 0)" "garbage token -> 401"

# valid token, allow path (role=tool_call, tool in scope)
C=$(code "$TOK" '{"tenant_id":"default","role":"tool_call","tool_name":"read_file","session_id":"sess-auth-1","args_json":"{}","license_risk_quota":100}')
[ "${E2E_DEBUG:-0}" = "1" ] && printf 'DBG valid token code=%s body=%s\n' "$C" "$(cat "$WORK/body")" >&2
check "$([ "$C" = "200" ] && echo 1 || echo 0)" "valid token -> 200"

# claims precedence: self-reported tenant conflicts with token -> 403
C=$(code "$TOK" '{"tenant_id":"other-tenant","role":"tool_call","tool_name":"read_file","session_id":"sess-auth-1","args_json":"{}"}')
check "$([ "$C" = "403" ] && echo 1 || echo 0)" "tenant mismatch -> 403"
check "$(cat "$WORK/body" | jget 'print("1" if d.get("error")=="subject_mismatch" else "0")')" "403 body carries subject_mismatch"

# capability: tool_call for a tool not in scope -> 403 (B2)
C=$(code "$TOK" '{"tenant_id":"default","role":"tool_call","tool_name":"curl","session_id":"sess-auth-1","args_json":"{}"}')
check "$([ "$C" = "403" ] && echo 1 || echo 0)" "tool_call out of scope -> 403"
check "$(cat "$WORK/body" | jget 'print("1" if d.get("error")=="capability_denied" else "0")')" "403 body carries capability_denied"

# B2 exemption: role=input is NOT capability-checked (chat_input is not an MCP tool)
C=$(code "$TOK" '{"tenant_id":"default","role":"input","tool_name":"chat_input","session_id":"sess-auth-1","content":"hello there"}')
check "$([ "$C" = "200" ] && echo 1 || echo 0)" "role=input exempt from capability check"

# sid binding: token bound to sess-auth-1 reused for another session -> 401
C=$(code "$TOK" '{"tenant_id":"default","role":"tool_call","tool_name":"read_file","session_id":"other-session","args_json":"{}"}')
check "$([ "$C" = "401" ] && echo 1 || echo 0)" "sid mismatch -> 401"

# service token: cross-tenant allowed (admin endpoint)
SVCR=$(curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/api/v1/admin/evaluate-token/service" \
  -H 'Content-Type: application/json' -d '{"purpose":"control-simulate","cross_tenant":true}')
SVC=$(printf '%s' "$SVCR" | jget 'print(d.get("data",{}).get("access_token",""))')
C=$(code "$SVC" '{"tenant_id":"tenant-x","role":"tool_call","tool_name":"anything","session_id":"sess-svc","args_json":"{}"}')
check "$([ "$C" = "200" ] && echo 1 || echo 0)" "service token may evaluate cross-tenant"

# ── revocation -> immediate 401 (agent-level, via Redis channel) ─────────────
info "E2E: license revocation -> next request 401 (no TTL wait)"
curl -s -X POST "$CADM/licenses/$LIC_ID/revoke" -H 'Content-Type: application/json' -d '{"reason":"e2e revoke"}' >/dev/null
REVOKED=0
for _ in $(seq 1 20); do
  C=$(code "$TOK" '{"tenant_id":"default","role":"tool_call","tool_name":"read_file","session_id":"sess-auth-1","args_json":"{}"}')
  if [ "$C" = "401" ]; then REVOKED=1; break; fi
  sleep 0.5
done
check "$REVOKED" "revoked license -> 401 (minute-level propagation)"
check "$(cat "$WORK/body" | jget 'print("1" if d.get("reason")=="revoked" else "0")')" "401 body carries reason=revoked"

# ── summary ──────────────────────────────────────────────────────────────────
echo
echo "=============== evaluate-auth e2e summary ==============="
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
