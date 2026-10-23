#!/usr/bin/env bash
# scripts/test-evaluation-capabilities-e2e.sh
# 本次改动（能力位诊断 + 探针 TargetSpec REST）控制面全流程集成测试。
#
# 模拟用户真实操作链路：独立自起 redis / virbius-evaluation（两个实例）/ virbius-control
# 三件套（全新 SQLite 数据目录），全程只经 REST API 操作——不直读/改写 SQLite 或 Redis。
#
# 覆盖：
#   [1] VE GET /api/v1/capabilities：Bearer 鉴权、能力位字段、数据最小化
#       （不回 env 值 / 不回 scan-root 真实路径）
#   [2] VE REST POST /evaluations/probes + target_spec：请求模板渲染、env 头注入、
#       job 轮询、报告 14 探针全命中、JobView 不泄露 spec
#   [2b] target_spec 负例：任意 {{env:...}} / 非 GET/POST / 缺 url / 超大 spec → 400
#   [3] control 代理 /evaluation/probes：target_spec 透传 + job/report 全链路
#   [4] control POST /admin/platform/evaluation/settings/test：
#       url/dns/tcp/health/auth/license + license_features/capabilities/
#       probe_remote/scan_root/mcp_egress/judge_llm 共 12 步全绿
#   [5] 负例：VE_PROBE_ALLOW_REMOTE=false → 诊断在“测试连接”阶段标红（不等提交后 403）
#
# 前置：java17 + mvn、redis-server；不需要 Kafka / 引擎 / 前端 / 许可证。
# 用法：
#   bash scripts/test-evaluation-capabilities-e2e.sh
#   VE_SKIP_BUILD=1 bash ...                 # 复用已构建 jar
#   VE_CAP_E2E_PORT_BASE=18800 bash ...      # 端口基址（+1..+5）
set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'
ok()  { echo -e "${GREEN}[PASS]${NC} $*"; }
err() { echo -e "${RED}[FAIL]${NC} $*"; failed=1; }
info(){ echo -e "${CYAN}[INFO]${NC} $*"; }

ROOT="$(cd "$(dirname "$0")/.."; pwd)"
EVAL_DIR=$ROOT/virbius-evaluation
CTRL_DIR=$ROOT/virbius-control
STUB=$EVAL_DIR/tests/stub_probe_agents.py
WORK=$(mktemp -d /tmp/ve-cap-e2e-XXXXXX)
BASE=${VE_CAP_E2E_PORT_BASE:-18800}
REDISP=$BASE; EVALP=$((BASE+1)); CTRLP=$((BASE+2)); TSPEC=$((BASE+3)); JUDGE=$((BASE+4))
EVAL2P=$((BASE+5))
EVALTOK="e2e-evaluation-token"
EVAL=http://127.0.0.1:$EVALP/api/v1
EVAL2=http://127.0.0.1:$EVAL2P/api/v1
CTRL=http://127.0.0.1:$CTRLP/api/v1
TENANT=default
failed=0
PIDS=()

# shellcheck disable=SC2329  # invoked via trap
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
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

# run_py：Python 断言块内部错误必须显形为 FAIL（禁止 traceback 被吞成假绿）。
run_py() {
  local out errfile
  out=$(mktemp); errfile=$(mktemp)
  if ! python3 "$@" >"$out" 2>"$errfile"; then
    err "run_py 断言块内部错误（未执行，禁止假绿）"
    sed 's/^/py| /' "$errfile"
  fi
  emit <"$out"
  if [ -s "$errfile" ]; then
    err "run_py 断言块 stderr 非空"
    sed 's/^/py| /' "$errfile"
  fi
  rm -f "$out" "$errfile"
}

