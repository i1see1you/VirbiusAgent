#!/usr/bin/env bash
# test-m2-native-mcp-e2e.sh
#
# M2 native-MCP integration E2E (spike productized, 2026-11-27):
#   Phase 0  preflight: docker, jq, python3 (>=3.10), control jar, proxy image
#   Phase 1  redis + control (compose, like the sandbox full-chain e2e)
#   Phase 2  issue canonical-grant license via ops-console API; fetch public key
#   Phase 3  proxy container with VIRBIUS_LICENSE_FILE fallback (no _meta license)
#   Phase 4  framework-native MCP clients (LangChain / OpenAI Agents):
#            tool discovery, schema, policy-denial error mapping, concurrency
#   Phase 5  in-flight disconnect via a TCP MITM that drops the tools/call
#            response: both clients must fail fast and NEVER re-send the call
#            (no duplicate execution)
#
# Usage:
#   ./scripts/test-m2-native-mcp-e2e.sh
#   ./scripts/test-m2-native-mcp-e2e.sh --cleanup
#   SKIP_M2=1 ./scripts/test-m2-native-mcp-e2e.sh   # infrastructure checks only
#
# Requires network access for `pip install langchain-mcp-adapters openai-agents`
# (test venv). Without it the script skips with a clear marker.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info() { echo -e "${CYAN}[STEP]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()  { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

TENANT="docker-m2-e2e"
BASE="http://127.0.0.1:18080"
PROXY_URL="http://127.0.0.1:19090"
MITM_URL="http://127.0.0.1:19091"
PROJECT="virbius-m2-native-e2e"
COMPOSE_DIR="scripts/sandbox-full-chain"
PROXY_NAME="virbius-m2-e2e-proxy"
MITM_NAME="virbius-m2-e2e-mitm"
PROXY_IMAGE="virbius-mcp-proxy:sandbox-e2e"
APP_VER="$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
JAR="$ROOT/virbius-control/target/virbius-control-${APP_VER}.jar"
SPIKE_DIR="$(mktemp -d /tmp/virbius-m2-e2e.XXXXXX)"
PY=""

COMPOSE=(docker compose -f "$COMPOSE_DIR/docker-compose.yml" --project-name "$PROJECT")

cleanup() {
  docker rm -f "$PROXY_NAME" "$MITM_NAME" >/dev/null 2>&1 || true
  "${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
  lsof -ti :19091 2>/dev/null | xargs kill -9 2>/dev/null || true
  rm -rf "$SPIKE_DIR"
}
trap cleanup EXIT

if [[ "${1:-}" == "--cleanup" ]]; then
  cleanup
  echo "cleaned"
  exit 0
fi

echo -e "${CYAN}============================================================${NC}"
echo -e "${CYAN} M2 native-MCP integration E2E (LangChain / OpenAI Agents)${NC}"
echo -e "${CYAN}============================================================${NC}"

# ─── Phase 0 ────────────────────────────────────────────────────────────────
info "0. preflight"
command -v jq >/dev/null || err "jq not found"
command -v python3 >/dev/null || err "python3 not found"
docker info >/dev/null 2>&1 || err "docker daemon not running"
[[ -f "$JAR" ]] || { info "building control jar (mvn package -DskipTests)..."; mvn -q package -DskipTests -pl virbius-control -am; }
[[ -f "$JAR" ]] || err "control jar missing"
if ! docker image inspect "$PROXY_IMAGE" >/dev/null 2>&1; then
  info "building proxy image (first run; cargo build takes a while)..."
  docker build --target virbius-mcp-proxy -t "$PROXY_IMAGE" \
    --build-arg HTTP_PROXY= --build-arg HTTPS_PROXY= \
    --build-arg http_proxy= --build-arg https_proxy= \
    --build-arg NO_PROXY="*" --build-arg no_proxy="*" "$ROOT"
fi
ok "control jar + proxy image ready"

info "0b. python venv with framework clients"
PYBIN=""
for cand in python3.13 python3.12 python3.11 python3; do
  if command -v "$cand" >/dev/null 2>&1; then
    ver=$("$cand" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
    case "$ver" in 3.1[0-9]|3.[2-9][0-9]) PYBIN="$cand"; break;; esac
  fi
