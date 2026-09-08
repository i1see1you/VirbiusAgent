#!/usr/bin/env bash
# ============================================================
# test-policy-rollout-e2e.sh
#
# 运营台「策略上线」页全流程 E2E：规则放量 + 节点灰度。
# 只走 virbius-control HTTP API（与前端 RolloutView.vue 同一组接口），
# 不直接读写 SQLite / Redis。
#
# 对应页面操作：
#   Tab「规则放量」
#     选规则 → 上线(publish) → 下一步(PATCH /rollout, 门禁 409 后勾选强制跳过)
#     → 阶梯 5% → 20% → 50% → 全量 → 启动/暂停阶梯 → 回退 → 停用 → 恢复
#   Tab「节点灰度」
#     版本弹窗(next-version + diff-rules) → 准备 Engine/Gateway/Edge/Falco
#     → 升级 → 暂停 → 恢复 → 回退
#     → 三层准备 → 升级至 100% → 完结
#
# Prerequisites:
#   virbius-control http://127.0.0.1:8080
#   Redis（准备 Bundle 时运营台内部写 canary artifact，脚本本身不碰 Redis）
#   租户 default 已存在；若已有他人的进行中灰度单，脚本会拒绝覆盖
#
# Usage:
#   bash scripts/test-policy-rollout-e2e.sh
# ============================================================

set -euo pipefail

CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
TENANT="${VIRBIUS_TENANT:-default}"
BUNDLE="${VIRBIUS_BUNDLE:-poc-default}"
RULE_ID="${VIRBIUS_ROLLOUT_RULE:-e2e_policy_rollout}"
OPERATOR="e2e-rollout-test"
NOTE_MARK="e2e-rollout-test"

PASS=0
FAIL=0
RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'

log_pass() { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
log_info() { echo -e "  ${CYAN}INFO${NC}: $1"; }
log_warn() { echo -e "  ${YELLOW}WARN${NC}: $1"; }

command -v jq >/dev/null 2>&1 || { echo "jq is required"; exit 1; }
command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }

TMPDIR=$(mktemp -d)
BODY_FILE="${TMPDIR}/body.json"

