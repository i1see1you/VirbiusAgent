#!/usr/bin/env bash
# test-image-blacklist.sh
# End-to-end integration test for the Image Blacklist feature: admin CRUD via
# the virbius-control API + engine enforcement driven by an ORDINARY groovy
# rule whose script reads match evidence via ctx.imageMatch('list_name')
# — thresholds live in the script, actions in the rule row, exactly like
# keyword lists (listMatch). Samples live in a dimension=image access list;
# entries are sha256:phash fingerprints uploaded as files.
# Everything runs through public APIs to simulate real user operations.
#
# Flow:
#   1.  Create dimension=image list + clean image baseline (no hit)
#   2.  Upload a fake (non-image) file          → rejected
#   3.  Upload malicious sample A via API       → sha256 + pHash returned
#   4.  Re-upload identical A                   → added=false (sha256 dedup)
#   5.  Upload variant B, read its pHash, delete it immediately (B must NOT be
#       blacklisted, so the later catch proves the pHash similarity layer)
#   6.  List entries via API                    → fingerprint visible
#   7.  Create groovy rule (ctx.imageMatch) + activate + publish
#   8.  Engine evaluate: exact A bytes          → block
#   9.  Engine evaluate: variant B (not blacklisted) → block via pHash distance
#   10. Engine evaluate: distinct clean C       → no blacklist hit
#   11. Archive the rule + publish              → A no longer denied (rule = switch)
#   12. Re-activate the rule + publish          → A denied again
#   13. Delete A via API                        → engine stops blocking after refresh
#
# Prerequisites:
#   virbius-control running on port 8080
#   virbius-engine  running on port 8082
#   tenant "default" exists
#
# Usage:
#   VIRBIUS_BASE=http://127.0.0.1:8080 \
#   VIRBIUS_ENGINE=http://127.0.0.1:8082 \
#   VIRBIUS_TENANT=default \
#   bash scripts/test-image-blacklist.sh
#
set -euo pipefail

