#!/usr/bin/env bash
# test-falco-full-chain-docker.sh
#
# Full-chain kernel-rule (falco layer) test on Docker Desktop for macOS:
#
#   Phase 0  preflight: docker/BTF, jars, musl subscriber binary, images
#   Phase 1  start redis + control + engine + falco (modern_ebpf) + subscriber
#   Phase 2  rule -> dry_run -> forced promote to full -> deploy -> finalize
#            (+ dry_run negative-control rule stays unscored)
#   Phase 3  subscriber applies rules file + SIGHUP -> falco real reload
#   Phase 4  trigger container writes /etc/shadow -> REAL ebpf alert
#            -> http_output -> engine 3-tier correlation -> falco_pending
#            (asserts dry_run alert observed but NOT scored)
#   Phase 5  P0 regression probes: escaped-quote condition truncation,
#            invalid priority from reason_code, falco reload failure
#   Phase 6  gate rejection (409 no-force / hard ban dry_run->full),
#            two-node gray split (buckets 41 vs 73), rollback reverts
#
# Everything (control/engine/redis/falco/subscriber) runs in Docker so the
# processes survive the invoking shell. Only API calls + assertions run on host.
#
# Prereqs: Docker Desktop running, jq, redis-cli, cargo (first run only).
# Java jars are (re)built automatically when missing or when sources changed.
#
# Usage:
#   ./scripts/test-falco-full-chain-docker.sh            # full run
#   ./scripts/test-falco-full-chain-docker.sh --cleanup  # tear everything down
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { echo -e "${CYAN}[STEP]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

TENANT="docker-e2e"
BASE="http://127.0.0.1:8080"
PROJECT="virbius-falco-e2e"
COMPOSE_DIR="scripts/falco-full-chain"
WORK="$(mktemp -d /tmp/falco-full-chain.XXXXXX)"
RULES_DIR="$WORK/rules"
mkdir -p "$RULES_DIR"
touch "$WORK/empty-rules.yaml"

COMPOSE=(docker compose -f "$COMPOSE_DIR/docker-compose.yml" --project-name "$PROJECT")
export WORK RULES_DIR TENANT

# pipefail + grep -q early-exit => `docker compose logs` dies with SIGPIPE
# (exit 141) and the pipeline reads as failure even when grep matched.
# Capture to a file first, then grep.
logs_have() { # service pattern
  "${COMPOSE[@]}" logs "$1" 2>&1 > "$WORK/.logs-$1.txt" 2>&1 || true
  grep -q "$2" "$WORK/.logs-$1.txt"
}

# ─── cleanup mode ───────────────────────────────────────────────────────────
if [[ "${1:-}" == "--cleanup" ]]; then
  WORK="$WORK" RULES_DIR="$RULES_DIR" TENANT="$TENANT" \
    "${COMPOSE[@]}" down -v --remove-orphans 2>/dev/null || true
  docker rm -f vtrigger vnode2 >/dev/null 2>&1 || true
  ok "compose project '$PROJECT' and trigger containers removed"
  exit 0
fi

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN} Falco kernel-rule FULL-CHAIN test on Docker Desktop (macOS) ${NC}"
echo -e "${CYAN}============================================================${NC}"
echo "  workdir: $WORK"

# ─── Phase 0: preflight ─────────────────────────────────────────────────────
info "0. preflight"

command -v jq >/dev/null || err "jq not found"
command -v redis-cli >/dev/null || err "redis-cli not found"
docker info >/dev/null 2>&1 || err "docker daemon not running"

# control/engine run from host-built jars (bind-mounted into plain JRE images).
# Build if missing or if any source file is newer than the jar.
need_build=false
for m in virbius-control virbius-engine; do
  JAR="$ROOT/$m/target/$m-0.1.0-SNAPSHOT.jar"
  if [[ ! -f "$JAR" ]] || [[ -n $(find "$ROOT/$m/src" -newer "$JAR" -name '*.java' -print -quit 2>/dev/null) ]]; then
    need_build=true
  fi
