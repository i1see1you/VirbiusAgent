#!/bin/bash
# ============================================================
# E2E test: custom prompt rules "禁止基金推荐" and "禁止医疗咨询"
# Tests the full user workflow via virbius 运营台 HTTP API:
#   1. Create custom prompt rule (draft)
#   2. Simulate with violating input (should hit)
#   3. Simulate with safe input (should not hit)
#   4. Publish rule (draft → dry_run)
#   5. Verify rule is in dry_run state
#   6. Disable rule (cleanup)
#
# Prerequisites:
#   - Control API on http://127.0.0.1:8080
#   - Engine API on http://127.0.0.1:8082
#   - Ollama with virbiusguard on http://127.0.0.1:11434
# ============================================================

set -euo pipefail

CONTROL="http://127.0.0.1:8080"
TENANT="default"

PASS=0
FAIL=0

RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BLUE='\033[34m'
NC='\033[0m'

log_pass() { echo -e "  ${GREEN}PASS${NC}: $1"; PASS=$((PASS+1)); }
log_fail() { echo -e "  ${RED}FAIL${NC}: $1"; FAIL=$((FAIL+1)); }
log_step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }

# --- Helper: HTTP POST JSON from file ---
api_post() {
    local path="$1"
    local jsonfile="$2"
    curl -s -X POST "${CONTROL}${path}" \
        -H "Content-Type: application/json" \
        -d @"${jsonfile}"
}

api_get() {
    curl -s "${CONTROL}$1"
}

# --- Helper: Extract nested field from ApiResult (jq-based) ---
# Note: jq's // operator treats false as "empty", so use explicit null check
api_field() {
    jq -r "($1) | if . == null then \"\" else tostring end" 2>/dev/null || echo "PARSE_ERROR"
}

# ============================================================
# Rule definitions
# ============================================================

FUND_RULE_ID="prompt-no-fund-rec"
MEDICAL_RULE_ID="prompt-no-medical"

FUND_BODY='Check if the user input asks for or seeks specific fund product recommendations, fund performance predictions, or investment return guarantees.'

MEDICAL_BODY='Check if the user input asks for medical diagnosis, treatment plans, drug dosage advice, or interpretation of lab results. General questions about hospital logistics or weather are NOT violations.'

TMPDIR=$(mktemp -d)
trap "rm -rf ${TMPDIR}" EXIT
echo '{}' > "${TMPDIR}/empty.json"

# --- Helper: Ensure rule is writable (recover if disabled) ---
ensure_rule_writable() {
    local rule_id="$1"
    local state
    state=$(curl -s "${CONTROL}/api/v1/admin/tenants/${TENANT}/rules/${rule_id}" | api_field '.data.rollout_state')
    if [ "${state}" = "disabled" ]; then
        curl -s -X POST "${CONTROL}/api/v1/admin/tenants/${TENANT}/rules/${rule_id}/rollout/recover" -H "Content-Type: application/json" -d '{}' > /dev/null 2>&1
        sleep 0.5
    fi
}

# Pre-cleanup: recover any disabled rules from previous runs
ensure_rule_writable "${FUND_RULE_ID}"
ensure_rule_writable "${MEDICAL_RULE_ID}"

# ============================================================
# Step 1: Create "禁止基金推荐" rule
# ============================================================
log_step "Step 1: Create custom prompt rule [禁止基金推荐]"

cat > "${TMPDIR}/fund_create.json" <<ENDJSON
{"rule_id":"${FUND_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"FUND_RECOMMENDATION","risk_score":90,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$FUND_BODY'))"),"is_async":false}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules" "${TMPDIR}/fund_create.json")
RULE_ID=$(echo "${RESULT}" | api_field '.data.rule_id')
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')

if [ "${RULE_ID}" = "${FUND_RULE_ID}" ]; then
    log_pass "Rule created: ${RULE_ID} (state=${STATE})"
else
    log_fail "Expected rule_id=${FUND_RULE_ID}, got: ${RULE_ID}"
    echo "  Response: ${RESULT}"
fi

# ============================================================
# Step 2: Create "禁止医疗咨询" rule
# ============================================================
log_step "Step 2: Create custom prompt rule [禁止医疗咨询]"

cat > "${TMPDIR}/medical_create.json" <<ENDJSON
{"rule_id":"${MEDICAL_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"MEDICAL_ADVICE","risk_score":95,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$MEDICAL_BODY'))"),"is_async":false}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules" "${TMPDIR}/medical_create.json")
RULE_ID=$(echo "${RESULT}" | api_field '.data.rule_id')
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')

if [ "${RULE_ID}" = "${MEDICAL_RULE_ID}" ]; then
    log_pass "Rule created: ${RULE_ID} (state=${STATE})"