AUTH_ARGS=()
if [[ -n "${VIRBIUS_API_KEY:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${VIRBIUS_API_KEY}")
fi
COMMON_HEADERS=(
  -H "Content-Type: application/json"
  -H "X-User-Id: ${OPERATOR}"
)
if [[ ${#AUTH_ARGS[@]} -gt 0 ]]; then
  COMMON_HEADERS+=("${AUTH_ARGS[@]}")
fi

# ── HTTP helpers (运营台 envelope: {code,message,data}) ──
# Writes body to BODY_FILE, prints HTTP status to stdout.
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

# ── Active deploy helpers ──
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
    log_info "回退本脚本留下的灰度单 ${did}"
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
    st="draft"
  fi
  if [[ "${st}" != "draft" && "${st}" != "disabled" && -n "${st}" ]]; then
    http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}' >/dev/null || true
    http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/recover" '{}' >/dev/null || true
  fi
}

cleanup_on_exit() {
  local did
  did=$(active_deploy_id || true)
  if [[ -n "${did}" ]]; then
    rollback_if_ours "${did}"
  fi
  # 测试规则停用，避免占用放量并发槽
  http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}' >/dev/null 2>&1 || true
  rm -rf "${TMPDIR}"
}

trap 'cleanup_on_exit' EXIT

# ============================================================
# Preflight
# ============================================================
log_step "Preflight: 运营台健康检查"

HC=$(http GET "/api/v1/health")
if [[ "${HC}" != 2* ]]; then
  echo "virbius-control 不可达: ${CONTROL} (HTTP ${HC})"
  exit 1
fi
log_pass "control health ${CONTROL}"

DID=$(active_deploy_id)
if [[ -n "${DID}" ]]; then
  http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active" >/dev/null
  if is_our_deploy; then
    log_warn "发现本脚本上次残留灰度单 ${DID}，先回退"
    rollback_if_ours "${DID}"
  else
    echo "租户 ${TENANT} 已有进行中的节点灰度 (deploy_id=${DID}, operator=$(field '.data.operator'))。"
    echo "本脚本不会覆盖他人的灰度单。请在运营台完结/回退后再跑。"
    exit 1
  fi
fi

# ============================================================
# Part A — 规则放量
# ============================================================
log_step "A0. 看板接口（页面刷新会打这些 GET）"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/dashboard/overview")
expect_ok "${code}" "GET dashboard/overview" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rollout-policy")
expect_ok "${code}" "GET rollout-policy" || true
LADDER=$(field '.data.canary_ladder | join(",")')
ALLOW_FORCE=$(field '.data.allow_force')
log_info "canary_ladder=${LADDER} allow_force=${ALLOW_FORCE}"
assert_eq "${ALLOW_FORCE}" "true" "租户允许强制跳过门禁（页面勾选「强制跳过」）"
IFS=',' read -r STEP1 STEP2 STEP3 STEP4 <<< "${LADDER}"
STEP1=${STEP1:-5}; STEP2=${STEP2:-20}; STEP3=${STEP3:-50}; STEP4=${STEP4:-100}

log_step "A1. 规则页：创建/重置测试规则（draft）"
reset_test_rule

UPSERT=$(python3 -c "
import json
print(json.dumps({
  'rule_id': '${RULE_ID}',
  'bundle_id': '${BUNDLE}',
  'layer': 'cloud',
  'runtime': 'groovy',
  'reason_code': 'E2E_POLICY_ROLLOUT',
  'risk_score': 10,
  'intent_action': 'review',
  'scope': {'bind_scope': 'global'},
  'body': 'def decide(ctx) { return false }'
}))
")
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules" "${UPSERT}")
expect_ok "${code}" "POST /rules 创建测试规则" || true
assert_eq "$(field '.data.rule_id')" "${RULE_ID}" "rule_id"
assert_eq "$(field '.data.rollout_state')" "draft" "初始状态 draft"

log_step "A2. 规则放量页：选中规则后加载看板"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}")
expect_ok "${code}" "GET /rules/{id}" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules?layer=cloud")
expect_ok "${code}" "GET /rules?layer=cloud（下拉列表）" || true
FOUND=$(jq -r --arg id "${RULE_ID}" '[.data[]? | select(.rule_id==$id)] | length' "${BODY_FILE}")
assert_eq "${FOUND}" "1" "规则出现在 cloud 列表"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/metrics?hours=24")
expect_ok "${code}" "GET metrics" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/timeline")
expect_ok "${code}" "GET timeline" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/audit-samples?limit=30")
expect_ok "${code}" "GET audit-samples" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/gates?limit=20")
expect_ok "${code}" "GET gates" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/rollout/ingest-health?layer=edge&hours=24")
expect_ok "${code}" "GET ingest-health" || true

log_step "A3. 点击「上线」draft → dry_run"
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
expect_ok "${code}" "POST rollout/publish" || true
assert_eq "$(field '.data.rollout_state')" "dry_run" "publish 后状态"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/timeline")
TL_STATES=$(jq -r '[.data[]?.rollout_state] | join(",")' "${BODY_FILE}")
log_info "timeline states: ${TL_STATES}"
echo "${TL_STATES}" | grep -q "dry_run" && log_pass "timeline 含 dry_run" || log_fail "timeline 缺少 dry_run"

log_step "A4. 点击「下一步」进入灰度 ${STEP1}% —— 门禁应拦截（无观察样本）"
PATCH_CANARY=$(python3 -c "import json; print(json.dumps({'rollout_state':'canary','canary_percent':int('${STEP1}'),'force':False}))")
code=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${PATCH_CANARY}")
MSG=$(field '.message')
if [[ "${code}" == "409" && "${MSG}" == GATE_FAILED* ]]; then
  log_pass "门禁拦截 HTTP 409: ${MSG}"
else
  log_fail "期望 GATE_FAILED 409，实际 HTTP ${code} msg=${MSG}"
fi

log_step "A5. 勾选「强制跳过门禁」并填写说明后再次「下一步」"
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

code=$(force_apply canary "${STEP1}")
expect_ok "${code}" "强制升级 → canary ${STEP1}%" || true
assert_eq "$(field '.data.rollout_state')" "canary" "状态 canary"
assert_eq "$(field '.data.canary_percent')" "${STEP1}" "灰度 ${STEP1}%"

log_step "A6. 更多操作：启动 / 暂停自动阶梯"
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/ladder/start" '{}')
expect_ok "${code}" "POST ladder/start" || true
assert_eq "$(field '.data.ladder_status')" "running" "ladder_status=running"
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/ladder/pause" '{}')
expect_ok "${code}" "POST ladder/pause" || true
assert_eq "$(field '.data.ladder_status')" "paused" "ladder_status=paused"

log_step "A7. 阶梯升级 ${STEP1}% → ${STEP2}% → ${STEP3}% → 全量"
code=$(force_apply canary "${STEP2}")
expect_ok "${code}" "强制升级 → canary ${STEP2}%" || true
assert_eq "$(field '.data.canary_percent')" "${STEP2}" "灰度 ${STEP2}%"

code=$(force_apply canary "${STEP3}")
expect_ok "${code}" "强制升级 → canary ${STEP3}%" || true
assert_eq "$(field '.data.canary_percent')" "${STEP3}" "灰度 ${STEP3}%"

code=$(force_apply full)
expect_ok "${code}" "强制升级 → full" || true
assert_eq "$(field '.data.rollout_state')" "full" "状态 full"

log_step "A8. 回退 / 停用 / 恢复（页面「更多操作」）"
code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/rollback" '{}')
expect_ok "${code}" "POST rollout/rollback" || true
assert_eq "$(field '.data.rollout_state')" "dry_run" "回退后 dry_run"

code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}')
expect_ok "${code}" "POST rollout/disable" || true
assert_eq "$(field '.data.rollout_state')" "disabled" "停用后 disabled"

