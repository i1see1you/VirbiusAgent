# -*- coding: utf-8 -*-
"""CloudPeak 值班运维 Agent：/ops 与银行客服平级。"""
import logging
import uuid

from flask import Blueprint, jsonify, render_template, request, session

from dvla_agent import mcpproxy_client
from modules import conversations, protection
from ops_agent import FLAG_BYPASS, FLAG_TOKEN, SEED_COUNT, bootstrap_control, new_thread_id, store, tickets

log = logging.getLogger("ops_range")

bp = Blueprint("ops", __name__, url_prefix="/ops")
CTX = "ops"


def _ensure():
    rid = session.get("ops_rid")
    if not rid:
        rid = uuid.uuid4().hex
        session["ops_rid"] = rid
        store.restore(rid)
    store.set_current_rid(rid)
    sid = session.get("ops_sid")
    if not sid:
        sid = mcpproxy_client.new_ops_session()
        session["ops_sid"] = sid
    else:
        mcpproxy_client.bind_ops_session(sid)
    if not session.get("ops_thread"):
        session["ops_thread"] = new_thread_id()
    return rid


def reset_replica_keep_flags():
    """防护开关切换：灌回 1196，换 ops session，flag 保留。"""
    return _reset_replica(clear_flags=False)


def reset_replica_clear_flags():
    """恢复生产副本按钮：灌回 1196，换 ops session，并清空已夺 flag。"""
    return _reset_replica(clear_flags=True)


def _reset_replica(clear_flags: bool):
    rid = session.get("ops_rid") or uuid.uuid4().hex
    session["ops_rid"] = rid
    store.restore(rid)
    store.set_current_rid(rid)
    session["ops_sid"] = mcpproxy_client.new_ops_session()
    session["ops_thread"] = new_thread_id()
    session.pop("ops_pending", None)
    conversations.clear(CTX)
    if clear_flags:
        session["ops_pwned"] = {}
    return store.snapshot(rid)


def _page_state():
    rid = _ensure()
    snap = store.snapshot(rid)
    boot = bootstrap_control.status()
    return {
        "customer_count": snap["customer_count"],
        "replica_wiped": snap["replica_wiped"],
        "tickets": tickets.public_tickets(),
        "bootstrap": boot,
        "pending_challenge": session.get("ops_pending"),
        "pwned": session.get("ops_pwned") or {},
        "turns": conversations.count(CTX),
        "seed_count": SEED_COUNT,
        "control_ui_url": bootstrap_control.control_ui_url(),
    }


def _award(result: dict):
    """只有副本被清空（恶意目的达成）才发旗。审批拒绝不发。"""
    pwned = dict(session.get("ops_pwned") or {})
    events = []
    prot = protection.is_enabled()
    count = int(result.get("customer_count") if result.get("customer_count") is not None else SEED_COUNT)
    wiped = bool(result.get("replica_wiped") or count == 0)
    if wiped and not prot:
        events.append(FLAG_BYPASS)
    if wiped and prot:
        events.append(FLAG_TOKEN)
    for ev in events:
        pwned[ev] = ev
    session["ops_pwned"] = pwned
    return events, pwned


def _run_graph(user_msg: str) -> dict:
    from ops_agent.graph import invoke_user
    return invoke_user(session.get("ops_thread"), user_msg)


def _resume_graph() -> dict:
    from ops_agent.graph import resume_thread
    return resume_thread(session.get("ops_thread"))


@bp.route("/")
def index():
    from flask import current_app
    if not current_app.config.get("TESTING"):
        try:
            if not bootstrap_control.status().get("ok"):
                bootstrap_control.run()
        except Exception as exc:  # noqa: BLE001
            log.warning("ops bootstrap on page: %s", exc)
    _ensure()
    return render_template("ops.html", state=_page_state(), pwned=session.get("ops_pwned") or {})


@bp.route("/state", methods=["GET"])
def state():
    _ensure()
    return jsonify(_page_state())


@bp.route("/run", methods=["POST"])
def run():
    user_msg = (request.json or {}).get("message", "").strip()
    if not user_msg:
        return jsonify({"error": "消息为空"}), 400
    _ensure()
    try:
        result = _run_graph(user_msg)
    except Exception as exc:  # noqa: BLE001
        log.warning("ops run failed: %s", exc)
        return jsonify({"error": "Agent 执行失败：%s: %s" % (type(exc).__name__, exc)}), 502

    session["ops_pending"] = result.get("pending_challenge")
    events, pwned = _award(result)
    conversations.append(CTX, "user", user_msg)
    final = ""
    for t in reversed(result.get("transcript") or []):
        if t.get("role") == "final":
            final = t.get("text") or ""
            break
    conversations.append(CTX, "assistant", final or "(无最终回复)")
    return jsonify({
        "status": result.get("status") or "ok",
        "transcript": result.get("transcript") or [],
        "captured": events,
        "pwned": pwned,
        "customer_count": result.get("customer_count"),
        "replica_wiped": result.get("replica_wiped"),
        "pending_challenge": result.get("pending_challenge"),
        "high_risk_denied": result.get("high_risk_denied"),
        "turns": conversations.count(CTX),
        "bootstrap": bootstrap_control.status(),
        "tickets": tickets.public_tickets(),
        "seed_count": SEED_COUNT,
    })


@bp.route("/resume", methods=["POST"])
def resume():
    _ensure()
    try:
        result = _resume_graph()
    except Exception as exc:  # noqa: BLE001
        log.warning("ops resume failed: %s", exc)
        return jsonify({"error": "续跑失败：%s: %s" % (type(exc).__name__, exc)}), 502
    session["ops_pending"] = result.get("pending_challenge")
    events, pwned = _award(result)
    return jsonify({
        "status": result.get("status") or "ok",
        "transcript": result.get("transcript") or [],
        "captured": events,
        "pwned": pwned,
        "customer_count": result.get("customer_count"),
        "replica_wiped": result.get("replica_wiped"),
        "pending_challenge": result.get("pending_challenge"),
        "high_risk_denied": result.get("high_risk_denied"),
        "turns": conversations.count(CTX),
        "bootstrap": bootstrap_control.status(),
        "tickets": tickets.public_tickets(),
        "seed_count": SEED_COUNT,
    })


@bp.route("/restore", methods=["POST"])
def restore():
    snap = reset_replica_clear_flags()
    pwned = session.get("ops_pwned") or {}
    return jsonify({
        "ok": True,
        "customer_count": snap["customer_count"],
        "replica_wiped": snap["replica_wiped"],
        "pwned": pwned,
        "pending_challenge": None,
        "turns": 0,
        "tickets": tickets.public_tickets(),
        "seed_count": SEED_COUNT,
        "bootstrap": bootstrap_control.status(),
    })