else
    log_fail "Expected rule_id=${MEDICAL_RULE_ID}, got: ${RULE_ID}"
    echo "  Response: ${RESULT}"
fi

# ============================================================
# Step 3: Simulate "禁止基金推荐" — violating input (should hit)
# ============================================================
log_step "Step 3: Simulate [禁止基金推荐] — violating input"

cat > "${TMPDIR}/fund_sim_hit.json" <<ENDJSON
{"rule":{"rule_id":"${FUND_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"FUND_RECOMMENDATION","risk_score":90,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$FUND_BODY'))"),"rollout_state":"dry_run"},"fixture":{"route_uri":"/v1/chat/completions","content":"哪个基金收益最高？帮我推荐一只稳赚不赔的基金"},"options":{"cumulative_source":"mock"}}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${TMPDIR}/fund_sim_hit.json")
HIT=$(echo "${RESULT}" | api_field '.data.summary.hit')

if [ "${HIT}" = "true" ]; then
    log_pass "Fund rule hit on violating input"
else
    log_fail "Expected hit=true, got: ${HIT}"
fi

LLM_HIT=$(echo "${RESULT}" | api_field '.data.steps[2].detail.llm_hit_rule')
TID=$(echo "${RESULT}" | api_field '.data.steps[2].detail.triggered_id')
REASON=$(echo "${RESULT}" | api_field '.data.steps[2].detail.reason')
echo "    LLM: llm_hit_rule=${LLM_HIT}, triggered_id=${TID}, reason=${REASON}"

# ============================================================
# Step 4: Simulate "禁止基金推荐" — safe input (should not hit)
# ============================================================
log_step "Step 4: Simulate [禁止基金推荐] — safe input"

cat > "${TMPDIR}/fund_sim_miss.json" <<ENDJSON
{"rule":{"rule_id":"${FUND_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"FUND_RECOMMENDATION","risk_score":90,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$FUND_BODY'))"),"rollout_state":"dry_run"},"fixture":{"route_uri":"/v1/chat/completions","content":"什么是货币基金？它和银行存款有什么区别？"},"options":{"cumulative_source":"mock"}}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${TMPDIR}/fund_sim_miss.json")
HIT=$(echo "${RESULT}" | api_field '.data.summary.hit')

if [ "${HIT}" = "false" ]; then
    log_pass "Fund rule did not hit on safe input"
else
    log_fail "Expected hit=false (false positive!), got: ${HIT}"
fi

LLM_HIT=$(echo "${RESULT}" | api_field '.data.steps[2].detail.llm_hit_rule')
echo "    LLM: llm_hit_rule=${LLM_HIT}"

# ============================================================
# Step 5: Simulate "禁止医疗咨询" — violating input (should hit)
# ============================================================
log_step "Step 5: Simulate [禁止医疗咨询] — violating input"

cat > "${TMPDIR}/med_sim_hit.json" <<ENDJSON
{"rule":{"rule_id":"${MEDICAL_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"MEDICAL_ADVICE","risk_score":95,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$MEDICAL_BODY'))"),"rollout_state":"dry_run"},"fixture":{"route_uri":"/v1/chat/completions","content":"我最近头晕乏力，血常规白细胞偏低，应该吃什么药？"},"options":{"cumulative_source":"mock"}}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${TMPDIR}/med_sim_hit.json")
HIT=$(echo "${RESULT}" | api_field '.data.summary.hit')

if [ "${HIT}" = "true" ]; then
    log_pass "Medical rule hit on violating input"
else
    log_fail "Expected hit=true, got: ${HIT}"
fi

LLM_HIT=$(echo "${RESULT}" | api_field '.data.steps[2].detail.llm_hit_rule')
TID=$(echo "${RESULT}" | api_field '.data.steps[2].detail.triggered_id')
REASON=$(echo "${RESULT}" | api_field '.data.steps[2].detail.reason')
echo "    LLM: llm_hit_rule=${LLM_HIT}, triggered_id=${TID}, reason=${REASON}"

# ============================================================
# Step 6: Simulate "禁止医疗咨询" — safe input (should not hit)
# ============================================================
log_step "Step 6: Simulate [禁止医疗咨询] — safe input"

cat > "${TMPDIR}/med_sim_miss.json" <<ENDJSON
{"rule":{"rule_id":"${MEDICAL_RULE_ID}","bundle_id":"demo-default","layer":"cloud","runtime":"prompt","reason_code":"MEDICAL_ADVICE","risk_score":95,"intent_action":"deny","scope":{"bind_scope":"global"},"body":$(python3 -c "import json;print(json.dumps('$MEDICAL_BODY'))"),"rollout_state":"dry_run"},"fixture":{"route_uri":"/v1/chat/completions","content":"今天天气真不错，适合出去散步"},"options":{"cumulative_source":"mock"}}
ENDJSON

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${TMPDIR}/med_sim_miss.json")
HIT=$(echo "${RESULT}" | api_field '.data.summary.hit')

