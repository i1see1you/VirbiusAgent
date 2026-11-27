#!/usr/bin/env bash
# test-gvisor-gate.sh — real-runsc product gate (proxy + runsc only).
#
# Verifies the v1.1.0 local-exec release gate with a REAL runsc (no stub):
#   - the proxy container runs privileged + host cgroup namespace
#   - runsc accepts the product OCI config: read-only rootfs, per-exec tmpfs
#     (/workspace, /tmp) with size caps, RLIMIT_NOFILE/NPROC, no network
#   - the tool result reports sandbox_used=gvisor and honest limits_enforced
#   - ENFORCE=1 also proves cgroup-backed memory limits (OOM on 200MB vs 128MB)
#
# It needs NO control / engine / redis:
#   - license: pre-signed fixture scripts/gvisor-gate/license.jwt (+ pubkey)
#   - manifest: a static edge manifest carrying gvisor_config
#   - engine-absent: test-only failover high_risk_fail_closed=false
#     (this gate proves the sandbox execution path, not engine decisions)
#
# Requirements: docker, jq, curl, a Linux host with runsc installed
# (CI installs it; on Docker Desktop pass SKIP_RUNSC_HOST_CHECK=1 and the
# runsc path inside the VM, because bind mounts resolve in the VM).
#
# Usage:
#   ./scripts/test-gvisor-gate.sh                 # default (--ignore-cgroups)
#   ENFORCE=1 ./scripts/test-gvisor-gate.sh       # cgroup-hard-limit variant
#   BUILD=1  ./scripts/test-gvisor-gate.sh        # (re)build the proxy image
#
# Env:
#   PROXY_IMAGE   default virbius-mcp-proxy:gvisor-gate
#   RUNSC_PATH    default /usr/local/bin/runsc
#   GVISOR_BIN_DIR default <dirname RUNSC_PATH>/gvisor-bin
#   ROOTFS_DIR    optional; else exported from alpine:3.20
#   PORT          default 19090
#   KEEP=1        keep the temp workdir and container for debugging
#   SKIP_RUNSC_HOST_CHECK=1  don't stat RUNSC_PATH on the host (Docker Desktop)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { echo -e "${CYAN}[STEP]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || err "missing dependency: $1"; }
need docker; need jq; need curl

PROXY_IMAGE="${PROXY_IMAGE:-virbius-mcp-proxy:gvisor-gate}"
RUNSC_PATH="${RUNSC_PATH:-/usr/local/bin/runsc}"
GVISOR_BIN_DIR="${GVISOR_BIN_DIR:-$(dirname "$RUNSC_PATH")/gvisor-bin}"
ROOTFS_DIR="${ROOTFS_DIR:-}"
PORT="${PORT:-19090}"
ENFORCE="${ENFORCE:-0}"
KEEP="${KEEP:-0}"
SKIP_RUNSC_HOST_CHECK="${SKIP_RUNSC_HOST_CHECK:-0}"

case "$ENFORCE" in 1|true|yes) ENFORCE=1 ;; *) ENFORCE=0 ;; esac

PROXY_URL="http://127.0.0.1:${PORT}"
NAME="virbius-gvisor-gate-$$"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/virbius-gvisor-gate.XXXXXX")"
FIXTURE_DIR="$ROOT/scripts/gvisor-gate"

cleanup() {
  local rc=$?
  exec 3>&- 2>/dev/null || true
  [[ -f "$WORK/sse.pid" ]] && kill "$(cat "$WORK/sse.pid")" 2>/dev/null || true
  if [[ "$KEEP" != "1" ]]; then
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$WORK"
  else
    warn "kept workdir=$WORK container=$NAME"
  fi
  exit $rc
}
trap cleanup EXIT

[[ -f "$FIXTURE_DIR/license.jwt" && -f "$FIXTURE_DIR/license.pub.pem" ]] \
  || err "missing license fixture under $FIXTURE_DIR"

if [[ "$SKIP_RUNSC_HOST_CHECK" != "1" ]]; then
  [[ -x "$RUNSC_PATH" ]] || err "runsc not found/executable at RUNSC_PATH=$RUNSC_PATH (install gVisor)"
  [[ -d "$GVISOR_BIN_DIR" ]] || err "gvisor-bin sidecars not found at $GVISOR_BIN_DIR"
