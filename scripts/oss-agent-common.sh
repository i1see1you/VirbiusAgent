#!/usr/bin/env bash
# =============================================================================
# Shared rig for the "OSS Agent as target" E2E scripts.
#
# Boots an ISOLATED control + engine (temp data dir, custom ports), ensures
# Redis/Kafka, and issues a tenant + tool registry + License for the target
# Agent. It does NOT boot the MCP proxy / upstream — that is proxy-mode only
# (see oss-agent-e2e-proxy.sh).
#
# Sourced, not executed. Callers get: check/jget/info/red/green, PASS/FAIL,
# oa_boot_all, and exported license material (LICENSE_JWT_FILE, LICENSE_PUB_FILE,
# APP_ID, TENANT) plus $PY (the examples venv interpreter).
# =============================================================================

set -euo pipefail

OA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$OA_ROOT"

: "${CONTROL_PORT:=18080}"
: "${ENGINE_PORT:=18082}"
: "${REDIS_HOST:=127.0.0.1}"; : "${REDIS_PORT:=6379}"
: "${KAFKA_HOST:=127.0.0.1}"; : "${KAFKA_PORT:=9092}"
# Unique per run: Kafka retains every earlier run's audit events, and the shared
# Redis keeps per-tenant session signals — a fixed tenant would both pollute the
# audit read and let stale signals (e.g. an accumulated prompt-injection score)
# bleed into this run's engine evaluation.
: "${TENANT:=oss-agent-$$}"
: "${APP_ID:=oss-agent-app}"
: "${ALLOW_TOOL:=read_doc}"
: "${BLOCK_TOOL:=danger_exec}"

OA_WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-ossagent.XXXXXX")"
OA_LOGS="$OA_WORK/logs"; OA_DATA="$OA_WORK/data"
mkdir -p "$OA_LOGS" "$OA_DATA"

VENV="$OA_ROOT/examples/oss-agent-target/.venv"
PY="$VENV/bin/python"

PASS=0; FAIL=0
green(){ printf '\033[0;32m%s\033[0m\n' "$*"; }
red(){ printf '\033[0;31m%s\033[0m\n' "$*"; }
info(){ printf '\033[0;36m[INFO]\033[0m %s\n' "$*"; }
skip(){ printf '\033[0;33m[SKIP]\033[0m %s\n' "$*"; }
check(){ if [ "$1" = "1" ]; then PASS=$((PASS+1)); green "  PASS  $2"; else FAIL=$((FAIL+1)); red "  FAIL  $2"; fi; }
jget(){ "$PY" -c "import sys,json;d=json.load(sys.stdin);$1" "${2:-}" 2>/dev/null || echo "PARSE_ERROR"; }

oa_wait_http(){ local url="$1" tries="${2:-40}"; for _ in $(seq 1 "$tries"); do curl -sf --max-time 2 "$url" >/dev/null 2>&1 && return 0; sleep 1; done; return 1; }
oa_free_port(){ local port="$1" pids; pids="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)"; [ -z "$pids" ] && return 0; info "freeing port $port (stale: $pids)"; kill $pids 2>/dev/null || true; for _ in $(seq 1 20); do lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || break; sleep 0.5; done; lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && { red "port $port still occupied"; exit 2; }; }
oa_assert_alive(){ kill -0 "$1" 2>/dev/null; }

oa_cleanup(){
  info "shutting down..."
  for v in OA_PROXY_PID OA_UPSTREAM_PID OA_CONTROL_PID OA_ENGINE_PID; do
    [ -n "${!v:-}" ] && kill "${!v}" 2>/dev/null || true
  done
  wait 2>/dev/null || true
  if [ "${KEEP_WORK:-0}" = "1" ]; then info "KEEP_WORK=1: logs at $OA_WORK"; else rm -rf "$OA_WORK"; fi
}
trap oa_cleanup EXIT INT TERM

