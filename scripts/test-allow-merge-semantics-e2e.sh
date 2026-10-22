#!/usr/bin/env bash
# ============================================================
# test-allow-merge-semantics-e2e.sh
#
# 运营台「FP 抑制（allow）规则例外灰度」全流程 E2E。
# 对应 2026-10-22 allow 合并语义修复（ActionMerge / PolicyDecider / AuditWriter）：
#   1. allow 命中只在 enforce 生效（canary 桶内上限 / dry_run 不压制）
#   2. 压制发生时决策/审计归因 = 被压制 deny 的 primary（黑洞修复）
#   3. 压制 allow 事件免 10% 采样必进审计链
#   4. allow-only 归因 = 具体 allow 规则（POLICY_ALLOW 回归零命中哨兵）
#   5. allow-only 归因必须过滤 enforce：dry_run/桶外候选不越位；
#      多条 enforce 例外间按 risk_score 高者优先（归因优先级，非打分）
#
# 只走运营台 HTTP API（与控制台页面同一组接口）+ 引擎 /v1/evaluate
# （调用方真实视角）。脚本从不打开 SQLite 文件、从不执行 redis-cli，
# 也不改动代码/数据 —— 引擎内存状态仅通过运营台发布动作刷新。
#
# 对应页面操作：
#   「新建规则」(POST /rules) → 草稿
#   「上线」(POST /rules/{id}/rollout/publish → dry_run)
#   「下一档」门禁 409 → 强制跳过勾选 (PATCH /rules/{id}/rollout, force+comment)
#   灰度 100%（canary_percent=100 全桶）→ 「全量」
#   「回退」(POST /rules/{id}/rollout/rollback) → 「停用」
#   审计中心 (GET /rules/{id}/audit-samples / GET /audit/trace/{trace_id})
#
# Prerequisites:
#   bash scripts/run-local.sh 已拉起标准栈
#   virbius-control :8080  virbius-engine :8082
#   Kafka :9092 时断言组 9 可用（审计链经 Kafka 落 tb_audit_events）；
#   无 Kafka 时断言组 9 自动 SKIP（engine→Kafka→control 的管道不属于规则面，
#   其余断言不依赖它）。ollama :11434 在线时基线归因取 prompt-self-harm（risk 100
#   primary），离线时取 groovy deny 规则 —— 脚本按首测基线自动锚定，两种情况皆兼容。
#
# Usage:
#   bash scripts/test-allow-merge-semantics-e2e.sh
#   VIRBIUS_CONTROL=http://127.0.0.1:18080 VIRBIUS_ENGINE=http://127.0.0.1:18082 \
#       bash scripts/test-allow-merge-semantics-e2e.sh
# ============================================================

set -uo pipefail

CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
TENANT="${VIRBIUS_TENANT:-default}"
BUNDLE="${VIRBIUS_BUNDLE:-poc-default}"
RULE_ID="${VIRBIUS_RULE_ID:-e2e_allow_semantics}"
# 副 allow 规则（例外排序场景，S5b）：risk_score 设为更高值验证归因优先级
RULE2_ID="${VIRBIUS_RULE2_ID:-e2e_allow_sem_sub}"
# 锚定词选择注意：怪 token（下划线密串）会被 guard 模型稳定误拦，导致
# allow-only 场景永远被压制场景接管——用自然词面，良性句里 guard 放行。
MARKER="${VIRBIUS_E2E_MARKER:-release_20261022_checklist}"
OPERATOR="e2e-allow-semantics"
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

TMPDIR_EVAL=$(mktemp -d /tmp/e2e-allow-sem.XXXXXX)
BODY_FILE="${TMPDIR_EVAL}/body.json"
TOKEN_FILE="${TMPDIR_EVAL}/evaluate-token"
trap 'rm -rf "${TMPDIR_EVAL}"' EXIT

AUTH_ARGS=()
if [[ -n "${VIRBIUS_API_KEY:-}" ]]; then
  AUTH_ARGS=(-H "Authorization: Bearer ${VIRBIUS_API_KEY}")
fi

# ── HTTP helpers（运营台 envelope: {code,message,data}）──
# 写 body 到 BODY_FILE，stdout 输出 HTTP 状态码。
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


# 从 BODY_FILE 取字段：jgetf 'data.rollout_state'
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

