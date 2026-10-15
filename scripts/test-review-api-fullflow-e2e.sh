#!/usr/bin/env bash
# =============================================================================
# Review full-flow E2E — API-ONLY (no direct SQLite / Redis access).
#
# Simulates the REAL user operation flow end to end:
#
#   运营台 (control HTTP API)          engine HTTP API                 SDK
#   -------------------------          ----------------               ------
#   create tenants      -------------> (audit events)  --Kafka-->  stats/alerts
#   read output-review-stats  <------- (engine -> Kafka -> control ingest)
#   alert list / ack / BOM    <-------+
#
# Every assertion is made through an HTTP API. The test NEVER opens the control
# SQLite file and NEVER runs redis-cli. Audit data reaches the control plane the
# same way it does in production: the engine publishes to Kafka and the control
# ingests it. Rules for the deterministic output review are code-level
# (OutputContentRules), so they need no seeded rules or LLM.
#
# Covered (this round's changes):
#   engine  role=input / role=output side-effect contract (disposal_action only
#           on output; no challenge on review roles; legacy tool_call unchanged)
#   engine  output disposal matrix: reject / redact+sanitized_content / warn
#   engine  streaming window review (stream_chunk=true) -> disposal
#   engine  rate limiter v2: windows of one trace share a session unit
#           (unthrottled), per-trace window cap and per-session quota return
#           429 + Retry-After
#   engine  no LLM endpoint: POST /v1/chat/completions is removed (404) —
#           callers stream directly from their LLM (OpenAI/Ollama) and review
#           via the evaluate API / SDK
#   control output-review stats: answer-level aggregation (COUNT DISTINCT
#           trace_id) + window_events, via the real Kafka ingest path
#   control alerts lifecycle + Decision BOM reconstruction
#   SDK     virbius-review-sdk against the live engine: review_input /
#           review_output + apply_disposal / wrap_stream mid-stream block
#
# Requires: java, curl, python3, nc, a reachable Redis, a reachable Kafka.
# Kafka may be started here via docker-compose.infra.yml if it is not up.
#
# Usage:            bash scripts/test-review-api-fullflow-e2e.sh
# Skip build:       SKIP_BUILD=1 bash scripts/test-review-api-fullflow-e2e.sh
# Custom ports:     CONTROL_PORT=18080 ENGINE_PORT=18082 MOCK_PORT=18999
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
ENGINE_PORT="${ENGINE_PORT:-18082}"
MOCK_PORT="${MOCK_PORT:-18999}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"
KAFKA_HOST="${KAFKA_HOST:-127.0.0.1}"; KAFKA_PORT="${KAFKA_PORT:-9092}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-fullflow.XXXXXX")"
LOGS="$WORK/logs"; DATA="$WORK/data"; SDK="$ROOT/virbius-review-sdk/src"
mkdir -p "$LOGS" "$DATA"

