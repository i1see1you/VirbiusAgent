#!/usr/bin/env bash
# =============================================================================
# Onboarding full-flow E2E — the REAL user flow for today's CLI additions:
#
#   virbius-mcp-proxy enroll   (one command: tenant -> tools -> license -> pubkey)
#     -> agent-license.jwt + license_pub.pem
#   virbius-mcp-proxy doctor    (pre-flight: config + key + license + connectivity)
#   python -m virbius_mcp_python doctor   (in-process readiness)
#
# The license issuance is asserted **through the control (operator) HTTP API**
# (/licenses/app-ids, /licenses/list, /tools) — this script never opens the
# control SQLite file and never runs redis-cli. `enroll` itself talks to control
# over the same admin API a human would use, so the test mirrors what a user
# actually does rather than poking the database.
#
# Requires: java, mvn, cargo, curl, python3, nc, docker (for Kafka), a reachable
# Redis + Kafka — the same rig as scripts/oss-agent-e2e-{proxy,sdk}.sh.
#
# Usage:       bash scripts/test-integration-onboarding-e2e.sh
# Skip build:  SKIP_BUILD=1 bash scripts/test-integration-onboarding-e2e.sh
# Keep logs:   KEEP_WORK=1  bash scripts/test-integration-onboarding-e2e.sh
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=oss-agent-common.sh
source "$ROOT/scripts/oss-agent-common.sh"

PROXY_BIN="$OA_ROOT/target/debug/virbius-mcp-proxy"
CONTROL="http://127.0.0.1:$CONTROL_PORT"
ENROLL_DIR="$OA_WORK/enrolled"

# Boot control + engine only. Unlike oss-agent-e2e-proxy.sh we do NOT bootstrap
# the license via curl: `enroll` performs the issuance itself (the user flow).
oa_preflight
oa_boot_engine_control
mkdir -p "$ENROLL_DIR"

if [ "${SKIP_BUILD:-0}" != "1" ] || [ ! -f "$PROXY_BIN" ]; then
  info "building virbius-mcp-proxy (cargo)..."
  cargo build -p virbius-mcp-proxy > "$OA_LOGS/proxy-build.log" 2>&1 || { red "cargo build failed"; tail -30 "$OA_LOGS/proxy-build.log"; exit 2; }
fi
[ -f "$PROXY_BIN" ] || { red "proxy binary missing: $PROXY_BIN"; exit 2; }

# ── 1) enroll: one command fetches the whole credential set ─────────────────
info "E2E: virbius-mcp-proxy enroll (tenant=$TENANT app=$APP_ID tools=$ALLOW_TOOL,$BLOCK_TOOL)"
if ! "$PROXY_BIN" enroll \
      --control "$CONTROL" --tenant "$TENANT" --app-id "$APP_ID" \
      --agent-name oss-agent-target --tools "$ALLOW_TOOL,$BLOCK_TOOL" \
      --out-dir "$ENROLL_DIR" > "$OA_LOGS/enroll.out" 2> "$OA_LOGS/enroll.err"; then
  red "enroll failed:"; cat "$OA_LOGS/enroll.err"; exit 2
fi
check "$([ -s "$ENROLL_DIR/agent-license.jwt" ] && echo 1 || echo 0)" \
      "enroll wrote agent-license.jwt (0600 credential)"
check "$([ -s "$ENROLL_DIR/license_pub.pem" ] && echo 1 || echo 0)" \
      "enroll wrote license_pub.pem"

# ── 2) assert the issuance via the OPERATOR API (never the DB) ──────────────
info "E2E: operator API reflects the enrollment"
LIC_APPIDS="$(curl -s "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/app-ids")"
check "$(printf '%s' "$LIC_APPIDS" | jget 'print("1" if sys.argv[1] in (d.get("data") or []) else "0")' "$APP_ID")" \
      "operator API lists the enrolled app id"

# The control serializes JSON SNAKE_CASE (JacksonConfig) — app_id / allowed_tools.
LIC_LIST="$(curl -s "$CONTROL/api/v1/admin/tenants/$TENANT/licenses/list")"
check "$(printf '%s' "$LIC_LIST" | jget 'print("1" if any((l.get("app_id") or l.get("appId"))==sys.argv[1] and set(sys.argv[2:4]) <= set((l.get("allowed_tools") or l.get("allowedTools")) or []) for l in (d.get("data") or [])) else "0")' "$APP_ID" "$ALLOW_TOOL" "$BLOCK_TOOL")" \
      "operator API shows the license carries the requested allowed tools"