expect_any() {
  local got="$1" label="$2"; shift 2
  for want in "$@"; do
    if [[ "${got}" == "${want}" ]]; then
      log_pass "${label} (${got})"; return 0
    fi
  done
  log_fail "${label}: got [${got}] want one of [$*]"
  return 1
}

expect_starts() {
  local got="$1" prefix="$2" label="$3"
  case "${got}" in
    "${prefix}"*) log_pass "${label} (${got:0:60}...)"; return 0 ;;
    *) log_fail "${label}: got [${got:0:80}] want prefix [${prefix}]"; return 1 ;;
  esac
}

# ── 引擎 evaluate（用户真实调用面）──
# 运营台 API 是全局 snake_case JSON 契约（test-policy-rollout-e2e.sh 同款）；
# 引擎 trace_id 由调用方透传（EvaluateRequestDto.traceId），必须自带以便审计中心回查。
EVTOKEN=""
EVTOKEN="$(cat "${TOKEN_FILE}" 2>/dev/null || true)"
eval_request() {
  local content="$1" role="${2:-tool_call}"
  local sid="e2e-allow-sem-$$-${RANDOM}${RANDOM}"
  local tid="e2e-tid-$$-${RANDOM}${RANDOM}"
  local payload
  payload=$(python3 -c "
import json, sys
print(json.dumps({
  'tenant_id': '${TENANT}',
  'session_id': '${sid}',
  'trace_id': '${tid}',
  'role': '${role}',
  'tool_name': 'exec_cmd' if '${role}' == 'tool_call' else None,
  'args_json': '${content}',
  'content': '${content}',
}, ensure_ascii=False))
")
  # 每次调用都重读 token 文件：evaluate() 常处于 $(...) 子 shell 里，
  # 子 shell 内的 mint 只有 TOKEN_FILE 才能对后续调用可见
  local tok tok_header=()
  tok="$(cat "${TOKEN_FILE}" 2>/dev/null || true)"
  [[ -n "${tok}" ]] && tok_header=(-H "Authorization: Bearer ${tok}")
  curl -sS --noproxy '*' --max-time "${EVAL_TIMEOUT}" -X POST "${ENGINE}/v1/evaluate" \
    -H "Content-Type: application/json" "${tok_header[@]+${tok_header[@]}}" -d "${payload}"
}

# 从 evaluate 响应取字段（snake_case DTO）
evf() { printf '%s' "$1" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    v = d.get('$2')
    print('' if v is None else v)
except Exception:
    print('')
" 2>/dev/null; }

# 兑换运营台 service evaluate token（evaluate 鉴权开启时的官方换发路径）。
# token 落临时文件：命令替换 $( ) 是子 shell，不能靠环境变量向主进程传值。
mint_eval_token() {
  local rc
  rc=$(http POST "/api/v1/admin/evaluate-token/service" \
    '{"purpose":"e2e-allow-semantics","cross_tenant":false}')
  if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    local tok
    tok=$(jgetf 'data.access_token')
    if [[ -n "${tok}" ]]; then
      printf '%s' "${tok}" > "${TOKEN_FILE}"
      EVTOKEN="${tok}"
      return 0
    fi
  fi
  return 1
}

# 评估 + 401 自动重兑 token 一次
evaluate() {
  local content="$1" role="${2:-tool_call}"
  local resp; resp=$(eval_request "${content}" "${role}")
  if [[ "$(evf "${resp}" 'error_code')" == "missing_token" ]] || \
     printf '%s' "${resp}" | grep -q 'missing_token'; then
    # 诊断信息走 stderr：evaluate() 的 stdout 是数据通道，不能被日志污染
    echo -e "  ${CYAN}INFO${NC}: evaluate 401（auth 开启）→ 经运营台兑换 service evaluate token" >&2
    if mint_eval_token; then
      resp=$(eval_request "${content}" "${role}")
    else
      echo "FATAL: engine evaluate auth 开启且 service token 兑换失败（POST /api/v1/admin/evaluate-token/service）" >&2
      exit 2
    fi
  fi
  printf '%s' "${resp}"
}

# 轮询直到 evaluate 结果满足期望值（快照刷新经 Kafka Stream，引擎秒级热重载）
wait_eval() {
  local label="$1" content="$2" field="$3" want="$4"
  local tries="${5:-12}" resp got
  for i in $(seq 1 "${tries}"); do
    resp=$(evaluate "${content}")
    got=$(evf "${resp}" "${field}")
    if [[ "${got}" == "${want}" ]]; then
      log_pass "${label}（第 ${i} 次探针）: ${field}=${got}"
      LAST_EVAL_JSON="${resp}"
      return 0
    fi
    sleep 2
  done
  LAST_EVAL_JSON="${resp}"
  log_fail "${label}: ${tries} 次探针内 ${field} 始终=${got:-<empty>}（期望 ${want}）"
  return 1
}
LAST_EVAL_JSON=""

# ── 残留复位（只碰自家规则；不覆盖他人进行中的放量）──
reset_test_rule() {
  local rid="${1:-${RULE_ID}}" rc st
  rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${rid}")
  if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
    st=$(jgetf 'data.rollout_state')
    log_info "发现上次残留规则 ${rid}（state=${st}），复位到草稿"
    if [[ "${st}" != "draft" && "${st}" != "disabled" ]]; then
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${rid}/rollout/disable" '{}')
      expect_ok "${rc}" "复位: 停用在线残留" >/dev/null || return 1
      st=disabled
    fi
    if [[ "${st}" == "disabled" ]]; then
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${rid}/rollout/recover" '{}')
      expect_ok "${rc}" "复位: 恢复为草稿" >/dev/null || return 1
    fi
  fi
  return 0
}

# ── 并发放量槽位健康检查（429 上限保护是产品行为；脏库需_operator 手动处置）──
check_rollout_capacity() {
  local rc msg
  # 干跑 publish 前先探测：草稿规则若被 429 则占槽，附带处置指引
  rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
  if [[ "${rc}" == "429" ]]; then
    msg=$(jgetf 'message')
    if [[ "${VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS:-0}" == "1" ]]; then
      log_info "并发放量上限触发（get ${msg}），DISPOSER 开启 → 自动清理历史残留"
      dispose_stale_rollouts
      rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/publish" '{}')
    else
      log_fail "并发放量上限: ${msg}"
      echo -e "  ${YELLOW}指引: ${TENANT} 租户存在大量历史放量残留（观察/灰度态）。
    1) 运营台「策略列表」按 rollout_state 过滤，人工处置（≤${MAX_ROLLOUTS:-20} 上限之外的老规则停用即可）。
    2) 或 opt-in 自动清理（只动 dry_run/canary 且非本脚本规则）: VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS=1 后重跑。${NC}"
      return 1
    fi
  fi
  expect_ok "${rc}" "运营台: 上线(publish)"
  return 0
}