BASE="${VIRBIUS_BASE:-http://127.0.0.1:8080}"
ENGINE="${VIRBIUS_ENGINE:-http://127.0.0.1:8082}"
TENANT="${VIRBIUS_TENANT:-default}"
ENGINE_REFRESH="${ENGINE_REFRESH_SECONDS:-60}"   # virbius.file.blacklist-refresh-seconds
RULE_ID="${RULE_ID:-e2e_image_blacklist}"
LIST_NAME="${LIST_NAME:-e2e_imgbl_list}"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; YELLOW='\033[0;33m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
pass()  { echo -e "${GREEN}[PASS]${NC}  $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }
fail()  { err "$*"; exit 1; }

ALL_PASS=true
R_FAKE="" R_UPLOAD="" R_DUP="" R_LIST="" R_RULE="" R_EXACT="" R_PHASH="" R_CLEAN="" \
R_SWITCH_OFF="" R_SWITCH_ON="" R_DEL="" R_GONE=""
mark() { local var="$1" val="$2"; eval "$var=\"$val\""; }

# ─── helpers ───
api_code() { python3 -c "import sys,json; print(json.load(sys.stdin).get('code','?'))"; }
data_field() { python3 -c "import sys,json; r=json.load(sys.stdin); d=r.get('data') or {}; print(d.get('$1','?'))"; }
data_flag() { python3 -c "import sys,json; r=json.load(sys.stdin); d=r.get('data') or {}; print(str(bool(d.get('$1'))).lower())"; }
eng_field() { python3 -c "import sys,json; print(json.load(sys.stdin).get('$1','?'))"; }

# Build an evaluate payload with one base64 attachment and run it.
# tool_name must be "search": demo rules whitelist it, so the only signals
# that can fire are the FileGuard ones — keeping primary-signal attribution
# deterministic (http_get would be denied by other demo rules).
evaluate_attachment() {  # $1=image_path  $2=name  $3=session_id
  local payload; payload="$(mktemp /tmp/imgbl-eval-XXXX.json)"
  python3 - "$1" "$2" "$3" "$payload" "$TENANT" <<'PYEOF'
import base64, json, sys
path, name, sess, out, tenant = sys.argv[1:6]
data = base64.b64encode(open(path, 'rb').read()).decode()
req = {
    "tenant_id": tenant,
    "session_id": sess,
    "tool_name": "search",
    "args_json": "{}",
    "content": "",
    "vars": {},
    "trace_id": "trace-imgbl-e2e",
    "license_risk_quota": 100,
    "attachments": [{"mime_type": "", "data_base64": data, "name": name}],
}
json.dump(req, open(out, 'w'))
PYEOF
  curl -s --max-time 60 -X POST "$ENGINE/v1/evaluate" \
    -H 'Content-Type: application/json' -d @"$payload"
  rm -f "$payload"
}

hamming64() {  # $1 $2 = 64-bit pHash values (API returns unsigned hex; tolerate decimal/signed)
  python3 -c "
def parse(x):
    x = str(x).strip()
    if any(c in 'abcdefABCDEF' for c in x):
        return int(x, 16)
    return int(x) & 0xFFFFFFFFFFFFFFFF
print(bin(parse('$1') ^ parse('$2')).count('1'))"
}

# The groovy policy rule: evidence via ctx.imageMatch, threshold in the
# script (exact OR hamming <= 10), action/risk from the rule row.
RULE_SCRIPT_BEGIN="def decide(ctx) {"
RULE_SCRIPT_MID="  def h = ctx.imageMatch('$LIST_NAME')"
RULE_SCRIPT_END="  if (h == null) { return false }\n  if (h.layer == 'exact') { return true }\n  return h.distance <= 10\n}"

RULE_BODY="$(python3 -c "
import json
script = '''$RULE_SCRIPT_BEGIN
$RULE_SCRIPT_MID
$RULE_SCRIPT_END'''
print(json.dumps(script))")"

create_rule() {
  curl -s -X POST "$BASE/api/v1/admin/tenants/$TENANT/rules" \
    -H 'Content-Type: application/json' -d '{
      "rule_id": "'"$RULE_ID"'",
      "layer": "cloud",
      "runtime": "groovy",
      "bundle_id": "poc-default",
      "reason_code": "E2E_IMAGE_BLACKLIST",
      "risk_score": 100,
      "intent_action": "deny",
      "scope": {"bind_scope": "global"},
      "body": '"$RULE_BODY"'
    }'
}

activate_rule() {
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"active"}' >/dev/null
  curl -s -f -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/runtime" \
    -H 'Content-Type: application/json' \
    -d '{"enforce_mode":"canary","canary_percent":100}' >/dev/null
}

archive_rule() {
  curl -s -X PATCH "$BASE/api/v1/admin/tenants/$TENANT/rules/$RULE_ID/status" \
    -H 'Content-Type: application/json' -d '{"rule_status":"archived"}' >/dev/null
}

publish_snapshot() {
  curl -s -f -X POST "$BASE/api/v1/admin/tenants/$TENANT/rules/_/runtime/publish-snapshot" \
    -H 'Content-Type: application/json' >/dev/null
}

ensure_list() {
  curl -s -f -X PUT "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME" \
    -H 'Content-Type: application/json' -d '{"dimension":"image","remark":"e2e image blacklist"}' >/dev/null
}

upload_sample() {  # $1=file $2=remark
  curl -s -X POST "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME/entries/image" \
    -F "file=@$1" -F "remark=$2"
}

delete_sample() {  # $1=sha256 $2=phash
  curl -s -X DELETE "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME/entries/$1:$2"
}

