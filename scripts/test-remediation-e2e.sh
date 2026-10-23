#!/usr/bin/env bash
# scripts/test-remediation-e2e.sh
# 一键处置（M1-min）控制面全流程集成测试。
#
# 模拟用户真实操作链路：独立自起 redis / virbius-evaluation / virbius-control 三件套
# （全新 SQLite 数据目录，seed 初始化），全程只经 REST API 操作——不直读/改写
# SQLite 或 Redis，模拟控制台运营的真实点击流。
#
# 链路：
#   [0]  构建 fat-jar/boot-jar（可 VE_SKIP_BUILD=1 复用）并启动 redis(独立端口/db)/evaluation/control
#   [1]  平台管理 API 配置评估接入（PUT /admin/platform/evaluation/settings）
#        + 诊断（POST /settings/test → url/tcp/health/auth/license 全绿）
#   [2]  扫描 evil 样例（evaluation API）→ 取 secrets/high-risk-actions/external 三类 risk_id
#   [3]  预览（console API）：plan 形状/executable/rule_id 前缀/默认值/绑定值/
#        DLP 归一化/payload_preview 占位已替换/existing_rule 不存在
#   [3b] 未知 risk_id 预览 → plans 空（HTTP 200）
#   [4]  apply ×3 → 全部 created
#   [5]  规则 API 逐条校验：bundle=poc-default / rollout_state=dry_run /
#        scope.app_ids=[agent_name] / runtime / DLP 强制 allow+risk=0
#   [6]  审计 API：GET /audit/trace/{report_id} 含 3×remediation_apply；
#        rollout timeline 含 publish→dry_run
#   [7]  幂等：重复 apply → 全部 skipped_exists（existing_state=dry_run）；预览带 existing_rule
#   [8]  变量防误用：未声明变量→failed / 非法值→failed / 不存在的 risk_id→failed
#   [9]  空 items → 400（"items 必须为 1-10 条"）
#
# 前置：java17 + mvn（默认构建）；redis-server（脚本自起独立实例：不占用本机默认 6379）。
#       不需要 Kafka / 引擎(8082) / 前端 / 许可证（evaluation 无许可语义=恒 active）。
#
# 用法：
#   bash scripts/test-remediation-e2e.sh
#   VE_SKIP_BUILD=1 bash ...          # 复用已构建 jar
#   VE_REM_E2E_PORT_BASE=18700 bash ...  # 端口基址（redis/evaluation/control 连续 3 个）
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()  { echo -e "${GREEN}[PASS]${NC} $*"; }
err() { echo -e "${RED}[FAIL]${NC} $*"; failed=1; }
info(){ echo -e "${CYAN}[INFO]${NC} $*"; }

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
EVAL_DIR=$ROOT/virbius-evaluation
CTRL_DIR=$ROOT/virbius-control
FIXTURE=$EVAL_DIR/src/test/resources/fixture-agent
WORK=$(mktemp -d /tmp/virbius-rem-e2e-XXXXXX)
BASE=${VE_REM_E2E_PORT_BASE:-18700}
REDISP=$BASE; EVALP=$((BASE+1)); CTRLP=$((BASE+2))
TENANT=default
EVALTOK="e2e-evaluation-token"
EVAL=http://127.0.0.1:$EVALP/api/v1
CTRL=http://127.0.0.1:$CTRLP/api/v1
failed=0

# shellcheck disable=SC2329  # invoked via trap
cleanup() {
  [ -n "${CTRL_PID:-}" ] && kill "$CTRL_PID" 2>/dev/null || true
  [ -n "${EVAL_PID:-}" ] && kill "$EVAL_PID" 2>/dev/null || true
  [ -n "${REDIS_PID:-}" ] && kill "$REDIS_PID" 2>/dev/null || true
}
trap cleanup EXIT