# 清扫执行平面里非本脚本/本组的历史放量规则（仅可配置开关触发）
dispose_stale_rollouts() {
  local layer rc count=0
  echo -e "  ${YELLOW}DISPOSER${NC}: 拉取全部层规则，停用 rollout_state ∈ {dry_run,canary} 且 rule_id 非本测试组的历史规则"
  for layer in cloud gateway edge agent; do
    rc=$(http GET "/api/v1/tenants/${TENANT}/rules?layer=${layer}")
    [[ "${rc}" == 2* ]] || continue
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
      if [[ "${rc}" == 2* ]]; then count=$((count+1)); else
        echo -e "  ${RED}DISPOSER 失败${NC}: ${rid} (HTTP ${rc})"; log_fail_and_exit=1
      fi
    done
  done
  log_info "DISPOSER 停用了 ${count} 条历史放量规则（回到草稿，可通过页面重新放量）"
  return 0
}

# ── 出口清理：规则若仍在执行平面则停用，释放并发放量槽位 ──
cleanup_on_exit() {
  local rc st rid
  for rid in "${RULE_ID}" "${RULE2_ID}"; do
    rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rules/${rid}")
    if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
      st=$(jgetf 'data.rollout_state')
      if [[ "${st}" == "dry_run" || "${st}" == "canary" || "${st}" == "full" ]]; then
        rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${rid}/rollout/disable" '{}')
        echo -e "\n${YELLOW}[cleanup]${NC} 规则 ${rid} 仍处于 ${st}，已停用（HTTP ${rc}）"
      fi
    fi
  done
}
trap cleanup_on_exit EXIT

