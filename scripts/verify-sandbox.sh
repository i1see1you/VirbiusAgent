#!/bin/bash
# ────────────────────────────────────────────────────────────────
# Verify local code-exec sandbox (shell/execute_python/execute_code/execute_node)
# Run inside the ECS proxy container or from host with docker exec:
#   docker exec <container> bash /path/to/verify-sandbox.sh
# ────────────────────────────────────────────────────────────────
set -euo pipefail

PROXY_URL="${PROXY_URL:-http://127.0.0.1:9090}"
FAIL=0
PASS=0
TMPDIR=$(mktemp -d)
SSE_OUT="$TMPDIR/sse_output"
SSE_PIPE="$TMPDIR/sse_pipe"
SSE_PID_FILE="$TMPDIR/sse.pid"

cleanup() {
    exec 3>&- 2>/dev/null || true
    [ -f "$SSE_PID_FILE" ] && kill "$(cat $SSE_PID_FILE)" 2>/dev/null || true
    rm -rf "$TMPDIR"
}
trap cleanup EXIT

# ── helpers ────────────────────────────────────────────────────

red()   { echo -e "\033[31m$*\033[0m"; }
green() { echo -e "\033[32m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }

_pass() { green "  PASS: $1"; ((PASS++)) || true; }
_fail() { red   "  FAIL: $1"; ((FAIL++)) || true; }

post() {
    local id="$1" method="$2" params="$3"
    local http_code
    http_code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 \
        -X POST "$PROXY_URL/messages/?session_id=$SID" \
        -H "Content-Type: application/json" \
        -d "{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}" 2>&1)
    if [ "$http_code" != "202" ] && [ "$http_code" != "200" ]; then
        red "  (POST $method -> HTTP $http_code)"
    fi
}

sse_wait() {
    # Read next SSE event from pipe (blocks until data arrives)
    local event="" data=""
    while IFS= read -r -t 10 line <&3; do
        line="${line%$'\r'}"  # Strip trailing CR (SSE uses CRLF)
        [[ "$line" =~ ^event: ]] && event="${line#event: }"
        [[ "$line" =~ ^data: ]]  && data="${line#data: }" && break
    done
    echo "$data"
}

sse_wait_json() {
    local raw
    raw=$(sse_wait)
    if [ -z "$raw" ]; then
        echo '{"_raw":"","_error":"sse_wait timeout (no data)"}'
    else
        echo "$raw" | jq -c . 2>/dev/null || echo '{"_raw":"'"$raw"'"}'
    fi
}

# ── 0. pre-checks ──────────────────────────────────────────────

bold "0. Environment"
echo

echo -n "  Proxy reachable ... "
proxy_ok=false
for i in 1 2 3 4 5; do
    if curl -sf --max-time 3 "$PROXY_URL/sse" -o /dev/null -w '%{http_code}' 2>/dev/null | grep -q 200; then
        proxy_ok=true
        break
    fi
    sleep 2
done
if $proxy_ok; then
    green "OK ($PROXY_URL)"
else
    _fail "cannot reach $PROXY_URL"
fi

echo -n "  ALLOW_UNSANDBOXED = "
ALLOW_UNSANDBOXED="${VIRBIUS_ALLOW_UNSANDBOXED:-}"
if [ -z "$ALLOW_UNSANDBOXED" ]; then ALLOW_UNSANDBOXED="(unset → default true)"; fi
echo "$ALLOW_UNSANDBOXED"

echo -n "  DEGRADE_MODE = "
DEGRADE_MODE="${VIRBIUS_SANDBOX_DEGRADE_MODE:-}"
if [ -z "$DEGRADE_MODE" ]; then DEGRADE_MODE="(unset → default report)"; fi
echo "$DEGRADE_MODE"

RUNSC=$(which runsc 2>/dev/null || echo "NOT FOUND")
echo "  runsc        = $RUNSC"
G_POOL=$(dirname "$(which runsc 2>/dev/null || echo /none)")/..
echo

# ── 1. establish SSE session ───────────────────────────────────

bold "1. Establish SSE session"
echo

mkfifo "$SSE_PIPE" 2>/dev/null || true
curl -s -N "$PROXY_URL/sse" > "$SSE_PIPE" &
echo $! > "$SSE_PID_FILE"

# Open pipe for reading on fd 3 (stays open for script lifetime,
# keeping curl alive and avoiding SIGPIPE on each sse_wait call)
exec 3< "$SSE_PIPE"

# endpoint event
DATA=$(sse_wait)
SID=$(echo "$DATA" | sed -n 's/.*session_id=\([^&]*\).*/\1/p')
if [ -n "$SID" ]; then
    _pass "session_id=$SID"
else
    _fail "cannot parse session_id from SSE endpoint event: $DATA"; exit 1
fi

# ── 2. initialize ──────────────────────────────────────────────

bold "2. Initialize"
echo

post 1 initialize '{"_meta":{"app_id":"virbius-test"}}'
RESP=$(sse_wait_json)
echo "  $RESP"

if echo "$RESP" | jq -e '.result.serverInfo' > /dev/null 2>&1; then
    _pass "initialize ok"
else
    _fail "initialize failed"; exit 1
fi

# ── 3. tools/list ──────────────────────────────────────────────

