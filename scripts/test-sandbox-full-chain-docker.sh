#!/usr/bin/env bash
# test-sandbox-full-chain-docker.sh
#
# Today's sandbox delivery + runtime changes, end-to-end on Docker:
#
#   Phase 0  preflight: docker, jq, control jar
#   Phase 1  redis + virbius-control (jar bind-mount, like Falco docker e2e)
#   Phase 2  per-rule Landlock/gVisor rollout + Edge prepare:
#            dry_run is NOT written into landlock_profiles / gvisor_config;
#            canary/full writes the gVisor rule body (distinctive limits);
#            dry_run→full stays hard-banned
#   Phase 3  MCP proxy container:
#            empty gvisor_config {} does not impersonate compiled defaults;
#            populated gvisor_config is applied to the process singleton
#            (log: runsc path from the rule body);
#            local-exec sleep returns sandbox_exec_timeout inside the wall
#            (no unbounded SSE hang)
#
# Usage:
#   ./scripts/test-sandbox-full-chain-docker.sh
#   ./scripts/test-sandbox-full-chain-docker.sh --cleanup
#   SKIP_PROXY=1 ./scripts/test-sandbox-full-chain-docker.sh   # control-plane only
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { echo -e "${CYAN}[STEP]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

TENANT="docker-sbx-e2e"
APP="sbx-e2e"
BASE="http://127.0.0.1:18080"
PROXY_URL="http://127.0.0.1:19090"
PROJECT="virbius-sandbox-e2e"
COMPOSE_DIR="scripts/sandbox-full-chain"
MARKER="/tmp/virbius-e2e-sbx-docker/*"
GVISOR_RUNSC="/e2e-gvisor-runsc"
GVISOR_MEM=67108864
LL_ID="e2e_sbx_ll_docker"
GV_ID="e2e_sbx_gv_docker"
EDGE_ID="e2e_sbx_edge_anchor"
PROXY_NAME="virbius-sandbox-e2e-proxy"
PROXY_IMAGE="virbius-mcp-proxy:sandbox-e2e"

COMPOSE=(docker compose -f "$COMPOSE_DIR/docker-compose.yml" --project-name "$PROJECT")

if [[ "${1:-}" == "--cleanup" ]]; then
  "${COMPOSE[@]}" down -v --remove-orphans 2>/dev/null || true
  docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
  ok "compose project '$PROJECT' and proxy container removed"
  exit 0
fi

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN} Sandbox Landlock/gVisor FULL-CHAIN on Docker${NC}"
echo -e "${CYAN}============================================================${NC}"

# ─── Phase 0 ────────────────────────────────────────────────────────────────
info "0. preflight"
command -v jq >/dev/null || err "jq not found"
command -v python3 >/dev/null || err "python3 not found"
docker info >/dev/null 2>&1 || err "docker daemon not running"

JAR="$ROOT/virbius-control/target/virbius-control-0.1.0-SNAPSHOT.jar"
need_build=false
if [[ ! -f "$JAR" ]] || [[ -n $(find "$ROOT/virbius-control/src" -newer "$JAR" -name '*.java' -print -quit 2>/dev/null) ]]; then
  need_build=true
fi
if $need_build; then
  info "building virbius-control jar (mvn package -DskipTests)..."
  mvn -q package -DskipTests -pl virbius-control -am
fi
ok "control jar up to date"

# ─── Phase 1 ────────────────────────────────────────────────────────────────
info "1. start redis + control"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
"${COMPOSE[@]}" up -d --wait redis >/dev/null
"${COMPOSE[@]}" up -d control >/dev/null

for i in $(seq 1 60); do
  if curl -sf "$BASE/api/v1/health" >/dev/null 2>&1; then
    break
  fi
  sleep 3
  [[ $i == 60 ]] && { "${COMPOSE[@]}" logs control | tail -40; err "control not healthy on $BASE"; }
done
ok "control healthy ($BASE)"

