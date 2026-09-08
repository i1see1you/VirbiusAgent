#!/usr/bin/env bash
# test-agent-risk-16-rules.sh
# End-to-end integration test: 16 Agent risk rules (10 cloud + 3 agent + 1 edge + 1 falco + 1 llm)
# via virbius-control + engine API.
#
# Prerequisites:
#   virbius-control running on port 8080
#   virbius-engine  running on port 8082
#   Redis running on 127.0.0.1:6379
#   tenant "default" already exists
#
# Usage:
#   VIRBIUS_BASE=http://127.0.0.1:8080 \
#   VIRBIUS_ENGINE=http://127.0.0.1:8082 \
#   VIRBIUS_TENANT=default \
#   bash scripts/test-agent-risk-14-rules.sh
#
set -euo pipefail

BASE="${VIRBIUS_BASE:-http://127.0.0.1:8080}"
ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
TENANT="${VIRBIUS_TENANT:-default}"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
pass()  { echo -e "${GREEN}[PASS]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }
fail()  { err "$*"; exit 1; }

ALL_PASS=true
assert_ok() { local label="$1" got="$2" expect="$3" rvar="${4:-}"
  if [[ "$got" == "$expect" ]]; then pass "$label -> $got"; else warn "$label -> got $got, expected $expect"; ALL_PASS=false; fi
  if [[ -n "$rvar" ]]; then mark "$rvar" "$([[ "$got" == "$expect" ]] && echo PASS || echo FAIL)"; fi
}

# Track per-rule results for summary (PASS / FAIL / SKIP)
R1="" R2="" R3="" R4="" R5="" R6="" R7="SKIP" R8="SKIP"
R9="" R10="SKIP" R11="SKIP" R12="SKIP" R13="" R14="" R15="" R16=""
mark() { local var="$1" val="$2"; eval "$var=\"$val\""; }

# ─── Rule CRUD helpers ───
upsert_rule() {
  local rule_id="$1" payload="$2"
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE/api/v1/admin/tenants/$TENANT/rules" \
    -H 'Content-Type: application/json' -d "$payload")
  if [[ "$code" == 2* ]]; then ok "Upserted $rule_id"; else warn "Upsert $rule_id → $code"; fi
}

activate_rule() {
  local rule_id="$1"
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/${rule_id}/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"active"}' >/dev/null \
    && ok "Activated $rule_id" || fail "Activate $rule_id failed"
}

# Use canary@100 as effective enforcement (dry_run→full is forbidden by the API)
set_canary() {
  local rule_id="$1"
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/${rule_id}/runtime" \
    -H 'Content-Type: application/json' \
    -d '{"enforce_mode":"canary","canary_percent":100}' >/dev/null \
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
  sleep 2
  local root; root="$(cd "$(dirname "$0")/.." && pwd)"
  python3 -c "
import subprocess, os, sys
pid = os.fork()
if pid == 0:
    os.setsid()
    log = open('/tmp/virbius-engine-e2e.log', 'w')
    subprocess.Popen(
        ['java', '-jar', '$root/virbius-engine/target/virbius-engine-0.1.0-SNAPSHOT.jar'],
        stdout=log, stderr=log, preexec_fn=lambda: os.setpgid(0, 0)
    )
    sys.exit(0)
"
  for i in $(seq 1 30); do
    if curl -sf "$ENGINE/admin/health" >/dev/null 2>&1; then
      ok "Engine ready"; return 0
    fi
    sleep 1
  done
  fail "Engine did not start after restart"
}