done
if $need_build; then
  info "building control/engine jars (mvn package -DskipTests)..."
  mvn -q package -DskipTests -pl virbius-control,virbius-engine -am
fi
ok "control/engine jars up to date"

BTF=$(docker run --rm --privileged --pid=host alpine:3.20 \
  sh -c 'test -f /sys/kernel/btf/vmlinux && echo YES || echo NO')
[[ "$BTF" == "YES" ]] || err "VM kernel lacks BTF — modern_ebpf cannot run"
ok "docker VM kernel exposes BTF (modern_ebpf capable)"

# musl subscriber binary (build if missing or rust sources changed)
SUB_SRC="${CARGO_TARGET_DIR:-$ROOT/target}/aarch64-unknown-linux-musl/debug/falco-config-subscriber"
need_sub=false
if [[ ! -x "$SUB_SRC" ]]; then
  need_sub=true
elif [[ -n $(find "$ROOT/virbius-kernel/src" -newer "$SUB_SRC" -name '*.rs' -print -quit 2>/dev/null) ]]; then
  need_sub=true
fi
if $need_sub; then
  info "cross-compiling subscriber (aarch64-unknown-linux-musl)..."
  rustup target add aarch64-unknown-linux-musl >/dev/null
  RUSTFLAGS="-C linker=rust-lld" \
    cargo build --target aarch64-unknown-linux-musl -p virbius-kernel --bin falco-config-subscriber
fi
cp "$SUB_SRC" "$COMPOSE_DIR/falco-config-subscriber"
ok "subscriber binary ready ($(du -h "$COMPOSE_DIR/falco-config-subscriber" | cut -f1) static musl)"

# K8s manifests are not loaded by compose — assert production ports/env here so
# bugs 1 and 6 cannot regress without this script noticing.
grep -q 'cluster.local:8082/api/internal/falco-alert' "$ROOT/virbius-kernel/deploy/falco-config.yaml" \
  || err "falco-config.yaml http_output must target engine :8082"
grep -q 'targetPort: 8082' "$ROOT/virbius-kernel/deploy/engine-service.yaml" \
  || err "engine-service.yaml must expose 8082"
grep -q 'VIRBIUS_TENANT_ID' "$ROOT/virbius-kernel/deploy/falco-daemonset.yaml" \
  || err "falco-daemonset.yaml must set VIRBIUS_TENANT_ID"
ok "k8s manifests: engine :8082 + VIRBIUS_TENANT_ID present"

info "pulling images (first run may take a few minutes)..."
"${COMPOSE[@]}" pull --quiet redis control engine falco
ok "images pulled"

# ─── Phase 1: start stack ───────────────────────────────────────────────────
info "1. fresh stack (down -v) for deterministic state, then start redis + control + engine"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
docker rm -f vtrigger vnode2 >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait redis >/dev/null
"${COMPOSE[@]}" up -d control engine >/dev/null

for i in $(seq 1 60); do
  C=$(curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 && echo OK || echo -n wait)
  E=$(curl -sf "http://127.0.0.1:8082/admin/health" >/dev/null 2>&1 && echo OK || echo -n wait)
  [[ "$C" == "OK" && "$E" == "OK" ]] && break
  sleep 3
  [[ $i == 60 ]] && { "${COMPOSE[@]}" logs control engine | tail -30; err "control/engine not healthy"; }
done
ok "control + engine healthy"

info "1b. start falco (modern_ebpf, real syscall observation)"
"${COMPOSE[@]}" up -d falco >/dev/null
FALCO_READY=false
for i in $(seq 1 45); do
  if "${COMPOSE[@]}" logs falco 2>&1 | grep -q "Starting health webserver"; then FALCO_READY=true; break; fi
  if "${COMPOSE[@]}" logs falco 2>&1 | grep -qiE "forcing termination|runtime error|fatal"; then break; fi
  sleep 2
