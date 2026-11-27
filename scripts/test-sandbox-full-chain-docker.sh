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
#            local exec fails closed when runsc is absent
#   Phase 4  ops-console API only (no SQLite/Redis reads):
#            issue/read licenses, publish fallback_policy=audit_only, then
#            prove no/wildcard license is still denied by the canonical-grant
#            gate while an explicit grant passes the gate
#   Phase 5  bounded stdin write + concurrency guard: a test-only runsc stub
#            blocks on stdin; second concurrent call gets local_exec_busy and
#            the blocked call returns sandbox_exec_timeout inside the wall
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

RUN_ID="$(date +%s)-$$"
TENANT="docker-sbx-e2e"
APP="sbx-e2e"
CANON_APP="sbx-canon-$RUN_ID"
WILD_APP="sbx-wild-$RUN_ID"
BARE_APP="sbx-bare-$RUN_ID"
BASE="http://127.0.0.1:18080"
PROXY_URL="http://127.0.0.1:19090"
PROJECT="virbius-sandbox-e2e"
COMPOSE_DIR="scripts/sandbox-full-chain"
MARKER="/tmp/virbius-e2e-sbx-docker/*"
GVISOR_RUNSC="/e2e-gvisor-runsc"
FAILOVER_JSON='{"low_risk_fail_open":true,"high_risk_fail_closed":false,"engine_timeout_ms":1000}'
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

# Licenses are issued through the control (ops-console) API below — no local
# key material and no DB/Redis access. Only the public key is materialized as
# a file so the proxy can verify API-issued JWTs.
LICENSE_DIR="$(mktemp -d)"

APP_VER=$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
export APP_VER
JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
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

# ─── licenses via ops-console API (user flow) ──────────────────────────────
# All license state is created/read/revoked through the control HTTP API.
# Nothing here opens SQLite or Redis directly.
issue_license() {
  local tools_json=$1 app=$2 label=$3 resp jwt lic
  resp=$(api POST "/api/v1/admin/tenants/$TENANT/licenses/issue" \
    "$(jq -n --arg app "$app" --arg name "sbx-e2e-$label" --argjson tools "$tools_json" \
      '{app_id:$app,agent_name:$name,allowed_tools:$tools,risk_quota:100,tool_rate_limit:100,expiry_seconds:3600,description:"sandbox full-chain e2e"}')" \
    ) || return 1
  jwt=$(echo "$resp" | jq -r '.data.jwt // empty')
  lic=$(echo "$resp" | jq -r '.data.license_id // empty')
  [[ -n "$jwt" && -n "$lic" ]] || return 1
  printf '%s %s\n' "$jwt" "$lic"
}

read CANON_JWT CANON_LIC_ID < <(issue_license '["virbius__shell","virbius__python"]' "$CANON_APP" canonical) \
  || err "could not issue canonical-grant license via API"
read WILDCARD_JWT WILDCARD_LIC_ID < <(issue_license '[]' "$WILD_APP" wildcard) \
  || err "could not issue wildcard license via API"
read BARE_JWT BARE_LIC_ID < <(issue_license '["shell"]' "$BARE_APP" bare) \
  || err "could not issue bare-name-grant license via API"
[[ -n "$CANON_JWT" && -n "$WILDCARD_JWT" && -n "$BARE_JWT" ]] \
  || err "empty license JWT from API"
ok "canonical + wildcard + bare-grant licenses issued via ops-console API"

# Issuing a license generates the tenant signing key; the public half is then
# available through the API for the proxy to verify JWTs presented by agents.
api GET "/api/v1/admin/tenants/$TENANT/licenses/public-key" \
  | python3 -c 'import json,sys; open("'"$LICENSE_DIR"'/license.pub.pem","w").write(json.load(sys.stdin)["data"]["public_key_pem"])' \
  || err "could not read license public key via API"
[[ -s "$LICENSE_DIR/license.pub.pem" ]] || err "control returned empty license public key"
ok "license public key read via ops-console API"