emit() {
  while IFS='|' read -r st msg; do
    [ -z "$st" ] && continue
    case "$st" in
      PASS) ok "$msg" ;;
      FAIL) err "$msg" ;;
      *)    info "$st|$msg" ;;
    esac
  done
}
# run_py：python 断言块崩溃必须显形为 FAIL（曾经出现过 traceback 被吞、整块断言假绿）
run_py() {
  local err
  err=$(mktemp)
  python3 "$@" > >(emit) 2>"$err"
  if [ -s "$err" ]; then
    err "run_py 断言块内部错误（未执行，禁止假绿）"
    sed 's/^/py| /' "$err"
  fi
  rm -f "$err"
}

# 控制台/评估 REST（临时加 ack 断言在 python 内做状态码校验）
api_c() { # $1=method $2=url $3=body(opt) $4=out  （评估侧需 Bearer；控制面 auth 关闭时该头被忽略）
  if [ "$1" = "GET" ]; then
    curl -s -o "$3" -w "%{http_code}" -H "Authorization: Bearer $EVALTOK" "$2"
  elif [ "$1" = "POST" ]; then
    curl -s -o "$4" -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $EVALTOK" -d "$3" "$2"
  elif [ "$1" = "PUT" ]; then
    curl -s -o "$4" -w "%{http_code}" -X PUT -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $EVALTOK" -d "$3" "$2"
  fi
}
pick_ctrl_jar() {
  local f
  for f in "$CTRL_DIR"/target/virbius-control-*.jar; do
    case "$f" in *-sources.jar) continue ;; esac
    printf '%s\n' "$f"
    return 0
  done
  return 1
}

info "===== 一键处置（M1-min）控制面全流程集成测试 ====="
info "work dir: $WORK  ports: redis=$REDISP evaluation=$EVALP control=$CTRLP"

# ---------- [0] 构建 + 启动 ----------
info "[0] 构建 jar 并启动 redis / evaluation / control（全新数据库）"
EVAL_JAR=$EVAL_DIR/target/virbius-evaluation-0.1.0-SNAPSHOT.jar
CTRL_JAR=$(pick_ctrl_jar || true)
if [ "${VE_SKIP_BUILD:-0}" = "1" ] && [ -f "$EVAL_JAR" ] && [ -n "$CTRL_JAR" ]; then
  info "跳过构建（VE_SKIP_BUILD=1）"
else
  (cd "$EVAL_DIR" && mvn -q -DskipTests package) >"$WORK/build-eval.log" 2>&1 \
    && ok "[0] evaluation 构建成功" || { err "[0] evaluation 构建失败，见 $WORK/build-eval.log"; tail -20 "$WORK/build-eval.log"; exit 1; }
  (cd "$CTRL_DIR" && mvn -q -DskipTests package) >"$WORK/build-ctrl.log" 2>&1 \
    && ok "[0] control 构建成功" || { err "[0] control 构建失败，见 $WORK/build-ctrl.log"; tail -20 "$WORK/build-ctrl.log"; exit 1; }
  CTRL_JAR=$(pick_ctrl_jar || true)
fi
[ -f "$EVAL_JAR" ] && [ -n "$CTRL_JAR" ] || { err "[0] jar 缺失：eval=$EVAL_JAR ctrl=[$CTRL_JAR]"; exit 1; }
command -v redis-server >/dev/null || { err "[0] redis-server 未安装"; exit 1; }

redis-server --daemonize no --port "$REDISP" --bind 127.0.0.1 \
  --save "" --appendonly no >"$WORK/redis.log" 2>&1 &
REDIS_PID=$!
up=0
for _ in $(seq 1 20); do
  [ "$(redis-cli -p "$REDISP" ping 2>/dev/null)" = "PONG" ] && { up=1; break; }; sleep 0.5
done
[ "$up" = "1" ] && ok "[0] Redis 就绪（独立实例 :${REDISP}, db 隔离）" \
  || { err "[0] Redis 启动超时"; exit 1; }

VE_STORE_DIR=$WORK/eval-store VE_OSV=false VE_API_TOKEN="$EVALTOK" SERVER_PORT=$EVALP \
  java -jar "$EVAL_JAR" >"$WORK/eval.log" 2>&1 &
