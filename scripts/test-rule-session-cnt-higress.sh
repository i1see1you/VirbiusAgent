#!/usr/bin/env bash
# test-rule-session-cnt-higress.sh
#
# Complete end-to-end test for rule_session_cnt gateway rule with Higress + WASM plugin.
#
# Architecture:
#   Agent → Higress (WASM plugin) → [expression eval] → mock MCP server
#                    ↓                              ↓
#               Redis (counters)              Engine (evaluate)
#
# The WASM plugin intercepts POST /mcp/tools/call requests:
#   1. Extracts tool_name and session_id from headers/body
#   2. Evaluates compiled expression IR (rule_session_cnt)
#   3. If expression matches → block (403)
#   4. Otherwise → forward to engine for further evaluation
#
# Prerequisites:
#   - Docker Desktop running
#   - Virbius services (Redis, Engine, Control) running via docker-compose
#   - rule_session_cnt already published (full rollout) via Control dashboard
#   - tinygo installed (for building WASM plugin): brew install tinygo
#
# Usage:
#   ./scripts/test-rule-session-cnt-higress.sh              # Run full test
#   ./scripts/test-rule-session-cnt-higress.sh --cleanup     # Remove Higress containers
#   ./scripts/test-rule-session-cnt-higress.sh --skip-build  # Skip WASM build (use existing)
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
HIGRESS_TEST_DIR="$SCRIPT_DIR/higress-test"

# ── Config ──
HIGRESS_IMAGE="higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/all-in-one:2.1.0"
HIGRESS_CONTAINER="virbius-higress"
MOCK_MCP_CONTAINER="virbius-mock-mcp"
NETWORK_NAME="virbius-net"
TENANT_ID="default"
RULE_ID="rule_session_cnt"
TEST_SESSION="test-session-e2e-001"
TEST_TOOL="read_file"
ENGINE_URL="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
CONTROL_URL="${VIRBIUS_CONTROL:-http://127.0.0.1:8080}"
REDIS_CONTAINER="virbius-redis"  # from docker-compose.yml

# ── Colors ──
RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }

# ── Parse args ──
DO_CLEANUP=false
SKIP_BUILD=false
for arg in "$@"; do
  case "$arg" in
    --cleanup)    DO_CLEANUP=true ;;
    --skip-build) SKIP_BUILD=true ;;
    *)            err "Unknown argument: $arg"; exit 1 ;;
  esac
