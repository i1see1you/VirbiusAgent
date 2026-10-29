#!/usr/bin/env bash
# ============================================================
# test-custom-prompt-judge-e2e.sh
#
# 自定义 prompt judge 规则的全流程 E2E：
#   运营台 API 创建/校验 → 模拟 → 上线 dry_run → 引擎 evaluate
#   → 灰度/全量 → 阈值边界 → fail_open/fail_closed → 停用
#   → 草稿编辑校验 → 内置 category 模拟 → 异步 judge
#   → async 动作投递（webhook 真实外呼/停用即停投/白名单拒绝/按字段截断
#                      / kafka at-most-once 失败指标）
#   → async 配置创建拒绝矩阵 / 草稿编辑拒绝与生效 / 配置回读 → 列表可见性
#
# 只通过 virbius-control 的运营台 API 做管理操作，并通过引擎 /v1/evaluate
# 验证真实调用面；不直接打开 SQLite，也不执行 redis-cli/读写 Redis 数据。
# 脚本自起独立 Redis、control、engine、judge stub，使用全新 SQLite 数据目录。
#
# 分类约定：10 个内置 prompt rule_id 走 category，其他 runtime=prompt 一律按 judge。
# 内置 rule_id 不存在时，普通 prompt 文本会被判为非法 judge body。
#
# 前置：java17、mvn、redis-server、python3、curl。
#
# 用法：
#   bash scripts/test-custom-prompt-judge-e2e.sh
#   JUDGE_E2E_SKIP_BUILD=1 bash scripts/test-custom-prompt-judge-e2e.sh
#   JUDGE_E2E_PORT_BASE=18800 bash scripts/test-custom-prompt-judge-e2e.sh
#   JUDGE_E2E_KEEP=1 bash scripts/test-custom-prompt-judge-e2e.sh
# ============================================================

set -uo pipefail

RED='\033[31m'; GREEN='\033[32m'; YELLOW='\033[33m'; BLUE='\033[34m'; CYAN='\033[36m'; NC='\033[0m'
PASS=0; FAIL=0; GAP=0; SKIP=0

ok()   { echo -e "  ${GREEN}PASS${NC}: $*"; PASS=$((PASS+1)); }
bad()  { echo -e "  ${RED}FAIL${NC}: $*"; FAIL=$((FAIL+1)); }
gap()  { echo -e "  ${YELLOW}GAP ${NC}: $*"; GAP=$((GAP+1)); }
step() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
info() { echo -e "  ${CYAN}INFO${NC}: $*"; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d /tmp/virbius-judge-e2e-XXXXXX)"
BASE="${JUDGE_E2E_PORT_BASE:-18800}"
REDISP="$BASE"
CTRLP=$((BASE + 1))
ENGINEP=$((BASE + 2))
STUBP=$((BASE + 3))
TENANT="${VIRBIUS_TENANT:-default}"
CONTROL="http://127.0.0.1:${CTRLP}"
ENGINE="http://127.0.0.1:${ENGINEP}"
STUB="http://127.0.0.1:${STUBP}"
BODY="${WORK}/body.json"

cleanup() {
  local keep="${JUDGE_E2E_KEEP:-0}" pid
  local pids=()
  for pid in "${CTRL_PID:-}" "${ENGINE_PID:-}" "${STUB_PID:-}" "${REDIS_PID:-}"; do
    [ -n "$pid" ] && pids+=("$pid")
  done
  if [ "${#pids[@]}" -gt 0 ]; then
    for pid in "${pids[@]}"; do
      kill "$pid" 2>/dev/null || true
    done
    for pid in "${pids[@]}"; do
      for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.1
      done
      kill -9 "$pid" 2>/dev/null || true
    done
  fi
  if [ "$keep" = "1" ]; then
    info "保留工作目录：${WORK}"
  else
    rm -rf "$WORK"
  fi
}
trap cleanup EXIT INT TERM

jget_file() {
  python3 - "$1" "$2" <<'PY'
import json, sys
try:
    node = json.load(open(sys.argv[1], encoding="utf-8"))
except Exception:
    print("")
    raise SystemExit
for part in sys.argv[2].lstrip(".").split("."):
    if isinstance(node, dict):
        node = node.get(part)
    elif isinstance(node, list):
        try:
            node = node[int(part)]
        except Exception:
            node = None
    else:
        node = None
    if node is None:
        break
print("" if node is None else node)
PY
}

jget() { jget_file "$BODY" "$1"; }

api() {
  local method="$1" path="$2" file="${3:-}"
  local url="${CONTROL}${path}"
  if [ -n "$file" ]; then
    curl -sS --noproxy '*' --max-time 30 -o "$BODY" -w '%{http_code}' \
      -X "$method" "$url" -H 'Content-Type: application/json' \
      -H 'X-User-Id: e2e-judge' -d @"$file"
  else
    curl -sS --noproxy '*' --max-time 30 -o "$BODY" -w '%{http_code}' \
      -X "$method" "$url" -H 'Content-Type: application/json' \
      -H 'X-User-Id: e2e-judge'
  fi
}

assert_ok() {
  local code="$1" label="$2"
  if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
    ok "$label"
    return 0
  fi
  bad "$label (HTTP ${code}, code=$(jget '.code'), msg=$(jget '.message'))"
  return 1
}

assert_eq() {
  local got="$1" want="$2" label="$3"
  if [ "$got" = "$want" ]; then
    ok "$label: ${got}"
  else
    bad "$label: got [${got}] want [${want}]"
  fi
}

wait_tcp() {
  python3 - "$1" "$2" <<'PY'
import socket, sys, time
host, port = sys.argv[1], int(sys.argv[2])
for _ in range(80):
    s = socket.socket()
    s.settimeout(0.3)
    try:
        s.connect((host, port))
        print("ready")
        raise SystemExit(0)
    except Exception:
        time.sleep(0.25)
    finally:
        s.close()
print("timeout")
raise SystemExit(1)
PY
}

wait_http() {
  local url="$1" label="$2" pid="${3:-}"
  for _ in $(seq 1 90); do
    curl -sf --noproxy '*' --max-time 2 "$url" >/dev/null 2>&1 && return 0
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      bad "$label 进程已退出: $url"
      return 1
    fi
    sleep 1
  done
  bad "$label not ready: $url"
  return 1
}

engine_startup_hint() {
  local log="$1"
  if grep -q 'No default constructor found' "$log" 2>/dev/null; then
    echo "  ROOT CAUSE: judge backend 构造器注入失败（Spring 看到多个构造器但无 @Autowired/默认构造器）：" >&2
    grep -o 'Failed to instantiate \[[^]]*JudgeBackend\]' "$log" 2>/dev/null | sort -u | sed 's/^/    /' >&2 || true
    echo "    单测直接 new 构造器，未覆盖 Spring 容器启动；这是产品代码缺口，不是 E2E 脚本问题。" >&2
  fi
}

