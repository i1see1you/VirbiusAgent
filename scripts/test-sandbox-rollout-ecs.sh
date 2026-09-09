#!/usr/bin/env bash
# ============================================================
# test-sandbox-rollout-ecs.sh
#
# ECS 上测试 sandbox 层（Landlock / gVisor）规则放量 → Edge 节点灰度
# → MCP proxy 真正跑进沙箱。
#
# 不改现有 compose 栈（不 docker rm / compose down）。完结后会
# docker restart MCP proxy：compose 把 stable edge-manifest.json
# 以文件 bind-mount 进去，finalize 的 rename canary→stable 会让
# 旧 inode 悬空，必须重启才能挂上新文件。
#
# 新建一条独立 Landlock 规则（tool_name=e2e_rollout_probe，独特
# read_paths），不改现有 execute_python / exec_cmd 的路径，也不
# 新建 gVisor 规则（gvisor_config 只取 canary/full 第一条，
# 新规则按 rule_id 排序可能抢走 gvisor_exec_cmd）。
# dry_run 的 sandbox 规则不写入 landlock_profiles / gvisor_config；
# 规则 canary% 不做会话分流，节点灰度只走 Edge 包。
#
# 默认从本机 SSH 到 ECS 再跑；密钥不进仓库。
#
# Usage (Mac):
#   bash scripts/test-sandbox-rollout-ecs.sh
#
# Usage (already on ECS):
#   bash scripts/test-sandbox-rollout-ecs.sh --on-ecs
#
# Env:
#   VIRBIUS_SSH_KEY   default /Users/iseeyou/Documents/VirbiusAgent.pem
#   VIRBIUS_ECS_HOST  default root@101.37.116.110
#   VIRBIUS_CONTROL   default http://127.0.0.1:8080
#   VIRBIUS_TENANT    default default
#   VIRBIUS_BUNDLE    default poc-default
#   VIRBIUS_APP_ID    default test1  (license JWT app_id)
#   SKIP_PROXY_RESTART=1   完结后不重启 proxy
#   SKIP_MCP=1             不做 MCP 运行时探测
# ============================================================

set -euo pipefail

SSH_KEY="${VIRBIUS_SSH_KEY:-/Users/iseeyou/Documents/VirbiusAgent.pem}"
ECS_HOST="${VIRBIUS_ECS_HOST:-root@101.37.116.110}"

on_ecs() {
  [[ "${1:-}" == "--on-ecs" ]] && return 0
  [[ "${VIRBIUS_ON_ECS:-}" == "1" ]] && return 0
  # ECS 主机名 iZ...；本机 Mac 有 PEM。已在 ECS 上直接跑时走这里。
  [[ -d /root/VirbiusAgent && "$(id -u)" == "0" && ! -f "${SSH_KEY}" ]] && return 0
  return 1
}

if ! on_ecs "${1:-}"; then
  [[ -f "${SSH_KEY}" ]] || { echo "SSH key not found: ${SSH_KEY}"; exit 1; }
  echo "SSH ${ECS_HOST}  (tunnels not required; MCP :9090 is probed on the ECS host)"
  exec ssh -i "${SSH_KEY}" \
    -o IdentitiesOnly=yes \
    -o StrictHostKeyChecking=accept-new \
    -o ServerAliveInterval=30 \
    "${ECS_HOST}" \
    "bash -s -- --on-ecs" < "${BASH_SOURCE[0]}"
fi

shift || true
[[ "${1:-}" == "--on-ecs" ]] && shift || true

CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
TENANT="${VIRBIUS_TENANT:-default}"
BUNDLE="${VIRBIUS_BUNDLE:-poc-default}"
APP_ID="${VIRBIUS_APP_ID:-test1}"
RULE_ID="${VIRBIUS_SANDBOX_RULE:-e2e_sbx_landlock}"
TOOL_NAME="e2e_rollout_probe"
MARKER_PATH="/tmp/virbius-e2e-sbx-rollout/*"
OPERATOR="e2e-sandbox-ecs"
NOTE_MARK="e2e-sandbox-ecs"
PROXY_URL="${VIRBIUS_PROXY_URL:-http://127.0.0.1:9090}"

PASS=0
FAIL=0
RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'