done
[[ -n "$PYBIN" ]] || err "python3 >= 3.10 required for langchain-mcp-adapters/openai-agents"
"$PYBIN" -m venv "$SPIKE_DIR/venv"
if ! "$SPIKE_DIR/venv/bin/pip" install -q --disable-pip-version-check langchain-mcp-adapters openai-agents 2>"$SPIKE_DIR/pip.err"; then
  if [[ "${SKIP_M2:-}" == "1" ]]; then
    warn "pip install failed and SKIP_M2=1 — infrastructure-only run"
    PY=""
  else
    err "pip install failed (network?): $(tail -2 "$SPIKE_DIR/pip.err")"
  fi
else
  PY="$SPIKE_DIR/venv/bin/python"
  ok "venv ready ($( "$SPIKE_DIR/venv/bin/pip" list 2>/dev/null | grep -E '^(langchain-mcp-adapters|openai-agents|mcp) ' | tr '\n' ' '))"
fi

# ─── Phase 1 ────────────────────────────────────────────────────────────────
info "1. start redis + control"
"${COMPOSE[@]}" down -v --remove-orphans >/dev/null 2>&1 || true
docker rm -f "$PROXY_NAME" "$MITM_NAME" >/dev/null 2>&1 || true
APP_VER="$APP_VER" "${COMPOSE[@]}" up -d --wait redis >/dev/null
APP_VER="$APP_VER" "${COMPOSE[@]}" up -d control >/dev/null
for i in $(seq 1 60); do
  curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 && break
  sleep 3
  [[ $i == 60 ]] && { "${COMPOSE[@]}" logs control | tail -20; err "control not healthy"; }
done
ok "control healthy ($BASE)"

# ─── Phase 2 ────────────────────────────────────────────────────────────────
info "2. issue canonical-grant license + fetch public key (ops-console API)"
RESP=$(curl -s -X POST "$BASE/api/v1/admin/tenants/$TENANT/licenses/issue" \
  -H 'Content-Type: application/json' -H 'X-User-Id: m2-e2e' \
  -d '{"app_id":"m2-e2e-agent","agent_name":"M2 Native E2E","allowed_tools":["virbius__shell","virbius__python"],"risk_quota":100,"tool_rate_limit":100,"expiry_seconds":3600}')
echo "$RESP" | jq -e '.code == 0' >/dev/null || err "license issue failed: $RESP"
echo "$RESP" | jq -r '.data.jwt' > "$SPIKE_DIR/license.jwt"
[[ -s "$SPIKE_DIR/license.jwt" ]] || err "empty license jwt"
curl -s "$BASE/api/v1/admin/tenants/$TENANT/licenses/public-key" \
  | jq -r '.data.public_key_pem' > "$SPIKE_DIR/license.pub.pem"
[[ -s "$SPIKE_DIR/license.pub.pem" ]] || err "empty public key"
# audit_only fallback (real operator flow): a canonical-grant call passes the
# gate and pipeline, then fails closed at the absent runsc (deterministic
# -32018 sandbox_unavailable denial for the error-mapping assertions).
curl -s -X PUT "$BASE/api/v1/admin/tenants/$TENANT/proxy-policy" \
  -H 'Content-Type: application/json' -H 'X-User-Id: m2-e2e' \
  -d '{"session_ttl_secs":3600,"fallback_policy":"audit_only","failover":{"low_risk_fail_open":true,"high_risk_fail_closed":false,"engine_timeout_ms":1000}}' \
  | jq -e '.code == 0' >/dev/null || err "proxy-policy publish failed"
ok "license issued (fallback-file mode: clients send NO _meta/license)"

# ─── Phase 3 ────────────────────────────────────────────────────────────────
info "3. proxy container with VIRBIUS_LICENSE_FILE fallback"
docker run -d --name "$PROXY_NAME" --network "${PROJECT}_default" -p 19090:9090 \
  -v "$SPIKE_DIR/license.pub.pem:/etc/virbius/license.pub.pem:ro" \
  -v "$SPIKE_DIR/license.jwt:/etc/virbius/license.jwt:ro" \
  -e VIRBIUS_TRANSPORT="tcp://0.0.0.0:9090" \
  -e VIRBIUS_UPSTREAM_URL="" \
  -e VIRBIUS_LOCAL_EXEC_MODE="gvisor" \
  -e VIRBIUS_REDIS_URL="redis://redis:6379" \
  -e VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license.pub.pem \
  -e VIRBIUS_LICENSE_FILE=/etc/virbius/license.jwt \
  -e HTTP_PROXY= -e HTTPS_PROXY= -e http_proxy= -e https_proxy= \
  "$PROXY_IMAGE" >/dev/null
