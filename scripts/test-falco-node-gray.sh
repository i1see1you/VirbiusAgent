#!/usr/bin/env bash
# E2E: kernel (falco) node-level gray release.
#
# Scenario (two simulated kernel nodes, buckets precomputed from control-plane CRC32C):
#   node A: VIRBIUS_NODE_ID=kernel-01.prod → bucket 15
#   node B: VIRBIUS_NODE_ID=node-b         → bucket 93
#
#   Phase 1 baseline:  rule v1 → full gray (5→20→50→100) + finalize → both nodes stable@rev1
#   Phase 2 gray v2:   new rule → prepare+upgrade to 20% → A runs canary@rev2, B stays rev1
#   Phase 3 to 100%:   upgrades to 100% + finalize → both nodes on rev2
#   Phase 4 rollback:  new rule → gray to 20% → rollback → both nodes fall back to rev2
#
# Uses isolated ports (control 8090, redis 6380) and temp dirs so it never touches dev state.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }
info() { echo -e "${CYAN}[STEP]${NC} $*"; }

TENANT="e2e-falco"
CONTROL_PORT=8090
REDIS_PORT=6380
BASE="http://127.0.0.1:${CONTROL_PORT}"
WORK="$(mktemp -d /tmp/falco-node-gray.XXXXXX)"
DATA_DIR="$WORK/data"
RULES_A="$WORK/rules-a"
RULES_B="$WORK/rules-b"
LOG_DIR="$WORK/logs"
mkdir -p "$DATA_DIR" "$RULES_A" "$RULES_B" "$LOG_DIR"

CONTROL_PID=""
SUB_A_PID=""
SUB_B_PID=""
REDIS_STARTED=""

cleanup() {
  echo ""
  info "cleanup (logs kept at $LOG_DIR)"
  [[ -n "$SUB_A_PID" ]] && kill "$SUB_A_PID" 2>/dev/null || true
  [[ -n "$SUB_B_PID" ]] && kill "$SUB_B_PID" 2>/dev/null || true
  [[ -n "$CONTROL_PID" ]] && kill "$CONTROL_PID" 2>/dev/null || true
  if [[ -n "$REDIS_STARTED" ]]; then redis-cli -p "$REDIS_PORT" shutdown nosave 2>/dev/null || true; fi
}
trap cleanup EXIT

# ─── 0. Environment ─────────────────────────────────────────────────────────
info "0. environment"

command -v redis-server >/dev/null || err "redis-server not found (brew install redis)"
command -v redis-cli    >/dev/null || err "redis-cli not found"
command -v jq           >/dev/null || err "jq not found"
command -v cargo        >/dev/null || err "cargo not found"
command -v java         >/dev/null || err "java not found"

if ! redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
  redis-server --daemonize yes --port "$REDIS_PORT" --bind 127.0.0.1 \
    --logfile "$LOG_DIR/redis.log" --save "" >/dev/null
  REDIS_STARTED=1
  for _ in $(seq 1 20); do
    redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG && break
    sleep 0.5
  done
fi
redis-cli -p "$REDIS_PORT" ping | grep -q PONG || err "redis not ready on $REDIS_PORT"
redis-cli -p "$REDIS_PORT" FLUSHDB >/dev/null   # dedicated test port; keep runs deterministic
ok "redis on :$REDIS_PORT"

info "building virbius-kernel subscriber..."
cargo build -q -p virbius-kernel --bin falco-config-subscriber
CARGO_TARGET="$(cargo metadata --format-version 1 --no-deps 2>/dev/null \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["target_directory"])')"
SUB_BIN="${CARGO_TARGET:-$ROOT/target}/debug/falco-config-subscriber"
[[ -x "$SUB_BIN" ]] || err "subscriber binary missing at $SUB_BIN"
ok "subscriber built"

if [[ ! -f virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar ]] || \
   [[ -n "$(find virbius-control/src/main -newer virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar -print -quit 2>/dev/null)" ]]; then
  info "building virbius-control..."
  mvn -q -pl virbius-control -am package -DskipTests -Dskip.installnodenpm -Dskip.npm
fi
ok "control jar ready"