bold "3. tools/list — local tools injection"
echo

post 2 tools/list '{}'
RESP_RAW=$(sse_wait)
RESP=$(echo "$RESP_RAW" | jq -c . 2>/dev/null || echo '{}')

TOOLS=$(echo "$RESP" | jq -r '.result.tools[]?.name' 2>/dev/null || echo "")

for t in shell execute_python execute_code execute_node; do
    echo -n "  $t ... "
    if echo "$TOOLS" | grep -qx "$t"; then
        green "present"
    else
        _fail "missing in tools/list"
    fi
done

echo

# ── 4. sandbox execution tests ─────────────────────────────────

bold "4. Sandbox execution"
echo

test_exec() {
    local label="$1" tool="$2" param_name="$3" code="$4" expect="$5"
    echo -n "  $label ... "
    post 10 "tools/call" "{\"name\":\"$tool\",\"arguments\":{\"$param_name\":\"$code\"}}"
    RESP=$(sse_wait)
    RESULT=$(echo "$RESP" | jq -c '.result' 2>/dev/null || echo '{}')
    IS_ERROR=$(echo "$RESULT" | jq -r '.isError' 2>/dev/null || echo '')
    TEXT=$(echo "$RESULT" | jq -r '.content[0].text' 2>/dev/null || echo '')
    # Also check JSON-RPC error response (e.g. timeout)
    ERR_MSG=$(echo "$RESP" | jq -r '.error.message // empty' 2>/dev/null || echo '')
    if [ -n "$ERR_MSG" ]; then
        TEXT="$ERR_MSG"
        IS_ERROR="true"
    fi
    DEGRADED=$(echo "$RESULT" | jq -r '._meta.degraded // empty' 2>/dev/null || echo '')
    SBOX_USED=$(echo "$RESULT" | jq -r '._meta.sandbox_used // empty' 2>/dev/null || echo '')

    case "$expect" in
        "ok")
            if [ "$IS_ERROR" != "true" ]; then _pass "$label -> exit ok"; else _fail "$label -> isError=true: $TEXT"; fi
            ;;
        "fail"|"deny")
            if echo "$TEXT" | grep -qi "fail\|denied\|deny\|unavailable\|not allowed\|timed out\|timeout"; then _pass "$label -> denied: $TEXT"; else _fail "$label -> expected deny but got: $TEXT"; fi
            ;;
    esac
    echo "     sandbox_used=$SBOX_USED degraded=$DEGRADED text=\"${TEXT:0:80}\""
}

# ── 4a. shell with sandbox_type=none (default) ──
echo "  --- shell (none, hostname) ---"
test_exec "shell hostname" shell command "hostname" "ok"

echo "  --- shell (none, timeout test: sleep 40) ---"
# Should be killed after ~30s timeout
test_exec "shell timeout" shell command "sleep 40" "fail"

# ── 4b. execute_python (none) ──
echo "  --- execute_python (none, print) ---"
test_exec "python print" execute_python code "print('hello sandbox')" "ok"

# ── 4c. execute_code (none) ──
echo "  --- execute_code (none) ---"
test_exec "execute_code print" execute_code code "print('from execute_code')" "ok"

# ── 4d. execute_node (none) ──
echo "  --- execute_node (none) ---"
test_exec "node console" execute_node code "console.log('node sandbox ok')" "ok"

echo

# ── 5. upstream tools unaffected ───────────────────────────────

bold "5. Upstream tool unaffected"
echo

# Call a typical upstream tool (search). If it exists in tools/list.
if echo "$TOOLS" | grep -q "search"; then
    echo -n "  search ... "
    post 20 "tools/call" '{"name":"search","arguments":{"query":"test"}}'
    RESP=$(sse_wait)
    if echo "$RESP" | jq -e '.result' > /dev/null 2>&1; then
        _pass "upstream search works"
    else
        ERROR=$(echo "$RESP" | jq -r '.error.message // "unknown"' 2>/dev/null || echo '?')
        _fail "upstream search failed: $ERROR"
    fi
else
    _pass "upstream: no 'search' tool (skip)"
fi

echo

# ── 6. check proxy logs ───────────────────────────────────────

bold "6. Logs (last 5 sandbox-related lines)"
echo

# Adapt log path for your deployment
LOG_FILE="${VIRBIUS_LOG_FILE:-/var/log/virbius/mcp-proxy.log}"
CONTAINER_LOG_FILE="${VIRBIUS_LOG_FILE:-/var/log/virbius/control.log}"

for f in "$LOG_FILE" /proc/1/fd/1 /dev/stderr; do
    if [ -f "$f" ] || [ -p "$f" ]; then
        echo "  sandbox log lines ($f):"
        grep -i "sandbox\|local.exec\|degraded\|unsandboxed" "$f" 2>/dev/null | tail -5 || echo "  (none)"
        break
    fi
done

echo

# ── 7. summary ─────────────────────────────────────────────────

bold "7. Summary"
echo
green "  Passed : $PASS"
red   "  Failed : $FAIL"
echo

if [ "$FAIL" -eq 0 ]; then
    green "All checks passed!"
    exit 0
else
    red "Some checks FAILED — review the output above."
    exit 1
fi
