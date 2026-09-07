#!/bin/sh
# Import a local GGUF into Ollama as VIRBIUS_OLLAMA_MODEL (default virbiusguard).
set -eu
export OLLAMA_HOST="${OLLAMA_HOST:-http://127.0.0.1:11434}"
MODEL="${VIRBIUS_OLLAMA_MODEL:-virbiusguard}"
GGUF="${VIRBIUS_GUARD_GGUF_PATH:-/models/model.gguf}"

i=0
while ! ollama list >/dev/null 2>&1; do
  i=$((i + 1))
  if [ "$i" -ge 180 ]; then
    echo "timeout waiting for ollama at $OLLAMA_HOST"
    exit 1
  fi
  echo "waiting for ollama..."
  sleep 2
done

if ollama show "$MODEL" >/dev/null 2>&1; then
  echo "model $MODEL already present"
  exit 0
fi

if [ ! -s "$GGUF" ]; then
  echo "missing GGUF at $GGUF"
  exit 1
fi

cat > /tmp/Modelfile <<EOF
FROM $GGUF
TEMPLATE """{{ if .System }}<|im_start|>system
{{ .System }}<|im_end|>
{{ end }}{{ range .Messages }}{{ if eq .Role "user" }}<|im_start|>user
{{ .Content }}<|im_end|>
<|im_start|>assistant
{{ else if eq .Role "assistant" }}{{ .Content }}<|im_end|>
{{ end }}{{ end }}"""
PARAMETER stop "<|im_start|>"
PARAMETER stop "<|im_end|>"
PARAMETER num_ctx 4096
EOF

echo "creating ollama model $MODEL from $GGUF"
ollama create "$MODEL" -f /tmp/Modelfile
ollama show "$MODEL" >/dev/null
echo "model $MODEL ready"