LIC_LIST=$(api GET "/api/v1/admin/tenants/$TENANT/licenses/list")
echo "$LIC_LIST" | jq -e --arg id "$CANON_LIC_ID" \
  '.data[] | select(.license_id == $id or .licenseId == $id)' >/dev/null \
  || err "canonical license not visible in /licenses/list"
ok "issued license visible via /licenses/list"

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
  # Edge delivery is the ops-console API surface for the compiled manifest;
  # no direct reads of the control container's data volume.
  curl -s "$BASE/api/v1/edge/tenants/$TENANT/apps/$APP/manifest?pool=canary" || echo ""
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
    "scratch_bytes": 33554432,
    "tmp_bytes": 8388608,
    "pids_limit": 96,
    "nofile_limit": 256,
    "nproc_limit": 64,
    "enforce_cgroups": False,
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
assert_json "$MF" "int(d.get('gvisor_config',{}).get('scratch_bytes') or 0)" "33554432" "gvisor_config.scratch_bytes from rule body (P0)"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('tmp_bytes') or 0)" "8388608" "gvisor_config.tmp_bytes from rule body (P0)"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('pids_limit') or 0)" "96" "gvisor_config.pids_limit from rule body (P0)"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('nofile_limit') or 0)" "256" "gvisor_config.nofile_limit from rule body (P0)"
assert_json "$MF" "int(d.get('gvisor_config',{}).get('nproc_limit') or 0)" "64" "gvisor_config.nproc_limit from rule body (P0)"
assert_json "$MF" "d.get('gvisor_config',{}).get('enforce_cgroups')" "False" "gvisor_config.enforce_cgroups from rule body (P0)"
api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/$DID/rollback" \
  '{"note":"e2e done with canary assertions"}' >/dev/null || true

api PUT "/api/v1/admin/tenants/$TENANT/proxy-policy" \
  "$(jq -n --argjson fo "$FAILOVER_JSON" \
    '{session_ttl_secs:1800,fallback_policy:"minimum_privilege",failover:$fo}')" >/dev/null

info "2f. control-plane API: legacy bare-name compat is gone"
TOOLS_JSON=$(api GET "/api/v1/admin/tenants/default/tools")
echo "$TOOLS_JSON" | jq -e \
  '([.data[] | select(.tool_name == "virbius__shell")] | length) == 1' >/dev/null \
  || err "default tool registry must contain virbius__shell: $TOOLS_JSON"
echo "$TOOLS_JSON" | jq -e \
  '([.data[] | select(.tool_name == "exec_cmd")] | length) == 0' >/dev/null \
  || err "default tool registry must not contain legacy exec_cmd: $TOOLS_JSON"
ok "tool registry (via API): canonical present, exec_cmd absent"

CHAIN_RULE=$(api GET "/api/v1/admin/tenants/default/rules/chain-write-exec")
echo "$CHAIN_RULE" | jq -e '.data.body | contains("virbius__shell")' >/dev/null \
  || err "chain-write-exec rule must match virbius__shell: $CHAIN_RULE"
echo "$CHAIN_RULE" | jq -e '.data.body | contains("virbius__python")' >/dev/null \
  || err "chain-write-exec rule must match virbius__python: $CHAIN_RULE"
ok "default chain rule (via API) matches canonical local-exec names"

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
  local manifest_dir=$1; shift
  docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
  docker run -d --name "$PROXY_NAME" \
    --network "${PROJECT}_default" \
    -p 19090:9090 \
    -v "$manifest_dir:/app/data/edge/default:ro" \
    -v "$LICENSE_DIR/license.pub.pem:/etc/virbius/license.pub.pem:ro" \
    -e VIRBIUS_TRANSPORT="tcp://0.0.0.0:9090" \
    -e VIRBIUS_UPSTREAM_URL="" \
    -e VIRBIUS_LOCAL_EXEC_MODE="gvisor" \
    -e VIRBIUS_REDIS_URL="redis://redis:6379" \
    -e VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license.pub.pem \
    "$@" \
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
mcp_post "$SID" 1 initialize "$(jq -n --arg jwt "$CANON_JWT" --arg a "$CANON_APP" \
  '{_meta:{app_id:$a,tenant_id:"docker-sbx-e2e",license_jwt:$jwt}}')"