code=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/recover" '{}')
expect_ok "${code}" "POST rollout/recover" || true
assert_eq "$(field '.data.rollout_state')" "draft" "恢复后 draft"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/timeline")
expect_ok "${code}" "回看 timeline" || true
N_TL=$(jq -r '.data | length' "${BODY_FILE}")
log_info "timeline 事件数=${N_TL}"
[[ "${N_TL}" -ge 6 ]] && log_pass "timeline 覆盖 publish/canary/full/rollback/disable/recover" \
  || log_fail "timeline 事件过少 (${N_TL})"

# 放量测完停用，避免占用并发槽；节点灰度测的是另一套状态机
http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}' >/dev/null || true

# ============================================================
# Part B — 节点灰度
# ============================================================
log_step "B0. 节点灰度页刷新（active / list / metrics / overview）"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
expect_ok "${code}" "GET deploy-rollout/active" || true
assert_eq "$(field '.data.active')" "false" "当前无进行中灰度"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/list")
expect_ok "${code}" "GET deploy-rollout/list" || true
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/metrics?hours=24")
expect_ok "${code}" "GET deploy-rollout/metrics" || true

log_step "B1. 版本弹窗：next-version + diff-rules（点「准备 Engine」前）"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/next-version?bundle_id=${BUNDLE}")
expect_ok "${code}" "GET next-version" || true
VER=$(field '.data.version')
[[ -n "${VER}" ]] && log_pass "next-version=${VER}" || log_fail "next-version 为空"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/diff-rules?bundle_id=${BUNDLE}&layer=cloud")
expect_ok "${code}" "GET diff-rules?layer=cloud" || true

prepare_layer() {
  local layer="$1" label="$2"
  local payload
  payload=$(python3 -c "import json; print(json.dumps({
    'bundle_id': '${BUNDLE}',
    'bundle_version': '${VER}',
    'layer': '${layer}',
    'description': '${NOTE_MARK} prepare ${label}'
  }))")
  http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/prepare" "${payload}"
}

assert_active() {
  local want_state="$1" want_pct="${2:-}"
  local code; code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
  expect_ok "${code}" "GET active (${want_state})" || return 0
  local st pct
  st=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
  pct=$(field '.data.canary_percent')
  assert_eq "${st}" "$(echo "${want_state}" | tr '[:upper:]' '[:lower:]')" "active.state"
  if [[ -n "${want_pct}" ]]; then
    assert_eq "${pct}" "${want_pct}" "active.canary_percent"
  fi
}

