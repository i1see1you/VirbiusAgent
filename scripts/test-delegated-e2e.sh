#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# 代客凭证（Delegated Credential Plane）端到端全流程测试
#
# 只通过运营台（virbius-control）HTTP API 模拟真实用户操作流，禁止直接读写
# SQLite / Redis：快照发布等效果通过"创建→列表→轮换→吊销"的 API 可见状态验证。
#
# 用法:   ./scripts/test-delegated-e2e.sh
# 环境变量:
#   BASE_URL        运营台地址（默认 http://127.0.0.1:8080）
#   ADMIN_API_KEY   可选，/api/v1/admin/** 的 Bearer token
#   TENANT_A/B      两个测试租户（默认 e2e-a / e2e-b，互不影响真实租户）
#
# 覆盖: 健康检查 → 创建(含规范化) → 列表 → 校验矩阵(11 项) → 重复冲突(2 项)
#       → 轮换 → 吊销(幂等) → 吊销后轮换复活 → 删除(含 404) → KEK 查看(含
#       no-store 响应头) → 租户隔离 → 全响应泄密扫描
# ─────────────────────────────────────────────────────────────────────────────
set -u

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
TENANT_A="${TENANT_A:-e2e-a}"
TENANT_B="${TENANT_B:-e2e-b}"
S="$(date +%s)"
H1="h1-$S"
HOST1="api-$S.example.com"
HOST1_UP="$(python3 -c 'import sys; print(sys.argv[1].upper())' "$HOST1")."
ISO="iso-$S"
SECRET1="sk-e2e-$S-0123456789abcdef"
SECRET2="sk-e2e-$S-rotated-9876543210"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'
PASS=0; FAIL=0
HDR_FILE="$(mktemp)"; BODY_LOG="$(mktemp)"
trap 'rm -f "$HDR_FILE" "$BODY_LOG"' EXIT

# ── helpers ─────────────────────────────────────────────────────────────────
ok()  { PASS=$((PASS+1)); printf "${GREEN}[OK]${NC}   %s\n" "$1"; }
bad() { FAIL=$((FAIL+1)); printf "${RED}[FAIL]${NC} %s\n" "$1"
        [ $# -gt 1 ] && printf '       → %s\n' "$2"; return 0; }

# req METHOD PATH [JSON_BODY]  → 设置 HTTP / RESP，并把 RESP 追加进泄密扫描日志
req() {
  local method=$1 path=$2 body=${3:-}
  local args=(-sS -X "$method" -D "$HDR_FILE")
  args+=(-H 'Content-Type: application/json')
  [ -n "${ADMIN_API_KEY:-}" ] && args+=(-H "Authorization: Bearer $ADMIN_API_KEY")
  [ -n "$body" ] && args+=(-d "$body")
  local out
  out="$(curl "${args[@]}" -w $'\n%{http_code}' "$BASE_URL$path" 2>&1)" || out="$out"$'\n'"000"
  HTTP="${out##*$'\n'}"
  RESP="${out%$'\n'*}"
  printf '%s\n' "$RESP" >>"$BODY_LOG"
}

# json_eval JSON EXPR  → 以 d 为响应对象求值（需 python3）
json_eval() { python3 -c 'import sys,json
try: d=json.loads(sys.argv[1] or "{}")
except Exception: d={}
print(eval(sys.argv[2], {"d": d}))' "$1" "$2" 2>/dev/null; }

assert_status() { # want msg
  if [ "$HTTP" = "$1" ]; then ok "$2"; else bad "$2" "HTTP=$HTTP want=$1 body=${RESP:0:160}"; fi
}
assert_code() { # want msg
  local c; c="$(json_eval "$RESP" "d.get('code')")"
  if [ "$c" = "$1" ]; then ok "$2 (code=$c)"; else bad "$2" "code=$c want=$1 body=${RESP:0:160}"; fi
}
assert_eq() { # got want msg
  if [ "$1" = "$2" ]; then ok "$3"; else bad "$3" "got='$1' want='$2'"; fi
}
assert_contains() { # haystack needle msg
  case "$1" in *"$2"*) ok "$3" ;; *) bad "$3" "missing '$2' in: ${1:0:160}" ;; esac
}
assert_not_contains() { # haystack needle msg
  case "$1" in *"$2"*) bad "$3" "unexpected '$2' found" ;; *) ok "$3" ;; esac
}

uri() { printf '/api/v1/admin/tenants/%s/delegated-credentials' "$1"; }
body_create() { # handle host header prefix value
  python3 -c 'import sys,json; print(json.dumps({"handle":sys.argv[1],"host":sys.argv[2],"header_name":sys.argv[3],"header_prefix":sys.argv[4],"value":sys.argv[5]}))' "$@"
}
row_field() { # list-json handle field
  json_eval "$1" "(lambda rs: rs[0].get('$3') if rs else None)([r for r in (d.get('data') or []) if r.get('handle')=='$2'])"
}

