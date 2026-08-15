# -*- coding: utf-8 -*-
"""virbiusDemo · 本地演示台入口。
模块：Dashboard / OWASP LLM Top10 + 全局 VirbiusAgent 防护开关。
被渗透的目标模型：可切换（DeepSeek / OpenRouter / 本地 Ollama）。
"""
import socket
import requests
from flask import Flask, render_template, request, jsonify

import config
from modules import modelsel, protection
from modules import settings as cfg_store
from modules.owasp import bp as owasp_bp
from modules.agent_range import bp as agent_bp
from modules.ctf import bp as ctf_bp

app = Flask(__name__)
app.secret_key = config.SECRET_KEY

app.register_blueprint(owasp_bp)
app.register_blueprint(agent_bp)
app.register_blueprint(ctf_bp)


@app.context_processor
def inject_models():
    """让所有模板都能拿到目标模型列表与当前选择、防护开关状态。"""
    return {"models": modelsel.MODELS, "current_model": modelsel.current(),
            "protection": protection.is_enabled()}


@app.route("/api/set-model", methods=["POST"])
def set_model():
    m = (request.json or {}).get("model", "")
    ok = modelsel.set_model(m)
    return jsonify({"ok": ok, "current": modelsel.current()})


@app.route("/api/set-protection", methods=["POST"])
def set_protection():
    value = (request.json or {}).get("enabled", False)
    protection.set_enabled(value)
    return jsonify({"ok": True, "enabled": protection.is_enabled()})


# ---------- 基础设置页 ----------
@app.route("/settings/")
def settings_page():
    return render_template("settings.html", conf=cfg_store.all())


@app.route("/api/settings", methods=["POST"])
def save_settings():
    body = request.json or {}
    updates = {}
    # 平台接入（始终提交）
    for src, dst in [("control_url", "VIRBIUS_CONTROL_URL"),
                     ("engine_url", "VIRBIUS_ENGINE_URL"),
                     ("license", "VIRBIUS_LICENSE_JWT")]:
        if src in body:
            updates[dst] = (body.get(src) or "").strip()
    # 模型配置（联动：仅提交当前 provider 的表单）
    provider = body.get("provider")
    if provider == "deepseek" and "deepseek_key" in body:
        updates["DEEPSEEK_API_KEY"] = (body.get("deepseek_key") or "").strip()
    elif provider == "openrouter" and "openrouter_key" in body:
        updates["OPENROUTER_API_KEY"] = (body.get("openrouter_key") or "").strip()
    elif provider == "local" and "ollama_url" in body:
        updates["OLLAMA_BASE_URL"] = (body.get("ollama_url") or "").strip()
    try:
        cfg_store.save(updates)
    except RuntimeError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 500
    # control 地址等变更：清除端层引擎缓存，使新地址下次扫描生效
    protection.reload()
    return jsonify({"ok": True, "conf": cfg_store.all()})


# ---------- 运行时 key 校验（触发聊天前检查） ----------
_KEY_PLACEHOLDER = "sk-REPLACE-ME"


def _model_ready(provider, model):
    if provider == "deepseek":
        key = cfg_store.get("DEEPSEEK_API_KEY")
        if not key or key == _KEY_PLACEHOLDER:
            return False, "使用 DeepSeek 需要先配置 API Key，请到「设置」页填写。"
        return True, ""
    if provider == "openrouter":
        key = cfg_store.get("OPENROUTER_API_KEY")
        if not key:
            return False, "使用 OpenRouter 小模型需要先配置 API Key，请到「设置」页填写。"
        return True, ""
    # 本地 Ollama：无需 key，但需服务可达且模型已安装
    return _local_ready(model)


def _local_ready(model):
    base = cfg_store.get("OLLAMA_BASE_URL")
    tags_url = base[:-len("/v1")] + "/api/tags" if base.endswith("/v1") else base.rstrip("/") + "/api/tags"
    try:
        r = requests.get(tags_url, timeout=3)
        if r.status_code != 200:
            return False, f"无法连接本地 Ollama（{base}）。请确认服务已启动，或在「设置」中检查地址。"
        names = [t.get("name") for t in r.json().get("models", [])]
        if model not in names:
            return False, f"本机 Ollama 未找到模型「{model}」。请先拉取该模型（不做自动下载），再返回重试。"
        return True, ""
    except requests.RequestException:
        return False, f"无法连接本地 Ollama（{base}）。请确认服务已启动，或在「设置」中检查地址。"


@app.route("/api/model-status", methods=["GET"])
def model_status():
    entry = modelsel.current_entry()
    ready, reason = _model_ready(entry["provider"], entry.get("model"))
    return jsonify({
        "model_id": entry["id"],
        "provider": entry["provider"],
        "ready": ready,
        "reason": reason,
        "settings_url": "/settings/",
    })


@app.route("/")
def index():
    return render_template("index.html")


def _pick_port():
    if config.PORT:
        return config.PORT
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))  # 让内核分配随机空闲端口
    port = s.getsockname()[1]
    s.close()
    return port


if __name__ == "__main__":
    port = _pick_port()
    print("=" * 60)
    print("  🛡️  virbiusDemo · VirbiusAgent 攻防演示台")
    print(f"  ➜  http://127.0.0.1:{port}")
    print(f"  目标模型: {config.DEEPSEEK_MODEL} @ {config.DEEPSEEK_BASE_URL}")
    print("=" * 60)
    app.run(host="0.0.0.0", port=port, debug=False)