#!/usr/bin/env bash
# 一键启动：建虚拟环境 → 装依赖 → 起服务（固定端口 8000）
set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "⚠️  未找到 .env，正在从 .env.example 复制。请编辑 .env 填入 DEEPSEEK_API_KEY。"
  cp .env.example .env
fi

if [ ! -d .venv ]; then
  echo "📦 创建虚拟环境 .venv ..."
  python3 -m venv .venv
fi
source .venv/bin/activate
pip install -q -r requirements.txt

echo "🚀 启动靶场（固定端口 8000，见下方输出）..."
python app.py