oa_preflight(){
  for bin in java curl python3 nc docker cargo; do
    command -v "$bin" >/dev/null 2>&1 || { red "missing required tool: $bin"; exit 2; }
  done
  if [ ! -x "$PY" ]; then
    info "examples venv missing — running scripts/oss-agent-setup.sh..."
    bash "$OA_ROOT/scripts/oss-agent-setup.sh" || { red "venv setup failed"; exit 2; }
  fi
  [ -x "$PY" ] || { red "examples venv still missing: $PY"; exit 2; }
  if pgrep -f 'virbius-control-.*\.jar' >/dev/null 2>&1; then
    red "another virbius-control is running (single-instance test env):"; pgrep -fl 'virbius-control-.*\.jar' >&2 || true; exit 2
  fi
  nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null || { red "Redis not reachable at $REDIS_HOST:$REDIS_PORT"; exit 2; }
  if ! nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null; then
    info "starting Kafka via docker-compose.infra.yml..."
    docker compose -p virbius-infra -f "$OA_ROOT/docker-compose.infra.yml" up -d --wait kafka >/dev/null 2>&1 || true
    for _ in $(seq 1 30); do nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null && break; sleep 1; done
    nc -z "$KAFKA_HOST" "$KAFKA_PORT" 2>/dev/null || { red "Kafka not reachable"; exit 2; }
  fi
}

OA_CONTROL_JAR=""; OA_ENGINE_JAR=""
oa_build(){
  local APP_VER
  APP_VER="$(grep -m1 '<version>' "$OA_ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
  OA_CONTROL_JAR="$OA_ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
  OA_ENGINE_JAR="$OA_ROOT/virbius-engine/target/virbius-engine-${APP_VER}.jar"
  if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$OA_CONTROL_JAR" ] || [ ! -f "$OA_ENGINE_JAR" ]; then
    info "building control + engine (skip tests)..."
    mvn -q -pl virbius-engine,virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm \
      > "$OA_LOGS/build.log" 2>&1 || { red "maven build failed"; tail -30 "$OA_LOGS/build.log"; exit 2; }
  fi
}

oa_boot_engine_control(){
  oa_build
  [ -f "$OA_CONTROL_JAR" ] || { red "control jar missing"; exit 2; }
  [ -f "$OA_ENGINE_JAR" ] || { red "engine jar missing"; exit 2; }

  VIRBIUS_LICENSE_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
  VIRBIUS_DELEGATED_MASTER_KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
  export VIRBIUS_LICENSE_MASTER_KEY VIRBIUS_DELEGATED_MASTER_KEY

  oa_free_port "$ENGINE_PORT"
  info "starting engine on :$ENGINE_PORT..."
  # The engine's prompt-injection / STI-taint detectors are LLM-backed
  # (virbius.prompt-llm, default http://127.0.0.1:11434). This rig tests the
  # PROXY, so disable them to keep engine decisions deterministic and
  # independent of whatever local model happens to be running.
  env VIRBIUS_DATA_DIR="$OA_DATA" VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
      VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
      VIRBIUS_INJECTION_DETECT_ENABLED=false \
      VIRBIUS_TAINT_DETECT_ENABLED=false \
      SPRING_PROFILES_ACTIVE=dev \
      java -jar "$OA_ENGINE_JAR" --server.port="$ENGINE_PORT" \
        --virbius.evaluate.auth.enabled=false \
        --virbius.evaluate.auth.bootstrap-pem-file= \
      > "$OA_LOGS/engine.log" 2>&1 &
  OA_ENGINE_PID=$!
  oa_wait_http "http://127.0.0.1:$ENGINE_PORT/admin/health" || { red "engine failed to start"; tail -n 30 "$OA_LOGS/engine.log"; exit 2; }
  oa_assert_alive "$OA_ENGINE_PID" || { red "engine died"; tail -n 30 "$OA_LOGS/engine.log"; exit 2; }

  oa_free_port "$CONTROL_PORT"
  info "starting control on :$CONTROL_PORT..."
  env VIRBIUS_DATA_DIR="$OA_DATA" VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
      KAFKA_BOOTSTRAP_SERVERS="$KAFKA_HOST:$KAFKA_PORT" \
      VIRBIUS_LICENSE_MASTER_KEY="$VIRBIUS_LICENSE_MASTER_KEY" \
      VIRBIUS_DELEGATED_MASTER_KEY="$VIRBIUS_DELEGATED_MASTER_KEY" \
      SPRING_PROFILES_ACTIVE=dev \
      java -jar "$OA_CONTROL_JAR" --server.port="$CONTROL_PORT" \
        --spring.kafka.consumer.auto-offset-reset=latest > "$OA_LOGS/control.log" 2>&1 &
  OA_CONTROL_PID=$!
  oa_wait_http "http://127.0.0.1:$CONTROL_PORT/api/v1/ready" || { red "control failed to start"; tail -n 30 "$OA_LOGS/control.log"; exit 2; }
  oa_assert_alive "$OA_CONTROL_PID" || { red "control died"; tail -n 30 "$OA_LOGS/control.log"; exit 2; }
}

