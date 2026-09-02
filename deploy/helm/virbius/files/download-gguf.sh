#!/bin/sh
# Download VirbiusGuard GGUF into /models/model.gguf (idempotent).
set -eu
OUT="${VIRBIUS_GUARD_GGUF_PATH:-/models/model.gguf}"
URL="${VIRBIUS_GUARD_GGUF_URL:-https://huggingface.co/i1see1you/VirbiusGuard/resolve/main/virbiusguard-v13-q4_k_m.gguf}"

if [ -s "$OUT" ]; then
  echo "gguf already present: $OUT ($(wc -c < "$OUT") bytes)"
  exit 0
fi

mkdir -p "$(dirname "$OUT")"
echo "downloading $URL"
curl -fL --retry 8 --retry-all-errors --retry-delay 3 \
  -A "virbius-ollama-import" \
  -o "${OUT}.part" \
  "$URL"
mv "${OUT}.part" "$OUT"
echo "downloaded $OUT ($(wc -c < "$OUT") bytes)"