EVAL_PID=$!
SPRING_PROFILES_ACTIVE=dev VIRBIUS_DATA_DIR=$WORK/ctrl-data \
  SPRING_DATA_REDIS_URL=redis://127.0.0.1:$REDISP/9 \
  VIRBIUS_REDIS_URL=redis://127.0.0.1:$REDISP/9 \
  VIRBIUS_LICENSE_MASTER_KEY="e2e-remediation-master-key-0123456789-abcdef" \
  KAFKA_BOOTSTRAP_SERVERS=localhost:9092 SERVER_PORT=$CTRLP \
  java -jar "$CTRL_JAR" >"$WORK/ctrl.log" 2>&1 &
CTRL_PID=$!

up=0
for _ in $(seq 1 60); do
  curl -sf "$EVAL/health" >/dev/null 2>&1 && curl -sf "$CTRL/health" >/dev/null 2>&1 && { up=1; break; }
  sleep 1
done
[ "$up" = "1" ] && ok "[0] evaluation(:$EVALP) + control(:$CTRLP) 全部就绪" \
  || { err "[0] 服务启动超时"; tail -30 "$WORK/eval.log" "$WORK/ctrl.log"; exit 1; }

# ---------- [1] 配置评估接入 + 诊断（平台管理 API = 用户在控制台点表单）----------
info "[1] PUT /admin/platform/evaluation/settings + 诊断 /settings/test"
BODY='{"enabled":true,"base_url":"http://127.0.0.1:'"$EVALP"'","api_token":"'"$EVALTOK"'","promo_enabled":false}'
CODE=$(api_c PUT "$CTRL/admin/platform/evaluation/settings" "$BODY" "$WORK/settings.json")
[ "$CODE" = "200" ] && ok "[1] 配置保存 → 200" || { err "[1] settings HTTP $CODE"; cat "$WORK/settings.json"; exit 1; }
run_py - "$WORK/settings.json" <<'PY'
import json, sys
v = json.loads(open(sys.argv[1]).read())
if isinstance(v, dict) and isinstance(v.get("data"), dict):
    v = v["data"]
okv = bool(v.get("enabled")) and bool(v.get("api_token_set", v.get("apiTokenSet")))
print(("PASS|" if okv else "FAIL|") + "设置回读 enabled/api_token_set" +
      ("" if okv else " got=%s" % v))
PY

# 诊断与凭据：BUG-2 修复后（EvaluationDiagnosticsService.get 授权重载）断言 6 步全绿；
# 另经评估侧 /api/v1/license API 直接验证 Bearer 凭据有效性。
CODE=$(api_c POST "$CTRL/admin/platform/evaluation/settings/test" '{}' "$WORK/diag.json")
run_py - "$CODE" "$WORK/diag.json" <<'PY'
import json, sys
try:
    data = json.loads(open(sys.argv[2]).read())
except Exception:
    data = {}
if isinstance(data, dict) and isinstance(data.get("data"), dict):
    data = data["data"]
steps = {}
if isinstance(data, dict):
    for s in data.get("steps", []):
        if isinstance(s, dict):
            steps[s.get("step", s.get("name"))] = s.get("ok", s.get("good"))
want = ("url", "dns", "tcp", "health", "auth", "license")
good = [k for k in want if steps.get(k) is True]
bad = [k for k in want if steps.get(k) is not True]
print(("PASS|" if len(good) == 6 else "FAIL|") +
      "诊断 6 步全绿（url/dns/tcp/health/auth/license）" +
      ("" if not bad else " bad=%s got=%s" % (bad, steps)))
PY

CODE=$(api_c GET "$EVAL/license" "$WORK/lic.json")
run_py - "$CODE" "$WORK/lic.json" <<'PY'
import json, sys
try:
    lic = json.loads(open(sys.argv[2]).read())
    lic = lic.get("data", lic)
except Exception:
    lic = {}
active = bool(lic.get("active"))
print(("PASS|" if sys.argv[1] == "200" and active else "FAIL|") +
      "评估侧 /api/v1/license（Bearer 凭据有效）active=%s mode=%s" % (active, lic.get("mode")))
PY

# ---------- [2] 扫描 evil 样例 ----------
info "[2] POST /evaluations/scans（evaluation API）"
CODE=$(api_c POST "$EVAL/evaluations/scans" \
  '{"target_path":"'"$FIXTURE"'","agent_name":"mail-agent"}' "$WORK/scan.json")
