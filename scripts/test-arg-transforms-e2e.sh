#!/usr/bin/env bash
# Full-flow arg_transforms: initialize → allow (incl. shell fast_path) → apply → exec/forward.
# No live Control/Engine required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec cargo test -p virbius-mcp-proxy --test integration_test arg_transform -- --nocapture