INIT=$(mcp_wait_id 1 20)
echo "$INIT" | jq -e '.result.serverInfo' >/dev/null || { mcp_close "$TMP"; err "initialize failed: $INIT"; }
ok "MCP initialize"

mcp_post "$SID" 2 tools/list '{}'
LIST=$(mcp_wait_id 2 20)
echo "$LIST" | jq -e '.result.tools[] | select(.name=="virbius__python")' >/dev/null \
  || { mcp_close "$TMP"; err "virbius__python missing from tools/list"; }
ok "local virbius__python advertised (license grant)"

info "3c. empty gvisor_config: local exec must fail closed (sandbox_unavailable)"
mcp_post "$SID" 10 tools/call '{"name":"virbius__shell","arguments":{"command":"echo gvisor-empty"}}'
EMPTY_EXEC=$(mcp_wait_id 10 20)
ESBOX=$(echo "$EMPTY_EXEC" | jq -r '.result._meta.sandbox_used // empty')
EERR=$(echo "$EMPTY_EXEC" | jq -r '.error.data.reason_code // .error.message // empty')
if [[ -n "$ESBOX" ]]; then
  mcp_close "$TMP"
  err "empty gvisor_config must NOT execute local code (sandbox_used=$ESBOX): $EMPTY_EXEC"
fi
if echo "$EERR" | grep -qi "sandbox_unavailable"; then
  ok "empty gvisor_config: sandbox_unavailable (fail-closed, not unsandboxed)"
else
  mcp_close "$TMP"
  err "expected sandbox_unavailable with empty gvisor_config, got: $EMPTY_EXEC"
fi
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
mcp_post "$SID" 1 initialize "$(jq -n --arg jwt "$CANON_JWT" --arg a "$CANON_APP" \
  '{_meta:{app_id:$a,tenant_id:"docker-sbx-e2e",license_jwt:$jwt}}')"
mcp_wait_id 1 20 >/dev/null
mcp_post "$SID" 12 tools/call '{"name":"virbius__shell","arguments":{"command":"echo gvisor-populated"}}'
POP=$(mcp_wait_id 12 25)
PBOX=$(echo "$POP" | jq -r '.result._meta.sandbox_used // empty')
PERR=$(echo "$POP" | jq -r '.error.data.reason_code // .error.message // empty')
if [[ -n "$PBOX" ]]; then
  # Only possible when the configured runsc path actually exists.
  ok "exec ran with sandbox_used=$PBOX (rule body reached runtime)"
elif echo "$PERR" | grep -qi "sandbox_unavailable"; then
  # The fixture runsc path is intentionally absent: apply is proven by the
  # log assertions above, and the call must fail closed, not degrade.
  ok "populated config with missing runsc: sandbox_unavailable (fail-closed)"
else
  mcp_close "$TMP"
  err "unexpected populated-config result: $POP"
fi
mcp_close "$TMP"
rm -rf "$TMP"
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true

# ─── Phase 4: authorization gate with audit_only, driven by ops-console API ──
# Real user flow: operator publishes fallback_policy=audit_only; an agent that
# presents no license (or only a wildcard license) must still be refused local
# code execution. The gate must fire before the pipeline can record an allow.
info "4a. publish fallback_policy=audit_only through the ops-console API"
api PUT "/api/v1/admin/tenants/$TENANT/proxy-policy" \
  "$(jq -n --argjson fo "$FAILOVER_JSON" \
    '{session_ttl_secs:3600,fallback_policy:"audit_only",failover:$fo}')" >/dev/null
PP=$(api GET "/api/v1/admin/tenants/$TENANT/proxy-policy")
assert_json "$PP" "d.get('data',{}).get('fallback_policy')" "audit_only" \
  "proxy-policy fallback_policy via API"
ok "audit_only fallback published and read back via API"

TMP=$(mktemp -d /tmp/sbx-gate.XXXXXX)
start_proxy "$ROOT/$COMPOSE_DIR/populated" || err "proxy failed to start (audit_only gate)"
sleep 2