fi
[[ -n "$RUNSC_PATH" && -n "$GVISOR_BIN_DIR" ]] || err "RUNSC_PATH/GVISOR_BIN_DIR must be set"
if ! docker image inspect "$PROXY_IMAGE" >/dev/null 2>&1; then BUILD=1; fi

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN} gVisor local-exec gate  (ENFORCE=${ENFORCE})${NC}"
echo -e "${CYAN}============================================================${NC}"

# ── 1. proxy image ─────────────────────────────────────────────────────────
if [[ "${BUILD:-0}" == "1" ]]; then
  info "1. build proxy image ($PROXY_IMAGE) — target virbius-mcp-proxy"
  docker build --target virbius-mcp-proxy -t "$PROXY_IMAGE" . >/dev/null \
    || err "docker build failed"
  ok "proxy image built"
else
  info "1. reuse proxy image $PROXY_IMAGE"
  ok "proxy image present"
fi

# ── 2. sandbox rootfs ──────────────────────────────────────────────────────
mkdir -p "$WORK/rootfs"
if [[ -n "$ROOTFS_DIR" ]]; then
  info "2. use ROOTFS_DIR=$ROOTFS_DIR"
  [[ -x "$ROOTFS_DIR/bin/sh" || -e "$ROOTFS_DIR/bin/busybox" ]] \
    || err "ROOTFS_DIR lacks bin/sh or bin/busybox"
  ROOTFS_MOUNT="$ROOTFS_DIR"
else
  info "2. export alpine:3.20 rootfs"
  docker image inspect alpine:3.20 >/dev/null 2>&1 || docker pull -q alpine:3.20 >/dev/null
  CR="gvisor-gate-rootfs-$$"
  docker create --name "$CR" alpine:3.20 >/dev/null
  docker export "$CR" | tar -xf - -C "$WORK/rootfs" || { docker rm -f "$CR" >/dev/null 2>&1; err "rootfs export failed"; }
  docker rm "$CR" >/dev/null
  chmod -R a+rX "$WORK/rootfs"
  ROOTFS_MOUNT="$WORK/rootfs"
  ok "rootfs ready ($(ls "$WORK/rootfs" | wc -l | tr -d ' ') entries)"
fi

# ── 3. license + manifest + config ─────────────────────────────────────────
info "3. prepare license / edge manifest / proxy config"
mkdir -p "$WORK/config" "$WORK/manifest" "$WORK/state"
cp "$FIXTURE_DIR/license.jwt" "$WORK/config/license.jwt"
cp "$FIXTURE_DIR/license.pub.pem" "$WORK/config/license.pub.pem"
chmod 777 "$WORK/state"

LICENSE_FILE="$WORK/config/license.jwt"
LICENSE_JWT="$(tr -d '\n' < "$LICENSE_FILE")"

cat > "$WORK/manifest/edge-manifest.json" <<JSON
{
  "tenant_id": "default",
  "manifest_version": "1",
  "app_id": "gvisor-gate",
  "rules": [],
  "dlp_rules": [],
  "tool_policies": [
    {"tool_name":"virbius__shell","risk_class":"high","sandbox_type":"gvisor","sandbox_intent":"gvisor","timeout_ms":30000,"fast_path":false},
    {"tool_name":"virbius__python","risk_class":"high","sandbox_type":"gvisor","sandbox_intent":"gvisor","timeout_ms":30000,"fast_path":false},
    {"tool_name":"virbius__node","risk_class":"high","sandbox_type":"gvisor","sandbox_intent":"gvisor","timeout_ms":30000,"fast_path":false}
  ],
  "landlock_profiles": [],
  "gvisor_config": {
    "runsc_path": "/opt/virbius/bin/runsc",
    "rootfs_path": "/opt/virbius/rootfs",
    "min_warm": 0,
    "max_idle": 2,
    "memory_limit_bytes": 134217728,
    "cpu_quota": 1.0,
    "network_disabled": true,
    "exec_timeout_ms": 30000,
    "scratch_bytes": 33554432,
    "tmp_bytes": 8388608,
    "pids_limit": 96,
    "nofile_limit": 256,
    "nproc_limit": 64,
    "enforce_cgroups": $([[ "$ENFORCE" == "1" ]] && echo true || echo false)
  },
  "sdk_config": {"audit_sample_rate_allow": 1.0, "audit_sample_rate_hit": 1.0}
}
JSON

cat > "$WORK/config/proxy.toml" <<TOML
[proxy]
listen = "tcp://0.0.0.0:9090"
upstream_url = ""