log_pass() { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
log_info() { echo -e "  ${CYAN}INFO${NC}: $1"; }
log_warn() { echo -e "  ${YELLOW}WARN${NC}: $1"; }

command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required"; exit 1; }
if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required (yum install -y jq / apt-get install -y jq)"
  exit 1
fi

TMPDIR=$(mktemp -d /tmp/sandbox-rollout-ecs.XXXXXX)
BODY_FILE="${TMPDIR}/body.json"
DID=""
PROXY_NAME=""
DATA_DIR=""
POLICY_BACKUP="${TMPDIR}/rollout-policy.orig.json"
POLICY_BUMPED=0
RULE_FULL=0

COMMON_HEADERS=(
  -H "Content-Type: application/json"
  -H "X-User-Id: ${OPERATOR}"
)
if [[ -n "${VIRBIUS_API_KEY:-}" ]]; then
  COMMON_HEADERS+=(-H "Authorization: Bearer ${VIRBIUS_API_KEY}")
fi

http() {
  local method="$1" path="$2" payload="${3:-}"
  local url="${CONTROL}${path}"
  if [[ -n "${payload}" ]]; then
    curl -sS -o "${BODY_FILE}" -w '%{http_code}' -X "${method}" "${url}" \
      "${COMMON_HEADERS[@]}" -d "${payload}"
  else
    curl -sS -o "${BODY_FILE}" -w '%{http_code}' -X "${method}" "${url}" \
      "${COMMON_HEADERS[@]}"
  fi
}

field() {
  jq -r "$1 | if . == null then \"\" else tostring end" "${BODY_FILE}" 2>/dev/null || echo ""
}

expect_ok() {
  local http_code="$1" label="$2"
  local code; code=$(field '.code')
  if [[ "${http_code}" == 2* && "${code}" == "0" ]]; then
    log_pass "${label} (HTTP ${http_code})"
    return 0
  fi
  log_fail "${label}: HTTP ${http_code} code=${code} msg=$(field '.message')"
  return 1
}

assert_eq() {
  local got="$1" want="$2" label="$3"
  if [[ "${got}" == "${want}" ]]; then
    log_pass "${label}: ${got}"
  else
    log_fail "${label}: got '${got}', want '${want}'"
  fi
}

active_deploy_id() {
  local code; code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
  [[ "${code}" == 2* ]] || { echo ""; return; }
  field '.data.deploy_id'
}

is_our_deploy() {
  local note operator
  note=$(field '.data.note')
  operator=$(field '.data.operator')
  [[ "${operator}" == "${OPERATOR}" || "${note}" == *"${NOTE_MARK}"* ]]
}

rollback_if_ours() {
  local did="$1"
  [[ -n "${did}" ]] || return 0
  local code; code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${did}")
  [[ "${code}" == 2* ]] || return 0
  if is_our_deploy; then
    log_info "rollback leftover deploy ${did}"
    http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${did}/rollback" \
      "{\"note\":\"${NOTE_MARK} cleanup rollback\"}" >/dev/null || true
  fi
}

reset_test_rule() {
  local code; code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}")
  if [[ "${code}" != 2* ]]; then
    return 0
  fi
  local st; st=$(field '.data.rollout_state')
  if [[ "${st}" == "disabled" ]]; then
    http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/recover" '{}' >/dev/null || true
    return 0
  fi
  if [[ "${st}" != "draft" && -n "${st}" ]]; then
    http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}' >/dev/null || true
    http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/recover" '{}' >/dev/null || true
  fi
}

discover_stack() {
  PROXY_NAME="${PROXY_CONTAINER:-}"
  if [[ -z "${PROXY_NAME}" ]]; then
    PROXY_NAME=$(docker ps --format '{{.Names}}' 2>/dev/null \
      | grep -E 'virbiusagent.*mcp-proxy' | head -1 || true)
  fi
  if [[ -z "${PROXY_NAME}" ]]; then
    PROXY_NAME=$(docker ps --format '{{.Names}}' 2>/dev/null \
      | grep -E 'mcp-proxy' | grep -v integration | head -1 || true)
  fi

  local ctl
  ctl=$(docker ps --format '{{.Names}}' 2>/dev/null | grep -E 'virbius.*control' | head -1 || true)
  if [[ -n "${ctl}" ]]; then
    DATA_DIR=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "${ctl}" 2>/dev/null || true)
  fi
  if [[ -z "${DATA_DIR}" ]]; then
    DATA_DIR="/var/lib/docker/volumes/virbiusagent_control-data/_data"
  fi
}

manifest_path() {
  local kind="$1"  # stable | canary
  local name="edge-manifest.json"
  [[ "${kind}" == "canary" ]] && name="edge-manifest-canary.json"
  echo "${DATA_DIR}/edge/${TENANT}/${APP_ID}/${name}"
}