PASS=0; FAIL=0
green(){ printf '\033[0;32m%s\033[0m\n' "$*"; }
red(){ printf '\033[0;31m%s\033[0m\n' "$*"; }
info(){ printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
check(){ if [ "$1" = "1" ]; then PASS=$((PASS+1)); green "  PASS  $2"; else FAIL=$((FAIL+1)); red "  FAIL  $2"; fi; }

cleanup() {
  info "shutting down..."
  [ -n "${ENGINE_PID:-}" ] && kill "$ENGINE_PID" 2>/dev/null || true
  [ -n "${CONTROL_PID:-}" ] && kill "$CONTROL_PID" 2>/dev/null || true
  [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
  pkill -f "$WORK/mock_ollama.py" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    info "KEEP_WORK=1: logs kept at $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT INT TERM

# ── preconditions ────────────────────────────────────────────────────────────
for bin in java curl python3 nc; do
  command -v "$bin" >/dev/null 2>&1 || { red "missing required tool: $bin"; exit 2; }
done
# Refuse to run next to a live control instance. The audit hash chain head is
# coordinated in shared Redis (virbius:audit:chain:{tenant}) while every control
# writes its own rows into its own database, so a second instance in the
# consumer group consumes the same events, assigns a different audit_seq against
# the same shared head, and leaves both databases holding a gappy subset —
# HashChainVerifier then reports "seq gap" and every audit-chain assertion
# fails. That is a real integrity break, not a flake, so fail loudly instead of
# emitting a misleading red run.
if pgrep -f 'virbius-control-.*\.jar' >/dev/null 2>&1; then
  red "another virbius-control process is already running:"
  pgrep -fl 'virbius-control-.*\.jar' >&2 || true
  red "stop it first — it shares the Redis audit chain head and the Kafka consumer group."
  exit 2
fi
if ! nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null; then
  red "Redis not reachable at $REDIS_HOST:$REDIS_PORT (engine requires it)."
  exit 2
fi
if ! nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null; then
  info "Kafka not on $KAFKA_HOST:$KAFKA_PORT — trying docker-compose.infra.yml..."
  docker compose -p virbius-infra -f "$ROOT/docker-compose.infra.yml" up -d --wait kafka >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null && break; sleep 1; done
  nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null || { red "Kafka not reachable (needed for the engine->control audit path)."; exit 2; }
fi
# Pre-create the audit/trace topics. Without this the control consumer subscribes
# to a topic that does not exist yet (auto-created only when the engine first
# publishes) and partition assignment is delayed ~1min — events published during
# the run would not be ingested until the test is over. Best-effort: if the
# broker is not the local docker one, fall back to a longer ingest wait below.
KAFKA_CID="$(docker ps --format '{{.Names}}' | grep -m1 'kafka' || true)"
if [ -n "$KAFKA_CID" ]; then
  for t in virbius-audit-events virbius-trace-events; do
    docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-topics.sh \
      --bootstrap-server localhost:9092 --create --if-not-exists \
      --topic "$t" --partitions 3 --replication-factor 1 >/dev/null 2>&1 || true
  done
  info "Kafka topics ensured (virbius-audit-events, virbius-trace-events)"
fi
APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
ENGINE_JAR="$ROOT/virbius-engine/target/virbius-engine-${APP_VER}.jar"
CONTROL_JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"

# ── build ────────────────────────────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$ENGINE_JAR" ] || [ ! -f "$CONTROL_JAR" ]; then
  info "building engine + control (skip tests)..."
  mvn -q -pl virbius-engine,virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm \
    || { red "build failed"; exit 2; }
fi
[ -f "$ENGINE_JAR" ] || { red "engine jar missing: $ENGINE_JAR"; exit 2; }
[ -f "$CONTROL_JAR" ] || { red "control jar missing: $CONTROL_JAR"; exit 2; }

# ── keys ─────────────────────────────────────────────────────────────────────
export VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
export VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
EXPORT_KEY="$(openssl rand -base64 32 2>/dev/null || python3 -c 'import os,base64;print(base64.b64encode(os.urandom(32)).decode())')"

# ── mock Ollama (native /api/chat + OpenAI-compatible /v1/chat/completions;
#    streams the XSS payload so the SDK can review a real LLM stream) ────────
cat > "$WORK/mock_ollama.py" <<PY
import json, http.server, socketserver
XSS = "Sure! Here is the snippet: <script>alert(document.cookie)</script> contact a@b.com"
NORMAL = "The weather today is sunny and warm."
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', 'ignore')
        try: req = json.loads(body or "{}")
        except Exception: req = {}
        content = XSS if "malicious" in json.dumps(req.get("messages", [])) else NORMAL
        if self.path == "/v1/chat/completions":
            self.openai(content, req.get("stream", False))
        else:
            self.ollama(content, req.get("stream", False))
    def ollama(self, content, stream):
        if stream:
            self.send_response(200)
            self.send_header('Content-Type', 'application/x-ndjson')
            self.end_headers()
            for i in range(0, len(content), 16):
                self.wfile.write((json.dumps({"message": {"role": "assistant", "content": content[i:i+16]}, "done": False}) + "\n").encode())
            self.wfile.write((json.dumps({"message": {"role": "assistant", "content": ""}, "done": True}) + "\n").encode())
        else:
            out = json.dumps({"message": {"role": "assistant", "content": content},
                              "prompt_eval_count": 10, "eval_count": 20}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(out)))
            self.end_headers()
            self.wfile.write(out)
    def openai(self, content, stream):
        if stream:
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.end_headers()
            for i in range(0, len(content), 16):
                chunk = {"choices": [{"index": 0, "delta": {"content": content[i:i+16]}}]}
                self.wfile.write(("data: " + json.dumps(chunk) + "\n\n").encode())
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            out = json.dumps({"choices": [{"index": 0,
                                           "message": {"role": "assistant", "content": content}}]}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(out)))
            self.end_headers()
            self.wfile.write(out)
    def log_message(self, *a): pass
socketserver.TCPServer.allow_reuse_address = True
socketserver.TCPServer(("127.0.0.1", $MOCK_PORT), H).serve_forever()
PY
python3 "$WORK/mock_ollama.py" > "$LOGS/mock.log" 2>&1 &
MOCK_PID=$!

wait_http(){ for _ in $(seq 1 40); do curl -sf --max-time 2 "$1" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }

MOCK_READY=0
for _ in $(seq 1 30); do
  if curl -s --max-time 2 -X POST "http://127.0.0.1:$MOCK_PORT/api/chat" -d '{"messages":[]}' 2>/dev/null | grep -q '"message"'; then
    MOCK_READY=1; break
  fi
  sleep 0.5
done
[ "$MOCK_READY" = "1" ] || { red "mock Ollama did not become ready on :$MOCK_PORT"; cat "$LOGS/mock.log" 2>/dev/null; exit 2; }

info "starting engine on :$ENGINE_PORT..."
env VIRBIUS_DATA_DIR="$DATA" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_PROMPT_LLM_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" \
      --virbius.evaluate.auth.enabled=false \
      --virbius.evaluate.auth.bootstrap-pem-file= \
    > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!

info "starting control on :$CONTROL_PORT..."
env VIRBIUS_DATA_DIR="$DATA" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" \
      --virbius.audit.export.key="$EXPORT_KEY" \
    > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!

wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed to start"; tail -n 30 "$LOGS/engine.log"; exit 2; }
wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/health" || { red "control failed to start"; tail -n 30 "$LOGS/control.log"; exit 2; }
info "services up."

E="http://127.0.0.1:$ENGINE_PORT"
C="http://127.0.0.1:$CONTROL_PORT"

# ── helpers (all HTTP, no sqlite/redis) ──────────────────────────────────────
evout(){ curl -s --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$1"; }
evcode(){ curl -s -o "$WORK/last_body" -w '%{http_code}' --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$1"; }
evretry(){ curl -s -D "$WORK/last_hdr" -o /dev/null --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$1"; grep -i '^Retry-After:' "$WORK/last_hdr" | tr -d '\r' | awk '{print $2}'; }
jget(){ python3 -c "import sys,json;d=json.load(sys.stdin);$1" 2>/dev/null || echo "PARSE_ERROR"; }

T="ff-t$$-$(date +%s)"
TS="ff-stats$$-$(date +%s)"
info "functional tenant=$T  stats tenant=$TS"

# ── Phase 1: 运营台 bootstrap (control API) ─────────────────────────────────
info "E2E: 运营台 create tenants (real operator flow)"
R=$(curl -s -X POST "$C/api/v1/admin/tenants" -H 'Content-Type: application/json' \
      -d "{\"tenant_id\":\"$T\",\"name\":\"fullflow functional\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("tenant_id")=="'"$T"'" or d.get("code")==200 else "0")')" \
      "create functional tenant $T via API"
R=$(curl -s -X POST "$C/api/v1/admin/tenants" -H 'Content-Type: application/json' \
      -d "{\"tenant_id\":\"$TS\",\"name\":\"fullflow stats\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("tenant_id")=="'"$TS"'" or d.get("code")==200 else "0")')" \
      "create stats tenant $TS via API"

info "E2E: baseline output-review-stats is empty for the fresh stats tenant"
R=$(curl -s "$C/api/v1/admin/tenants/$TS/audit/output-review-stats")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("total",0)==0 and d.get("data",{}).get("window_events",0)==0 else "0")')" \
      "baseline total=0 window_events=0 (no history in this tenant)"

# ── Phase 2: engine caller flow (deterministic output rules) ────────────────
info "E2E: output review disposal matrix (role=output)"

R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s1\",\"trace_id\":\"tr-xss\",\"content\":\"Sure! Here is the snippet: <script>alert(document.cookie)</script>\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("effective_action")=="deny" and d.get("disposal_action")=="reject" and d.get("sanitized_content") is None else "0")')" \
      "XSS output -> deny + disposal reject (no sanitized)"
check "$(printf '%s' "$R" | jget 'print("1" if d.get("challenge_id") in (None,"") else "0")')" \
      "output review never returns a challenge"

R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s2\",\"trace_id\":\"tr-cred\",\"content\":\"leaked key AKIAABCDEFGHIJKLMNOP and mail a@b.com\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("effective_action")=="deny" and d.get("disposal_action")=="redact" and "[REDACTED_AWS_KEY]" in (d.get("sanitized_content") or "") and "[REDACTED_EMAIL]" in (d.get("sanitized_content") or "") else "0")')" \
      "credential leak -> disposal redact + sanitized_content"

R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s3\",\"trace_id\":\"tr-ok\",\"content\":\"The weather today is sunny and warm.\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("effective_action")=="allow" and d.get("disposal_action")=="warn" and d.get("sanitized_content") is None else "0")')" \
      "normal output -> allow + disposal warn"

info "E2E: streaming-window output review (stream_chunk=true)"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s4\",\"trace_id\":\"tr-win1\",\"stream_chunk\":true,\"content\":\"partial window <script>alert(1)</script>\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("effective_action")=="deny" and d.get("disposal_action") in ("reject","omit") else "0")')" \
      "stream window XSS -> disposal reject/omit"

info "E2E: role=input review contract (no disposal, no challenge)"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"input\",\"tool_name\":\"chat_input\",\"session_id\":\"s5\",\"trace_id\":\"tr-in\",\"content\":\"Please write a poem about the sea\"}")
check "$(printf '%s' "$R" | jget 'print("1" if "disposal_action" not in d and "sanitized_content" not in d else "0")')" \
      "role=input response has no disposal/sanitized fields"
check "$(printf '%s' "$R" | jget 'print("1" if d.get("challenge_id") in (None,"") else "0")')" \
      "role=input review never returns a challenge"

info "E2E: legacy role=tool_call response shape unchanged (no disposal fields)"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"tool_call\",\"tool_name\":\"read_file\",\"session_id\":\"s6\",\"trace_id\":\"tr-legacy\",\"args_json\":\"{\\\"path\\\":\\\"/tmp/x\\\"}\",\"license_risk_quota\":100}")
check "$(printf '%s' "$R" | jget 'print("1" if "disposal_action" not in d and "sanitized_content" not in d and "effective_action" in d else "0")')" \
      "tool_call keeps legacy response (no disposal keys, effective_action present)"

info "E2E: role is a closed enum (user rejected with 400, blank defaults to tool_call)"
V=$(curl -s -o "$WORK/role_body" -w '%{http_code}' --max-time 10 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d '{"tenant_id":"default","role":"user","tool_name":"chat_input","content":"hi"}')
check "$([ "$V" = "400" ] && echo 1 || echo 0)" "role=user (legacy) -> 400"
check "$(cat "$WORK/role_body" 2>/dev/null | jget 'print("1" if d.get("error")=="unsupported_role" else "0")')" \
      "400 body carries unsupported_role + supported_roles"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"\",\"tool_name\":\"read_file\",\"session_id\":\"s7\",\"trace_id\":\"tr-blank\",\"args_json\":\"{}\",\"license_risk_quota\":100}")
check "$(printf '%s' "$R" | jget 'print("1" if "effective_action" in d and d.get("error") != "unsupported_role" else "0")')" \
      "blank role -> tool_call pipeline (200, effective_action present)"

# ── Phase 3: evaluate rate limiter v2 ───────────────────────────────────────
info "E2E: rate limiter v2 — 30 windows of ONE trace share a session unit (unthrottled)"
RL=1
for i in $(seq 1 30); do
  evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-rl\",\"trace_id\":\"tr-rl\",\"stream_chunk\":true,\"content\":\"window $i\"}" \
    | grep -q '"rate_limited"' && RL=0
done
check "$RL" "30 windows of one trace pass the rate limiter"

info "E2E: rate limiter v2 — per-trace window cap returns 429 + Retry-After"
CODE_LAST=""; LIMITED=0; RETRY_HDR=""
for i in $(seq 1 70); do
  CODE_LAST=$(evcode "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-cap\",\"trace_id\":\"tr-cap\",\"stream_chunk\":true,\"content\":\"cap window $i\"}")
  if [ "$CODE_LAST" = "429" ]; then LIMITED=1; RETRY_HDR=$(evretry "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-cap\",\"trace_id\":\"tr-cap\",\"stream_chunk\":true,\"content\":\"cap window 71\"}"); break; fi
done
check "$([ "$LIMITED" = "1" ] && echo 1 || echo 0)" "window cap exceeded -> HTTP 429"
check "$([ -n "$RETRY_HDR" ] && [ "$RETRY_HDR" -gt 0 ] 2>/dev/null && echo 1 || echo 0)" "429 carries a positive Retry-After header"

info "E2E: rate limiter v2 — per-session quota returns 429 + rate_limited body"
QL=0
for i in $(seq 1 65); do
  BODY=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-quota\",\"trace_id\":\"tr-q$i\",\"content\":\"benign answer $i\"}")
  printf '%s' "$BODY" | grep -q '"rate_limited"' && { QL=1; break; }
done
check "$QL" "per-session quota exceeded -> rate_limited body"

# ── Phase 4: the engine no longer serves an LLM endpoint ───────────────────
info "E2E: /v1/chat/completions is removed from the engine (LLM is called directly)"
V=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$E/v1/chat/completions" -H 'Content-Type: application/json' -d '{"model":"m","messages":[]}')
check "$([ "$V" = "404" ] || [ "$V" = "405" ] && echo 1 || echo 0)" \
      "POST /v1/chat/completions on the engine -> 404/405 (endpoint removed)"

# ── Phase 5: SDK full flow against the live engine ──────────────────────────
info "E2E: SDK review_input / review_output / apply_disposal (live engine)"
SDK_OK=$(PYTHONPATH="$SDK" python3 - "$E" "$T" 2>"$WORK/sdk1.err" <<'PY'
import sys, json, urllib.request
from virbius_review import ReviewClient

engine, tenant = sys.argv[1], sys.argv[2]

def transport(payload):
    req = urllib.request.Request(
        engine + "/v1/evaluate", data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

client = ReviewClient(engine, transport=transport, window_chars=20, context_chars=5,
                      refusal_text="BLOCKED_BY_SDK")
ok = []

# input review (benign)
v = client.review_input("Please write a poem about the sea",
                        tenant_id=tenant, session_id="sdk-in", trace_id="sdk-tr-in")
ok.append(not v.blocked)

# output review + apply_disposal (XSS -> reject)
v = client.review_output("Sure! <script>alert(1)</script>",
                         tenant_id=tenant, session_id="sdk-out", trace_id="sdk-tr-out")
ok.append(v.blocked and v.disposal_action == "reject")
ok.append(client.apply_disposal("Sure! <script>alert(1)</script>", v) == "BLOCKED_BY_SDK")

# streaming: a benign window fills and is forwarded first, then an XSS delta
# forms the next window and is blocked mid-stream.
deltas = ["benign prefix text here ", "<script>alert(1)</script> tail"]
events = list(client.wrap_stream(deltas, tenant_id=tenant, session_id="sdk-st",
                                 trace_id="sdk-tr-st"))
types = [type(e).__name__ for e in events]
ok.append("Delta" in types and "Blocked" in types and "Done" not in types)

if not all(ok):
    print("sdk1 checks:", ok, "stream_types:", types, file=sys.stderr)
print("1" if all(ok) else "0")
PY
)
check "$SDK_OK" "SDK input/output/disposal + wrap_stream mid-stream block (live engine)"
[ "$SDK_OK" = "1" ] || { red "    SDK1 stderr:"; sed 's/^/    /' "$WORK/sdk1.err" 2>/dev/null || true; }

info "E2E: SDK adapter parses a live LLM SSE stream (direct OpenAI-compatible Ollama)"
AD_OK=$(PYTHONPATH="$SDK" python3 - "$E" "$T" "http://127.0.0.1:$MOCK_PORT" 2>"$WORK/sdk2.err" <<'PY'
import sys, json, http.client
from urllib.parse import urlparse
from virbius_review.adapters import from_openai_sse
from virbius_review import ReviewClient

engine, tenant, llm = sys.argv[1], sys.argv[2], sys.argv[3]
u = urlparse(llm)

# The caller streams directly from the LLM (OpenAI-compatible endpoint) —
# the engine no longer proxies chat traffic.
conn = http.client.HTTPConnection(u.hostname, u.port, timeout=20)
body = json.dumps({"model": "m", "stream": True,
                   "messages": [{"role": "user", "content": "malicious"}]})
conn.request("POST", "/v1/chat/completions", body=body,
             headers={"Content-Type": "application/json"})
resp = conn.getresponse()

def sse_lines():
    while True:
        line = resp.readline()
        if not line:
            break
        yield line.decode("utf-8", "ignore")

# 1) adapter alone: SSE -> text deltas (content is chunked, so test the join)
deltas = list(from_openai_sse(sse_lines()))
conn.close()
joined = "".join(deltas)
if "<script>" not in joined:
    print("0"); raise SystemExit

# 2) full caller flow: stream deltas -> SDK window review -> engine
def transport(payload):
    import urllib.request
    req = urllib.request.Request(
        engine + "/v1/evaluate", data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read().decode())

client = ReviewClient(engine, transport=transport, window_chars=24, context_chars=6)
events = list(client.wrap_stream(deltas, tenant_id=tenant, session_id="sdk-sse",
                                 trace_id="sdk-tr-sse"))
types = [type(e).__name__ for e in events]
print("1" if "Blocked" in types and "Done" not in types else "0")
PY
)
check "$AD_OK" "LLM SSE (direct) -> SDK adapter -> wrap_stream -> Blocked (full stream flow)"

# ── Phase 6: 运营台 observation via the real Kafka ingest ───────────────────
info "E2E: control output-review stats (answer-level aggregation) via Kafka"
# 3 windows of ONE trace + 1 whole answer of another => 4 events, 2 answers.
for i in 1 2 3; do
  evout "{\"tenant_id\":\"$TS\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-agg\",\"trace_id\":\"tr-agg-answer\",\"stream_chunk\":true,\"content\":\"agg window $i <script>alert(1)</script>\"}" >/dev/null
done
evout "{\"tenant_id\":\"$TS\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-agg2\",\"trace_id\":\"tr-agg-answer2\",\"content\":\"whole answer <script>alert(1)</script>\"}" >/dev/null

STATS=""; GOT=0
for _ in $(seq 1 45); do
  STATS=$(curl -s "$C/api/v1/admin/tenants/$TS/audit/output-review-stats")
  GOT=$(printf '%s' "$STATS" | jget 'print(1 if d.get("data",{}).get("window_events",0) >= 4 else 0)')
  [ "$GOT" = "1" ] && break
  sleep 1
done
check "$GOT" "window_events reached >=4 via Kafka ingest (no DB seeding)"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("window_events") == 4 else "0")')" \
      "window_events == 4 (every window counted once)"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("total") == 2 else "0")')" \
      "total == 2 (answer-level: 3 windows of one trace count as ONE answer)"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("denied") == 2 else "0")')" \
      "denied == 2 (answer-level deny count)"
