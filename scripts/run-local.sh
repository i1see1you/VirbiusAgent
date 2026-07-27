#!/usr/bin/env bash
# Start VirbiusAgent locally (control + engine + tests)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$("/usr/libexec/java_home" -v 17 2>/dev/null || true)}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}${PATH:-}"
export VIRBIUS_DATA_DIR="${VIRBIUS_DATA_DIR:-$ROOT/data}"
export VIRBIUS_REDIS_PORT="${VIRBIUS_REDIS_PORT:-6379}"
export VIRBIUS_REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:${VIRBIUS_REDIS_PORT}}"
MVN="${MVN:-mvn}"

LOG_DIR="${LOG_DIR:-/tmp/virbius-agent/logs}"
REDIS_PID_FILE="${REDIS_PID_FILE:-/tmp/virbius-agent/redis.pid}"
mkdir -p "$LOG_DIR" "$VIRBIUS_DATA_DIR" "$(dirname "$REDIS_PID_FILE")"

# ─── Colors ───
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ─── Cleanup ───
kill_port() {
  local port=$1
  local pids; pids=$(lsof -ti :"$port" 2>/dev/null || true)
  [[ -z "$pids" ]] && return 0
  info "Killing process(es) on port $port: $pids"
  kill -9 $pids 2>/dev/null || true; sleep 1
}

wait_http() {
  local url=$1 name=$2
  for _ in $(seq 1 40); do
    if curl -sf "$url" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  err "$name did not become ready: $url"; return 1
}

cleanup() {
  info "Shutting down..."
  kill_port 8080 2>/dev/null || true  # control
  kill_port 8082 2>/dev/null || true  # engine
}
trap cleanup INT TERM

# ─── Redis ───
ensure_redis() {
  if [[ "${VIRBIUS_REDIS_SKIP:-}" == "1" ]]; then
    info "Skipping Redis (VIRBIUS_REDIS_SKIP=1)"; return 0
  fi
  if ! command -v redis-cli >/dev/null 2>&1; then
    info "redis-cli not found; counters degraded. Install: brew install redis"; return 0
  fi
  if redis-cli -p "$VIRBIUS_REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
    ok "Redis already running on port $VIRBIUS_REDIS_PORT"; return 0
  fi
  if ! command -v redis-server >/dev/null 2>&1; then
    info "redis-server not found; counters degraded."; return 0
  fi
  info "Starting Redis on port $VIRBIUS_REDIS_PORT..."
  redis-server --daemonize yes --port "$VIRBIUS_REDIS_PORT" \
    --bind 127.0.0.1 --pidfile "$REDIS_PID_FILE" \
    --logfile "$LOG_DIR/redis.log" --save ""
  for _ in $(seq 1 20); do
    if redis-cli -p "$VIRBIUS_REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
      ok "Redis ready ($VIRBIUS_REDIS_URL)"; return 0
    fi; sleep 0.5
  done
  err "Redis did not respond on port $VIRBIUS_REDIS_PORT"
  tail -20 "$LOG_DIR/redis.log" 2>/dev/null || true; return 1
}

# ─── Pre-flight checks ───
info "===== VirbiusAgent Local Dev ====="
echo "  Root:       $ROOT"
echo "  Data:       $VIRBIUS_DATA_DIR"
echo "  Logs:       $LOG_DIR"
echo "  Redis:      $VIRBIUS_REDIS_URL"
echo ""

# ─── Step 1: Build Rust (virbius-core + virbius-kernel) ───
info "Building Rust components..."
if cargo build -p virbius-core -p virbius-kernel 2>&1 | tail -3; then
  ok "Rust components built"
else
  err "Rust build failed"; exit 1
fi

# ─── Step 2: Run Rust unit tests ───
info "Running Rust unit tests..."
if cargo test -p virbius-core -p virbius-kernel 2>&1 | tail -5; then
  ok "All Rust unit tests passed"
else
  err "Rust tests failed"; exit 1
fi

# ─── Step 3: Run integration tests ───
if [[ -f virbius-core/tests/integration_test.rs ]]; then
  info "Running integration tests..."
  if cargo test -p virbius-core --test integration_test 2>&1 | tail -5; then
    ok "Integration tests passed"
  else
    err "Integration tests failed (may need ed25519-dalek trait imports)"
    info "Fix and re-run: cargo test -p virbius-core --test integration_test"
  fi