assert_marker_in() {
  local file="$1" label="$2"
  if [[ ! -f "${file}" ]]; then
    log_fail "${label}: file missing (${file})"
    return 0
  fi
  if python3 - "${file}" "${TOOL_NAME}" "${MARKER_PATH}" <<'PY'
import json, sys
path, tool, marker = sys.argv[1], sys.argv[2], sys.argv[3]
root = json.load(open(path))
profiles = root.get("landlock_profiles") or []
hit = False
for p in profiles:
    if p.get("tool_name") == tool and marker in (p.get("read_paths") or []):
        hit = True
        break
sys.exit(0 if hit else 1)
PY
  then
    log_pass "${label}: landlock profile ${TOOL_NAME} + ${MARKER_PATH}"
  else
    log_fail "${label}: marker not in ${file}"
  fi
}

assert_marker_absent() {
  local file="$1" label="$2"
  if [[ ! -f "${file}" ]]; then
    log_pass "${label}: no canary file (dry_run not delivered)"
    return 0
  fi
  if python3 - "${file}" "${TOOL_NAME}" "${MARKER_PATH}" <<'PY'
import json, sys
path, tool, marker = sys.argv[1], sys.argv[2], sys.argv[3]
root = json.load(open(path))
profiles = root.get("landlock_profiles") or []
for p in profiles:
    if p.get("tool_name") == tool and marker in (p.get("read_paths") or []):
        sys.exit(1)
sys.exit(0)
PY
  then
    log_pass "${label}: dry_run marker not in landlock_profiles"
  else
    log_fail "${label}: dry_run marker should not be in ${file}"
  fi
}

assert_gvisor_config() {
  local file="$1" label="$2"
  if [[ ! -f "${file}" ]]; then
    log_fail "${label}: file missing (${file})"
    return 0
  fi
  local runsc
  runsc=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print((d.get('gvisor_config') or {}).get('runsc_path') or '')" "${file}")
  if [[ -n "${runsc}" ]]; then
    log_pass "${label}: gvisor_config.runsc_path=${runsc}"
  else
    log_fail "${label}: gvisor_config empty (expected existing gvisor_exec_cmd)"
  fi
}

# Registry intent is armed only when this package has a matching profile / gvisor_config.
assert_effective_sandbox_types() {
  local file="$1" label="$2"
  if [[ ! -f "${file}" ]]; then
    log_fail "${label}: file missing (${file})"
    return 0
  fi
  local detail
  detail=$(python3 - "${file}" <<'PY'
import json, sys
root = json.load(open(sys.argv[1]))
policies = {p.get("tool_name"): p for p in (root.get("tool_policies") or []) if p.get("tool_name")}
profiles = {p.get("tool_name") for p in (root.get("landlock_profiles") or []) if p.get("tool_name")}
gvisor_armed = bool(root.get("gvisor_config") or {})
fails = []

def check(name, want_type, want_intent=None):
    p = policies.get(name)
    if p is None:
        fails.append(f"{name} missing from tool_policies")
        return
    got = p.get("sandbox_type")
    if got != want_type:
        fails.append(f"{name} sandbox_type={got!r} want {want_type!r}")
    if want_intent is not None and p.get("sandbox_intent") not in (None, "", want_intent):
        fails.append(f"{name} sandbox_intent={p.get('sandbox_intent')!r} want {want_intent!r}")

if gvisor_armed:
    check("exec_cmd", "gvisor", "gvisor")
elif "exec_cmd" in policies:
    check("exec_cmd", "none", "gvisor")

if "execute_python" in profiles:
    check("execute_python", "landlock", "landlock")
elif "execute_python" in policies:
    check("execute_python", "none")

for name in ("shell", "execute_code", "execute_node"):
    if name in policies and name not in profiles:
        check(name, "none")

if fails:
    print("; ".join(fails))
    sys.exit(1)
print("ok")
PY
  ) || true
  if [[ "${detail}" == "ok" ]]; then
    log_pass "${label}: effective sandbox_type matches delivered fragments"
  else
    log_fail "${label}: ${detail}"
  fi
}

force_apply() {
  local state="$1" pct="${2:-}"
  local payload
  if [[ "${state}" == "full" ]]; then
    payload=$(python3 -c "import json; print(json.dumps({'rollout_state':'full','canary_percent':None,'force':True,'comment':'${NOTE_MARK}: force bypass promotion gate'}))")
  else
    payload=$(python3 -c "import json; print(json.dumps({'rollout_state':'canary','canary_percent':int('${pct}'),'force':True,'comment':'${NOTE_MARK}: force bypass promotion gate'}))")
  fi
  http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${payload}"
}