gate_probe() {
  local label=$1 jwt=$2 app=$3 want_code=$4 want_reason=$5
  local d="$TMP/gate-$label" sid init resp got reason sbox
  mkdir -p "$d"
  mcp_session "$d"
  sid=$(mcp_read 20 | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
  [[ -n "$sid" ]] || { mcp_close "$d"; err "SSE session_id missing for $label"; }
  mcp_post "$sid" 1 initialize "$(jq -n --arg jwt "$jwt" --arg t "$TENANT" --arg a "$app" \
    '{_meta:{app_id:$a,tenant_id:$t} + (if $jwt=="" then {} else {license_jwt:$jwt} end)}')"
  init=$(mcp_wait_id 1 20)
  echo "$init" | jq -e '.result.serverInfo' >/dev/null \
    || { mcp_close "$d"; err "initialize failed for $label: $init"; }
  mcp_post "$sid" 2 tools/call '{"name":"virbius__shell","arguments":{"command":"echo gate"}}'
  resp=$(mcp_wait_id 2 25)
  mcp_close "$d"
  got=$(echo "$resp" | jq -r '.error.code // empty')
  reason=$(echo "$resp" | jq -r '.error.data.reason_code // empty')
  sbox=$(echo "$resp" | jq -r '.result._meta.sandbox_used // empty')
  [[ -z "$sbox" ]] || err "$label must not execute local code (sandbox_used=$sbox): $resp"
  if [[ "$got" == "$want_code" ]] && [[ "$reason" == "$want_reason" ]]; then
    ok "$label -> $got / $want_reason (audited as block, no allow)"
  else
    err "$label: got $got / $reason, want $want_code / $want_reason: $resp"
  fi
}

info "4b. no license + audit_only must be denied by the canonical-grant gate"
gate_probe "no-license" "" "$CANON_APP" "-32016" "local_exec_not_granted"

info "4c. wildcard license (empty allowed_tools) must also be denied"
gate_probe "wildcard" "$WILDCARD_JWT" "$WILD_APP" "-32016" "local_exec_not_granted"

info "4c2. bare-name grant must not authorize canonical local exec"
gate_probe "bare-grant" "$BARE_JWT" "$BARE_APP" "-32016" "local_exec_not_granted"

info "4d. explicit canonical grant passes the gate (fails closed at absent runsc)"
gate_probe "canonical" "$CANON_JWT" "$CANON_APP" "-32018" "sandbox_unavailable"

info "4d2. bare tool name is an ordinary upstream tool (no migration hint)"
BARE_NAME_DIR="$TMP/gate-bare-name"
mkdir -p "$BARE_NAME_DIR"
mcp_session "$BARE_NAME_DIR"
BARE_SID=$(mcp_read 20 | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$BARE_SID" ]] || { mcp_close "$BARE_NAME_DIR"; err "SSE session_id missing for bare-name probe"; }
mcp_post "$BARE_SID" 1 initialize '{"_meta":{"app_id":"test1","tenant_id":"docker-sbx-e2e"}}'
mcp_wait_id 1 20 >/dev/null
mcp_post "$BARE_SID" 3 tools/call '{"name":"shell","arguments":{"command":"echo bare"}}'
BARE_RESP=$(mcp_wait_id 3 20)
mcp_close "$BARE_NAME_DIR"
BARE_CODE=$(echo "$BARE_RESP" | jq -r '.error.code // empty')
BARE_MSG=$(echo "$BARE_RESP" | jq -r '.error.message // empty')
[[ "$BARE_CODE" == "-32602" ]] \
  || err "bare 'shell' should be an ordinary unknown upstream tool: $BARE_RESP"
if echo "$BARE_MSG" | grep -qiE 'legacy|migration|no longer execute locally'; then
  err "bare-name call must not carry a legacy migration hint: $BARE_MSG"
fi
ok "bare 'shell' -> unknown upstream tool (-32602) with no migration hint"

info "4e. operator restores minimum_privilege through the API"
api PUT "/api/v1/admin/tenants/$TENANT/proxy-policy" \
  "$(jq -n --argjson fo "$FAILOVER_JSON" \
    '{session_ttl_secs:1800,fallback_policy:"minimum_privilege",failover:$fo}')" >/dev/null
PP=$(api GET "/api/v1/admin/tenants/$TENANT/proxy-policy")
assert_json "$PP" "d.get('data',{}).get('fallback_policy')" "minimum_privilege" \
  "proxy-policy restored via API"
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true

# ─── Phase 5: bounded stdin write + concurrency guard under blocking exec ──
# A stand-in runsc accepts the OCI run but never reads the payload stream, so
# a large script fills the pipe and the stdin write would block forever without
# the new deadline. The fake lives only in this test harness.
info "5a. install test-only runsc stub that never drains stdin"
FAKE_RUNSC_DIR=$(mktemp -d /tmp/sbx-runsc.XXXXXX)
cat > "$FAKE_RUNSC_DIR/runsc" <<'EOF'
#!/bin/sh
case " $* " in
  *" run "*) exec sleep 60 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$FAKE_RUNSC_DIR/runsc"
ok "runsc stub ready (run -> sleep 60, delete -> exit 0)"

info "5b. proxy with concurrency=1 and a blocking gVisor container"
start_proxy "$ROOT/$COMPOSE_DIR/populated" \
  -v "$FAKE_RUNSC_DIR:/fake-runsc:ro" \
  -e VIRBIUS_RUNSC_PATH=/fake-runsc/runsc \
  -e VIRBIUS_GVISOR_ROOTFS=/tmp/virbius-rootfs \
  -e VIRBIUS_LOCAL_EXEC_CONCURRENCY=1 \
  -e VIRBIUS_LOCAL_EXEC_CODE_BYTES=200000 \
  || err "proxy failed to start (concurrency probe)"
sleep 2

TMP=$(mktemp -d /tmp/sbx-concurrency.XXXXXX)
mcp_session "$TMP"
SID=$(mcp_read 20 | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$SID" ]] || { mcp_close "$TMP"; err "SSE session_id missing for concurrency probe"; }
mcp_post "$SID" 1 initialize "$(jq -n --arg jwt "$CANON_JWT" --arg t "$TENANT" --arg a "$CANON_APP" \
  '{_meta:{app_id:$a,tenant_id:$t,license_jwt:$jwt}}')"
mcp_wait_id 1 20 >/dev/null

BIG_CODE=$(python3 -c 'print("x" * 150000)')
BIG_PARAM=$(jq -n --arg c "$BIG_CODE" '{name:"virbius__shell",arguments:{command:$c}}')
mcp_post "$SID" 20 tools/call "$BIG_PARAM"
mcp_post "$SID" 21 tools/call "$BIG_PARAM"
R20=""; R21=""
COLLECT_DEADLINE=$((SECONDS + 60))
while [[ -z "$R20" || -z "$R21" ]] && (( SECONDS < COLLECT_DEADLINE )); do
  RAW=$(mcp_read 10)
  [[ -z "$RAW" ]] && continue
  RID=$(echo "$RAW" | jq -r '.id // empty')
  case "$RID" in
    20) [[ -n "$R20" ]] || R20="$RAW" ;;
    21) [[ -n "$R21" ]] || R21="$RAW" ;;
  esac