done

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN} rule_session_cnt E2E Test (Higress)    ${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# ── Cleanup mode ──
if $DO_CLEANUP; then
  info "Cleaning up Higress test containers..."
  docker rm -f "$HIGRESS_CONTAINER" 2>/dev/null && ok "Removed $HIGRESS_CONTAINER" || true
  docker rm -f "$MOCK_MCP_CONTAINER" 2>/dev/null && ok "Removed $MOCK_MCP_CONTAINER" || true
  docker network rm "$NETWORK_NAME" 2>/dev/null && ok "Removed network $NETWORK_NAME" || true
  rm -rf "$HIGRESS_TEST_DIR"
  exit 0
fi

# ═══════════════════════════════════════════════════════════════
# Step 0: Pre-flight checks
# ═══════════════════════════════════════════════════════════════
info "Step 0: Pre-flight checks"

if ! docker info >/dev/null 2>&1; then
  err "Docker is not running. Start Docker Desktop first."
  exit 1
fi
ok "Docker is running"

# Check Virbius services
if ! curl -sf "$CONTROL_URL/api/v1/health" >/dev/null 2>&1; then
  err "Virbius Control is not reachable at $CONTROL_URL"
  err "Start services with: docker compose up -d redis virbius-engine virbius-control"
  exit 1
fi
ok "Virbius Control reachable: $CONTROL_URL"

if ! curl -sf "$ENGINE_URL/admin/health" >/dev/null 2>&1; then
  err "Virbius Engine is not reachable at $ENGINE_URL"
  exit 1
fi
ok "Virbius Engine reachable: $ENGINE_URL"

# Check Redis
if ! docker exec "$REDIS_CONTAINER" redis-cli ping >/dev/null 2>&1; then
  err "Redis container '$REDIS_CONTAINER' is not running or not accessible"
  err "Start services with: docker compose up -d redis"
  exit 1
fi
ok "Redis reachable"

# Check tinygo for WASM build
if ! $SKIP_BUILD; then
  if command -v tinygo >/dev/null 2>&1; then
    ok "tinygo found: $(tinygo version 2>&1 | head -1)"
  else
    warn "tinygo not found — will attempt to install via brew"
    if command -v brew >/dev/null 2>&1; then
      brew install tinygo
    else
      err "tinygo is required to build the WASM plugin. Install with: brew install tinygo"
      err "Or run with --skip-build if you have a pre-built WASM binary."
      exit 1
    fi
  fi
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 1: Build WASM plugin
# ═══════════════════════════════════════════════════════════════
WASM_BIN="$HIGRESS_TEST_DIR/virbius-gateway.wasm"

if $SKIP_BUILD && [[ -f "$WASM_BIN" ]]; then
  info "Step 1: Skipping WASM build (using existing binary)"
  ok "WASM binary: $WASM_BIN ($(du -h "$WASM_BIN" | cut -f1))"
else
  info "Step 1: Build WASM plugin"
  mkdir -p "$HIGRESS_TEST_DIR"

  WASM_SRC="$ROOT/virbius-gateway/wasm"
  if [[ ! -d "$WASM_SRC" ]]; then
    err "WASM source not found: $WASM_SRC"
    exit 1
  fi

  # Build with tinygo
  info "Building with tinygo..."
  (cd "$WASM_SRC" && tinygo build -o "$WASM_BIN" -scheduler=none -target=wasi ./...) 2>&1 || {
    err "WASM build failed"
    err "Make sure tinygo is installed: brew install tinygo"
    exit 1
  }
  ok "WASM binary built: $WASM_BIN ($(du -h "$WASM_BIN" | cut -f1))"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 2: Get rule definition and compile expression IR
# ═══════════════════════════════════════════════════════════════
info "Step 2: Get rule definition and compile expression IR"

# Get rule from Control API
RULE_JSON=$(curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/rules/$RULE_ID")
if echo "$RULE_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); d['data']" >/dev/null 2>&1; then
  RULE_DATA=$(echo "$RULE_JSON" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['data']))")
  ok "Rule '$RULE_ID' found in Control"
else
  err "Rule '$RULE_ID' not found in Control API"
  err "Response: $RULE_JSON"
  err "Make sure the rule is created and published via the Control dashboard."
  exit 1
fi

# Extract rule fields
RULE_LAYER=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('layer',''))")
RULE_RUNTIME=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('runtime',''))")
RULE_INTENT=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('intent_action','deny'))")
RULE_RISK=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('risk_score',80))")
RULE_REASON=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('reason_code','session_count_exceeded'))")
RULE_ROLLOUT=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('rollout_state',''))")
RULE_BODY=$(echo "$RULE_DATA" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('body',''))")

info "  Layer:     $RULE_LAYER"
info "  Runtime:   $RULE_RUNTIME"
info "  Intent:    $RULE_INTENT"
info "  Risk:      $RULE_RISK"
info "  Rollout:   $RULE_ROLLOUT"

if [[ "$RULE_ROLLOUT" != "full" ]]; then
  warn "Rule rollout state is '$RULE_ROLLOUT', not 'full'. Test may not reflect production behavior."
fi

if [[ "$RULE_LAYER" != "gateway" || "$RULE_RUNTIME" != "lua" ]]; then
  err "Rule is not a gateway Lua rule (layer=$RULE_LAYER, runtime=$RULE_RUNTIME)"
  err "This test is designed for gateway Lua rules only."
  exit 1
fi

# Compile Lua script to expression IR using virbius-expr CLI
EXPR_CLI="$ROOT/virbius-expr/cmd/virbius-expr"
EXPR_BIN="$HIGRESS_TEST_DIR/virbius-expr"

