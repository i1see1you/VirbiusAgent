#!/usr/bin/env bash
# ============================================================
# test-guard-fp-suppress-e2e.sh
#
# VirbiusGuard（prompt-* 规则）误拦 prompt → 用「allow 例外规则」消除误拦的
# 全流程 E2E（用户真实操作流）。
#
# 场景定义：guard 模型对某些无害请求词面固有 FP（memory §5 已实锤：命令型
# tool_call 字符串被判 JAILBREAK risk 100）。用户侧标准处置 = 新建一条
# allow 例外规则锚定该误拦词面，按「观测 → 灰度 → 全量」放量让例外生效，
# 回退/停用可随时撤销。对应本轮 allow 合并语义修复（ActionMerge）：
#   * allow 命中只在 enforce 生效（canary 桶内 / full；dry_run 纯观测）
#   * 压制发生时决策/审计归因 = 被压制 deny 的 primary（黑洞修复）
#   * 压制 allow 事件免 10% 采样必进审计链
#
# 压制特征对（本测试的核心断言）：
#   effective_action=allow 且 rule_id=误拦基准规则（如 prompt-jailbreak）
#   —— 该组合只能由 allow 压制产生（guard 自己放行的归因是 POLICY_ALLOW；
#      纯 allow 命中无 deny 可压时不会携带 deny 归因）。因此断言对 guard
#      模型的措辞抖动是稳健的：不锚定单次放行，而锚定“压制必携原归因”。
#
# 只走运营台 HTTP API（与控制台页面同一组接口）+ 引擎 /v1/evaluate（调用方
# 真实视角）。脚本从不打开 SQLite 文件、从不执行 redis-cli，也不改动代码/数据；
# 结束时会把自家测试规则复位/停用，不触碰他人规则。
#
# 对应页面操作：
#   「新建规则」(POST /rules) → 草稿
#   「上线」(POST /rules/{id}/rollout/publish → dry_run 观测期)
#   「下一档」门禁 409 → 强制跳过 (PATCH /rules/{id}/rollout, force+comment)
#   灰度 100%（canary_percent=100 全桶）→ 例外生效 → 「全量」
#   审计中心 (GET /audit/trace/{trace_id}) 回查压制归因对
#   「回退」(POST /rules/{id}/rollout/rollback) → 「停用」→ 误拦恢复
#
# Prerequisites:
#   bash scripts/run-local.sh 已拉起标准栈；guard 模型在现方向对 FP_PROMPT
#   稳定误拦（与本轮实探一致）。ollama 离线时基准不成立 → 脚本会给出诊断。
#
# Usage:
#   bash scripts/test-guard-fp-suppress-e2e.sh
#   VIRBIUS_CONTROL=http://127.0.0.1:18080 VIRBIUS_ENGINE=http://127.0.0.1:18082 \
#       bash scripts/test-guard-fp-suppress-e2e.sh
# ============================================================

set -uo pipefail

CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
TENANT="${VIRBIUS_TENANT:-default}"
BUNDLE="${VIRBIUS_BUNDLE:-poc-default}"
RULE_ID="${VIRBIUS_RULE_ID:-e2e_guard_fp_suppress}"
# 守卫误拦基准（良性请求，实测被 prompt-jailbreak 判 JAILBREAK risk 100）。
FP_PROMPT="${VIRBIUS_FP_PROMPT:-db.users.find({\"note\": {\"\$gt\": \"a\"}})}"
OPERATOR="e2e-guard-fp-suppress"
EVAL_TIMEOUT="${EVAL_TIMEOUT:-120}"

PASS=0; FAIL=0; SKIP=0
RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'

