#!/usr/bin/env bash
# test-sandbox-ops-e2e.sh
#
# Sandbox (Landlock / gVisor) operator flow then live MCP traffic.
# Config only via virbius-control admin APIs (same buttons as 运营台).
# Manifest check via Edge pull API (what the node actually GETs).
# Traffic only via 管 :8088 → 端 mcp-proxy. No redis-cli / sqlite3.
#
# Prereqs: Docker four-layer stack (virbius-4layer) already up.
#
# Usage:
#   export WORK=/tmp/virbius-four-layer RULES_DIR=/tmp/virbius-four-layer/rules TENANT=default
#   bash scripts/test-sandbox-ops-e2e.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
GATEWAY="${VIRBIUS_GATEWAY:-http://127.0.0.1:8088}"
TENANT="${VIRBIUS_TENANT:-default}"
BUNDLE="${VIRBIUS_BUNDLE:-default}"
PROJECT="${VIRBIUS_COMPOSE_PROJECT:-virbius-4layer}"
WORK="${WORK:-/tmp/virbius-four-layer}"
COMPOSE=(docker compose
  -f "$ROOT/scripts/falco-full-chain/docker-compose.yml"
  -f "$ROOT/scripts/local-four-layer/docker-compose.yml"
  -p "$PROJECT")

RUN="sbx$(date +%H%M%S)$$"
APP_ID="sbx-app-$RUN"
RID_EDGE="sbx_${RUN}_edge"
RID_LL="sbx_${RUN}_ll"
RID_GV="sbx_${RUN}_gv"
MARKER="/tmp/sbx-ops-${RUN}/*"
LICENSE_ID=""
trap cleanup_run EXIT

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { echo -e "${CYAN}[STEP]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

PASS=0; FAIL=0
assert() {
  local label="$1" okflag="$2"
  if [[ "$okflag" == "1" ]]; then ok "$label"; PASS=$((PASS+1))
  else warn "$label"; FAIL=$((FAIL+1)); fi
}

api() {
  local method=$1 path=$2 body=${3:-} resp http_code payload
  if [[ -n "$body" ]]; then
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$CONTROL$path" \
      -H 'Content-Type: application/json' -d "$body")
  else
    resp=$(curl -s -w '\n%{http_code}' -X "$method" "$CONTROL$path")
  fi
  http_code=$(tail -n1 <<<"$resp"); payload=$(sed '$d' <<<"$resp")
  if [[ "$http_code" -ge 400 ]] || ! jq -e '.code == 0' >/dev/null 2>&1 <<<"$payload"; then
    echo "API $method $path -> HTTP $http_code: $payload" >&2
    return 1
  fi
  echo "$payload"
}

admin() { api "$1" "/api/v1/admin/tenants/$TENANT$2" "${3:-}"; }

archive_rule() {
  curl -s -X PATCH "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$1/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null || true
}

cleanup_run() {
  info "cleanup rules/license"
  archive_rule "$RID_EDGE"
  archive_rule "$RID_LL"
  archive_rule "$RID_GV"
  if [[ -n "$LICENSE_ID" ]]; then
    curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/$LICENSE_ID/revoke" \
      -H 'Content-Type: application/json' -d '{"reason":"sbx e2e"}' >/dev/null || true
  fi
}

clear_active_deploy() {
  local d
  d=$(admin GET "/deploy-rollout/active" | jq -r '.data.deploy_id // .data.deployId // empty')
  if [[ -n "$d" && "$d" != "null" ]]; then
    admin POST "/deploy-rollout/$d/rollback" '{"note":"sbx e2e clear"}' >/dev/null || true
  fi
}

promote_full() {
  admin PATCH "/rules/$1/rollout" \
    '{"rollout_state":"canary","canary_percent":5,"force":true,"comment":"sbx e2e"}' >/dev/null
  admin PATCH "/rules/$1/rollout" \
    '{"rollout_state":"full","force":true,"comment":"sbx e2e"}' >/dev/null
}

# Edge node pull (not sqlite / not redis-cli). Keep raw bytes — echo would drop trailing newlines.
pull_manifest_file() {
  local pool="${1:-stable}" dest="${2:?}"
  curl -sf "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP_ID/manifest?pool=$pool" -o "$dest"
}

pull_manifest() {
  local f="$WORK/sbx-manifest-${1:-stable}.json"
  pull_manifest_file "${1:-stable}" "$f" || return 1
  cat "$f"
}

manifest_sha_matches_policy_version() {
  local f="$WORK/sbx-manifest-stable.json"
  [[ -f "$f" ]] || return 1
  local got exp
  got=$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$f")
  exp=$(curl -sf "$CONTROL/api/v1/edge/tenants/$TENANT/apps/$APP_ID/policy-version" \
    | jq -r '.content_sha256 // .data.content_sha256 // empty')
  [[ -n "$exp" && "$got" == "$exp" ]]
}

manifest_has_marker() {
  local raw=$1
  echo "$raw" | MARKER="$MARKER" python3 -c '
import json, os, sys
d = json.load(sys.stdin)
m = os.environ["MARKER"]
for p in d.get("landlock_profiles") or []:
    if m in (p.get("read_paths") or []):
        sys.exit(0)
sys.exit(1)
'
}

mcp() {
  GATEWAY="$GATEWAY" TENANT="$TENANT" python3 - "$@" <<'PY'
import json, os, re, sys, threading, time, urllib.request
gw = os.environ["GATEWAY"].rstrip("/")
tenant = os.environ["TENANT"]
jwt, app_id, method = sys.argv[1], sys.argv[2], sys.argv[3]
params = json.loads(sys.argv[4]) if len(sys.argv) > 4 else {}
got, sid, ready = {}, {"v": None}, threading.Event()

def reader():
    req = urllib.request.Request(gw + "/sse", headers={"Accept": "text/event-stream"})
    with urllib.request.urlopen(req, timeout=45) as resp:
        event, data_lines = None, []
        for raw in resp:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if line.startswith("event:"):
                event = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].lstrip())
            elif line == "":
                data = "\n".join(data_lines)
                data_lines, ev, event = [], event, None
                if ev == "endpoint" or "session_id=" in data:
                    m = re.search(r"session_id=([^&\s]+)", data)
                    if m:
                        sid["v"] = m.group(1)
                        ready.set()
                elif data:
                    try:
                        obj = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    if obj.get("id") is not None:
                        got[obj["id"]] = obj