[security]
control_base_url = ""
engine_url = "http://127.0.0.1:9"
license_file = "/config/license.jwt"
license_public_key = "/config/license.pub.pem"
fallback_policy = "minimum_privilege"

[security.failover]
high_risk_fail_closed = false
low_risk_fail_open = true
engine_timeout_ms = 500

[local_exec]
mode = "gvisor"

[trace]
enabled = false
TOML
ok "fixtures ready (manifest enforce_cgroups=$([[ "$ENFORCE" == "1" ]] && echo true || echo false))"

# ── 4. start proxy: privileged + host cgroup namespace ─────────────────────
info "4. start proxy (privileged + cgroup:host, real runsc)"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" \
  --privileged --cgroupns=host \
  -p "${PORT}:9090" \
  -v "$ROOTFS_MOUNT:/opt/virbius/rootfs:ro" \
  -v "$WORK/config:/config:ro" \
  -v "$WORK/manifest:/app/data/edge/default:ro" \
  -v "$WORK/state:/var/lib/virbius/gvisor" \
  -v "$RUNSC_PATH:/opt/virbius/bin/runsc:ro" \
  -v "$GVISOR_BIN_DIR:/opt/virbius/bin/gvisor-bin:ro" \
  -e VIRBIUS_RUNSC_PATH=/opt/virbius/bin/runsc \
  -e VIRBIUS_GVISOR_ROOTFS=/opt/virbius/rootfs \
  -e VIRBIUS_GVISOR_STATE_ROOT=/var/lib/virbius/gvisor \
  -e VIRBIUS_GVISOR_ENFORCE_CGROUPS="$ENFORCE" \
  -e HTTP_PROXY="" -e HTTPS_PROXY="" -e http_proxy="" -e https_proxy="" \
  "$PROXY_IMAGE" --config /config/proxy.toml >/dev/null || err "docker run failed"

for i in $(seq 1 40); do
  if curl -sf --max-time 2 "$PROXY_URL/health" >/dev/null 2>&1; then break; fi
  if ! docker ps --format '{{.Names}}' | grep -qx "$NAME"; then
    docker logs "$NAME" 2>&1 | tail -40; err "proxy container exited"
  fi
  sleep 1
  [[ $i == 40 ]] && { docker logs "$NAME" 2>&1 | tail -40; err "proxy not healthy on $PROXY_URL"; }
done
ok "proxy healthy on $PROXY_URL"

# ── MCP SSE helpers ────────────────────────────────────────────────────────
mcp_session() {
  rm -f "$WORK/sse" "$WORK/sse.pid"
  mkfifo "$WORK/sse"
  curl -s -N "$PROXY_URL/sse" > "$WORK/sse" &
  echo $! > "$WORK/sse.pid"
  exec 3< "$WORK/sse"
}
mcp_read() {
  local timeout=${1:-20} data="" line
  while IFS= read -r -t "$timeout" line <&3; do
    line="${line%$'\r'}"
    [[ "$line" =~ ^data: ]] && data="${line#data: }" && break
  done
  echo "$data"
}
mcp_post() {
  local sid=$1 id=$2 method=$3 params=$4
  curl -s -o /dev/null --max-time 10 -X POST "$PROXY_URL/messages/?session_id=$sid" \
    -H "Content-Type: application/json" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}" || true
}
mcp_wait_id() {
  local want=$1 deadline=$((SECONDS + ${2:-60})) raw
  while (( SECONDS < deadline )); do
    raw=$(mcp_read 10)
    [[ -z "$raw" ]] && continue
    if echo "$raw" | jq -e --argjson id "$want" \
      '(.id == $id) and (has("result") or has("error"))' >/dev/null 2>&1; then
      echo "$raw"; return 0
    fi
  done
  echo ""
}