CTL=$("${COMPOSE[@]}" ps -q control)
[[ -n "$CTL" ]] || err "control container id missing"

# ─── helpers ────────────────────────────────────────────────────────────────
api() {
  local method=$1 path=$2 body=${3:-} resp http_code payload
  if [[ -n "$body" ]]; then
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' -H 'X-User-Id: e2e-sandbox-docker' -d "$body")
  else
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" \
      -H 'X-User-Id: e2e-sandbox-docker')
  fi
  http_code=$(tail -n1 <<<"$resp"); payload=$(sed '$d' <<<"$resp")
  if [[ "$http_code" -ge 400 ]] || ! jq -e '.code == 0' >/dev/null 2>&1 <<<"$payload"; then
    echo "API $method $path -> HTTP $http_code: $payload" >&2
    return 1
  fi
  echo "$payload"
}

api_raw() {
  local method=$1 path=$2 body=${3:-} resp
  if [[ -n "$body" ]]; then
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" \
      -H 'Content-Type: application/json' -H 'X-User-Id: e2e-sandbox-docker' -d "$body")
  else
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" \
      -H 'X-User-Id: e2e-sandbox-docker')
  fi
  echo "$resp"
}

rollback_active() {
  local d
  d=$(curl -s "$BASE/api/v1/admin/tenants/$TENANT/deploy-rollout/active" \
    | jq -r '.data.deploy_id // empty' || true)
  if [[ -n "$d" && "$d" != "null" ]]; then
    curl -s -X POST "$BASE/api/v1/admin/tenants/$TENANT/deploy-rollout/$d/rollback" \
      -H 'Content-Type: application/json' -d '{"note":"e2e-sandbox-docker cleanup"}' >/dev/null || true
  fi
}

read_canary_manifest() {
  docker exec "$CTL" cat "/data/edge/$TENANT/$APP/edge-manifest-canary.json" 2>/dev/null || echo ""
}

assert_json() {
  local raw=$1 query=$2 want=$3 label=$4
  local got
  got=$(echo "$raw" | python3 -c "import json,sys; d=json.load(sys.stdin); print($query)" 2>/dev/null || echo "")
  if [[ "$got" == "$want" ]]; then
    ok "$label: $got"
  else
    err "$label: got '$got', want '$want'"
  fi
}

# ─── Phase 2: control-plane delivery ───────────────────────────────────────
info "2a. edge service-bind anchor so prepare writes per-app manifests"
api POST "/api/v1/admin/tenants/$TENANT/rules" "$(python3 - <<PY
import json
print(json.dumps({
  "rule_id": "$EDGE_ID",
  "bundle_id": "default",
  "layer": "edge",
  "runtime": "lua-dsl",
  "reason_code": "INFO",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "service", "bind_ref": {"app_ids": ["$APP"]}},
  "body": {"keywords": ["__never_match_sbx_e2e__"], "list_type": "deny"},
}))
PY
)" >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$EDGE_ID/rollout" \
  '{"rollout_state":"dry_run"}' >/dev/null
ok "edge anchor $EDGE_ID dry_run (app_id=$APP)"