done
"${COMPOSE[@]}" logs falco 2>&1 | grep -iE "modern|ebpf|driver" | head -5 | sed 's/^/    /'
$FALCO_READY || { "${COMPOSE[@]}" logs falco | tail -30; err "falco failed to start"; }
sleep 4  # falco can die right after webserver start (e.g. /host/proc missing)
if ! docker inspect "$PROJECT-falco-1" --format '{{.State.Running}}' | grep -q true; then
  "${COMPOSE[@]}" logs falco 2>&1 | tail -15 | sed 's/^/    /'
  err "falco started but terminated right after — check logs above"
fi
ok "falco running with modern_ebpf (REAL kernel observation in the VM)"

info "1c. start config-subscriber (pid=host -> SIGHUP can reach falco)"
"${COMPOSE[@]}" up -d --build subscriber >/dev/null
sleep 2
"${COMPOSE[@]}" logs subscriber 2>&1 | head -3 | sed 's/^/    /'
docker exec "$PROJECT-subscriber-1" which pgrep >/dev/null 2>&1 \
  && ok "subscriber up (pgrep available)" || warn "pgrep missing in subscriber image"

# Second node before the first deploy: needed to catch first-gray empty-stable (bug 2).
RULES_DIR_B="$WORK/rules-node2"
mkdir -p "$RULES_DIR_B"
docker rm -f vnode2 >/dev/null 2>&1 || true
docker run -d --name vnode2 \
  --network "${PROJECT}_default" \
  -e VIRBIUS_REDIS_URL=redis://redis:6379 \
  -e VIRBIUS_TENANT_ID="$TENANT" \
  -e VIRBIUS_NODE_ID=docker-node-2 \
  -e VIRBIUS_FALCO_RULES_DIR=/etc/falco/rules.d \
  -v "$RULES_DIR_B:/etc/falco/rules.d" \
  "${PROJECT}-subscriber" >/dev/null
ok "second kernel node up (docker-node-2, bucket 73; docker-node-1 bucket 41)"

# ─── helpers ────────────────────────────────────────────────────────────────
api() { # method path [body]
  local method=$1 path=$2 body=${3:-} resp http_code payload
  if [[ -n "$body" ]]; then
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H 'Content-Type: application/json' -d "$body")
  else
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path")
  fi
  http_code=$(tail -n1 <<<"$resp"); payload=$(sed '$ d' <<<"$resp")
  if [[ "$http_code" -ge 400 ]] || ! jq -e '.code == 0' >/dev/null 2>&1 <<<"$payload"; then
    echo "API $method $path -> HTTP $http_code: $payload" >&2; return 1
  fi
  echo "$payload"
}

create_rule() { # rule_id reason_code condition output
  api POST "/api/v1/admin/tenants/$TENANT/rules" "{
    \"rule_id\": \"$1\", \"layer\": \"falco\", \"runtime\": \"falco\",
    \"reason_code\": \"$2\", \"intent_action\": \"allow\",
    \"scope\": {\"description\": \"docker e2e\"},
    \"body\": {\"condition\": \"$3\", \"output\": \"$4\", \"tags\": \"e2e,docker\"}
  }" >/dev/null
  api PATCH "/api/v1/admin/tenants/$TENANT/rules/$1/rollout" '{"rollout_state":"dry_run"}' >/dev/null
}

promote_rule_full() { # rule_id — dry_run -> canary -> full (force: e2e has no time/data for gates)
  # dry_run -> full is a hard ban (not force-able), so step through canary.
  api PATCH "/api/v1/admin/tenants/$TENANT/rules/$1/rollout" \
    '{"rollout_state":"canary","canary_percent":5,"force":true,"comment":"e2e promote"}' >/dev/null
  api PATCH "/api/v1/admin/tenants/$TENANT/rules/$1/rollout" \
    '{"rollout_state":"full","force":true,"comment":"e2e promote"}' >/dev/null
}