check "$(printf '%s' "$STATS" | jget 'print("1" if (d.get("data",{}).get("deny_rate") or 0) > 0 else "0")')" \
      "deny_rate > 0"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("window_events",0) > d.get("data",{}).get("total",0) else "0")')" \
      "window_events > total (aggregation, not raw event count)"
check "$(printf '%s' "$STATS" | jget 'print("1" if "allowed" in d.get("data",{}) and "sampled_allow_answers" in d.get("data",{}) else "0")')" \
      "stats expose reweighted fields (allowed, sampled_allow_answers)"

info "E2E: 运营台 alert lifecycle (auto-created from deny audit events)"
ALERTS=""; ALERT_OK=0; AID=""
for _ in $(seq 1 45); do
  ALERTS=$(curl -s "$C/api/v1/admin/tenants/$TS/alerts?status=open&limit=200")
  AID=$(printf '%s' "$ALERTS" | jget 'print(next((a["alert_id"] for a in d.get("data",[]) if str(a.get("trace_id","")).startswith("tr-agg")), ""))')
  NT=$(printf '%s' "$ALERTS" | jget 'print(sum(1 for a in d.get("data",[]) if str(a.get("trace_id","")).startswith("tr-agg")))')
  [ "$NT" = "2" ] && { ALERT_OK=1; break; }
  sleep 1
