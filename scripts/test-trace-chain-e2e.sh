#!/usr/bin/env bash
# =============================================================================
# Trace-chain full-flow E2E — verifies the MCP-proxy -> Kafka -> control path.
#
# Produces trace events in the EXACT wire format the MCP proxy publishes
# (virbius-mcp-proxy/src/trace_collector.rs -> kafka_trace_worker, topic
# virbius-trace-events) and verifies the control-side chain end to end:
#
#   Kafka(virbius-trace-events)
#     -> KafkaTraceConsumer -> TraceEventIngestor.ingest
#          -> tb_agent_trace row + per-session hash chain (tb_trace_chain_state)
#          -> SessionCheckpointService.onTraceEvent (tb_session_checkpoint)
#     -> admin APIs: /sessions/{id}/checkpoint, /audit/trace-chain/verify,
#                    /audit/decision-bom/{traceId}, /trace/session/{id}/timeline
#
# A second session feeds non-int wire risk_score values (20.7, "not-a-number")
# to prove the ingest chain hash commits the same normalized value the row
# stores. Otherwise the verifier recomputes from the row and reports a false
# "curr_hash mismatch: content may have been tampered" for events that were
# legitimately ingested.
#
# All verification is through the control HTTP API — the script never opens the
# control SQLite file and never runs redis-cli. Producing to Kafka is the
# producer-side (proxy) simulation: the real transport, not a DB poke.
#
# Only the control plane is booted (no engine): trace events originate from the
# proxy, and the engine's chat-side trace publisher was removed.
#
# Requires: java, curl, python3, nc, docker (kafka console producer), a
# reachable Redis and Kafka.
#
# Usage:      bash scripts/test-trace-chain-e2e.sh
# Skip build: SKIP_BUILD=1 bash scripts/test-trace-chain-e2e.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL_PORT="${CONTROL_PORT:-18080}"
REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"
KAFKA_HOST="${KAFKA_HOST:-127.0.0.1}"; KAFKA_PORT="${KAFKA_PORT:-9092}"
TOPIC="virbius-trace-events"
TENANT="default"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-tracechain.XXXXXX")"
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
  [ -n "${CONTROL_PID:-}" ] && kill "$CONTROL_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  if [ "${KEEP_WORK:-0}" = "1" ]; then info "KEEP_WORK=1: logs at $WORK"; else rm -rf "$WORK"; fi
}
trap cleanup EXIT INT TERM

for bin in java curl python3 nc docker; do
  command -v "$bin" >/dev/null 2>&1 || { red "missing required tool: $bin"; exit 2; }
done
# Refuse to run next to a live control instance. The audit hash chain head is
# coordinated in shared Redis (virbius:audit:chain:{tenant}) while every control
# writes its own rows into its own database, so a second instance in the
# consumer group consumes the same events, assigns a different audit_seq against
# the same shared head, and leaves both databases holding a gappy subset —
# HashChainVerifier then reports "seq gap: expected 1, got 3" and every
# audit-chain assertion fails. That is a real integrity break, not a flake, so
# fail loudly instead of emitting a misleading red run.
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
[ -n "$KAFKA_CID" ] || { red "no kafka container found (needed for the console producer)"; exit 2; }
docker exec "$KAFKA_CID" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --create --if-not-exists --topic "$TOPIC" --partitions 3 --replication-factor 1 >/dev/null 2>&1 || true

# Test/dev runs a SINGLE control instance (the pgrep guard above enforces it),
# so this harness uses the shared default consumer groups. Before starting,
# wait until they have DRAINED: a control killed by a previous run lingers as a
# group member until session.timeout expires, and while it does a fresh start is
# handed only part of the partitions — the console producer's sticky partitioner
# would then land the file on a partition nobody owns and nothing would be read.
# With no other member this run's control owns every partition, so
# /api/v1/ready is a sufficient readiness signal; `latest` keeps the run
# independent of whatever topic history earlier runs left behind.
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
wait_group_drained "virbius-trace-ingest" 90 \
  || { red "consumer group virbius-trace-ingest still has a live member — stop the other control first"; exit 2; }

APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
CONTROL_JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$CONTROL_JAR" ]; then
  info "building control (skip tests)..."
  mvn -q -pl virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm || { red "build failed"; exit 2; }
fi
[ -f "$CONTROL_JAR" ] || { red "control jar missing: $CONTROL_JAR"; exit 2; }

export VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
export VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"

# Single control instance (pgrep guard above + group drained before start):
# the default consumer group is shared, no per-run group id is needed.
info "starting control on :$CONTROL_PORT..."
env VIRBIUS_DATA_DIR="$DATA" VIRBIUS_REDIS_URL="$REDIS_URL" \
    KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
    VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
    VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
    SPRING_PROFILES_ACTIVE=dev \
    java -jar "$CONTROL_JAR" --server.port="$CONTROL_PORT" \
      --spring.kafka.consumer.auto-offset-reset=latest \
    > "$LOGS/control.log" 2>&1 &