threading.Thread(target=reader, daemon=True).start()
if not ready.wait(12):
    print(json.dumps({"error": {"message": "no SSE session"}})); sys.exit(2)

def post(i, meth, par):
    body = json.dumps({"jsonrpc": "2.0", "id": i, "method": meth, "params": par}).encode()
    req = urllib.request.Request(
        f"{gw}/messages/?session_id={sid['v']}", data=body,
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=20) as r:
        if r.status not in (200, 202):
            raise RuntimeError(f"POST {meth} -> {r.status}")

def wait(i, sec=25):
    deadline = time.time() + sec
    while i not in got and time.time() < deadline:
        time.sleep(0.05)
    return got.get(i) or {"error": {"message": "timeout"}}

meta = {"app_id": app_id, "tenant_id": tenant}
if jwt and jwt != "-":
    meta["license_jwt"] = jwt
post(1, "initialize", {
    "protocolVersion": "2024-11-05",
    "capabilities": {},
    "clientInfo": {"name": "sbx-e2e", "version": "0"},
    "_meta": meta,
})
init = wait(1, 12)
if "result" not in init:
    print(json.dumps(init)); sys.exit(3)
post(2, method, params)
print(json.dumps(wait(2, 30)))
PY
}

recreate_proxy() {
  mkdir -p "$WORK"
  if [[ -d "$WORK/tenant.pem" ]]; then rmdir "$WORK/tenant.pem"; fi
  [[ -f "$WORK/tenant.pem" ]] || touch "$WORK/tenant.pem"
  export WORK RULES_DIR="${RULES_DIR:-$WORK/rules}" TENANT
  export VIRBIUS_APP_ID="$APP_ID"
  export VIRBIUS_CONTROL_BASE_URL="http://control:8080"
  export VIRBIUS_TENANT_ID="$TENANT"
  "${COMPOSE[@]}" up -d --no-deps --force-recreate proxy >/dev/null
  for _ in $(seq 1 25); do
    curl -sf "$GATEWAY/health" >/dev/null && return 0
    sleep 1
  done
  return 1
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  sed -n '2,16p' "$0"
  exit 0
fi

echo "===== sandbox ops e2e ====="
echo "  control: $CONTROL"
echo "  gateway: $GATEWAY"
echo "  app_id:  $APP_ID"
echo ""

command -v jq >/dev/null || err "jq required"
command -v python3 >/dev/null || err "python3 required"
mkdir -p "$WORK"
if [[ -d "$WORK/tenant.pem" ]]; then rmdir "$WORK/tenant.pem"; fi
touch "$WORK/tenant.pem"

info "0. preflight"
curl -sf "$CONTROL/api/v1/health" >/dev/null || err "control not ready"
curl -sf "$GATEWAY/health" >/dev/null || err "gateway/proxy not ready"
ok "control + 管 + 端 reachable"

info "1. ops: tools + License (execute_python landlock, exec_cmd gvisor)"
admin POST "/tools" '{"tool_name":"execute_python","risk_class":"high","sandbox_type":"landlock","timeout_ms":5000,"fast_path":false}' >/dev/null
admin POST "/tools" '{"tool_name":"exec_cmd","risk_class":"high","sandbox_type":"gvisor","timeout_ms":8000,"fast_path":false}' >/dev/null
ok "registered execute_python=landlock exec_cmd=gvisor"

ISSUE=$(admin POST "/licenses/issue" "$(jq -n --arg app "$APP_ID" \
  '{app_id:$app, agent_name:"sbx-e2e",
    allowed_tools:["execute_python","exec_cmd","check_query_scope"],
    risk_quota:80, tool_rate_limit:50, expiry_seconds:86400, description:"sbx e2e"}')")
LICENSE_ID=$(jq -r '.data.license_id' <<<"$ISSUE")
JWT=$(jq -r '.data.jwt' <<<"$ISSUE")
[[ -n "$JWT" && "$JWT" != "null" ]] || err "license issue returned no jwt"
PEM=$(admin GET "/licenses/public-key" | jq -r '.data.public_key_pem')
printf '%s\n' "$PEM" > "$WORK/tenant.pem"
ok "issued license $LICENSE_ID"

info "2. ops: edge service-bind + sandbox Landlock/gVisor (draft → dry_run)"
clear_active_deploy
admin POST "/rules" "$(jq -n --arg id "$RID_EDGE" --arg app "$APP_ID" --arg b "$BUNDLE" \
  '{rule_id:$id, bundle_id:$b, layer:"edge", runtime:"lua-dsl",
    reason_code:"INFO", risk_score:0, intent_action:"allow",
    scope:{bind_scope:"service", bind_ref:{app_ids:[$app]}},
    body:{keywords:["__never_match_sbx_ops__"], list_type:"deny"}}')" >/dev/null