info "starting control on :$CONTROL_PORT ..."
env VIRBIUS_DATA_DIR="$DATA_DIR" \
    VIRBIUS_REDIS_URL="redis://127.0.0.1:${REDIS_PORT}" \
    SPRING_PROFILES_ACTIVE=dev \
    SERVER_PORT="$CONTROL_PORT" \
    java -jar virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar \
    >"$LOG_DIR/control.log" 2>&1 &
CONTROL_PID=$!
for _ in $(seq 1 60); do
  curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -sf "$BASE/api/v1/health" >/dev/null || { tail -30 "$LOG_DIR/control.log"; err "control not ready"; }
ok "control ready"

# ─── helpers ────────────────────────────────────────────────────────────────
api() { # method path [json-body] — fails loudly with the response body on HTTP errors
  local method=$1 path=$2 body=${3:-}
  local resp http_code payload
  if [[ -n "$body" ]]; then
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body")
  else
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path")
  fi
  http_code=$(tail -n1 <<<"$resp")
  payload=$(sed '$ d' <<<"$resp")
  if [[ "$http_code" -ge 400 ]]; then
    echo "API $method $path -> HTTP $http_code: $payload" >&2
    return 1
  fi
  if ! jq -e '.code == 0' >/dev/null 2>&1 <<<"$payload"; then
    echo "API $method $path -> error: $payload" >&2
    return 1
  fi
  echo "$payload"
}

create_falco_rule() { # rule_id output-text
  api POST "/api/v1/admin/tenants/$TENANT/rules" "{
    \"rule_id\": \"$1\", \"layer\": \"falco\", \"runtime\": \"falco\",
    \"reason_code\": \"WARNING\", \"intent_action\": \"deny\",
    \"body\": {\"condition\": \"evt.num > 0\", \"output\": \"$2\", \"tags\": \"e2e,gray\"}
  }" >/dev/null
}

rule_dry_run() { # rule_id
  api PATCH "/api/v1/admin/tenants/$TENANT/rules/$1/rollout" '{"rollout_state":"dry_run"}' >/dev/null
}

deploy_prepare() { # → deploy_id (bundle_id passed explicitly; omitting it hits a latent
  # nextVersion(null) bug where every finalize records 0.1.0 → PK conflict)
  api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" \
    '{"bundle_id":"default","layer":"falco","description":"e2e"}' \
    | jq -r '.data.deploy_id // .data.deployId'
}

deploy_op() { # deploy_id op
  api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$1/$2" '{}' >/dev/null
}

deploy_percent() { # deploy_id
  api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$1" \
    | jq -r '.data.canary_percent // .data.canaryPercent'
}

falco_stable_rev() {
  redis-cli -p "$REDIS_PORT" HGET "virbius:falco:pointer:$TENANT" stable_revision
}

wait_file_contains() { # dir needle timeout_sec label
  local dir=$1 needle=$2 timeout=${3:-15} label=$4
  for _ in $(seq 1 "$timeout"); do
    if [[ -f "$dir/$TENANT-active.yaml" ]] && grep -q "$needle" "$dir/$TENANT-active.yaml"; then
      ok "$label"
      return 0
    fi
    sleep 1
  done
  err "$label (timeout: $dir/$TENANT-active.yaml missing '$needle')"
}

wait_file_not_contains() { # dir needle timeout_sec label
  local dir=$1 needle=$2 timeout=${3:-15} label=$4
  for _ in $(seq 1 "$timeout"); do
    if [[ ! -f "$dir/$TENANT-active.yaml" ]] || ! grep -q "$needle" "$dir/$TENANT-active.yaml" 2>/dev/null; then
      ok "$label"
      return 0
    fi
    sleep 1
  done
  err "$label (timeout: $dir/$TENANT-active.yaml still contains '$needle')"
}

# ─── 1. baseline: v1 → stable on both nodes ─────────────────────────────────
info "1. baseline: rule v1 → gray 5→20→50→100 → finalize"
create_falco_rule "r-falco-e2e-1" "e2e baseline rule v1"
rule_dry_run "r-falco-e2e-1"

D1=$(deploy_prepare)
[[ -n "$D1" && "$D1" != "null" ]] || err "prepare failed"
for i in 1 2 3 4; do deploy_op "$D1" upgrade; done
P=$(deploy_percent "$D1")
[[ "$P" == "100" ]] || err "expected 100%, got $P"
deploy_op "$D1" finalize