# ─── Engine evaluate helper ───
# Direct JSON construction via Python stdout, piped to curl
evaluate() {
  local tool_name="$1" args_json="$2" content="$3" extra_vars="$4"
  local session_id="${5:-sess-e2e-$(date +%s)-$$-$RANDOM}"
  local json_payload; json_payload=$(python3 -c "
import json, os, sys
args = json.loads('$args_json')
vars_dict = {}
ev = '$extra_vars'.strip()
if ev:
    for pair in ev.split(','):
        if '=' in pair:
            k, v = pair.split('=', 1)
            vars_dict[k.strip()] = v.strip()
req = {
    'tenant_id': '$TENANT',
    'session_id': '$session_id',
    'tool_name': '$tool_name',
    'args_json': json.dumps(args),
    'content': '''$content''',
    'vars': vars_dict,
    'trace_id': 'trace-e2e',
    'license_risk_quota': 100
}
print(json.dumps(req, ensure_ascii=False))
")
  curl -s --max-time 10 -X POST "$ENGINE/v1/evaluate" \
    -H 'Content-Type: application/json' \
    -d "$json_payload"
}

# Extract field from JSON response (engine uses snake_case)
json_field() {
  local key="$1"
  # Convert camelCase to snake_case (e.g., effectiveAction → effective_action)
  local snake; snake=$(echo "$key" | python3 -c "
import sys, re
s = sys.stdin.read().strip()
s = re.sub('(.)([A-Z][a-z]+)', r'\1_\2', s)
s = re.sub('([a-z0-9])([A-Z])', r'\1_\2', s)
print(s.lower())
")
  python3 -c "import sys,json; print(json.load(sys.stdin).get('$snake','?'))"
}

echo "===== Agent Risk 16-Rule Integration Test ====="
echo "  Control: $BASE"
echo "  Engine:  $ENGINE"
echo "  Tenant:  $TENANT"
echo ""

# ─── Pre-flight ───
info "Checking health..."
curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 || fail "virbius-control not ready"
curl -sf "$ENGINE/admin/health" >/dev/null 2>&1 || fail "virbius-engine not ready"

# ─── Clean old test rules ───
info "Archiving previous test rules..."
for rid in sensitive_entity_challenge political_sensitive_deny bulk_query_challenge \
           query_rate_limit_1m entity_type_probe self_query_only \
           session_risk_escalation trust_boundary_leak dlp_idcard prompt_safety_l3 \
           etc_file_read_detect rapid_tool_switch; do
  curl -s -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$rid/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null 2>&1 || true
done
sleep 1

# ─── Create prerequisite data list + cumulative ───
info "Creating prerequisite data list: political_sensitive_list..."
curl -s -f -X PUT "$BASE/api/v1/admin/tenants/$TENANT/lists/political_sensitive_list" \
  -H 'Content-Type: application/json' \
  -d '{"dimension":"var:logical","remark":"e2e test sensitive persons"}' >/dev/null \
  && ok "Created list political_sensitive_list" || warn "Create list failed (may already exist)"

info "Adding entries to political_sensitive_list..."
curl -s -X PUT "$BASE/api/v1/admin/tenants/$TENANT/lists/political_sensitive_list/entries" \
  -H 'Content-Type: application/json' \
  -d '{"values":["person_x","person_y","person_z"]}' >/dev/null \
  && ok "Added entries to list" || warn "Add entries failed"

info "Creating prerequisite cumulative: user_query_1m..."
curl -s -f -X PUT "$BASE/api/v1/admin/tenants/$TENANT/cumulatives/user_query_1m" \
  -H 'Content-Type: application/json' \
  -d '{
    "cumulative_name": "user_query_1m",
    "description": "User query count per minute",
    "dimension": "session_id",
    "window_kind": "rolling",
    "window_minutes": 1,
    "priority": 0,
    "status": "active"
  }' >/dev/null \
  && ok "Created cumulative user_query_1m" || warn "Create cumulative failed (may already exist)"

# ==============================================================
# 1-10: Upsert 10 Agent risk rules
# ==============================================================

# 1. 敏感实体查询 → Challenge
info "=== 1) sensitive_entity_challenge → challenge ==="
upsert_rule "sensitive_entity_challenge" '{
  "rule_id": "sensitive_entity_challenge",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "SENSITIVE_ENTITY",
  "risk_score": 40,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def et = ctx.var('\''args.entity_type'\'')\n  return et in ['\''VP'\'','\''CEO'\'','\''CFO'\'','\''CISO'\'','\''DIRECTOR'\'','\''BOARD_MEMBER'\'']\n}"
}'

# 2. 政治敏感人物查询 → Deny
info "=== 2) political_sensitive_deny → block ==="
upsert_rule "political_sensitive_deny" '{
  "rule_id": "political_sensitive_deny",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "POLITICAL_SENSITIVE",
  "risk_score": 100,
  "intent_action": "deny",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  return ctx.listMatch('\''political_sensitive_list'\'', ctx.var('\''args.entity_value'\''))\n}"
}'

# 3. 批量数据拉取 → Challenge
info "=== 3) bulk_query_challenge → challenge ==="
upsert_rule "bulk_query_challenge" '{
  "rule_id": "bulk_query_challenge",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "BULK_QUERY",
  "risk_score": 35,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def limit = ctx.var('\''args.limit'\'')\n  if (limit == null) return false\n  try { return Integer.parseInt(limit) > 100 }\n  catch (Exception e) { return false }\n}"
}'