admin PATCH "/rules/$RID_EDGE/rollout" '{"rollout_state":"dry_run"}' >/dev/null

admin POST "/rules" "$(jq -n --arg id "$RID_LL" --arg b "$BUNDLE" --arg m "$MARKER" \
  '{rule_id:$id, bundle_id:$b, layer:"sandbox", runtime:"landlock",
    reason_code:"SBX_OPS_LL", risk_score:0, intent_action:"allow",
    scope:{bind_scope:"global"},
    body:{tool_name:"execute_python",
          read_paths:[$m, "/usr/*", "/bin/*", "/tmp/*", "/lib/*", "/lib64/*",
                      "/etc/ld.so.cache", "/etc/localtime", "/etc/nsswitch.conf",
                      "/proc/self/exe", "/proc/self/maps", "/dev/urandom"],
          write_paths:["/tmp/*"],
          exec_paths:["/usr/bin/*", "/bin/*", "/usr/local/bin/*"]}}')" >/dev/null
admin POST "/rules" "$(jq -n --arg id "$RID_GV" --arg b "$BUNDLE" \
  '{rule_id:$id, bundle_id:$b, layer:"sandbox", runtime:"gvisor",
    reason_code:"SBX_OPS_GV", risk_score:0, intent_action:"allow",
    scope:{bind_scope:"global"},
    body:{runsc_path:"/opt/virbius/bin/runsc", rootfs_path:"/opt/virbius/rootfs",
          min_warm:1, max_idle:2, memory_limit_bytes:67108864, cpu_quota:0.5,
          network_disabled:true, exec_timeout_ms:5000}}')" >/dev/null
admin PATCH "/rules/$RID_LL/rollout" '{"rollout_state":"dry_run"}' >/dev/null
admin PATCH "/rules/$RID_GV/rollout" '{"rollout_state":"dry_run"}' >/dev/null
ok "sandbox rules dry_run"