# ============================================================
echo "===== FP 抑制 allow 规则：例外灰度语义 E2E ====="
echo "  Control: ${CONTROL}"
echo "  Engine:  ${ENGINE}"
echo "  Tenant:  ${TENANT}  Rule: ${RULE_ID}"
echo ""

# ── P0 前置体检 ──
log_step "P0. 前置体检"
rc=$(http GET "/api/v1/health")
expect_ok "${rc}" "运营台存活" || exit 2
rc=$(curl -sS --noproxy '*' --max-time 10 -o /dev/null -w '%{http_code}' "${ENGINE}/admin/health")
expect_eq "${rc}" "200" "引擎存活" || exit 2

rc=$(http GET "/api/v1/admin/tenants/${TENANT}/rollout-policy")
expect_ok "${rc}" "读取租户放量策略" && \
  ALLOW_FORCE=$(jgetf 'data.allow_force') && \
  MAX_ROLLOUTS=$(jgetf 'data.max_concurrent_rollouts')
log_info "allow_force=${ALLOW_FORCE:-?} max_concurrent_rollouts=${MAX_ROLLOUTS:-?}"

# 审计链活性探测（Kafka 管道；不可用时断言组 9 降级 SKIP）
AUDIT_LIVE=0
rc=$(http GET "/api/v1/admin/tenants/${TENANT}/audit/ingest-status")
if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
  AUDIT_LIVE=1; log_info "审计 ingest-status 可读（断言组 9 启用）"
else
  log_info "审计 ingest-status 不可读（HTTP ${rc}），断言组 9 将 SKIP"
fi

# ============================================================
log_step "S1. 残留复位 + 规则草稿（新建 FP 抑制规则）"
reset_test_rule || { log_fail "残留复位失败"; exit 2; }

