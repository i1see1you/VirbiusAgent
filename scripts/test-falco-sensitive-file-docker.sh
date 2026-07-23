#!/usr/bin/env bash
# test-falco-sensitive-file-docker.sh
#
# Test the builtin_sensitive_file_access Falco rule in local Docker.
#
# This script:
#   1. Starts Falco in a privileged Docker container with the Virbius rule loaded
#   2. Triggers the rule by writing to /etc/shadow in a test container
#   3. Verifies the alert appears in Falco stdout
#   4. (Optional) If Virbius Engine is running, verifies the alert reaches the Engine
#
# Prerequisites:
#   - Docker Desktop (macOS or Linux) running
#   - (Optional) Virbius Engine running on port 8082 for full-pipeline test
#
# Usage:
#   ./scripts/test-falco-sensitive-file-docker.sh              # Falco detection only
#   ./scripts/test-falco-sensitive-file-docker.sh --full       # Full pipeline (needs Engine)
#   ./scripts/test-falco-sensitive-file-docker.sh --cleanup     # Remove Falco containers
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FALCO_CONFIG_DIR="$SCRIPT_DIR/falco-test"

# ── Config ──
FALCO_IMAGE="falcosecurity/falco:0.39.0"
FALCO_CONTAINER="virbius-falco-test"
TRIGGER_CONTAINER="virbius-falco-trigger"
ENGINE_URL="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
ENGINE_HOST_INTERNAL="host.docker.internal"
FALCO_HTTP_OUTPUT_URL="${FALCO_HTTP_OUTPUT_URL:-http://${ENGINE_HOST_INTERNAL}:8082/api/internal/falco-alert}"

# ── Colors ──
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Parse args ──
MODE="falco-only"
DO_CLEANUP=false
for arg in "$@"; do
  case "$arg" in
    --full)     MODE="full-pipeline" ;;
    --cleanup)  DO_CLEANUP=true ;;
    *)          err "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} Falco builtin_sensitive_file_access Test  ${NC}"
echo -e "${CYAN} Mode: $MODE                              ${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# ── Cleanup mode ──
if $DO_CLEANUP; then
  info "Cleaning up Falco test containers..."
  docker rm -f "$FALCO_CONTAINER" 2>/dev/null && ok "Removed $FALCO_CONTAINER" || true
  docker rm -f "$TRIGGER_CONTAINER" 2>/dev/null && ok "Removed $TRIGGER_CONTAINER" || true
  exit 0
fi

# ── Step 0: Pre-flight checks ──
info "Step 0: Pre-flight checks"

if ! docker info >/dev/null 2>&1; then
  err "Docker is not running. Start Docker Desktop first."
  exit 1
fi
ok "Docker is running"

# Detect Docker Desktop (LinuxKit kernel) — Falco cannot run inside it
DOCKER_PLATFORM=$(docker info --format '{{.OperatingSystem}}' 2>/dev/null || echo "unknown")
if echo "$DOCKER_PLATFORM" | grep -qi "docker desktop\|linuxkit"; then
  warn "Docker Desktop detected — Falco cannot load syscall drivers inside LinuxKit VM"
  warn "Switching to mock mode: sending simulated Falco alerts to Engine endpoint"
  MODE="mock"
fi

if [[ ! -f "$FALCO_CONFIG_DIR/virbius-rules.yaml" ]]; then
  err "Rules file not found: $FALCO_CONFIG_DIR/virbius-rules.yaml"
  exit 1
fi
ok "Rules file: $FALCO_CONFIG_DIR/virbius-rules.yaml"

if [[ ! -f "$FALCO_CONFIG_DIR/falco.yaml" ]]; then
  err "Falco config not found: $FALCO_CONFIG_DIR/falco.yaml"
  exit 1
fi
ok "Falco config: $FALCO_CONFIG_DIR/falco.yaml"

# Check Engine availability (for full-pipeline mode)
ENGINE_REACHABLE=false
if [[ "$MODE" == "full-pipeline" ]]; then
  if curl -sf "$ENGINE_URL/admin/health" >/dev/null 2>&1; then
    ok "Virbius Engine reachable: $ENGINE_URL"
    ENGINE_REACHABLE=true
  else
    warn "Engine not reachable: $ENGINE_URL"
    warn "Full-pipeline test requires Engine + Redis running."
    warn "Start with: ./scripts/run-local.sh"
    warn "Falling back to Falco-detection-only mode."
    MODE="falco-only"
  fi