api() { # $1=method $2=url $3=body(opt) $4=out；evaluation 侧带 Bearer
  local method=$1 url=$2 body=${3:-} out=${4:-}
  case "$method" in
    GET)  curl -s -o "$out" -w "%{http_code}" -H "Authorization: Bearer $EVALTOK" "$url" ;;
    POST) curl -s -o "$out" -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
            -H "Authorization: Bearer $EVALTOK" -d "$body" "$url" ;;
    PUT)  curl -s -o "$out" -w "%{http_code}" -X PUT -H 'Content-Type: application/json' \
            -H "Authorization: Bearer $EVALTOK" -d "$body" "$url" ;;
  esac
}

ctrl_api() { # control 面（dev 关闭鉴权；平台/租户管理员路径）
  local method=$1 path=$2 body=${3:-} out=${4:-}
  case "$method" in
    GET)  curl -s -o "$out" -w "%{http_code}" "$CTRL$path" ;;
    POST) curl -s -o "$out" -w "%{http_code}" -X POST -H 'Content-Type: application/json' \
            -d "$body" "$CTRL$path" ;;
    PUT)  curl -s -o "$out" -w "%{http_code}" -X PUT -H 'Content-Type: application/json' \
            -d "$body" "$CTRL$path" ;;
  esac
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

wait_http() { # $1=url $2=label $3=attempts
  local url=$1 label=$2 n=${3:-60}
  for _ in $(seq 1 "$n"); do
    curl -sf "$url" >/dev/null 2>&1 && return 0
    sleep 1
  done
  err "[0] ${label} 启动超时: $url"
  return 1
}

info "===== 能力位诊断 + 探针 TargetSpec 全流程 E2E ====="
info "work dir: $WORK  ports: redis=$REDISP eval=$EVALP control=$CTRLP target=$TSPEC judge=$JUDGE eval2=$EVAL2P"

# ---------- [0] 构建 + 启动 ----------
info "[0] 构建 jar 并启动 redis / stubs / evaluation×2 / control（全新数据库）"
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

mkdir -p "$WORK/scan-root"
: >"$WORK/scan-root/sample.txt"
REQLOG=$WORK/tspec-req.jsonl
: >"$REQLOG"

redis-server --daemonize no --port "$REDISP" --bind 127.0.0.1 \
  --save "" --appendonly no >"$WORK/redis.log" 2>&1 &
PIDS+=($!)
redis_up=0
for _ in $(seq 1 20); do
  [ "$(redis-cli -p "$REDISP" ping 2>/dev/null)" = "PONG" ] && { redis_up=1; break; }
  sleep 0.5
done
[ "$redis_up" = "1" ] || { err "[0] redis 启动超时"; exit 1; }

BEHAVIOR=templated STUB_REQ_LOG="$REQLOG" STUB_EXPECT_KEY=spec-secret \
  python3 "$STUB" "$TSPEC" >"$WORK/stub-target.log" 2>&1 &
PIDS+=($!)
BEHAVIOR=judge python3 "$STUB" "$JUDGE" >"$WORK/stub-judge.log" 2>&1 &
PIDS+=($!)
wait_http "http://127.0.0.1:$TSPEC/" "templated target stub" 20 || exit 1
wait_http "http://127.0.0.1:$JUDGE/" "judge stub" 20 || exit 1
ok "[0] redis / target / judge stub 就绪"

VE_STORE_DIR=$WORK/eval1-store VE_OSV=false VE_API_TOKEN="$EVALTOK" SERVER_PORT=$EVALP \
  VE_PROBE=true VE_PROBE_ALLOW_REMOTE=true VE_PROBE_ALLOW_HOSTS=127.0.0.1 \
  VE_SCAN_ROOT="$WORK/scan-root" \
  VE_JUDGE_LLM_URL="http://127.0.0.1:$JUDGE" VE_JUDGE_LLM_MODEL=stub-judge \
  VE_JUDGE_LLM_KEY=stub-key \
  VE_PROBE_HEADER_AUTHORIZED_X_API_KEY=spec-secret \
  java -jar "$EVAL_JAR" >"$WORK/eval1.log" 2>&1 &