# ── 5. initialize + shell execution ────────────────────────────────────────
info "5. MCP session: initialize + tools/call virbius__shell"
mcp_session
EP=$(mcp_read 20)
SID=$(echo "$EP" | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
[[ -n "$SID" ]] || { docker logs "$NAME" 2>&1 | tail -40; err "SSE session_id missing: $EP"; }
ok "MCP session $SID"

mcp_post "$SID" 1 initialize "$(jq -n --arg jwt "$LICENSE_JWT" \
  '{_meta:{app_id:"gvisor-gate",tenant_id:"default",license_jwt:$jwt}}')"
INIT=$(mcp_wait_id 1 30)
echo "$INIT" | jq -e '.result.serverInfo' >/dev/null || err "initialize failed: $INIT"
ok "initialize ok"

mcp_post "$SID" 2 tools/list '{}'
LIST=$(mcp_wait_id 2 30)
echo "$LIST" | jq -e '.result.tools[] | select(.name=="virbius__shell")' >/dev/null \
  || err "virbius__shell missing from tools/list: $LIST"
ok "virbius__shell advertised (license grant)"

SHELL_CMD=$'echo GV_TOKEN_OK\n'
SHELL_CMD+=$'touch /etc/should_fail 2>&1 || echo ROFS_OK\n'
SHELL_CMD+=$'touch /workspace/ok && echo WS_OK\n'
SHELL_CMD+=$'echo data > /tmp/y && echo TMP_OK\n'
SHELL_CMD+=$'grep -E "Max open files|Max processes" /proc/self/limits\n'
SHELL_CMD+=$'uname -a\n'

mcp_post "$SID" 3 tools/call "$(jq -n --arg c "$SHELL_CMD" '{name:"virbius__shell",arguments:{command:$c}}')"
CALL=$(mcp_wait_id 3 60)
[[ -n "$CALL" ]] || { docker logs "$NAME" 2>&1 | tail -40; err "shell call produced no response"; }

echo "$CALL" | jq -e '.result._meta.sandbox_used == "gvisor"' >/dev/null \
  || err "expected sandbox_used=gvisor: $CALL"
echo "$CALL" | jq -e '.result._meta.exit_code == 0' >/dev/null \
  || err "expected exit_code=0: $CALL"
TEXT=$(echo "$CALL" | jq -r '.result.content[0].text // ""')
for tok in GV_TOKEN_OK ROFS_OK WS_OK TMP_OK "Max open files" "Max processes"; do
  echo "$TEXT" | grep -q "$tok" || err "sandbox output missing '$tok': $TEXT"
done
echo "$TEXT" | grep -q "Max open files *256" || err "RLIMIT_NOFILE not 256: $TEXT"
echo "$TEXT" | grep -q "Max processes *64" || err "RLIMIT_NPROC not 64: $TEXT"
ok "real gVisor exec: RO rootfs, /workspace + /tmp rw, rlimits applied"

echo "$TEXT" | grep -q "4.19.0-gvisor" || warn "uname did not show gvisor kernel (rootfs may lack uname)"
EXPECT_ENFORCED=$([[ "$ENFORCE" == "1" ]] && echo true || echo false)
echo "$CALL" | jq -e --argjson e "$EXPECT_ENFORCED" '.result._meta.gvisor_limits_enforced == $e' >/dev/null \
  || err "gvisor_limits_enforced != $EXPECT_ENFORCED: $CALL"
ok "honest _meta: gvisor_limits_enforced=$EXPECT_ENFORCED"

# ── 6. ENFORCE: cgroup-backed memory limit must actually kill ──────────────
if [[ "$ENFORCE" == "1" ]]; then
  info "6. ENFORCE: allocate ~200MB against 128MB cgroup limit -> OOM"
  # Fast memory hog: string doubling reaches the 128MiB limit in ~27 steps.
  MEM_CMD=$'awk \'BEGIN{s="x"; for(i=0;i<30;i++){s=s s}; print "alloc_done", length(s)}\'\n'
  MEM_CMD+=$'echo AFTER_ALLOC\n'
  mcp_post "$SID" 4 tools/call "$(jq -n --arg c "$MEM_CMD" '{name:"virbius__shell",arguments:{command:$c}}')"
  OOM=$(mcp_wait_id 4 60)
  [[ -n "$OOM" ]] || err "memtest produced no response"
  MTEXT=$(echo "$OOM" | jq -r '.result.content[0].text // .error.message // ""')
  if echo "$MTEXT" | grep -q "alloc_done"; then
    err "allocation completed under a 128MB limit (cgroup not enforced): $OOM"
  fi
  # The allocating child is killed by the cgroup OOM killer; the outer sh
  # survives and reports "Killed", so we assert on that evidence (not exit 0).
  if ! echo "$MTEXT" | grep -qiE 'killed|oom|out of memory'; then
    err "expected OOM-kill evidence (no alloc_done, no kill marker): $OOM"
  fi
  ok "cgroup memory limit enforced (allocating child killed, allocation never completed)"
fi

echo -e "${GREEN}============================================================${NC}"
echo -e "${GREEN} GVISOR GATE PASSED (ENFORCE=${ENFORCE})${NC}"
echo -e "${GREEN}============================================================${NC}"