# Wait until the engine snapshot reflects a change. Polls evaluate against a
# probe image until the expected rule appears (or disappears when want=NONE).
wait_engine() {  # $1=image_path $2=expected_rule_id(or "NONE") $3=timeout_seconds $4=label
  local img="$1" want="$2" timeout="$3" label="$4"
  local start=$(date +%s)
  local deadline=$(( start + timeout ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    local rid; rid=$(evaluate_attachment "$img" "probe.png" "sess-imgbl-poll-$$-$RANDOM" | eng_field rule_id)
    if { [ "$want" = "NONE" ] && [ "$rid" != "$RULE_ID" ]; } || [ "$rid" = "$want" ]; then
      ok "$label (elapsed $(( $(date +%s) - start ))s)"
      return 0
    fi
    sleep 5
  done
  warn "$label timed out after ${timeout}s"
  return 1
}

echo "===== Image Blacklist E2E Test (list CRUD + groovy-rule engine enforcement) ====="
echo "  Control: $BASE"
echo "  Engine:  $ENGINE"
echo "  Tenant:  $TENANT"
echo "  List:    $LIST_NAME"
echo ""

# ─── 0. Prepare test images ───
info "Generating test images (Python PIL)..."
TMPDIR_IMG="$(mktemp -d /tmp/imgbl-e2e-XXXX)"
python3 - "$TMPDIR_IMG" <<'PYEOF'
import sys, os
from PIL import Image, ImageDraw

d = sys.argv[1]
# A: "malicious sample" — distinctive diagonal-stripe pattern with marker circles
a = Image.new('RGB', (256, 256))
dr = ImageDraw.Draw(a)
for i in range(-256, 256, 16):
    dr.line([(i, 0), (i + 256, 256)], fill=(190, 30, 30), width=6)
dr.ellipse([28, 28, 100, 100], fill=(20, 20, 120))
dr.ellipse([156, 156, 228, 228], fill=(250, 210, 40))
a.save(os.path.join(d, 'a_sample.png'))

# B: evasion variant of A — resized 50% and re-encoded as JPEG
a.resize((128, 128)).save(os.path.join(d, 'b_variant.jpg'), quality=62)

# C: visually distinct clean image — vertical gradient + inverse-frequency bars
c = Image.new('RGB', (256, 256))
dr = ImageDraw.Draw(c)
for y in range(256):
    for x in range(0, 256, 32):
        v = (x * 7 + y) % 256
        dr.rectangle([x, y, x + 31, y], fill=(v, 255 - v, (x + y) % 256))
c.save(os.path.join(d, 'c_clean.png'))

# decoy: not a real image
open(os.path.join(d, 'fake.png'), 'w').write('this is definitely not an image')
print('images written to', d)
PYEOF
IMG_A="$TMPDIR_IMG/a_sample.png"
IMG_B="$TMPDIR_IMG/b_variant.jpg"
IMG_C="$TMPDIR_IMG/c_clean.png"
IMG_FAKE="$TMPDIR_IMG/fake.png"

# ─── Pre-flight ───
info "Checking health..."
curl -sf "$BASE/api/v1/health" >/dev/null 2>&1 || fail "virbius-control not ready"
curl -sf "$ENGINE/admin/health" >/dev/null 2>&1 || fail "virbius-engine not ready"
ok "Control and Engine are up"

info "Creating image list $LIST_NAME..."
ensure_list

# Clean leftovers from previous runs: test entries + archived rule (API only)
info "Cleaning leftovers from previous runs..."
existing=$(curl -s "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME" | python3 -c "
import sys, json
d = (json.load(sys.stdin).get('data') or {})
print('\n'.join(e['value'] for e in (d.get('entries') or [])))" || true)
while IFS= read -r val; do
  [ -n "$val" ] || continue
  curl -s -X DELETE "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME/entries/$val" >/dev/null
  info "  deleted leftover entry ${val:0:16}…"
done <<< "$existing"
archive_rule 2>/dev/null || true
publish_snapshot 2>/dev/null || true

# ─── 1. Baseline ───
info "=== 1) Baseline: clean image should not hit the rule ==="
base_eval=$(evaluate_attachment "$IMG_C" "c_clean.png" "sess-imgbl-base-$$-$RANDOM")
base_rid=$(echo "$base_eval" | eng_field rule_id)
if [ "$base_rid" = "$RULE_ID" ]; then
  fail "Baseline clean image already hits the rule — pick a different clean image"
else
  ok "Baseline not blacklisted (primary=$base_rid)"
fi

# ─── 2. Upload fake (non-image) file → must be rejected ───
info "=== 2) Upload non-image file → expect rejection ==="
fake_resp=$(upload_sample "$IMG_FAKE" "e2e fake")
fake_code=$(echo "$fake_resp" | api_code)
if [ "$fake_code" != "0" ]; then
  pass "Non-image rejected: $(echo "$fake_resp" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("message",""))')"
  mark R_FAKE PASS
else
  warn "Non-image upload unexpectedly succeeded"
  mark R_FAKE FAIL
  ALL_PASS=false
fi

# ─── 3. Upload sample A ───
info "=== 3) Upload malicious sample A ==="
add_resp=$(upload_sample "$IMG_A" "e2e image blacklist test")
add_code=$(echo "$add_resp" | api_code)
if [ "$add_code" = "0" ]; then
  A_SHA=$(echo "$add_resp" | data_field sha256)
  A_PHASH=$(echo "$add_resp" | data_field phash)
  pushed=$(echo "$add_resp" | python3 -c "import sys,json; d=(json.load(sys.stdin).get('data') or {}).get('image_lists') or {}; print(str(bool(d.get('pushed'))).lower())")
  ok "Uploaded A: sha256=${A_SHA:0:16}… phash=$A_PHASH (redis pushed=$pushed)"
  mark R_UPLOAD PASS
else
  err "Upload A failed: $add_resp"
  mark R_UPLOAD FAIL; ALL_PASS=false
  echo "$add_resp" | head -c 300; exit 1
fi

# ─── 4. Duplicate upload → dedup via sha256 (added=false) ───
info "=== 4) Re-upload identical image → expect dedup (added=false) ==="
dup_resp=$(upload_sample "$IMG_A" "dup")
dup_code=$(echo "$dup_resp" | api_code)
dup_added=$(echo "$dup_resp" | data_flag added)
if [ "$dup_code" = "0" ] && [ "$dup_added" = "false" ]; then
  pass "Duplicate deduped (added=false, same fingerprint PK)"
  mark R_DUP PASS
else
  warn "Duplicate upload behavior unexpected: code=$dup_code added=$dup_added"
  mark R_DUP FAIL; ALL_PASS=false
fi

# ─── 5. Variant B: upload only to read its pHash, then delete immediately ───
# B must NOT stay in the list, otherwise the engine would catch it via the
# exact layer. The pHash-layer proof later requires B to be absent while still
# being similar to blacklisted A.
info "=== 5) Upload variant B to read its pHash, then delete it ==="
addb_resp=$(upload_sample "$IMG_B" "e2e pHash probe")
addb_code=$(echo "$addb_resp" | api_code)
DIST=""
if [ "$addb_code" = "0" ]; then
  B_SHA=$(echo "$addb_resp" | data_field sha256)
  B_PHASH=$(echo "$addb_resp" | data_field phash)
  DIST=$(hamming64 "$A_PHASH" "$B_PHASH")
  delb_code=$(delete_sample "$B_SHA" "$B_PHASH" | api_code)
  if [ "$delb_code" = "0" ]; then
    ok "Variant B probed and deleted: phash=$B_PHASH, hamming(A,B)=$DIST (script deny threshold ≤10)"
  else
    warn "Could not delete variant B — pHash step would degenerate to an exact hit"
    DIST=""
  fi
else
  warn "Variant B upload failed: $addb_resp"
  B_PHASH=""
fi

# ─── 6. List contains the entry ───
info "=== 6) List entries via API → fingerprint visible ==="
list_found=$(curl -s "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME" | python3 -c "
import sys, json
d = (json.load(sys.stdin).get('data') or {})
entries = d.get('entries') or []
print('yes' if any(e.get('value','').startswith('$A_SHA') for e in entries) else 'no')")
if [ "$list_found" = "yes" ]; then
  pass "Sample A fingerprint visible in list $LIST_NAME"
  mark R_LIST PASS
else
  warn "Sample A missing from list"
  mark R_LIST FAIL; ALL_PASS=false
fi

# ─── 7. Create the groovy policy rule ───
info "=== 7) Create groovy rule (ctx.imageMatch) + activate + publish ==="
create_resp=$(create_rule)
create_code=$(echo "$create_resp" | api_code)
if [ "$create_code" = "0" ]; then
  activate_rule
  publish_snapshot
  sleep 3
  ok "Rule $RULE_ID active (canary=100), snapshot published"
  mark R_RULE PASS
else
  err "Rule creation failed: $create_resp"
  mark R_RULE FAIL; ALL_PASS=false
fi

# ─── 8. Engine: exact hit on A ───
info "=== 8) Engine evaluate: exact bytes of A → block ==="
info "Waiting up to $((ENGINE_REFRESH + 20))s for engine blacklist snapshot refresh..."
if wait_engine "$IMG_A" "$RULE_ID" "$((ENGINE_REFRESH + 20))" "Engine picked up sample A"; then
  eval_a=$(evaluate_attachment "$IMG_A" "a_sample.png" "sess-imgbl-exact-$$-$RANDOM")
  rid=$(echo "$eval_a" | eng_field rule_id)
  eff=$(echo "$eval_a" | eng_field effective_action)
  reason=$(echo "$eval_a" | eng_field reason_code)
  if [[ "$rid" == "$RULE_ID" && "$eff" == "block" && "$reason" == E2E_IMAGE_BLACKLIST* ]]; then
    pass "Exact hit: action=$eff rule=$rid reason=$reason"
    mark R_EXACT PASS
  else
    warn "Exact-hit mismatch: action=$eff rule=$rid reason=$reason"
    mark R_EXACT FAIL; ALL_PASS=false
  fi
else
  mark R_EXACT FAIL; ALL_PASS=false
fi

# ─── 9. Engine: pHash hit on variant B (NOT blacklisted) ───
info "=== 9) Engine evaluate: variant B (not blacklisted) → pHash catch via A ==="
eval_b=$(evaluate_attachment "$IMG_B" "b_variant.jpg" "sess-imgbl-phash-$$-$RANDOM")
rid_b=$(echo "$eval_b" | eng_field rule_id)
eff_b=$(echo "$eval_b" | eng_field effective_action)
if [[ "$rid_b" == "$RULE_ID" && "$eff_b" == "block" ]]; then
  pass "pHash hit: action=$eff_b reason=$(echo "$eval_b" | eng_field reason_code) (hamming distance to A: $DIST)"
  mark R_PHASH PASS
else
  warn "pHash-hit mismatch: action=$eff_b rule=$rid_b (hamming $DIST)"
  mark R_PHASH FAIL; ALL_PASS=false
fi

# ─── 10. Engine: clean image C → no blacklist hit ───
info "=== 10) Engine evaluate: distinct clean image C → no blacklist hit ==="
eval_c=$(evaluate_attachment "$IMG_C" "c_clean.png" "sess-imgbl-clean-$$-$RANDOM")
rid_c=$(echo "$eval_c" | eng_field rule_id)
eff_c=$(echo "$eval_c" | eng_field effective_action)
if [ "$rid_c" != "$RULE_ID" ]; then
  pass "Clean image not blacklisted: action=$eff_c rule=$rid_c"
  mark R_CLEAN PASS
else
  warn "Clean image unexpectedly blacklisted: $rid_c"
  mark R_CLEAN FAIL; ALL_PASS=false
fi

# ─── 11. Rule = switch: archiving the rule stops enforcement ───
info "=== 11) Archive rule + publish → sample A no longer denied ==="
archive_rule
publish_snapshot
sleep 3
eval_off=$(evaluate_attachment "$IMG_A" "a_sample.png" "sess-imgbl-off-$$-$RANDOM")
rid_off=$(echo "$eval_off" | eng_field rule_id)
if [ "$rid_off" != "$RULE_ID" ]; then
  pass "Rule archived → enforcement off (primary=$rid_off)"
  mark R_SWITCH_OFF PASS
else
  warn "Rule archived but A still denied by $rid_off"
  mark R_SWITCH_OFF FAIL; ALL_PASS=false
fi

# ─── 12. Re-activate the rule → enforcement back on ───
info "=== 12) Re-activate rule + publish → sample A denied again ==="
activate_rule
publish_snapshot
sleep 3
eval_on=$(evaluate_attachment "$IMG_A" "a_sample.png" "sess-imgbl-on-$$-$RANDOM")
rid_on=$(echo "$eval_on" | eng_field rule_id)
eff_on=$(echo "$eval_on" | eng_field effective_action)
if [[ "$rid_on" == "$RULE_ID" && "$eff_on" == "block" ]]; then
  pass "Rule re-activated → enforcement restored (action=$eff_on)"
  mark R_SWITCH_ON PASS
else
  warn "Rule re-activated but no denial: action=$eff_on rule=$rid_on"
  mark R_SWITCH_ON FAIL; ALL_PASS=false
fi

# ─── 13. Delete A via API; engine stops blocking after refresh ───
info "=== 13) Delete sample A via API ==="
del1=$(delete_sample "$A_SHA" "$A_PHASH" | api_code)
if [ "$del1" = "0" ]; then
  pass "Deleted sample A"
  mark R_DEL PASS
else
  warn "Delete failed (code=$del1)"
  mark R_DEL FAIL; ALL_PASS=false
fi

after_count=$(curl -s "$BASE/api/v1/admin/tenants/$TENANT/lists/$LIST_NAME" | python3 -c "
import sys,json
d = (json.load(sys.stdin).get('data') or {})
print(len(d.get('entries') or []))")
if [ "$after_count" = "0" ]; then
  pass "List is empty after delete"
else
  warn "$after_count entry(ies) still present"
  mark R_DEL FAIL; ALL_PASS=false
fi

info "Waiting up to $((ENGINE_REFRESH + 20))s for engine to drop the deleted samples..."
if wait_engine "$IMG_A" "NONE" "$((ENGINE_REFRESH + 20))" "Engine stopped blocking A after delete"; then
  pass "Post-delete evaluate: A no longer blacklisted"
  mark R_GONE PASS
else
  mark R_GONE FAIL; ALL_PASS=false
fi

# ─── Cleanup ───
archive_rule 2>/dev/null || true
publish_snapshot 2>/dev/null || true
rm -rf "$TMPDIR_IMG"

# ─── Summary ───
echo ""
if $ALL_PASS; then
  echo -e "${GREEN}===== Image blacklist E2E: ALL PASS =====${NC}"
else
  echo -e "${YELLOW}===== Image blacklist E2E: some steps failed (see above) =====${NC}"
fi
status_icon() { case "${1:-}" in PASS) printf "✅";; FAIL) printf "❌";; *) printf "⚪";; esac; }
echo ""
echo "Summary:"
echo "  $(status_icon "$R_FAKE")   1.  non-image upload rejected"
echo "  $(status_icon "$R_UPLOAD")  2.  sample A uploaded via list API (sha256+pHash, redis pushed)"
echo "  $(status_icon "$R_DUP")   3.  duplicate upload deduped (added=false)"
echo "  $(status_icon "$R_LIST")   4.  fingerprint visible via list API"
echo "  $(status_icon "$R_RULE")   5.  groovy rule (ctx.imageMatch) created + published"
echo "  $(status_icon "$R_EXACT")  6.  engine exact hit on A → block (rule identity)"
echo "  $(status_icon "$R_PHASH")  7.  engine pHash hit on re-encoded variant → block"
echo "  $(status_icon "$R_CLEAN")  8.  clean image → no blacklist hit"
echo "  $(status_icon "$R_SWITCH_OFF")  9.  archiving rule switches enforcement off"
echo "  $(status_icon "$R_SWITCH_ON") 10. re-activating rule restores enforcement"
echo "  $(status_icon "$R_DEL") 11. delete via API, list updated"
echo "  $(status_icon "$R_GONE") 12. engine stops blocking after delete+refresh"