[ "$CODE" = "200" ] && ok "[2] 静态扫描 → 200" || { err "[2] 扫描 HTTP $CODE"; cat "$WORK/scan.json"; exit 1; }
REPORT_ID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["report_id"])' "$WORK/scan.json")

run_py - "$WORK/scan.json" "$WORK/riskids.txt" <<'PY'
import json, sys
findings = json.load(open(sys.argv[1]))["data"]["findings"]
want = {"secrets", "high-risk-actions", "external-connections-suspicious"}
picks, other = {}, []
for f in findings:
    cur = (f.get("check_id"), f.get("risk_id"))
    other.append(cur)
    if f.get("check_id") in want and f.get("remediations"):
        picks[f["check_id"]] = f["risk_id"]
missing = want - set(picks)
lines = ["%s|%s" % (k, v) for k, v in picks.items()]
open(sys.argv[2], "w").write("\n".join(lines) + "\n")
if missing:
    print("FAIL|evil 报告未携带三类检测 (missing=%s have=%s)" % (sorted(missing), [c for c, _ in other]))
else:
    print("PASS|三类检测 risk_id 提取：%s" % ",".join("%s→%s" % (c, r[:14] + "…") for c, r in picks.items()))
PY
R_SECRETS=$(grep '^secrets|' "$WORK/riskids.txt" | cut -d'|' -f2)
R_HIGHRISK=$(grep '^high-risk-actions|' "$WORK/riskids.txt" | cut -d'|' -f2)
R_EGRESS=$(grep '^external-connections-suspicious|' "$WORK/riskids.txt" | cut -d'|' -f2)
[ -n "$R_SECRETS" ] && [ -n "$R_HIGHRISK" ] && [ -n "$R_EGRESS" ] || exit 1

# ---------- [3] 预览 ----------
info "[3] GET …/reports/{rid}/remediation-preview"
PREVIEW_URL="$CTRL/admin/tenants/$TENANT/evaluation/reports/$REPORT_ID/remediation-preview"
CODE=$(api_c GET "$PREVIEW_URL?risk_ids=$R_SECRETS,$R_HIGHRISK,$R_EGRESS" "$WORK/preview.json")
run_py - "$WORK/preview.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[1])), None
code, body = json.load(open(sys.argv[1])), None
import json as j
b = j.load(open(sys.argv[1]))
data = b["data"]
plans = {p["plan_id"]: p for p in data["plans"]}
out = []
def a(cond, msg): out.append(("PASS" if cond else "FAIL", msg))

a(len(data["plans"]) == 3, "预览返回 3 个 plan（%d）" % len(data["plans"]))
a(data["agent_name"] == "mail-agent", "agent_name=mail-agent（默认 app 候选）")
a(data["app_candidates"] == [], "app_candidates 首次为空（无既有规则）")

dlp = plans.get("dlp_secrets")
a(bool(dlp), "dlp_secrets 在预览中")
if dlp:
    a(dlp["executable"] is True, "dlp_secrets executable=true")
    a(dlp["rule_id"].startswith("eval-secrets-"), "rule_id 前缀 eval-secrets-（%s）" % dlp["rule_id"])
    a(dlp.get("unresolved_vars") == [], "无未解析变量")
    a("normalized" in dlp and dlp["normalized"] is True, "DLP 归一化标记 normalized=true")
    a("{{" not in j.dumps(dlp.get("payload_preview", {})), "payload_preview 无占位残留（默认值已代入）")
    a(dlp["vars"]["pattern"]["default_value"] is not None, "pattern 默认值在预览中展示")

hi = plans.get("challenge_highrisk")
a(bool(hi), "challenge_highrisk 在预览中")
if hi:
    a(hi["vars"]["tool"].get("bound_value") != "", "tool 绑定值来自证据（bound_value 非空，用户可改）")
    a("{{" not in j.dumps(hi.get("payload_preview", {})), "高危工具 payload_preview 已代入绑定工具名")

