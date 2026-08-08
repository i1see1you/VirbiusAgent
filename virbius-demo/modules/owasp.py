# -*- coding: utf-8 -*-
"""OWASP LLM Top 10 靶场：路由 + 各关卡攻击/判定逻辑。"""
from flask import Blueprint, render_template, request, jsonify, session

import llm_client
from modules import conversations, modelsel, inspect_util, protection
from data.owasp_labs import LABS, get_lab

bp = Blueprint("owasp", __name__, url_prefix="/owasp")


@bp.route("/")
def index():
    solved = set(session.get("owasp_solved", []))
    return render_template("owasp.html", labs=LABS, solved=solved)


@bp.route("/lab/<code>")
def lab(code):
    lab = get_lab(code)
    if not lab:
        return "关卡不存在", 404
    solved = set(session.get("owasp_solved", []))
    # 不把 flag 答案泄进模板
    public = {k: v for k, v in lab.items()
              if k not in ("system", "poisoned_doc", "flag_equals")}
    public["flag_equals_hint"] = "flag_equals" in lab
    return render_template("owasp_lab.html", lab=public, solved=solved)


def _build_messages(lab, user_msg):
    """根据关卡类型组装发给模型的消息。"""
    system = lab.get("system", "")
    if lab.get("rag"):
        # 模拟检索：把被投毒文档拼进上下文
        context = lab["poisoned_doc"]
        user = f"【检索到的文档】\n{context}\n\n【用户问题】\n{user_msg}"
        return system, user
    return system, user_msg


@bp.route("/chat/<code>", methods=["POST"])
def chat(code):
    lab = get_lab(code)
    if not lab or not lab.get("interactive"):
        return jsonify({"error": "该关卡不支持对话"}), 400
    user_msg = (request.json or {}).get("message", "").strip()
    if not user_msg:
        return jsonify({"error": "消息为空"}), 400

    system, user = _build_messages(lab, user_msg)
    ctx = f"owasp:{code}"
    history = conversations.get(ctx)
    messages = [{"role": "system", "content": system}] + history + \
               [{"role": "user", "content": user}]

    # ---- VirbiusAgent 防护引擎拦截点（端层 Edge）----
    protection_result = None
    if protection.is_enabled():
        protection_result = protection.evaluate(code, system, user, messages)
        if protection_result["action"] == "block":
            kw = "、".join(protection_result.get("matched_keywords") or [])
            return jsonify({
                "answer": "⛔ 请求已被 VirbiusAgent 端层(Edge)规则拦截。"
                          f"命中规则 {protection_result.get('rule_id')} "
                          f"[{protection_result.get('reason_code')}]"
                          + (f"，触发关键词：{kw}" if kw else ""),
                "auto_solved": False, "turns": conversations.count(ctx),
                "debug": inspect_util.build(messages, "", []),
                "protection": protection_result}), 200

    ent = modelsel.current_entry()
    try:
        answer = llm_client.chat(messages, temperature=0.6, max_tokens=800,
                                 provider=ent["provider"], model=ent["model"])
    except llm_client.LLMError as e:
        return jsonify({"error": str(e)}), 502

    # ---- VirbiusAgent 输出侧 DLP：对模型输出做敏感信息脱敏（LLM02 等泄露类）----
    output_dlp = None
    if protection.is_enabled():
        d = protection.sanitize_output(answer)
        if d.get("masked"):
            output_dlp = d
            answer = d["text"]
            # 输出侧 DLP 已脱敏时，覆盖端层返回的 note，避免误报"端层未命中放行"。
            if protection_result is not None:
                protection_result["note"] = (
                    "VirbiusAgent 输出侧 DLP 已脱敏敏感信息 "
                    f"({output_dlp.get('entity') or 'dlp'})"
                )

    conversations.append(ctx, "user", user)
    conversations.append(ctx, "assistant", answer)

    # 只打码「藏在 system prompt 里的 flag」（行为型目标如 PWNED 保持可见）
    secrets = [t for t in (lab.get("flag_equals"), lab.get("flag_contains"))
               if t and t in system]
    debug = inspect_util.build(messages, answer, secrets)

    # 自动判定（部分关卡，如 XSS / 注入命中即通关）
    auto_solved = False
    if "flag_contains" in lab and lab["flag_contains"].lower() in answer.lower():
        auto_solved = True
    if lab.get("xss") and _looks_like_xss(answer):
        auto_solved = True
    if auto_solved:
        _mark_solved(code)

    return jsonify({"answer": answer, "auto_solved": auto_solved,
                    "turns": conversations.count(ctx), "debug": debug,
                    "protection": protection_result, "output_dlp": output_dlp})


@bp.route("/reset/<code>", methods=["POST"])
def reset(code):
    conversations.clear(f"owasp:{code}")
    return jsonify({"ok": True, "turns": 0})


def _looks_like_xss(text):
    low = text.lower()
    triggers = ["onerror=", "onload=", "<script", "javascript:", "onfocus=", "<svg"]
    return any(t in low for t in triggers)


@bp.route("/submit/<code>", methods=["POST"])
def submit(code):
    lab = get_lab(code)
    if not lab:
        return jsonify({"error": "关卡不存在"}), 404
    guess = (request.json or {}).get("guess", "").strip()
    correct = False
    if "flag_equals" in lab:
        correct = guess == lab["flag_equals"]
    elif "flag_contains" in lab:
        correct = lab["flag_contains"].lower() in guess.lower()
    if correct:
        _mark_solved(code)
    return jsonify({"correct": correct})


def _mark_solved(code):
    solved = set(session.get("owasp_solved", []))
    solved.add(code)
    session["owasp_solved"] = list(solved)