cleanup() {
  for h in "$H1" "$ISO"; do
    for t in "$TENANT_A" "$TENANT_B"; do
      curl -sS -o /dev/null -X DELETE "$BASE_URL$(uri "$t")/$h" 2>/dev/null || true
    done
  done
}

# ── T1 健康检查 ──────────────────────────────────────────────────────────────
echo "── T1  运营台健康检查 ──"
req GET /api/v1/health
assert_status 200 "health HTTP 200"
assert_code 0 "health code=0"

# ── T2 创建（含 host 规范化：大写 + 尾点 → 小写无尾点） ─────────────────────
echo "── T2  创建凭据 ──"
req POST "$(uri "$TENANT_A")" "$(body_create "$H1" "$HOST1_UP" Authorization "Bearer " "$SECRET1")"
assert_status 200 "create HTTP 200"
assert_code 0 "create code=0"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('handle')")" "$H1" "返回 handle 正确"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('host')")" "$HOST1" "host 已规范化(小写/去尾点)"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('status')")" "active" "初始 status=active"
assert_not_contains "$RESP" "$SECRET1" "创建响应不回显凭据值"
assert_not_contains "$RESP" "ciphertext" "创建响应不含密文字段"

# ── T3 列表 ────────────────────────────────────────────────────────────────
echo "── T3  列表可见 ──"
req GET "$(uri "$TENANT_A")"
assert_status 200 "list HTTP 200"
assert_eq "$(row_field "$RESP" "$H1" "header_name")" "Authorization" "列表 header_name"
assert_eq "$(row_field "$RESP" "$H1" "header_prefix")" "Bearer " "列表 header_prefix"
assert_eq "$(row_field "$RESP" "$H1" "status")" "active" "列表 status"
[ -n "$(row_field "$RESP" "$H1" "updated_at")" ] && ok "列表含 updated_at" || bad "列表含 updated_at" "empty"
assert_not_contains "$RESP" "$SECRET1" "列表不回显凭据值"
assert_not_contains "$RESP" "ciphertext" "列表不含密文字段"

# ── T4 校验矩阵（该 controller 业务错误为信封式: HTTP 200 + code 字段） ─────
echo "── T4  校验拒绝矩阵 ──"
expect_reject() { # code-msg-fragment handle host header prefix value
  local frag=$1; shift
  req POST "$(uri "$TENANT_A")" "$(body_create "$@" )"
  assert_code 400 "拒绝: $frag"
  assert_contains "$RESP" "$frag" "错误信息: $frag"
}
expect_reject "value must be >= 16 bytes" "v-short" "vshort-$S.example.com" Authorization "Bearer " "short"
expect_reject "value: control characters" "v-crlf" "vcrlf-$S.example.com" Authorization "Bearer " $'line1\rline2xxxxxxx'
expect_reject "managed/deny list" "v-deny" "vdeny-$S.example.com" Host "Bearer " "$SECRET1"
expect_reject "managed/deny list" "v-ctype" "vctype-$S.example.com" Content-Type "" "$SECRET1"
expect_reject "invalid token character" "v-space" "vspace-$S.example.com" "Bad Header" "" "$SECRET1"
expect_reject "only [A-Za-z0-9_.-] allowed" "bad handle!" "vhandle-$S.example.com" Authorization "Bearer " "$SECRET1"
expect_reject "handle must be 1..64" "$(python3 -c 'print("x"*65)')" "vlong-$S.example.com" Authorization "Bearer " "$SECRET1"
expect_reject "host rejected" "v-host" "http://insecure-$S.example.com" Authorization "Bearer " "$SECRET1"
expect_reject "host rejected" "v-ip" "127.0.0.1" Authorization "Bearer " "$SECRET1"
req POST "$(uri "$TENANT_A")" "$(body_create "v-prefix" "vprefix-$S.example.com" Authorization "$(printf 'A\tB')" "$SECRET1")"
assert_code 400 "拒绝: prefix 控制字符"
assert_contains "$RESP" "control characters rejected" "错误信息: prefix 控制字符"
req POST "$(uri "$TENANT_A")" "$(printf '{"handle":"v-null","host":"vnull-%s.example.com","header_name":"Authorization","header_prefix":"Bearer "}' "$S")"
assert_code 400 "拒绝: 缺失 value"
assert_contains "$RESP" "value is required" "错误信息: 缺失 value"

# ── T5 重复冲突 ────────────────────────────────────────────────────────────
echo "── T5  重复冲突 ──"
req POST "$(uri "$TENANT_A")" "$(body_create "$H1" "other-$S.example.com" Authorization "Bearer " "$SECRET1")"
assert_code 409 "同 handle 重复 → 409"
assert_contains "$RESP" "already exists" "错误信息: 重复 handle"
req POST "$(uri "$TENANT_A")" "$(body_create "h1b-$S" "$HOST1" Authorization "Bearer " "$SECRET1")"
assert_code 409 "同 host 重复 → 409"