oa_bootstrap_license(){
  local C="http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants/$TENANT"
  info "bootstrapping tenant $TENANT + license ($APP_ID, allowlist=$ALLOW_TOOL)..."
  curl -s -X POST "http://127.0.0.1:$CONTROL_PORT/api/v1/admin/tenants" \
    -H 'Content-Type: application/json' -d "{\"tenant_id\":\"$TENANT\",\"name\":\"oss agent target\"}" >/dev/null
  for tool in "$ALLOW_TOOL" "$BLOCK_TOOL"; do
    curl -s -X POST "$C/tools" -H 'Content-Type: application/json' \
      -d "{\"tool_name\":\"$tool\",\"risk_class\":\"low\",\"sandbox_type\":\"none\",\"timeout_ms\":30000,\"fast_path\":true,\"approval_mode\":\"lax\"}" >/dev/null
  done
  local jwt
  jwt="$(curl -s -X POST "$C/licenses/issue" -H 'Content-Type: application/json' \
    -d "{\"app_id\":\"$APP_ID\",\"agent_name\":\"oss-agent-target\",\"allowed_tools\":[\"$ALLOW_TOOL\"],\"risk_quota\":100}" \
    | jget 'print(d.get("data",{}).get("jwt") or "")')"
  [ -n "$jwt" ] || { red "license issue returned no jwt"; tail -30 "$OA_LOGS/control.log"; exit 2; }
  LICENSE_JWT_FILE="$OA_WORK/license.jwt"; LICENSE_PUB_FILE="$OA_WORK/license_pub.pem"
  printf '%s' "$jwt" > "$LICENSE_JWT_FILE"
  curl -s "$C/licenses/public-key" \
    | "$PY" -c 'import sys,json; d=json.load(sys.stdin); sys.stdout.write((d.get("data",{}).get("public_key_pem") or "").strip()+"\n")' \
    > "$LICENSE_PUB_FILE"
  grep -q "BEGIN PUBLIC KEY" "$LICENSE_PUB_FILE" || { red "public-key fetch failed"; exit 2; }
  # Canonical proxy env names (VIRBIUS_LICENSE_FILE / VIRBIUS_LICENSE_PUBLIC_KEY /
  # VIRBIUS_APP_ID) — the same variables VirbiusGuard.from_env() reads.
  export VIRBIUS_LICENSE_FILE="$LICENSE_JWT_FILE" \
         VIRBIUS_LICENSE_PUBLIC_KEY="$LICENSE_PUB_FILE" \
         VIRBIUS_APP_ID="$APP_ID" \
         VIRBIUS_TENANT_ID="$TENANT" \
         LICENSE_JWT_FILE LICENSE_PUB_FILE APP_ID TENANT
}

oa_boot_all(){ oa_preflight; oa_boot_engine_control; oa_bootstrap_license; }

oa_summary(){
  echo
  echo "=============== oss-agent e2e summary ==============="
  green "PASS: $PASS"
  if [ "$FAIL" -gt 0 ]; then red "FAIL: $FAIL"; else echo "FAIL: 0"; fi
  [ "$FAIL" -eq 0 ] || exit 1
  green "ALL CHECKS PASSED"
}

oa_llm_available(){
  [ -n "${OPENAI_API_KEY:-}${DEEPSEEK_API_KEY:-}" ]
}