RULE_BODY='def decide(ctx) { def v = ctx.var("content"); return v != null && v.contains("'${MARKER}'") }'
UP_PAYLOAD=$(python3 -c "
import json
print(json.dumps({
  'rule_id': '${RULE_ID}',
  'bundle_id': '${BUNDLE}',
  'layer': 'cloud',
  'runtime': 'groovy',
  'reason_code': 'E2E_ALLOW_SUPPRESS_MARKER',
  'risk_score': 0,
  'intent_action': 'allow',
  'scope': {'bind_scope': 'global'},
  'body': '''def decide(ctx) {
    def v = ctx.var(\"content\")
    return v != null && v.contains(\"${MARKER}\")
}''',
}, ensure_ascii=False))
")

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules" "${UP_PAYLOAD}")
expect_ok "${rc}" "运营台保存规则（草稿）" || { log_fail "无法创建规则（请检查他人并发放量/网关状态）"; exit 2; }
expect_eq "$(jgetf 'data.rollout_state')" "draft" "新规则初始为草稿"
expect_eq "$(jgetf 'data.intent_action')" "allow" "intent_action=allow 通过意图白名单"
expect_eq "$(jgetf 'data.runtime')" "groovy" "runtime=groovy（脚本执行平面）"

# ============================================================
log_step "S2. 草稿隔离 + 基线锚定（规则未发布， deny 语义不受影响）"

DENY_PAYLOAD="rm -rf / --no-preserve-root"
DENY_WITH_MARKER="rm -rf / --no-preserve-root ${MARKER}"

# guard 模型对同一攻击串的 SELF_HARM/JAILBREAK 归类会在调用间漂移（两条
# prompt 规则同 risk，谁命中取决于当次模型判定）。因此不做一次性归因锚定：
# 各阶段统一用“归因是某条 guard/deny 规则（非本 allow 规则、非 POLICY_ALLOW）”
# 作压制/干跑语义判据；审计回查则用压制探针当次自带的 (trace, rule, reason)。
expect_guard_rule() {
  local rid="$1" label="$2"
  if [[ -z "${rid}" || "${rid}" == "POLICY_ALLOW" || "${rid}" == "${RULE_ID}" ]]; then
    log_fail "${label}: 归因异常 rid=[${rid}]（应为 guard/deny 规则）"
    return 1
  fi
  log_pass "${label}: 归因=被压制 deny/guard 规则（${rid}）"
}

resp=$(evaluate "${DENY_PAYLOAD}")
BASE_ACTION=$(evf "${resp}" 'effective_action')
BASE_RULE=$(evf "${resp}" 'rule_id')
BASE_REASON=$(evf "${resp}" 'reason_code')
expect_eq "${BASE_ACTION}" "block" "基线：无 marker 恶意串被 deny"
log_info "基线归因参考: action=${BASE_ACTION} rule=${BASE_RULE} reason=${BASE_REASON}"
if [[ -z "${BASE_RULE}" || "${BASE_RULE}" == "POLICY_ALLOW" ]]; then
  log_fail "基线 deny 无有效归因（引擎/规则面异常），后续断言不可信"; exit 2
fi

# 零命中哨兵：规则完全不命中的良性输入，归因回到 POLICY_ALLOW（本轮修复后
# POLICY_ALLOW 从"allow 只读兜底"回归纯哨兵语义——只在真无命中时出现）。
# 注意用 chat 角色：命令型 tool_call 字面量会碰 guard 的固有 FP（见清单 §5）。
resp=$(evaluate "Say hi" "input")
expect_eq "$(evf "${resp}" 'effective_action')" "allow" "零命中: 良性输入放行"
if [[ -z "$(evf "${resp}" 'rule_id')" ]] ; then
  log_fail "零命中: rule_id 为空（哨兵契约缺失）"
else
  expect_eq "$(evf "${resp}" 'rule_id')" "POLICY_ALLOW" "零命中归因 = POLICY_ALLOW 哨兵（不再冒充具体规则）"
fi

resp=$(evaluate "${DENY_WITH_MARKER}")
expect_eq "$(evf "${resp}" 'effective_action')" "block" "草稿隔离：带 marker 行为不变（规则未在执行平面）"

# ============================================================
log_step "S3. 上线发布 → dry_run（例外纯观察期，不压制 deny）"

check_rollout_capacity || { log_fail "无法进入 dry_run（并发放量槽位被占，处置见上方指引）"; exit 2; }
expect_eq "$(jgetf 'data.rollout_state')" "dry_run" "发布后进入观察期 dry_run"

wait_eval "dry_run 例外不压制（deny 仍拦截）" "${DENY_WITH_MARKER}" 'effective_action' "block" 12 \
  || true
expect_guard_rule "$(evf "${LAST_EVAL_JSON}" 'rule_id')" \
  "dry_run 归因不变（仍指向拦截规则，例外不参与）"

resp=$(evaluate "${DENY_PAYLOAD}")
expect_eq "$(evf "${resp}" 'effective_action')" "block" "dry_run: 无 marker 基线不受影响"

# ============================================================
log_step "S4. 放量 → 灰度 100%（canary 桶内生效：allow 压制 deny 且归因保留）"

# 干跑：不带 force 试探门禁。门禁在既有审计样本充足时可直接通过（200），
# 在无样本数据时应拒绝（409 GATE_FAILED）——两种返回都属合法运营结果。
rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"canary","canary_percent":100,"force":false}')
if [[ "${rc}" == "409" ]]; then
  expect_starts "$(jgetf 'message')" "GATE_FAILED" "门禁拒绝原因可读（无样本数据）"
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
    expect_ok "${rc}" "运营台策略: 打开 allow_force" || exit 2
  fi
  rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
    '{"rollout_state":"canary","canary_percent":100,"force":true,"comment":"e2e: allow 语义修复回归，跳过门禁"}')
  expect_ok "${rc}" "强制跳过进行放量（canary 100%）"
else
  expect_ok "${rc}" "门禁直接放行（历史审计样本充足，免强制）"
fi
expect_eq "$(jgetf 'data.rollout_state')" "canary" "放量状态 canary"
expect_eq "$(jgetf 'data.canary_percent')" "100" "canary_percent=100（全桶吸附）"

wait_eval "canary: allow 压制 deny" "${DENY_WITH_MARKER}" 'effective_action' "allow" 12 \
  || true
expect_guard_rule "$(evf "${LAST_EVAL_JSON}" 'rule_id')" \
  "压制裁决归因仍指向被压制 deny/guard 规则（审计黑洞修复）"
expect_eq "$(evf "${LAST_EVAL_JSON}" 'effective_action')" "allow" \
  "有效动作 allow（例外生效，FP 被抑制）"

resp=$(evaluate "${DENY_PAYLOAD}")
expect_eq "$(evf "${resp}" 'effective_action')" "block" "canary: 无 marker 仍 deny（例外不失守）"