make_rule_payload() {
  local out="$1" rule_id="$2" threshold="$3" on_error="$4" ready="$5" criterion="$6"
  python3 - "$out" "$rule_id" "$threshold" "$on_error" "$ready" "$criterion" <<'PY'
import json, sys
out, rule_id, threshold, on_error, ready, criterion = sys.argv[1:]
body = {
    "version": 1,
    "mode": "judge",
    "criterion": criterion,
    "examples": {"hit": ["请推荐一只基金", "帮我看看这只基金能不能买"]},
    "threshold": float(threshold),
    "on_error": on_error,
    "enforcement_ready": ready == "true",
}
payload = {
    "rule_id": rule_id,
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE",
    "risk_score": 90,
    "intent_action": "deny",
    "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False),
    "is_async": False,
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
}

publish_rule() {
  local rule_id="$1"
  local code
  code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/${rule_id}/rollout/publish" "${WORK}/empty.json")
  assert_ok "$code" "publish ${rule_id} → dry_run"
  assert_eq "$(jget '.data.rollout_state')" "dry_run" "publish 后状态"
}

disable_rule() {
  local rule_id="$1"
  local code
  code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/${rule_id}/rollout/disable" "${WORK}/empty.json")
  assert_ok "$code" "disable ${rule_id}"
}

make_eval_payload() {
  local out="$1" content="$2"
  python3 - "$out" "$TENANT" "$content" <<'PY'
import json, sys, time, os
out, tenant, content = sys.argv[1:]
now = time.time_ns()
payload = {
    "tenant_id": tenant,
    "session_id": "e2e-judge-%s-%s" % (os.getpid(), now),
    "trace_id": "e2e-judge-trace-%s" % now,
    "role": "input",
    "content": content,
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
}

evaluate() {
  local content="$1"
  make_eval_payload "${WORK}/eval-request.json" "$content"
  curl -sS --noproxy '*' --max-time 30 -o "${WORK}/eval-response.json" \
    -w '%{http_code}' -X POST "${ENGINE}/v1/evaluate" \
    -H 'Content-Type: application/json' -d @"${WORK}/eval-request.json"
}

eval_field() { jget_file "${WORK}/eval-response.json" "$1"; }

wait_eval() {
  local label="$1" content="$2" field="$3" want="$4" tries="${5:-20}"
  local code got i
  for i in $(seq 1 "$tries"); do
    code=$(evaluate "$content")
    if [ "$code" = "200" ]; then
      got=$(eval_field "$field")
      if [ "$got" = "$want" ]; then
        ok "${label}（第 ${i} 次探针）: ${field}=${got}"
        return 0
      fi
    fi
    sleep 1
  done
  bad "$label: 20 次探针内 ${field} 始终=[${got:-<empty>}]（期望 ${want}）"
  python3 - "${WORK}/eval-response.json" <<'PY'
import json, sys
try:
    print(json.dumps(json.load(open(sys.argv[1], encoding="utf-8")), ensure_ascii=False)[:1000])
except Exception:
    print("<no response>")
PY
  return 1
}

stub_calls() {
  local kind="${1:-all}"
  curl -sS --noproxy '*' --max-time 5 "${STUB}/calls" > "${WORK}/calls.json"
  python3 - "${WORK}/calls.json" "$kind" <<'PY'
import json, sys
calls = json.load(open(sys.argv[1], encoding="utf-8"))
kind = sys.argv[2]
if kind == "all":
    print(len(calls))
else:
    print(len([c for c in calls if c.get("kind") == kind]))
PY
}

stub_assert_judge_content() {
  local expected_file="$1" label="$2"
  curl -sS --noproxy '*' --max-time 5 "${STUB}/calls" > "${WORK}/calls.json"
  local found
  found=$(python3 - "${WORK}/calls.json" "$expected_file" <<'PY'
import json, sys
calls = json.load(open(sys.argv[1], encoding="utf-8"))
want = open(sys.argv[2], encoding="utf-8").read()
print("1" if any(c.get("kind") == "judge" and c.get("content") == want for c in calls) else "0")
PY
)
  if [ "$found" = "1" ]; then
    ok "${label}（judge 收到完整未截断原文）"
  else
    bad "${label}（未找到等长原文；可能被分块/截断或未走 judge）"
  fi
}

# 读取 engine Prometheus 指标（只读 HTTP，不碰 DB/Redis）。
# 用法: prom_metric <metric_name> <k=v,k=v>；无匹配输出空串。
prom_metric() {
  local name="$1" labels="$2"
  curl -sS --noproxy '*' --max-time 5 "${ENGINE}/actuator/prometheus" > "${WORK}/prom.txt" || { echo ""; return; }
  python3 - "${WORK}/prom.txt" "$name" "$labels" <<'PY'
import re, sys
path, name, labels = sys.argv[1], sys.argv[2], sys.argv[3]
want = {}
for kv in [x for x in labels.split(",") if x]:
    k, v = kv.split("=", 1)
    want[k.strip()] = v.strip().strip('"')
total = 0.0
found = False
for line in open(path, encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#") or not line.startswith(name):
        continue
    head, _, value = line.rpartition(" ")
    try:
        val = float(value)
    except Exception:
        continue
    m = re.match(r"^[a-zA-Z_:][a-zA-Z0-9_:]*(?:\{(.*)\})?$", head)
    if not m:
        continue
    pairs = dict(re.findall(r'([a-zA-Z_][a-zA-Z0-9_]*)="([^"]*)"', m.group(1) or ""))
    if all(pairs.get(k) == v for k, v in want.items()):
        total += val
        found = True
print(("%g" % total) if found else "")
PY
}

# 轮询指标直到 >= want（浮点比较用 python），失败打印当前值。
wait_prom_at_least() {
  local label="$1" name="$2" labels="$3" want="$4" tries="${5:-40}"
  local i got
  for i in $(seq 1 "$tries"); do
    got=$(prom_metric "$name" "$labels")
    if python3 - "$got" "$want" <<'PY'
import sys
try:
    got, want = float(sys.argv[1]), float(sys.argv[2])
except Exception:
    raise SystemExit(1)
raise SystemExit(0 if got >= want else 1)
PY
    then
      ok "${label}（第 ${i} 次探针）: ${got}"
      return 0
    fi
    sleep 0.25
  done
  bad "${label}: ${name}{${labels}} 始终=${got:-<missing>}（期望 >= ${want}）"
  return 1
}

# 在 stub /calls 中找第一条 body 含 needle 的 webhook 投递，输出其 body；未找到输出空串。
stub_hook_body() {
  local needle="$1"
  curl -sS --noproxy '*' --max-time 5 "${STUB}/calls" > "${WORK}/calls.json" || { echo ""; return; }
  python3 - "${WORK}/calls.json" "$needle" <<'PY'
import json, sys
calls = json.load(open(sys.argv[1], encoding="utf-8"))
for c in calls:
    if c.get("kind") == "hook" and sys.argv[2] in str(c.get("body", "")):
        print(c.get("body", ""))
        break
PY
}


# ============================================================
# [0] 构建与启动隔离环境
# ============================================================
step "[0] 构建并启动隔离 Redis/control/engine/judge stub"

for p in "$REDISP" "$CTRLP" "$ENGINEP" "$STUBP"; do
  if python3 - "$p" <<'PY' >/dev/null 2>&1
import socket, sys
s = socket.socket(); s.settimeout(0.2)
try:
    s.connect(("127.0.0.1", int(sys.argv[1])))
except Exception:
    raise SystemExit(1)
raise SystemExit(0)
PY
  then
    echo "端口 ${p} 已被占用，请设置 JUDGE_E2E_PORT_BASE 或释放端口" >&2
    exit 1
  fi
done

VERSION=$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
ENGINE_JAR="${ROOT}/virbius-engine/target/virbius-engine-${VERSION}.jar"
CTRL_JAR="${ROOT}/virbius-control/target/virbius-control-${VERSION}.jar"

if [ "${JUDGE_E2E_SKIP_BUILD:-0}" != "1" ]; then
  info "mvn package virbius-engine,virbius-control"
  if ! (cd "$ROOT" && mvn -q -pl virbius-engine,virbius-control -am package \
      -DskipTests -Dskip.installnodenpm -Dskip.npm) >"${WORK}/build.log" 2>&1; then
    bad "构建失败，见 ${WORK}/build.log"
    tail -30 "${WORK}/build.log" >&2 || true
    exit 1
  fi
  ok "构建完成"
else
  info "跳过构建（JUDGE_E2E_SKIP_BUILD=1）"
fi

[ -f "$ENGINE_JAR" ] || { bad "engine jar 不存在: $ENGINE_JAR"; exit 1; }
[ -f "$CTRL_JAR" ] || { bad "control jar 不存在: $CTRL_JAR"; exit 1; }
[ -f "${WORK}/empty.json" ] || echo '{}' > "${WORK}/empty.json"

command -v redis-server >/dev/null 2>&1 || { bad "缺少 redis-server"; exit 1; }
command -v python3 >/dev/null 2>&1 || { bad "缺少 python3"; exit 1; }

cat > "${WORK}/judge_stub.py" <<'PY'
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

port = int(sys.argv[1])
log_path = sys.argv[2]
lock = threading.Lock()


def record(entry):
    with lock:
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")


def read_calls():
    calls = []
    try:
        with open(log_path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    calls.append(json.loads(line))
    except FileNotFoundError:
        pass
    return calls


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def send_json(self, status, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.send_json(200, {"ok": True})
        elif self.path == "/calls":
            self.send_json(200, read_calls())
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw.decode("utf-8"))
        except Exception:
            body = {}
        if self.path == "/v1/judge":
            content = str((body.get("state") or {}).get("content") or "")
            rules = body.get("rules") or []
            if not isinstance(rules, list):
                rules = []
            if "JUDGE_DOWN" in content:
                record({"kind": "judge", "content": content,
                        "rules": [r.get("id") for r in rules if isinstance(r, dict)],
                        "error": "stub down"})
                self.send_json(500, {"error": "stub down"})
                return
            if "JUDGE_EXACT_095" in content:
                probability = 0.95
            else:
                probability = 0.9 if any(k in content for k in ("基金", "医疗", "阈值")) else 0.1
            answers = {}
            for rule in rules:
                if isinstance(rule, dict) and rule.get("id"):
                    answers[str(rule["id"])] = {"noul": probability}
            record({"kind": "judge", "content": content,
                    "rules": [r.get("id") for r in rules if isinstance(r, dict)],
                    "probability": probability})
            self.send_json(200, {"answers": answers})
            return
        if self.path == "/hook":
            record({"kind": "hook", "body": raw.decode("utf-8", "replace")[:800]})
            self.send_json(200, {"ok": True})
            return
        if self.path == "/v1/chat/completions":
            record({"kind": "category", "content": raw.decode("utf-8", "replace")[:200]})
            self.send_json(200, {
                "choices": [{"message": {"content": '{"hit_rule": false, "triggered_id": "none"}'}}]
            })
            return
        self.send_json(404, {"error": "not found"})


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY

python3 "${WORK}/judge_stub.py" "$STUBP" "${WORK}/judge-calls.jsonl" >"${WORK}/stub.log" 2>&1 &
STUB_PID=$!
if [ "$(wait_tcp 127.0.0.1 "$STUBP")" != "ready" ]; then
  bad "judge stub 启动失败"
  exit 1
fi
ok "judge stub :${STUBP}"

redis-server --port "$REDISP" --bind 127.0.0.1 --save "" --appendonly no \
  --daemonize no >"${WORK}/redis.log" 2>&1 &
REDIS_PID=$!
if [ "$(wait_tcp 127.0.0.1 "$REDISP")" != "ready" ]; then
  bad "Redis 启动失败"
  exit 1
fi
ok "Redis :${REDISP}（隔离实例，未执行 redis-cli）"

SERVER_PORT="$ENGINEP" \
VIRBIUS_DATA_DIR="${WORK}/engine-data" \
VIRBIUS_REDIS_URL="redis://127.0.0.1:${REDISP}/9" \
VIRBIUS_EVALUATE_AUTH_ENABLED=false \
VIRBIUS_NEOHORSE_URL="http://127.0.0.1:${STUBP}" \
VIRBIUS_PROMPT_JUDGE_TIMEOUT_MS=1000 \
VIRBIUS_PROMPT_LLM_BASE_URL="http://127.0.0.1:${STUBP}" \
VIRBIUS_PROMPT_LLM_TIMEOUT_MS=1000 \
VIRBIUS_LOG_DIR="${WORK}/engine-logs" \
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,prometheus \
VIRBIUS_ASYNC_WEBHOOK_ALLOWED_HOSTS="127.0.0.1:${STUBP}" \
VIRBIUS_ASYNC_FIELD_MAX_CHARS=64 \
KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1" \
SPRING_PROFILES_ACTIVE=dev \
java -jar "$ENGINE_JAR" >"${WORK}/engine.log" 2>&1 &
ENGINE_PID=$!

SERVER_PORT="$CTRLP" \
VIRBIUS_DATA_DIR="${WORK}/control-data" \
VIRBIUS_REDIS_URL="redis://127.0.0.1:${REDISP}/9" \
SPRING_DATA_REDIS_URL="redis://127.0.0.1:${REDISP}/9" \
VIRBIUS_LICENSE_MASTER_KEY="e2e-judge-master-key-0123456789-abcdef" \
VIRBIUS_ENGINE_URL="$ENGINE" \
VIRBIUS_SECURITY_API_KEY_ENABLED=false \
VIRBIUS_LOG_DIR="${WORK}/control-logs" \
KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:1" \
SPRING_PROFILES_ACTIVE=dev \
java -jar "$CTRL_JAR" >"${WORK}/control.log" 2>&1 &
CTRL_PID=$!

wait_http "${ENGINE}/admin/health" "engine" "$ENGINE_PID" || {
  engine_startup_hint "${WORK}/engine.log"
  tail -30 "${WORK}/engine.log" >&2
  exit 1
}
wait_http "${CONTROL}/api/v1/health" "control" "$CTRL_PID" || {
  tail -30 "${WORK}/control.log" >&2
  exit 1
}
ok "engine :${ENGINEP} + control :${CTRLP} 就绪"

# ============================================================
# [1] 运营台校验接口：非法 judge 规则必须被拒绝
# ============================================================
step "[1] 运营台 validate-script：judge schema 校验"

cat > "${WORK}/validate_bad.json" <<'JSON'
{"rule_id":"e2e-judge-fund","layer":"cloud","runtime":"prompt","body":"{\"version\":1,\"mode\":\"judge\",\"criterion\":\"x\"}"}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_bad.json")
assert_ok "$code" "POST validate-script（缺 threshold）"
assert_eq "$(jget '.data.valid')" "False" "缺 threshold 校验失败"
case "$(jget '.data.errors')" in
  *threshold*) ok "错误信息指向 threshold" ;;
  *) bad "错误信息未指向 threshold: $(jget '.data.errors')" ;;
esac

cat > "${WORK}/validate_closed.json" <<'JSON'
{"rule_id":"e2e-judge-fund","layer":"cloud","runtime":"prompt","body":"{\"version\":1,\"mode\":\"judge\",\"criterion\":\"x\",\"threshold\":0.5,\"on_error\":\"fail_closed\"}"}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_closed.json")
assert_ok "$code" "POST validate-script（fail_closed 未 ready）"
assert_eq "$(jget '.data.valid')" "False" "fail_closed 未 ready 校验失败"
case "$(jget '.data.errors')" in
  *enforcement_ready*) ok "错误信息指向 enforcement_ready" ;;
  *) bad "错误信息未指向 enforcement_ready: $(jget '.data.errors')" ;;
