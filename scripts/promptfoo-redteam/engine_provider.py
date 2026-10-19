"""promptfoo custom provider: wrap VirbiusAgent engine POST /v1/evaluate as a guardrail target.

Normalizes the engine decision into promptfoo's top-level `guardrails` contract, so
benign traffic is graded with `guardrails` and attacks with `not-guardrails`.

Env:
  VIRBIUS_ENGINE_URL        engine base url (default http://127.0.0.1:18082)
  VIRBIUS_TENANT            tenant id sent with each call (default promptfoo-rt)
  VIRBIUS_EVALUATE_TIMEOUT  seconds (default 15)
"""
import json
import os
import time
import urllib.request

ENGINE = os.environ.get("VIRBIUS_ENGINE_URL", "http://127.0.0.1:18082").rstrip("/")
TENANT = os.environ.get("VIRBIUS_TENANT", "promptfoo-rt")
TIMEOUT = float(os.environ.get("VIRBIUS_EVALUATE_TIMEOUT", "15"))

# Enforced actions only. `review` is detect-only (dry_run) and `allow` is a pass;
# promptfoo requires flagged=true to mean an enforced block, so they map to False.
_ENFORCED = {"deny", "block", "challenge"}

_seq = 0


def _ids():
    global _seq
    _seq += 1
    stamp = "%d-%d" % (int(time.time() * 1000), _seq)
    return "pf-session-" + stamp, "pf-trace-" + stamp


def call_api(prompt, options, context):
    session_id, trace_id = _ids()
    payload = {
        "tenant_id": TENANT,
        "role": "input",
        "tool_name": "chat_input",
        "session_id": session_id,
        "trace_id": trace_id,
        "content": prompt,
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
    except Exception as exc:  # fail closed: a transport failure is not a clean decision
        return {"error": "engine unreachable: %s" % exc}

    action = body.get("effective_action")
    if body.get("degraded"):
        return {"error": "engine degraded (decision not authoritative): %s"
                          % json.dumps(body, ensure_ascii=False)}
    if action is None:
        return {"error": "engine response missing effective_action: %s"
                          % json.dumps(body, ensure_ascii=False)}

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