info "Building virbius-expr CLI..."
(cd "$ROOT/virbius-expr" && go build -o "$EXPR_BIN" ./cmd/virbius-expr/) 2>&1 || {
  err "Failed to build virbius-expr CLI"
  exit 1
}
ok "virbius-expr CLI built"

# Extract the Lua script body
LUA_SCRIPT=$(echo "$RULE_DATA" | python3 -c "
import sys, json
d = json.load(sys.stdin)
body = d.get('body', '')
if isinstance(body, str):
    # body might be JSON-encoded string
    try:
        body = json.loads(body)
    except:
        pass
if isinstance(body, dict):
    body = body.get('script', body.get('body', ''))
print(body)
")

if [[ -z "$LUA_SCRIPT" || "$LUA_SCRIPT" == "None" ]]; then
  err "Could not extract Lua script from rule body"
  err "Rule body: $RULE_BODY"
  exit 1
fi

# Write Lua script to temp file for compilation
LUA_TMP="$HIGRESS_TEST_DIR/rule_session_cnt.lua"
echo "$LUA_SCRIPT" > "$LUA_TMP"
info "Lua script extracted ($(wc -l < "$LUA_TMP") lines)"

# Map intent to expression action
EXPR_ACTION="block"
case "$RULE_INTENT" in
  deny|block)     EXPR_ACTION="block" ;;
  challenge)      EXPR_ACTION="challenge" ;;
  review)         EXPR_ACTION="review" ;;
  *)              EXPR_ACTION="block" ;;
esac

# Compile to expression IR with action binding
EXPR_IR=$( "$EXPR_BIN" \
  --file "$LUA_TMP" \
  --id "$RULE_ID" \
  --script \
  --with-action \
  --rule-id "$RULE_ID" \
  --action "$EXPR_ACTION" \
  --reason "$RULE_REASON" \
  --risk-score "$RULE_RISK" 2>&1) || {
  err "Failed to compile Lua script to expression IR"
  err "virbius-expr output: $EXPR_IR"
  err "Lua script:"
  cat "$LUA_TMP"
  exit 1
}

# Validate IR is valid JSON
echo "$EXPR_IR" | python3 -m json.tool >/dev/null 2>&1 || {
  err "Expression IR is not valid JSON"
  err "Output: $EXPR_IR"
  exit 1
}

ok "Expression IR compiled successfully"
echo "$EXPR_IR" | python3 -m json.tool | head -20
echo "  ..."
echo ""
EXPR_IR_FILE="$HIGRESS_TEST_DIR/expr-ir.json"
echo "$EXPR_IR" > "$EXPR_IR_FILE"

# ═══════════════════════════════════════════════════════════════
# Step 3: Create Higress configuration
# ═══════════════════════════════════════════════════════════════
info "Step 3: Create Higress configuration"

# 3a. Create the WASM plugin config JSON
# The expressions array is embedded directly in the plugin config
PLUGIN_CONFIG="$HIGRESS_TEST_DIR/plugin-config.json"
python3 -c "
import json, sys

# Load expression IR
with open('$EXPR_IR_FILE') as f:
    ir = json.load(f)

# Build plugin config
config = {
    'tenant_id': '$TENANT_ID',
    'evaluate': True,
    'engine_url': 'http://virbius-engine:8082',
    'engine_timeout_ms': 3000,
    'tool_rate_limit': 200,
    'fast_path_tools': [],
    'tool_allowlist': [],
    'license_verify': False,
    'tls': False,
    'fail_mode': 'open',
    'expressions': [ir]
}

with open('$PLUGIN_CONFIG', 'w') as f:
    json.dump(config, f, indent=2)

print(f'Plugin config written: {len(json.dumps(config))} bytes')
" || {
  err "Failed to create plugin config"
  exit 1
}
ok "Plugin config: $PLUGIN_CONFIG"

# 3b. Create mock MCP server (simple Python HTTP server)
MOCK_MCP_SCRIPT="$HIGRESS_TEST_DIR/mock_mcp_server.py"
cat > "$MOCK_MCP_SCRIPT" << 'PYTHON_EOF'
#!/usr/bin/env python3
"""Mock MCP server that accepts tools/call and returns a simple response."""
import json
from http.server import HTTPServer, BaseHTTPRequestHandler

class MockMCPHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_len = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_len) if content_len > 0 else b'{}'
        try:
            req = json.loads(body)
        except:
            req = {}
        resp = {
            "jsonrpc": "2.0",
            "id": req.get("id", 1),
            "result": {
                "content": [{"type": "text", "text": "mock tool executed successfully"}]
            }
        }
        data = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == '/health':
            data = b'{"status":"ok"}'
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, format, *args):
        print(f"[mock-mcp] {args[0]}")

if __name__ == '__main__':
    server = HTTPServer(('0.0.0.0', 8080), MockMCPHandler)
    print("[mock-mcp] Listening on :8080")
    server.serve_forever()
PYTHON_EOF
ok "Mock MCP server script: $MOCK_MCP_SCRIPT"

# 3c. Create Higress Dockerfile (wraps all-in-one with our WASM + config)
HIGRESS_DOCKERFILE="$HIGRESS_TEST_DIR/Dockerfile.higress"
cat > "$HIGRESS_DOCKERFILE" << 'DOCKERFILE_EOF'
FROM higress-registry.cn-hangzhou.cr.aliyuncs.com/higress/all-in-one:2.1.0

# Copy WASM plugin binary
COPY virbius-gateway.wasm /wasm/virbius-gateway.wasm

# Copy plugin config
COPY plugin-config.json /wasm/plugin-config.json

# Copy mock MCP server
COPY mock_mcp_server.py /app/mock_mcp_server.py
DOCKERFILE_EOF
ok "Higress Dockerfile: $HIGRESS_DOCKERFILE"

# 3d. Create Higress dynamic config (routes, services, plugins)
# Higress all-in-one reads config from /root/higress/conf/
HIGRESS_CONF_DIR="$HIGRESS_TEST_DIR/higress-conf"
mkdir -p "$HIGRESS_CONF_DIR"

# Main Higress config
cat > "$HIGRESS_CONF_DIR/higress.conf" << 'HIGRESS_EOF'
# Higress standalone config for Virbius testing

# Admin API
admin:
  address:
    socket_address: { address: 127.0.0.1, port_value: 9901 }

# Static resources (Envoy bootstrap)
static_resources:
  listeners:
    - name: listener_http
      address:
        socket_address: { address: 0.0.0.0, port_value: 80 }
      filter_chains:
        - filters:
            - name: envoy.filters.network.http_connection_manager
              typed_config:
                "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                stat_prefix: ingress_http
                codec_type: AUTO
                route_config:
                  name: local_route
                  virtual_hosts:
                    - name: backend
                      domains: ["*"]
                      routes:
                        - match: { prefix: "/" }
                          route: { cluster: mock_mcp_cluster, timeout: 30s }
                http_filters:
                  - name: envoy.filters.http.wasm
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.filters.http.wasm.v3.Wasm
                      config:
                        name: virbius-gateway
                        root_id: virbius-gateway
                        vm_config:
                          vm_id: vm.wasm
                          runtime: envoy.wasm.runtime.v8
                          code:
                            local:
                              filename: /wasm/virbius-gateway.wasm
                        configuration:
                          "@type": type.googleapis.com/google.protobuf.StringValue
                          value: |
                            {{PLUGIN_CONFIG_JSON}}
                  - name: envoy.filters.http.router
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router

  clusters:
    # Mock MCP server cluster
    - name: mock_mcp_cluster
      type: STRICT_DNS
      lb_policy: ROUND_ROBIN
      load_assignment:
        cluster_name: mock_mcp_cluster
        endpoints:
          - lb_endpoints:
              - endpoint:
                  address:
                    socket_address:
                      address: mock-mcp
                      port_value: 8080

    # Redis cluster (for WASM plugin rate limiting)
    - name: virbius-redis
      type: STRICT_DNS
      lb_policy: ROUND_ROBIN
      load_assignment:
        cluster_name: virbius-redis
        endpoints:
          - lb_endpoints:
              - endpoint:
                  address:
                    socket_address:
                      address: redis
                      port_value: 6379

    # Engine cluster (for WASM plugin evaluate calls)
    # The WASM code uses Istio-style cluster name
    - name: outbound|8082||virbius-engine.default.svc.cluster.local
      type: STRICT_DNS
      lb_policy: ROUND_ROBIN
      load_assignment:
        cluster_name: outbound|8082||virbius-engine.default.svc.cluster.local
        endpoints:
          - lb_endpoints:
              - endpoint:
                  address:
                    socket_address:
                      address: engine
                      port_value: 8082