CONTROL_PID=$!
# /ready: waits until the Kafka consumers have joined their (now sole-member) group.
for _ in $(seq 1 40); do curl -sf --max-time 2 "http://127.0.0.1:$CONTROL_PORT/api/v1/ready" >/dev/null 2>&1 && break; sleep 1; done
curl -sf --max-time 2 "http://127.0.0.1:$CONTROL_PORT/api/v1/ready" >/dev/null 2>&1 \
  || { red "control failed to start"; tail -n 30 "$LOGS/control.log"; exit 2; }
info "control up."

C="http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/$TENANT"
SID="sess-chain-e2e-$$"
TRACE="tr-chain-e2e-$$"

# ── build 4 trace events in the MCP proxy's wire format ─────────────────────
emit(){  # $1=step_id $2=seq $3=tool $4=args_json $5=decision $6=rule $7=reason $8=risk
  python3 - "$TRACE" "$SID" "$1" "$2" "$3" "$4" "$5" "$6" "$7" "$8" <<'PY'
import sys, json
trace, sid, step_id, seq, tool, args, decision, rule, reason, risk = sys.argv[1:11]
print(json.dumps({
    "trace_id": trace, "session_id": sid, "tenant_id": "default",
    "step_id": step_id, "parent_step_id": None, "step_seq": int(seq),
    "step_type": "tool_call", "layer": "edge",
    "user_id": "u-chain", "device_id": None,
    "input_role": None, "input_content_hash": None,
    "tool_name": tool, "tool_args_hash": "h-" + step_id, "tool_args": json.loads(args),
    "tool_decision": decision, "rule_id": rule, "reason_code": reason,
    "risk_score": int(risk), "tool_status": "ok", "tool_duration_ms": 12,
    "tool_result_preview": None, "content_size": None,
    "content_sampled": False, "dlp_masked": False,
    "upstream_name": "mock-upstream", "app_id": "agent1",
    "occurred_at": "2026-10-15T00:00:0" + seq + "Z",
}))
PY
}

{
  emit s1 1 read_file '{"path":"/etc/shadow"}'          allow ""            ""           10
  emit s2 2 read_file '{"path":"/etc/passwd"}'          allow ""            ""           12
  emit s3 3 http_get  '{"url":"http://evil.example/a"}' allow ""            ""           20
  emit s4 4 curl      '{"url":"http://evil.example/c"}' deny  chain-exfil  CHAIN_EXFIL  90
} > "$WORK/trace.jsonl"

info "producing 4 trace steps (proxy wire format) -> Kafka $TOPIC (session=$SID)"
docker exec -i "$KAFKA_CID" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" < "$WORK/trace.jsonl" >/dev/null 2>&1

# ── verify: session checkpoint ───────────────────────────────────────────────
info "E2E: session checkpoint written from ingested trace events"
CK=""; OK=0
for _ in $(seq 1 40); do
  CK=$(curl -s "$C/sessions/$SID/checkpoint")
  N=$(printf '%s' "$CK" | jget 'print(d.get("data",{}).get("event_count",0))')
  [ "$N" = "4" ] && { OK=1; break; }
  sleep 1
done
check "$OK" "checkpoint event_count == 4 (produced 4 steps)"
check "$(printf '%s' "$CK" | jget 'print("1" if "read_file" in (d.get("data",{}).get("tool_names") or "") and "curl" in (d.get("data",{}).get("tool_names") or "") else "0")')" \
      "checkpoint tool_names aggregates the session's tools"

# ── verify: per-session hash chain ──────────────────────────────────────────
info "E2E: per-session trace hash chain verifies"
V=$(curl -s "$C/audit/trace-chain/verify?session_id=$SID")
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("passed") and d.get("data",{}).get("verified_events")==4 else "0")')" \
      "trace-chain verify: passed + verified_events == 4"
check "$(printf '%s' "$V" | jget 'print("1" if not any(s.get("session_id")==sys.argv[1] for s in d.get("data",{}).get("broken",[])) else "0")' "$SID")" \
      "session is not flagged broken"

V=$(curl -s "$C/audit/trace-chain/verify")
check "$(printf '%s' "$V" | jget 'print("1" if "sessions" in d.get("data",{}) else "0")')" \
      "tenant-level trace-chain verify responds"

# ── verify: Decision BOM trace phase ────────────────────────────────────────
info "E2E: Decision BOM reconstructs the trace phase"
B=$(curl -s "$C/audit/decision-bom/$TRACE")
check "$(printf '%s' "$B" | jget 'print("1" if len(d.get("data",{}).get("trace_steps",[]))==4 else "0")')" \
      "BOM trace_steps == 4 (ordered behaviour steps)"