PIDS+=($!)

# 第二实例：探针开启但 REST 闸关闭，用于“诊断阶段暴露 403”。
VE_STORE_DIR=$WORK/eval2-store VE_OSV=false VE_API_TOKEN="$EVALTOK" SERVER_PORT=$EVAL2P \
  VE_PROBE=true VE_PROBE_ALLOW_REMOTE=false VE_PROBE_ALLOW_HOSTS=127.0.0.1 \
  VE_SCAN_ROOT="$WORK/scan-root" \
  VE_JUDGE_LLM_URL="http://127.0.0.1:$JUDGE" VE_JUDGE_LLM_MODEL=stub-judge \
  VE_JUDGE_LLM_KEY=stub-key \
  VE_PROBE_HEADER_AUTHORIZED_X_API_KEY=spec-secret \
  java -jar "$EVAL_JAR" >"$WORK/eval2.log" 2>&1 &
PIDS+=($!)

SPRING_PROFILES_ACTIVE=dev VIRBIUS_DATA_DIR=$WORK/ctrl-data \
  SPRING_DATA_REDIS_URL=redis://127.0.0.1:$REDISP/9 \
  VIRBIUS_REDIS_URL=redis://127.0.0.1:$REDISP/9 \
  VIRBIUS_LICENSE_MASTER_KEY="e2e-cap-master-key-0123456789-abcdef" \
  KAFKA_BOOTSTRAP_SERVERS=localhost:9092 SERVER_PORT=$CTRLP \
  java -jar "$CTRL_JAR" >"$WORK/ctrl.log" 2>&1 &
PIDS+=($!)

wait_http "$EVAL/health" "evaluation(:$EVALP)" 60 || { tail -30 "$WORK/eval1.log"; exit 1; }
wait_http "$EVAL2/health" "evaluation2(:$EVAL2P)" 60 || { tail -30 "$WORK/eval2.log"; exit 1; }
wait_http "$CTRL/health" "control(:$CTRLP)" 60 || { tail -30 "$WORK/ctrl.log"; exit 1; }
ok "[0] evaluation×2 + control 全部就绪"

# ---------- [1] VE capabilities ----------
info "[1] GET /api/v1/capabilities（Bearer；数据最小化）"
CODE=$(api GET "$EVAL/capabilities" "" "$WORK/cap.json")
run_py - "$CODE" "$WORK/cap.json" "$WORK/scan-root" <<'PY'
import json, sys
code, path, root = sys.argv[1], sys.argv[2], sys.argv[3]
raw = open(path, encoding="utf-8").read()
d = json.loads(raw)
d = d.get("data", d)
def a(cond, msg): print(("PASS|" if cond else "FAIL|") + msg)
a(code == "200", "capabilities → 200")
a(d.get("probe", {}).get("enabled") is True, "probe.enabled=true")
a(d.get("probe", {}).get("allow_remote") is True, "probe.allow_remote=true")
a(d.get("scan_root", {}).get("configured") is True, "scan_root.configured=true")
a(d.get("scan_root", {}).get("usable") is True, "scan_root.usable=true")
eg = d.get("mcp_egress", {})
a(eg.get("allow_hosts_configured") is True and eg.get("allow_hosts") == 1,
  "mcp_egress: allow_hosts_configured=true / allow_hosts=1")
a(eg.get("deny_loopback") is False, "mcp_egress.deny_loopback=false")
j = d.get("judge_llm", {})
a(j.get("configured") is True and j.get("reachable") is True, "judge_llm configured+reachable")
h = d.get("probe_headers", {})
a(h.get("configured") is True and
  "VE_PROBE_HEADER_AUTHORIZED_X_API_KEY" in (h.get("vars") or []),
  "probe_headers 只列变量名（含 AUTHORIZED_X_API_KEY）")