done
mcp_close "$TMP"

BUSY=false
TIMEOUT=false
for r in "$R20" "$R21"; do
  c=$(echo "$r" | jq -r '.error.code // empty')
  m=$(echo "$r" | jq -r '.error.message // empty')
  s=$(echo "$r" | jq -r '.result._meta.sandbox_used // empty')
  [[ -z "$s" ]] || err "blocking exec must not report sandbox_used: $r"
  if [[ "$c" == "-32019" ]]; then BUSY=true; fi
  if [[ "$c" == "-32603" ]] && echo "$m" | grep -qi "sandbox_exec_timeout"; then TIMEOUT=true; fi
done
if $BUSY; then ok "second concurrent call rejected with local_exec_busy (-32019)"; else err "missing local_exec_busy: $R20 / $R21"; fi
if $TIMEOUT; then ok "blocked exec returned bounded sandbox_exec_timeout (stdin write deadline)"; else err "missing bounded stdin timeout: $R20 / $R21"; fi

docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
rm -rf "$FAKE_RUNSC_DIR"

# ─── Phase 6: P0 hardening — reconciler, honest _meta, cgroups flag ─────────
# Operator-API driven end to end: the rule body carries the P0 isolation
# fields (delivered via edge manifest API assertions in Phase 2e), and the
# proxy runs against a test-only runsc stub that can list/delete (reconciler)
# and execute (success path). No SQLite/Redis access anywhere.

