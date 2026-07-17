#!/usr/bin/env bash
# curl-test-three-layers.sh
# 通过 virbius-control + engine 测试 global / service / tool 三层规则
#
# 前置条件:
#   virbius-control 运行在 8080 端口
#   virbius-engine  运行在 8082 端口
#   tenant "default" 已存在
#
# 说明:
#   三条规则全用 cloud/groovy 运行时，因为 engine 只评估 cloud 层。
#   tool 层的 BindScope 匹配需要 matchCtx.toolName()，但 engine 的
#   EvaluateHttpController 未设置该字段，因此 tool 层判断在 Groovy 脚本
#   内通过 ctx.var('tool_name') 实现。
#
set -euo pipefail

BASE="${VIRBIUS_BASE:-http://127.0.0.1:8080}"
ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
TENANT="${VIRBIUS_TENANT:-default}"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }
fail() { err "$*"; exit 1; }

# ─── Helpers ───
upsert_rule() {
  local rule_id="$1" payload="$2"
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE/api/v1/admin/tenants/$TENANT/rules" \
    -H 'Content-Type: application/json' -d "$payload")
  if [[ "$code" == 2* ]]; then ok "Upserted rule $rule_id"; else warn "Upsert $rule_id → $code"; fi
}

activate_rule() {
  local rule_id="$1"
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/${rule_id}/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"active"}' >/dev/null \
    && ok "Activated $rule_id" || fail "Activate $rule_id failed"
}

set_canary() {
  local rule_id="$1"
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/${rule_id}/runtime" \
    -H 'Content-Type: application/json' -d '{"enforce_mode":"canary","canary_percent":100}' >/dev/null \
    && ok "Set canary=100 on $rule_id" || fail "Set canary on $rule_id failed"
}

publish_rules() {
  curl -s -f -X POST "$BASE/api/v1/admin/tenants/$TENANT/rules/_/runtime/publish-snapshot" \
    -H 'Content-Type: application/json' >/dev/null \
    && ok "Published snapshot" || warn "Publish failed"
}

restart_engine() {
  info "Restarting engine to pick up new rules..."
  lsof -ti :8082 2>/dev/null | xargs kill -9 2>/dev/null || true
  sleep 1
  nohup env VIRBIUS_DATA_DIR="$ROOT/data" VIRBIUS_REDIS_URL="${VIRBIUS_REDIS_URL:-redis://127.0.0.1:6379}" \
    SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-dev}" \
    java -jar "$ROOT/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar" \
    >/tmp/virbius-agent/logs/engine.log 2>&1 &
  for i in $(seq 1 20); do
    if curl -sf http://127.0.0.1:8082/admin/health >/dev/null 2>&1; then
      ok "Engine ready"; return 0
    fi
    sleep 1
  done
  fail "Engine did not start"
}

evaluate() {
  local app_id="$1" tool_name="$2" tool_args="$3"
  curl -s -X POST "$ENGINE/v1/evaluate" \
    -H 'Content-Type: application/json' \
    -d "$(python3 -c "
import json
req = {
  'tenantId': '$TENANT',
  'toolName': '$tool_name',
  'argsJson': json.dumps($tool_args),
  'vars': {'app_id': '$app_id'}
}
print(json.dumps(req))
")"
}

echo "===== 三层规则测试 ====="
echo "  Control: $BASE"
echo "  Engine:  $ENGINE"
echo "  Tenant:  $TENANT"
echo ""

# ─── 前置检查 ───
info "Checking health..."
curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 || fail "virbius-control not ready"
curl -sf "$ENGINE/admin/health" >/dev/null 2>&1 || fail "virbius-engine not ready"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# ─── 清理旧测试规则 ───
info "Cleaning old test rules..."
for rid in test_global_deny test_service_deny test_tool_challenge; do
  curl -s -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$rid/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null 2>&1 || true
done
sleep 1

# ──────────────────────────────────────────────
# 1. global 层规则: 所有 delete_file 均 deny
# ──────────────────────────────────────────────
info "=== 1) global 层: 所有 app 的 delete_file 均 deny ==="
upsert_rule "test_global_deny" '{
  "rule_id": "test_global_deny",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "TEST_GLOBAL",
  "risk_score": 80,
  "intent_action": "deny",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def tool = ctx.var('\''tool_name'\'')\n  if (tool == '\''delete_file'\'') return ['\''action'\'':'\''deny'\'', '\''risk'\'':80]\n  return ['\''action'\'':'\''allow'\'']\n}"
}'