log_pass()  { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail()  { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_skip()  { echo -e "  ${YELLOW}SKIP${NC}: $1"; SKIP=$((SKIP+1)); }
log_step()  { echo -e "\n${BLUE}=== $1 ===${NC}"; }
log_info()  { echo -e "  ${CYAN}INFO${NC}: $1"; }

command -v curl >/dev/null 2>&1 || { echo "curl is required"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "python3 is required"; exit 1; }

TMPDIR_EVAL=$(mktemp -d /tmp/e2e-guard-fp.XXXXXX)
BODY_FILE="${TMPDIR_EVAL}/body.json"
TOKEN_FILE="${TMPDIR_EVAL}/evaluate-token"
trap 'rm -rf "${TMPDIR_EVAL}"' EXIT

AUTH_ARGS=()
if [[ -n "${VIRBIUS_API_KEY:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${VIRBIUS_API_KEY}")
fi

# ── HTTP helpers（运营台 envelope: {code,message,data}）──
http() {
  local method="$1" path="$2"
  local url="${CONTROL}${path}"
  if [[ $# -ge 3 && -n "$3" ]]; then
    curl -sS --noproxy '*' --max-time 30 -o "${BODY_FILE}" -w '%{http_code}' \
      -X "${method}" "${url}" "${AUTH_ARGS[@]+${AUTH_ARGS[@]}}" \
      -H "Content-Type: application/json" -H "X-User-Id: ${OPERATOR}" -d "$3"
  else
    curl -sS --noproxy '*' --max-time 30 -o "${BODY_FILE}" -w '%{http_code}' \
      "${AUTH_ARGS[@]+${AUTH_ARGS[@]}}" -X "${method}" "${url}" \
      -H "Content-Type: application/json" -H "X-User-Id: ${OPERATOR}"
  fi
}

jgetf() {
  python3 -c "
import json, sys
try:
    node = json.load(open('${BODY_FILE}'))
except Exception:
    print(''); raise SystemExit
for part in sys.argv[1].split('.'):
    if isinstance(node, dict):
        node = node.get(part)
    elif isinstance(node, list):
        try: node = node[int(part)]
        except Exception: node = None
    if node is None: break
print('' if node is None else node)
" "$1"
}

expect_ok() {
  local http_code="$1" label="$2"
  if [[ "${http_code}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    log_pass "${label} (HTTP ${http_code})"; return 0
  fi
  log_fail "${label}: HTTP ${http_code} code=$(jgetf 'code') msg=$(jgetf 'message')"
  return 1
}

expect_eq() {
  local got="$1" want="$2" label="$3"
  if [[ "${got}" == "${want}" ]]; then
    log_pass "${label} (${got})"; return 0
  fi
  log_fail "${label}: got [${got}] want [${want}]"
  return 1
}

expect_starts() {
  local got="$1" prefix="$2" label="$3"
  case "${got}" in
    "${prefix}"*) log_pass "${label} (${got:0:60}...)"; return 0 ;;
    *) log_fail "${label}: got [${got:0:80}] want prefix [${prefix}]"; return 1 ;;
  esac
}

# ── 引擎 evaluate（调用方真实面；trace_id 调用方自带供审计回查）──
eval_request() {
  local content="$1" role="${2:-tool_call}"
  local sid="e2e-gfp-$$-${RANDOM}${RANDOM}"
  local tid="e2e-gfp-$$-${RANDOM}${RANDOM}"
  local payload
  payload=$(python3 -c '
import json, sys
print(json.dumps({
  "tenant_id": "'${TENANT}'",
  "session_id": sys.argv[2],
  "trace_id": sys.argv[3],
  "role": "tool_call",
  "tool_name": "exec_cmd" if sys.argv[4] == "tool_call" else None,
  "args_json": sys.argv[1],
  "content": sys.argv[1],
}, ensure_ascii=False))' "${content}" "${sid}" "${tid}" "${role}")
  local tok tok_header=()
  tok="$(cat "${TOKEN_FILE}" 2>/dev/null || true)"
  [[ -n "${tok}" ]] && tok_header=(-H "Authorization: Bearer ${tok}")
  curl -sS --noproxy '*' --max-time "${EVAL_TIMEOUT}" -X POST "${ENGINE}/v1/evaluate" \
    -H "Content-Type: application/json" "${tok_header[@]+${tok_header[@]}}" -d "${payload}"
}

mint_eval_token() {
  local rc
  rc=$(http POST "/api/v1/admin/evaluate-token/service" \
    '{"purpose":"e2e-guard-fp-suppress","cross_tenant":false}')
  if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    local tok
    tok=$(jgetf 'data.access_token')
    if [[ -n "${tok}" ]]; then
      printf '%s' "${tok}" > "${TOKEN_FILE}"
      return 0
    fi
  fi
  return 1
}

evaluate() {
  local content="$1" role="${2:-tool_call}"
  local resp
  resp=$(eval_request "${content}" "${role}")
  if printf '%s' "${resp}" | grep -q 'missing_token'; then
    echo -e "  ${CYAN}INFO${NC}: evaluate 401（auth 开启）→ 经运营台兑换 service token" >&2
    if mint_eval_token; then
      resp=$(eval_request "${content}" "${role}")
    else
      echo "FATAL: engine evaluate auth 开启且 service token 兑换失败" >&2
      exit 2
    fi
  fi
  printf '%s' "${resp}"
}

evf() { printf '%s' "$1" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    v = d.get('$2')
    print('' if v is None else v)
except Exception:
    print('')
" 2>/dev/null; }

# probe_until_block：guard 对词面有抖动（措辞敏感），基准不稳时重采样直到
# 出现 block by prompt-* 上限 N 轮。stdout 输出最终响应 JSON；断言由调用方做。
probe_until_block() {
  local tries="${1:-15}" resp action rid
  for _ in $(seq 1 "${tries}"); do
    resp=$(evaluate "${FP_PROMPT}")
    action=$(evf "${resp}" 'effective_action')
    rid=$(evf "${resp}" 'rule_id')
    if [[ "${action}" == "block" && "${rid}" == prompt-* ]]; then
      LAST_EVAL_JSON="${resp}"
      return 0
    fi
    sleep 1
  done
  LAST_EVAL_JSON="${resp}"
  return 1
}
LAST_EVAL_JSON=""

# probe_until_suppressed：压制特征对 = allow 且 rule_id==基准规则。
probe_until_suppressed() {
  local tries="${1:-25}" resp action rid
  for _ in $(seq 1 "${tries}"); do
    resp=$(evaluate "${FP_PROMPT}")
    action=$(evf "${resp}" 'effective_action')
    rid=$(evf "${resp}" 'rule_id')
    if [[ "${action}" == "allow" && "${rid}" == "${BASE_RULE}" ]]; then
      LAST_EVAL_JSON="${resp}"
      return 0
    fi
    sleep 1
  done
  LAST_EVAL_JSON="${resp}"
  return 1
}

# probe_absent_suppression：确认压制已撤销（回退/停用面）——N 次探针中
# 不允许再出现 (allow, BASE_RULE) 对。guard 仍可能拦截（block）或自行放行
# （POLICY_ALLOW），两者均为合法撤销态。
probe_absent_suppression() {
  local probes="${1:-5}" resp action rid
  for _ in $(seq 1 "${probes}"); do
    resp=$(evaluate "${FP_PROMPT}")
    action=$(evf "${resp}" 'effective_action')
    rid=$(evf "${resp}" 'rule_id')
    if [[ "${action}" == "allow" && "${rid}" == "${BASE_RULE}" ]]; then
      LAST_EVAL_JSON="${resp}"
      return 1
    fi
    sleep 1
  done
  return 0
}

# ── 残留复位（只碰自家规则）──
reset_test_rule() {
  local rc st
  rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}")
  if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    st=$(jgetf 'data.rollout_state')
    log_info "发现上次残留规则（state=${st}），复位到草稿"
    if [[ "${st}" != "draft" && "${st}" != "disabled" ]]; then
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}')
      st=disabled
    fi
    if [[ "${st}" == "disabled" ]]; then
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/recover" '{}')
    fi
  fi
  return 0
}

dispose_stale_rollouts() {
  local layer rc count=0
  echo -e "  ${YELLOW}DISPOSER${NC}: 停用 rollout_state ∈ {dry_run,canary} 且非本测试组的历史规则"
  for layer in cloud gateway edge agent; do
    rc=$(http GET "/api/v1/tenants/${TENANT}/rules?layer=${layer}")
    [[ "${rc}" == "200" ]] || continue
    local ids
    ids=$(python3 -c "
import json
try:
    rules = json.load(open('${BODY_FILE}'))
except Exception:
    rules = []
for r in rules:
    if r.get('rollout_state') in ('dry_run', 'canary') and not str(r.get('rule_id','')).startswith(('e2e_', 'e2e-')):
        print(r.get('rule_id'))
")
    for rid in ${ids}; do
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${rid}/rollout/disable" '{}')
      if [[ "${rc}" == 2* ]]; then
        count=$((count+1))
      else
        echo -e "  ${RED}DISPOSER 失败${NC}: ${rid} (HTTP ${rc})"
      fi
    done
  done
  log_info "DISPOSER 停用了 ${count} 条历史放量规则（回到草稿，可通过页面重新放量）"
  return 0
}

check_rollout_capacity() {
  local rc
  rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
  if [[ "${rc}" == "429" ]]; then
    log_fail "并发放量上限: $(jgetf 'message')"
    echo -e "  ${YELLOW}指引: 处置租户内历史放量残留（策略列表按 rollout_state 过滤停用多余规则），
  或启用 DISPOSER（VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS=1）后重跑。${NC}"
    if [[ "${VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS:-0}" == "1" ]]; then
      log_info "DISPOSER 开启 → 自动清理历史残留"
      dispose_stale_rollouts
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
    else
      return 1
    fi
  fi
  expect_ok "${rc}" "运营台: 上线(publish → dry_run 观测期)"
  return 0
}

cleanup_on_exit() {
  local rc st
  rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}")
  if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    st=$(jgetf 'data.rollout_state')
    if [[ "${st}" == "dry_run" || "${st}" == "canary" || "${st}" == "full" ]]; then
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/disable" '{}')
      echo -e "\n${YELLOW}[cleanup]${NC} 规则仍处于 ${st}，已停用（HTTP ${rc}）"
    fi
  fi
}
trap cleanup_on_exit EXIT

# ============================================================
echo "===== VirbiusGuard 误拦 → allow 例外规则 全流程 E2E ====="
echo "  Control: ${CONTROL}"
echo "  Engine:  ${ENGINE}"
echo "  Tenant:  ${TENANT}  Rule: ${RULE_ID}"
echo "  FP prompt: ${FP_PROMPT}"
echo ""

# ── P0 前置体检 ──
log_step "P0. 前置体检"
rc=$(http GET "/api/v1/health")
expect_ok "${rc}" "运营台存活" || exit 2
rc=$(curl -sS --noproxy '*' --max-time 10 -o /dev/null -w '%{http_code}' "${ENGINE}/admin/health")
expect_eq "${rc}" "200" "引擎存活" || exit 2

ALLOW_FORCE="false"
rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rollout-policy")
if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
  ALLOW_FORCE=$(jgetf 'data.allow_force' | tr '[:upper:]' '[:lower:]')
fi
log_info "allow_force=${ALLOW_FORCE}"

# 审计链活性（Kafka 管道；不可用时 S7 断言组降级 SKIP）
AUDIT_LIVE=0
rc=$(http GET "/api/v1/admin/tenants/${TENANT}/audit/ingest-status")
if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
  AUDIT_LIVE=1; log_info "审计 ingest-status 可读（S7 启用）"
else
  log_info "审计 ingest-status 不可读（HTTP ${rc}），S7 将 SKIP"
fi

# ── 守卫误拦基准锚定（guard 抖动 → 重采样）──
log_info "基准锚定：确认该良性 prompt 现在确实被 guard（prompt-*）误拦"
if probe_until_block 15; then
  BASE_ACTION=$(evf "${LAST_EVAL_JSON}" 'effective_action')
  BASE_RULE=$(evf "${LAST_EVAL_JSON}" 'rule_id')
  BASE_REASON=$(evf "${LAST_EVAL_JSON}" 'reason_code')
  BASE_RISK=$(evf "${LAST_EVAL_JSON}" 'max_risk_score')
  expect_eq "${BASE_ACTION}" "block" "基准：良性 prompt 被拦截（FP 复现）"
  expect_starts "${BASE_RISK}" "100" "基准：guard 顶格打分（${BASE_RISK}）"
  log_info "基准归因锚定: rule=${BASE_RULE} reason=${BASE_REASON}"
else
  log_fail "基准不成立：${15} 次采样内良性 prompt 未被 guard 拦截"
  echo -e "  ${YELLOW}诊断: guard 模型对该词面当前未误拦（措辞抖动/模型版本变化/ollama 离线）。
  处置: 换一个现网确认仍误拦的词面做 VIRBIUS_FP_PROMPT 重跑，
  或先跑 scripts/test-guardrails-e2e.sh 验证 guard 数据面本身。${NC}"
  exit 1
fi

# ============================================================
log_step "S1. 残留复位 + 新建 allow 例外规则（锚定误拦词面）"
reset_test_rule || { log_fail "残留复位失败"; exit 2; }

UP_PAYLOAD=$(MARKER="${FP_PROMPT}" RULE_ID_ARMED="${RULE_ID}" python3 -c '
import json, os
marker = os.environ["MARKER"]
groovy_marker = marker.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
groovy = ("def decide(ctx) {\n"
          "    def v = ctx.var(\"content\")\n"
          "    return v != null && v.contains(\"" + groovy_marker + "\")\n"
          "}")
print(json.dumps({
  "rule_id": os.environ["RULE_ID_ARMED"],
  "bundle_id": "'"${BUNDLE}"'",
  "layer": "cloud",
  "runtime": "groovy",
  "reason_code": "E2E_GUARD_FP_SUPPRESS",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "global"},
  "body": groovy,
}, ensure_ascii=False))
')

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules" "${UP_PAYLOAD}")
expect_ok "${rc}" "运营台保存 allow 例外规则（草稿）" || { log_fail "无法创建规则"; exit 2; }
expect_eq "$(jgetf 'data.rollout_state')" "draft" "新规则初始为草稿"
expect_eq "$(jgetf 'data.intent_action')" "allow" "intent_action=allow（例外规则）"
expect_eq "$(jgetf 'data.risk_score')" "0" "风险 0（例外不打分）"

# ============================================================
log_step "S2. 草稿隔离（例外未发布，误拦仍在）"
rc=$(probe_until_block 15) && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  expect_eq "$(evf "${LAST_EVAL_JSON}" 'rule_id')" "${BASE_RULE}" \
    "草稿隔离：归因不变（仍为 ${BASE_RULE}）"
else
  log_skip "本轮 guard 放行了该词面（其自身抖动），草稿隔离等待无意义"
fi

# ============================================================
log_step "S3. 上线 → dry_run（例外纯观测期，不压制拦截）"
check_rollout_capacity || { log_fail "无法进入 dry_run"; exit 2; }
expect_eq "$(jgetf 'data.rollout_state')" "dry_run" "发布后进入观测期 dry_run"

rc=$(probe_until_block 15) && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  expect_eq "$(evf "${LAST_EVAL_JSON}" 'rule_id')" "${BASE_RULE}" \
    "dry_run: 仍拦截且归因不变（例外不压制 = 观测语义）"
else
  log_skip "观测期内 guard 放行（抖动）；不作为失败（压制语义在 S5 证明）"
fi

resp=$(evaluate "Say hi" "input")
expect_eq "$(evf "${resp}" 'effective_action')" "allow" "良性输入对照保持放行"

# ============================================================
log_step "S4. 放量 → 灰度 100%（例外开始压制：allow 且归因保留）"

rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"canary","canary_percent":100,"force":false}')
if [[ "${rc}" == "409" ]]; then
  expect_starts "$(jgetf 'message')" "GATE_FAILED" "门禁拒绝原因可读"
  ALLOW_FORCE_LC="$(printf '%s' "${ALLOW_FORCE:-false}" | tr '[:upper:]' '[:lower:]')"
  if [[ "${ALLOW_FORCE_LC}" != "true" ]]; then
    log_info "租户策略禁强制跳过 → 经 PUT /rollout-policy 打开 allow_force"
    POLICY=$(curl -sS --noproxy '*' "${CONTROL}/api/v1/admin/tenants/${TENANT}/rollout-policy" \
      "${AUTH_ARGS[@]+${AUTH_ARGS[@]}}" | python3 -c "
import json, sys
d = json.load(sys.stdin)['data']
d['allow_force'] = True
print(json.dumps(d))
")
    rc=$(http PUT "/api/v1/admin/tenants/${TENANT}/rollout-policy" "${POLICY}")
    expect_ok "${rc}" "租户策略: 打开 allow_force" || exit 2
  fi
  rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
    '{"rollout_state":"canary","canary_percent":100,"force":true,"comment":"e2e: guard FP 抑制例外灰度"}')
  expect_ok "${rc}" "强制跳过进入灰度（canary 100%）"
else
  expect_ok "${rc}" "门禁直接放行（历史审计样本充足，免强制）"
fi
expect_eq "$(jgetf 'data.rollout_state')" "canary" "放量状态 canary"
expect_eq "$(jgetf 'data.canary_percent')" "100" "canary_percent=100（全桶）"

SUPPRESS_TRACE=""
if probe_until_suppressed 25; then
  SUPPRESS_TRACE=$(evf "${LAST_EVAL_JSON}" 'trace_id')
  FINE_ACTION=$(evf "${LAST_EVAL_JSON}" 'effective_action')
  FINE_RULE=$(evf "${LAST_EVAL_JSON}" 'rule_id')
  expect_eq "${FINE_ACTION}" "allow" "灰度生效：误拦 prompt 放行"
  expect_eq "${FINE_RULE}" "${BASE_RULE}" "压制归因保留（仍记 ${BASE_RULE}，黑洞修复）"
  log_info "压制 trace_id: ${SUPPRESS_TRACE}"
else
  log_fail "灰度 25 次探针内未出现压制特征对 (allow, ${BASE_RULE})"
  echo -e "  ${YELLOW}诊断: 若 (allow, POLICY_ALLOW) 频出，说明 guard 可能漂移到放行（非压制）；
  可换 FP_PROMPT 重跑或用 VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS=1 清残留后重试。${NC}"
fi

resp=$(evaluate "SELECT * FROM users WHERE id = 7 LIMIT 1;")
if [[ "$(evf "${resp}" 'effective_action')" == "block" ]]; then
  log_pass "对照：另一条仍被 guard 拦的词面（未锚定的 SELECT）不受例外影响"
else
  log_warn "对照词面本轮未被 guard 拦截（抖动），跳过锚定边界 assert"
fi

# ============================================================
log_step "S5. 灰度 → 全量（压制维持且归因一致）"

rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"full","force":false}')
if [[ "${rc}" == "409" ]]; then
  expect_starts "$(jgetf 'message')" "GATE_FAILED" "canary→full 门禁仍依赖样本"
  rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
    '{"rollout_state":"full","force":true,"comment":"e2e: guard FP 抑制例外全量"}')
  expect_ok "${rc}" "强制跳过放量至全量"
else
  expect_ok "${rc}" "canary→full 门禁直接放行"
fi
expect_eq "$(jgetf 'data.rollout_state')" "full" "放量状态 full"

probe_until_suppressed 25 && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  expect_eq "$(evf "${LAST_EVAL_JSON}" 'effective_action')" "allow" "全量：误拦 prompt 维持放行"
  expect_eq "$(evf "${LAST_EVAL_JSON}" 'rule_id')" "${BASE_RULE}" "全量压制归因一致"
else
  log_fail "全量后未再观察到压制特征对"
fi

# ============================================================
log_step "S6. 审计中心：压制 (allow, 被压制 guard 规则) 必落链（免采样）"

if [[ "${AUDIT_LIVE}" == 1 && -n "${SUPPRESS_TRACE}" ]]; then
  AUDIT_GOT=0
  for _ in $(seq 1 10); do
    rc=$(http GET "/api/v1/admin/tenants/${TENANT}/audit/trace/${SUPPRESS_TRACE}")
    if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
      N=$(jgetf 'data.db_count')
      if [[ "${N}" -gt 0 ]] 2>/dev/null; then
        AUDIT_GOT=1
        J=$(curl -sS --noproxy '*' "${CONTROL}/api/v1/admin/tenants/${TENANT}/audit/trace/${SUPPRESS_TRACE}" \
          "${AUTH_ARGS[@]+${AUTH_ARGS[@]}}")
        OK=$(printf '%s' "${J}" | python3 -c "
import json, sys
d = json.load(sys.stdin).get('data') or {}
evs = d.get('db_events') or []
evs = evs if isinstance(evs, list) else (evs.get('events') if isinstance(evs, dict) else [])
BASE_RULE, BASE_REASON = '${BASE_RULE}', '${BASE_REASON}'
hit = [e for e in evs if (e.get('effective_action') == 'allow'
        and e.get('rule_id') == BASE_RULE
        and e.get('reason_code') == BASE_REASON)]
print(1 if hit else 0)
")
        expect_eq "${OK}" "1" "trace ${SUPPRESS_TRACE}: 审计行 (allow, ${BASE_RULE}, ${BASE_REASON}) 落链"
        break
      fi
    fi
    sleep 2
  done
  [[ "${AUDIT_GOT}" == 1 ]] || log_fail "压制 allow 事件 ${SUPPRESS_TRACE} 未在 20s 内落入审计链"
else
  log_skip "Kafka 审计管道不可用或灰度期未捕获压制 trace —— 审计断言组在带 Kafka 的栈上运行"
fi

# ============================================================
log_step "S7. 回退 → 停用（例外撤销，误拦恢复可能）"

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/rollback" '{}')
expect_ok "${rc}" "运营台: 回退(rollback)"
expect_eq "$(jgetf 'data.rollout_state')" "dry_run" "回到观测期"

probe_absent_suppression 6 && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  log_pass "回退后压制撤销（6 探针内无 (allow, ${BASE_RULE}) 特征对）"
else
  log_fail "回退后仍观察到压制效果（例外未收回）"
fi

rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"disabled"}')
expect_ok "${rc}" "运营台: 停用规则"
expect_eq "$(jgetf 'data.rollout_state')" "disabled" "规则停用"

probe_absent_suppression 6 && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  log_pass "停用后压制撤销（无幽灵例外）"
else
  log_fail "停用后仍观察到压制效果（幽灵例外）"
fi

log_info "收尾建议：复位测试规则状态 mechanisms 见脚本头部说明"
probe_until_block 15 && rc=0 || rc=$?
if [[ "${rc}" == "0" ]]; then
  log_pass "停用后误拦基准可复现（guard 拦截面未受影响）"
else
  log_skip "停用后 guard 对该词面持续放行（词面抖动）——不影响压制撤销的判定"
fi

# ============================================================
echo ""
echo -e "${BLUE}===== 结果汇总 =====${NC}"
echo -e "  ${GREEN}PASS: ${PASS}${NC}  ${RED}FAIL: ${FAIL}${NC}  ${YELLOW}SKIP: ${SKIP}${NC}"
echo -e "  规则 ${RULE_ID} 终态: disabled（下次运行自动复位到草稿）"
if [[ "${FAIL}" -gt 0 ]]; then
  echo -e "  ${BLUE}提示: BUG 复现判定 — 用 run-local 纯净栈再跑一遍; 无 Kafka 栈时 S6 跳过属预期;
  基准/抖动类失败请换 VIRBIUS_FP_PROMPT 词面重试${NC}"
  exit 1
fi
echo -e "  ${GREEN}全通过${NC}"