info "3. ops: prepare layer=edge while dry_run — profiles must not ship"
VER=$(admin GET "/deploy-rollout/next-version?bundle_id=$BUNDLE" | jq -r '.data.version')
DID=$(admin POST "/deploy-rollout/prepare" "$(jq -n --arg v "$VER" --arg b "$BUNDLE" \
  '{bundle_id:$b, bundle_version:$v, layer:"edge", description:"sbx dry_run must not ship"}')" \
  | jq -r '.data.deploy_id // .data.deployId')
[[ -n "$DID" && "$DID" != "null" ]] || err "prepare (dry_run) no deploy_id"
CANARY=$(pull_manifest canary || true)
if [[ -z "$CANARY" ]]; then
  warn "canary manifest 404 — try stable"
  CANARY=$(pull_manifest stable || echo '{}')
fi
if echo "$CANARY" | jq -e . >/dev/null 2>&1 && manifest_has_marker "$CANARY"; then
  assert "dry_run Landlock marker 不得出现在 Edge 制品" 0
else
  assert "dry_run Landlock marker 不在 Edge 制品" 1
fi
GPATH=$(echo "$CANARY" | jq -r '.gvisor_config.runsc_path // empty' 2>/dev/null || echo "")
[[ -z "$GPATH" ]] && assert "dry_run gvisor_config 为空" 1 || assert "dry_run gvisor_config 为空 (got $GPATH)" 0
admin POST "/deploy-rollout/$DID/rollback" '{"note":"sbx rollback dry_run"}' >/dev/null
ok "rolled back dry_run edge deploy"

info "4. ops: dry_run → full 硬禁"
RESP=$(curl -s -w '\n%{http_code}' -X PATCH \
  "$CONTROL/api/v1/admin/tenants/$TENANT/rules/$RID_GV/rollout" \
  -H 'Content-Type: application/json' \
  -d '{"rollout_state":"full","force":true,"comment":"should reject"}')
BODY=$(sed '$d' <<<"$RESP")
echo "$BODY" | grep -qi "dry_run -> full\|permanently forbidden" \
  && assert "hard-ban dry_run→full" 1 \
  || assert "hard-ban dry_run→full (got $BODY)" 0

info "5. ops: canary→full + prepare/upgrade/finalize layer=edge"
promote_full "$RID_EDGE"
promote_full "$RID_LL"
promote_full "$RID_GV"
VER=$(admin GET "/deploy-rollout/next-version?bundle_id=$BUNDLE" | jq -r '.data.version')
DID=$(admin POST "/deploy-rollout/prepare" "$(jq -n --arg v "$VER" --arg b "$BUNDLE" \
  '{bundle_id:$b, bundle_version:$v, layer:"edge", description:"sbx full sandbox"}')" \
  | jq -r '.data.deploy_id // .data.deployId')
[[ -n "$DID" && "$DID" != "null" ]] || err "prepare (full) no deploy_id"
for _ in $(seq 1 8); do
  p=$(admin GET "/deploy-rollout/$DID" | jq -r '.data.canary_percent // .data.canaryPercent')
  [[ "$p" == "100" ]] && break
  admin POST "/deploy-rollout/$DID/upgrade" '{}' >/dev/null
done
[[ "$p" == "100" ]] || err "edge ladder stuck at $p%"
admin POST "/deploy-rollout/$DID/finalize" '{}' >/dev/null
ok "edge deploy $DID finalized"

STABLE=$(pull_manifest stable)
echo "$STABLE" | jq -e . >/dev/null || err "stable Edge pull 不是 JSON"
manifest_has_marker "$STABLE" \
  && assert "运营台 full 后 Edge pull 含 Landlock marker" 1 \
  || assert "运营台 full 后 Edge pull 含 Landlock marker" 0
manifest_sha_matches_policy_version \
  && assert "policy-version sha256 == Edge pull 字节" 1 \
  || assert "policy-version sha256 == Edge pull 字节" 0
echo "$STABLE" | jq -e --arg t execute_python \
  '[.tool_policies[]? | select(.tool_name==$t) | .sandbox_type] | any(.=="landlock")' >/dev/null \
  && assert "execute_python effective sandbox_type=landlock" 1 \
  || assert "execute_python effective sandbox_type=landlock" 0
GPATH=$(echo "$STABLE" | jq -r '.gvisor_config.runsc_path // empty')
[[ "$GPATH" == "/opt/virbius/bin/runsc" ]] \
  && assert "gvisor_config.runsc_path 来自运营台规则 body" 1 \
  || assert "gvisor_config.runsc_path 来自运营台规则 body (got '$GPATH')" 0