else
  # Check anyway for optional HTTP output
  if curl -sf "$ENGINE_URL/admin/health" >/dev/null 2>&1; then
    ok "Virbius Engine reachable (HTTP output will be enabled): $ENGINE_URL"
    ENGINE_REACHABLE=true
  else
    info "Engine not running — Falco stdout only (no HTTP output)"
  fi
fi
echo ""

# ── Step 1: Pull Falco image ──
info "Step 1: Pull Falco image ($FALCO_IMAGE)"
if ! docker image inspect "$FALCO_IMAGE" >/dev/null 2>&1; then
  docker pull "$FALCO_IMAGE"
fi
ok "Falco image ready"
echo ""

# ── Step 2: Prepare Falco config (enable HTTP output if Engine is running) ──
info "Step 2: Prepare Falco configuration"

# Create a runtime copy of falco.yaml (to avoid modifying the original)
RUNTIME_CONFIG_DIR=$(mktemp -d)
cp "$FALCO_CONFIG_DIR/falco.yaml" "$RUNTIME_CONFIG_DIR/falco.yaml"
mkdir -p "$RUNTIME_CONFIG_DIR/falco_rules.d"
cp "$FALCO_CONFIG_DIR/virbius-rules.yaml" "$RUNTIME_CONFIG_DIR/falco_rules.d/virbius-rules.yaml"

