# -*- coding: utf-8 -*-
"""运行期配置：读取 / 持久化 demo 的全局设置。

存储位置：容器卷 VIRBIUS_CONFIG_DIR/config.json（compose 注入 VIRBIUS_CONFIG_DIR=/data）；
本地开发未注入时回退到 demo 根目录的 config.json。

优先级：运行期页面配置 > 环境变量（compose 注入）> 默认值兜底。
- get() 在 llm_client 等调用处动态读取，因此修改后立即生效，无需重启。
- 全局一份（单用户演示台），明文存储。
- License 按 Control 地址分套（control_profiles）。切环境只换本地票，不重新签发。
"""
import json
import os
import shutil
from urllib.parse import urlparse

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


def normalize_control_url(url):
    return (url or "").strip().rstrip("/")


def profile_slug(url):
    parsed = urlparse(normalize_control_url(url) or "http://unknown")
    host = parsed.hostname or "unknown"
    if parsed.port:
        return "%s_%s" % (host, parsed.port)
    return host


def profile_label(url):
    host = urlparse(normalize_control_url(url) or "").hostname or ""
    if host == "192.168.0.100":
        return "ThinkPad"
    if "grainmind" in host:
        return "grainmind"
    return host or normalize_control_url(url)


def _load():
    global _overrides
    try:
        with open(_CONFIG_PATH, encoding="utf-8") as f:
            _overrides = json.load(f)
    except (OSError, ValueError):
        _overrides = {}
    if not isinstance(_overrides.get("control_profiles"), dict):
        _sync_current_profile_into_map()
        _persist()


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


def license_pem_path(tenant_id: str, control_url=None) -> str:
    """本 Control 环境下该租户公钥路径。两套环境不能共用同一个 pem 文件。"""
    slug = profile_slug(control_url or get("VIRBIUS_CONTROL_URL"))
    folder = os.path.join(_CONFIG_DIR, "licenses", slug)
    os.makedirs(folder, exist_ok=True)
    return os.path.join(folder, tenant_id + ".pem")


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


def control_profiles() -> list:
    """设置页环境列表。不含 JWT。"""
    profiles = _overrides.get("control_profiles") or {}
    current = normalize_control_url(get("VIRBIUS_CONTROL_URL"))
    from mcp_runtime.labs import LABS

    total = len(LABS)
    out = []
    seen = set()
    for url, rec in profiles.items():
        key = normalize_control_url(url)
        if not key or key in seen:
            continue
        seen.add(key)
        licenses = (rec or {}).get("licenses") or {}
        issued = sum(
            1 for v in licenses.values()
            if isinstance(v, dict) and str(v.get("jwt") or "").strip()
        )
        stored = str((rec or {}).get("label") or "").strip()
        out.append({
            "control_url": key,
            "engine_url": str((rec or {}).get("engine_url") or "").rstrip("/"),
            "label": stored or profile_label(key),
            "issued": issued,
            "lab_count": total,
            "current": key == current,
        })
    if current and current not in seen:
        issued = sum(1 for lab in LABS if get_license(lab.id).get("jwt"))
        out.append({
            "control_url": current,
            "engine_url": (get("VIRBIUS_ENGINE_URL") or "").rstrip("/"),
            "label": profile_label(current),
            "issued": issued,
            "lab_count": total,
            "current": True,
        })
    out.sort(key=lambda p: (p["label"], p["control_url"]))
    return out


def _empty_licenses():
    from mcp_runtime.labs import LABS
    return {lab.id: {"jwt": "", "pem_path": ""} for lab in LABS}


def _copy_licenses(licenses, control_url=None):
    from mcp_runtime.labs import BY_ID

    out = {}
    src = licenses if isinstance(licenses, dict) else {}
    target = normalize_control_url(control_url or get("VIRBIUS_CONTROL_URL"))
    for lab_id, rec in src.items():
        if not isinstance(rec, dict):
            continue
        jwt = str(rec.get("jwt") or "").strip()
        pem_path = str(rec.get("pem_path") or "").strip()
        lab = BY_ID.get(lab_id)
        tenant_id = lab.tenant_id if lab is not None else lab_id
        dest = license_pem_path(tenant_id, target)
        if pem_path and os.path.isfile(pem_path) and os.path.abspath(pem_path) != os.path.abspath(dest):
            try:
                shutil.copy2(pem_path, dest)
                pem_path = dest
            except OSError:
                pass
        elif os.path.isfile(dest):
            pem_path = dest
        out[lab_id] = {"jwt": jwt, "pem_path": pem_path}
    return out


def _sync_current_profile_into_map():
    control = normalize_control_url(get("VIRBIUS_CONTROL_URL"))
    if not control:
        return
    profiles = dict(_overrides.get("control_profiles") or {})
    prev = profiles.get(control) or {}
    profiles[control] = {
        "label": str(prev.get("label") or "").strip() or profile_label(control),
        "engine_url": (get("VIRBIUS_ENGINE_URL") or "").rstrip("/"),
        "licenses": _copy_licenses(_overrides.get("licenses"), control),
    }
    _overrides["control_profiles"] = profiles
    _overrides["licenses"] = profiles[control]["licenses"]