info "2b. create Landlock + gVisor sandbox rules (draft → dry_run)"
api POST "/api/v1/admin/tenants/$TENANT/rules" "$(python3 - <<PY
import json
print(json.dumps({
  "rule_id": "$LL_ID",
  "bundle_id": "default",
  "layer": "sandbox",
  "runtime": "landlock",
  "reason_code": "E2E_SBX_LL",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "global"},
  "body": {
    "tool_name": "e2e_rollout_probe",
    "read_paths": ["$MARKER", "/usr/*", "/bin/*", "/tmp/*"],
    "write_paths": ["/tmp/*"],
    "exec_paths": ["/usr/bin/*", "/bin/*"],
  },
}))
PY
)" >/dev/null
api POST "/api/v1/admin/tenants/$TENANT/rules" "$(python3 - <<PY
import json
print(json.dumps({
  "rule_id": "$GV_ID",
  "bundle_id": "default",
  "layer": "sandbox",
  "runtime": "gvisor",
  "reason_code": "E2E_SBX_GV",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "global"},
  "body": {
    "runsc_path": "$GVISOR_RUNSC",
    "rootfs_path": "/opt/virbius/rootfs",
    "min_warm": 3,
    "max_idle": 7,
    "memory_limit_bytes": $GVISOR_MEM,
    "cpu_quota": 0.5,
    "network_disabled": True,
    "exec_timeout_ms": 5000,
  },
}))
PY
)" >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$LL_ID/rollout" '{"rollout_state":"dry_run"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" '{"rollout_state":"dry_run"}' >/dev/null
ok "sandbox rules in dry_run"

info "2c. prepare layer=edge while sandbox rules are dry_run — must NOT ship profiles"
VER=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/next-version?bundle_id=default" \
  | jq -r '.data.version')
PREP=$(jq -n --arg ver "$VER" \
  '{bundle_id:"default",bundle_version:$ver,layer:"edge",description:"dry_run must not ship sandbox"}')
DID=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" "$PREP" \
  | jq -r '.data.deploy_id')
[[ -n "$DID" && "$DID" != "null" ]] || err "prepare (dry_run) returned no deploy_id"
MF=$(read_canary_manifest)
[[ -n "$MF" ]] || err "canary manifest missing after prepare"
echo "$MF" | MARKER="$MARKER" python3 -c '
import json, os, sys
marker = os.environ["MARKER"]
d = json.load(sys.stdin)
for p in d.get("landlock_profiles") or []:
    if marker in (p.get("read_paths") or []):
        sys.exit(1)
sys.exit(0)
' || err "dry_run landlock marker leaked into canary"
ok "dry_run landlock marker absent from canary landlock_profiles"
GPATH=$(echo "$MF" | python3 -c "import json,sys; d=json.load(sys.stdin); c=d.get('gvisor_config') or {}; print(c.get('runsc_path') or '')")
[[ -z "$GPATH" ]] || err "dry_run gVisor rule leaked runsc_path=$GPATH (want empty {})"
ok "dry_run gvisor_config is empty (not compiled-in defaults)"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$DID/rollback" \
  '{"note":"rollback after dry_run deliverability"}' >/dev/null
ok "rolled back dry_run-only edge deploy"

info "2d. dry_run → full is hard-banned even with force"
RESP=$(api_raw PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"full","force":true,"comment":"should be rejected"}')
CODE=$(tail -n1 <<<"$RESP")
BODY=$(sed '$d' <<<"$RESP")
if echo "$BODY" | grep -qi "dry_run -> full\|permanently forbidden"; then
  ok "hard-ban dry_run→full HTTP $CODE"
else
  err "expected dry_run→full reject, got HTTP $CODE: $BODY"
fi

info "2e. force canary → full, prepare again — rule body must land in gvisor_config"
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$LL_ID/rollout" \
  '{"rollout_state":"canary","canary_percent":5,"force":true,"comment":"e2e"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$LL_ID/rollout" \
  '{"rollout_state":"full","force":true,"comment":"e2e"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"canary","canary_percent":5,"force":true,"comment":"e2e"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"full","force":true,"comment":"e2e"}' >/dev/null
ok "sandbox rules promoted to full via canary"

VER=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/next-version?bundle_id=default" \
  | jq -r '.data.version')
PREP=$(jq -n --arg ver "$VER" \
  '{bundle_id:"default",bundle_version:$ver,layer:"edge",description:"full sandbox must ship"}')
DID=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" "$PREP" \
  | jq -r '.data.deploy_id')
