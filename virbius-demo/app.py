# -*- coding: utf-8 -*-
"""virbiusDemo · 本地演示台入口。
模块：Dashboard / OWASP LLM Top10 + 全局 VirbiusAgent 防护开关。
被渗透的目标模型：可切换（DeepSeek / OpenRouter / 本地 Ollama）。
"""
import socket
from flask import Flask, render_template, request, jsonify

import config
from modules import modelsel, protection
from modules.owasp import bp as owasp_bp
from modules.agent_range import bp as agent_bp

app = Flask(__name__)
app.secret_key = config.SECRET_KEY

app.register_blueprint(owasp_bp)
app.register_blueprint(agent_bp)


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
    app.run(host="127.0.0.1", port=port, debug=False)