deploy_to_full() { # -> deploy_id; drives prepare -> ladder -> 100% -> finalize
  local d p
  d=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" \
        '{"bundle_id":"default","layer":"falco","description":"docker e2e"}' \
      | jq -r '.data.deploy_id // .data.deployId')
  [[ -n "$d" && "$d" != "null" ]] || { echo "prepare failed" >&2; return 1; }
  for _ in $(seq 1 8); do
    p=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$d" | jq -r '.data.canary_percent // .data.canaryPercent')
    [[ "$p" == "100" ]] && break
    api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$d/upgrade" '{}' >/dev/null
  done
  [[ "$p" == "100" ]] || { echo "ladder stuck at $p%" >&2; return 1; }
  api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$d/finalize" '{}' >/dev/null
  echo "$d"
}

wait_file_contains() { # needle timeout label
  wait_file_contains_in "$RULES_DIR" "$1" "$2" "$3"
}

wait_file_contains_in() { # dir needle timeout label
  local dir=$1 needle=$2 timeout=${3:-30} label=$4
  for _ in $(seq 1 "$timeout"); do
    if [[ -f "$dir/$TENANT-active.yaml" ]] && grep -q "$needle" "$dir/$TENANT-active.yaml"; then
      ok "$label"; return 0
    fi
    sleep 1
  done
  err "$label (timeout: $dir/$TENANT-active.yaml missing '$needle')"
}

# ─── Phase 2: rule + rollout ────────────────────────────────────────────────
info "2. create rule -> dry_run -> full (forced) -> deploy prepare -> ladder -> 100% -> finalize"
create_rule "e2e_docker_shadow_write" "WARNING" \
  "evt.type in (open, openat, openat2) and fd.name=/etc/shadow and evt.is_open_write=true" \
  "E2E shadow write (pid=%proc.pid, file=%fd.name, cmd=%proc.cmdline)"
ok "rule e2e_docker_shadow_write in execution plane (dry_run)"

# Negative control for the dry_run scoring guard: stays in dry_run forever,
# its alerts must be observed but never scored.
create_rule "e2e_dryrun_negative" "WARNING" \
  "evt.type in (open, openat, openat2) and fd.name=/root/should_not_score.txt and evt.is_open_write=true" \
  "E2E dry-run negative (pid=%proc.pid)"

# Scoring requires canary/full; bundle deploys do not change per-rule rollout_state,
# so promote explicitly BEFORE the deploy compiles the ruleset.
promote_rule_full "e2e_docker_shadow_write"
ok "rule e2e_docker_shadow_write promoted to full (via canary, forced)"

D1=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" \
      '{"bundle_id":"default","layer":"falco","description":"docker e2e"}' \
    | jq -r '.data.deploy_id // .data.deployId')
[[ -n "$D1" && "$D1" != "null" ]] || err "prepare failed"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D1/upgrade" '{}' >/dev/null
P=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D1" | jq -r '.data.canary_percent // .data.canaryPercent')
[[ "$P" == "50" ]] || err "first deploy effective step expected 50%, got $P%"
# Bug 2: first gray used to wipe out-of-bucket nodes (stable_revision=0).
# After the fix both nodes must have the compiled rules (seeded stable == canary).
wait_file_contains "e2e_docker_shadow_write" 40 "first-gray canary node (bucket 41) has rules"
wait_file_contains_in "$RULES_DIR_B" "e2e_docker_shadow_write" 40 "first-gray stable node (bucket 73) kept baseline (not wiped)"
ok "first-gray: neither node was left with zero rules"
for _ in $(seq 1 8); do
  P=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D1" | jq -r '.data.canary_percent // .data.canaryPercent')
  [[ "$P" == "100" ]] && break
  api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D1/upgrade" '{}' >/dev/null
done
[[ "$P" == "100" ]] || err "ladder stuck at $P%"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D1/finalize" '{}' >/dev/null
ok "deploy $D1 reached 100% + finalized"

