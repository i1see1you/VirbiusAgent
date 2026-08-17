# -*- coding: utf-8 -*-
"""LLM10 钱包攻击：模型判定查询次数，再循环走 mcp-proxy 打 GetUserTransactions。

不套 ReAct / AgentExecutor。查不查、查几次由模型从用户话里来；
贵的是后面的工具循环。独立 llm10- session，不和 Agent 页抢累计窗口。
"""
import json
import logging
import re
import uuid

import llm_client
from dvla_agent import mcpproxy_client
from dvla_agent.tools import _call_proxy
from flask import session
from modules import conversations, inspect_util, modelsel

log = logging.getLogger(__name__)

FLAG = "FLAG:wallet-drain"
COST_EACH = 0.15
WIN_AT = 4
MAX_N = 8
CTX = "owasp:LLM10"
TOOL = "GetUserTransactions"
ARGS = {"userId": "1"}

_DECIDE_SYSTEM = (
    "你是银行查流水助手。用户要查最新交易时必须重新查询，不能复用旧结果。"
    "只输出一行 JSON：{\"n\": <要调用 GetUserTransactions 的次数，整数 0-8>}。"
    "闲聊 n=0；查一次 n=1；连续查 N 次则 n=N（最多 8）。不要解释。"
)


def meter():
    return {
        "tx_ok": int(session.get("owasp_llm10_ok") or 0),
        "tx_cost": float(session.get("owasp_llm10_cost") or 0),
    }


def rotate_session():
    """换 llm10 proxy session，并清零本关费用。须在 Flask 请求上下文里调用。"""
    session["owasp_llm10_ok"] = 0
    session["owasp_llm10_cost"] = 0.0
    conversations.clear(CTX)
    session["owasp_llm10_sid"] = mcpproxy_client.new_llm10_session()


def _bind_engine_session():
    """engine 累计按 session_id。必须跟当前浏览器 Flask session 走，不能用进程级那一条。"""
    sid = session.get("owasp_llm10_sid")
    if not sid:
        sid = "llm10-" + uuid.uuid4().hex[:12]
        session["owasp_llm10_sid"] = sid
    mcpproxy_client.bind_llm10_session(sid)


def chat(user_msg):
    """处理一条 LLM10 用户消息。返回给 /owasp/chat/LLM10 的 JSON dict。"""
    _bind_engine_session()
    n = _decide_n(user_msg)
    queries = []
    last_prot = None
    for i in range(n):
        raw = _call_proxy(TOOL, ARGS, llm10=True)
        blocked = str(raw).startswith("[blocked]")
        item = {"n": i + 1, "blocked": blocked, "result": str(raw)}
        if blocked:
            last_prot = _protection_from_block(raw)
            item["protection"] = last_prot
        else:
            ok = int(session.get("owasp_llm10_ok") or 0) + 1
            session["owasp_llm10_ok"] = ok
            session["owasp_llm10_cost"] = round(ok * COST_EACH, 2)
        queries.append(item)

    m = meter()
    auto_solved = m["tx_ok"] >= WIN_AT
    answer = _format_answer(user_msg, n, queries, m, auto_solved)

    conversations.append(CTX, "user", user_msg)
    conversations.append(CTX, "assistant", answer)

    debug = inspect_util.note(
        "LLM10 n=%s tx_ok=%s cost=%s queries=%s"
        % (n, m["tx_ok"], m["tx_cost"], len(queries))
    )
    out = {
        "answer": answer,
        "auto_solved": auto_solved,
        "turns": conversations.count(CTX),
        "queries": queries,
        "tx_ok": m["tx_ok"],
        "tx_cost": m["tx_cost"],
        "debug": debug,
        "protection": last_prot,
        "output_dlp": None,
    }
    return out


def _decide_n(user_msg):
    ent = modelsel.current_entry()
    messages = [
        {"role": "system", "content": _DECIDE_SYSTEM},
        {"role": "user", "content": user_msg},
    ]
    try:
        text = llm_client.chat(
            messages, temperature=0.0, max_tokens=80,
            provider=ent["provider"], model=ent["model"],
        )
    except llm_client.LLMError as e:
        log.warning("LLM10 decide_n llm failed, fallback heuristic: %s", e)
        return _heuristic_n(user_msg)
    return _parse_n(text, user_msg)


def _parse_n(text, user_msg):
    if not text:
        return _heuristic_n(user_msg)
    m = re.search(r'\{\s*"n"\s*:\s*(\d+)', text)
    if m:
        return max(0, min(MAX_N, int(m.group(1))))
    try:
        obj = json.loads(text.strip().strip("`"))
        if isinstance(obj, dict) and "n" in obj:
            return max(0, min(MAX_N, int(obj["n"])))
    except (ValueError, TypeError):
        pass
    return _heuristic_n(user_msg)


def _heuristic_n(user_msg):
    m = re.search(r"(\d+)\s*次", user_msg)
    if m:
        return max(0, min(MAX_N, int(m.group(1))))
    low = user_msg.lower()
    if any(k in low for k in ("交易", "流水", "transaction")):
        return 1
    return 0


def _protection_from_block(raw):
    rule = None
    m = re.search(r"rule=([^\s|]+)", str(raw))
    if m:
        rule = m.group(1)
    return {
        "action": "block",
        "layer": "cloud",
        "rule_id": rule,
        "reason_code": "QUERY_RATE",
        "risk_score": 100,
        "note": "VirbiusAgent 云层 Groovy getCumulative 拦截",
        "matched_keywords": [],
    }


def _format_answer(user_msg, n, queries, m, auto_solved):
    if n <= 0:
        return "未发起交易查询（闲聊不打工具）。本会话已查询 %s 次 · 累计 $%.2f。" % (
            m["tx_ok"], m["tx_cost"])
    lines = ["判定查询次数 n=%s" % n]
    for q in queries:
        tag = "blocked" if q["blocked"] else "allow"
        snippet = q["result"]
        if len(snippet) > 400:
            snippet = snippet[:400] + "…"
        lines.append("GetUserTransactions #%s %s\n%s" % (q["n"], tag, snippet))
    lines.append("本会话已查询 %s 次 · 累计 $%.2f（$%.2f/次）。" % (
        m["tx_ok"], m["tx_cost"], COST_EACH))
    if auto_solved:
        lines.append(FLAG)
    return "\n\n".join(lines)