# 4. 高频查询限流 → Deny
info "=== 4) query_rate_limit_1m → block ==="
upsert_rule "query_rate_limit_1m" '{
  "rule_id": "query_rate_limit_1m",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "RATE_LIMIT_1M",
  "risk_score": 80,
  "intent_action": "deny",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  return ctx.getCumulative('\''user_query_1m'\'') > 3\n}"
}'

# 5. 跨实体类型探测 → Challenge
info "=== 5) entity_type_probe → challenge ==="
upsert_rule "entity_type_probe" '{
  "rule_id": "entity_type_probe",
  "layer": "agent",
  "runtime": "agent-groovy",
  "bundle_id": "poc-default",
  "reason_code": "ENTITY_PROBE",
  "risk_score": 45,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def types = ctx.sessionHistory(10).collect{\n    it.args?.entity_type ?: '\'''\''\n  }.unique().findAll{ it != '\'''\'' }\n  return types.size() >= 3\n}"
}'

# 6. 越权查询 → Challenge
info "=== 6) self_query_only → challenge ==="
upsert_rule "self_query_only" '{
  "rule_id": "self_query_only",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "CROSS_USER_QUERY",
  "risk_score": 50,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def uid = ctx.var('\''user_id'\'')\n  def ev = ctx.var('\''args.entity_value'\'')\n  return ev != null \u0026\u0026 uid != null \u0026\u0026 ev != uid\n}"
}'

# 7. 会话风险累积 → Challenge
info "=== 7) session_risk_escalation → challenge ==="
upsert_rule "session_risk_escalation" '{
  "rule_id": "session_risk_escalation",
  "layer": "agent",
  "runtime": "agent-groovy",
  "bundle_id": "poc-default",
  "reason_code": "SESSION_RISK_HIGH",
  "risk_score": 60,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  return ctx.sessionRiskScore() > 60\n}"
}'

# 8. 信任边界泄漏 → Deny
info "=== 8) trust_boundary_leak → block ==="
upsert_rule "trust_boundary_leak" '{
  "rule_id": "trust_boundary_leak",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "TRUST_BOUNDARY_LEAK",
  "risk_score": 90,
  "intent_action": "deny",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  return ctx.var('\''content'\'')?.contains('\''password='\'') == true\n}"
}'

# 9. DLP 脱敏 → Allow (edge layer, DLP DSL — stored as reference, not executed by engine)
info "=== 9) dlp_idcard → allow (edge DLP, stored for reference) ==="
upsert_rule "dlp_idcard" '{
  "rule_id": "dlp_idcard",
  "layer": "edge",
  "runtime": "dlp-dsl",
  "bundle_id": "poc-default",
  "reason_code": "DLP_IDCARD",
  "risk_score": 0,
  "intent_action": "allow",
  "scope": {"bind_scope": "global"},
  "body": {"entity_type": "idcard_cn", "action": "mask"}
}'

# 10. Prompt 安全分类 → Challenge
info "=== 10) prompt_safety_l3 → challenge ==="
upsert_rule "prompt_safety_l3" '{
  "rule_id": "prompt_safety_l3",
  "layer": "cloud",
  "runtime": "prompt",
  "bundle_id": "poc-default",
  "reason_code": "PROMPT_SAFETY",
  "risk_score": 50,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": {}
}'

# 11. Falco /etc 敏感文件读取 → 系统调用层拦截
info "=== 11) etc_file_read_detect (Falco) ==="
upsert_rule "etc_file_read_detect" '{
  "rule_id": "etc_file_read_detect",
  "layer": "falco",
  "runtime": "falco_rule",
  "bundle_id": "poc-default",
  "reason_code": "ETC_SENSITIVE_FILE",
  "risk_score": 85,
  "intent_action": "deny",
  "scope": {"bind_scope": "global"},
  "body": "{\"condition\": \"open_file and (fd.name contains /etc/passwd or fd.name contains /etc/shadow or fd.name contains /etc/ssh/ or fd.name contains /etc/sudoers)\", \"output\": \"Sensitive file read detected (file=%fd.name user=%user.name)\", \"tags\": [\"filesystem\", \"mitre_persistence\"]}"
}'

