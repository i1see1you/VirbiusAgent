# -*- coding: utf-8 -*-
"""VirbiusAgent 防护引擎（第二步 · 端层 Edge 实现）。

直接调用真实的 virbius-agent 能力（不再模拟）：
  1. 通过 PyO3 扩展 `virbius_mcp_python`（编译自 virbius-core）执行端层扫描，
     matcher + enforce 均在 virbius-core 内完成，拦截决策完全来自真实引擎。
  2. 规则来源由 `data/virbius.json` 决定，可切换：
       - offline : 直接读本地 edge-manifest.json
       - control : 从本地 virbius-control 的 Edge API 拉取规则
       - cloud   : 从云端 control 拉取规则
  3. 仅用于前端展示的 `matched_keywords` / `hit_count` 由 Python 依据命中主规则
     从 manifest 计算（不影响拦截决策）。

接口结构保持与第一步占位一致（PRD §5 约定）。
"""
import json
import os
from flask import session

# virbius.json 规则来源配置路径（换 active_mode 即可切换云端/control/离线）
_CONFIG_PATH = os.path.join(
    os.path.dirname(__file__), "..", "data", "virbius.json",
)

# 本地离线 manifest（仅离线模式 / 展示用）
_MANIFEST_PATH = os.path.join(
    os.path.dirname(__file__), "..", "data", "edge", "default", "demo-app",
    "edge-manifest.json",
)

# 真实引擎扩展（PyO3 编译自 virbius-core）。构建方式见 README。
_engine = None
_engine_ready = False
_engine_error = None
_active_mode = "offline"


