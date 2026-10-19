"""promptfoo custom provider: drive the FULL guardrail chain through the
OpenAI-compatible guardrail proxy (input review -> upstream LLM -> output review).

Unlike engine_provider.py (which posts role=input straight to /v1/evaluate), this
routes an ordinary OpenAI chat request at the proxy, so the whole request path is
exercised end to end. A guardrail block surfaces as the SDK refusal text in the
assistant message, which is normalized into promptfoo's `guardrails` contract.

Env:
  VIRBIUS_PROXY_URL   proxy chat completions url
                      (default http://127.0.0.1:18189/v1/chat/completions)
  PROXY_MODEL         upstream model id (default qwen3.5:4b)
  PROXY_TIMEOUT       seconds (default 60)
  PROXY_REFUSAL       refusal marker (default 安全策略)
"""
import json
import os
import urllib.request

PROXY = os.environ.get(
    "VIRBIUS_PROXY_URL", "http://127.0.0.1:18189/v1/chat/completions")
MODEL = os.environ.get("PROXY_MODEL", "qwen3.5:4b")
TIMEOUT = float(os.environ.get("PROXY_TIMEOUT", "60"))
REFUSAL = os.environ.get("PROXY_REFUSAL", "安全策略")


def call_api(prompt, options, context):
    opts = (options or {}).get("config") or {}
    model = opts.get("model") or MODEL
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
    }
    req = urllib.request.Request(
        PROXY,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:  # fail closed: a transport failure is not a clean decision
        return {"error": "proxy unreachable: %s" % exc}

    choices = body.get("choices") or []
    content = ""
    if choices:
        content = (choices[0].get("message") or {}).get("content") or ""
    flagged = REFUSAL in content
    return {
        "output": content,
        "guardrails": {
            "flagged": flagged,
            "flaggedInput": flagged,
            "reason": "proxy refusal" if flagged else "proxy passed",
        },
        "metadata": {"model": model, "refusal": flagged},
    }