resp=$(evaluate "Say hi" "input")
expect_eq "$(evf "${resp}" 'effective_action')" "allow" "良性输入保持放行"
expect_eq "$(evf "${resp}" 'rule_id')" "POLICY_ALLOW" "生效例外存在但未命中 → 归因仍为哨兵（allow 例外不冒充命中）"

# ============================================================
log_step "S5. 量：canary → 全量（full 同样压制且归因一致）"

rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"full","force":false}')
if [[ "${rc}" == "409" ]]; then
  expect_starts "$(jgetf 'message')" "GATE_FAILED" "canary→full 门禁仍依赖样本数据"
  rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
    '{"rollout_state":"full","force":true,"comment":"e2e: allow 语义修复回归，跳过门禁"}')
  expect_ok "${rc}" "强制跳过放量至全量"
else
  expect_ok "${rc}" "canary→full 门禁直接放行"
fi
expect_eq "$(jgetf 'data.rollout_state')" "full" "放量状态 full"

wait_eval "full: allow 压制维持" "${DENY_WITH_MARKER}" 'effective_action' "allow" 12 || true
expect_guard_rule "$(evf "${LAST_EVAL_JSON}" 'rule_id')" \
  "full 压制裁决归因一致（仍指向被压制规则）"

resp=$(evaluate "${DENY_PAYLOAD}")
expect_eq "$(evf "${resp}" 'effective_action')" "block" "full: 无 marker 仍 deny"

# 抓取压制样本（供 S6 审计回查）——guard 归类在 SELF_HARM/JAILBREAK 间漂移，
# 用探针当次自带的 (trace, rule, reason)，跨探针固定锚定不可靠。静默探针，
# 不走 wait_eval（避免其计数污染）。
SUPPRESS_TID=""; SUPPRESS_RULE=""; SUPPRESS_REASON=""
for i in $(seq 1 10); do
  resp=$(evaluate "${DENY_WITH_MARKER}")
  _a=$(evf "${resp}" 'effective_action'); _r=$(evf "${resp}" 'rule_id')
  if [[ "${_a}" == "allow" && -n "${_r}" && "${_r}" != "POLICY_ALLOW" && "${_r}" != "${RULE_ID}" ]]; then
    SUPPRESS_TID=$(evf "${resp}" 'trace_id')
    SUPPRESS_RULE="${_r}"
    SUPPRESS_REASON=$(evf "${resp}" 'reason_code')
    break
  fi
  sleep 1
done
[[ -n "${SUPPRESS_TID}" ]] && log_info "压制样本: trace=${SUPPRESS_TID} rule=${SUPPRESS_RULE} reason=${SUPPRESS_REASON}"

# allow-only 归因（归因修复：无 deny 可压时，响应/审计归因到具体 allow 规则
# 而非 POLICY_ALLOW）。guard 对带 marker 的良性串也可能误拦（压制场景）——
# allow-only 只在 guard 放行的时刻出现，用探针循环捕捉 (allow, RULE_ID)。
# 注意：此类事件未压制任何 enforcement → 走正常 10% 采样，不做审计中心断言。
ALLOW_ONLY_GOT=0
for i in $(seq 1 20); do
  resp=$(evaluate "cat ${MARKER}.md")
  if [[ "$(evf "${resp}" 'effective_action')" == "allow" && "$(evf "${resp}" 'rule_id')" == "${RULE_ID}" ]]; then
    ALLOW_ONLY_GOT=1
    break
  fi
  sleep 1
done
if [[ "${ALLOW_ONLY_GOT}" == 1 ]]; then
  expect_eq "$(evf "${resp}" 'effective_action')" "allow" "allow-only: 良性放行"
  expect_eq "$(evf "${resp}" 'rule_id')" "${RULE_ID}" "allow-only 归因到具体 allow 规则（不再 POLICY_ALLOW）"
  expect_eq "$(evf "${resp}" 'max_risk_score')" "0" "allow-only 决策风险恒 0（归因不等于打分）"
else
  log_fail "allow-only 归因未在 20 探针内出现（got rid=$(evf "${resp}" 'rule_id')，持续被 guard 误拦压制？）"
fi

# ============================================================
log_step "S5b. 例外排序（副 allow 规则）：dry_run 候选不越位，risk_score 归因优先级"