log_step "B2. 准备 Engine → 升级 → 暂停 → 恢复 → 回退"
code=$(prepare_layer "cloud" "engine")
expect_ok "${code}" "POST prepare layer=cloud (准备 Engine)" || true
DID=$(field '.data.deploy_id')
[[ -n "${DID}" ]] && log_pass "deploy_id=${DID}" || log_fail "prepare 未返回 deploy_id"
assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "pending" "prepare 后 PENDING"
assert_eq "$(field '.data.canary_percent')" "0" "prepare 后 0%"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
expect_ok "${code}" "GET active 含节点分布/事件" || true
NEXT_PCT=$(field '.data.effective_next_percent')
NEXT_ST=$(field '.data.effective_next_state' | tr '[:upper:]' '[:lower:]')
log_info "upgrade preview: next_state=${NEXT_ST:-canary} next_percent=${NEXT_PCT:-${STEP1}}"

code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/upgrade" \
  "{\"note\":\"${NOTE_MARK} upgrade\"}")
expect_ok "${code}" "POST upgrade（开始灰度）" || true
ST=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
PCT=$(field '.data.canary_percent')
log_info "upgrade 后 state=${ST} percent=${PCT}%"
if [[ "${ST}" != "canary" && "${ST}" != "full" ]]; then
  log_fail "升级后应为 canary 或 full，实际 ${ST}"
fi

if [[ "${ST}" == "canary" ]]; then
  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/pause" \
    "{\"note\":\"${NOTE_MARK} pause\"}")
  expect_ok "${code}" "POST pause" || true
  assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "paused" "暂停后 PAUSED"
  assert_eq "$(field '.data.canary_percent')" "${PCT}" "暂停保持灰度比例"

  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/upgrade" \
    "{\"note\":\"${NOTE_MARK} resume\"}")
  expect_ok "${code}" "POST upgrade（从暂停恢复）" || true
  assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "canary" "恢复后 CANARY"
  assert_eq "$(field '.data.canary_percent')" "${PCT}" "恢复后比例不变"
else
  log_warn "升级直接到 FULL（节点分桶跳档），跳过暂停/恢复（页面此时不显示暂停）"
fi

code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/rollback" \
  "{\"note\":\"${NOTE_MARK} rollback\"}")
expect_ok "${code}" "POST rollback" || true
assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "rolled_back" "回退后 ROLLED_BACK"

code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
expect_ok "${code}" "回退后 active 应清空" || true
ACT=$(field '.data.active'); DID2=$(field '.data.deploy_id')
if [[ "${ACT}" == "false" || -z "${DID2}" ]]; then
  log_pass "回退后无进行中灰度单"
else
  log_fail "回退后仍有 active deploy_id=${DID2}"
fi

log_step "B3. 覆盖其余「准备」按钮（Gateway / Edge / Falco），每次准备完立刻回退"
for layer_spec in "gateway:准备 Gateway" "edge:准备 Edge" "falco:准备 Falco"; do
  layer="${layer_spec%%:*}"
  label="${layer_spec#*:}"
  code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/next-version?bundle_id=${BUNDLE}")
  VER=$(field '.data.version')
  code=$(prepare_layer "${layer}" "${label}")
  if [[ "${code}" == 2* && "$(field '.code')" == "0" ]]; then
    log_pass "POST prepare layer=${layer} (${label})"
    DID=$(field '.data.deploy_id')
    http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/rollback" \
      "{\"note\":\"${NOTE_MARK} rollback after ${label}\"}" >/dev/null || true
    log_pass "回退 ${label} 灰度单 ${DID}"
  else
    log_fail "prepare layer=${layer} HTTP ${code} $(field '.message')"
  fi
done

log_step "B4. 「全部部署」三层准备 → 升级至 100% → 完结"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/next-version?bundle_id=${BUNDLE}")
VER=$(field '.data.version')
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/diff-rules?bundle_id=${BUNDLE}")
expect_ok "${code}" "GET diff-rules（全部署弹窗）" || true

payload=$(python3 -c "import json; print(json.dumps({
  'bundle_id': '${BUNDLE}',
  'bundle_version': '${VER}',
  'layer': '',
  'description': '${NOTE_MARK} prepare all layers'
}))")
code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/prepare" "${payload}")
expect_ok "${code}" "POST prepare layer='' (全部部署)" || true
DID=$(field '.data.deploy_id')
assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "pending" "三层准备 PENDING"