TOOLS="$(curl -s "$CONTROL/api/v1/admin/tenants/$TENANT/tools")"
check "$(printf '%s' "$TOOLS" | jget 'names={(t.get("tool_name") or t.get("toolName")) for t in (d.get("data") or [])}; print("1" if set(sys.argv[1:3]) <= names else "0")' "$ALLOW_TOOL" "$BLOCK_TOOL")" \
      "operator API lists the enrolled tools in the registry"

# ── 3) proxy doctor: pre-flight validates the enrolled credential ───────────
info "E2E: virbius-mcp-proxy doctor"
set +e
DOC_OUT="$(VIRBIUS_LICENSE_FILE="$ENROLL_DIR/agent-license.jwt" \
   VIRBIUS_LICENSE_PUBLIC_KEY="$ENROLL_DIR/license_pub.pem" \
   VIRBIUS_APP_ID="$APP_ID" \
   VIRBIUS_ENGINE_URL="http://127.0.0.1:$ENGINE_PORT" \
   VIRBIUS_CONTROL_URL="$CONTROL" \
   "$PROXY_BIN" doctor 2>&1)"
DOC_RC=$?
set -e
printf '%s\n' "$DOC_OUT" > "$OA_WORK/doctor.out"
check "$(printf '%s' "$DOC_OUT" | grep -q 'license verify  : ok' && echo 1 || echo 0)" \
      "proxy doctor verifies the enrolled license against the public key"
check "$([ "$DOC_RC" -eq 0 ] && echo 1 || echo 0)" \
      "proxy doctor exits 0 (no failures)"

# ── 4) SDK doctor: in-process readiness on the same material ────────────────
info "E2E: python -m virbius_mcp_python doctor --json"
SDK_DOC="$(VIRBIUS_LICENSE_FILE="$ENROLL_DIR/agent-license.jwt" \
   VIRBIUS_LICENSE_PUBLIC_KEY="$ENROLL_DIR/license_pub.pem" \
   VIRBIUS_APP_ID="$APP_ID" \
   "$PY" -m virbius_mcp_python doctor --json 2> "$OA_LOGS/sdk-doctor.err" || true)"
printf '%s\n' "$SDK_DOC" > "$OA_WORK/sdk-doctor.json"
check "$(printf '%s' "$SDK_DOC" | jget 'print("1" if d.get("ok") is True else "0")')" \
      "SDK doctor reports ready (ok=true) on the enrolled material"
check "$(printf '%s' "$SDK_DOC" | jget 'print("1" if set(sys.argv[1:3]) <= set(d.get("allowed_tools") or []) else "0")' "$ALLOW_TOOL" "$BLOCK_TOOL")" \
      "SDK doctor sees the enrolled allowed tools"

# ── 5) enroll idempotency: refuse to clobber, replace only with --revoke ────
info "E2E: enroll idempotency (409 without --revoke; replace with --revoke)"
set +e
"$PROXY_BIN" enroll --control "$CONTROL" --tenant "$TENANT" --app-id "$APP_ID" \
  --tools "$ALLOW_TOOL,$BLOCK_TOOL" --out-dir "$ENROLL_DIR" > "$OA_LOGS/reenroll.out" 2> "$OA_LOGS/reenroll.err"
RE_RC=$?
set -e
check "$([ "$RE_RC" -ne 0 ] && echo 1 || echo 0)" \
      "re-enroll without --revoke fails (no silent overwrite)"
check "$(grep -qi 'already exists' "$OA_LOGS/reenroll.err" && echo 1 || echo 0)" \
      "the refusal names the existing license and suggests --revoke"
check "$("$PROXY_BIN" enroll --control "$CONTROL" --tenant "$TENANT" --app-id "$APP_ID" \
          --tools "$ALLOW_TOOL,$BLOCK_TOOL" --out-dir "$ENROLL_DIR" --revoke \
          > "$OA_LOGS/reenroll-revoke.out" 2>&1 && echo 1 || echo 0)" \
      "re-enroll with --revoke replaces the active license"

oa_summary