for i in $(seq 1 30); do
  curl -sf --max-time 2 "$PROXY_URL/health" >/dev/null 2>&1 && break
  sleep 1
  [[ $i == 30 ]] && { docker logs "$PROXY_NAME" | tail -20; err "proxy not healthy"; }
done
ok "proxy healthy ($PROXY_URL)"

# ─── Phase 4 + 5 ────────────────────────────────────────────────────────────
if [[ -z "$PY" ]]; then
  warn "no framework venv — stopping after infrastructure checks (SKIP_M2=1)"
  exit 0
fi

cat > "$SPIKE_DIR/m2_native_test.py" <<'PYEOF'
import asyncio, json, subprocess, sys, time, urllib.request

DIRECT = "http://127.0.0.1:19090/sse"
MITM = "http://127.0.0.1:19091/sse"
SPIKE = sys.argv[1]
FAILS = []

def check(label, cond, detail=""):
    print(("PASS " if cond else "FAIL ") + label + ((" | " + detail) if detail else ""))
    if not cond:
        FAILS.append(label)

def wait_health(timeout=30):
    for _ in range(timeout * 2):
        try:
            with urllib.request.urlopen("http://127.0.0.1:19090/health", timeout=1) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(0.5)
    return False

# ---------- Phase 4: LangChain ----------
from langchain_mcp_adapters.client import MultiServerMCPClient

async def langchain():
    client = MultiServerMCPClient({"virbius": {"transport": "sse", "url": DIRECT}})
    tools = await client.get_tools()
    names = [t.name for t in tools]
    check("LC discovery canonical only", "virbius__shell" in names and "virbius__python" in names
          and "shell" not in names and "execute_python" not in names, str(names))
    shell = [t for t in tools if t.name == "virbius__shell"][0]
    try:
        await shell.ainvoke({"command": "echo hi"})
        check("LC denial surfaces exception", False, "denial returned success?!")
    except Exception as e:
        msg = str(e)
        check("LC denial error mapping", "McpError" in type(e).__name__
              and ("blocked by policy" in msg or "gVisor" in msg or "sandbox" in msg
                   or "local" in msg), f"{type(e).__name__}: {msg[:80]}")
    async def c(i):
        try:
            await shell.ainvoke({"command": f"echo {i}"})
            return ("ok",)
        except Exception as e:
            return ("exc", type(e).__name__)
    rs = await asyncio.gather(c(1), c(2), c(3))
    check("LC concurrent calls stable", all(r[0] == "exc" for r in rs), str(rs))
    return names

# ---------- Phase 4: OpenAI Agents ----------
from agents.mcp import MCPServerSse

async def openai_agents():
    async with MCPServerSse(params={"url": DIRECT}) as server:
        tools = await server.list_tools()
        names = [t.name for t in tools]
        check("OA discovery canonical only", "virbius__shell" in names
              and "shell" not in names and "execute_python" not in names, str(names))
        try:
            await server.call_tool("virbius__shell", {"command": "echo hi"})
            check("OA denial surfaces exception", False, "denial returned success?!")
        except Exception as e:
            msg = str(e)
            check("OA denial error mapping", "McpError" in type(e).__name__, f"{msg[:80]}")
        async def c(i):
            try:
                await server.call_tool("virbius__shell", {"command": f"echo {i}"})
                return ("ok",)
            except Exception as e:
                return ("exc", type(e).__name__)
        rs = await asyncio.gather(c(1), c(2), c(3))
        check("OA concurrent calls stable", all(r[0] == "exc" for r in rs), str(rs))

# ---------- Phase 5: in-flight disconnect via MITM ----------
from mcp import ClientSession
from mcp.client.sse import sse_client