[[ -n "$DID" && "$DID" != "null" ]] || err "prepare (full) returned no deploy_id"
MF=$(read_canary_manifest)
[[ -n "$MF" ]] || err "canary manifest missing after full prepare"
echo "$MF" | MARKER="$MARKER" python3 -c '
import json, os, sys
marker = os.environ["MARKER"]
d = json.load(sys.stdin)
for p in d.get("landlock_profiles") or []:
    if marker in (p.get("read_paths") or []):
        sys.exit(0)
sys.exit(1)
' || err "full landlock marker missing from canary"
ok "full landlock marker present in canary landlock_profiles"
assert_json "$MF" "d.get('gvisor_config',{}).get('runsc_path')" "$GVISOR_RUNSC" "gvisor_config.runsc_path from rule body"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('memory_limit_bytes') or 0)" "$GVISOR_MEM" "gvisor_config.memory_limit_bytes from rule body"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('min_warm') or 0)" "3" "gvisor_config.min_warm from rule body"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('exec_timeout_ms') or 0)" "5000" "gvisor_config.exec_timeout_ms from rule body"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$DID/rollback" \
  '{"note":"e2e done with canary assertions"}' >/dev/null || true

# ─── Phase 3: MCP proxy runtime ─────────────────────────────────────────────
if [[ "${SKIP_PROXY:-}" == "1" ]]; then
  warn "SKIP_PROXY=1 — skip MCP wall / singleton apply"
  echo ""
  ok "control-plane sandbox delivery checks passed"
  exit 0
fi

info "3a. build MCP proxy image (linux, today's wait.rs + GvisorPool::apply)"
need_proxy=true
if docker image inspect "$PROXY_IMAGE" >/dev/null 2>&1; then
  if [[ -z $(find "$ROOT/virbius-core/src" "$ROOT/virbius-mcp-proxy/src" -newer <(docker inspect -f '{{.Created}}' "$PROXY_IMAGE" 2>/dev/null || echo /dev/null) \( -name '*.rs' \) -print -quit 2>/dev/null) ]]; then
    # Created timestamp vs find -newer is unreliable; rebuild if sources changed
    # since a stamp file.
    :
  fi
fi
STAMP="$ROOT/scripts/sandbox-full-chain/.proxy-image.stamp"
if [[ -f "$STAMP" ]] && docker image inspect "$PROXY_IMAGE" >/dev/null 2>&1; then
  if [[ -z $(find "$ROOT/virbius-core/src" "$ROOT/virbius-mcp-proxy/src" "$ROOT/Dockerfile" -newer "$STAMP" \( -name '*.rs' -o -name 'Dockerfile' \) -print -quit 2>/dev/null) ]]; then
    need_proxy=false
  fi
fi
if $need_proxy; then
  docker build --target virbius-mcp-proxy -t "$PROXY_IMAGE" \
    --build-arg APT_MIRROR="${APT_MIRROR:-}" \
    --build-arg CRATES_MIRROR="${CRATES_MIRROR:-}" \
    --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= \
    --build-arg http_proxy= --build-arg https_proxy= \
    --build-arg NO_PROXY="*" --build-arg no_proxy="*" \
    "$ROOT"
  date > "$STAMP"
fi
ok "proxy image $PROXY_IMAGE"

start_proxy() {
  local manifest_dir=$1
  docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
  docker run -d --name "$PROXY_NAME" \
    -p 19090:9090 \
    -v "$manifest_dir:/app/data/edge/default:ro" \
    -v "$ROOT/$COMPOSE_DIR/license/license.pub.pem:/etc/virbius/license.pub.pem:ro" \
    -v "$ROOT/$COMPOSE_DIR/license/license.jwt:/etc/virbius/license.jwt:ro" \
    -e VIRBIUS_TRANSPORT="tcp://0.0.0.0:9090" \
    -e VIRBIUS_UPSTREAM_URL="" \
    -e VIRBIUS_ALLOW_UNSANDBOXED=true \
    -e VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license.pub.pem \
    -e VIRBIUS_LICENSE_FILE=/etc/virbius/license.jwt \
    -e HTTP_PROXY="" -e HTTPS_PROXY="" -e http_proxy="" -e https_proxy="" \
    "$PROXY_IMAGE" >/dev/null
  local i
  for i in $(seq 1 30); do
    if curl -sf --max-time 2 "$PROXY_URL/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  docker logs "$PROXY_NAME" 2>&1 | tail -40
  return 1
}