a("spec-secret" not in raw, "响应不回 env 值")
a(root not in raw, "响应不回 scan-root 真实路径")
PY

CODE=$(curl -s -o "$WORK/cap-noauth.json" -w "%{http_code}" "$EVAL/capabilities")
[ "$CODE" = "401" ] && ok "[1] 无 Bearer → 401" || err "[1] 无 Bearer 期望 401，实际 $CODE"

# ---------- [2] REST target_spec 正例 ----------
info "[2] POST /evaluations/probes（target_spec：模板 + env 头注入）"
cat >"$WORK/target-rest.yaml" <<YAML
url: http://127.0.0.1:${TSPEC}/agent/chat
method: POST
headers:
  X-Api-Key: "{{env:VE_PROBE_HEADER_AUTHORIZED_X_API_KEY}}"
  X-Tenant: demo
body:
  message: "{{message}}"
  context: { session: "{{session}}" }
response_path: \$.data.reply
YAML

python3 - "$WORK/target-rest.yaml" "$WORK/probe-rest.json" <<'PY'
import json, sys
spec = open(sys.argv[1], encoding="utf-8").read()
json.dump({"target_type": "http", "identity": "authorized", "target_spec": spec,
           "agent_name": "rest-spec-agent"}, open(sys.argv[2], "w"), ensure_ascii=False)
PY

CODE=$(api POST "$EVAL/evaluations/probes" "@$WORK/probe-rest.json" "$WORK/probe-rest-resp.json")
[ "$CODE" = "202" ] && ok "[2] target_spec 探针提交 → 202" || { err "[2] 提交 HTTP $CODE"; cat "$WORK/probe-rest-resp.json"; exit 1; }
JOB=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["job_id"])' "$WORK/probe-rest-resp.json")
info "[2] job=${JOB}，轮询至终态"
STATUS=""
for _ in $(seq 1 180); do
  CODE=$(api GET "$EVAL/evaluations/jobs/$JOB" "" "$WORK/job-rest.json")
  [ "$CODE" = "200" ] || { err "[2] job 查询 HTTP $CODE"; cat "$WORK/job-rest.json"; exit 1; }
  STATUS=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["status"])' "$WORK/job-rest.json")
  case "$STATUS" in succeeded|failed|canceled) break ;; esac
  sleep 1
done
[ "$STATUS" = "succeeded" ] && ok "[2] job succeeded" || { err "[2] job status=$STATUS"; cat "$WORK/job-rest.json"; tail -30 "$WORK/eval1.log"; exit 1; }
RID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["report_id"])' "$WORK/job-rest.json")
[ -n "$RID" ] && ok "[2] report_id=$RID" || { err "[2] job 无 report_id"; exit 1; }
RAWJOB=$(cat "$WORK/job-rest.json")
case "$RAWJOB" in *target_spec*|*spec-secret*) err "[2] JobView 泄露 target_spec/凭据" ;; *) ok "[2] JobView 不含 spec/凭据" ;; esac

CODE=$(api GET "$EVAL/evaluations/reports/$RID" "" "$WORK/report-rest.json")
run_py - "$CODE" "$WORK/report-rest.json" "$REQLOG" "$TSPEC" <<'PY'
import json, sys
code, path, reqlog, tspec = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
r = json.load(open(path, encoding="utf-8")); r = r.get("data", r)
def a(cond, msg): print(("PASS|" if cond else "FAIL|") + msg)
a(code == "200", "报告 → 200")
a(len(r.get("findings", [])) == 14, "模板目标 14 探针全命中（%d）" % len(r.get("findings", [])))
a(all(f.get("confidence") == "verified" for f in r.get("findings", [])),
  "全部 confidence=verified")