async def disconnect_mitm():
    async with sse_client(MITM) as (r, w):
        async with ClientSession(r, w) as session:
            await asyncio.wait_for(session.initialize(), timeout=15)
            t0 = time.time()
            try:
                await asyncio.wait_for(
                    session.call_tool("virbius__shell", {"command": "echo hi"}), timeout=20)
                check("MITM in-flight fail-fast", False, "call returned after response drop?!")
            except Exception as e:
                elapsed = time.time() - t0
                check("MITM in-flight fail-fast", elapsed < 10,
                      f"{type(e).__name__} in {elapsed:.1f}s")
    await asyncio.sleep(2)
    try:
        with open(f"{SPIKE}/mitm_stats.json") as f:
            s = json.load(f)
    except FileNotFoundError:
        s = {}
    check("MITM response dropped once", s.get("responses_dropped", 0) >= 1, json.dumps(s))
    check("MITM no re-send after drop", s.get("posts_after_drop", 0) == 0, json.dumps(s))

async def main():
    await langchain()
    await openai_agents()
    await disconnect_mitm()

asyncio.run(main())
print("M2_NATIVE_RESULT:", "PASS" if not FAILS else f"FAIL({len(FAILS)}): {FAILS}")
sys.exit(0 if not FAILS else 1)
PYEOF

cat > "$SPIKE_DIR/mitm.py" <<'PYEOF'
import asyncio, re, json

UPSTREAM = ("127.0.0.1", 19090)
stats = {"tools_call_posts": 0, "responses_dropped": 0, "posts_after_drop": 0, "dropped": False}
CALL_ID_RE = re.compile(rb'"id"\s*:\s*(\d+)')
PENDING = set()

async def pipe(reader, writer, on_data=None):
    try:
        while True:
            data = await reader.read(65536)
            if not data:
                break
            if on_data and on_data(data) == "drop":
                try: writer.close()
                except Exception: pass
                return
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try: writer.close()
        except Exception: pass

class Conn:
    def __init__(self):
        self.is_sse = False
    def on_client(self, data):
        if b"tools/call" in data:
            m = CALL_ID_RE.search(data)
            if stats["dropped"]:
                stats["posts_after_drop"] += 1
            elif m:
                PENDING.add(int(m.group(1)))
                stats["tools_call_posts"] += 1
        return "forward"
    def on_upstream(self, data):
        if self.is_sse and PENDING:
            m = CALL_ID_RE.search(data)
            if m and int(m.group(1)) in PENDING and (b'"result"' in data or b'"error"' in data):
                stats["responses_dropped"] += 1
                stats["dropped"] = True
                return "drop"
        return "forward"

async def dump():
    import sys
    while True:
        with open(sys.argv[1], "w") as f:
            json.dump(stats, f)
        await asyncio.sleep(0.3)

async def handle(cr, cw):
    ur, uw = await asyncio.open_connection(*UPSTREAM)
    conn = Conn()
    first = await cr.read(65536)
    if first.startswith(b"GET /sse"):
        conn.is_sse = True
    if first:
        conn.on_client(first)
        uw.write(first)
        await uw.drain()
    await asyncio.gather(
        pipe(cr, uw, on_data=conn.on_client),
        pipe(ur, cw, on_data=conn.on_upstream),
    )

async def main():
    srv = await asyncio.start_server(handle, "127.0.0.1", 19091)
    asyncio.create_task(dump())
    async with srv:
        await srv.serve_forever()

asyncio.run(main())
PYEOF

info "4+5. framework-native discovery/denial/concurrency + MITM disconnect"
# MITM runs on the HOST (pure-stdlib python3); it forwards to the proxy and
# drops the first tools/call response to simulate in-flight disconnect.
nohup python3 "$SPIKE_DIR/mitm.py" "$SPIKE_DIR/mitm_stats.json" > "$SPIKE_DIR/mitm.log" 2>&1 &
MITM_PID=$!
sleep 1

set +e
"$PY" "$SPIKE_DIR/m2_native_test.py" "$SPIKE_DIR" 2>&1 | grep -vE "Warning|ExceptionGroup|Traceback|File \"|^ +|\^" | tail -30
RC=${PIPESTATUS[0]}
set -e
kill "$MITM_PID" 2>/dev/null || true
lsof -ti :19091 2>/dev/null | xargs kill -9 2>/dev/null || true

if [[ "$RC" == "0" ]]; then
  ok "M2 native-MCP E2E: all checks passed"
else
  err "M2 native-MCP E2E failed (rc=$RC)"
fi