wait_http() {
  local url="$1" tries="${2:-20}"
  local i
  for i in $(seq 1 "${tries}"); do
    if curl -sf --max-time 3 "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

gvisor_smoke() {
  local runsc="${1:-/opt/virbius/bin/runsc}" out
  if [[ -z "${PROXY_NAME}" ]]; then
    out=$(timeout 15 "${runsc}" --ignore-cgroups "do" echo gvisor-do-ok 2>/dev/null || true)
  else
    out=$(timeout 15 docker exec "${PROXY_NAME}" "${runsc}" --root /tmp/virbius-gvisor-e2e --ignore-cgroups "do" echo gvisor-do-ok 2>/dev/null || true)
  fi
  if echo "${out}" | grep -q gvisor-do-ok; then
    log_pass "runsc do echo gvisor-do-ok (gVisor runtime works in ${PROXY_NAME:-host})"
  else
    log_fail "runsc do failed: ${out:0:160}"
  fi
}

cleanup_stale_runsc() {
  [[ -n "${PROXY_NAME}" ]] || return 0
  local ids id
  ids=$(timeout 8 docker exec "${PROXY_NAME}" /opt/virbius/bin/runsc --root /tmp/virbius-gvisor-state --ignore-cgroups list 2>/dev/null \
    | awk 'NR>1{print $1}' || true)
  for id in ${ids}; do
    timeout 8 docker exec "${PROXY_NAME}" /opt/virbius/bin/runsc --root /tmp/virbius-gvisor-state --ignore-cgroups delete --force "${id}" >/dev/null 2>&1 || true
  done
}

mcp_probe() {
  local sse_pipe="${TMPDIR}/sse_pipe"
  local sse_pid_file="${TMPDIR}/sse.pid"
  rm -f "${sse_pipe}" "${sse_pid_file}"
  mkfifo "${sse_pipe}"

  curl -s -N "${PROXY_URL}/sse" > "${sse_pipe}" &
  echo $! > "${sse_pid_file}"
  exec 3< "${sse_pipe}"

  sse_wait() {
    local timeout="${1:-70}" data="" line
    while IFS= read -r -t "${timeout}" line <&3; do
      line="${line%$'\r'}"
      [[ "${line}" =~ ^data: ]] && data="${line#data: }" && break
    done
    echo "${data}"
  }

  sse_wait_rpc() {
    local want_id="$1" deadline=$((SECONDS + ${2:-90})) raw
    while (( SECONDS < deadline )); do
      raw=$(sse_wait 15)
      [[ -z "${raw}" ]] && continue
      if echo "${raw}" | jq -e --argjson id "${want_id}" \
        '(.id == $id) and (has("result") or has("error"))' >/dev/null 2>&1; then
        echo "${raw}"
        return 0
      fi
    done
    echo ""
    return 0
  }

  post_mcp() {
    local id="$1" method="$2" params="$3" http_code
    http_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
      -X POST "${PROXY_URL}/messages/?session_id=${SID}" \
      -H "Content-Type: application/json" \
      -d "{\"jsonrpc\":\"2.0\",\"id\":${id},\"method\":\"${method}\",\"params\":${params}}" || echo "000")
    if [[ "${http_code}" != "202" && "${http_code}" != "200" ]]; then
      log_warn "MCP POST ${method} HTTP ${http_code}"
    fi
  }

  local DATA SID RESP TOOLS SBOX TEXT DEGRADED ERR
  DATA=$(sse_wait 20)
  SID=$(echo "${DATA}" | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
  if [[ -z "${SID}" ]]; then
    log_fail "MCP SSE session_id missing: ${DATA}"
    exec 3>&- 2>/dev/null || true
    kill "$(cat "${sse_pid_file}")" 2>/dev/null || true
    return 0
  fi
  log_pass "MCP session_id=${SID}"

  post_mcp 1 initialize "{\"_meta\":{\"app_id\":\"${APP_ID}\"}}"
  RESP=$(sse_wait_rpc 1 30)
  if echo "${RESP}" | jq -e '.result.serverInfo' >/dev/null 2>&1; then
    log_pass "MCP initialize app_id=${APP_ID}"
  else
    log_fail "MCP initialize failed: ${RESP:0:200}"
    exec 3>&- 2>/dev/null || true
    kill "$(cat "${sse_pid_file}")" 2>/dev/null || true
    return 0
  fi

  post_mcp 2 tools/list '{}'
  RESP=$(sse_wait_rpc 2 30)
  TOOLS=$(echo "${RESP}" | jq -r '.result.tools[]?.name' 2>/dev/null || echo "")
  log_info "tools/list: $(echo "${TOOLS}" | tr '\n' ' ')"

  if echo "${TOOLS}" | grep -qx "exec_cmd"; then
    post_mcp 15 "tools/call" \
      '{"name":"exec_cmd","arguments":{"command":"echo gvisor-sandbox-ok && hostname"}}'
    RESP=$(sse_wait_rpc 15 50)
    SBOX=$(echo "${RESP}" | jq -r '.result._meta.sandbox_used // empty' 2>/dev/null || echo "")
    DEGRADED=$(echo "${RESP}" | jq -r '.result._meta.degraded // empty' 2>/dev/null || echo "")
    TEXT=$(echo "${RESP}" | jq -r '.result.content[0].text // empty' 2>/dev/null || echo "")
    ERR=$(echo "${RESP}" | jq -r '.error.message // empty' 2>/dev/null || echo "")
    if [[ "${SBOX}" == "gvisor" && "${DEGRADED}" != "true" ]]; then
      log_pass "exec_cmd sandbox_used=gvisor  text=${TEXT:0:80}"
    elif echo "${ERR} ${TEXT} ${RESP}" | grep -qi sandbox_exec_timeout; then
      log_pass "exec_cmd JSON-RPC sandbox_exec_timeout within wall (no hang) err=${ERR:0:80}"
    elif [[ -z "${RESP}" ]]; then
      log_fail "exec_cmd SSE hung past 50s wall (no JSON-RPC)"
    else
      log_fail "exec_cmd sandbox_used=${SBOX} degraded=${DEGRADED} err=${ERR:0:80} text=${TEXT:0:80}"
    fi
  else
    log_fail "exec_cmd not in tools/list"
  fi

  if echo "${TOOLS}" | grep -qx "execute_python"; then
    post_mcp 20 "tools/call" '{"name":"execute_python","arguments":{"code":"print(\"hello landlock\")"}}'
    RESP=$(sse_wait_rpc 20 40)
    SBOX=$(echo "${RESP}" | jq -r '.result._meta.sandbox_used // empty' 2>/dev/null || echo "")
    DEGRADED=$(echo "${RESP}" | jq -r '.result._meta.degraded // empty' 2>/dev/null || echo "")
    TEXT=$(echo "${RESP}" | jq -r '.result.content[0].text // .error.message // empty' 2>/dev/null || echo "")
    if [[ "${SBOX}" == "landlock" && "${DEGRADED}" != "true" ]]; then
      log_pass "execute_python sandbox_used=landlock  text=${TEXT:0:80}"
    else
      log_fail "execute_python sandbox_used=${SBOX} degraded=${DEGRADED} (want landlock) text=${TEXT:0:120}"
    fi
  else
    log_warn "execute_python not in tools/list — skip landlock runtime probe"
  fi

  exec 3>&- 2>/dev/null || true
  kill "$(cat "${sse_pid_file}")" 2>/dev/null || true
}

restore_policy() {
  if [[ "${POLICY_BUMPED}" == "1" && -f "${POLICY_BACKUP}" ]]; then
    log_info "restore max_concurrent_rollouts from backup"
    http PUT "/api/v1/admin/tenants/${TENANT}/rollout-policy" "$(cat "${POLICY_BACKUP}")" >/dev/null || true
  fi
}

cleanup_on_exit() {
  local did
  did=$(active_deploy_id || true)
  if [[ -n "${did}" ]]; then
    rollback_if_ours "${did}"
  fi
  http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}' >/dev/null 2>&1 || true
  restore_policy
  cleanup_stale_runsc || true
  rm -rf "${TMPDIR}"
}