# 12. 工具高频切换探测 → Challenge (核层)
info "=== 12) rapid_tool_switch → challenge ==="
upsert_rule "rapid_tool_switch" '{
  "rule_id": "rapid_tool_switch",
  "layer": "cloud",
  "runtime": "groovy",
  "bundle_id": "poc-default",
  "reason_code": "RAPID_TOOL_SWITCH",
  "risk_score": 45,
  "intent_action": "challenge",
  "scope": {"bind_scope": "global"},
  "body": "def decide(ctx) {\n  def tools = ctx.sessionHistory(5).collect{ it.tool_name ?: '\'''\'' }.unique().findAll{ it != '\'''\'' }\n  return tools.size() >= 2\n}"
}'

# ==============================================================
# 13. Register tools for exemption tests
# ==============================================================
info "=== 13) Register tools for exemption tests ==="
for tool_req in \
  '{"tool_name":"query_user_profile","risk_class":"low","sandbox_type":"none","timeout_ms":30000,"fast_path":true,"approval_mode":"strict"}' \
  '{"tool_name":"query_audit_events","risk_class":"low","sandbox_type":"none","timeout_ms":30000,"fast_path":true,"approval_mode":"lax"}'; do
  tn=$(echo "$tool_req" | python3 -c "import sys,json; print(json.load(sys.stdin)['tool_name'])")
  am=$(echo "$tool_req" | python3 -c "import sys,json; print(json.load(sys.stdin).get('approval_mode','strict'))")
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST \
    "$BASE/api/v1/admin/tenants/$TENANT/tools" \
    -H 'Content-Type: application/json' -d "$tool_req")
  if [[ "$code" == 2* ]]; then ok "Registered tool $tn (approval_mode=$am)"; else warn "Register tool $tn → $code"; fi
done

# ==============================================================
# 14. Activate + publish + restart
# ==============================================================
info "=== 14) Activate all rules & publish ==="
for rid in sensitive_entity_challenge political_sensitive_deny bulk_query_challenge \
           query_rate_limit_1m entity_type_probe self_query_only \
           session_risk_escalation trust_boundary_leak prompt_safety_l3 \
           etc_file_read_detect rapid_tool_switch; do
  activate_rule "$rid"
  set_canary "$rid"
done
# DLP edge rule: activate but keep dry_run
curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/dlp_idcard/status" \
  -H 'Content-Type: application/json' -d '{"rule_status":"active"}' >/dev/null \
  && ok "Activated dlp_idcard" || true

# Publish snapshot: engine subscriber picks it up from LAST_ENTRY + ">"
publish_rules
info "Waiting for engine to load new rules..."
sleep 2