# ── T6 轮换值 ──────────────────────────────────────────────────────────────
echo "── T6  轮换值 ──"
req PUT "$(uri "$TENANT_A")/$H1" "$(body_create "$H1" "$HOST1" Authorization "Token " "$SECRET2")"
assert_status 200 "rotate HTTP 200"
assert_code 0 "rotate code=0"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('header_prefix')")" "Token " "轮换后 prefix 已更新"
assert_not_contains "$RESP" "$SECRET2" "轮换响应不回显新值"
req GET "$(uri "$TENANT_A")"
assert_eq "$(row_field "$RESP" "$H1" "header_prefix")" "Token " "列表确认轮换生效"
assert_not_contains "$RESP" "$SECRET2" "列表不回显新值"

# ── T7 吊销（幂等） ────────────────────────────────────────────────────────
echo "── T7  吊销 ──"
req POST "$(uri "$TENANT_A")/$H1/revoke"
assert_status 200 "revoke HTTP 200"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('status')")" "revoked" "revoke 后 status=revoked"
req POST "$(uri "$TENANT_A")/$H1/revoke"
assert_status 200 "重复 revoke 幂等 → 200"

# ── T8 吊销后轮换复活 ──────────────────────────────────────────────────────
echo "── T8  吊销后更新复活 ──"
req PUT "$(uri "$TENANT_A")/$H1" "$(body_create "$H1" "$HOST1" Authorization "Bearer " "$SECRET2")"
assert_status 200 "revoked 行 update → 200"
assert_eq "$(json_eval "$RESP" "d.get('data',{}).get('status')")" "active" "update 后 status 回到 active"

# ── T9 删除 ────────────────────────────────────────────────────────────────
echo "── T9  删除 ──"
req DELETE "$(uri "$TENANT_A")/$H1"
assert_status 200 "delete HTTP 200"
req GET "$(uri "$TENANT_A")"
assert_eq "$(row_field "$RESP" "$H1" "handle")" "None" "删除后列表不再包含"
req DELETE "$(uri "$TENANT_A")/$H1"
assert_code 404 "重复 delete → 404"

# ── T10 KEK 查看（no-store 响应头） ────────────────────────────────────────
echo "── T10 KEK 查看 ──"
req POST "$(uri "$TENANT_A")/kek/reveal"
assert_status 200 "kek reveal HTTP 200"
KEK="$(json_eval "$RESP" "d.get('data',{}).get('kek_base64')")"
[ "${#KEK}" -ge 40 ] && ok "kek_base64 长度合理(${#KEK})" || bad "kek_base64 长度合理" "len=${#KEK}"
assert_contains "$(tr 'A-Z' 'a-z' <"$HDR_FILE")" "cache-control: no-store" "响应头 Cache-Control: no-store"
assert_contains "$(tr 'A-Z' 'a-z' <"$HDR_FILE")" "pragma: no-cache" "响应头 Pragma: no-cache"

# ── T11 租户隔离 ───────────────────────────────────────────────────────────
echo "── T11 租户隔离 ──"
req POST "$(uri "$TENANT_A")" "$(body_create "$ISO" "iso-a-$S.example.com" Authorization "Bearer " "$SECRET1")"
assert_eq "$(json_eval "$RESP" "d.get('code')")" "0" "A 租户创建 $ISO"
req POST "$(uri "$TENANT_B")" "$(body_create "$ISO" "iso-b-$S.example.com" Authorization "Bearer " "$SECRET1")"
assert_eq "$(json_eval "$RESP" "d.get('code')")" "0" "B 租户同名 handle 不冲突"
req GET "$(uri "$TENANT_A")"
assert_eq "$(row_field "$RESP" "$ISO" "host")" "iso-a-$S.example.com" "A 列表只见 A 的 host"
assert_not_contains "$RESP" "iso-b-$S.example.com" "A 列表不含 B 的 host"
req GET "$(uri "$TENANT_B")"
assert_eq "$(row_field "$RESP" "$ISO" "host")" "iso-b-$S.example.com" "B 列表只见 B 的 host"
req DELETE "$(uri "$TENANT_A")/$ISO"
assert_status 200 "删除 A 的 $ISO"
req GET "$(uri "$TENANT_B")"
assert_eq "$(row_field "$RESP" "$ISO" "host")" "iso-b-$S.example.com" "删 A 不影响 B"
req DELETE "$(uri "$TENANT_B")/$ISO"
assert_status 200 "删除 B 的 $ISO"

# ── T12 全响应泄密扫描 ──────────────────────────────────────────────────────
echo "── T12 全响应泄密扫描 ──"
for secret in "$SECRET1" "$SECRET2"; do
  if grep -qF "$secret" "$BODY_LOG"; then
    bad "凭据值从未出现在任何响应体" "泄漏: ${secret:0:12}…"
  else
    ok "凭据值从未出现在任何响应体 (${secret:0:12}…)"
  fi
done

# ── 汇总 ────────────────────────────────────────────────────────────────────
cleanup
echo ""
echo "════════════════════════════════════════"
printf "结果: ${GREEN}%d 通过${NC}, ${RED}%d 失败${NC}\n" "$PASS" "$FAIL"
echo "════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