# ─── Phase 3: subscriber apply + real SIGHUP reload ─────────────────────────
info "3. verify subscriber applied rules and falco REALLY reloaded (SIGHUP)"
# falco does not log the literal string "SIGHUP" — it silently restarts and
# re-prints the boot sequence. Count "Starting health webserver" occurrences.
RELOAD_MARKER="Starting health webserver"
RELOADS_BEFORE=$("${COMPOSE[@]}" logs falco 2>&1 | grep -c "$RELOAD_MARKER" || true)
wait_file_contains "e2e_docker_shadow_write" 40 "rules file written: $RULES_DIR/$TENANT-active.yaml"

RELOADED=false
for _ in $(seq 1 15); do
  RELOADS_NOW=$("${COMPOSE[@]}" logs falco 2>&1 | grep -c "$RELOAD_MARKER" || true)
  [[ "$RELOADS_NOW" -gt "$RELOADS_BEFORE" ]] && { RELOADED=true; break; }
  sleep 1
done
if $RELOADED; then
  ok "falco restarted after SIGHUP (boot-sequence count $RELOADS_BEFORE -> $RELOADS_NOW)"
  "${COMPOSE[@]}" logs falco 2>&1 | grep "rules.d" | tail -1 | sed 's/^/    /'
else
  err "falco did not restart after rules apply — hot reload path broken"
fi

# ─── Phase 4: real trigger -> alert -> engine -> risk ───────────────────────
info "4. trigger REAL syscall alert and verify engine correlation + risk"
docker rm -f vtrigger >/dev/null 2>&1 || true
# clear stale scoring state from previous runs (redis container is reused)
redis-cli -p 6379 DEL "session:sess-docker-001:falco_pending" >/dev/null
docker run -d --name vtrigger --pid=host debian:bookworm-slim \
  sh -c 'sleep 8; echo "virbius-test::12345:0:99999:7:::" >> /etc/shadow; echo x >> /root/should_not_score.txt; sleep 2' >/dev/null
TPID=$(docker inspect -f '{{.State.Pid}}' vtrigger)
[[ "$TPID" =~ ^[0-9]+$ && "$TPID" -gt 1 ]] || err "could not resolve trigger VM pid"

# register pidmap exactly like pidmap.rs would (host_pid -> session)
redis-cli -p 6379 SET "pid_trace:$TPID" \
  "{\"host_pid\":$TPID,\"session_id\":\"sess-docker-001\",\"app_id\":\"docker-agent\",\"tenant_id\":\"$TENANT\"}" \
  EX 3600 >/dev/null
ok "pidmap registered: pid_trace:$TPID -> sess-docker-001 (trigger fires in ~8s)"

sleep 10  # trigger fires at 8s; poll loops below absorb falco->engine latency
# stdout evidence is nice-to-have; the authoritative signal is engine-side
# (http_output -> FalcoAlertController). falco stdout alerting has proven
# flaky in this containerized setup, so don't hard-fail on it.
if logs_have falco "e2e_docker_shadow_write"; then
  ok "FALCO ALERT on stdout (real ebpf observation):"
  grep "e2e_docker_shadow_write" "$WORK/.logs-falco.txt" | tail -1 | cut -c1-220 | sed 's/^/    /'
else
  warn "no alert line on falco stdout (known container quirk) — checking engine side instead"
fi
if logs_have falco "libcurl failed"; then
  err "falco http_output failed (libcurl) — alert never reached engine"
fi

# poll: main alert must be scored (falco->engine latency varies run to run)
PENDING=""
for _ in $(seq 1 30); do
  PENDING=$(redis-cli -p 6379 GET "session:sess-docker-001:falco_pending" 2>/dev/null || echo "")
  [[ "$PENDING" =~ ^[0-9]+$ && "$PENDING" -ge 1 ]] && break
  sleep 1