# ──────────────────────────────────────────────
# 2. service 层规则: 仅 medical-prod 的 db_write deny
# ──────────────────────────────────────────────
info "=== 2) service 层: 仅 medical-prod 的 db_write deny ==="
upsert_rule "test_service_deny" '{
  "rule_id": "test_service_deny",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "TEST_SERVICE",
  "risk_score": 80,
  "intent_action": "deny",
  "scope": {
    "bind_scope": "service",
    "bind_ref": {"app_ids": ["medical-prod"]}
  },
  "body": "def decide(ctx) {\n  def tool = ctx.var('\''tool_name'\'')\n  if (tool == '\''db_write'\'') return ['\''action'\'':'\''deny'\'', '\''risk'\'':80]\n  return ['\''action'\'':'\''allow'\'']\n}"
}'

# ──────────────────────────────────────────────
# 3. tool 层规则: 仅 delete_file 走 challenge
# ──────────────────────────────────────────────
# 注: engine 的 MatchContext.toolName 为 null，bind_scope=tool 不匹配
# 改用 bind_scope=service（app_id 维度），groovy 脚本内部判断 tool_name
info "=== 3) tool 层: 仅 medical-prod 的 delete_file 触发 challenge ==="
upsert_rule "test_tool_challenge" '{
  "rule_id": "test_tool_challenge",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "TEST_TOOL",
  "risk_score": 60,
  "intent_action": "challenge",
  "scope": {
    "bind_scope": "service",
    "bind_ref": {"app_ids": ["medical-prod"]}
  },
  "body": "def decide(ctx) {\n  def tool = ctx.var('\''tool_name'\'')\n  if (tool == '\''delete_file'\'') return ['\''action'\'':'\''challenge'\'', '\''risk'\'':60]\n  return ['\''action'\'':'\''allow'\'']\n}"
}'

# ──────────────────────────────────────────────
# 4. 激活 + 发布 + 重启 engine
# ──────────────────────────────────────────────
info "=== 4) 激活并发布 ==="
for rid in test_global_deny test_service_deny test_tool_challenge; do
  activate_rule "$rid"
  set_canary "$rid"
done
publish_rules
restart_engine

# ──────────────────────────────────────────────
# 5. 验证三层命中
# ──────────────────────────────────────────────
info ""
info "=== 5) 验证三层命中 ==="

assert_action() {
  local label="$1" app_id="$2" tool_name="$3" tool_args="$4" expect="$5"
  echo ""
  info "$label"
  local json; json=$(evaluate "$app_id" "$tool_name" "$tool_args")
  local eff; eff=$(echo "$json" | python3 -c "
import sys, json
r = json.load(sys.stdin)
print(r.get('effective_action', '?'))
")
  echo "  effective_action=$eff (expected: $expect)"
  if [[ "$eff" == "$expect" ]]; then
    ok "  PASS"
  else
    warn "  FAIL (got $eff, expected $expect)"
  fi
}

# 5a: global 层 — global 规则对所有 app 生效
assert_action "5a  global 层: medical-prod 调 delete_file → block" \
  "medical-prod" "delete_file" '{"path":"/tmp/x"}' "block"

# 5b: service 层 — beta 不应命中 medical-prod 规则
assert_action "5b  service 层: beta 调 db_write → allow (非 medical-prod)" \
  "beta" "db_write" '{"sql":"select 1"}' "allow"

# 5c: service 层 — medical-prod 调 db_write → block
assert_action "5c  service 层: medical-prod 调 db_write → block" \
  "medical-prod" "db_write" '{"sql":"select 1"}' "block"

# 5d: tool 层 — read_file 不应命中 delete_file 规则
assert_action "5d  tool 层: medical-prod 调 read_file → allow（不是 delete_file）" \
  "medical-prod" "read_file" '{"path":"/etc/hosts"}' "allow"

# 5e: tool 层 — delete_file → challenge
assert_action "5e  tool 层: medical-prod 调 delete_file → challenge" \
  "medical-prod" "delete_file" '{"path":"/tmp/x"}' "challenge"

# ──────────────────────────────────────────────
# 6. 清理
# ──────────────────────────────────────────────
info ""
info "=== 6) 清理测试规则 ==="
for rid in test_global_deny test_service_deny test_tool_challenge; do
  curl -s -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$rid/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null && ok "Archived $rid" || true
done

echo ""
echo -e "${GREEN}===== 测试完成 =====${NC}"