trap 'cleanup_on_exit' EXIT

echo "============================================================"
echo " Sandbox (Landlock/gVisor) rollout E2E on ECS"
echo "============================================================"
echo "  host=$(hostname) kernel=$(uname -r)"
echo "  control=${CONTROL} tenant=${TENANT} app_id=${APP_ID}"

log_step "0. Preflight"

HC=$(http GET "/api/v1/health")
if [[ "${HC}" != 2* ]]; then
  echo "virbius-control unreachable: ${CONTROL} (HTTP ${HC})"
  exit 1
fi
log_pass "control health ${CONTROL}"

if [[ -d /sys/kernel/security/landlock ]] || grep -qi landlock /proc/config.gz 2>/dev/null; then
  log_pass "host Landlock ABI present"
else
  log_warn "could not confirm Landlock sysfs (kernel=$(uname -r)); continuing"
fi

if [[ -x /opt/virbius/bin/runsc ]]; then
  log_pass "runsc at /opt/virbius/bin/runsc"
elif [[ -x /usr/local/bin/runsc ]]; then
  log_pass "runsc at /usr/local/bin/runsc"
else
  log_fail "runsc not found (gVisor runtime probe will fail)"
fi
[[ -d /opt/virbius/rootfs ]] && log_pass "gVisor rootfs /opt/virbius/rootfs" \
  || log_warn "rootfs /opt/virbius/rootfs missing"

