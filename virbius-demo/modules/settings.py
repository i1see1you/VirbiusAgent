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


def config_dir() -> str:
    return _CONFIG_DIR


def get_license(lab_id: str) -> dict:
    """本关 JWT / pem。旧的 VIRBIUS_LICENSE_JWT 并集票忽略。"""
    rec = (_overrides.get("licenses") or {}).get(lab_id) or {}
    if not isinstance(rec, dict):
        rec = {}
    return {
        "jwt": str(rec.get("jwt") or "").strip(),
        "pem_path": str(rec.get("pem_path") or "").strip(),
    }


def save_license(lab_id: str, jwt: str, pem_path: str = "") -> None:
    licenses = dict(_overrides.get("licenses") or {})
    prev = dict(licenses.get(lab_id) or {}) if isinstance(licenses.get(lab_id), dict) else {}
    if jwt:
        prev["jwt"] = jwt
    if pem_path:
        prev["pem_path"] = pem_path
    licenses[lab_id] = prev
    save({"licenses": licenses})


def license_statuses() -> dict:
    """设置页级联用：每关签发状态。"""
    import base64
    from mcp_runtime.labs import LABS

    def claims(jwt: str) -> dict:
        token = (jwt or "").strip()
        parts = token.split(".")
        if len(parts) < 2:
            return {}
        pad = "=" * ((4 - len(parts[1]) % 4) % 4)
        try:
            raw = base64.urlsafe_b64decode(parts[1] + pad)
            data = json.loads(raw.decode("utf-8"))
            return data if isinstance(data, dict) else {}
        except Exception:
            return {}

    out = {}
    for lab in LABS:
        rec = get_license(lab.id)
        c = claims(rec.get("jwt") or "")
        tools = c.get("allowed_tools") or []
        out[lab.id] = {
            "label": lab.label,
            "tenant_id": lab.tenant_id,
            "app_id": lab.app_id,
            "jwt": rec.get("jwt") or "",
            "issued": bool(rec.get("jwt")),
            "tool_count": len(tools) if isinstance(tools, list) else 0,
        }
    return out


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