done
if [[ "$PENDING" =~ ^[0-9]+$ && "$PENDING" -ge 1 ]]; then
  ok "engine correlated alert -> session:sess-docker-001:falco_pending=$PENDING"
else
  warn "falco_pending='$PENDING' — checking engine log..."
  "${COMPOSE[@]}" logs engine 2>&1 | grep -i "falco" | tail -5 | sed 's/^/    /'
  err "engine did not count the alert"
fi
"${COMPOSE[@]}" logs engine 2>&1 | grep "falco alert received" | tail -1 | cut -c1-200 | sed 's/^/    /'

# dry_run negative: the second write must be observed but NOT scored.
# poll: the dry_run alert is a separate http POST and can land after the main one
DRY_OBSERVED=false
for _ in $(seq 1 30); do
  if logs_have engine "observed (dry_run, not scored).*rule=e2e_dryrun_negative"; then
    DRY_OBSERVED=true; break
  fi
  sleep 1
done
if $DRY_OBSERVED; then
  ok "dry_run rule alert observed without scoring (rule=e2e_dryrun_negative)"
else
  warn "no dry_run observe line in engine log — dumping recent falco logs"
  "${COMPOSE[@]}" logs engine 2>&1 | grep -i "falco" | tail -5 | sed 's/^/    /'
  err "dry_run alert was not handled as observe-only"
fi
# re-read AFTER both alerts were processed
PENDING=$(redis-cli -p 6379 GET "session:sess-docker-001:falco_pending" 2>/dev/null || echo "")
if [[ "$PENDING" == "1" ]]; then
  ok "falco_pending stayed 1 — dry_run alert did not increment the score"
else
  err "falco_pending=$PENDING, expected exactly 1 (dry_run alert leaked into scoring)"
fi

# ─── Phase 5: P0 regression probes ──────────────────────────────────────────
info "5. P0 probes: escaped-quote condition + invalid priority from reason_code"
# condition scoped to a nonexistent process so the probe never fires an alert
# storm (a bare "not startswith falco" matches every execve in the VM and
# floods falco's output queue / engine logs); the escaped-quote YAML assertion
# below only needs the startswith clause to survive compilation.
create_rule "e2e_badquote" "WARNING" \
  'evt.type=execve and proc.name=e2e_never_fires and not proc.name startswith \"falco\"' \
  "should not matter"
create_rule "e2e_badprio" "SENSITIVE_FILE_WRITE" \
  "evt.type=open and fd.name=/tmp/never" \
  "should not matter"
D2=$(deploy_to_full)
ok "deploy $D2 (with defective rules) promoted"
wait_file_contains "e2e_badquote" 40 "bad rules file applied by subscriber"

echo ""
echo -e "${YELLOW}── P0 evidence in generated YAML ($RULES_DIR/$TENANT-active.yaml) ──${NC}"
COND_LINE=$(grep -A0 "condition: evt.type=execve" "$RULES_DIR/$TENANT-active.yaml" || echo "(missing)")
echo "    badquote condition -> $COND_LINE"
# Fixed behavior: JSON parsing unescapes \" so the YAML carries the intact condition with
# plain quotes. Bug behavior: line truncated at the first escaped quote (ends with backslash)
# or the condition missing entirely.
if grep -q 'condition: evt.type=execve and proc.name=e2e_never_fires and not proc.name startswith "falco"$' "$RULES_DIR/$TENANT-active.yaml"; then
  ok "P0#2 not reproduced (condition intact)"
else
  warn "P0#2 CONFIRMED: escaped-quote condition truncated or missing"
fi
PRIO_LINE=$(grep -A5 "rule: e2e_badprio" "$RULES_DIR/$TENANT-active.yaml" | grep "priority:" || echo "(missing)")
echo "    badprio priority   -> $PRIO_LINE"
if echo "$PRIO_LINE" | grep -q "SENSITIVE_FILE_WRITE"; then
  warn "P0#3 CONFIRMED: reason_code leaked into falco priority (invalid)"
