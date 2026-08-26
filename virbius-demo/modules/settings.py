# -*- coding: utf-8 -*-
"""运行期配置：读取 / 持久化 demo 的全局设置。

存储位置：容器卷 VIRBIUS_CONFIG_DIR/config.json（compose 注入 VIRBIUS_CONFIG_DIR=/data）；
本地开发未注入时回退到 demo 根目录的 config.json。

优先级：运行期页面配置 > 环境变量（compose 注入）> 默认值兜底。
- get() 在 llm_client 等调用处动态读取，因此修改后立即生效，无需重启。
- 全局一份（单用户演示台），明文存储。
"""
import json
import os

# 配置目录：容器内由 compose 注入 VIRBIUS_CONFIG_DIR=/data；本地开发回退到 demo 根目录
_CONFIG_DIR = os.environ.get(
    "VIRBIUS_CONFIG_DIR",
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
)
_CONFIG_PATH = os.path.join(_CONFIG_DIR, "config.json")

# 运行期覆盖（来自页面设置，global 单用户）
_overrides = {}

# 默认值兜底（与 config.py / .env.example 对齐）
_DEFAULTS = {
    "VIRBIUS_CONTROL_URL": "http://localhost:8080",
    "VIRBIUS_ENGINE_URL": "http://localhost:8082",
    "VIRBIUS_LICENSE_JWT": "",
    "DEEPSEEK_API_KEY": "sk-REPLACE-ME",
    "DEEPSEEK_BASE_URL": "https://api.deepseek.com",
    "DEEPSEEK_MODEL": "deepseek-chat",
    "OPENROUTER_API_KEY": "",
    "OPENROUTER_BASE_URL": "https://openrouter.ai/api/v1",
    "OLLAMA_BASE_URL": "http://localhost:11434/v1",
}


def _load():
    global _overrides
    try:
        with open(_CONFIG_PATH, encoding="utf-8") as f:
            _overrides = json.load(f)
    except (OSError, ValueError):
        _overrides = {}


def get(key: str) -> str:
    """返回运行期配置；未显式设置（含空串视为清除覆盖）则回退环境变量 -> 默认值。"""
    val = _overrides.get(key)
    if val:
        return val
    return os.environ.get(key, _DEFAULTS.get(key, ""))


def all() -> dict:
    """返回当前生效的完整配置（含默认/环境变量兜底）。"""
    return {k: get(k) for k in _DEFAULTS}


def save(updates: dict) -> None:
    """合并更新并持久化到磁盘（全局作用域）。空值视为清除覆盖，回退环境变量/默认值。"""
    _overrides.update(updates or {})
    try:
        os.makedirs(_CONFIG_DIR, exist_ok=True)
        with open(_CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(_overrides, f, ensure_ascii=False, indent=2)
    except OSError as exc:
        raise RuntimeError(f"保存配置失败: {exc}")


_load()