info "6a. success-mode runsc stub (run→ok, list→stale ids, delete→ok)"
STUB_DIR=$(mktemp -d /tmp/sbx-runsc-p0.XXXXXX)
cat > "$STUB_DIR/runsc" <<'EOF'
#!/bin/sh
case " $* " in
  *" list "*)
    echo "ID       PID         STATUS    BUNDLE PATH"
    echo "virbius-shell-staleA - CREATED  /tmp/virbius-gvisor/virbius-shell-staleA"
    echo "virbius-shell-staleB - CREATED  /tmp/virbius-gvisor/virbius-shell-staleB"
    exit 0 ;;
  *" delete "*) exit 0 ;;
  *" run "*)
    cat >/dev/null 2>&1 || true
    echo "sandboxed-ok"
    exit 0 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "$STUB_DIR/runsc"
STALE_DIR=$(mktemp -d /tmp/sbx-stale.XXXXXX)
mkdir -p "$STALE_DIR/virbius-shell-staleA" "$STALE_DIR/virbius-shell-staleB"
echo stale > "$STALE_DIR/virbius-shell-staleA/config.json"
ok "stub + stale bundle dirs ready (mounted at the manifest's runsc/bundle paths)"

info "6b. startup reconciler must sweep a previous instance's leftovers"
start_proxy "$ROOT/$COMPOSE_DIR/populated" \
  -v "$STUB_DIR/runsc:/e2e-gvisor-runsc:ro" \
  -v "$STALE_DIR:/tmp/virbius-gvisor" \
  || err "proxy failed to start (P0 reconciler probe)"
sleep 2
if docker logs "$PROXY_NAME" 2>&1 | grep -q "reconciled 2 stale container(s)"; then
  ok "startup reconciler reported 2 stale containers cleaned"
else
  docker logs "$PROXY_NAME" 2>&1 | tail -20
  err "reconciler did not run/clean the stale containers at startup"
fi
LEFT=$(docker exec "$PROXY_NAME" ls -A /tmp/virbius-gvisor 2>/dev/null || true)
if echo "$LEFT" | grep -q "staleA\|staleB"; then
  err "stale bundle dirs survived reconcile: $LEFT"
fi
ok "stale bundle dirs removed from bundle_root"