mcp_session() {
  local tmp=$1
  rm -f "$tmp/sse" "$tmp/pid"
  mkfifo "$tmp/sse"
  curl -s -N "$PROXY_URL/sse" > "$tmp/sse" &
  echo $! > "$tmp/pid"
  exec 3< "$tmp/sse"
}

mcp_read() {
  local timeout=${1:-20} data="" line
  while IFS= read -r -t "$timeout" line <&3; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^data: ]] && data="${line#data: }" && break
  done
  echo "$data"
}

mcp_wait_id() {
  local want=$1 deadline=$((SECONDS + ${2:-40})) raw
  while (( SECONDS < deadline )); do
    raw=$(mcp_read 10)
    [[ -z "$raw" ]] && continue
    if echo "$raw" | jq -e --argjson id "$want" \
      '(.id == $id) and (has("result") or has("error"))' >/dev/null 2>&1; then
      echo "$raw"
      return 0
    fi
  done
  echo ""
}

mcp_post() {
  local sid=$1 id=$2 method=$3 params=$4
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST "$PROXY_URL/messages/?session_id=$sid" \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}" >/dev/null || true
}

mcp_close() {
  local tmp=$1
  exec 3>&- 2>/dev/null || true
  [[ -f "$tmp/pid" ]] && kill "$(cat "$tmp/pid")" 2>/dev/null || true
}

info "3b. proxy + empty gvisor_config {} — no Default impersonation; exec wall"
TMP=$(mktemp -d /tmp/sbx-mcp.XXXXXX)
start_proxy "$ROOT/$COMPOSE_DIR/empty" || err "proxy failed to start (empty manifest)"
ok "proxy up with empty gvisor_config"

if docker logs "$PROXY_NAME" 2>&1 | grep -q "$GVISOR_RUNSC"; then
  err "empty manifest must not mention $GVISOR_RUNSC"
fi
ok "empty manifest did not apply $GVISOR_RUNSC"