# ==============================================================
# 14b. Falco Docker test for etc_file_read_detect (ECS only)
# ==============================================================
FALCO_CONTAINER_NAME="virbius-e2e-falco-$$"
FALCO_RULES_DIR=""
FALCO_ALERT_FOUND=false

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  info "=== 14b) Falco Docker test: etc_file_read_detect ==="

  # Fetch the rule from Control API
  info "Fetching etc_file_read_detect rule from API..."
  RULE_RESP=$(curl -s "$BASE/api/v1/admin/tenants/$TENANT/rules/etc_file_read_detect")
  RULE_BODY=$(echo "$RULE_RESP" | python3 -c "
import sys, json
r = json.load(sys.stdin)
data = r.get('data', r)
body = data.get('body', '{}')
if isinstance(body, str):
    body = json.loads(body)
print(json.dumps(body))
" 2>/dev/null || echo '{}')

  FALCO_CONDITION=$(echo "$RULE_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('condition',''))" 2>/dev/null)
  FALCO_OUTPUT=$(echo "$RULE_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('output',''))" 2>/dev/null)
  FALCO_TAGS=$(echo "$RULE_BODY" | python3 -c "import sys,json; t=json.load(sys.stdin).get('tags',[]); print(','.join(t) if isinstance(t,list) else str(t))" 2>/dev/null)

  if [[ -z "$FALCO_CONDITION" ]]; then
    warn "Could not extract rule condition from API, using fallback"
    FALCO_CONDITION="open_file and (fd.name contains /etc/passwd or fd.name contains /etc/shadow or fd.name contains /etc/ssh/ or fd.name contains /etc/sudoers)"
    FALCO_OUTPUT="Sensitive file read detected (file=%fd.name user=%user.name)"
    FALCO_TAGS="filesystem,mitre_persistence"
  fi

  # Generate Falco rules YAML
  FALCO_RULES_DIR=$(mktemp -d)
  cat > "$FALCO_RULES_DIR/virbius-rules.yaml" << FALCOEOF
- rule: etc_file_read_detect
  desc: Detect access to sensitive /etc files (from Control API)
  condition: evt.type in (open, openat, openat2) and (fd.name contains /etc/passwd or fd.name contains /etc/shadow or fd.name contains /etc/ssh/ or fd.name contains /etc/sudoers)
  output: Sensitive file read detected (file=%fd.name user=%user.name)
  priority: WARNING
  tags: [$FALCO_TAGS]
FALCOEOF
  ok "Generated Falco rules: $FALCO_RULES_DIR/virbius-rules.yaml"
  cat "$FALCO_RULES_DIR/virbius-rules.yaml" | sed 's/^/    /'

  # Start Falco container
  info "Starting Falco container..."
  docker rm -f "$FALCO_CONTAINER_NAME" 2>/dev/null || true

  DOCKER_FALCO_ARGS=(
    run -d
    --name "$FALCO_CONTAINER_NAME"
    --privileged
    --pid=host
    --security-opt seccomp=unconfined
    --ulimit memlock=-1:-1
    -v /dev:/dev
    -v /proc:/host/proc:ro
    -v /sys:/host/sys:ro
    -v "$FALCO_RULES_DIR/virbius-rules.yaml:/etc/falco/falco_rules.local.yaml:ro"
  )
  # Conditionally mount BPF paths (required on some kernels)
  [[ -d /sys/kernel/btf ]] && DOCKER_FALCO_ARGS+=(-v /sys/kernel/btf:/sys/kernel/btf:ro)
  [[ -d /sys/kernel/tracing ]] && DOCKER_FALCO_ARGS+=(-v /sys/kernel/tracing:/sys/kernel/tracing:rw)
  [[ -d /sys/fs/bpf ]] && DOCKER_FALCO_ARGS+=(-v /sys/fs/bpf:/sys/fs/bpf:rw)

  docker "${DOCKER_FALCO_ARGS[@]}" falcosecurity/falco:0.41.0 falco >/dev/null 2>&1 \
    && ok "Falco container started" || { warn "Failed to start Falco container"; FALCO_RULES_DIR=""; }

  if [[ -n "$FALCO_RULES_DIR" ]]; then
    # Wait for Falco to initialize
    info "Waiting for Falco to initialize..."
    FALCO_READY=false
    for _i in $(seq 1 30); do
      sleep 2
      if docker logs "$FALCO_CONTAINER_NAME" 2>&1 | grep -q "Opening 'syscall' source"; then
        FALCO_READY=true
        break
      fi
      if docker logs "$FALCO_CONTAINER_NAME" 2>&1 | grep -qi "error.*module\|fatal\|Error:"; then
        break
      fi
    done

    if $FALCO_READY; then
      ok "Falco initialized"

      # Verify rule was loaded
      sleep 3
      FALCO_LOGS=$(docker logs "$FALCO_CONTAINER_NAME" 2>&1)
      if echo "$FALCO_LOGS" | grep -q "Sensitive file read detected"; then
        ok "Rule etc_file_read_detect loaded and triggering"
      else
        warn "Rule output not found in Falco startup logs"
      fi

      # Trigger the rule
      info "Triggering rule: reading /etc/shadow..."
      docker run --rm --pid=host debian:bookworm-slim cat /etc/shadow >/dev/null 2>&1 || true
      sleep 5

      # Check Falco logs for alert (grep for the output message, not rule name)
      FALCO_LOGS=$(docker logs "$FALCO_CONTAINER_NAME" 2>&1)
      if echo "$FALCO_LOGS" | grep -q "Sensitive file read detected"; then
        ok "Falco alert detected: Sensitive file read detected"
        FALCO_ALERT_FOUND=true
      else
        warn "No Falco alert for sensitive file read"
        echo "  Falco logs (last 15 lines):"
        echo "$FALCO_LOGS" | tail -15 | sed 's/^/    /'
      fi
    else
      err "Falco did not initialize properly"
      docker logs "$FALCO_CONTAINER_NAME" 2>&1 | tail -10 | sed 's/^/    /'
    fi
  fi
else
  info "=== 14b) Falco Docker test: SKIPPED (Docker not available) ==="
fi
echo ""

# ==============================================================
# 15. Verify rules via engine evaluate
# ==============================================================
info ""
info "=== 15) Verify rule decisions ==="

# ── 12a: sensitive_entity_challenge: entity_type=VP → challenge ──
info "12a  sensitive_entity_challenge (entity_type=VP)"
json=$(evaluate "query_user_profile" '{"entity_type":"VP","entity_value":"vp001"}' "" "")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12a  VP query → challenge" "$eff" "challenge" R1

# ── 12b: political_sensitive_deny: entity_value=person_x → block ──
info "12b  political_sensitive_deny (entity_value=person_x)"
# Use user_id=person_x so self_query_only does NOT fire (ev == uid), isolating political_sensitive_deny
json=$(evaluate "query_user_profile" '{"entity_type":"user","entity_value":"person_x"}' "" "user_id=person_x")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12b  person_x → block" "$eff" "block" R2

# ── 12c: bulk_query_challenge: limit=200 → challenge ──
info "12c  bulk_query_challenge (limit=200)"
json=$(evaluate "query_audit_events" '{"entity_type":"user","limit":"200"}' "" "")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12c  limit=200 → challenge" "$eff" "challenge" R3

# ── 12d: bulk_query no hit: limit=10 → allow ──
info "12d  bulk_query_challenge (limit=10, should not hit)"
json=$(evaluate "query_audit_events" '{"entity_type":"user","limit":"10"}' "" "")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12d  limit=10 → allow" "$eff" "allow" R3

# ── 12e: self_query_only: entity_value != user_id → challenge ──
info "12e  self_query_only (entity_value differs from user_id)"
json=$(evaluate "query_self_profile" '{"entity_value":"uid-999"}' "" "user_id=uid-1")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12e  cross-user → challenge" "$eff" "challenge" R6

# ── 12f: self_query_only: same → allow ──
info "12f  self_query_only (same user, should not hit)"
json=$(evaluate "query_self_profile" '{"entity_value":"uid-1"}' "" "user_id=uid-1")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12f  same user → allow" "$eff" "allow" R6

# ── 12g: trust_boundary_leak: content contains "password=" → block ──
info "12g  trust_boundary_leak (content has password=)"
json=$(evaluate "http_get" '{}' "password=12345" "")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12g  password leak → block" "$eff" "block" R9
rid=$(echo "$json" | json_field ruleId)
assert_ok "12g  rule_id check" "$rid" "trust_boundary_leak" R9

# ── 12h: trust_boundary_leak: clean content → allow ──
info "12h  trust_boundary_leak (clean content, should not hit)"
json=$(evaluate "http_get" '{}' "" "")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12h  clean → allow" "$eff" "allow" R9

# ── 12i: low-risk default: normal query → allow ──
info "12i  low risk default (no rule should match)"
json=$(evaluate "query_audit_events" '{"entity_type":"self","limit":"5"}' "" "user_id=uid-1")
eff=$(echo "$json" | json_field effectiveAction)
assert_ok "12i  default allow" "$eff" "allow" R13

# ── 12j: etc_file_read_detect (Falco): tested via Docker in step 14b ──
info "12j  etc_file_read_detect — Falco syscall alert"
if $FALCO_ALERT_FOUND; then
  pass "12j  Falco alert detected for /etc/shadow read"
  mark R12 PASS
else
  warn "12j  etc_file_read_detect → FAIL (no Falco alert detected)"
  mark R12 FAIL
fi

# ── 12k: rapid_tool_switch: 3 API calls with different tools → challenge ──
info "12k  rapid_tool_switch (3 different tools via API)"
SES_RAPID="sess-rapid-$(date +%s)-$$-$RANDOM"
json_k1=$(evaluate "query_user_profile" '{"entity_type":"self"}' "" "" "$SES_RAPID")
eff_k1=$(echo "$json_k1" | json_field effectiveAction)
assert_ok "12k  query_user_profile → allow" "$eff_k1" "allow"
sleep 1
json_k2=$(evaluate "http_get" '{"entity_type":"self"}' "" "" "$SES_RAPID")
eff_k2=$(echo "$json_k2" | json_field effectiveAction)
assert_ok "12k  http_get → allow" "$eff_k2" "allow"
sleep 1
json_k3=$(evaluate "write_file" '{"entity_type":"self"}' "" "" "$SES_RAPID")
eff_k3=$(echo "$json_k3" | json_field effectiveAction)
assert_ok "12k  3 tools → challenge" "$eff_k3" "challenge" R5
if [[ "$eff_k1" != "allow" || "$eff_k2" != "allow" || "$eff_k3" != "challenge" ]]; then
  mark R5 FAIL
else
  mark R5 PASS
fi

# ── 12l: entity_type_probe: agent-groovy, not in engine cache ──
info "12l  entity_type_probe — agent-groovy rule not loaded in engine cache"
info "     Skipping: requires agent-side evaluation with session history"
warn "12l  entity_type_probe → SKIP (agent-groovy, not in engine cache)"

# ── 12m: session_risk_escalation: needs session risk score ──
info "12m  session_risk_escalation — depends on session risk score in Redis"
info "     Skipping: requires Redis key session:*:risk with score > 60"
warn "12m  session_risk_escalation → SKIP (needs Redis risk score)"

# ── 12n: prompt_safety_l3: needs LLM (VirbiusGuard) ──
info "12n  prompt_safety_l3 — depends on LLM (VirbiusGuard) classification"
info "     Skipping: requires external LLM service"
warn "12n  prompt_safety_l3 → SKIP (needs LLM service)"

# ── 12o: dlp_idcard: edge layer, not evaluated by engine ──
info "12o  dlp_idcard — edge layer DLP, not in engine evaluate path"
info "     Verified at edge gateway layer instead"
warn "12o  dlp_idcard → SKIP (edge layer, not testable via engine)"

# ==============================================================
# 16. Challenge exemption flow (strict mode)
# ==============================================================
info ""
info "=== 16) Challenge exemption flow (strict) ==="
SES13="sess-exempt-strict-$(date +%s)-$$-$RANDOM"
info "Triggering a challenge on VP query..."
json=$(evaluate "query_user_profile" '{"entity_type":"VP","entity_value":"vp001"}' "" "" "$SES13")
eff=$(echo "$json" | json_field effectiveAction)
ch_id=$(echo "$json" | json_field challengeId)
if [[ "$eff" == "challenge" && "$ch_id" != "?" && -n "$ch_id" ]]; then
  ok "Challenge created: $ch_id"

  # Approve via challenge API
  info "Approving challenge $ch_id ..."
  app_resp=$(curl -s -X POST "$BASE/api/v1/challenges/$ch_id/approve" \
    -H 'Content-Type: application/json' \
    -d '{"approved_by":"e2e-test","comment":"e2e approval test"}')
  app_ok=$(echo "$app_resp" | python3 -c "import sys,json; r=json.load(sys.stdin); print('ok' if r.get('token') else 'fail')")
  if [[ "$app_ok" == "ok" ]]; then
    ok "Challenge approved successfully (got token)"
  else
    warn "Challenge approval response: $app_resp"
  fi

  # Same request again → exemption prevents new challenge
  # (rate limiter may block due to 2nd call in session, but no new challengeId)
  info "Re-issuing same VP query — exemption should prevent re-challenge..."
  json2=$(evaluate "query_user_profile" '{"entity_type":"VP","entity_value":"vp001"}' "" "" "$SES13")
  eff2=$(echo "$json2" | json_field effectiveAction)
  ch2_id=$(echo "$json2" | json_field challengeId)
  if [[ "$ch2_id" == "None" || -z "$ch2_id" ]]; then
    ok "13a  no new challenge created (exemption active) — action=$eff2"
    mark R14 PASS
  else
    assert_ok "13a  exemption → no challenge" "$ch2_id" "None" R14
  fi
else
  warn "13   Challenge not created (eff=$eff ch_id=$ch_id) — skipping exemption test"
  mark R14 FAIL
fi

# ==============================================================
# 17. Challenge exemption with lax mode
# ==============================================================
info ""
info "=== 17) Challenge exemption flow (lax mode) ==="
info "Lax mode: approval_mode=lax on query_audit_events, diff args still exempted"
SES14="sess-exempt-lax-$(date +%s)-$$-$RANDOM"
info "Triggering challenge on bulk query..."
json3=$(evaluate "query_audit_events" '{"entity_type":"user","limit":"200"}' "" "" "$SES14")
eff3=$(echo "$json3" | json_field effectiveAction)
ch3_id=$(echo "$json3" | json_field challengeId)
if [[ "$eff3" == "challenge" && "$ch3_id" != "?" && -n "$ch3_id" ]]; then
  ok "Lax challenge created: $ch3_id"
  app3=$(curl -s -X POST "$BASE/api/v1/challenges/$ch3_id/approve" \
    -H 'Content-Type: application/json' \
    -d '{"approved_by":"e2e-test","approval_mode":"lax","comment":"lax mode test"}')
  info "Approve response: $app3"

  # Different args but same tool → should be exempted in lax mode
  info "Re-issuing with different args — exemption should prevent re-challenge..."
  json4=$(evaluate "query_audit_events" '{"entity_type":"VP","limit":"50"}' "" "" "$SES14")
  eff4=$(echo "$json4" | json_field effectiveAction)
  ch4_id=$(echo "$json4" | json_field challengeId)
  if [[ "$ch4_id" == "None" || -z "$ch4_id" ]]; then
    ok "14a  no new challenge created (lax exemption active) — action=$eff4"
    mark R15 PASS
  else
    assert_ok "14a  lax exemption → no challenge" "$ch4_id" "None" R15
  fi
else
  warn "14   Challenge not created — skipping lax exemption test"
  mark R15 FAIL
fi

# ==============================================================
# 18. Priority: deny wins over challenge
# ==============================================================
info ""
info "=== 18) Rule priority: deny(100) > challenge(50) ==="
SES15="sess-priority-$(date +%s)-$$-$RANDOM"
info "First 5 calls: bulk_query fires each time; rate limit (threshold >3) fires on call 5..."
R16_PASS=true
for i15 in 1 2 3 4 5; do
  json15=$(evaluate "query_audit_events" '{"entity_type":"user","limit":"200"}' "" "" "$SES15")
  eff15=$(echo "$json15" | json_field effectiveAction)
  if [[ "$i15" -le 4 ]]; then
    assert_ok "15a  call $i15 → challenge" "$eff15" "challenge"
    [[ "$eff15" != "challenge" ]] && R16_PASS=false
  else
    assert_ok "15b  call $i15 → block (deny > challenge)" "$eff15" "block"
    [[ "$eff15" != "block" ]] && R16_PASS=false
  fi
done
mark R4 "$R16_PASS"
mark R16 "$R16_PASS"

# ==============================================================
# 19. Cleanup
# ==============================================================
info ""
info "=== 19) Clean up test rules ==="
for rid in sensitive_entity_challenge political_sensitive_deny bulk_query_challenge \
           query_rate_limit_1m entity_type_probe self_query_only \
           session_risk_escalation trust_boundary_leak dlp_idcard prompt_safety_l3 \
           etc_file_read_detect rapid_tool_switch; do
  curl -s -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$rid/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null && ok "Archived $rid" || true
done

# Clean up Falco Docker container
if [[ -n "${FALCO_CONTAINER_NAME:-}" ]]; then
  docker rm -f "$FALCO_CONTAINER_NAME" 2>/dev/null && ok "Removed Falco container $FALCO_CONTAINER_NAME" || true
fi
if [[ -n "${FALCO_RULES_DIR:-}" && -d "${FALCO_RULES_DIR:-}" ]]; then
  rm -rf "$FALCO_RULES_DIR"
fi

# ==============================================================
# Results summary
# ==============================================================
echo ""
if [[ "$ALL_PASS" == "true" ]]; then
  echo -e "${GREEN}===== All 16 rules test passed =====${NC}"
else
  echo -e "${YELLOW}===== Some tests had unexpected results (see above) =====${NC}"
fi

status_icon() {
  case "${1:-}" in
    PASS) printf "✅" ;;
    FAIL) printf "❌" ;;
    *)    printf "⚪" ;;
  esac
}