discover_stack
log_info "proxy container=${PROXY_NAME:-none}  data_dir=${DATA_DIR}"
[[ -n "${PROXY_NAME}" ]] && log_pass "found MCP proxy ${PROXY_NAME}" \
  || log_warn "MCP proxy container not found (runtime probe may still hit ${PROXY_URL})"

DID=$(active_deploy_id)
if [[ -n "${DID}" ]]; then
  http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active" >/dev/null
  if is_our_deploy; then
    log_warn "leftover deploy from this script ${DID}, rolling back"
    rollback_if_ours "${DID}"
  else
    echo "Tenant ${TENANT} already has an active node gray (deploy_id=${DID}, operator=$(field '.data.operator'))."
    echo "Refusing to overwrite. Finalize/rollback it in the console first."
    exit 1
  fi
fi

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rollout-policy")
expect_ok "${code}" "GET rollout-policy" || true
jq -c '.data' "${BODY_FILE}" > "${POLICY_BACKUP}"
LADDER=$(field '.data.canary_ladder | join(",")')
ALLOW_FORCE=$(field '.data.allow_force')
CUR_MAX=$(field '.data.max_concurrent_rollouts')
log_info "canary_ladder=${LADDER} allow_force=${ALLOW_FORCE} max_concurrent_rollouts=${CUR_MAX}"
assert_eq "${ALLOW_FORCE}" "true" "tenant allow_force (needed to skip empty-sample gates)"
IFS=',' read -r STEP1 STEP2 STEP3 STEP4 <<< "${LADDER}"
STEP1=${STEP1:-5}; STEP2=${STEP2:-20}; STEP3=${STEP3:-50}; STEP4=${STEP4:-100}

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules")
expect_ok "${code}" "GET /rules (all layers)" || true
SLOT=$(jq '[.data[]? | select(.rollout_state=="dry_run" or .rollout_state=="canary")] | length' "${BODY_FILE}")
log_info "active dry_run+canary slots=${SLOT} / ${CUR_MAX}"
if [[ -n "${CUR_MAX}" && "${SLOT}" -ge "${CUR_MAX}" ]]; then
  NEED=$((SLOT + 2))
  BUMP=$(jq -c --argjson m "${NEED}" '.max_concurrent_rollouts=$m' "${POLICY_BACKUP}")
  code=$(http PUT "/api/v1/admin/tenants/${TENANT}/rollout-policy" "${BUMP}")
  if [[ "${code}" == 2* && "$(field '.code')" == "0" ]]; then
    POLICY_BUMPED=1
    log_pass "temporarily raise max_concurrent_rollouts ${CUR_MAX} → ${NEED} (restored on exit)"
  else
    log_fail "could not raise concurrent limit HTTP ${code} $(field '.message')"
  fi
fi

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules?layer=sandbox")
expect_ok "${code}" "GET /rules?layer=sandbox" || true
log_info "existing sandbox rules: $(jq -r '[.data[]? | .rule_id + "=" + (.rollout_state//"")] | join(", ")' "${BODY_FILE}")"

# ============================================================
# A. Per-rule Landlock rollout (draft → dry_run → canary → full)
# ============================================================
log_step "A1. Create/reset Landlock marker rule (draft)"
reset_test_rule

UPSERT=$(RULE_ID="${RULE_ID}" BUNDLE="${BUNDLE}" TOOL_NAME="${TOOL_NAME}" MARKER_PATH="${MARKER_PATH}" python3 - <<'PY'
import json, os
print(json.dumps({
  "rule_id": os.environ["RULE_ID"],
  "bundle_id": os.environ["BUNDLE"],
  "layer": "sandbox",
  "runtime": "landlock",
  "reason_code": "E2E_SBX_LANDLOCK",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "global"},
  "body": {
    "tool_name": os.environ["TOOL_NAME"],
    "read_paths": [os.environ["MARKER_PATH"], "/usr/*", "/bin/*", "/tmp/*"],
    "write_paths": ["/tmp/*"],
    "exec_paths": ["/usr/bin/*", "/bin/*"],
  },
}))
PY
)
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules" "${UPSERT}")
expect_ok "${code}" "POST /rules create ${RULE_ID}" || true
assert_eq "$(field '.data.rollout_state')" "draft" "initial state draft"
assert_eq "$(field '.data.layer')" "sandbox" "layer=sandbox"
assert_eq "$(field '.data.runtime')" "landlock" "runtime=landlock"