done
check "$ALERT_OK" "alerts auto-created for both denied answers"
if [ "$ALERT_OK" = "1" ]; then
  N1=$(printf '%s' "$ALERTS" | jget 'print(sum(1 for a in d.get("data",[]) if a.get("trace_id")=="tr-agg-answer"))')
  check "$([ "$N1" = "1" ] && echo 1 || echo 0)" \
        "one alert per answer: 3 deny window events of one trace -> exactly 1 alert (no flooding)"
  NT=$(printf '%s' "$ALERTS" | jget 'print(sum(1 for a in d.get("data",[]) if str(a.get("trace_id","")).startswith("tr-agg")))')
  check "$([ "$NT" = "2" ] && echo 1 || echo 0)" \
        "two denied answers -> exactly 2 alerts total"
fi
if [ -n "$AID" ]; then
  R=$(curl -s "$C/api/v1/admin/tenants/$TS/alerts/$AID")
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("status")=="open" else "0")')" \
        "alert get shows the new alert"
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("session_id") in ("s-agg","s-agg2") else "0")')" \
        "alert carries the engine session_id"
  R=$(curl -s -X POST "$C/api/v1/admin/tenants/$TS/alerts/$AID/ack?operator=fullflow-e2e")
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("acknowledged") else "0")')" \
        "alert ack returns acknowledged=true"
  R=$(curl -s "$C/api/v1/admin/tenants/$TS/alerts/$AID/bom")
  check "$(printf '%s' "$R" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[])) >= 1 else "0")')" \
        "alert Decision BOM reconstructs its trace"
fi
R=$(curl -s "$C/api/v1/admin/tenants/$TS/alerts/nope")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("code")==404 else "0")')" \
      "alert get missing -> 404 code"

info "E2E: Decision BOM by trace + audit chain status (read paths)"
R=$(curl -s "$C/api/v1/admin/tenants/$TS/audit/decision-bom/tr-agg-answer2")
check "$(printf '%s' "$R" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[])) >= 1 else "0")')" \
      "decision BOM reconstructs the denied answer trace"
R=$(curl -s "$C/api/v1/admin/tenants/$TS/audit/chain/status")
check "$(printf '%s' "$R" | jget 'print("1" if "data" in d else "0")')" \
      "audit chain status responds"

# ── summary ──────────────────────────────────────────────────────────────────
echo
echo "================ full-flow (API-only) summary ================="
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