elif echo "$PRIO_LINE" | grep -q "priority: WARNING"; then
  ok "P0#3 not reproduced (invalid reason_code falls back to WARNING)"
else
  warn "P0#3 inconclusive: priority line '$PRIO_LINE'"
fi
sleep 3
if "${COMPOSE[@]}" logs falco 2>&1 | grep -qiE "error|invalid" ; then
  warn "falco rejected the new rules file (reload failure):"
  "${COMPOSE[@]}" logs falco 2>&1 | grep -iE "error|invalid" | tail -4 | sed 's/^/    /'
fi

# ─── Phase 6: multi-node canary split + gate rejection + rollback ───────────
info "6. node-gray split (bucket 41 vs 73) + gate 409 + rollback"

# 6a. gate probes (require NO active deploy): fresh rule in dry_run
create_rule "e2e_gate_probe" "WARNING" "evt.type=execve and proc.name=nevermind" "gate probe"
RESP=$(curl -s -w '\n%{http_code}' -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/e2e_gate_probe/rollout" \
  -H 'Content-Type: application/json' -d '{"rollout_state":"canary","canary_percent":5}')
CODE=$(tail -n1 <<<"$RESP")
if [[ "$CODE" == "409" ]] && grep -q "GATE_FAILED" <<<"$RESP"; then
  ok "gate rejected dry_run->canary without force (409 GATE_FAILED)"
else
  err "expected 409 GATE_FAILED for unforced promotion, got $CODE: $RESP"
fi
# hard ban: dry_run -> full must reject even WITH force. Two layers enforce it:
# the state machine validator (400) fires before the promotion gate (409).
RESP=$(curl -s -w '\n%{http_code}' -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/e2e_gate_probe/rollout" \
  -H 'Content-Type: application/json' -d '{"rollout_state":"full","force":true,"comment":"e2e emergency"}')
CODE=$(tail -n1 <<<"$RESP")
if [[ "$CODE" == "400" || "$CODE" == "409" ]] && grep -q "permanently forbidden" <<<"$RESP"; then
  ok "dry_run->full hard ban holds even with force ($CODE)"
else
  err "expected hard ban for forced dry_run->full, got $CODE: $RESP"
fi

# 6b. second kernel node already started in phase 1 (bug 2 first-gray coverage)
docker inspect vnode2 --format '{{.State.Running}}' 2>/dev/null | grep -q true \
  || err "vnode2 (docker-node-2) should still be running from phase 1"
ok "second kernel node still up (docker-node-2, bucket 73; docker-node-1 bucket 41)"

# 6c. deploy with a new rule; effective first step must be 50% (bucket 41 enters, 73 waits)
create_rule "e2e_split_probe" "WARNING" "evt.type=execve and proc.name=splitprobe" "split probe"
D3=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" \
      '{"bundle_id":"default","layer":"falco","description":"split test"}' | jq -r '.data.deploy_id')
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D3/upgrade" '{}' >/dev/null
P=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D3" | jq -r '.data.canary_percent')
[[ "$P" == "50" ]] || err "expected effective first step 50%, got $P%"
ok "ladder effective first step = 50% (bucket 41 enters canary, bucket 73 waits)"

SPLIT_OK=false
for _ in $(seq 1 30); do
  A_HAS=$(grep -q e2e_split_probe "$RULES_DIR/$TENANT-active.yaml" 2>/dev/null && echo Y || echo N)
  B_HAS=$(grep -q e2e_split_probe "$RULES_DIR_B/$TENANT-active.yaml" 2>/dev/null && echo Y || echo N)
  [[ "$A_HAS" == "Y" && "$B_HAS" == "N" ]] && { SPLIT_OK=true; break; }
  sleep 1
done
$SPLIT_OK || err "canary split wrong: node1 has=$A_HAS (want Y), node2 has=$B_HAS (want N)"
ok "SPLIT: canary node (bucket 41) runs new revision, stable node (bucket 73) keeps old"

CONV=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D3/convergence")
jq -e '.data.nodes[] | select(.node_id=="docker-node-1") | select(.expected_pool=="canary" and .converged==true)' <<<"$CONV" >/dev/null \
  || { echo "$CONV" | jq .; err "convergence: docker-node-1 should be canary+converged"; }
jq -e '.data.nodes[] | select(.node_id=="docker-node-2") | select(.expected_pool=="stable" and .converged==true)' <<<"$CONV" >/dev/null \
  || { echo "$CONV" | jq .; err "convergence: docker-node-2 should be stable+converged"; }
ok "convergence endpoint agrees: node1=canary/converged, node2=stable/converged"

# 6d. rollback: pointer cleared, canary node reverts to previous stable revision
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D3/rollback" '{}' >/dev/null
REVERTED=false
for _ in $(seq 1 30); do
  grep -q e2e_split_probe "$RULES_DIR/$TENANT-active.yaml" 2>/dev/null || { REVERTED=true; break; }
  sleep 1
done
$REVERTED || err "node1 did not revert to stable revision after rollback"
ok "rollback at 50%: canary node reverted to previous stable revision"

# 6e. rollback AFTER 100% (before finalize) must also revert — Falco promote is deferred
# to finalize (bug 3). Previously upgrade-to-100% already rewrote the stable pointer.
create_rule "e2e_full_rollback_probe" "WARNING" "evt.type=execve and proc.name=fullrollback" "full rollback probe"
D4=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" \
      '{"bundle_id":"default","layer":"falco","description":"100pct rollback"}' | jq -r '.data.deploy_id')
for _ in $(seq 1 8); do
  P=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D4" | jq -r '.data.canary_percent // .data.canaryPercent')
  [[ "$P" == "100" ]] && break
  api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D4/upgrade" '{}' >/dev/null
done
[[ "$P" == "100" ]] || err "D4 ladder stuck at $P%"
wait_file_contains "e2e_full_rollback_probe" 40 "100% canary applied on node1 (not yet finalized)"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$D4/rollback" '{}' >/dev/null
REVERTED100=false
for _ in $(seq 1 30); do
  grep -q e2e_full_rollback_probe "$RULES_DIR/$TENANT-active.yaml" 2>/dev/null || { REVERTED100=true; break; }
  sleep 1
done
$REVERTED100 || err "node1 kept 100% canary rules after rollback — Falco stable pointer was promoted too early"
ok "rollback at 100% (pre-finalize): node1 reverted to previous stable revision"
docker rm -f vnode2 >/dev/null 2>&1 || true

# ─── summary ────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN} FULL-CHAIN RESULT                                           ${NC}"
echo -e "${GREEN}============================================================${NC}"
echo "  [x] rule defined via control API (layer=falco)"
echo "  [x] compiled + gray rollout to 100% + finalized"
echo "  [x] subscriber wrote $TENANT-active.yaml"
echo "  [x] SIGHUP -> falco real reload (pid=host)"
echo "  [x] real ebpf alert on /etc/shadow write"
echo "  [x] engine pid correlation + falco_pending=$PENDING"
echo "  [x] dry_run alert observe-only (no scoring)"
echo "  [x] gate 409 without force / hard ban with force"
echo "  [x] node-gray split: bucket41=canary, bucket73=stable, rollback reverts"
echo "  [x] first-gray baseline kept on out-of-bucket node"
echo "  [x] rollback at 100% (pre-finalize) reverts Falco stable"
echo "  [*] P0 probes above (escaped-quote / priority)"
echo ""
echo "  inspect:  docker compose -f $COMPOSE_DIR/docker-compose.yml -p $PROJECT logs -f falco"
echo "  rules:    cat $RULES_DIR/$TENANT-active.yaml"
echo "  cleanup:  $0 --cleanup"