REV1=$(falco_stable_rev)
[[ "$REV1" =~ ^[0-9]+$ && "$REV1" -gt 0 ]] || err "falco stable_revision not set (got '$REV1') — store wiring broken?"
ok "baseline promoted: stable_revision=$REV1"

info "starting subscribers (A=kernel-01.prod bucket15, B=node-b bucket93)"
env VIRBIUS_REDIS_URL="redis://127.0.0.1:${REDIS_PORT}" VIRBIUS_TENANT_ID="$TENANT" \
    VIRBIUS_NODE_ID="kernel-01.prod" VIRBIUS_FALCO_RULES_DIR="$RULES_A" \
    "$SUB_BIN" >"$LOG_DIR/sub-a.log" 2>&1 &
SUB_A_PID=$!
env VIRBIUS_REDIS_URL="redis://127.0.0.1:${REDIS_PORT}" VIRBIUS_TENANT_ID="$TENANT" \
    VIRBIUS_NODE_ID="node-b" VIRBIUS_FALCO_RULES_DIR="$RULES_B" \
    "$SUB_BIN" >"$LOG_DIR/sub-b.log" 2>&1 &
SUB_B_PID=$!

wait_file_contains "$RULES_A" "r-falco-e2e-1" 20 "node A boot-syncs stable@$REV1"
wait_file_contains "$RULES_B" "r-falco-e2e-1" 20 "node B boot-syncs stable@$REV1"

# ─── 2. gray v2: node-level isolation at 20% ────────────────────────────────
info "2. gray v2: add rule → 20% → A canary / B stable"
create_falco_rule "r-falco-e2e-2" "e2e canary rule v2"
rule_dry_run "r-falco-e2e-2"

D2=$(deploy_prepare)
deploy_op "$D2" upgrade            # 5%
deploy_op "$D2" upgrade            # 20%
P=$(deploy_percent "$D2")
[[ "$P" == "20" ]] || err "expected 20%, got $P"

wait_file_contains     "$RULES_A" "r-falco-e2e-2" 20 "node A (bucket15<20) runs CANARY with v2"
wait_file_not_contains "$RULES_B" "r-falco-e2e-2" 20 "node B (bucket93>=20) stays on STABLE (no v2)"
grep -q "r-falco-e2e-1" "$RULES_B/$TENANT-active.yaml" || err "node B lost v1 while stable"
ok "node B still has v1 content — node-level isolation holds"

# ─── 3. advance to 100% + finalize ──────────────────────────────────────────
info "3. advance 20→100 → finalize"
deploy_op "$D2" upgrade            # 50%
deploy_op "$D2" upgrade            # 100% (promotes falco)
P=$(deploy_percent "$D2")
[[ "$P" == "100" ]] || err "expected 100%, got $P"
REV2=$(falco_stable_rev)
[[ "$REV2" -gt "$REV1" ]] || err "promote at 100% did not bump stable_revision ($REV1 → $REV2)"

deploy_op "$D2" finalize
wait_file_contains "$RULES_A" "r-falco-e2e-2" 20 "node A converges to promoted stable@$REV2"
wait_file_contains "$RULES_B" "r-falco-e2e-2" 20 "node B converges to promoted stable@$REV2"

# ─── 4. rollback path ───────────────────────────────────────────────────────
info "4. gray v3 → 20% → rollback"
create_falco_rule "r-falco-e2e-3" "e2e rollback rule v3"
rule_dry_run "r-falco-e2e-3"

D3=$(deploy_prepare)
deploy_op "$D3" upgrade            # 5%
deploy_op "$D3" upgrade            # 20%
wait_file_contains     "$RULES_A" "r-falco-e2e-3" 20 "node A runs CANARY with v3"
wait_file_not_contains "$RULES_B" "r-falco-e2e-3" 20 "node B still stable (no v3)"

deploy_op "$D3" rollback
wait_file_not_contains "$RULES_A" "r-falco-e2e-3" 20 "node A falls back to stable@$REV2 after rollback"
grep -q "r-falco-e2e-2" "$RULES_A/$TENANT-active.yaml" || err "node A did not restore v2 content"
ok "node A restored v2 content"

echo ""
echo -e "${GREEN}===== falco node-gray E2E passed =====${NC}"
echo "  logs: $LOG_DIR"