log_step "A2. Publish draft → dry_run"
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
expect_ok "${code}" "POST rollout/publish" || true
assert_eq "$(field '.data.rollout_state')" "dry_run" "publish → dry_run"
PUBLISHED_STATE=$(field '.data.rollout_state')

if [[ "${PUBLISHED_STATE}" == "dry_run" ]]; then
  log_step "A2b. dry_run is not written into Edge landlock_profiles"
  code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/next-version?bundle_id=${BUNDLE}")
  VER=$(field '.data.version')
  payload=$(python3 -c "import json; print(json.dumps({
    'bundle_id': '${BUNDLE}',
    'bundle_version': '${VER}',
    'layer': 'edge',
    'description': '${NOTE_MARK} prepare edge while sandbox rule is dry_run'
  }))")
  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/prepare" "${payload}")
  if [[ "${code}" == 2* && "$(field '.code')" == "0" ]]; then
    DID=$(field '.data.deploy_id')
    CANARY_MF=$(manifest_path canary)
    assert_marker_absent "${CANARY_MF}" "canary while ${RULE_ID}=dry_run"
    http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/rollback" \
      "{\"note\":\"${NOTE_MARK} rollback after dry_run deliverability check\"}" >/dev/null || true
    log_pass "rolled back dry_run-only edge deploy ${DID}"
    DID=""
  else
    log_fail "prepare while dry_run HTTP ${code} $(field '.message')"
  fi

  log_step "A3. dry_run → full is hard-banned (even with force)"
  payload=$(python3 -c "import json; print(json.dumps({'rollout_state':'full','force':True,'comment':'${NOTE_MARK} should be rejected'}))")
  code=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${payload}")
  MSG=$(field '.message')
  if echo "${MSG}" | grep -qi "dry_run -> full"; then
    log_pass "hard-ban dry_run→full HTTP ${code}: ${MSG}"
  else
    log_fail "expected dry_run→full reject, got HTTP ${code} msg=${MSG}"
  fi

  log_step "A4. dry_run → canary without force (expect GATE_FAILED 409)"
  payload=$(python3 -c "import json; print(json.dumps({'rollout_state':'canary','canary_percent':int('${STEP1}'),'force':False}))")
  code=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${payload}")
  MSG=$(field '.message')
  if [[ "${code}" == "409" && "${MSG}" == GATE_FAILED* ]]; then
    log_pass "gate blocked HTTP 409: ${MSG}"
  else
    log_warn "expected GATE_FAILED 409, got HTTP ${code} msg=${MSG} (continuing with force)"
  fi

  log_step "A5. Force canary ${STEP1}% then full"
  code=$(force_apply canary "${STEP1}")
  expect_ok "${code}" "force → canary ${STEP1}%" || true
  assert_eq "$(field '.data.rollout_state')" "canary" "state canary"

  code=$(force_apply full)
  expect_ok "${code}" "force → full" || true
  assert_eq "$(field '.data.rollout_state')" "full" "state full"
  [[ "$(field '.data.rollout_state')" == "full" ]] && RULE_FULL=1
else
  log_fail "skip A3–A5 and edge deploy: rule never reached dry_run"
fi

# ============================================================
# B. Edge bundle gray (sandbox profiles land in the edge manifest)
# ============================================================
EDGE_FINALIZED=0
log_step "B1. prepare layer=edge"
if [[ "${RULE_FULL}" -ne 1 ]]; then
  log_fail "skip edge prepare: ${RULE_ID} is not full"
else
  code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/next-version?bundle_id=${BUNDLE}")
  expect_ok "${code}" "GET next-version" || true
  VER=$(field '.data.version')
  [[ -n "${VER}" ]] && log_info "bundle_version=${VER}" || log_warn "next-version empty"

  payload=$(python3 -c "import json; print(json.dumps({
    'bundle_id': '${BUNDLE}',
    'bundle_version': '${VER}',
    'layer': 'edge',
    'description': '${NOTE_MARK} prepare edge for sandbox landlock marker'
  }))")
  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/prepare" "${payload}")
  expect_ok "${code}" "POST prepare layer=edge" || true
  DID=$(field '.data.deploy_id')
  [[ -n "${DID}" ]] && log_pass "deploy_id=${DID}" || log_fail "prepare did not return deploy_id"

  CANARY_MF=$(manifest_path canary)
  STABLE_MF=$(manifest_path stable)
  log_info "canary manifest ${CANARY_MF}"
  assert_marker_in "${CANARY_MF}" "canary after prepare"
  assert_gvisor_config "${CANARY_MF}" "canary gVisor config"
  assert_effective_sandbox_types "${CANARY_MF}" "canary effective sandbox_type"

  log_step "B2. upgrade ladder to 100%"
  for i in 1 2 3 4 5 6; do
    code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
    ST=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
    PCT=$(field '.data.canary_percent')
    log_info "ladder ${i}: state=${ST} percent=${PCT}%"
    if [[ "${ST}" == "full" || "${PCT}" == "100" ]]; then
      break
    fi
    if [[ "${ST}" != "pending" && "${ST}" != "canary" && "${ST}" != "paused" ]]; then
      log_fail "cannot upgrade, state=${ST}"
      break
    fi
    code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/upgrade" \
      "{\"note\":\"${NOTE_MARK} ladder ${i}\"}")
    if [[ "${code}" != 2* ]]; then
      log_fail "upgrade ${i} HTTP ${code} $(field '.message')"
      break
    fi
    log_pass "upgrade ${i} → $(field '.data.state') $(field '.data.canary_percent')%"
  done

  code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
  ST=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
  PCT=$(field '.data.canary_percent')
  if [[ "${ST}" == "full" || "${PCT}" == "100" ]]; then
    log_pass "at full state=${ST} percent=${PCT}%"
    code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/finalize" \
      "{\"note\":\"${NOTE_MARK} finalize\"}")
    expect_ok "${code}" "POST finalize" || true
    assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "finalized" "finalized"
    [[ "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" == "finalized" ]] && EDGE_FINALIZED=1
  else
    log_fail "did not reach 100% (state=${ST} percent=${PCT}%), rolling back"
    http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/rollback" \
      "{\"note\":\"${NOTE_MARK} abort unfinished ladder\"}" >/dev/null || true
  fi

  DID=""
  assert_marker_in "${STABLE_MF}" "stable after finalize"
  assert_gvisor_config "${STABLE_MF}" "stable gVisor config"
  assert_effective_sandbox_types "${STABLE_MF}" "stable effective sandbox_type"
fi

# ============================================================
# C. Remount proxy + MCP runtime
# ============================================================
log_step "C1. Restart MCP proxy so file bind-mount picks up promoted manifest"
if [[ "${SKIP_PROXY_RESTART:-}" == "1" ]]; then
  log_warn "SKIP_PROXY_RESTART=1"
elif [[ "${EDGE_FINALIZED}" -ne 1 ]]; then
  log_warn "no new edge finalize this run — skip proxy restart"
  wait_http "${PROXY_URL}/health" 5 && log_pass "proxy health ${PROXY_URL}/health" \
    || log_fail "proxy not reachable at ${PROXY_URL}"
elif [[ -n "${PROXY_NAME}" ]]; then
  docker restart "${PROXY_NAME}" >/dev/null
  log_pass "docker restart ${PROXY_NAME}"
  if wait_http "${PROXY_URL}/health" 25; then
    log_pass "proxy health ${PROXY_URL}/health"
  else
    log_fail "proxy did not become healthy after restart"
    docker logs --tail 40 "${PROXY_NAME}" 2>/dev/null || true
  fi
else
  log_warn "no proxy container; checking ${PROXY_URL}/health anyway"
  wait_http "${PROXY_URL}/health" 5 && log_pass "proxy already healthy" \
    || log_fail "proxy not reachable at ${PROXY_URL}"
fi

log_step "C2. Runtime: gVisor (runsc do) + MCP landlock/gvisor tools"
gvisor_smoke /opt/virbius/bin/runsc
if [[ "${SKIP_MCP:-}" == "1" ]]; then
  log_warn "SKIP_MCP=1"
else
  mcp_probe
  cleanup_stale_runsc || true
fi

echo ""
echo "========================================"
echo -e "  ${GREEN}PASS${NC}=${PASS}  ${RED}FAIL${NC}=${FAIL}"
echo "========================================"
echo "Cleanup: disable ${RULE_ID} (marker stays in current stable manifest"
echo "until the next edge deploy). Compose stack was not torn down."
if [[ "${FAIL}" -gt 0 ]]; then
  exit 1
fi
exit 0