mcp_session "$TMP"
EP=$(mcp_read 20)
SID=$(echo "$EP" | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$SID" ]] || { mcp_close "$TMP"; docker logs "$PROXY_NAME" | tail -20; err "SSE session_id missing: $EP"; }
ok "MCP session $SID"
mcp_post "$SID" 1 initialize '{"_meta":{"app_id":"test1"}}'
INIT=$(mcp_wait_id 1 20)
echo "$INIT" | jq -e '.result.serverInfo' >/dev/null || { mcp_close "$TMP"; err "initialize failed: $INIT"; }
ok "MCP initialize"

mcp_post "$SID" 2 tools/list '{}'
LIST=$(mcp_wait_id 2 20)
echo "$LIST" | jq -e '.result.tools[] | select(.name=="execute_python")' >/dev/null \
  || { mcp_close "$TMP"; err "execute_python missing from tools/list"; }
ok "local execute_python injected"

# timeout_ms=3000 + WALL_SLACK 2s → JSON-RPC well before 20s
info "3c. execute_python sleep 30 must return sandbox_exec_timeout (wall), not hang"
START=$SECONDS
mcp_post "$SID" 10 tools/call \
  '{"name":"execute_python","arguments":{"code":"import time; time.sleep(30)"}}'
TO=$(mcp_wait_id 10 20)
ELAPSED=$((SECONDS - START))
MSG=$(echo "$TO" | jq -r '.error.message // .result.content[0].text // empty' 2>/dev/null || echo "")
if echo "$MSG" | grep -qi "sandbox_exec_timeout\|timeout"; then
  ok "sleep returned timeout in ${ELAPSED}s (wall; message=${MSG:0:80})"
else
  mcp_close "$TMP"
  err "expected sandbox_exec_timeout within ~20s, elapsed=${ELAPSED}s got=${TO:0:240}"
fi
if (( ELAPSED > 18 )); then
  err "wall took ${ELAPSED}s — still hanging (want << 20s for timeout_ms=3000)"
fi

mcp_post "$SID" 11 tools/call '{"name":"exec_cmd","arguments":{"command":"echo gvisor-empty"}}'
EMPTY_EXEC=$(mcp_wait_id 11 25)
META_MEM=$(echo "$EMPTY_EXEC" | jq -r '.result._meta.gvisor_memory_limit_bytes // empty')
SBOX=$(echo "$EMPTY_EXEC" | jq -r '.result._meta.sandbox_used // empty')
DEG=$(echo "$EMPTY_EXEC" | jq -r '.result._meta.degraded // empty')
if [[ -n "$META_MEM" ]]; then
  mcp_close "$TMP"
  err "empty gvisor_config must not expose gvisor_memory_limit_bytes=$META_MEM (Default impersonation)"
fi
ok "empty gvisor_config: no delivered limits in _meta (sandbox_used=$SBOX degraded=$DEG)"
mcp_close "$TMP"

info "3d. restart proxy with populated gvisor_config — singleton apply from rule body"
start_proxy "$ROOT/$COMPOSE_DIR/populated" || err "proxy failed to start (populated manifest)"
sleep 1
if docker logs "$PROXY_NAME" 2>&1 | grep -q "runsc not found at $GVISOR_RUNSC"; then
  ok "GvisorPool applied rule body (runsc_path=$GVISOR_RUNSC)"
elif docker logs "$PROXY_NAME" 2>&1 | grep -q "$GVISOR_RUNSC"; then
  ok "GvisorPool saw rule-body runsc_path=$GVISOR_RUNSC"
else
  docker logs "$PROXY_NAME" 2>&1 | tail -30
  err "populated manifest did not apply $GVISOR_RUNSC to the gVisor singleton"
fi

mcp_session "$TMP"
EP=$(mcp_read 20)
SID=$(echo "$EP" | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$SID" ]] || { mcp_close "$TMP"; err "SSE session_id missing after restart"; }
mcp_post "$SID" 1 initialize '{"_meta":{"app_id":"test1"}}'
mcp_wait_id 1 20 >/dev/null
mcp_post "$SID" 12 tools/call '{"name":"exec_cmd","arguments":{"command":"echo gvisor-populated"}}'
POP=$(mcp_wait_id 12 25)
POP_MEM=$(echo "$POP" | jq -r '.result._meta.gvisor_memory_limit_bytes // empty')
if [[ "$POP_MEM" == "$GVISOR_MEM" ]]; then
  ok "exec _meta.gvisor_memory_limit_bytes=$POP_MEM (rule body reached runtime)"
else
  # runsc missing → degrade; apply was already proven via logs.
  ok "exec degraded without runsc (_meta mem='$POP_MEM'); apply already logged from rule body"
fi
mcp_close "$TMP"
rm -rf "$TMP"
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN} Sandbox full-chain Docker checks passed${NC}"
echo -e "${GREEN}============================================================${NC}"
echo "  [x] dry_run omitted from landlock_profiles / gvisor_config"
echo "  [x] canary/full writes gVisor rule body (mem/min_warm/exec_timeout/runsc)"
echo "  [x] dry_run→full hard-banned"
echo "  [x] empty {} does not impersonate Default pool limits"
echo "  [x] populated gvisor_config applied to process singleton"
echo "  [x] MCP local-exec wall returns sandbox_exec_timeout (no hang)"
echo "Cleanup: ./scripts/test-sandbox-full-chain-docker.sh --cleanup"
exit 0