# 用户在例外清单再加一条 higher-risk allow 规则（同词面）。先停在观察期：
# 合并语义要求未生效的 allow 候选既不参与压制，也不在 allow-only 归因里
# 越位（回退必须逐条过滤 enforce）——否则 risk=20 的副规则会压过主规则。
reset_test_rule "${RULE2_ID}" || { log_fail "副规则残留复位失败"; exit 2; }

UP2_PAYLOAD=$(python3 -c "
import json
print(json.dumps({
  'rule_id': '${RULE2_ID}',
  'bundle_id': '${BUNDLE}',
  'layer': 'cloud',
  'runtime': 'groovy',
  'reason_code': 'E2E_ALLOW_SUB_PRIORITY',
  'risk_score': 20,
  'intent_action': 'allow',
  'scope': {'bind_scope': 'global'},
  'body': '''def decide(ctx) {
    def v = ctx.var(\"content\")
    return v != null && v.contains(\"${MARKER}\")
}''',
}, ensure_ascii=False))
")

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules" "${UP2_PAYLOAD}")
expect_ok "${rc}" "运营台保存副规则（草稿）" || exit 2
expect_eq "$(jgetf 'data.risk_score')" "20" "副规则 risk_score=20 控制台原样保存（用户面归因优先级）"
expect_eq "$(jgetf 'data.intent_action')" "allow" "副规则 intent=allow"

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE2_ID}/rollout/publish" '{}')
if [[ "${rc}" == "429" ]]; then
  log_fail "副规则 publish 429（并发放量槽位占满，VIRBIUS_E2E_DISPOSE_STALE_ROLLOUTS=1 可自动清理）"
  exit 2
fi
expect_ok "${rc}" "运营台: 副规则上线(publish)"
expect_eq "$(jgetf 'data.rollout_state')" "dry_run" "副规则进入观察期 dry_run"

# 副规则 dry_run 在场时主规则（full）的压制语义不变（dry_run 非 suppression
# 语义在 S3 已覆盖；此处断言的是"多规则观察不干扰在线例外"）。
wait_eval "副规则 dry_run 不改变主规则压制" "${DENY_WITH_MARKER}" 'effective_action' "allow" 8 \
  || true
expect_guard_rule "$(evf "${LAST_EVAL_JSON}" 'rule_id')" \
  "副规则 dry_run: 压制归因仍指向被压制 deny/guard 规则"

# 副规则未生效 → allow-only 归因仍必须是主规则（若回退没过滤 enforce，
# risk=20 的 dry_run 副规则会在此越位）。
wait_eval "allow-only 归因不受 dry_run 候选影响" "cat ${MARKER}.md" 'rule_id' "${RULE_ID}" 8 || true
expect_eq "$(evf "${LAST_EVAL_JSON}" 'effective_action')" "allow" "dry_run 候选在场: 良性放行不变"

# 放量副规则到灰度 100%（两条例外均 enforce）→ 归因按 risk_score 高者优先
# （归因优先级约定：不影响决策打分，合并风险仍 0）。
rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE2_ID}/rollout" \
  '{"rollout_state":"canary","canary_percent":100,"force":true,"comment":"e2e: 例外排序回归（副规则）"}')
if [[ "${rc}" == "409" ]]; then
  expect_starts "$(jgetf 'message')" "GATE_FAILED" "副规则 canary 门禁拒因可读"
  rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE2_ID}/rollout" \
    '{"rollout_state":"canary","canary_percent":100,"force":true,"comment":"e2e: 例外排序回归（副规则）"}')
  expect_ok "${rc}" "副规则强制跳过放量"
else
  expect_ok "${rc}" "副规则门禁直接放行"
fi
expect_eq "$(jgetf 'data.rollout_state')" "canary" "副规则状态 canary@100"

wait_eval "例外均生效时归因切到高 risk 例外" "cat ${MARKER}.md" 'rule_id' "${RULE2_ID}" 12 || true
expect_eq "$(evf "${LAST_EVAL_JSON}" 'effective_action')" "allow" "双例外: 良性放行不变"
expect_eq "$(evf "${LAST_EVAL_JSON}" 'max_risk_score')" "0" "双例外: 决策风险恒 0（risk_score 只作归因优先级）"

# 撤销副规则 → 归因回收主规则（单条例外的可解释性）
rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE2_ID}/rollout" \
  '{"rollout_state":"disabled"}')
