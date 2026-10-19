#!/usr/bin/env python3
"""OpenAI-compatible /v1/chat/completions proxy that runs VirbiusAgent review
around an upstream LLM, so promptfoo / Tencent AIG can drive the FULL guardrail
chain (input review -> LLM -> output review) instead of only POST /v1/evaluate.

Test scaffolding only — no product code is imported. The review loop (input /
output / streaming window / disposal) is reused verbatim from the official
Python SDK (virbius-review-sdk); this file is just the HTTP shim.

    promptfoo / AIG ──OpenAI──▶ proxy ──review_input──▶ engine /v1/evaluate
                                   │
                                   ├──stream──▶ UPSTREAM_BASE_URL/chat/completions
                                   └──review_output per window──▶ engine

    VIRBIUS_ENGINE_URL   engine base                  (default http://127.0.0.1:8082)
    UPSTREAM_BASE_URL    OpenAI-compatible LLM base, incl. /v1
                                                       (default http://127.0.0.1:11434/v1)
    UPSTREAM_API_KEY     bearer for the upstream LLM   (optional)
    VIRBIUS_TENANT       tenant id sent to the engine  (default default)
    GUARDRAIL_FAIL_OPEN  1 = forward unreviewed on engine outage
                                                       (default 0 = fail CLOSED)
    WINDOW_CHARS         streaming window cap          (default 200)
    CONTEXT_CHARS        streaming window overlap      (default 50)
    PROXY_PORT           listen port                   (default 8089)
    VIRBIUS_REVIEW_SDK_PATH  override the SDK src dir  (default <repo>/virbius-review-sdk/src)

Usage:
    PYTHONPATH=virbius-review-sdk/src python3 scripts/guardrail-openai-proxy/openai_proxy.py
    # then point promptfoo/AIG at http://127.0.0.1:8089/v1 (any non-empty api key)
    python3 scripts/guardrail-openai-proxy/openai_proxy.py --selftest
"""
from __future__ import annotations

import json
import os
import sys
import time
import uuid
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_HERE = os.path.dirname(os.path.abspath(__file__))
_SDK = os.environ.get(
    "VIRBIUS_REVIEW_SDK_PATH",
    os.path.join(_HERE, "..", "..", "virbius-review-sdk", "src"),
)
sys.path.insert(0, os.path.abspath(_SDK))
from virbius_review import Blocked, Delta, Done, ReviewClient, ReviewError  # noqa: E402
from virbius_review.adapters import from_openai_sse  # noqa: E402

ENGINE = os.environ.get("VIRBIUS_ENGINE_URL", "http://127.0.0.1:8082").rstrip("/")
UPSTREAM = os.environ.get("UPSTREAM_BASE_URL", "http://127.0.0.1:11434/v1").rstrip("/")
UPSTREAM_KEY = os.environ.get("UPSTREAM_API_KEY", "")
TENANT = os.environ.get("VIRBIUS_TENANT", "default")
FAIL_OPEN = os.environ.get("GUARDRAIL_FAIL_OPEN", "0") == "1"
WINDOW_CHARS = int(os.environ.get("WINDOW_CHARS", "200"))
CONTEXT_CHARS = int(os.environ.get("CONTEXT_CHARS", "50"))
PORT = int(os.environ.get("PROXY_PORT", "8089"))
TIMEOUT = float(os.environ.get("PROXY_TIMEOUT", "60"))
MODEL = os.environ.get("PROXY_MODEL_ID", "virbius-guarded")

client = ReviewClient(
    engine_url=ENGINE,
    fail_open=FAIL_OPEN,
    window_chars=WINDOW_CHARS,
    context_chars=CONTEXT_CHARS,
    transport=lambda payload: _engine_post(payload),
)


def _engine_post(payload: dict) -> dict:
    """Engine transport on stdlib urllib (no `requests` dependency)."""
    req = urllib.request.Request(
        ENGINE + "/v1/evaluate",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "ignore")
        try:  # 429/401/403 bodies carry the engine's policy decision
            return json.loads(body)
        except Exception:
            raise RuntimeError("engine HTTP %s: %s" % (exc.code, body[:200]))


def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):  # OpenAI multimodal parts
        return "".join(
            p.get("text", "") for p in content
            if isinstance(p, dict) and p.get("type") == "text"
        )
    return ""


def _last_user(messages: list) -> str:
    for msg in reversed(messages):
        if isinstance(msg, dict) and msg.get("role") == "user":
            return _text(msg.get("content"))
    return ""


def _ids() -> tuple:
    stamp = "%d-%s" % (int(time.time() * 1000), uuid.uuid4().hex[:8])
    return "proxy-session-" + stamp, "proxy-trace-" + stamp


def _upstream_headers() -> dict:
    headers = {"Content-Type": "application/json"}
    if UPSTREAM_KEY:
        headers["Authorization"] = "Bearer " + UPSTREAM_KEY
    return headers


def _upstream_open(body: dict, stream: bool):
    data = dict(body)
    data["stream"] = stream
    req = urllib.request.Request(
        UPSTREAM + "/chat/completions",
        data=json.dumps(data).encode(),
        headers=_upstream_headers(),
        method="POST",
    )
    return urllib.request.urlopen(req, timeout=TIMEOUT)