def _load_config():
    try:
        with open(_CONFIG_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
        mode = cfg.get("active_mode", "offline")
        return mode, cfg.get("modes", {}).get(mode, {})
    except (OSError, ValueError):
        return "offline", {}


def _absolute_path(p):
    """把配置里的相对路径转成绝对路径（相对 demo 根目录）。"""
    if not p:
        return p
    return os.path.abspath(os.path.join(os.path.dirname(__file__), "..", p))


def _build_edge_config(mode_cfg):
    """由 virbius.json 的某个 mode 构造 EdgeInitConfig 字典。"""
    result = {
        "tenant_id": mode_cfg.get("tenant_id", "default"),
        "app_id": mode_cfg.get("app_id", "demo-app"),
        "cache_dir": _absolute_path(mode_cfg.get("cache_dir", "data/edge/default/demo-app")),
    }
    if mode_cfg.get("offline_manifest_path"):
        result["offline_manifest_path"] = _absolute_path(mode_cfg["offline_manifest_path"])
    if mode_cfg.get("control_base_url"):
        result["control_base_url"] = mode_cfg["control_base_url"]
    if mode_cfg.get("edge_api_key"):
        result["edge_api_key"] = mode_cfg["edge_api_key"]
    if mode_cfg.get("device_id"):
        result["device_id"] = mode_cfg["device_id"]
    return result


def _init_engine():
    """加载扩展并安装真实规则（幂等）。"""
    global _engine, _engine_ready, _engine_error, _active_mode
    if _engine is not None:
        return
    try:
        import virbius_mcp_python as _eng
    except ImportError as e:
        _engine_error = (
            "真实 virbius-core 引擎扩展未安装。请先构建并安装 virbius_mcp_python："
            "cd VirbiusAgent/virbius-mcp-python && maturin build --release && "
            "pip install --force-reinstall ../target/wheels/*.whl。"
            f"（{e}）"
        )
        return
    _engine = _eng
    _active_mode, mode_cfg = _load_config()
    try:
        _engine.configure_rules(json.dumps(_build_edge_config(mode_cfg)))
        _engine_ready = True
    except Exception as e:  # noqa: BLE001
        _engine_error = f"virbius_mcp_python 初始化失败：{e}"


# ---------- 会话开关 ----------
def is_enabled():
    """读取全局防护开关（存于会话，默认关）。"""
    return session.get("virbius_protection", False) is True


def set_enabled(value):
    session["virbius_protection"] = bool(value)


def active_mode():
    """当前规则来源模式（offline / control / cloud）。供前端/日志标注。"""
    _init_engine()
    return _active_mode


def engine_error():
    """引擎不可用时的说明（未安装扩展等）；可用则返回 None。"""
    _init_engine()
    return _engine_error


# ---------- 展示用辅助（不参与拦截决策） ----------
def _keyword_hit(content, keywords):
    """与 virbius-core matcher 语义一致的关键词命中（仅用于展示 matched_keywords）。"""
    if not content:
        return False
    lower = content.lower()
    for kw in keywords:
        if not kw:
            continue
        if any(ord(c) > 0x7F for c in kw):
            if kw in content:
                return True
        elif lower and kw.lower() in lower:
            return True
    return False


def _matched_keywords(content, rule_id):
    if not content or not rule_id:
        return []
    try:
        with open(_MANIFEST_PATH, encoding="utf-8") as f:
            manifest = json.load(f)
    except (OSError, ValueError):
        return []
    for rule in manifest.get("rules", []):
        if rule.get("rule_id") == rule_id:
            kws = rule.get("body", {}).get("keywords", [])
            return [kw for kw in kws if _keyword_hit(content, [kw])]
    return []


# ---------- 端层扫描：真实 virbius-core ----------
def scan(content, session_id="demo-session"):
    """端层扫描入口：调用真实 virbius-core（PyO3），返回统一结果 dict。"""
    _init_engine()
    if not _engine_ready:
        raise RuntimeError(_engine_error or "virbius_mcp_python 未就绪")

    result = _engine.scan_edge(content, session_id)
    action = result.get("action", "allow")
    primary = None
    if result.get("rule_id"):
        primary = {
            "rule_id": result.get("rule_id"),
            "rule_revision": result.get("rule_revision"),
            "reason_code": result.get("reason_code"),
            "risk_score": result.get("max_risk_score"),
        }
    return {
        "action": action,
        "effective_action": result.get("effective_action", action),
        "layer": primary.get("layer", "edge") if primary else "edge",
        "trace_id": result.get("trace_id"),
        "rule_id": result.get("rule_id"),
        "rule_revision": result.get("rule_revision"),
        "reason_code": result.get("reason_code"),
        "risk_score": result.get("max_risk_score"),
        "matched_keywords": _matched_keywords(content, result.get("rule_id")),
        "hit_count": 1 if primary else 0,
        "mode": _active_mode,
        "note": ("VirbiusAgent 端层(Edge)规则拦截" if action == "block"
                 else "VirbiusAgent 防护已启用（端层未命中，放行）"),
    }


def evaluate(code, system, user, messages):
    """端层评估入口（PRD §5 接口）。对用户输入做真实端层扫描，命中 deny+full 即拦截。"""
    result = scan(user)
    return result


# ---------- 输出侧 DLP：敏感信息脱敏（LLM02 等泄露类） ----------
def sanitize_output(text, session_id="demo-session"):
    """对模型输出做真实 virbius-core DLP 脱敏。

    调用 PyO3 的 `desensitize`，命中 manifest 中的 dlp_rules（如 custom_regex
    匹配密钥）时，把敏感信息替换为占位符（如 {{VIRBIUS_API_KEY_0}}）。

    返回 dict：
      - text     : 脱敏后的文本
      - masked   : 是否发生了脱敏
      - entity   : 命中的实体类型（如 custom_regex / phone_cn）
      - rule_id  : 命中的 DLP 规则 id
    """
    _init_engine()
    if not _engine_ready:
        raise RuntimeError(_engine_error or "virbius_mcp_python 未就绪")

    before = text
    try:
        sanitized = _engine.desensitize(text, session_id)
    except Exception as e:  # noqa: BLE001
        return {"text": text, "masked": False, "entity": None, "rule_id": None,
                "error": str(e)}
    masked = sanitized != before
    return {
        "text": sanitized,
        "masked": masked,
        "entity": "dlp" if masked else None,
        "rule_id": "edge_dlp_*" if masked else None,
        "mode": _active_mode,
    }