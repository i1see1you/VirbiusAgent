"""promptfoo provider for skill/tool-call threats derived from SkillSpector patterns.

Extends engine_provider semantics to non-input roles: `tool_call` sends
`args_json` as content plus `tool_name`, so the engine's tool policies and
prompt-injection detector see the same payload the MCP proxy sends.

Vars:
  role       input (default) | tool_call | output
  tool_name  default chat_input
  args_json  JSON object string for tool_call
"""
import json
import os
import time
import urllib.request

ENGINE = os.environ.get("VIRBIUS_ENGINE_URL", "http://127.0.0.1:18082").rstrip("/")
TENANT = os.environ.get("VIRBIUS_TENANT", "promptfoo-rt")
TIMEOUT = float(os.environ.get("VIRBIUS_EVALUATE_TIMEOUT", "60"))

_ENFORCED = {"deny", "block", "challenge"}
_seq = 0


def _ids():
    global _seq
    _seq += 1
    stamp = "%d-%d" % (int(time.time() * 1000), _seq)
    return "pf-session-" + stamp, "pf-trace-" + stamp


def call_api(prompt, options, context):
    vars = (context or {}).get("vars", {}) or {}
    role = vars.get("role") or "input"
    tool_name = vars.get("tool_name") or "chat_input"
    args_json = vars.get("args_json") or ""
    content = args_json if role == "tool_call" else prompt

    session_id, trace_id = _ids()
    payload = {
        "tenant_id": TENANT,
        "role": role,
        "tool_name": tool_name,
        "session_id": session_id,
        "trace_id": trace_id,
        "content": content,
        "args_json": args_json,
        "license_risk_quota": 100,
    }
    req = urllib.request.Request(
        ENGINE + "/v1/evaluate",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:
        return {"error": "engine unreachable: %s" % exc}

    action = body.get("effective_action")
    if body.get("degraded"):
        return {"error": "engine degraded: %s" % json.dumps(body, ensure_ascii=False)}
    if action is None:
        return {"error": "missing effective_action: %s" % json.dumps(body, ensure_ascii=False)}

    flagged = action in _ENFORCED
    outcome = {
        "effective_action": action,
        "rule_id": body.get("rule_id"),
        "reason_code": body.get("reason_code"),
        "max_risk_score": body.get("max_risk_score"),
    }
    return {
        "output": json.dumps(outcome, ensure_ascii=False),
        "guardrails": {
            "flagged": flagged,
            "flaggedInput": flagged,
            "reason": "engine action=%s rule=%s" % (action, body.get("rule_id")),
        },
        "metadata": outcome,
    }
