#!/usr/bin/env bash
# 构建 agent 隔离沙箱镜像。开启 Docker 隔离执行前先跑一次。
set -e
cd "$(dirname "$0")"
docker build -t llm-sec-range-agent-sandbox .
echo "✅ 镜像 llm-sec-range-agent-sandbox 已构建。"
echo "启用隔离执行：AGENT_USE_DOCKER=1 python3 app.py"