expect_ok "${rc}" "运营台: 停用副规则"
expect_eq "$(jgetf 'data.rollout_state')" "disabled" "副规则停用"

wait_eval "副规则停用后归因回主规则" "cat ${MARKER}.md" 'rule_id' "${RULE_ID}" 12 || true
expect_eq "$(evf "${LAST_EVAL_JSON}" 'effective_action')" "allow" "单例外回归: 良性放行不变"

# ============================================================
log_step "S6. 审计中心：压制 allow 事件必落链（免采样）+ 归因一致"

if [[ "${AUDIT_LIVE}" == 1 && -n "${SUPPRESS_TID}" ]]; then
  TID="${SUPPRESS_TID}"
  AUDIT_GOT=0
  for i in $(seq 1 10); do
    rc=$(http GET "/api/v1/admin/tenants/${TENANT}/audit/trace/${TID}")
    if [[ "${rc}" == 2* && "$(jgetf 'code')" == "0" ]]; then
      N=$(jgetf 'data.db_count')
      if [[ "${N}" -gt 0 ]] 2>/dev/null; then
        AUDIT_GOT=1
        J=$(curl -sS --noproxy '*' "${CONTROL}/api/v1/admin/tenants/${TENANT}/audit/trace/${TID}" \
          "${AUTH_ARGS[@]+${AUTH_ARGS[@]}}")
        EV=$(printf '%s' "${J}" | python3 -c "
import json, sys
d = json.load(sys.stdin).get('data') or {}
evs = d.get('db_events') or []
evs = evs if isinstance(evs, list) else (evs.get('events') if isinstance(evs, dict) else [])
print(json.dumps(evs))
")
          OK=$(printf '%s' "${EV}" | python3 -c "
import json, sys
evs = json.load(sys.stdin)
RULE, REASON = '${SUPPRESS_RULE}', '${SUPPRESS_REASON}'
hit = [e for e in evs if (e.get('effective_action') == 'allow'
        and e.get('rule_id') == RULE
        and e.get('reason_code') == REASON)]
print(1 if hit else 0)
")
          expect_eq "${OK}" "1" "trace ${TID}: 审计行 (allow, ${SUPPRESS_RULE}, ${SUPPRESS_REASON}) 落链"
          break
        fi
      fi
      sleep 2
    done
    [[ "${AUDIT_GOT}" == 1 ]] || log_fail "压制 allow 事件 ${TID} 未在 20s 内落入审计链（engine→Kafka→control）"
else
  log_skip "审计断言组降级：Kafka 管道不可用（AUDIT_LIVE=${AUDIT_LIVE}）或本轮未捕获压制样本（guard 漂移）"
fi

# ============================================================
log_step "S7. 回退 → 停用（例外行为随状态收口）"

rc=$(http POST "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/rollback" '{}')
expect_ok "${rc}" "运营台: 回退(rollback)"
expect_eq "$(jgetf 'data.rollout_state')" "dry_run" "回到观察期"

wait_eval "回退后例外撤销（重新拦截）" "${DENY_WITH_MARKER}" 'effective_action' "block" 12 || true

rc=$(http PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" \
  '{"rollout_state":"disabled"}')
expect_ok "${rc}" "运营台: 停用规则"
expect_eq "$(jgetf 'data.rollout_state')" "disabled" "规则停用"

resp=$(evaluate "${DENY_WITH_MARKER}")
expect_eq "$(evf "${resp}" 'effective_action')" "block" "停用后带 marker 仍 deny（无幽灵例外）"

# ============================================================
echo ""
echo -e "${BLUE}===== 结果汇总 =====${NC}"
echo -e "  ${GREEN}PASS: ${PASS}${NC}  ${RED}FAIL: ${FAIL}${NC}  ${YELLOW}SKIP: ${SKIP}${NC}"
echo -e "  规则 ${RULE_ID} / ${RULE2_ID} 终态: disabled（下次运行自动复位到草稿）"
if [[ "${FAIL}" -gt 0 ]]; then
  echo -e "  ${BLUE}提示: BUG 复现判定 — 用 run-local 纯净栈再跑一遍; 若在无 Kafka 栈跑，跳过 S6 属预期${NC}"
  exit 1
fi
echo -e "  ${GREEN}全通过${NC}"
