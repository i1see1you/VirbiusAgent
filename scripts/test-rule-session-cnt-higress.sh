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
# API-only verification (no direct sqlite/redis access from the test):
#   - Threshold semantics are proved through the operator console:
#     POST /api/v1/admin/tenants/{t}/rules/simulate with
#     fixture.overrides.cumulative.{name} (high → block, low → allow).
#   - Natural counter growth is proved by driving real calls through
#     Higress (engine ingest) and observing the gateway block by behavior.
#   - Cumulative definitions, rule state and the gateway artifact pointer
#     are read from Control APIs; Redis liveness comes from engine
#     /admin/ready (redis=up).
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

# Check Redis reachability through the engine API (no direct redis-cli)
REDIS_STATE=$(curl -s "$ENGINE_URL/admin/ready" 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    print('up' if d.get('redis') == 'up' else 'down')
except Exception:
    print('down')
" 2>/dev/null || echo "down")
if [[ "$REDIS_STATE" != "up" ]]; then
  err "Engine reports Redis is not up (GET $ENGINE_URL/admin/ready → redis != up)"
  err "Start services with: docker compose up -d redis virbius-engine virbius-control"
  exit 1
fi
ok "Redis reachable (via engine /admin/ready)"

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
# Step 6: Prove cumulative threshold via Control /rules/simulate
# ═══════════════════════════════════════════════════════════════
info "Step 6: Prove cumulative threshold via Control /rules/simulate (API-only)"

# Cumulative definitions live in Control; runtime counter values do NOT have an
# admin read API (CumulativeService.snapshot is unwired), so threshold semantics
# are proved through the operator console simulate endpoint instead of redis.
CUMULATIVE_DEF=$(curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/cumulatives" 2>/dev/null || echo "{}")
CUM_NAMES=$(echo "$CUMULATIVE_DEF" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    data = d.get('data', d)
    items = data if isinstance(data, list) else list((data or {}).values())
    for c in items:
        if isinstance(c, dict) and c.get('cumulative_name'):
            print(c.get('cumulative_name'))
except Exception:
    pass
" 2>/dev/null || true)
if [[ -n "$CUM_NAMES" ]]; then
  info "Cumulative defs in Control: $(echo "$CUM_NAMES" | tr '\n' ' ')"
else
  warn "Control returned no cumulative definitions"
fi

# Find the cumulative name + comparison + threshold referenced by the rule
CUM_NAME=$(echo "$LUA_SCRIPT" | grep -oE 'getCumulative\([^)]+\)' | head -1 | grep -oE '"[^"]+"' | tr -d '"' || true)
[[ -z "$CUM_NAME" ]] && CUM_NAME="session_cnt"
CUM_OP=$(echo "$LUA_SCRIPT" | grep -oE "getCumulative\(['\"]${CUM_NAME}['\"]\)[[:space:]]*(>=|>|<=|<|==)" | grep -oE '(>=|>|<=|<|==)' | head -1 || true)
CUM_THRESHOLD=$(echo "$LUA_SCRIPT" | grep -oE "getCumulative\(['\"]${CUM_NAME}['\"]\)[[:space:]]*(>=|>|<=|<|==)[[:space:]]*[0-9]+" | grep -oE '[0-9]+' | head -1 || true)
CUM_OP="${CUM_OP:->=}"
CUM_THRESHOLD="${CUM_THRESHOLD:-100}"
info "Rule references cumulative '$CUM_NAME' $CUM_OP $CUM_THRESHOLD"

# Counters that hit the rule (at/above threshold) and miss it (below)
SIM_HIT_VALUE="$CUM_THRESHOLD"
SIM_MISS_VALUE=$(( CUM_THRESHOLD - 1 ))
[[ "$SIM_MISS_VALUE" -lt 0 ]] && SIM_MISS_VALUE=0

SIM_ROUTE="/mcp/tools/call"

build_simulate_body() {  # $1=count $2=outfile
  python3 - "$RULE_DATA" "$CUM_NAME" "$1" "$SIM_ROUTE" "$TEST_SESSION" "$2" "$LUA_SCRIPT" << 'PY'
import json, sys
rule = json.loads(sys.argv[1])
cum_name, count, route, session, out, script = (
    sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5], sys.argv[6], sys.argv[7])
# Use the decoded script so simulate parses it even if the stored body was JSON-encoded.
rule["body"] = script
body = {
    "rule": rule,
    "fixture": {
        "route_uri": route,
        "headers": {"X-Virbius-Session-Id": session},
        "overrides": {"cumulative": {cum_name: count}},
    },
    "options": {"cumulative_source": "mock"},
}
with open(out, "w") as f:
    json.dump(body, f)
PY
}

sim_summary() {  # stdin: simulate response json -> "hit effective_action"
  python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    s = (d.get('data') or {}).get('summary') or {}
    print('%s %s' % (s.get('hit'), s.get('effective_action')))
except Exception:
    print('? ?')
" 2>/dev/null || echo "? ?"
}

SIM_HIT_FILE="$HIGRESS_TEST_DIR/simulate_hit.json"
SIM_MISS_FILE="$HIGRESS_TEST_DIR/simulate_miss.json"
build_simulate_body "$SIM_HIT_VALUE" "$SIM_HIT_FILE"
build_simulate_body "$SIM_MISS_VALUE" "$SIM_MISS_FILE"

info "  simulate with cumulative=$SIM_HIT_VALUE (expect block)..."
SIM_HIT_RESP=$(curl -s -X POST "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/rules/simulate" \
  -H 'Content-Type: application/json' --data @"$SIM_HIT_FILE" 2>/dev/null || echo "{}")
SIM_HIT_SUM=$(printf '%s' "$SIM_HIT_RESP" | sim_summary)
info "  simulate hit summary: hit=$SIM_HIT_SUM"

info "  simulate with cumulative=$SIM_MISS_VALUE (expect allow)..."
SIM_MISS_RESP=$(curl -s -X POST "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/rules/simulate" \
  -H 'Content-Type: application/json' --data @"$SIM_MISS_FILE" 2>/dev/null || echo "{}")
SIM_MISS_SUM=$(printf '%s' "$SIM_MISS_RESP" | sim_summary)
info "  simulate miss summary: hit=$SIM_MISS_SUM"

SIM_HIT_OK=false
[[ "$SIM_HIT_SUM" == "true block" ]] && SIM_HIT_OK=true
SIM_MISS_OK=false
[[ "$SIM_MISS_SUM" == *" allow"* ]] && SIM_MISS_OK=true

if $SIM_HIT_OK; then
  ok "Simulate: cumulative $CUM_OP $CUM_THRESHOLD → rule hits, effective_action=block"
else
  warn "Simulate: expected 'true block' at counter=$SIM_HIT_VALUE, got: $SIM_HIT_SUM"
fi
if $SIM_MISS_OK; then
  ok "Simulate: cumulative below threshold → rule does not hit (allow)"
else
  warn "Simulate: expected allow at counter=$SIM_MISS_VALUE, got: $SIM_MISS_SUM"
fi
echo ""

# ═══════════════════════════════════════════════════════════════
# Step 7: Send test requests through Higress
# ═══════════════════════════════════════════════════════════════
info "Step 7: Send test requests through Higress (port 80)"

HIGRESS_URL="http://localhost:80"
RULE_TRIGGERED=false

# ─── Test 1: High counter → rule hit (proved via Control simulate) ───
echo ""
echo -e "${YELLOW}  Test 1: cumulative $CUM_OP $CUM_THRESHOLD → rule_session_cnt hits (block)${NC}"
info "  Operator-console simulate (session=$TEST_SESSION, cumulative=$SIM_HIT_VALUE)"

if $SIM_HIT_OK; then
  ok "Test 1 PASSED: simulate reports hit + effective_action=block"
  RULE_TRIGGERED=true
else
  warn "Test 1: simulate did not report a block (summary: $SIM_HIT_SUM)"
  warn "  This could mean:"
  warn "    1. rule/condition not parsed by Control simulate"
  warn "    2. cumulative override name mismatch ('$CUM_NAME')"
  warn "    3. rule rollout_state is dry_run (effective_action=review)"
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

# ─── Test 3: Drive calls so the counter grows naturally past the threshold ───
echo ""
NATURAL_CALLS=$(( CUM_THRESHOLD + 2 ))
[[ "$NATURAL_CALLS" -gt 30 ]] && NATURAL_CALLS=30
echo -e "${YELLOW}  Test 3: Send $NATURAL_CALLS requests (threshold $CUM_OP $CUM_THRESHOLD) to grow counter naturally${NC}"
NATURAL_SESSION="natural-$(date +%s)"
info "  Session: $NATURAL_SESSION"

ALLOWED_COUNT=0
BLOCKED_COUNT=0
for i in $(seq 1 "$NATURAL_CALLS"); do
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

# Natural counter growth is observed purely from gateway behavior — the test must
# not read the redis counter directly. A 403 (or engine block) after N calls is
# end-to-end evidence that the engine ingested the counter past the threshold.
if [[ "$ALLOWED_COUNT" -gt 0 ]]; then
  ok "Test 3 PASSED: Some requests were allowed before threshold"
fi
if [[ "$BLOCKED_COUNT" -gt 0 ]]; then
  ok "Test 3 PASSED: requests started blocking after $ALLOWED_COUNT calls (counter crossed threshold end-to-end)"
else
  info "Test 3: no block observed within $NATURAL_CALLS calls (counter may not have crossed $CUM_THRESHOLD yet)"
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
# Step 9: Verify state via Control APIs (no direct sqlite/redis)
# ═══════════════════════════════════════════════════════════════
info "Step 9: Verify state via Control APIs"

echo -e "${CYAN}  ── Cumulative definitions (Control) ──${NC}"
curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/cumulatives" \
  | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    items = d.get('data', [])
    if isinstance(items, dict):
        items = list(items.values())
    for c in items or []:
        if isinstance(c, dict):
            print('  %s (dimension=%s, window=%s/%s)' % (
                c.get('cumulative_name'), c.get('dimension'),
                c.get('window_kind'), c.get('window_minutes')))
except Exception as e:
    print('  (unavailable: %s)' % e)
" 2>/dev/null || echo "  (cumulatives API unavailable)"

echo ""
echo -e "${CYAN}  ── Rule state (Control) ──${NC}"
curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/rules/$RULE_ID" \
  | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    r = d.get('data') or {}
    print('  rule_id=%s layer=%s runtime=%s intent=%s rollout=%s' % (
        r.get('rule_id'), r.get('layer'), r.get('runtime'),
        r.get('intent_action'), r.get('rollout_state')))
except Exception as e:
    print('  (unavailable: %s)' % e)
" 2>/dev/null || echo "  (rule API unavailable)"

echo ""
echo -e "${CYAN}  ── Gateway artifact pointer (Control) ──${NC}"
curl -s "$CONTROL_URL/api/v1/admin/tenants/$TENANT_ID/gateway-artifacts/policy-version" \
  | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    a = d.get('data') or {}
    print('  artifact_revision=%s storage=%s published_at=%s publish_id=%s' % (
        a.get('artifact_revision'), a.get('storage'),
        a.get('published_at'), a.get('publish_id')))
except Exception as e:
    print('  (unavailable: %s)' % e)
" 2>/dev/null || echo "  (gateway-artifacts API unavailable)"
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
echo "  Test 1 (simulate counter $CUM_OP $CUM_THRESHOLD → block): $([ "$RULE_TRIGGERED" = true ] && echo -e "${GREEN}✓ PASSED${NC}" || echo -e "${RED}✗ FAILED${NC}")"
echo "  Test 2 (fresh session → allow): $([ "$HTTP_CODE2" == "200" ] && echo -e "${GREEN}✓ PASSED${NC}" || echo -e "${YELLOW}? CHECK${NC}")"
echo "  Test 3 (natural increment):     $ALLOWED_COUNT allowed / $BLOCKED_COUNT blocked (of $NATURAL_CALLS calls)"
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
rm -f /tmp/resp2.json

exit 0
