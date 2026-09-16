"""Runtime LLM config. Plaintext in data/config.json, not committed."""

from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
CONFIG_PATH = ROOT / "data" / "config.json"

PROVIDERS = {
    "deepseek": {
        "label": "DeepSeek",
        "base": "https://api.deepseek.com",
        "model": "deepseek-chat",
        "needs_key": True,
    },
    "qwen": {
        "label": "通义千问",
        "base": "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "model": "qwen-plus",
        "needs_key": True,
    },
    "zhipu": {
        "label": "智谱 GLM",
        "base": "https://open.bigmodel.cn/api/paas/v4",
        "model": "glm-4-flash",
        "needs_key": True,
    },
    "kimi": {
        "label": "Kimi",
        "base": "https://api.moonshot.cn/v1",
        "model": "kimi-k2-turbo-preview",
        "needs_key": True,
    },
    "doubao": {
        "label": "豆包",
        "base": "https://ark.cn-beijing.volces.com/api/v3",
        "model": "doubao-seed-1-6-250615",
        "needs_key": True,
    },
}


def load() -> dict:
    try:
        data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    return data if isinstance(data, dict) else {}


def save(provider: str, model: str, base: str, key: str) -> dict:
    if provider not in PROVIDERS:
        raise ValueError("不支持的模型类型")
    preset = PROVIDERS[provider]
    prev = load()
    rec = {
        "provider": provider,
        "model": (model or preset["model"]).strip(),
        "base": (base or preset["base"]).strip().rstrip("/"),
        "key": (key or "").strip()
        or str(prev.get("key") or "")
        or os.environ.get("SNAPSHOT_API_KEY")
        or os.environ.get("DEEPSEEK_API_KEY")
        or "",
    }
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps(rec, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return rec


def key_hint(key: str) -> str:
    key = (key or "").strip()
    if not key:
        return ""
    if len(key) < 8:
        return "已保存"
    return f"{key[:3]}…{key[-4:]}"