esac

cat > "${WORK}/validate_range.json" <<'JSON'
{"rule_id":"e2e-judge-fund","layer":"cloud","runtime":"prompt","body":"{\"version\":1,\"mode\":\"judge\",\"criterion\":\"x\",\"threshold\":0.96,\"on_error\":\"fail_open\"}"}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_range.json")
assert_ok "$code" "POST validate-script（threshold 越界 0.96）"
assert_eq "$(jget '.data.valid')" "False" "threshold 越界校验失败"
case "$(jget '.data.errors')" in
  *between*) ok "错误信息指向 threshold 范围" ;;
  *) bad "错误信息未指向 threshold 范围: $(jget '.data.errors')" ;;
esac

cat > "${WORK}/validate_mode.json" <<'JSON'
{"rule_id":"e2e-judge-fund","layer":"cloud","runtime":"prompt","body":"{\"version\":1,\"mode\":\"category\",\"criterion\":\"x\",\"threshold\":0.5,\"on_error\":\"fail_open\"}"}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_mode.json")
assert_ok "$code" "POST validate-script（自定义 rule_id 用 mode=category）"
assert_eq "$(jget '.data.valid')" "False" "非内置 rule_id 不得声明 category mode"
case "$(jget '.data.errors')" in
  *judge*) ok "错误信息指出 mode 必须 judge" ;;
  *) bad "错误信息未指出 mode 必须 judge: $(jget '.data.errors')" ;;
esac

cat > "${WORK}/validate_good.json" <<'JSON'
{"rule_id":"e2e-judge-fund","layer":"cloud","runtime":"prompt","body":"{\"version\":1,\"mode\":\"judge\",\"criterion\":\"x\",\"threshold\":0.5,\"on_error\":\"fail_open\",\"enforcement_ready\":false}"}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_good.json")
assert_ok "$code" "POST validate-script（合法 judge）"
assert_eq "$(jget '.data.valid')" "True" "合法 judge body valid=true"

cat > "${WORK}/validate_builtin.json" <<'JSON'
{"rule_id":"prompt-pii","layer":"cloud","runtime":"prompt","body":"\"PII category prompt\""}
JSON
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/validate-script" "${WORK}/validate_builtin.json")
assert_ok "$code" "POST validate-script（内置 category prompt）"
assert_eq "$(jget '.data.valid')" "True" "内置 prompt 仍按 category 校验"

# ============================================================
# [2] 创建 judge 规则
# ============================================================
step "[2] 运营台创建 judge 规则"

RULE_ID="e2e-judge-fund"
CRITERION='当用户请求具体基金产品推荐、收益预测或保本承诺时命中'
make_rule_payload "${WORK}/fund_create.json" "$RULE_ID" 0.5 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/fund_create.json")
assert_ok "$code" "POST /rules 创建 ${RULE_ID}"
assert_eq "$(jget '.data.rule_id')" "$RULE_ID" "创建返回 rule_id"
assert_eq "$(jget '.data.rollout_state')" "draft" "初始状态 draft"

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}")
assert_ok "$code" "GET /rules/${RULE_ID}"
if grep -q 'thresholds' "$BODY"; then
  bad "规则 body 仍出现 thresholds（应为单一 threshold）"
else
  ok "规则 body 不含 thresholds"
fi
if grep -q 'threshold' "$BODY"; then
  ok "规则 body 含单一 threshold"
else
  bad "规则 body 未持久化 threshold"
fi

# 创建路径复检：fail_closed 必须显式 enforcement_ready=true 才能入库
make_rule_payload "${WORK}/closed_unready.json" "e2e-judge-closed-unready" 0.5 fail_closed false "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/closed_unready.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "创建接口接受了 fail_closed + enforcement_ready=false"
else
  ok "创建接口拒绝 fail_closed 未 ready（HTTP ${code}）"