info "6. recreate 端节点 with APP_ID (real Edge sync from control)"
recreate_proxy || err "proxy did not come back"
if docker logs "${PROJECT}-proxy-1" 2>&1 | grep -q "manifest sha256 mismatch"; then
  assert "proxy Edge sync 未因 sha256 mismatch 丢弃清单" 0
else
  assert "proxy Edge sync 未因 sha256 mismatch 丢弃清单" 1
fi

info "7. traffic: Landlock execute_python via 管 :8088"
HELLO=$(mcp "$JWT" "$APP_ID" "tools/call" \
  '{"name":"execute_python","arguments":{"code":"print(\"sbx-landlock-ok\")"}}')
SBOX=$(echo "$HELLO" | jq -r '.result._meta.sandbox_used // empty')
DEG=$(echo "$HELLO" | jq -r '.result._meta.degraded // false')
TEXT=$(echo "$HELLO" | jq -r '.result.content[0].text // .error.message // empty')
[[ "$SBOX" == "landlock" && "$DEG" != "true" && "$TEXT" == *sbx-landlock-ok* ]] \
  && assert "execute_python sandbox_used=landlock text=sbx-landlock-ok" 1 \
  || assert "execute_python sandbox_used=landlock (got sbox=$SBOX deg=$DEG text=${TEXT:0:120})" 0

info "8. traffic: Landlock 拒绝读 /etc/shadow（规则未放行）"
DENY=$(mcp "$JWT" "$APP_ID" "tools/call" \
  '{"name":"execute_python","arguments":{"code":"print(open(\"/etc/shadow\").read(20))"}}')
DTEXT=$(echo "$DENY" | jq -r '.result.content[0].text // .error.message // empty')
DEXIT=$(echo "$DENY" | jq -r '.result._meta.exit_code // empty')
if echo "$DTEXT" | grep -qiE 'permission denied|errno 13|EACCES|error'; then
  assert "Landlock blocks /etc/shadow" 1
elif [[ "$DEXIT" != "0" && -n "$DEXIT" ]]; then
  assert "Landlock blocks /etc/shadow (exit=$DEXIT)" 1
else
  assert "Landlock blocks /etc/shadow (got ${DTEXT:0:160})" 0
fi

info "9. traffic: sandbox wall — sleep 30 必须在 timeout_ms=5000 内返回"
START=$SECONDS
TO=$(mcp "$JWT" "$APP_ID" "tools/call" \
  '{"name":"execute_python","arguments":{"code":"import time; time.sleep(30)"}}')
ELAPSED=$((SECONDS - START))
TMSG=$(echo "$TO" | jq -r '.error.message // .result.content[0].text // empty')
if echo "$TMSG" | grep -qi "sandbox_exec_timeout\|timeout"; then
  assert "sleep 返回 timeout (${ELAPSED}s)" 1
else
  assert "sleep 返回 timeout (${ELAPSED}s got ${TMSG:0:120})" 0
fi
(( ELAPSED < 20 )) && assert "wall << 20s (elapsed=${ELAPSED})" 1 \
  || assert "wall << 20s (elapsed=${ELAPSED})" 0

info "10. traffic: exec_cmd 要求 gVisor — 无 runsc 必须拒绝（不能 unsandboxed）"
GV=$(mcp "$JWT" "$APP_ID" "tools/call" \
  '{"name":"exec_cmd","arguments":{"command":"echo sbx-gvisor-ok"}}')
GS=$(echo "$GV" | jq -r '.result._meta.sandbox_used // empty')
GD=$(echo "$GV" | jq -r '.result._meta.degraded // false')
GERR=$(echo "$GV" | jq -r '.error.message // empty')
if [[ "$GS" == "gvisor" && "$GD" != "true" ]]; then
  assert "exec_cmd sandbox_used=gvisor" 1
elif echo "$GERR" | grep -qi "sandbox_unavailable"; then
  assert "exec_cmd 无 runsc → sandbox_unavailable（制品步骤 5 已验证）" 1
else
  assert "exec_cmd gVisor 或 sandbox_unavailable (got sbox=$GS err=${GERR:0:120})" 0
fi

echo ""
echo "===== summary: $PASS pass, $FAIL fail ====="
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
echo -e "${GREEN}沙箱运营台上线 + Edge 拉取 + Landlock 执行面跑通。${NC}"
exit 0