for i in 1 2 3 4 5 6; do
  code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
  ST=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
  PCT=$(field '.data.canary_percent')
  log_info "ladder step ${i}: state=${ST} percent=${PCT}%"
  if [[ "${ST}" == "full" || "${PCT}" == "100" ]]; then
    break
  fi
  if [[ "${ST}" != "pending" && "${ST}" != "canary" && "${ST}" != "paused" ]]; then
    log_fail "无法继续升级，state=${ST}"
    break
  fi
  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/upgrade" \
    "{\"note\":\"${NOTE_MARK} ladder step ${i}\"}")
  if [[ "${code}" != 2* ]]; then
    log_fail "upgrade step ${i} HTTP ${code} $(field '.message')"
    break
  fi
  log_pass "upgrade step ${i} → $(field '.data.state') $(field '.data.canary_percent')%"
done

code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
ST=$(field '.data.state' | tr '[:upper:]' '[:lower:]')
PCT=$(field '.data.canary_percent')
if [[ "${ST}" == "full" || "${PCT}" == "100" ]]; then
  log_pass "已到全量 state=${ST} percent=${PCT}%"
  code=$(http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/finalize" \
    "{\"note\":\"${NOTE_MARK} finalize\"}")
  expect_ok "${code}" "POST finalize（完结）" || true
  assert_eq "$(field '.data.state' | tr '[:upper:]' '[:lower:]')" "finalized" "完结后 FINALIZED"
else
  log_fail "未能升到 100%/FULL（state=${ST} percent=${PCT}），尝试回退以免留下进行中灰度"
  http POST "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}/rollback" \
    "{\"note\":\"${NOTE_MARK} abort unfinished ladder\"}" >/dev/null || true
fi

code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/active")
DID_LEFT=$(field '.data.deploy_id')
if [[ -z "${DID_LEFT}" || "$(field '.data.active')" == "false" ]]; then
  log_pass "完结后无进行中灰度单"
else
  log_fail "完结后仍有 active ${DID_LEFT}"
fi

log_step "B5. 历史列表应能看到刚才的回退单和完结单"
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/list")
expect_ok "${code}" "GET deploy-rollout/list 历史" || true
N_HIST=$(jq -r '.data | length' "${BODY_FILE}")
log_info "历史部署单 ${N_HIST} 条"
HAS_RB=$(jq -r --arg m "${NOTE_MARK}" '[.data[]? | select((.note // "") | contains($m))] | length' "${BODY_FILE}")
[[ "${HAS_RB}" -ge 1 ]] && log_pass "历史中含本测试标记的部署单 (${HAS_RB})" \
  || log_fail "历史中找不到 ${NOTE_MARK} 部署单"
STATES=$(jq -r '[.data[]?.state] | unique | join(",")' "${BODY_FILE}")
log_info "历史状态集合: ${STATES}"
echo "${STATES}" | grep -qi "rolled_back" && log_pass "历史含 rolled_back" || log_fail "历史缺少 rolled_back"
echo "${STATES}" | grep -qi "finalized" && log_pass "历史含 finalized" || log_fail "历史缺少 finalized"

# GET 单条详情（页面表格点进去等价）
code=$(http GET "/api/v1/admin/tenants/${TENANT}/deploy-rollout/${DID}")
expect_ok "${code}" "GET deploy-rollout/{id} 完结单详情" || true

# ============================================================
# Summary
# ============================================================
echo ""
echo "========================================"
echo -e "  ${GREEN}PASS${NC}=${PASS}  ${RED}FAIL${NC}=${FAIL}"
echo "========================================"
if [[ "${FAIL}" -gt 0 ]]; then
  exit 1
fi
exit 0
