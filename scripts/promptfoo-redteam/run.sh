#!/usr/bin/env bash
# PoC: run promptfoo red-team probes against VirbiusAgent engine /v1/evaluate.
# Boots a minimal engine (auth disabled; Redis required by the readiness gate),
# runs `promptfoo eval`, then tears the engine down.
#
#   bash scripts/promptfoo-redteam/run.sh
#   ENGINE_PORT=18082 PROMPTFOO="npx --yes promptfoo@latest" bash scripts/promptfoo-redteam/run.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
ENGINE_PORT="${ENGINE_PORT:-18082}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"; REDIS_PORT="${REDIS_PORT:-6379}"
PROMPTFOO="${PROMPTFOO:-npx --yes promptfoo@latest}"
export VIRBIUS_ENGINE_URL="http://127.0.0.1:${ENGINE_PORT}"

command -v java >/dev/null || { echo "java required"; exit 2; }
nc -z "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null \
  || { echo "Redis not reachable at $REDIS_HOST:$REDIS_PORT (engine readiness gate)"; exit 2; }

APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
ENGINE_JAR="$ROOT/virbius-engine/target/virbius-engine-${APP_VER}.jar"
[ -f "$ENGINE_JAR" ] || { echo "engine jar missing: $ENGINE_JAR"
  echo "build: mvn -q -pl virbius-engine -am package -DskipTests"; exit 2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ve-pf.XXXXXX")"
ENGINE_PID=""
cleanup(){ [ -n "$ENGINE_PID" ] && kill "$ENGINE_PID" 2>/dev/null || true; wait 2>/dev/null || true; }
trap cleanup EXIT INT TERM

KEY="$(od -An -tx1 -N32 /dev/urandom | tr -d ' \n')"
echo "[run.sh] starting engine :$ENGINE_PORT (logs: $WORK/engine.log)"
env VIRBIUS_DATA_DIR="$WORK/data" VIRBIUS_REDIS_URL="redis://$REDIS_HOST:$REDIS_PORT" \
    VIRBIUS_LICENSE_MASTER_KEY="$KEY" SPRING_PROFILES_ACTIVE=dev \
    java -jar "$ENGINE_JAR" --server.port="$ENGINE_PORT" \
      --virbius.evaluate.auth.enabled=false --virbius.evaluate.auth.bootstrap-pem-file= \
    > "$WORK/engine.log" 2>&1 &
ENGINE_PID=$!

for _ in $(seq 1 90); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$VIRBIUS_ENGINE_URL/admin/ready" 2>/dev/null)" = "200" ] && break
  sleep 1
done
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$VIRBIUS_ENGINE_URL/admin/ready" 2>/dev/null)" = "200" ] \
  || { echo "[run.sh] engine not ready; tail:"; tail -20 "$WORK/engine.log"; exit 1; }
echo "[run.sh] engine ready → $VIRBIUS_ENGINE_URL"

cd "$HERE"
$PROMPTFOO eval -c promptfooconfig.yaml --no-cache "$@"