eg = plans.get("block_suspicious_domain")
a(bool(eg), "block_suspicious_domain 在预览中")
if eg:
    a(eg["vars"]["domain"].get("bound_value") != "", "domain 绑定值非空")
    a("{{" not in j.dumps(eg.get("payload_preview", {})), "外渗域名 payload_preview 已代入")
a("existing_rule" not in j.dumps(data.get("plans")), "首次预览无 existing_rule")
for st, msg in out:
    print("%s|%s" % (st, msg))
PY

CODE=$(api_c GET "$PREVIEW_URL?risk_ids=not-a-risk-id" "$WORK/preview2.json")
run_py - "$CODE" "$WORK/preview2.json" <<'PY'
import json, sys
print(("PASS|" if sys.argv[1] == "200" and json.load(open(sys.argv[2]))["data"]["plans"] == []
       else "FAIL|") + "未知 risk_id 预览 → 200 且 plans 空")
PY

# ---------- [4] apply ×3 ----------
info "[4] POST …/reports/{rid}/remediation-apply（3 项）"
APPLY_BODY='{"items":[{"risk_id":"'"$R_SECRETS"'","plan_id":"dlp_secrets","variables":{},"app_id":null},{"risk_id":"'"$R_HIGHRISK"'","plan_id":"challenge_highrisk","variables":{},"app_id":null},{"risk_id":"'"$R_EGRESS"'","plan_id":"block_suspicious_domain","variables":{},"app_id":null}]}'
CODE=$(api_c POST "$CTRL/admin/tenants/$TENANT/evaluation/reports/$REPORT_ID/remediation-apply" "$APPLY_BODY" "$WORK/apply.json")
run_py - "$CODE" "$WORK/apply.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[2]))
print(("PASS|" if b["code"] == 0 else "FAIL|") + "apply 响应 code=0")
res = {x["plan_id"]: x for x in b["data"]["results"]}
for pid in ("dlp_secrets", "challenge_highrisk", "block_suspicious_domain"):
    r = res.get(pid, {})
    okc = r.get("status") == "created" and (r.get("rule_id") or "").startswith("eval-")
    print(("PASS|" if okc else "FAIL|") + "%s → created（rule_id=%s）" % (pid, r.get("rule_id", "?")))
PY
RULE_DLP=$(python3 -c 'import json,sys;print([x["rule_id"] for x in json.load(open(sys.argv[1]))["data"]["results"] if x["plan_id"]=="dlp_secrets"][0])' "$WORK/apply.json")
RULE_HI=$(python3 -c 'import json,sys;print([x["rule_id"] for x in json.load(open(sys.argv[1]))["data"]["results"] if x["plan_id"]=="challenge_highrisk"][0])' "$WORK/apply.json")
RULE_EG=$(python3 -c 'import json,sys;print([x["rule_id"] for x in json.load(open(sys.argv[1]))["data"]["results"] if x["plan_id"]=="block_suspicious_domain"][0])' "$WORK/apply.json")