m = r.get("meta") or {}
a(m.get("target_type") == "http", "meta.target_type=http")
a(str(m.get("target_fingerprint") or "").startswith("http:"), "meta.target_fingerprint 存在（http:）")
ps = r.get("probe_summary") or {}
a(ps.get("total") == 18 and ps.get("hit") == 14 and ps.get("skipped") == 4,
  "probe_summary total=18 hit=14 skipped=4（%s）" % json.dumps(ps, ensure_ascii=False))
rows = [json.loads(l) for l in open(reqlog, encoding="utf-8") if l.strip()]
a(bool(rows), "目标收到请求（%d 条）" % len(rows))
a(all(x.get("x_api_key") == "spec-secret" for x in rows), "env 头注入：X-Api-Key 正确")
a(all(x.get("message") and "{{" not in x.get("message", "") for x in rows),
  "body {{message}} 已替换（无占位残留）")
a(all(x.get("session") for x in rows), "body {{session}} 已替换")
a(all(x.get("x_tenant") == "demo" for x in rows), "静态头 X-Tenant=demo 保留")
PY

# ---------- [2b] target_spec 负例 ----------
info "[2b] target_spec 安全边界（env 前缀 / method / url / 大小）"
python3 - "$WORK/probe-rest.json" <<'PY' >"$WORK/neg-env.json"
import json, sys
b = json.load(open(sys.argv[1]))
b["target_spec"] = b["target_spec"].replace("VE_PROBE_HEADER_AUTHORIZED_X_API_KEY", "VE_API_TOKEN")
json.dump(b, sys.stdout, ensure_ascii=False)
PY
CODE=$(api POST "$EVAL/evaluations/probes" "@$WORK/neg-env.json" "$WORK/neg-env-resp.json")
run_py - "$CODE" "$WORK/neg-env-resp.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[2], encoding="utf-8"))
msg = b.get("message", "")
okv = sys.argv[1] == "400" and "VE_PROBE_HEADER_" in msg
print(("PASS|" if okv else "FAIL|") + "任意 {{env:VE_API_TOKEN}} → 400（仅允许 VE_PROBE_HEADER_ 前缀）" +
      ("" if okv else " code=%s msg=%s" % (sys.argv[1], msg)))
PY