def _persist():
    try:
        os.makedirs(_CONFIG_DIR, exist_ok=True)
        with open(_CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(_overrides, f, ensure_ascii=False, indent=2)
    except OSError as exc:
        raise RuntimeError(f"保存配置失败: {exc}")


def apply_control_urls(control_url=None, engine_url=None):
    """切 Control 时先把当前票存进旧环境，再加载目标环境已有的票。"""
    _sync_current_profile_into_map()
    old = normalize_control_url(get("VIRBIUS_CONTROL_URL"))
    if control_url is None:
        target = old
    else:
        target = normalize_control_url(control_url)
    incoming_engine = None if engine_url is None else (engine_url or "").strip().rstrip("/")
    profiles = dict(_overrides.get("control_profiles") or {})
    if target and target != old and target in profiles:
        rec = profiles.get(target) or {}
        engine = incoming_engine or rec.get("engine_url") or ""
        _overrides["VIRBIUS_CONTROL_URL"] = target
        if engine:
            _overrides["VIRBIUS_ENGINE_URL"] = engine
        _overrides["licenses"] = _copy_licenses(rec.get("licenses"), target)
    else:
        if target:
            _overrides["VIRBIUS_CONTROL_URL"] = target
        if incoming_engine:
            _overrides["VIRBIUS_ENGINE_URL"] = incoming_engine
        if target and target != old:
            _overrides["licenses"] = _empty_licenses()
    _sync_current_profile_into_map()
    _persist()


def set_profile_label(control_url, label):
    key = normalize_control_url(control_url)
    if not key:
        return
    name = (label or "").strip() or profile_label(key)
    profiles = dict(_overrides.get("control_profiles") or {})
    rec = dict(profiles.get(key) or {})
    rec["label"] = name
    if "licenses" not in rec:
        rec["licenses"] = _empty_licenses()
    if "engine_url" not in rec:
        rec["engine_url"] = ""
    profiles[key] = rec
    _overrides["control_profiles"] = profiles
    _persist()


def upsert_profile(control_url, engine_url="", label="", activate=True, previous_url=None):
    """新增或改一个接入环境。activate=True 时切过去（已有票会换回来）。"""
    target = normalize_control_url(control_url)
    if not target:
        raise RuntimeError("Control 地址不能为空")
    name = (label or "").strip() or profile_label(target)
    engine = (engine_url or "").strip().rstrip("/")
    prev = normalize_control_url(previous_url) if previous_url else ""
    _sync_current_profile_into_map()
    profiles = dict(_overrides.get("control_profiles") or {})
    src = prev if prev and prev in profiles else (target if target in profiles else "")
    rec = dict(profiles.get(src) or {})
    if src and src != target:
        rec["licenses"] = _copy_licenses(rec.get("licenses"), target)
        profiles.pop(src, None)
    rec["label"] = name
    rec["engine_url"] = engine
    if "licenses" not in rec:
        rec["licenses"] = _empty_licenses()
    profiles[target] = rec
    _overrides["control_profiles"] = profiles
    _persist()
    if activate:
        apply_control_urls(target, engine)
        set_profile_label(target, name)
    return target


def delete_profile(control_url):
    """删掉一套环境。删的是当前生效的，会切到剩下的第一套。"""
    target = normalize_control_url(control_url)
    _sync_current_profile_into_map()
    profiles = dict(_overrides.get("control_profiles") or {})
    if target not in profiles:
        raise RuntimeError("没有这个接入环境")
    if len(profiles) <= 1:
        raise RuntimeError("至少保留一个接入环境")
    current = normalize_control_url(get("VIRBIUS_CONTROL_URL"))
    profiles.pop(target, None)
    _overrides["control_profiles"] = profiles
    if current == target:
        nxt = next(iter(profiles))
        rec = profiles.get(nxt) or {}
        apply_control_urls(nxt, rec.get("engine_url"))
    else:
        _persist()


def save(updates: dict) -> None:
    """合并更新并持久化到磁盘（全局作用域）。空值视为清除覆盖，回退环境变量/默认值。

    改 Control 地址时走分套 License：已有环境恢复本地票，新环境才清空票。
    """
    updates = dict(updates or {})
    new_control = updates.pop("VIRBIUS_CONTROL_URL", None)
    new_engine = updates.pop("VIRBIUS_ENGINE_URL", None)
    if new_control is not None or new_engine is not None:
        apply_control_urls(
            None if new_control is None else new_control,
            None if new_engine is None else new_engine,
        )
        if new_control is not None:
            updates.pop("licenses", None)
    if updates:
        _overrides.update(updates)
        _sync_current_profile_into_map()
        _persist()
    elif new_control is None and new_engine is None:
        _sync_current_profile_into_map()
        _persist()


_load()