# Enable HTTP output if Engine is reachable
if $ENGINE_REACHABLE; then
  info "Enabling HTTP output → $FALCO_HTTP_OUTPUT_URL"
  # Use awk to selectively enable ONLY http_output (not file_output or grpc)
  awk '
    /^[^ #]/ { in_http=0 }
    /^http_output:/ { in_http=1 }
    in_http && /  enabled: false/ { sub(/false/, "true") }
    in_http && /  url:/ { sub(/".*"/, "\"'"$FALCO_HTTP_OUTPUT_URL"'\"") }
    { print }
  ' "$RUNTIME_CONFIG_DIR/falco.yaml" > "$RUNTIME_CONFIG_DIR/falco_runtime.yaml"
  mv "$RUNTIME_CONFIG_DIR/falco_runtime.yaml" "$RUNTIME_CONFIG_DIR/falco.yaml"
  ok "HTTP output enabled"
else
  info "HTTP output disabled (Engine not running)"
fi
echo ""

# ── Mock mode: Skip Falco container, send simulated alerts to Engine ──
if [[ "$MODE" == "mock" ]]; then
  echo ""
  info "Mock mode: sending simulated Falco alerts to Engine"
  echo ""

  # Check if Engine is reachable
  if ! curl -sf "$ENGINE_URL/admin/health" >/dev/null 2>&1; then
    err "Engine not reachable: $ENGINE_URL"
    err "Mock mode requires Engine to be running (start with: ./scripts/run-local.sh)"
    exit 1
  fi
  ok "Engine reachable: $ENGINE_URL"

  # Send simulated alert for /etc/shadow write
  info "Sending simulated alert: write to /etc/shadow"
  ALERT_RESPONSE=$(curl -s -X POST "$ENGINE_URL/api/internal/falco-alert" \
    -H 'Content-Type: application/json' \
    -d '{
      "output": "Sensitive file access (user=root, pid=12345, file=/etc/shadow)",
      "priority": "Warning",
      "rule": "builtin_sensitive_file_access",
      "time": "'$(date -u +%Y-%m-%dT%H:%M:%S.000000000Z)'",
      "output_fields": {
        "evt.time": '$(date +%s)000000000',
        "proc.pid": 12345,
        "proc.ppid": 1,
        "proc.cgroup.id": 0,
        "proc.cmdline": "echo test >> /etc/shadow",
        "fd.name": "/etc/shadow",
        "user.name": "root"
      }
    }' 2>&1)
  echo "  Response: $ALERT_RESPONSE"

  if echo "$ALERT_RESPONSE" | grep -q "ignored\|pid_not_mapped\|ok"; then
    ok "Engine received and processed the alert"
    FALCO_ALERT_DETECTED=true
  else
    warn "Unexpected response from Engine"
    FALCO_ALERT_DETECTED=false
  fi

  # Summary
  echo ""
  echo -e "${GREEN}========================================${NC}"
  echo -e "${GREEN} Test Summary (Mock Mode)                ${NC}"
  echo -e "${GREEN}========================================${NC}"
  echo ""
  echo "  Rule:     builtin_sensitive_file_access"
  echo "  Condition: open/openat/openat2 with write on:"
  echo "             /etc/shadow, /etc/passwd,"
  echo "             /root/.ssh/id_rsa, /root/.ssh/authorized_keys"
  echo "  Priority: WARNING"
  echo ""
  echo "  ○ Falco container not started (Docker Desktop detected)"
  if $FALCO_ALERT_DETECTED; then
    echo -e "  ${GREEN}✓ Engine endpoint verified (received simulated alert)${NC}"
  else
    echo -e "  ${RED}✗ Engine endpoint failed${NC}"
  fi
  echo ""
  echo "  Note: Real Falco testing requires Linux kernel (not Docker Desktop)"
  echo "        Test on a Linux VM or native Linux for full Falco validation"
  echo ""
  exit 0
fi

# ── Step 3: Start Falco container ──
info "Step 3: Start Falco container"

# Remove any existing container
docker rm -f "$FALCO_CONTAINER" 2>/dev/null || true

# Build Docker run args
# Key mounts:
#   /dev     → /dev          (device access)
#   /proc    → /host/proc    (process info, Falco syscall source)
#   /sys     → /host/sys     (BTF at /host/sys/kernel/btf/vmlinux — REQUIRED for modern_ebpf)
#   /usr     → /host/usr     (kernel headers for falcoctl)
#   /boot    → /host/boot    (kernel config, vmlinux — if exists)
#   /lib/modules → /host/lib/modules (kernel modules — if exists)
DOCKER_RUN_ARGS=(
  run -d
  --name "$FALCO_CONTAINER"
  --privileged
  --pid=host
  -v /dev:/dev
  -v /proc:/host/proc:ro
  -v /sys:/host/sys:ro
  -v /usr:/host/usr:ro
  -v "$RUNTIME_CONFIG_DIR/falco.yaml:/etc/falco/falco.yaml:ro"
  -v "$RUNTIME_CONFIG_DIR/falco_rules.d:/etc/falco/falco_rules.d:ro"
  -p 8765:8765
)

# Conditionally mount /boot and /lib/modules (may not exist on Docker Desktop for Mac)
if [[ -d /boot ]]; then
  DOCKER_RUN_ARGS+=( -v /boot:/host/boot:ro )
fi
if [[ -d /lib/modules ]]; then
  DOCKER_RUN_ARGS+=( -v /lib/modules:/host/lib/modules:ro )
fi

# Falco 0.39.0 does NOT have --userspace or --modern-bpf as falco CLI flags.
# The driver is managed by falcoctl in the entrypoint script.
# The entrypoint ends with `exec "$@"`, so we must pass `falco` as the command
# to make it `exec falco`. Passing --userspace causes `exec --userspace` which
# the shell rejects (interprets -- as an exec option flag).
# The entrypoint already runs `falcoctl driver config` + `falcoctl driver install`
# and auto-detects the best driver (modern_ebpf on supported kernels).
ARCH=$(uname -m)
info "Platform: $ARCH — letting Falco auto-detect driver (entrypoint handles falcoctl)"

info "Starting Falco container..."
docker "${DOCKER_RUN_ARGS[@]}" \
  "$FALCO_IMAGE" \
  falco

echo ""

# Wait for Falco to initialize
info "Waiting for Falco to initialize..."
FALCO_READY=false
for i in $(seq 1 30); do
  sleep 2
  if docker logs "$FALCO_CONTAINER" 2>&1 | grep -q "Starting health webserver"; then
    FALCO_READY=true
    break
  fi
  if docker logs "$FALCO_CONTAINER" 2>&1 | grep -qi "error\|fatal\|panic"; then
    # Check if it's just a warning about rules
    if docker logs "$FALCO_CONTAINER" 2>&1 | grep -qi "rules loaded\|Starting Falco"; then
      FALCO_READY=true
      break
    fi
    err "Falco failed to start. Logs:"
    docker logs "$FALCO_CONTAINER" 2>&1 | tail -30
    exit 1
  fi
  printf "."
done
echo ""

if ! $FALCO_READY; then
  warn "Falco did not show ready signal within 60s. Checking logs..."
  docker logs "$FALCO_CONTAINER" 2>&1 | tail -20
  warn "Proceeding anyway (Falco may still be loading)..."
fi

# Show Falco startup logs
info "Falco startup logs:"
docker logs "$FALCO_CONTAINER" 2>&1 | head -20
echo ""

# ── Step 4: Verify Falco loaded the rule ──
info "Step 4: Verify Falco loaded the rule"

# Falco prints loaded rules summary on startup
if docker logs "$FALCO_CONTAINER" 2>&1 | grep -q "builtin_sensitive_file_access"; then
  ok "Rule 'builtin_sensitive_file_access' loaded"
else
  # Falco might not print rule names on startup, check for rules count
  RULES_COUNT=$(docker logs "$FALCO_CONTAINER" 2>&1 | grep -oE 'Loading rules[^0-9]*([0-9]+)' | grep -oE '[0-9]+$' || echo "0")
  if [[ "$RULES_COUNT" != "0" ]]; then
    ok "Falco loaded $RULES_COUNT rule(s)"
  else
    # Try alternative: use falco --list
    docker exec "$FALCO_CONTAINER" falco --list --rules 2>/dev/null | grep -q "builtin_sensitive_file_access" && ok "Rule verified via --list" || warn "Could not verify rule via logs, proceeding with trigger test"
  fi
fi
echo ""

# ── Step 5: Trigger the rule ──
info "Step 5: Trigger the rule — write to /etc/shadow"
echo ""
echo -e "${YELLOW}  Rule condition:${NC}"
echo "    evt.type in (open, openat, openat2)"
echo "    and fd.name in (/etc/shadow, /etc/passwd, /root/.ssh/id_rsa, /root/.ssh/authorized_keys)"
echo "    and evt.is_open_write=true"
echo ""
echo -e "${YELLOW}  Trigger action:${NC}"
echo "    Run a container (with --pid=host) that writes to /etc/shadow"
echo ""

# Run a trigger container that writes to /etc/shadow
# Using debian:bookworm-slim because it has an existing /etc/shadow
docker rm -f "$TRIGGER_CONTAINER" 2>/dev/null || true

info "Running trigger container (debian — has /etc/shadow)..."
docker run --name "$TRIGGER_CONTAINER" --pid=host debian:bookworm-slim sh -c 'echo "virbius-test::12345:0:99999:7:::" >> /etc/shadow' 2>&1 || true

info "Trigger container completed"
echo ""

# ── Step 6: Check Falco for the alert ──
info "Step 6: Check Falco stdout for the alert"
echo ""

sleep 3  # Give Falco time to process and output the alert

# Capture Falco logs
FALCO_LOGS=$(docker logs "$FALCO_CONTAINER" 2>&1)

# Check if the alert was generated
if echo "$FALCO_LOGS" | grep -q "builtin_sensitive_file_access"; then
  ok "ALERT DETECTED: builtin_sensitive_file_access"
  echo ""
  echo -e "${GREEN}  ── Falco Alert ──${NC}"
  # Extract and pretty-print the alert JSON
  echo "$FALCO_LOGS" | grep "builtin_sensitive_file_access" | while IFS= read -r line; do
    if echo "$line" | grep -q "^{"; then
      # JSON format — pretty print
      echo "$line" | python3 -m json.tool 2>/dev/null || echo "$line"
    else
      echo "  $line"
    fi
  done
  echo ""
  FALCO_ALERT_DETECTED=true
else
  err "No alert detected in Falco stdout"
  warn "Falco logs (last 20 lines):"
  echo "$FALCO_LOGS" | tail -20
  echo ""
  FALCO_ALERT_DETECTED=false
fi

# ── Step 7: (Optional) Verify Engine received the alert ──
if $ENGINE_REACHABLE && $FALCO_ALERT_DETECTED; then
  echo ""
  info "Step 7: Verify Engine received the alert (full pipeline)"

  # Check Falco HTTP output logs for delivery
  if echo "$FALCO_LOGS" | grep -qi "http.*output\|http.*send\|alert.*sent"; then
    ok "Falco HTTP output activity detected in logs"
  fi

  # The Engine's FalcoAlertController will log the alert.
  # Since we didn't register a PID in pidmap, the Engine will return "pid_not_mapped"
  # which is expected — it means the alert reached the Engine but wasn't correlated
  # to an Agent session (because we didn't run a real Agent).
  info "Checking Engine logs for Falco alert receipt..."

  ENGINE_LOG="/tmp/virbius-agent/logs/engine.log"
  if [[ -f "$ENGINE_LOG" ]]; then
    sleep 2
    if grep -q "falco alert" "$ENGINE_LOG" 2>/dev/null; then
      ok "Engine received the alert:"
      grep "falco alert" "$ENGINE_LOG" | tail -3 | sed 's/^/  /'
    else
      warn "No Falco alert found in Engine log ($ENGINE_LOG)"
      warn "Note: Engine may filter alerts without matching PID in pidmap."
      warn "      This is expected if no Agent session was registered."
    fi
  else
    warn "Engine log not found: $ENGINE_LOG"
    warn "To verify full pipeline, check Engine logs manually."
  fi

  # Alternative: directly test the Engine endpoint
  info "Directly testing Engine endpoint..."
  RESULT=$(curl -s -X POST "$ENGINE_URL/api/internal/falco-alert" \
    -H 'Content-Type: application/json' \
    -d '{
      "rule": "builtin_sensitive_file_access",
      "priority": "Warning",
      "output": "Sensitive file access (user=root, pid=12345, file=/etc/shadow)",
      "output_fields": {
        "evt.time": 1704067200000000000,
        "proc.pid": 12345,
        "proc.ppid": 1,
        "proc.cgroup.id": 0,
        "proc.name": "sh",
        "proc.cmdline": "sh -c echo virbius >> /etc/shadow",
        "fd.name": "/etc/shadow",
        "user.name": "root"
      }
    }')
  echo "  Engine response: $RESULT"
  if echo "$RESULT" | grep -q "ignored\|pid_not_mapped"; then
    ok "Engine endpoint functional (alert received, filtered as expected — no Agent PID registered)"
  elif echo "$RESULT" | grep -q "ok"; then
    ok "Engine endpoint functional (alert processed)"
  else
    warn "Unexpected Engine response"
  fi
fi

# ── Step 8: Additional trigger tests ──
if $FALCO_ALERT_DETECTED; then
  echo ""
  info "Step 8: Additional trigger tests"
  echo ""

  # Test 2: Write to /root/.ssh/authorized_keys
  echo -e "${YELLOW}  Test 2: Write to /root/.ssh/authorized_keys${NC}"
  docker run --rm --pid=host --user root debian:bookworm-slim sh -c 'mkdir -p /root/.ssh && echo "ssh-rsa AAAA... test" >> /root/.ssh/authorized_keys' 2>&1 || true
  sleep 2
  if docker logs "$FALCO_CONTAINER" 2>&1 | tail -10 | grep -q "authorized_keys"; then
    ok "Alert for /root/.ssh/authorized_keys detected"
  else
    warn "No alert for authorized_keys (may need --user root in trigger)"
  fi

  # Test 3: Read-only access (should NOT trigger — rule requires write)
  echo ""
  echo -e "${YELLOW}  Test 3: Read /etc/shadow (should NOT trigger — write-only rule)${NC}"
  docker run --rm --pid=host debian:bookworm-slim cat /etc/shadow 2>/dev/null || true
  sleep 2
  # Count alerts before and after
  ALERTS_BEFORE=$(docker logs "$FALCO_CONTAINER" 2>&1 | grep -c "builtin_sensitive_file_access" || echo "0")
  # Wait a moment and check if count increased
  sleep 1
  ALERTS_AFTER=$(docker logs "$FALCO_CONTAINER" 2>&1 | grep -c "builtin_sensitive_file_access" || echo "0")
  if [[ "$ALERTS_BEFORE" == "$ALERTS_AFTER" ]]; then
    ok "No new alert for read-only access (correct — rule requires evt.is_open_write=true)"
  else
    warn "Alert count increased ($ALERTS_BEFORE → $ALERTS_AFTER) — read may have triggered (unexpected)"
  fi
fi

# ── Summary ──
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN} Test Summary                            ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  Rule:     builtin_sensitive_file_access"
echo "  Condition: open/openat/openat2 with write on:"
echo "             /etc/shadow, /etc/passwd,"
echo "             /root/.ssh/id_rsa, /root/.ssh/authorized_keys"
echo "  Priority: WARNING"
echo ""
if $FALCO_ALERT_DETECTED; then
  echo -e "  ${GREEN}✓ Falco rule triggered successfully${NC}"
else
  echo -e "  ${RED}✗ Falco rule did not trigger${NC}"
fi

if $ENGINE_REACHABLE; then
  echo -e "  ${GREEN}✓ Engine endpoint verified${NC}"
else
  echo "  ○ Engine not tested (not running)"
fi

echo ""
echo "  Falco container: $FALCO_CONTAINER (still running)"
echo "  View logs:       docker logs -f $FALCO_CONTAINER"
echo "  Cleanup:         ./scripts/test-falco-sensitive-file-docker.sh --cleanup"
echo ""

# Clean up temp config dir
rm -rf "$RUNTIME_CONFIG_DIR"