python3 - "$WORK/probe-rest.json" <<'PY' >"$WORK/neg-method.json"
import json, sys
b = json.load(open(sys.argv[1]))
b["target_spec"] = b["target_spec"].replace("method: POST", "method: TRACE")
json.dump(b, sys.stdout, ensure_ascii=False)
PY
CODE=$(curl -s -o "$WORK/neg-method-resp.json" -w "%{http_code}" -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EVALTOK" \
  -d @"$WORK/neg-method.json" "$EVAL/evaluations/probes")
[ "$CODE" = "400" ] && ok "[2b] method=TRACE → 400" || err "[2b] method=TRACE 期望 400，实际 $CODE"

python3 - "$WORK/probe-rest.json" <<'PY' >"$WORK/neg-url.json"
import json, sys
b = json.load(open(sys.argv[1]))
b["target_spec"] = "method: POST\nbody: {message: hi}\n"
json.dump(b, sys.stdout, ensure_ascii=False)
PY
CODE=$(curl -s -o "$WORK/neg-url-resp.json" -w "%{http_code}" -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EVALTOK" \
  -d @"$WORK/neg-url.json" "$EVAL/evaluations/probes")
[ "$CODE" = "400" ] && ok "[2b] 缺 url → 400" || err "[2b] 缺 url 期望 400，实际 $CODE"

python3 - "$WORK/neg-big.json" <<'PY'
import json, sys
json.dump({"target_type": "http", "target_spec": "url: http://127.0.0.1/\n" + "x" * 70000},
          open(sys.argv[1], "w"))
PY
CODE=$(curl -s -o "$WORK/neg-big-resp.json" -w "%{http_code}" -X POST \
  -H 'Content-Type: application/json' -H "Authorization: Bearer $EVALTOK" \
  -d @"$WORK/neg-big.json" "$EVAL/evaluations/probes")
[ "$CODE" = "400" ] && ok "[2b] 超大 spec → 400" || err "[2b] 超大 spec 期望 400，实际 $CODE"

# ---------- [3] control 代理 target_spec ----------
info "[3] PUT settings（VE1 + probe_tenants=*）→ control 代理探针"
python3 - "$EVALP" "$EVALTOK" <<'PY' >"$WORK/settings.json"
import json, sys
json.dump({"enabled": True, "base_url": "http://127.0.0.1:%s" % sys.argv[1],
           "api_token": sys.argv[2], "promo_enabled": False, "probe_tenants": "*"},
          sys.stdout)
PY
CODE=$(ctrl_api PUT "/admin/platform/evaluation/settings" "@$WORK/settings.json" "$WORK/settings-resp.json")
[ "$CODE" = "200" ] && ok "[3] settings 保存 → 200" || { err "[3] settings HTTP $CODE"; cat "$WORK/settings-resp.json"; exit 1; }

python3 - "$WORK/probe-rest.json" "$WORK/probe-ctrl.json" <<'PY'
import json, sys
b = json.load(open(sys.argv[1]))
b["agent_name"] = "ctrl-spec-agent"
json.dump(b, open(sys.argv[2], "w"), ensure_ascii=False)
PY
CODE=$(curl -s -o "$WORK/probe-ctrl-resp.json" -w "%{http_code}" -X POST \
  -H 'Content-Type: application/json' -d @"$WORK/probe-ctrl.json" \
  "$CTRL/admin/tenants/$TENANT/evaluation/probes")
[ "$CODE" = "202" ] && ok "[3] control 代理提交 → 202" || { err "[3] 代理提交 HTTP $CODE"; cat "$WORK/probe-ctrl-resp.json"; exit 1; }
CJOB=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["job_id"])' "$WORK/probe-ctrl-resp.json")
CSTATUS=""
for _ in $(seq 1 180); do
  CODE=$(ctrl_api GET "/admin/tenants/$TENANT/evaluation/jobs/$CJOB" "" "$WORK/cjob.json")
  [ "$CODE" = "200" ] || { err "[3] 代理 job 查询 HTTP $CODE"; cat "$WORK/cjob.json"; exit 1; }
  CSTATUS=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["status"])' "$WORK/cjob.json")
  case "$CSTATUS" in succeeded|failed|canceled) break ;; esac
  sleep 1
done
[ "$CSTATUS" = "succeeded" ] && ok "[3] 代理 job succeeded" || { err "[3] 代理 job status=$CSTATUS"; cat "$WORK/cjob.json"; exit 1; }
CRID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["data"]["report_id"])' "$WORK/cjob.json")
CODE=$(ctrl_api GET "/admin/tenants/$TENANT/evaluation/reports/$CRID" "" "$WORK/creport.json")
run_py - "$CODE" "$WORK/creport.json" <<'PY'
import json, sys
r = json.load(open(sys.argv[2], encoding="utf-8")); r = r.get("data", r)
def a(cond, msg): print(("PASS|" if cond else "FAIL|") + msg)
a(sys.argv[1] == "200", "代理报告 → 200")
a(len(r.get("findings", [])) == 14, "代理报告 14 探针全命中（%d）" % len(r.get("findings", [])))
PY

# 代理负例：env 越权同样透传 400
CODE=$(curl -s -o "$WORK/neg-ctrl-resp.json" -w "%{http_code}" -X POST \
  -H 'Content-Type: application/json' -d @"$WORK/neg-env.json" \
  "$CTRL/admin/tenants/$TENANT/evaluation/probes")
[ "$CODE" = "400" ] && ok "[3] 代理透传 400（env 白名单）" || err "[3] 代理负例期望 400，实际 $CODE"