info "6c. honest result _meta on a successful (stubbed) gVisor execution"
TMP=$(mktemp -d /tmp/sbx-p0meta.XXXXXX)
mcp_session "$TMP"
P0_SID=$(mcp_read 20 | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$P0_SID" ]] || { mcp_close "$TMP"; err "SSE session_id missing for P0 meta probe"; }
mcp_post "$P0_SID" 1 initialize "$(jq -n --arg jwt "$CANON_JWT" --arg t "$TENANT" --arg a "$CANON_APP" \
  '{_meta:{app_id:$a,tenant_id:$t,license_jwt:$jwt}}')"
mcp_wait_id 1 20 >/dev/null
mcp_post "$P0_SID" 30 tools/call '{"name":"virbius__shell","arguments":{"command":"echo p0"}}'
M=$(mcp_wait_id 30 25)
echo "$M" | jq -e '.result._meta.sandbox_used == "gvisor"' >/dev/null \
  || { mcp_close "$TMP"; err "expected gvisor execution via stub: $M"; }
echo "$M" | jq -e '.result.content[0].text | contains("sandboxed-ok")' >/dev/null \
  || { mcp_close "$TMP"; err "stub stdout missing (high-risk output is wrapped in trust_boundary): $M"; }
ok "stub stdout delivered inside the high-risk trust_boundary envelope"
echo "$M" | jq -e '.result._meta.exit_code == 0' >/dev/null \
  || { mcp_close "$TMP"; err "exit_code != 0: $M"; }
echo "$M" | jq -e '.result._meta.gvisor_limits_enforced == false' >/dev/null \
  || { mcp_close "$TMP"; err "limits_enforced must be false by default (no cgroups): $M"; }
echo "$M" | jq -e '.result._meta.gvisor_scratch_bytes == 33554432' >/dev/null \
  || { mcp_close "$TMP"; err "scratch_bytes not reported from rule body: $M"; }
echo "$M" | jq -e '.result._meta.gvisor_pids_limit == 96' >/dev/null \
  || { mcp_close "$TMP"; err "pids_limit not reported from rule body: $M"; }
echo "$M" | jq -e '.result._meta.gvisor_nofile_limit == 256' >/dev/null \
  || { mcp_close "$TMP"; err "nofile_limit not reported from rule body: $M"; }
ok "_meta: gvisor execution + honest limits_enforced=false + P0 field plumbing"

info "6d. virbius/status trimmed snapshot agrees with the result _meta"
mcp_post "$P0_SID" 31 'virbius/status' '{}'
ST=$(mcp_wait_id 31 15)
mcp_close "$TMP"
echo "$ST" | jq -e '.result.local_exec.limits_enforced == false' >/dev/null \
  || err "status limits_enforced must be false by default: $ST"
echo "$ST" | jq -e '.result.local_exec.scratch_bytes == 33554432' >/dev/null \
  || err "status scratch_bytes missing: $ST"
ok "virbius/status: limits_enforced=false + scratch plumbing (no admin surface needed)"

info "6e. operator flips enforce_cgroups=true via console rule upsert (API only)"
# Real console flow for editing a rule that is in the execution plane (the API
# blocks editing while canary/full — RuleService edit lock): roll back to
# dry_run, take the rule offline (disable), edit (upsert), then re-promote
# draft -> dry_run -> canary -> full. Every step goes through the ops API.
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"dry_run","comment":"e2e p0 rollback before edit"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"disabled","comment":"e2e p0 take offline before edit"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"draft","comment":"e2e p0 reopen draft for edit"}' >/dev/null
ok "full -> dry_run -> disabled -> draft (edit-lock honored, rule re-opened for edit)"
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
    "scratch_bytes": 33554432,
    "tmp_bytes": 8388608,
    "pids_limit": 96,
    "nofile_limit": 256,
    "nproc_limit": 64,
    "enforce_cgroups": True,
  },
}))
PY
)" >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"dry_run","comment":"e2e p0 re-arm"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"canary","canary_percent":5,"force":true,"comment":"e2e p0"}' >/dev/null
api PATCH "/api/v1/admin/tenants/$TENANT/rules/$GV_ID/rollout" \
  '{"rollout_state":"full","force":true,"comment":"e2e p0"}' >/dev/null
ok "edited rule re-promoted to full (draft -> dry_run -> canary -> full)"
VER=$(api GET "/api/v1/admin/tenants/$TENANT/deploy-rollout/next-version?bundle_id=default" \
  | jq -r '.data.version')
PREP=$(jq -n --arg ver "$VER" \
  '{bundle_id:"default",bundle_version:$ver,layer:"edge",description:"p0 enforce_cgroups"}')
DID=$(api POST "/api/v1/admin/tenants/$TENANT/deploy-rollout/prepare" "$PREP" \
  | jq -r '.data.deploy_id')
[[ -n "$DID" && "$DID" != "null" ]] || err "prepare (enforce_cgroups) returned no deploy_id"
MF=$(read_canary_manifest)
assert_json "$MF" "d.get('gvisor_config',{}).get('enforce_cgroups')" "True" \
  "delivered manifest carries enforce_cgroups=true after console upsert"
ok "console upsert delivers enforce_cgroups=true via the edge manifest API"

info "6f. proxy with enforce_cgroups=true: result _meta and status flip honestly"
docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
start_proxy "$ROOT/$COMPOSE_DIR/enforced" \
  -v "$STUB_DIR/runsc:/e2e-gvisor-runsc:ro" \
  -v "$STALE_DIR:/tmp/virbius-gvisor" \
  || err "proxy failed to start (enforced manifest)"