HIGRESS_EOF

# Inject plugin config JSON into Higress config (escaped for YAML)
PLUGIN_CONFIG_ESCAPED=$(python3 -c "
import json
with open('$PLUGIN_CONFIG') as f:
    config = json.load(f)
# Escape for embedding in YAML block scalar
print(json.dumps(config))
")
# Replace the placeholder
python3 -c "
import sys
with open('$HIGRESS_CONF_DIR/higress.conf', 'r') as f:
    content = f.read()
content = content.replace('{{PLUGIN_CONFIG_JSON}}', '''$PLUGIN_CONFIG_ESCAPED''')
with open('$HIGRESS_CONF_DIR/higress.conf', 'w') as f:
    f.write(content)
"

ok "Higress config: $HIGRESS_CONF_DIR/higress.conf"
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 4: Start Higress + mock MCP server
# ═══════════════════════════════════════════════════════════════
info "Step 4: Start Higress + mock MCP server"

# Remove existing containers
docker rm -f "$HIGRESS_CONTAINER" "$MOCK_MCP_CONTAINER" 2>/dev/null || true

# Create network if it doesn't exist
docker network create "$NETWORK_NAME" 2>/dev/null || true

# Connect existing services to the test network with aliases
info "Connecting existing services to test network..."
docker network connect --alias redis "$NETWORK_NAME" "$REDIS_CONTAINER" 2>/dev/null || true
docker network connect --alias engine "$NETWORK_NAME" virbius-engine 2>/dev/null || true
ok "Redis and Engine connected to $NETWORK_NAME"

# Start mock MCP server
info "Starting mock MCP server..."
docker run -d \
  --name "$MOCK_MCP_CONTAINER" \
  --network "$NETWORK_NAME" \
  --network-alias mock-mcp \
  -v "$MOCK_MCP_SCRIPT:/app/mock_mcp_server.py:ro" \
  -w /app \
  python:3.12-slim \
  python mock_mcp_server.py 2>&1 || {
    err "Failed to start mock MCP server"
    exit 1
  }
ok "Mock MCP server started: $MOCK_MCP_CONTAINER"

# Start Higress
info "Starting Higress..."
docker run -d \
  --name "$HIGRESS_CONTAINER" \
  --network "$NETWORK_NAME" \
  -p 80:80 \
  -p 9901:9901 \
  -v "$WASM_BIN:/wasm/virbius-gateway.wasm:ro" \
  -v "$PLUGIN_CONFIG:/wasm/plugin-config.json:ro" \
  -v "$HIGRESS_CONF_DIR/higress.conf:/root/higress/conf/higress.conf:ro" \
  "$HIGRESS_IMAGE" 2>&1 || {
    err "Failed to start Higress"
    err "Note: If the image pull fails, try: docker pull $HIGRESS_IMAGE"
    exit 1
  }
ok "Higress started: $HIGRESS_CONTAINER"
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 5: Wait for Higress to be ready
# ═══════════════════════════════════════════════════════════════
info "Step 5: Wait for Higress to initialize..."
HIGRESS_READY=false
for i in $(seq 1 30); do
  sleep 2
  # Check if Higress is responding
  if curl -sf -o /dev/null -w "%{http_code}" http://localhost:80/ 2>/dev/null | grep -qE "^[2-5]"; then
    HIGRESS_READY=true
    break
  fi
  # Check if Envoy admin is up
  if curl -sf http://localhost:9901/server_info >/dev/null 2>&1; then
    HIGRESS_READY=true
    break
  fi
  # Check container is still running
  if ! docker ps -q -f "name=$HIGRESS_CONTAINER" | grep -q .; then
    err "Higress container exited unexpectedly"
    err "Logs:"
    docker logs "$HIGRESS_CONTAINER" 2>&1 | tail -30
    exit 1
  fi
  printf "."
done
echo ""

if ! $HIGRESS_READY; then
  warn "Higress did not show ready signal within 60s"
  warn "Checking if it's still starting..."
  docker logs "$HIGRESS_CONTAINER" 2>&1 | tail -20
  warn "Proceeding anyway..."
else
  ok "Higress is ready"
fi

# Show Higress startup logs
info "Higress logs (last 10 lines):"
docker logs "$HIGRESS_CONTAINER" 2>&1 | tail -10 || true
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 6: Pre-populate Redis with cumulative counter
# ═══════════════════════════════════════════════════════════════
info "Step 6: Pre-populate Redis cumulative counter"

# The cumulative counter key format: virbius:cum:{tenant}:{cumulativeName}:{dimensionValue}
# We need to know the cumulative name and dimension used by rule_session_cnt
# Try to get cumulative definitions from Control API
CUMULATIVE_DEF=$(curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/cumulatives" 2>/dev/null || echo "{}")
CUM_NAMES=$(echo "$CUMULATIVE_DEF" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    data = d.get('data', d)
    if isinstance(data, list):
        for c in data:
            print(c.get('cumulative_name', ''))
    elif isinstance(data, dict):
        for k in data:
            print(k)
except:
    print('session_cnt')
" 2>/dev/null || echo "session_cnt")

info "Available cumulative names: $CUM_NAMES"

# Try to find the cumulative name used in the rule body
CUM_NAME=$(echo "$LUA_SCRIPT" | grep -oE 'getCumulative\([^)]+\)' | head -1 | grep -oE '"[^"]+"' | tr -d '"' || echo "session_cnt")
if [[ -z "$CUM_NAME" ]]; then
  CUM_NAME="session_cnt"
fi
info "Using cumulative name: $CUM_NAME"

# Determine dimension (usually session_id for session count rules)
CUM_DIMENSION="session_id"
CUM_VALUE="$TEST_SESSION"

# Calculate the current time slot (minute-level bucket)
# The slot is: epoch_minutes / granularity
CURRENT_SLOT=$(python3 -c "
import time
# Assuming 1-minute granularity
slot = int(time.time() // 60)
print(slot)
")

# Write a high counter value to trigger the rule
# Using Redis HINCRBY to set the counter
CUM_KEY="virbius:cum:$TENANT_ID:$CUM_NAME:$CUM_VALUE"
info "Setting counter: $CUM_KEY field=$CURRENT_SLOT value=150"
docker exec "$REDIS_CONTAINER" redis-cli HSET "$CUM_KEY" "$CURRENT_SLOT" 150
docker exec "$REDIS_CONTAINER" redis-cli EXPIRE "$CUM_KEY" 3600

# Verify
CUM_VAL=$(docker exec "$REDIS_CONTAINER" redis-cli HGET "$CUM_KEY" "$CURRENT_SLOT")
info "Counter value: $CUM_VAL"
ok "Redis cumulative counter pre-populated"
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 7: Send test requests through Higress
# ═══════════════════════════════════════════════════════════════
info "Step 7: Send test requests through Higress (port 80)"

HIGRESS_URL="http://localhost:80"
RULE_TRIGGERED=false

# ─── Test 1: Request with pre-populated high counter → should BLOCK ───
echo ""
echo -e "${YELLOW}  Test 1: Request with counter=150 (should trigger rule_session_cnt)${NC}"
info "  Sending POST /mcp/tools/call with session=$TEST_SESSION"

RESP1=$(curl -s -o /tmp/resp1.json -w "%{http_code}" -X POST "$HIGRESS_URL/mcp/tools/call" \
  -H 'Content-Type: application/json' \
  -H "x-mcp-tool-name: $TEST_TOOL" \
  -H "x-mcp-session-id: $TEST_SESSION" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "read_file",
      "arguments": {"path": "/etc/hostname"}
    },
    "id": 1
  }' 2>&1 || echo "000")

HTTP_CODE1=$(echo "$RESP1" | tail -1)
BODY1=$(cat /tmp/resp1.json 2>/dev/null || echo "")

info "  HTTP Status: $HTTP_CODE1"
info "  Response: $(echo "$BODY1" | head -c 200)"

if [[ "$HTTP_CODE1" == "403" ]]; then
  ok "Test 1 PASSED: Request blocked (403) as expected"
  if echo "$BODY1" | grep -qi "block\|deny\|forbidden"; then
    ok "  Block reason found in response"
  fi
  RULE_TRIGGERED=true
elif [[ "$HTTP_CODE1" == "200" ]]; then
  # Check if engine evaluation blocked it
  if echo "$BODY1" | grep -qi "block\|error.*-32006"; then
    ok "Test 1 PASSED: Request blocked by engine"
    RULE_TRIGGERED=true
  else
    warn "Test 1: Request was allowed (200) — rule may not have triggered"
    warn "  This could mean:"
    warn "    1. WASM expression evaluation didn't match (expression IR issue)"
    warn "    2. Engine evaluation allowed it (cumulative counter read issue)"
    warn "    3. WASM plugin not loaded correctly"
  fi
else
  warn "Test 1: Unexpected HTTP status: $HTTP_CODE1"
fi

# ─── Test 2: Request with a fresh session (counter=0) → should ALLOW ───
echo ""
echo -e "${YELLOW}  Test 2: Request with fresh session (counter=0, should NOT trigger)${NC}"
FRESH_SESSION="fresh-session-$(date +%s)"

RESP2=$(curl -s -o /tmp/resp2.json -w "%{http_code}" -X POST "$HIGRESS_URL/mcp/tools/call" \
  -H 'Content-Type: application/json' \
  -H "x-mcp-tool-name: $TEST_TOOL" \
  -H "x-mcp-session-id: $FRESH_SESSION" \
  -d '{
    "jsonrpc": "2.0",
    "method": "tools/call",
    "params": {
      "name": "read_file",
      "arguments": {"path": "/etc/hostname"}
    },
    "id": 2
  }' 2>&1 || echo "000")

HTTP_CODE2=$(echo "$RESP2" | tail -1)
BODY2=$(cat /tmp/resp2.json 2>/dev/null || echo "")

info "  HTTP Status: $HTTP_CODE2"
info "  Response: $(echo "$BODY2" | head -c 200)"

if [[ "$HTTP_CODE2" == "200" ]]; then
  ok "Test 2 PASSED: Fresh session request allowed (200)"
elif [[ "$HTTP_CODE2" == "403" ]]; then
  warn "Test 2: Fresh session was blocked — unexpected (may be engine evaluation)"
else
  warn "Test 2: Unexpected HTTP status: $HTTP_CODE2"
fi

# ─── Test 3: Send multiple requests to increment counter naturally ───
echo ""
echo -e "${YELLOW}  Test 3: Send 10 requests to increment counter naturally${NC}"
NATURAL_SESSION="natural-$(date +%s)"
info "  Session: $NATURAL_SESSION"

ALLOWED_COUNT=0
BLOCKED_COUNT=0
for i in $(seq 1 10); do
  RESP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$HIGRESS_URL/mcp/tools/call" \
    -H 'Content-Type: application/json' \
    -H "x-mcp-tool-name: $TEST_TOOL" \
    -H "x-mcp-session-id: $NATURAL_SESSION" \
    -d "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/etc/hostname\"}},\"id\":$i}" 2>&1 || echo "000")
  if [[ "$RESP" == "200" ]]; then
    ALLOWED_COUNT=$((ALLOWED_COUNT + 1))
  elif [[ "$RESP" == "403" ]]; then
    BLOCKED_COUNT=$((BLOCKED_COUNT + 1))
  fi
  printf "  Request #%d: HTTP %s\n" "$i" "$RESP"
done

info "  Results: $ALLOWED_COUNT allowed, $BLOCKED_COUNT blocked"

# Check the natural counter in Redis
NATURAL_KEY="virbius:cum:$TENANT_ID:$CUM_NAME:$NATURAL_SESSION"
NATURAL_VAL=$(docker exec "$REDIS_CONTAINER" redis-cli HGET "$NATURAL_KEY" "$CURRENT_SLOT" 2>/dev/null || echo "0")
info "  Natural counter in Redis: $NATURAL_VAL"

if [[ "$ALLOWED_COUNT" -gt 0 ]]; then
  ok "Test 3 PASSED: Some requests were allowed before threshold"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 8: Check Higress/WASM logs
# ═══════════════════════════════════════════════════════════════
info "Step 8: Check Higress/WASM logs"

echo -e "${CYAN}  ── Higress logs (last 30 lines) ──${NC}"
docker logs "$HIGRESS_CONTAINER" 2>&1 | tail -30 || true
echo ""

echo -e "${CYAN}  ── WASM-related log lines ──${NC}"
docker logs "$HIGRESS_CONTAINER" 2>&1 | grep -i "virbius\|wasm\|expr\|block\|deny\|eval" | tail -20 || echo "  (no WASM-related logs found)"
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 9: Verify Redis state
# ═══════════════════════════════════════════════════════════════
info "Step 9: Verify Redis state"

echo -e "${CYAN}  ── Cumulative counters ──${NC}"
echo "  Key: virbius:cum:$TENANT_ID:$CUM_NAME:*"
docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "virbius:cum:$TENANT_ID:$CUM_NAME:*" | while read -r key; do
  echo "  $key:"
  docker exec "$REDIS_CONTAINER" redis-cli HGETALL "$key" | sed 's/^/    /'
done

echo ""
echo -e "${CYAN}  ── Rate limit counters ──${NC}"
docker exec "$REDIS_CONTAINER" redis-cli --scan --pattern "tool:*" | while read -r key; do
  echo "  $key: $(docker exec "$REDIS_CONTAINER" redis-cli GET "$key")"
done

echo ""
echo -e "${CYAN}  ── Gateway artifact pointer ──${NC}"
docker exec "$REDIS_CONTAINER" redis-cli HGETALL "virbius:config:gateway:$TENANT_ID" | sed 's/^/  /' || echo "  (not found)"
echo ""

# ═══════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN} Test Summary                           ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "  Rule:        $RULE_ID"
echo "  Layer:       $RULE_LAYER ($RULE_RUNTIME)"
echo "  Intent:      $RULE_INTENT (risk=$RULE_RISK)"
echo "  Rollout:     $RULE_ROLLOUT"
echo "  Expression:  $(echo "$EXPR_IR" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('expression',{}).get('source','?'))" 2>/dev/null || echo '?')"
echo ""
echo "  Test 1 (high counter → block):  $([ "$RULE_TRIGGERED" = true ] && echo -e "${GREEN}✓ PASSED${NC}" || echo -e "${RED}✗ FAILED${NC}")"
echo "  Test 2 (fresh session → allow): $([ "$HTTP_CODE2" == "200" ] && echo -e "${GREEN}✓ PASSED${NC}" || echo -e "${YELLOW}? CHECK${NC}")"
echo "  Test 3 (natural increment):     $ALLOWED_COUNT allowed / $BLOCKED_COUNT blocked"
echo ""
echo "  Higress:      $HIGRESS_CONTAINER (still running, port 80)"
echo "  Mock MCP:     $MOCK_MCP_CONTAINER (still running)"
echo "  Network:      $NETWORK_NAME"
echo ""
echo "  View Higress logs:  docker logs -f $HIGRESS_CONTAINER"
echo "  Send test request:  curl -X POST http://localhost:80/mcp/tools/call -H 'x-mcp-tool-name: read_file' -H 'x-mcp-session-id: <session>' -H 'Content-Type: application/json' -d '{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"read_file\",\"arguments\":{\"path\":\"/etc/hostname\"}},\"id\":1}'"
echo "  Cleanup:            ./scripts/test-rule-session-cnt-higress.sh --cleanup"
echo ""

# Clean up temp files
rm -f /tmp/resp1.json /tmp/resp2.json

exit 0
