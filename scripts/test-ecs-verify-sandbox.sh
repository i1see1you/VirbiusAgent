#!/bin/bash
# ============================================================================
# ECS Sandbox Verification — one-shot script
#
# Usage on ECS:
#   scp this + project files → ECS, then:  bash /root/VirbiusAgent/scripts/ecs-verify-sandbox.sh
#
# Or run directly from local Mac via SSH:
#   ssh -i ~/Documents/VirbiusAgent.pem root@101.37.116.110 \
#     'bash -s' < scripts/ecs-verify-sandbox.sh
# ============================================================================
set -euo pipefail

PROJECT_DIR="${PROJECT_DIR:-/root/VirbiusAgent}"
LICENSE_DIR="${LICENSE_DIR:-/etc/virbius}"
PROXY_PORT="${PROXY_PORT:-9090}"
CONTAINER_NAME="virbius-sandbox-test"

red()   { echo -e "\033[31m$*\033[0m"; }
green() { echo -e "\033[32m$*\033[0m"; }
cyan()  { echo -e "\033[36m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }
step()  { bold "\n▸ $*"; }

# ────────────────────────────────────────────────────────────────
# 0. Prerequisites
# ────────────────────────────────────────────────────────────────
bold "═══════════════════════════════════════════════════════"
bold "  VirbiusAgent Sandbox E2E Verification"
bold "═══════════════════════════════════════════════════════"

step "0. Prerequisites"
command -v docker  >/dev/null || { red "docker not found"; exit 1; }
command -v curl   >/dev/null || { red "curl not found";   exit 1; }
command -v jq     >/dev/null || { red "jq not found — install with: yum install -y jq"; exit 1; }
green "  docker: $(docker --version)"
green "  curl:   $(curl --version | head -1)"
green "  jq:     $(jq --version)"

# ────────────────────────────────────────────────────────────────
# 1. License files
# ────────────────────────────────────────────────────────────────
step "1. License files"
mkdir -p "$LICENSE_DIR"

cat > "$LICENSE_DIR/license.pub.pem" <<'PEM'
-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAXiYxDJJVIN1rCEKY5u5SmZhAVJ3cXI1X2p8/6w7fIQY=
-----END PUBLIC KEY-----
PEM

# JWT: app_id=test1, allowed_tools=[] (wildcard), risk_quota=60, exp=Sep 2027
cat > "$LICENSE_DIR/license.jwt" <<'JWT'
eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJyaXNrX3F1b3RhIjo2MCwiaWF0IjoxNzg1NzMxMzc0LCJ0b29sX3JhdGVfbGltaXQiOjUwLCJleHAiOjE4MTcyNjczNzQsInRlbmFudF9pZCI6ImRlZmF1bHQiLCJhZ2VudF9uYW1lIjoidGVzdDEiLCJhbGxvd2VkX3Rvb2xzIjpbXSwiYXBwX2lkIjoidGVzdDEiLCJhZ2VudF9haWQiOiJhaWQ6Y246b3JnOmRlZmF1bHQ6YWdlbnQ6dGVzdDEtOGQ0OWVjYzkifQ.B0zs26e-OtfuH-iKP9AaczkQd5sjs0SqYxj4WXTi4IFj63CXY9UxKbuLgnMbb5VjUxlD_PIN-JGOeZcyqqX-DQ
JWT

green "  license.pub.pem → $LICENSE_DIR/license.pub.pem"
green "  license.jwt     → $LICENSE_DIR/license.jwt"

# ────────────────────────────────────────────────────────────────
# 2. Build proxy image
# ────────────────────────────────────────────────────────────────
step "2. Build proxy image"
cd "$PROJECT_DIR"

# Clear proxy env vars from Docker daemon
docker build --target virbius-mcp-proxy -t virbius-mcp-proxy:dev \
  --build-arg APT_MIRROR=cn --build-arg CRATES_MIRROR=cn --build-arg MAVEN_MIRROR=aliyun \
  . 2>&1 | tail -5
green "  image built: virbius-mcp-proxy:dev"

# ────────────────────────────────────────────────────────────────
# 3. Stop old container, start new one
# ────────────────────────────────────────────────────────────────
step "3. Start proxy container"
# Free port 9090 if the compose stack proxy still holds it
docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
docker stop virbiusagent-virbius-mcp-proxy-1 2>/dev/null && echo "  (stopped compose proxy to free port 9090)" || true

# Stage the control-plane generated edge manifest (with landlock profiles) so it
# is present at container startup when the proxy loads it via read_manifest().
# Staged edge manifest: prefer an explicit verify manifest (e.g. the repo debug
# manifest with sandbox_type=gvisor/landlock profiles) over the control-plane one.
VERIFY_MANIFEST="${VERIFY_MANIFEST:-}"
if [ -n "$VERIFY_MANIFEST" ] && [ ! -f "$VERIFY_MANIFEST" ]; then
  red "  VERIFY_MANIFEST not found: $VERIFY_MANIFEST"
  exit 1
fi
CTL_MANIFEST="${CTL_MANIFEST:-/var/lib/docker/volumes/virbiusagent_control-data/_data/edge/default/test1/edge-manifest.json}"
EDGE_SYNC_DIR="/tmp/edge-sync/$CONTAINER_NAME"
mkdir -p "$EDGE_SYNC_DIR/edge/default"
if [ -n "$VERIFY_MANIFEST" ]; then
  cp "$VERIFY_MANIFEST" "$EDGE_SYNC_DIR/edge/default/edge-manifest.json"
  echo "  (staged verify manifest -> $EDGE_SYNC_DIR/edge/default/edge-manifest.json)"
elif [ -f "$CTL_MANIFEST" ]; then
  cp "$CTL_MANIFEST" "$EDGE_SYNC_DIR/edge/default/edge-manifest.json"
  echo "  (staged control manifest -> $EDGE_SYNC_DIR/edge/default/edge-manifest.json)"
else
  echo "  (WARN: control manifest not found at $CTL_MANIFEST)"
fi

# Match the compose stack's real gVisor wiring: privileged + runsc/rootfs mounts.
# runsc and rootfs live on the host at /opt/virbius/bin + /opt/virbius/rootfs.
docker run -d --name "$CONTAINER_NAME" \
  --network host \
  --privileged \
  -v "$LICENSE_DIR/license.pub.pem:/etc/virbius/license.pub.pem:ro" \
  -v "$LICENSE_DIR/license.jwt:/etc/virbius/license.jwt:ro" \
  -v /opt/virbius/bin:/opt/virbius/bin:ro \
  -v /opt/virbius/rootfs:/opt/virbius/rootfs \
  -v "$EDGE_SYNC_DIR/edge/default:/app/data/edge/default:ro" \
  -e VIRBIUS_TRANSPORT="tcp://0.0.0.0:$PROXY_PORT" \
  -e VIRBIUS_UPSTREAM_URL="" \
  -e VIRBIUS_ALLOW_UNSANDBOXED=true \
  -e VIRBIUS_RUNSC_PATH=/opt/virbius/bin/runsc \
  -e VIRBIUS_GVISOR_ROOTFS=/opt/virbius/rootfs \
  -e VIRBIUS_GVISOR_STATE_ROOT=/tmp/virbius-gvisor-state \
  -e VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license.pub.pem \
  -e VIRBIUS_LICENSE_FILE=/etc/virbius/license.jwt \
  virbius-mcp-proxy:dev

# Wait for proxy to be ready
green "  waiting for proxy..."
for i in $(seq 1 15); do
  if curl -sf --max-time 2 "http://127.0.0.1:$PROXY_PORT/health" >/dev/null 2>&1; then
    green "  proxy ready (http://127.0.0.1:$PROXY_PORT)"
    break
  fi
  [ "$i" -eq 15 ] && { red "  proxy failed to start"; docker logs "$CONTAINER_NAME"; exit 1; }
  sleep 2
done

# Show startup logs
docker logs "$CONTAINER_NAME" 2>&1 | grep -E "loaded license|upstream|listening|panic" | head -10

# ────────────────────────────────────────────────────────────────
# 4. Run verification script
# ────────────────────────────────────────────────────────────────
step "4. Run sandbox verification"
bash "$PROJECT_DIR/scripts/test-verify-sandbox.sh" 2>&1
VERIFY_EXIT=$?

# ────────────────────────────────────────────────────────────────
# 5. Results
# ────────────────────────────────────────────────────────────────
echo
if [ "$VERIFY_EXIT" -eq 0 ]; then
  green "═══════════════════════════════════════════════════════"
  green "  ALL CHECKS PASSED"
  green "═══════════════════════════════════════════════════════"
else
  red "═══════════════════════════════════════════════════════"
  red "  SOME CHECKS FAILED (exit=$VERIFY_EXIT)"
  red "═══════════════════════════════════════════════════════"
fi

# ────────────────────────────────────────────────────────────────
# 6. Cleanup (uncomment to auto-remove)
# ────────────────────────────────────────────────────────────────
# docker rm -f "$CONTAINER_NAME" 2>/dev/null
# bold "  container removed: $CONTAINER_NAME"

exit $VERIFY_EXIT