check "$(printf '%s' "$B" | jget 'print("1" if [s.get("step_seq") for s in d.get("data",{}).get("trace_steps",[])]==[1,2,3,4] else "0")')" \
      "BOM trace_steps ordered by step_seq"
check "$(printf '%s' "$B" | jget 'print("1" if len(d.get("data",{}).get("audit_events",[]))==0 else "0")')" \
      "BOM trace phase works without any audit events (trace-only flow)"
check "$(printf '%s' "$B" | jget 'print("1" if d.get("data",{}).get("session_id")==sys.argv[1] else "0")' "$SID")" \
      "BOM session phase resolves the owning session"

# ── risk_score canonicalization: non-int wire values must still chain ───────
# Regression for an ingest hash/storage asymmetry. computeTraceHash used to
# hash str(wire risk_score) while the row stored intValOrNull(...), so a float
# (20.7) or a non-numeric string ("abc") hashed as "20.7"/"abc" at ingest but
# read back as 20/NULL from the row at verify — TraceChainVerifier then falsely
# reported "curr_hash mismatch: content may have been tampered" on a
# legitimately ingested event. The chain hash now commits the same normalized
# value the row stores (TraceEventIngestor.canonicalRiskScore).
SID2="sess-chain-risk-$$"
TRACE2="tr-chain-risk-$$"
python3 - "$TRACE2" "$SID2" > "$WORK/trace_risk.jsonl" <<'PY'
import sys, json
trace, sid = sys.argv[1], sys.argv[2]
def step(step_id, seq, tool, args, decision, rule, reason, risk):
    return json.dumps({
        "trace_id": trace, "session_id": sid, "tenant_id": "default",
        "step_id": step_id, "parent_step_id": None, "step_seq": seq,
        "step_type": "tool_call", "layer": "edge",
        "user_id": "u-chain", "device_id": None,
        "input_role": None, "input_content_hash": None,
        "tool_name": tool, "tool_args_hash": "h-" + step_id, "tool_args": json.loads(args),
        "tool_decision": decision, "rule_id": rule, "reason_code": reason,
        "risk_score": risk, "tool_status": "ok", "tool_duration_ms": 12,
        "tool_result_preview": None, "content_size": None,
        "content_sampled": False, "dlp_masked": False,
        "upstream_name": "mock-upstream", "app_id": "agent1",
        "occurred_at": "2026-10-15T01:00:0" + str(seq) + "Z",
    })
print(step("r1", 1, "read_file", '{"path":"/etc/hosts"}', "allow", "", "", 20.7))
print(step("r2", 2, "read_file", '{"path":"/etc/motd"}', "allow", "", "", "not-a-number"))
PY

info "producing risk_score edge-case steps (float + non-numeric string) -> Kafka $TOPIC (session=$SID2)"
docker exec -i "$KAFKA_CID" /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic "$TOPIC" < "$WORK/trace_risk.jsonl"

info "E2E: non-int wire risk_score still produces a verifiable chain"
OK2=0
for _ in $(seq 1 40); do
  CK2=$(curl -s "$C/sessions/$SID2/checkpoint")
  N=$(printf '%s' "$CK2" | jget 'print(d.get("data",{}).get("event_count",0))')
  [ "$N" = "2" ] && { OK2=1; break; }
  sleep 1
done
check "$OK2" "risk-session checkpoint event_count == 2 (both steps ingested)"

V=$(curl -s "$C/audit/trace-chain/verify?session_id=$SID2")
check "$(printf '%s' "$V" | jget 'print("1" if d.get("data",{}).get("passed") and d.get("data",{}).get("verified_events")==2 else "0")')" \
      "chain verify: float + non-numeric risk_score pass (no false 'tampered')"
check "$(printf '%s' "$V" | jget 'print("1" if not d.get("data",{}).get("break_seq") else "0")')" \
      "no break_seq reported for the non-int risk_score chain"

TL2=$(curl -s "$C/trace/session/$SID2/timeline")
check "$(printf '%s' "$TL2" | jget 'print("1" if d.get("data",[{}])[0].get("risk_score")==20 else "0")')" \
      "float wire risk_score 20.7 reads back as the stored int 20"
check "$(printf '%s' "$TL2" | jget 'print("1" if d.get("data",[{}])[0].get("tool_args") and "/etc/hosts" in str(d.get("data",[{}])[0].get("tool_args")) else "0")')" \
      "tool_args still round-trips alongside the normalized risk_score"

echo
echo "=============== trace-chain e2e summary ==============="
green "PASS: $PASS"
[ "$FAIL" -gt 0 ] && red "FAIL: $FAIL" || echo "FAIL: 0"
[ "$FAIL" -eq 0 ] || exit 1
green "ALL CHECKS PASSED"