fi

# ─── Step 4: Build Java (engine + control) ───
if command -v "$MVN" >/dev/null 2>&1; then
  info "Building Java components..."
  if "$MVN" -q -pl virbius-engine,virbius-control -am package -DskipTests 2>&1 | tail -3; then
    ok "Java components built"
  else
    info "Java build skipped (dependencies may need parent POM setup)"
  fi
else
  info "Maven not found; Java build skipped. Install Maven 3.9+ or set MVN=/path/to/mvn"
fi

# ─── Step 4.5: Rebuild database (optional) ───
if [[ "${VIRBIUS_REBUILD_DB:-}" == "1" ]]; then
  info "VIRBIUS_REBUILD_DB=1: rebuilding control database..."
  DB_FILE="$VIRBIUS_DATA_DIR/virbius-control.db"
  if [[ -f "$DB_FILE" ]]; then
    rm -f "$DB_FILE" "$DB_FILE-wal" "$DB_FILE-shm"
    ok "Removed $DB_FILE (will be recreated from schema.sql + seed.sql on startup)"
  else
    info "Database file not found: $DB_FILE (nothing to rebuild)"
  fi
  # Also flush Redis so stale policy/challenge data doesn't survive the rebuild
  if command -v redis-cli >/dev/null 2>&1 && redis-cli -p "$VIRBIUS_REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
    redis-cli -p "$VIRBIUS_REDIS_PORT" FLUSHDB >/dev/null 2>&1
    ok "Flushed Redis DB (stale challenge exemptions / policy cache cleared)"
  fi
fi

# ─── Step 5: Start services ───
for p in 8080 8082; do kill_port "$p"; done
ensure_redis

if command -v java >/dev/null 2>&1 && [[ -f virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar ]]; then
  info "Starting virbius-control (port 8080)..."
  nohup env VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
    java -jar "$ROOT/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar" \
    >"$LOG_DIR/control.log" 2>&1 &
  wait_http "http://127.0.0.1:8080/api/v1/health" "virbius-control" || { err "Check logs: $LOG_DIR/control.log"; tail -30 "$LOG_DIR/control.log" 2>/dev/null || true; }
fi

if command -v java >/dev/null 2>&1 && [[ -f virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar ]]; then
  info "Starting virbius-engine (port 8082)..."
  nohup env VIRBIUS_DATA_DIR="$VIRBIUS_DATA_DIR" VIRBIUS_REDIS_URL="$VIRBIUS_REDIS_URL" \
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
    java -jar "$ROOT/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar" \
    >"$LOG_DIR/engine.log" 2>&1 &
  wait_http "http://127.0.0.1:8082/admin/health" "virbius-engine" || { err "Check logs: $LOG_DIR/engine.log"; tail -30 "$LOG_DIR/engine.log" 2>/dev/null || true; }
fi

# ─── Summary ───
echo ""
echo -e "${GREEN}===== VirbiusAgent Local Dev Ready =====${NC}"
echo "  virbius-core    $(cargo metadata --format-version 1 --no-deps 2>/dev/null | python3 -c 'import sys,json; d=json.load(sys.stdin); print([p for p in d["packages"] if p["name"]=="virbius-core"][0]["version"])' 2>/dev/null || echo '0.1.0')"
echo "  virbius-kernel  $(cargo metadata --format-version 1 --no-deps 2>/dev/null | python3 -c 'import sys,json; d=json.load(sys.stdin); print([p for p in d["packages"] if p["name"]=="virbius-kernel"][0]["version"])' 2>/dev/null || echo '0.1.0')"
echo "  virbius-control http://127.0.0.1:8080"
echo "  virbius-engine  http://127.0.0.1:8082"
echo "  Redis           $VIRBIUS_REDIS_URL"
echo "  Logs:           $LOG_DIR"
echo ""
echo "  Quick test:"
echo "    cargo test -p virbius-core -p virbius-kernel"
echo "    curl http://127.0.0.1:8080/api/v1/health"
echo "    curl http://127.0.0.1:8082/admin/health"
echo ""
echo "  Example MCP proxy:"
echo "    cargo run --example mcp_proxy"
echo ""
