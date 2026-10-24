#!/usr/bin/env bash
# Generate a one-time platform_admin API key and the SQL needed to insert it.
# This is an operator bootstrap tool, not a runtime backdoor: it does not run
# inside control and the key is not stored anywhere by the script.
set -euo pipefail
: "${VIRBIUS_API_KEY_PEPPER:?set VIRBIUS_API_KEY_PEPPER to the control pepper}"
LABEL="${1:-bootstrap}"

command -v openssl >/dev/null || { echo "openssl required" >&2; exit 2; }
TOKEN="vrb_tk_$(openssl rand -hex 24)"
HASH=$(printf '%s' "$TOKEN" | openssl dgst -sha256 -hmac "$VIRBIUS_API_KEY_PEPPER" -hex | awk '{print $NF}')
PREFIX=${TOKEN:0:12}
CID=$(uuidgen 2>/dev/null | tr 'A-Z' 'a-z' || python3 -c 'import uuid; print(uuid.uuid4())')

umask 077
SQL=$(mktemp "${TMPDIR:-/tmp}/bootstrap-api-key.XXXXXX")
cat >"$SQL" <<SQL
INSERT INTO tb_tenant_api_credential
  (credential_id, tenant_id, role, key_hash, key_prefix, label, status, created_by, created_at, hash_algo)
VALUES
  ('$CID', '*', 'platform_admin', '$HASH', '$PREFIX', '$LABEL', 'active', 'bootstrap', UTC_TIMESTAMP(), 'hmac-sha256-pepper');
SQL

echo "TOKEN=$TOKEN" >&2
echo "SQL=$SQL" >&2
cat "$SQL"