fi

# 创建路径复检：非内置 rule_id 的 mode=category body 是非法 judge
python3 - "${WORK}/wrong_mode_create.json" <<'PY'
import json, sys
payload = {
    "rule_id": "e2e-judge-wrong-mode",
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_JUDGE_WRONG_MODE",
    "risk_score": 90,
    "intent_action": "deny",
    "scope": {"bind_scope": "global"},
    "body": json.dumps({"version": 1, "mode": "category", "criterion": "x",
                        "threshold": 0.5, "on_error": "fail_open"}, ensure_ascii=False),
    "is_async": False,
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/wrong_mode_create.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "创建接口接受了 mode=category 的自定义 prompt 规则"
else
  ok "创建接口拒绝 mode=category 的自定义 prompt 规则（HTTP ${code}）"
fi

# ============================================================
# [3] 模拟：judge 规则必须调用 judge backend
# ============================================================
step "[3] 运营台 simulate judge 规则"

python3 - "${WORK}/simulate.json" "${WORK}/fund_create.json" <<'PY'
import json, sys
rule = json.load(open(sys.argv[2], encoding="utf-8"))
rule["rollout_state"] = "dry_run"
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "请推荐一只稳赚不赔的基金"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
BEFORE=$(stub_calls judge)
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/simulate.json")
AFTER=$(stub_calls judge)
if [ "$code" = "200" ] \
  && [ "$(jget '.data.summary.hit')" = "True" ] \
  && [ "$(jget '.data.steps.2.detail.mode')" = "judge" ] \
  && [ "$(jget '.data.steps.2.detail.backend')" = "neohorse-4b" ] \
  && [ "$(jget '.data.steps.2.detail.judge_hit')" = "True" ] \
  && [ "$(jget '.data.steps.2.detail.error_applied')" = "False" ] \
  && [ "$AFTER" -gt "$BEFORE" ]; then
  ok "simulate 命中且调用了 judge backend"
else
  bad "simulate 未走 judge backend（HTTP ${code}，judge 调用 ${BEFORE}→${AFTER}，detail.mode=$(jget '.data.steps.2.detail.mode')）"
fi
assert_eq "$(jget '.data.steps.2.detail.probability')" "0.9" "simulate detail 概率"
assert_eq "$(jget '.data.steps.2.detail.threshold')" "0.5" "simulate detail 阈值"
assert_eq "$(jget '.data.summary.effective_action')" "review" "simulate dry_run 命中 → review"

# full 态模拟：同一 draft 判定应升级为 block（仓位语义在 signal step）
python3 - "${WORK}/simulate_full.json" "${WORK}/fund_create.json" <<'PY'
import json, sys
rule = json.load(open(sys.argv[2], encoding="utf-8"))
rule["rollout_state"] = "full"
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "请推荐一只稳赚不赔的基金"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/simulate_full.json")
assert_ok "$code" "POST simulate（rollout_state=full）"
assert_eq "$(jget '.data.summary.effective_action')" "block" "simulate full 态命中 → block"
assert_eq "$(jget '.data.steps.2.detail.mode')" "judge" "simulate full 仍走 judge"

# ============================================================
# [4] 创建非法 judge：创建接口必须拒绝
# ============================================================
step "[4] 创建非法 judge body（检查创建接口校验）"

INVALID_ID="e2e-judge-invalid"
python3 - "${WORK}/invalid_create.json" "$INVALID_ID" <<'PY'
import json, sys
raw = '{"version":1,"mode":"judge","criterion":"invalid"'
payload = {
    "rule_id": sys.argv[2],
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_JUDGE_INVALID",
    "risk_score": 90,
    "intent_action": "deny",
    "scope": {"bind_scope": "global"},
    "body": raw,
    "is_async": False,
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/invalid_create.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  gap "创建接口接受了非法 judge JSON（HTTP ${code}）"
else
  ok "创建接口拒绝非法 judge JSON（HTTP ${code}）"
fi

# ============================================================
# [5] 上线 dry_run 并通过引擎验证命中/未命中
# ============================================================
step "[5] 上线 dry_run + 引擎 evaluate（命中/未命中）"

publish_rule "$RULE_ID"

LONG_CONTENT="请推荐一只基金$(python3 -c 'print("A" * 5000)')"
printf '%s' "$LONG_CONTENT" > "${WORK}/expected-content.txt"
BEFORE=$(stub_calls judge)
wait_eval "dry_run judge 命中（长原文）" "$LONG_CONTENT" effective_action review || true
assert_eq "$(eval_field 'rule_id')" "$RULE_ID" "dry_run 命中归因 judge 规则"
AFTER=$(stub_calls judge)
if [ "$AFTER" -gt "$BEFORE" ]; then
  ok "engine 调用了 judge backend（${BEFORE}→${AFTER}）"
else
  bad "engine 未调用 judge backend"
fi
stub_assert_judge_content "${WORK}/expected-content.txt" "full-content 透传"

wait_eval "dry_run judge 未命中（良性）" "今天天气不错，适合出去散步" effective_action allow || true

# ============================================================
# [6] 灰度 100% → 全量，验证真实拦截
# ============================================================
step "[6] dry_run → canary(100) → full，验证真实拦截"

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rollout-policy")
assert_ok "$code" "GET rollout-policy"
if [ "$(jget '.data.allow_force')" != "True" ]; then
  python3 - "${WORK}/policy.json" "$BODY" <<'PY'
import json, sys
data = json.load(open(sys.argv[2], encoding="utf-8"))["data"]
data["allow_force"] = True
json.dump(data, open(sys.argv[1], "w"), ensure_ascii=False)
PY
  code=$(api PUT "/api/v1/admin/tenants/${TENANT}/rollout-policy" "${WORK}/policy.json")
  assert_ok "$code" "打开 allow_force"
fi

cat > "${WORK}/canary.json" <<'JSON'
{"rollout_state":"canary","canary_percent":100,"force":true,"comment":"e2e judge: force canary"}
JSON
code=$(api PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${WORK}/canary.json")
assert_ok "$code" "${RULE_ID} dry_run → canary(100)"
assert_eq "$(jget '.data.rollout_state')" "canary" "状态 canary"

cat > "${WORK}/full.json" <<'JSON'
{"rollout_state":"full","canary_percent":null,"force":true,"comment":"e2e judge: force full"}
JSON
code=$(api PATCH "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout" "${WORK}/full.json")
assert_ok "$code" "${RULE_ID} canary → full"
assert_eq "$(jget '.data.rollout_state')" "full" "状态 full"

wait_eval "full judge 命中拦截" "$LONG_CONTENT" effective_action block || true
assert_eq "$(eval_field 'rule_id')" "$RULE_ID" "full 命中归因 judge 规则"
wait_eval "full judge 未命中放行" "今天天气不错，适合出去散步" effective_action allow || true

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${RULE_ID}/rollout/timeline")
assert_ok "$code" "GET rollout timeline"
STATES=$(python3 - "$BODY" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
except Exception:
    data = []
print(",".join(str(x.get("rollout_state", "")) for x in data))
PY
)
if [[ "$STATES" == *dry_run* && "$STATES" == *canary* && "$STATES" == *full* ]]; then
  ok "timeline 覆盖 dry_run/canary/full: ${STATES}"
else
  bad "timeline 未覆盖完整放量路径: ${STATES}"
fi

disable_rule "$RULE_ID"
wait_eval "disable 后 judge 规则不再命中" "$LONG_CONTENT" effective_action allow || true

# ============================================================
# [7] 单阈值：0.9 概率 < 0.95 阈值不命中
# ============================================================
step "[7] 单 threshold 边界"

HIGH_ID="e2e-judge-high"
make_rule_payload "${WORK}/high_create.json" "$HIGH_ID" 0.95 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/high_create.json")
assert_ok "$code" "创建 ${HIGH_ID}（threshold=0.95）"
assert_eq "$(jget '.data.rule_id')" "$HIGH_ID" "high rule_id"
publish_rule "$HIGH_ID"
BEFORE=$(stub_calls judge)
wait_eval "0.9 < 0.95 不命中" "请推荐一只基金" effective_action allow || true
AFTER=$(stub_calls judge)
if [ "$AFTER" -gt "$BEFORE" ]; then
  ok "高阈值规则仍调用 judge backend（${BEFORE}→${AFTER}）"
else
  bad "高阈值规则未调用 judge backend"
fi
disable_rule "$HIGH_ID"

# ============================================================
# [8] fail_open：backend 500 时放行
# ============================================================
step "[8] on_error=fail_open（backend 500）"

OPEN_ID="e2e-judge-open"
make_rule_payload "${WORK}/open_create.json" "$OPEN_ID" 0.5 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/open_create.json")
assert_ok "$code" "创建 ${OPEN_ID}（fail_open）"
publish_rule "$OPEN_ID"
wait_eval "fail_open: backend 500 时 allow" "JUDGE_DOWN 请推荐基金" effective_action allow || true
disable_rule "$OPEN_ID"

# ============================================================
# [9] fail_closed：backend 500 时按规则 action 产出信号
# ============================================================
step "[9] on_error=fail_closed（backend 500）"

CLOSED_ID="e2e-judge-closed"
make_rule_payload "${WORK}/closed_create.json" "$CLOSED_ID" 0.5 fail_closed true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/closed_create.json")
assert_ok "$code" "创建 ${CLOSED_ID}（fail_closed + ready）"
assert_eq "$(jget '.data.rule_id')" "$CLOSED_ID" "closed rule_id"
python3 - "${WORK}/closed_simulate.json" "${WORK}/closed_create.json" <<'PY'
import json, sys
rule = json.load(open(sys.argv[2], encoding="utf-8"))
rule["rollout_state"] = "dry_run"
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "JUDGE_DOWN 请推荐基金"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
BEFORE=$(stub_calls judge)
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/closed_simulate.json")
AFTER=$(stub_calls judge)
assert_eq "$(jget '.data.summary.hit')" "True" "simulate fail_closed backend 500 产出信号"
assert_eq "$(jget '.data.summary.effective_action')" "review" "simulate fail_closed dry_run → review"
assert_eq "$(jget '.data.steps.2.detail.judge_hit')" "False" "simulate fail_closed 不伪报模型命中"
assert_eq "$(jget '.data.steps.2.detail.error_applied')" "True" "simulate fail_closed 标记错误策略生效"
assert_eq "$(jget '.data.steps.2.detail.error_policy')" "fail_closed" "simulate fail_closed policy"
if [ "$AFTER" -gt "$BEFORE" ]; then
  ok "simulate fail_closed 调用了 judge backend（${BEFORE}→${AFTER}）"
else
  bad "simulate fail_closed 未调用 judge backend"
fi
publish_rule "$CLOSED_ID"
wait_eval "fail_closed: backend 500 时 review" "JUDGE_DOWN 请推荐基金" effective_action review || true
assert_eq "$(eval_field 'rule_id')" "$CLOSED_ID" "fail_closed 归因规则"
disable_rule "$CLOSED_ID"

# ============================================================
# [10] 非法 judge draft simulate 不得回退 category
# ============================================================
step "[10] 非法 judge JSON 不回退 category（simulate）"

python3 - "${WORK}/invalid_simulate.json" "$INVALID_ID" <<'PY'
import json, sys
rule = {
    "rule_id": sys.argv[2],
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_JUDGE_INVALID",
    "risk_score": 90,
    "intent_action": "deny",
    "scope": {"bind_scope": "global"},
    "body": '{"version":1,"mode":"judge","criterion":"invalid"',
    "rollout_state": "dry_run",
    "is_async": False,
}
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "请推荐一只基金"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
BEFORE_JUDGE=$(stub_calls judge)
BEFORE_CATEGORY=$(stub_calls category)
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/invalid_simulate.json")
AFTER_JUDGE=$(stub_calls judge)
AFTER_CATEGORY=$(stub_calls category)
ERRORS=$(jget '.data.errors')
if [ "$code" = "200" ] \
  && [ "$(jget '.data.ok')" = "False" ] \
  && [ "$(jget '.data.summary.hit')" = "False" ] \
  && [[ "$ERRORS" == *prompt*judge* ]] \
  && [ "$AFTER_JUDGE" = "$BEFORE_JUDGE" ] \
  && [ "$AFTER_CATEGORY" = "$BEFORE_CATEGORY" ]; then
  ok "非法 judge draft 返回错误且未回退 category/judge（errors=${ERRORS}）"
else
  bad "非法 judge draft 处理异常：HTTP=${code} ok=$(jget '.data.ok') hit=$(jget '.data.summary.hit') errors=${ERRORS} judge=${BEFORE_JUDGE}→${AFTER_JUDGE} category=${BEFORE_CATEGORY}→${AFTER_CATEGORY}"
fi

# ============================================================
# [11] 阈值边界：下限(0.05)命中 + 等值(0.95)命中（>= 语义）
# ============================================================
step "[11] 阈值边界（0.05 下限命中 / 0.95 等值命中）"

FLOOR_ID="e2e-judge-floor"
make_rule_payload "${WORK}/floor_create.json" "$FLOOR_ID" 0.05 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/floor_create.json")
assert_ok "$code" "创建 ${FLOOR_ID}（threshold=0.05）"
publish_rule "$FLOOR_ID"
wait_eval "0.1 >= 0.05 命中（良性词也过阈值下限）" "今天天气不错，适合出去散步" effective_action review || true
assert_eq "$(eval_field 'rule_id')" "$FLOOR_ID" "下限命中归因规则"
disable_rule "$FLOOR_ID"

BOUND_ID="e2e-judge-boundary"
make_rule_payload "${WORK}/bound_create.json" "$BOUND_ID" 0.95 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/bound_create.json")
assert_ok "$code" "创建 ${BOUND_ID}（threshold=0.95）"
publish_rule "$BOUND_ID"
BEFORE=$(stub_calls judge)
wait_eval "概率=阈值(0.95>=0.95) 等值命中" "JUDGE_EXACT_095 请推荐一只基金" effective_action review || true
AFTER=$(stub_calls judge)
if [ "$AFTER" -gt "$BEFORE" ]; then
  ok "等值命中仍调用 judge backend（${BEFORE}→${AFTER}）"
else
  bad "等值命中未见 judge 调用"
fi
BEFORE=$(stub_calls judge)
wait_eval "概率低于阈值(0.9<0.95)不命中" "请推荐一只基金" effective_action allow || true
disable_rule "$BOUND_ID"

# ============================================================
# [12] 草稿规则编辑：更新走校验，非法 body 被拒且不脏写
# ============================================================
step "[12] 草稿规则编辑（update 校验）"

EDIT_ID="e2e-judge-edit"
make_rule_payload "${WORK}/edit_create.json" "$EDIT_ID" 0.95 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/edit_create.json")
assert_ok "$code" "创建 ${EDIT_ID}（draft, threshold=0.95）"
assert_eq "$(jget '.data.rollout_state')" "draft" "初始 draft"

python3 - "${WORK}/edit_invalid.json" "$EDIT_ID" "${WORK}/edit_create.json" <<'PY'
import json, sys
out, rule_id, src = sys.argv[1], sys.argv[2], sys.argv[3]
payload = json.load(open(src, encoding="utf-8"))
payload["rule_id"] = rule_id
payload["body"] = '{"version":1,"mode":"judge","criterion":"broken"'
payload["reason_code"] = "E2E_JUDGE_EDIT_INVALID"
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/edit_invalid.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "草稿编辑接受了非法 judge body"
else
  ok "草稿编辑拒绝非法 judge body（HTTP ${code}）"
fi
rule_threshold() {
  python3 - "$BODY" <<'PY'
import json, sys
try:
    body = json.load(open(sys.argv[1], encoding="utf-8"))["data"]["body"]
    if isinstance(body, str):
        try:
            inner = json.loads(body)
            if isinstance(inner, dict):
                body = inner
        except Exception:
            body = json.loads(json.dumps(body))
    print(body.get("threshold", "") if isinstance(body, dict) else "")
except Exception:
    print("")
PY
}
code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${EDIT_ID}")
assert_ok "$code" "非法编辑后回读规则"
assert_eq "$(rule_threshold)" "0.95" "非法编辑未变更旧 body（仍 threshold=0.95）"

make_rule_payload "${WORK}/edit_update.json" "$EDIT_ID" 0.5 fail_open true "$CRITERION"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/edit_update.json")
assert_ok "$code" "合法编辑（threshold 0.95 → 0.5）"
code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${EDIT_ID}")
assert_ok "$code" "合法编辑后回读规则"
assert_eq "$(rule_threshold)" "0.5" "新 body 已持久化（threshold=0.5）"

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${EDIT_ID}/revisions")
assert_ok "$code" "GET revisions"
REVISIONS=$(python3 - "$BODY" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
except Exception:
    data = []
print(len(data))
PY
)
if [ "$REVISIONS" -ge 2 ]; then
  ok "revisions 记录了编辑版本（${REVISIONS} 条）"
else
  bad "revisions 未见编辑版本（${REVISIONS} 条）"
fi

python3 - "${WORK}/edit_simulate.json" "${WORK}/edit_update.json" <<'PY'
import json, sys
rule = json.load(open(sys.argv[2], encoding="utf-8"))
rule["rollout_state"] = "dry_run"
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "请推荐一只稳赚不赔的基金"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/edit_simulate.json")
assert_ok "$code" "POST simulate（编辑后 draft）"
assert_eq "$(jget '.data.summary.hit')" "True" "编辑后 simulate 仍命中（0.9>=0.5）"
assert_eq "$(jget '.data.steps.2.detail.threshold')" "0.5" "simulate 反映编辑后阈值"

# ============================================================
# [13] 内置 category（seeded full）走运营台模拟：不调用 judge backend
# ============================================================
step "[13] 内置 category 规则页面模拟（seeded prompt-pii）"

BUILTIN_ID="prompt-pii"
code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${BUILTIN_ID}")
assert_ok "$code" "GET 内置规则 ${BUILTIN_ID}"
if [ "$(jget '.data.runtime')" = "prompt" ] && [ "$(jget '.data.rollout_state')" = "full" ]; then
  ok "内置规则为 runtime=prompt 且 full"
else
  bad "内置规则元信息异常：runtime=$(jget '.data.runtime') state=$(jget '.data.rollout_state')"
fi
python3 - "${WORK}/builtin_simulate.json" "$BODY" <<'PY'
import json, sys
data = json.load(open(sys.argv[2], encoding="utf-8"))["data"]
body = data.get("body")
if isinstance(body, str):
    try:
        inner = json.loads(body)
        if isinstance(inner, str):
            body = inner
    except Exception:
        pass
rule = {
    "rule_id": data.get("rule_id"),
    "bundle_id": data.get("bundle_id") or "demo-default",
    "layer": data.get("layer"),
    "runtime": data.get("runtime"),
    "reason_code": data.get("reason_code"),
    "risk_score": data.get("risk_score"),
    "intent_action": data.get("intent_action"),
    "scope": data.get("scope") or {"bind_scope": "global"},
    "body": body,
    "rollout_state": data.get("rollout_state"),
    "is_async": bool(data.get("is_async")),
}
payload = {
    "rule": rule,
    "fixture": {"route_uri": "/v1/chat/completions", "content": "今天天气不错，适合出去散步"},
    "options": {"cumulative_source": "mock"},
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
BEFORE_JUDGE=$(stub_calls judge)
BEFORE_CATEGORY=$(stub_calls category)
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules/simulate" "${WORK}/builtin_simulate.json")
AFTER_JUDGE=$(stub_calls judge)
AFTER_CATEGORY=$(stub_calls category)
assert_ok "$code" "POST simulate（内置 category）"
assert_eq "$(jget '.data.steps.2.detail.mode')" "category" "内置规则 simulate mode=category"
assert_eq "$(jget '.data.summary.hit')" "False" "stub hit_rule=false → 未命中"
if [ "$AFTER_JUDGE" = "$BEFORE_JUDGE" ] && [ "$AFTER_CATEGORY" -gt "$BEFORE_CATEGORY" ]; then
  ok "内置 category 走 LLM 未误走 judge（judge ${BEFORE_JUDGE}→${AFTER_JUDGE}, LLM ${BEFORE_CATEGORY}→${AFTER_CATEGORY}）"
else
  bad "内置 category 调面异常：judge ${BEFORE_JUDGE}→${AFTER_JUDGE}, LLM ${BEFORE_CATEGORY}→${AFTER_CATEGORY}"
fi

# ============================================================
# [14] 异步 judge 规则：async 分支按 judge 分类且不影响当次判定
# ============================================================
step "[14] 异步 judge 规则（is_async=true）"

python3 - "${WORK}/async_create.json" "$STUBP" <<'PY'
import json, sys
out, stub_port = sys.argv[1], sys.argv[2]
body = {
    "version": 1,
    "mode": "judge",
    "criterion": "命中时触发异步 webhook 通知",
    "examples": {"hit": ["请推荐一只基金"]},
    "threshold": 0.5,
    "on_error": "fail_open",
    "enforcement_ready": False,
}
payload = {
    "rule_id": "e2e-judge-async",
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE_ASYNC",
    "risk_score": 90,
    "intent_action": "allow",
    "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False),
    "is_async": True,
    "async_action_config": json.dumps(
        {"type": "webhook", "url": "http://127.0.0.1:%s/hook" % stub_port,
         "body": {"rule_id": "{{rule_id}}"}}, ensure_ascii=False),
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_create.json")
assert_ok "$code" "创建 async judge 规则"
publish_rule "e2e-judge-async"
BEFORE=$(stub_calls judge)
BEFORE_HOOK_METRIC=$(prom_metric virbius_async_action_total 'type=webhook,result=success')
wait_eval "async judge 命中不改当次判定（allow）" "请推荐一只稳赚不赔的基金" effective_action allow || true
AFTER=$(stub_calls judge)
if [ "$AFTER" -gt "$BEFORE" ]; then
  ok "async 分支仍调用 judge backend（${BEFORE}→${AFTER}）"
else
  bad "async 分支未调用 judge backend"
fi

# webhook 必须真实外呼：stub /hook 收到 POST，body 含渲染后的 rule_id
HOOK_BODY=""
for _ in $(seq 1 40); do
  HOOK_BODY=$(stub_hook_body "e2e-judge-async")
  [ -n "$HOOK_BODY" ] && break
  sleep 0.25
done
if [ -n "$HOOK_BODY" ]; then
  ok "async webhook 真实外呼且占位符已渲染: ${HOOK_BODY}"
else
  bad "async webhook 未外呼（stub /hook 无记录）"
fi
WANT_HOOK_METRIC=$(python3 - "${BEFORE_HOOK_METRIC:-0}" <<'PY'
import sys
print(float(sys.argv[1] or 0) + 1)
PY
)
wait_prom_at_least "webhook 投递成功计数 +1" \
  virbius_async_action_total 'type=webhook,result=success' "$WANT_HOOK_METRIC"
disable_rule "e2e-judge-async"

# 停用即停投（规则生命周期 = 投递开关）
BEFORE_HOOKS=$(stub_calls hook)
wait_eval "停用后 evaluate 正常返回" "请推荐一只稳赚不赔的基金" effective_action allow || true
sleep 1
AFTER_HOOKS=$(stub_calls hook)
if [ "$AFTER_HOOKS" = "$BEFORE_HOOKS" ]; then
  ok "停用后无新增 webhook 投递（${BEFORE_HOOKS}→${AFTER_HOOKS}）"
else
  bad "停用规则仍产生 webhook 投递（${BEFORE_HOOKS}→${AFTER_HOOKS}）"
fi

# ------------------------------------------------------------
# [14b] webhook 白名单：引擎层拒绝不在 allowed-hosts 内的目标
#       （engine env: VIRBIUS_ASYNC_WEBHOOK_ALLOWED_HOSTS=127.0.0.1:${STUBP}）
# ------------------------------------------------------------
step "[14b] webhook 白名单拒绝（不外呼，仅计 skipped）"

python3 - "${WORK}/async_deny_create.json" <<'PY'
import json, sys
out = sys.argv[1]
body = {
    "version": 1, "mode": "judge", "criterion": "命中时投递（白名单外目标）",
    "examples": {"hit": ["请推荐一只基金"]},
    "threshold": 0.5, "on_error": "fail_open", "enforcement_ready": False,
}
payload = {
    "rule_id": "e2e-judge-async-deny",
    "bundle_id": "poc-default", "layer": "cloud", "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE_ASYNC_DENY", "risk_score": 90,
    "intent_action": "allow", "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False),
    "is_async": True,
    # 同 host 不同端口 → 不在 host:port 白名单内，不允许真实外呼
    "async_action_config": json.dumps(
        {"type": "webhook", "url": "http://127.0.0.1:1/hook",
         "body": {"rule_id": "{{rule_id}}"}}, ensure_ascii=False),
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_deny_create.json")
assert_ok "$code" "创建白名单外 webhook 规则（创建接口不拦，引擎投递层拦）"
publish_rule "e2e-judge-async-deny"
BEFORE_HOOKS=$(stub_calls hook)
BEFORE_SKIP=$(prom_metric virbius_async_action_total 'type=webhook,result=skipped')
wait_eval "白名单拒绝不影响当次判定（allow）" "请推荐一只稳赚不赔的基金" effective_action allow || true
WANT_SKIP=$(python3 - "${BEFORE_SKIP:-0}" <<'PY'
import sys
print(float(sys.argv[1] or 0) + 1)
PY
)
wait_prom_at_least "webhook skipped 计数 +1（host:port 不匹配）" \
  virbius_async_action_total 'type=webhook,result=skipped' "$WANT_SKIP"
sleep 1
AFTER_HOOKS=$(stub_calls hook)
if [ "$AFTER_HOOKS" = "$BEFORE_HOOKS" ]; then
  ok "白名单拒绝未外呼（hook ${BEFORE_HOOKS}→${AFTER_HOOKS}）"
else
  bad "白名单拒绝仍外呼了（hook ${BEFORE_HOOKS}→${AFTER_HOOKS}）"
fi
disable_rule "e2e-judge-async-deny"

# ------------------------------------------------------------
# [14c] 按字段截断：vars 256→field-max-chars(64)、content_preview→512；
#       per-field 截断且渲染后 JSON 仍合法
#       （engine env: VIRBIUS_ASYNC_FIELD_MAX_CHARS=64）
# ------------------------------------------------------------
step "[14c] 按字段截断（vars 256→64、content_preview→512，JSON 不损坏）"

python3 - "${WORK}/async_trunc_create.json" "$STUBP" <<'PY'
import json, sys
out, stub_port = sys.argv[1], sys.argv[2]
body = {
    "version": 1, "mode": "judge", "criterion": "命中时投递（截断验证）",
    "examples": {"hit": ["请推荐一只基金"]},
    "threshold": 0.5, "on_error": "fail_open", "enforcement_ready": False,
}
payload = {
    "rule_id": "e2e-judge-async-trunc",
    "bundle_id": "poc-default", "layer": "cloud", "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE_ASYNC_TRUNC", "risk_score": 90,
    "intent_action": "allow", "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False),
    "is_async": True,
    "async_action_config": json.dumps(
        {"type": "webhook", "url": "http://127.0.0.1:%s/hook" % stub_port,
         "body": {"rule_id": "{{rule_id}}", "note": "{{vars.long_tok}}",
                  "preview": "{{content_preview}}"}}, ensure_ascii=False),
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_trunc_create.json")
assert_ok "$code" "创建截断验证 webhook 规则"
publish_rule "e2e-judge-async-trunc"

BEFORE_TRUNC_METRIC=$(prom_metric virbius_async_action_total 'type=webhook,result=success')
WANT_TRUNC_METRIC=$(python3 - "${BEFORE_TRUNC_METRIC:-0}" <<'PY'
import sys
print(float(sys.argv[1] or 0) + 1)
PY
)

# content: 前 12 个汉字 + 3000 个'A'（触发 content_preview 截断）；vars.long_tok: 256 个'B'
TRUNC_BODY=""
TRUNC_EVAL_CODE="000"
for _ in $(seq 1 40); do
  TRUNC_EVAL_CODE=$(curl -sS --noproxy '*' --max-time 30 -o "${WORK}/eval-response.json" \
    -w '%{http_code}' -X POST "${ENGINE}/v1/evaluate" \
    -H 'Content-Type: application/json' -d "$(python3 - "${TENANT}" <<'PY'
import json, sys, time, os
now = time.time_ns()
content = "请推荐一只稳赚不赔的基金 " + "A" * 3000
print(json.dumps({
    "tenant_id": sys.argv[1],
    "session_id": "e2e-trunc-%s-%s" % (os.getpid(), now),
    "trace_id": "e2e-trunc-trace-%s" % now,
    "role": "input",
    "content": content,
    "vars": {"long_tok": "B" * 256},
}, ensure_ascii=False))
PY
)")
  TRUNC_BODY=$(stub_hook_body "e2e-judge-async-trunc")
  [ -n "$TRUNC_BODY" ] && break
  sleep 0.25
done
assert_eq "$TRUNC_EVAL_CODE" "200" "截断规则 evaluate HTTP 200"
wait_prom_at_least "截断规则投递成功计数 +1" \
  virbius_async_action_total 'type=webhook,result=success' "$WANT_TRUNC_METRIC"

if [ -n "$TRUNC_BODY" ]; then
  TRUNC_RESULT=$(
    python3 - "$TRUNC_BODY" <<'PY'
import json, sys
try:
    body = json.loads(sys.argv[1])
except Exception:
    print("BAD_JSON")
    raise SystemExit
note = body.get("note", "")
preview = body.get("preview", "")
checks = [
    note == "B" * 64,                          # vars 占位符 256 → field-max 64
    preview.startswith("请推荐一只稳赚不赔的基金"),
    len(preview) == 512,                       # content_preview → 512
    body.get("rule_id") == "e2e-judge-async-trunc",
]
print("OK" if all(checks) else "MISMATCH note_len=%d preview_len=%d" % (len(note), len(preview)))
PY
  )
else
  TRUNC_RESULT="NO_HOOK"
fi
if [ "$TRUNC_RESULT" = "OK" ]; then
  ok "字段按上限截断且渲染后 JSON 合法（note=64B, preview=512chars）"
else
  bad "截断断言未全过: ${TRUNC_RESULT}"
fi
disable_rule "e2e-judge-async-trunc"

# ============================================================
# [15] 规则列表可见性：新建 judge 规则出现在 layer=cloud 列表
# ============================================================
step "[15] 规则列表可见性"

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules?layer=cloud")
assert_ok "$code" "GET rules?layer=cloud"
python3 - "$BODY" <<'PY' > "${WORK}/listed.txt"
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
except Exception:
    data = []
print(",".join(str(r.get("rule_id", "")) for r in data))
PY
LISTED=$(cat "${WORK}/listed.txt")
if [[ "$LISTED" == *e2e-judge-fund* && "$LISTED" == *e2e-judge-edit* \
  && "$LISTED" == *e2e-judge-async* && "$LISTED" == *e2e-judge-async-deny* \
  && "$LISTED" == *e2e-judge-async-trunc* ]]; then
  ok "新建 judge/异步规则均在列表中"
else
  bad "列表缺失新建规则: ${LISTED}"
fi

# ============================================================
# [16] 异步 Kafka 动作：at-most-once（broker 不可达 → failure 指标 + 不影响判定）
# ============================================================
step "[16] 异步 Kafka 动作（broker 不可达 → failure 可观测）"

KAFKA_ASYNC_ID="e2e-judge-async-kafka"
python3 - "${WORK}/async_kafka_create.json" <<'PY'
import json, sys
body = {
    "version": 1,
    "mode": "judge",
    "criterion": "命中时投递 Kafka 动作",
    "examples": {"hit": ["请推荐一只基金"]},
    "threshold": 0.5,
    "on_error": "fail_open",
    "enforcement_ready": False,
}
payload = {
    "rule_id": "e2e-judge-async-kafka",
    "bundle_id": "poc-default",
    "layer": "cloud",
    "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE_ASYNC_KAFKA",
    "risk_score": 90,
    "intent_action": "allow",
    "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False),
    "is_async": True,
    "async_action_config": json.dumps(
        {"type": "kafka", "topic": "virbius-rule-actions",
         "message": {"rule_id": "{{rule_id}}", "session_id": "{{session_id}}"}},
        ensure_ascii=False),
}
json.dump(payload, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_kafka_create.json")
assert_ok "$code" "创建 async kafka judge 规则"
assert_eq "$(jget '.data.rule_id')" "$KAFKA_ASYNC_ID" "kafka async rule_id"
publish_rule "$KAFKA_ASYNC_ID"
BEFORE_FAIL=$(prom_metric virbius_async_action_total 'type=kafka,result=failure')
wait_eval "kafka 动作失败不影响当次判定（allow）" "请推荐一只稳赚不赔的基金" effective_action allow || true
WANT_FAIL=$(python3 - "${BEFORE_FAIL:-0}" <<'PY'
import sys
print(float(sys.argv[1] or 0) + 1)
PY
)
wait_prom_at_least "kafka 投递失败计数 +1（at-most-once, broker 不可达）" \
  virbius_async_action_total 'type=kafka,result=failure' "$WANT_FAIL"
# 失败后引擎必须仍然健康（投递永不抛出、不影响判定面）
code=$(curl -sS --noproxy '*' --max-time 5 -o /dev/null -w '%{http_code}' "${ENGINE}/admin/health")
assert_eq "$code" "200" "kafka 失败后 engine 仍健康"
disable_rule "$KAFKA_ASYNC_ID"

# ============================================================
# [17] 异步动作配置校验：非法 type/url/topic 必须被创建接口拒绝
# ============================================================
step "[17] async_action_config 校验拒绝矩阵"

# 通用 payload 构造（cfg 允许任何字符串；合法/非法由控制台接口裁断）
make_async_cfg() {
  local out="$1" rule_id="$2" cfg="$3" reason="${4:-E2E_ASYNC_INVALID}"
  python3 - "$out" "$rule_id" "$cfg" "$reason" <<'PY'
import json, sys
body = {
    "version": 1, "mode": "judge", "criterion": "异步动作编辑流程",
    "threshold": 0.5, "on_error": "fail_open", "enforcement_ready": False,
}
payload = {
    "rule_id": sys.argv[2], "bundle_id": "poc-default", "layer": "cloud",
    "runtime": "prompt", "reason_code": sys.argv[4], "risk_score": 90,
    "intent_action": "allow", "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False), "is_async": True,
    "async_action_config": sys.argv[3],
}
json.dump(payload, open(sys.argv[1], "w"), ensure_ascii=False)
PY
}

# 创建接口拒绝矩阵（同一个易触发的 body 会干扰下面 [17b] 创建，先跑坏用例）
make_async_cfg "${WORK}/bad_async_redis.json" "e2e-async-bad-redis" \
  '{"type":"redis_stream","stream_key":"virbius:rule:hit:actions"}'
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/bad_async_redis.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "创建接口接受了已移除的 redis_stream 异步动作"
else
  ok "创建接口拒绝 redis_stream（HTTP ${code}）"
fi

make_async_cfg "${WORK}/bad_async_topic.json" "e2e-async-bad-topic" \
  '{"type":"kafka","topic":"bad topic!"}'
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/bad_async_topic.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "创建接口接受了非法 kafka topic"
else
  ok "创建接口拒绝非法 kafka topic（HTTP ${code}）"
fi

make_async_cfg "${WORK}/bad_async_url.json" "e2e-async-bad-url" \
  '{"type":"webhook","url":"ftp://example.com/x"}'
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/bad_async_url.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "创建接口接受了非 http(s) webhook url"
else
  ok "创建接口拒绝非 http(s) webhook url（HTTP ${code}）"
fi

# ------------------------------------------------------------
# [17b] 编辑路径（draft update）：非法配置拒绝、旧配置未变、合法更新生效
# ------------------------------------------------------------
step "[17b] 草稿规则异步配置编辑（upsert 更新路径）"

EDIT_ASYNC_ID="e2e-judge-async-edit"
make_async_cfg "${WORK}/async_edit_create.json" "$EDIT_ASYNC_ID" \
  '{"type":"webhook","url":"http://127.0.0.1:1/hook","body":{"rule_id":"{{rule_id}}"}}' \
  "E2E_PROMPT_JUDGE_ASYNC_EDIT"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_edit_create.json")
assert_ok "$code" "创建合法异步配置草稿 $EDIT_ASYNC_ID"
assert_eq "$(jget '.data.rollout_state')" "draft" "编辑规则初始为 draft"

# 1) 草稿编辑为 redis_stream → 拒绝
make_async_cfg "${WORK}/async_edit_redis.json" "$EDIT_ASYNC_ID" \
  '{"type":"redis_stream","stream_key":"virbius:rule:hit:actions"}' \
  "E2E_PROMPT_JUDGE_ASYNC_EDIT"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_edit_redis.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "草稿编辑接受了已移除的 redis_stream"
else
  ok "草稿编辑拒绝 redis_stream（HTTP ${code}）"
fi

# 2) 草稿编辑为非法 topic → 拒绝
make_async_cfg "${WORK}/async_edit_topic.json" "$EDIT_ASYNC_ID" \
  '{"type":"kafka","topic":"bad topic!"}' \
  "E2E_PROMPT_JUDGE_ASYNC_EDIT"
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_edit_topic.json")
if [[ "$code" == 2* ]] && [ "$(jget '.code')" = "0" ]; then
  bad "草稿编辑接受了非法 kafka topic"
else
  ok "草稿编辑拒绝非法 kafka topic（HTTP ${code}）"
fi

# 3) 两次拒绝后旧配置必须完好（原子性）
rule_async_cfg() {
  python3 - "$BODY" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
    cfg = data.get("async_action_config") or ""
    try:
        parsed = json.loads(cfg)
    except Exception:
        parsed = {}
    print("%s|%s" % (parsed.get("type", ""), parsed.get("url", parsed.get("topic", ""))))
except Exception:
    print("|")
PY
}
code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${EDIT_ASYNC_ID}")
assert_ok "$code" "拒绝后回读 $EDIT_ASYNC_ID"
assert_eq "$(rule_async_cfg)" "webhook|http://127.0.0.1:1/hook" "拒绝编辑未变更旧配置"

# 4) 草稿编辑为合法 webhook（指向 stub）→ 生效
python3 - "${WORK}/async_edit_ok.json" "$STUBP" <<'PY'
import json, sys
out, stub_port = sys.argv[1], sys.argv[2]
body = {
    "version": 1, "mode": "judge", "criterion": "异步动作编辑流程",
    "threshold": 0.5, "on_error": "fail_open", "enforcement_ready": False,
}
payload = {
    "rule_id": "e2e-judge-async-edit",
    "bundle_id": "poc-default", "layer": "cloud", "runtime": "prompt",
    "reason_code": "E2E_PROMPT_JUDGE_ASYNC_EDIT", "risk_score": 90,
    "intent_action": "allow", "scope": {"bind_scope": "global"},
    "body": json.dumps(body, ensure_ascii=False), "is_async": True,
    "async_action_config": json.dumps(
        {"type": "webhook", "url": "http://127.0.0.1:%s/hook" % stub_port,
         "body": {"rule_id": "{{rule_id}}", "phase": "edited"}}, ensure_ascii=False),
}
json.dump(payload, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
code=$(api POST "/api/v1/admin/tenants/${TENANT}/rules" "${WORK}/async_edit_ok.json")
assert_ok "$code" "草稿编辑为合法 webhook 配置"
code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${EDIT_ASYNC_ID}")
assert_ok "$code" "编辑成功后回读"
assert_eq "$(rule_async_cfg)" "webhook|http://127.0.0.1:${STUBP}/hook" "合法编辑已持久化"

# 5) 编辑后的配置必须真实生效：上线后命中 → stub 收到携 'phase:"edited"' 的投递
publish_rule "$EDIT_ASYNC_ID"
BEFORE_EDIT_METRIC=$(prom_metric virbius_async_action_total 'type=webhook,result=success')
wait_eval "编辑规则命中不改当次判定（allow）" "请推荐一只稳赚不赔的基金" effective_action allow || true
WANT_EDIT_METRIC=$(python3 - "${BEFORE_EDIT_METRIC:-0}" <<'PY'
import sys
print(float(sys.argv[1] or 0) + 1)
PY
)
wait_prom_at_least "编辑后的投递成功计数 +1" \
  virbius_async_action_total 'type=webhook,result=success' "$WANT_EDIT_METRIC"
sleep 0.5
if [[ -n "$(stub_hook_body "e2e-judge-async-edit")" ]]; then
  EDIT_HOOK_OK=$(python3 - "$(stub_hook_body "e2e-judge-async-edit")" <<'PY'
import json, sys
try:
    parsed = json.loads(sys.argv[1])
except Exception:
    raise SystemExit
print("ok" if parsed.get("phase") == "edited" else "wrong_phase")
PY
)
  if [ "$EDIT_HOOK_OK" = "ok" ]; then
    ok "编辑后的模板已生效（phase=edited 真实外呼）"
  else
    bad "投递内容与编辑后模板不符: ${EDIT_HOOK_OK}"
  fi
else
  bad "编辑规则未投递（stub /hook 无 e2e-judge-async-edit）"
fi
disable_rule "$EDIT_ASYNC_ID"

# ============================================================
# [18] async_action_config 回读：类型与 topic/url 原样保留
# ============================================================
step "[18] async_action_config 回读"

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/${KAFKA_ASYNC_ID}")
assert_ok "$code" "GET ${KAFKA_ASYNC_ID}"
CFG=$(
  python3 - "$BODY" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
cfg = data.get("async_action_config") or ""
try:
    parsed = json.loads(cfg)
except Exception:
    parsed = {}
print("%s|%s|%s" % (parsed.get("type", ""), parsed.get("topic", ""), data.get("is_async")))
PY
)
if [ "$CFG" = "kafka|virbius-rule-actions|True" ]; then
  ok "async_action_config 回读=type/topic/is_async 完整"
else
  bad "async_action_config 回读异常: ${CFG}"
fi

code=$(api GET "/api/v1/admin/tenants/${TENANT}/rules/e2e-judge-async")
assert_ok "$code" "GET e2e-judge-async"
WEBHOOK_CFG=$(
  python3 - "$BODY" "$STUBP" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))["data"]
cfg = data.get("async_action_config") or ""
try:
    parsed = json.loads(cfg)
except Exception:
    parsed = {}
print("%s|%s" % (parsed.get("type", ""), parsed.get("url", "")))
PY
)
if [ "$WEBHOOK_CFG" = "webhook|http://127.0.0.1:${STUBP}/hook" ]; then
  ok "webhook 配置回读=type/url 完整"
else
  bad "webhook 配置回读异常: ${WEBHOOK_CFG}"
fi

# ============================================================
# Summary
# ============================================================
echo ""
echo "=========================================="
echo "PASS: ${PASS}  FAIL: ${FAIL}  GAP: ${GAP}  SKIP: ${SKIP}"
echo "=========================================="
[ "$FAIL" -eq 0 ] || exit 1
exit 0