# ---------- [5] 规则 API 校验 ----------
info "[5] GET /rules/{ruleId}（dry_run/bundle/scope/runtime/归一化）"
for pair in "dlp:$RULE_DLP" "highrisk:$RULE_HI" "egress:$RULE_EG"; do
  tag=${pair%%:*}; rid=${pair#*:}
  CODE=$(api_c GET "$CTRL/admin/tenants/$TENANT/rules/$rid" "$WORK/rule-$tag.json")
  [ "$CODE" = "200" ] && ok "[5] GET rule $rid → 200" \
    || err "[5] GET rule $tag=$rid → HTTP ${CODE}"
done
run_py - "$WORK/rule-dlp.json" "$WORK/rule-highrisk.json" "$WORK/rule-egress.json" <<'PY'
import json, sys
out = []
def a(cond, msg): out.append(("PASS" if cond else "FAIL", msg))
def load(p):
    try:
        d = json.loads(open(p).read())
        return d.get("data", {}) if isinstance(d, dict) else {}
    except Exception:
        return {}
def body_of(r):
    b = r.get("body")
    if isinstance(b, str):
        try:
            return json.loads(b)
        except Exception:
            return {}
    return b if isinstance(b, dict) else {}
dlp, hi, eg = load(sys.argv[1]), load(sys.argv[2]), load(sys.argv[3])

if not dlp:
    a(False, "①DLP 规则 GET 失败（apply 未创建，见 [4] 输出）")
else:
    a(dlp.get("rollout_state") == "dry_run", "①DLP rollout_state=dry_run")
    a(dlp.get("bundle_id") == "poc-default", "①DLP bundle=poc-default")
    a(dlp.get("scope", {}).get("bind_ref", {}).get("app_ids") == ["mail-agent"],
      "①DLP scope 绑定 app=mail-agent")
    a(dlp.get("runtime") == "dlp-dsl" and dlp.get("intent_action") == "allow"
      and dlp.get("risk_score") == 0,
      "①DLP 归一化：dlp-dsl / intent=allow / risk=0（模板 mask/60 被强制覆盖）")
    db = body_of(dlp)
    a(db.get("entity_type") == "custom_regex",
      "①DLP body.entity_type=custom_regex（规则详情 body 为 JSON 字符串，仍需命中合法实体）")
    a(db.get("pattern") == "AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}",
      "①DLP pattern = 默认值已代入")
    a(dlp.get("layer") == "edge", "①DLP layer=edge")
for tag, r, code, act in ((" 高危", hi, challenge_key := "challenge", "HITL_REQUIRED"),
                          (" 外渗", eg, "deny", "EGRESS_SUSPICIOUS")):
    if not r:
        a(False, "规则缺失 %s" % tag)
        continue
    a(r.get("rollout_state") == "dry_run", "%s rollout_state=dry_run" % tag)
    a(r.get("bundle_id") == "poc-default", "%s bundle=poc-default" % tag)
    a(r.get("scope", {}).get("bind_ref", {}).get("app_ids") == ["mail-agent"],
      "%s scope 绑定 app=mail-agent" % tag)
    a(r.get("runtime") == "lua-dsl", "%s runtime=lua-dsl" % tag)
    a(r.get("intent_action") == code and r.get("reason_code") == act,
      "%s intent=%s / %s" % (tag, code, act))
    a(r.get("layer") == "edge", "%s layer=edge" % tag)
for st, msg in out:
    print("%s|%s" % (st, msg))
PY

# ---------- [6] 审计 API ----------
info "[6] GET /audit/trace/{report_id} + /rules/{rid}/rollout/timeline"
CODE=$(api_c GET "$CTRL/admin/tenants/$TENANT/audit/trace/$REPORT_ID" "$WORK/audit.json")
run_py - "$CODE" "$WORK/audit.json" "$WORK/apply.json" <<'PY'
import json, sys
raw = json.dumps(json.load(open(sys.argv[2])))
results = json.load(open(sys.argv[3]))["data"]["results"]
out = []
def a(cond, msg): out.append(("PASS" if cond else "FAIL", msg))
n_apply = raw.count('"remediation_apply"')
a(n_apply >= 3, "审计 trace 查询（API，读库而非 DB 手工读）：remediation_apply 事件 ≥3（%d）" % n_apply)
for r in results:
    if r["status"] != "created":
        continue
    a(r["rule_id"] in raw, "审计含 rule_id=%s（tool_name=plan_id/trace_id=report_id 链路）" % r["rule_id"])
for st, msg in out:
    print("%s|%s" % (st, msg))
PY
for pair in "highrisk:$RULE_HI" "egress:$RULE_EG"; do
  tag=${pair%%:*}; rid=${pair#*:}
  CODE=$(api_c GET "$CTRL/admin/tenants/$TENANT/rules/$rid/rollout/timeline" "$WORK/timeline-$tag.json")
  run_py - "$CODE" "$WORK/timeline-$tag.json" <<'PY'
import json, sys
try:
    b = json.loads(open(sys.argv[2]).read())
    data = b.get("data", [])
except Exception:
    data = []
okt = sys.argv[1] == "200" and any(
    isinstance(e, dict) and e.get("rollout_state") == "dry_run"
    and e.get("trigger") == "publish" for e in data)
print(("PASS|" if okt else "FAIL|") +
      "rollout timeline 含 publish→dry_run 事件（API 可读，%d 条）" % len(data))
PY
done

# ---------- [7] 幂等 apply ----------
info "[7] 重复 apply → skipped_exists"
CODE=$(api_c POST "$CTRL/admin/tenants/$TENANT/evaluation/reports/$REPORT_ID/remediation-apply" "$APPLY_BODY" "$WORK/apply2.json")
run_py - "$CODE" "$WORK/apply2.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[2]))
res = {x["plan_id"]: x for x in b["data"]["results"]}
out = []
def a(cond, msg): out.append(("PASS" if cond else "FAIL", msg))
for pid in ("dlp_secrets", "challenge_highrisk", "block_suspicious_domain"):
    r = res.get(pid, {})
    a(r.get("status") == "skipped_exists" and r.get("existing_state") == "dry_run",
      "%s 重复 apply → skipped_exists（不覆盖，existing_state=dry_run）" % pid)
a("下线" in json.dumps(res, ensure_ascii=False), "失败指导文案存在（下线→编辑→重新发布）")
for st, msg in out:
    print("%s|%s" % (st, msg))
PY

# ---------- [8] 变量防误用 ----------
info "[8] 变量防误用矩阵（未声明/非法值/未知 risk_id）"
apply_item() {
  api_c POST "$CTRL/admin/tenants/$TENANT/evaluation/reports/$REPORT_ID/remediation-apply" "$1" "$2"
}
CODE=$(apply_item '{"items":[{"risk_id":"'"$R_SECRETS"'","plan_id":"dlp_secrets","variables":{"patternXXXX":"x"},"app_id":null}]}' "$WORK/dupe.json")
run_py - "$CODE" "$WORK/dupe.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[2]))["data"]["results"][0]
okr = r["status"] == "failed" and "未声明" in r.get("error", "")
print(("PASS|" if not okr or True else "") if False else ("PASS|" if okr else "FAIL|") +
      "白名单外变量 → failed 未声明变量" + ("" if okr else " (%s %s)" % (r["status"], r.get("error"))))
PY
CODE=$(apply_item '{"items":[{"risk_id":"'"$R_SECRETS"'","plan_id":"dlp_secrets","variables":{"pattern":"a\nb"},"app_id":null}]}' "$WORK/dupe2.json")
run_py - "$CODE" "$WORK/dupe2.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[2]))["data"]["results"][0]
okr = r["status"] == "failed" and "非法" in r.get("error", "")
print(("PASS|" if okr else "FAIL|") + "换行危险值 → failed 非法字符" +
      ("" if okr else " (%s %s)" % (r["status"], r.get("error"))))
PY
CODE=$(apply_item '{"items":[{"risk_id":"nope0001","plan_id":"challenge_highrisk","variables":{"tool":"x"},"app_id":null}]}' "$WORK/dupe3.json")
run_py - "$CODE" "$WORK/dupe3.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[2]))["data"]["results"][0]
okr = r["status"] == "failed" and "不在报告中" in r.get("error", "")
print(("PASS|" if okr else "FAIL|") + "未知 risk_id → failed（历史伪造改判/新风险无凭据）" +
      ("" if okr else " (%s %s)" % (r["status"], r.get("error"))))
PY

# ---------- [9] 边界请求 ----------
CODE=$(api_c POST "$CTRL/admin/tenants/$TENANT/evaluation/reports/$REPORT_ID/remediation-apply" '{"items":[]}' "$WORK/empty.json")
run_py - "$CODE" "$WORK/empty.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[2]))
print(("PASS|" if sys.argv[1] == "400" and "items 必须为" in b.get("message", "")
       else "FAIL|") + "空 items → 400 提示")
PY

echo
if [ "$failed" = "0" ]; then
  echo -e "${GREEN}===== 一键处置全流程 E2E 全绿（work dir: ${WORK}） =====${NC}"
  exit 0
else
  echo -e "${RED}===== 存在失败项（work dir: ${WORK}，服务日志见同目录 *.log） =====${NC}"
  exit 1
fi