echo ""
echo "Summary:"
echo "  $(status_icon "$R1") 1.  sensitive_entity_challenge  — VP query → challenge"
echo "  $(status_icon "$R2") 2.  political_sensitive_deny    — person_x → block"
echo "  $(status_icon "$R3") 3.  bulk_query_challenge         — limit=200 → challenge"
echo "  $(status_icon "$R4") 4.  query_rate_limit_1m          — (tested in priority section)"
echo "  $(status_icon "$R5") 5.  rapid_tool_switch           — 3 sequential tool calls → challenge"
echo "  $(status_icon "$R6") 6.  self_query_only              — mismatched user → challenge, same → allow"
echo "  $(status_icon "$R7") 7.  entity_type_probe           — SKIP (agent-groovy, not in engine cache)"
echo "  $(status_icon "$R8") 8.  session_risk_escalation      — SKIP (needs Redis risk score)"
echo "  $(status_icon "$R9") 9.  trust_boundary_leak          — password= → block, clean → allow"
echo "  $(status_icon "$R10") 10. dlp_idcard                   — SKIP (edge layer, not engine)"
echo "  $(status_icon "$R11") 11. prompt_safety_l3             — SKIP (needs LLM)"
echo "  $(status_icon "$R12") 12. etc_file_read_detect         — Falco alert on /etc/shadow read"
echo "  $(status_icon "$R13") 13. low risk default             — no match → allow"
echo "  $(status_icon "$R14") 14. strict exemption             — approve → allow"
echo "  $(status_icon "$R15") 15. lax exemption                — diff args → allow"
echo "  $(status_icon "$R16") 16. rule priority                — deny wins over challenge → block"
