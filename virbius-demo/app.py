# -*- coding: utf-8 -*-
"""virbiusDemo · 本地演示台入口。
模块：Dashboard / OWASP LLM Top10 + 全局 VirbiusAgent 防护开关。
被渗透的目标模型：可切换（DeepSeek / OpenRouter / 本地 Ollama）。
"""
import logging
import socket
import json
import os
import shutil
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
    """把记忆拦截开关写到 mcp-proxy 默认路径 ./data/edge/default/edge-manifest.json。"""
    root = os.path.dirname(os.path.abspath(__file__))
    src = os.path.join(root, "demo_data", "memory_edge_manifest.json")
    dst_dir = os.path.join(root, "data", "edge", "default")
    dst = os.path.join(dst_dir, "edge-manifest.json")
    if not os.path.isfile(src):
        logging.getLogger("app").warning("memory edge manifest missing: %s", src)
        return
    os.makedirs(dst_dir, exist_ok=True)
    shutil.copyfile(src, dst)


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
    if "VIRBIUS_LICENSE_JWT" in updates:
        from dvla_agent import mcpproxy_client
        mcpproxy_client.drop_all_proxy_clients()
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


def _warmup_exfil_groovy():
    """JIT-compile cloud_exfil_chain_deny. Cold Groovy L3 has a 50ms cap and
    fail-opens; the first real SendEmail would then look like 'protection off'."""
    log = logging.getLogger("warmup")

    def _post(url, payload):
        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode(),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.load(resp)

    def run():
        time.sleep(2)
        engine = (cfg_store.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
        if not engine:
            return
        url = engine + "/v1/evaluate"
        sid = "groovy-warmup"
        notice = {
            "tenant_id": "default", "session_id": sid, "user_id": "1",
            "tool_name": "GetBankNotice", "role": "tool_call",
            "args_json": json.dumps({"topic": "reconcile"}),
            "vars": {"app_id": "demo-app"},
        }
        mail = {
            "tenant_id": "default", "session_id": sid, "user_id": "1",
            "tool_name": "SendEmail", "role": "tool_call",
            "args_json": json.dumps({"to": "recon@clearing-partner.com", "body": "warmup"}),
            "vars": {"app_id": "demo-app"},
        }
        for i in range(4):
            try:
                _post(url, notice)
                data = _post(url, mail)
                log.info(
                    "exfil groovy warmup %s action=%s rule=%s",
                    i, data.get("effective_action"), data.get("rule_id"),
                )
                tx = {
                    "tenant_id": "default", "session_id": "groovy-warmup-rate",
                    "user_id": "1", "tool_name": "GetUserTransactions",
                    "role": "tool_call",
                    "args_json": json.dumps({"userId": "1"}),
                    "vars": {"app_id": "demo-app"},
                }
                _post(url, tx)
                pay = {
                    "tenant_id": "default", "session_id": "groovy-warmup-llm06",
                    "user_id": "1", "tool_name": "PayoutToAccount",
                    "role": "tool_call",
                    "args_json": json.dumps({
                        "expense_id": "EXP-WARMUP",
                        "account": "6222-0000-8888",
                        "amount": 3280,
                    }),
                    "vars": {"app_id": "demo-app"},
                }
                _post(url, pay)
                if data.get("effective_action") in ("block", "deny"):
                    return
            except Exception as exc:  # noqa: BLE001
                log.warning("exfil groovy warmup failed: %s", exc)
            time.sleep(1)

    threading.Thread(target=run, daemon=True, name="groovy-warmup").start()


def _bootstrap_ops():
    log = logging.getLogger("ops.bootstrap")

    def run():
        time.sleep(1)
        try:
            from ops_agent import bootstrap_control
            st = bootstrap_control.run()
            log.info("ops bootstrap ok=%s err=%s", st.get("ok"), st.get("error") or "")
        except Exception as exc:  # noqa: BLE001
            log.warning("ops bootstrap thread failed: %s", exc)
        engine = (cfg_store.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
        if not engine:
            return
        url = engine + "/v1/evaluate"
        payload = {
            "tenant_id": "default", "session_id": "ops-groovy-warmup", "user_id": "1",
            "tool_name": "drop_production_table", "role": "tool_call",
            "args_json": json.dumps({"table": "customers"}),
            "vars": {"app_id": "demo-app"},
        }
        for i in range(4):
            try:
                req = urllib.request.Request(
                    url, data=json.dumps(payload).encode(), method="POST",
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(req, timeout=20) as resp:
                    data = json.load(resp)
                log.info(
                    "ops groovy warmup %s action=%s rule=%s",
                    i, data.get("effective_action"), data.get("rule_id"),
                )
                if data.get("effective_action") in ("challenge", "block", "deny"):
                    return
            except Exception as exc:  # noqa: BLE001
                log.warning("ops groovy warmup failed: %s", exc)
            time.sleep(1)

    threading.Thread(target=run, daemon=True, name="ops-bootstrap").start()


def _bootstrap_egress():
    log = logging.getLogger("egress.bootstrap")

    def run():
        time.sleep(2)
        try:
            from egress_agent import bootstrap_control
            st = bootstrap_control.run()
            log.info("egress bootstrap ok=%s err=%s", st.get("ok"), st.get("error") or "")
        except Exception as exc:  # noqa: BLE001
            log.warning("egress bootstrap thread failed: %s", exc)
        engine = (cfg_store.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
        if engine:
            url = engine + "/v1/evaluate"
            payload = {
                "tenant_id": "default", "session_id": "egr-groovy-warmup", "user_id": "1",
                "tool_name": "send_mail", "role": "tool_call",
                "args_json": json.dumps({"to": "leak@evil.com", "body": "warmup"}),
                "vars": {"app_id": "demo-app"},
            }
            for i in range(4):
                try:
                    req = urllib.request.Request(
                        url, data=json.dumps(payload).encode(), method="POST",
                        headers={"Content-Type": "application/json"},
                    )
                    with urllib.request.urlopen(req, timeout=20) as resp:
                        data = json.load(resp)
                    log.info(
                        "egress groovy warmup %s action=%s rule=%s",
                        i, data.get("effective_action"), data.get("rule_id"),
                    )
                    if data.get("effective_action") in ("block", "deny"):
                        break
                except Exception as exc:  # noqa: BLE001
                    log.warning("egress groovy warmup failed: %s", exc)
                time.sleep(1)
        try:
            from dvla_agent import mcpproxy_client
            out = mcpproxy_client.call_tool_egr(
                "http_get", {"url": "https://flag.evil.com/x"},
            )
            log.info("egress sidecar warmup http_get=%s", str(out)[:240])
        except Exception as exc:  # noqa: BLE001
            log.warning("egress sidecar warmup failed: %s", exc)

    threading.Thread(target=run, daemon=True, name="egress-bootstrap").start()


def _bootstrap_chain():
    log = logging.getLogger("chain.bootstrap")

    def run():
        time.sleep(3)
        try:
            from chain_agent import bootstrap_control
            st = bootstrap_control.run()
            log.info("chain bootstrap ok=%s err=%s", st.get("ok"), st.get("error") or "")
        except Exception as exc:  # noqa: BLE001
            log.warning("chain bootstrap thread failed: %s", exc)
        engine = (cfg_store.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
        if not engine:
            return
        url = engine + "/v1/evaluate"
        payload = {
            "tenant_id": "default", "session_id": "chain-groovy-warmup", "user_id": "1",
            "tool_name": "delete_file", "role": "tool_call",
            "args_json": json.dumps({"path": "tmp/cache/warmup.txt"}),
            "vars": {"app_id": "demo-app"},
        }
        for i in range(4):
            try:
                req = urllib.request.Request(
                    url, data=json.dumps(payload).encode(), method="POST",
                    headers={"Content-Type": "application/json"},
                )
                with urllib.request.urlopen(req, timeout=20) as resp:
                    data = json.load(resp)
                log.info(
                    "chain groovy warmup %s action=%s rule=%s",
                    i, data.get("effective_action"), data.get("rule_id"),
                )
                if data.get("effective_action") in ("block", "deny", "allow"):
                    break
            except Exception as exc:  # noqa: BLE001
                log.warning("chain groovy warmup failed: %s", exc)
            time.sleep(1)

    threading.Thread(target=run, daemon=True, name="chain-bootstrap").start()


if __name__ == "__main__":
    port = _pick_port()
    print("=" * 60)
    print("  🛡️  virbiusDemo · VirbiusAgent 攻防演示台")
    print(f"  ➜  http://127.0.0.1:{port}")
    print(f"  目标模型: {config.DEEPSEEK_MODEL} @ {config.DEEPSEEK_BASE_URL}")
    print("=" * 60)
    _warmup_exfil_groovy()
    _bootstrap_ops()
    _bootstrap_egress()
    _bootstrap_chain()
    app.run(host="0.0.0.0", port=port, debug=False)