# ---------- [4] 诊断正例：12 步全绿 ----------
info "[4] POST /admin/platform/evaluation/settings/test（12 步）"
CODE=$(ctrl_api POST "/admin/platform/evaluation/settings/test" '{}' "$WORK/diag.json")
run_py - "$CODE" "$WORK/diag.json" <<'PY'
import json, sys
code = sys.argv[1]
d = json.load(open(sys.argv[2], encoding="utf-8")); d = d.get("data", d)
steps = {s.get("step"): s for s in d.get("steps", [])}
def a(cond, msg): print(("PASS|" if cond else "FAIL|") + msg)
want = ("url", "dns", "tcp", "health", "auth", "license", "license_features",
        "capabilities", "probe_remote", "scan_root", "mcp_egress", "judge_llm")
a(code == "200", "诊断 → 200")
a(len(d.get("steps", [])) == 12, "步骤数=12（%d）" % len(d.get("steps", [])))
a(all(k in steps for k in want), "新步骤齐全：%s" % ",".join(want[6:]))
a(all(steps.get(k, {}).get("ok") is True for k in want), "12 步全绿")
a("未限制" in steps.get("license_features", {}).get("detail", ""), "license_features=未限制（trial）")
a("allow_remote=true" in steps.get("probe_remote", {}).get("detail", ""), "probe_remote 显示 allow_remote=true")
a("usable=true" in steps.get("scan_root", {}).get("detail", ""), "scan_root usable=true")
a("allow_hosts=1" in steps.get("mcp_egress", {}).get("detail", ""), "mcp_egress allow_hosts=1")
a("TCP 连通" in steps.get("judge_llm", {}).get("detail", ""), "judge_llm TCP 连通")
PY

# ---------- [5] 诊断负例：allow_remote=false 在测试阶段标红 ----------
info "[5] 切换到 VE2（VE_PROBE_ALLOW_REMOTE=false）→ 诊断预暴露 403"
python3 - "$EVAL2P" "$EVALTOK" <<'PY' >"$WORK/settings2.json"
import json, sys
json.dump({"enabled": True, "base_url": "http://127.0.0.1:%s" % sys.argv[1],
           "promo_enabled": False, "probe_tenants": "*"}, sys.stdout)
PY
CODE=$(curl -s -o "$WORK/settings2-resp.json" -w "%{http_code}" -X PUT \
  -H 'Content-Type: application/json' -d @"$WORK/settings2.json" \
  "$CTRL/admin/platform/evaluation/settings")
[ "$CODE" = "200" ] && ok "[5] 切换到 VE2 → 200" || { err "[5] settings HTTP $CODE"; cat "$WORK/settings2-resp.json"; exit 1; }
CODE=$(ctrl_api POST "/admin/platform/evaluation/settings/test" '{}' "$WORK/diag2.json")
run_py - "$CODE" "$WORK/diag2.json" <<'PY'
import json, sys
d = json.load(open(sys.argv[2], encoding="utf-8")); d = d.get("data", d)
steps = {s.get("step"): s for s in d.get("steps", [])}
p = steps.get("probe_remote", {})
def a(cond, msg): print(("PASS|" if cond else "FAIL|") + msg)
a(sys.argv[1] == "200", "诊断接口仍 → 200（结构可读）")
a(d.get("ok") is False, "整体 ok=false（测试阶段暴露）")
a(p.get("ok") is False, "probe_remote 标红")
a("allow_remote=false" in p.get("detail", "") and "403" in p.get("detail", ""),
  "detail 明确 allow_remote=false 且提示 403")
a(steps.get("capabilities", {}).get("ok") is True, "capabilities 本身读取成功")
PY

echo
if [ "$failed" = "0" ]; then
  echo -e "${GREEN}===== 能力位诊断 + 探针 TargetSpec E2E 全绿（work dir: ${WORK}） =====${NC}"
  exit 0
else
  echo -e "${RED}===== 存在失败项（work dir: ${WORK}，服务日志见同目录 *.log） =====${NC}"
  exit 1
fi