sleep 2
TMP=$(mktemp -d /tmp/sbx-p0enforced.XXXXXX)
mcp_session "$TMP"
EF_SID=$(mcp_read 20 | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$EF_SID" ]] || { mcp_close "$TMP"; err "SSE session_id missing for enforced probe"; }
mcp_post "$EF_SID" 1 initialize "$(jq -n --arg jwt "$CANON_JWT" --arg t "$TENANT" --arg a "$CANON_APP" \
  '{_meta:{app_id:$a,tenant_id:$t,license_jwt:$jwt}}')"
mcp_wait_id 1 20 >/dev/null
mcp_post "$EF_SID" 40 tools/call '{"name":"virbius__shell","arguments":{"command":"echo enforced"}}'
EM=$(mcp_wait_id 40 25)
echo "$EM" | jq -e '.result._meta.gvisor_limits_enforced == true' >/dev/null \
  || { mcp_close "$TMP"; err "enforced config must report limits_enforced=true: $EM"; }
ok "_meta.gvisor_limits_enforced=true under enforce_cgroups rule"
mcp_post "$EF_SID" 41 'virbius/status' '{}'
EST=$(mcp_wait_id 41 15)
mcp_close "$TMP"
echo "$EST" | jq -e '.result.local_exec.limits_enforced == true' >/dev/null \
  || err "status must agree with enforced config: $EST"
ok "virbius/status: limits_enforced=true (configured == reported)"

docker rm -f "$PROXY_NAME" >/dev/null 2>&1 || true
rm -rf "$STUB_DIR" "$STALE_DIR"

info "5c. revoke API-issued licenses through the ops-console API"
api POST "/api/v1/admin/tenants/$TENANT/licenses/$CANON_LIC_ID/revoke" \
  '{"reason":"sandbox e2e cleanup"}' >/dev/null
api POST "/api/v1/admin/tenants/$TENANT/licenses/$WILDCARD_LIC_ID/revoke" \
  '{"reason":"sandbox e2e cleanup"}' >/dev/null
api POST "/api/v1/admin/tenants/$TENANT/licenses/$BARE_LIC_ID/revoke" \
  '{"reason":"sandbox e2e cleanup"}' >/dev/null
ok "licenses revoked via API"

echo ""
echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN} Sandbox full-chain Docker checks passed${NC}"
echo -e "${GREEN}============================================================${NC}"
echo "  [x] dry_run omitted from landlock_profiles / gvisor_config"
echo "  [x] canary/full writes gVisor rule body (mem/min_warm/exec_timeout/runsc)"
echo "  [x] dry_run→full hard-banned"
echo "  [x] empty {} does not impersonate Default pool limits"
echo "  [x] populated gvisor_config applied to process singleton"
echo "  [x] licenses issued/read/revoked via ops-console API (no DB/Redis reads)"
echo "  [x] manifest assertions read from edge-delivery API (no container FS reads)"
echo "  [x] audit_only + no/wildcard/bare-grant license -> local_exec_not_granted (-32016)"
echo "  [x] canonical grant passes the gate and fails closed at sandbox"
echo "  [x] bare 'shell' is an ordinary upstream tool; no migration hint"
echo "  [x] tool registry + default chain rule (via API) use canonical names"
echo "  [x] concurrency=1 holds slot during blocking exec (local_exec_busy)"
echo "  [x] stdin write bounded -> sandbox_exec_timeout (no unbounded hang)"
echo "  [x] P0 rule-body fields delivered: scratch/tmp/pids/nofile/nproc/enforce_cgroups"
echo "  [x] startup reconciler sweeps stale containers + bundle dirs (log + FS check)"
echo "  [x] successful gVisor exec (stub) -> _meta limits_enforced=false by default"
echo "  [x] _meta/status plumb scratch_bytes/pids_limit/nofile_limit from rule body"
echo "  [x] console upsert delivers enforce_cgroups=true; _meta/status flip honestly"
echo "Cleanup: ./scripts/test-sandbox-full-chain-docker.sh --cleanup"
exit 0