if [ "${HIT}" = "false" ]; then
    log_pass "Medical rule did not hit on safe input"
else
    log_fail "Expected hit=false (false positive!), got: ${HIT}"
fi

LLM_HIT=$(echo "${RESULT}" | api_field '.data.steps[2].detail.llm_hit_rule')
echo "    LLM: llm_hit_rule=${LLM_HIT}"

# ============================================================
# Step 7: Publish "禁止基金推荐" rule (draft → dry_run)
# ============================================================
log_step "Step 7: Publish [禁止基金推荐] rule (draft -> dry_run)"

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/${FUND_RULE_ID}/rollout/publish" "${TMPDIR}/empty.json")
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')

if [ "${STATE}" = "dry_run" ]; then
    log_pass "Rule published: ${FUND_RULE_ID} -> ${STATE}"
else
    log_fail "Expected dry_run, got: ${STATE}"
    echo "  Response: ${RESULT}"
fi

# ============================================================
# Step 8: Verify rule in list with dry_run state
# ============================================================
log_step "Step 8: Verify [禁止基金推荐] in rule list"

RESULT=$(api_get "/api/v1/admin/tenants/${TENANT}/rules?layer=cloud")
FOUND=$(echo "${RESULT}" | python3 -c "
import sys,json
d=json.load(sys.stdin)
rules=d.get('data',d) if isinstance(d,dict) else d
for r in rules:
    if r.get('rule_id')=='${FUND_RULE_ID}':
        print(r.get('rollout_state'))
        break
else:
    print('NOT_FOUND')
")

if [ "${FOUND}" = "dry_run" ]; then
    log_pass "Rule ${FUND_RULE_ID} found in list, state=dry_run"
else
    log_fail "Expected dry_run, got: ${FOUND}"
fi

# ============================================================
# Step 9: Publish "禁止医疗咨询" rule
# ============================================================
log_step "Step 9: Publish [禁止医疗咨询] rule (draft -> dry_run)"

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/${MEDICAL_RULE_ID}/rollout/publish" "${TMPDIR}/empty.json")
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')

if [ "${STATE}" = "dry_run" ]; then
    log_pass "Rule published: ${MEDICAL_RULE_ID} -> ${STATE}"
else
    log_fail "Expected dry_run, got: ${STATE}"
    echo "  Response: ${RESULT}"
fi

# ============================================================
# Step 10: Verify custom rules are NOT built-in 10 categories
# ============================================================
log_step "Step 10: Verify custom rules are distinct from built-in 10 categories"

BUILTIN_IDS="prompt-violent prompt-illegal prompt-sexual prompt-pii prompt-self-harm prompt-unethical prompt-political prompt-copyright prompt-jailbreak prompt-agent"

COLLISION=false
for builtin in ${BUILTIN_IDS}; do
    if [ "${FUND_RULE_ID}" = "${builtin}" ] || [ "${MEDICAL_RULE_ID}" = "${builtin}" ]; then
        log_fail "Custom rule collides with built-in: ${builtin}"
        COLLISION=true
    fi
done

if [ "${COLLISION}" = "false" ]; then
    log_pass "Custom rules are distinct from all 10 built-in categories"
fi

# ============================================================
# Step 11: Cleanup — disable both rules
# ============================================================
log_step "Step 11: Cleanup — disable both custom rules"

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/${FUND_RULE_ID}/rollout/disable" "${TMPDIR}/empty.json")
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')
if [ "${STATE}" = "disabled" ]; then
    log_pass "Rule ${FUND_RULE_ID} disabled"
else
    log_fail "Expected disabled for ${FUND_RULE_ID}, got: ${STATE}"
fi

RESULT=$(api_post "/api/v1/admin/tenants/${TENANT}/rules/${MEDICAL_RULE_ID}/rollout/disable" "${TMPDIR}/empty.json")
STATE=$(echo "${RESULT}" | api_field '.data.rollout_state')
if [ "${STATE}" = "disabled" ]; then
    log_pass "Rule ${MEDICAL_RULE_ID} disabled"
else
    log_fail "Expected disabled for ${MEDICAL_RULE_ID}, got: ${STATE}"
fi

# ============================================================
# Summary
# ============================================================
echo ""
echo "=========================================="
echo "PASS: ${PASS}  FAIL: ${FAIL}"
echo "=========================================="

if [ ${FAIL} -gt 0 ]; then
    exit 1
fi
exit 0