def _upstream_complete(body: dict) -> str:
    with _upstream_open(body, False) as resp:
        data = json.loads(resp.read().decode())
    choices = data.get("choices") or [{}]
    return _text((choices[0].get("message") or {}).get("content"))


def _upstream_stream(body: dict):
    resp = _upstream_open(body, True)
    try:
        for raw in resp:
            yield raw.decode("utf-8", "ignore")
    finally:
        resp.close()


def _chunk(cid: str, model: str, content: str, finish=None) -> bytes:
    payload = {
        "id": cid, "object": "chat.completion.chunk", "model": model,
        "choices": [{"index": 0, "delta": ({"content": content} if content else {}),
                     "finish_reason": finish}],
    }
    return ("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode()


def _completion(cid: str, model: str, content: str, finish="stop") -> dict:
    return {
        "id": cid, "object": "chat.completion", "created": int(time.time()),
        "model": model,
        "choices": [{"index": 0, "message": {"role": "assistant", "content": content},
                     "finish_reason": finish}],
        "usage": {"prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0},
    }


def _error(msg: str, status: int) -> dict:
    return {"error": {"message": msg, "type": "invalid_request_error", "code": status}}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # keep the proxy quiet
        pass

    def _send(self, status: int, body: bytes, content_type: str) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def _json(self, status: int, obj) -> None:
        self._send(status, json.dumps(obj, ensure_ascii=False).encode(), "application/json")

    def do_GET(self):
        path = self.path.rstrip("/")
        if path in ("/v1/models", "/models"):
            return self._json(200, {"object": "list",
                                    "data": [{"id": MODEL, "object": "model"}]})
        if path in ("/health", "/healthz", ""):
            return self._json(200, {"status": "ok"})
        self._json(404, _error("not found", 404))

    def do_POST(self):
        if self.path.rstrip("/") != "/v1/chat/completions":
            return self._json(404, _error("not found", 404))
        try:
            n = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(n) or b"{}")
        except Exception:
            return self._json(400, _error("invalid JSON body", 400))

        messages = body.get("messages") or []
        prompt = _last_user(messages)
        cid = "chatcmpl-" + uuid.uuid4().hex
        model = body.get("model") or MODEL
        sid, tid = _ids()
        want_stream = bool(body.get("stream"))

        try:
            verdict = client.review_input(prompt, tenant_id=TENANT,
                                          session_id=sid, trace_id=tid)
        except ReviewError as exc:
            return self._json(502, _error("guardrail unavailable: %s" % exc, 502))

        if verdict.blocked:
            refusal = client.config.refusal_text
            if want_stream:
                return self._stream_text(cid, model, refusal)
            return self._json(200, _completion(cid, model, refusal))

        if want_stream:
            return self._stream_reviewed(cid, model, body, sid, tid)

        try:
            content = _upstream_complete(body)
            out_verdict = client.review_output(content, tenant_id=TENANT,
                                               session_id=sid, trace_id=tid)
            out = client.apply_disposal(content, out_verdict)
        except ReviewError as exc:
            return self._json(502, _error("guardrail unavailable: %s" % exc, 502))
        except Exception as exc:
            return self._json(502, _error("upstream failed: %s" % exc, 502))
        return self._json(200, _completion(cid, model, out))

    # -- streaming helpers -------------------------------------------------

    def _sse_start(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

    def _stream_text(self, cid: str, model: str, text: str) -> None:
        self._sse_start()
        self.wfile.write(_chunk(cid, model, text))
        self.wfile.write(_chunk(cid, model, "", "stop"))
        self.wfile.write(b"data: [DONE]\n\n")

    def _stream_reviewed(self, cid: str, model: str, body: dict, sid: str, tid: str) -> None:
        self._sse_start()
        self.wfile.write(_chunk(cid, model, ""))  # role preamble
        refusal = client.config.refusal_text
        try:
            deltas = from_openai_sse(_upstream_stream(body))
            blocked = False
            for event in client.wrap_stream(deltas, tenant_id=TENANT,
                                            session_id=sid, trace_id=tid):
                if isinstance(event, Delta):
                    self.wfile.write(_chunk(cid, model, event.text))
                elif isinstance(event, Blocked):
                    blocked = True
                    self.wfile.write(_chunk(cid, model, refusal))
        except ReviewError as exc:
            blocked = True
            self.wfile.write(_chunk(cid, model, " [guardrail unavailable: %s]" % exc))
        self.wfile.write(_chunk(cid, model, "", "stop"))
        self.wfile.write(b"data: [DONE]\n\n")


def _selftest() -> int:
    assert _last_user([{"role": "user", "content": "a"},
                       {"role": "assistant", "content": "b"},
                       {"role": "user", "content": "c"}]) == "c"
    assert _last_user([{"role": "user", "content": [{"type": "text", "text": "hi"}]}]) == "hi"
    assert _text([{"type": "text", "text": "x"}, {"type": "image_url"}]) == "x"
    assert b'"content": "xy"' in _chunk("c", "m", "xy")
    assert b'"finish_reason": "stop"' in _chunk("c", "m", "", "stop")
    print("selftest OK")
    return 0


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        raise SystemExit(_selftest())
    print("[proxy] :%d  engine=%s  upstream=%s  tenant=%s  fail_open=%s"
          % (PORT, ENGINE, UPSTREAM, TENANT, FAIL_OPEN))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
