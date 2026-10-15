"""Core review logic shared by the sync and async clients.

Pure helpers (payload building, verdict mapping, disposal application and the
streaming window driver) live here; the HTTP layer is injected as a callable
so the core has zero HTTP dependencies and is trivially testable.
"""

from __future__ import annotations

from typing import Callable, Iterable

from .config import ReviewConfig
from .models import Blocked, Delta, Done, InputVerdict, OutputVerdict
from .window import StreamReviewWindow


class ReviewError(Exception):
    """Raised when ``fail_open=False`` and the engine call fails."""


# -- payload / verdict mapping (shared by sync and async) ------------------

def input_payload(cfg: ReviewConfig, content: str, **ctx) -> dict:
    return _payload(cfg, role="input", tool_name=cfg.input_tool_name,
                    content=content, stream_chunk=False, **ctx)


def output_payload(cfg: ReviewConfig, content: str, stream_chunk: bool, **ctx) -> dict:
    return _payload(cfg, role="output", tool_name=cfg.output_tool_name,
                    content=content, stream_chunk=stream_chunk, **ctx)


def input_verdict(data: dict) -> InputVerdict:
    action = str(data.get("effective_action", "allow"))
    blocked = action.lower() in ("deny", "block")
    return InputVerdict(
        blocked=blocked,
        effective_action=action,
        reason_code=data.get("reason_code"),
        rule_id=data.get("rule_id"),
        risk_score=int(data.get("max_risk_score") or 0),
        raw=data,
    )


def output_verdict(data: dict) -> OutputVerdict:
    action = str(data.get("effective_action", "allow"))
    blocked = action.lower() in ("deny", "block")
    disposal = data.get("disposal_action")
    if not disposal:
        if "error" not in data:
            # The engine always returns disposal_action for role=output; a
            # response without it (and without a local fail-open marker) is a
            # contract violation — fail loudly instead of guessing.
            raise ReviewError(
                "engine response for role=output is missing disposal_action "
                "(contract violation)")
        # Local fail-open verdict: no engine contract to enforce.
        disposal = "reject" if blocked else "warn"
    return OutputVerdict(
        blocked=blocked,
        disposal_action=str(disposal),
        sanitized_content=data.get("sanitized_content"),
        effective_action=action,
        reason_code=data.get("reason_code"),
        rule_id=data.get("rule_id"),
        risk_score=int(data.get("max_risk_score") or 0),
        raw=data,
    )


def allow_on_error(cfg: ReviewConfig, message: str) -> dict:
    """fail-open verdict payload (the raw dict carries the error)."""
    return {"effective_action": "allow", "error": message}


def raise_or_fail_open(cfg: ReviewConfig, message: str) -> dict:
    if not cfg.fail_open:
        raise ReviewError(message)
    return allow_on_error(cfg, message)


def rate_limited_error(retry_after_seconds) -> None:
    """Rate limiting (HTTP 429) always fails CLOSED — no fail-open opt-in.

    A throttle is a deliberate decision by the engine, not an outage: with
    fail-open, an abusive client could flood the limiter and have every
    subsequent window forwarded unreviewed (a review bypass). The engine and
    the SDK ship together, so there is no version-skew excuse either.
    """
    raise ReviewError(f"rate_limited (retry_after {retry_after_seconds}s)")


def apply_disposal(cfg: ReviewConfig, content: str, verdict: OutputVerdict) -> str:
    """Apply the disposal verbatim — the canonical enforcement mapping.

    ``reject`` → refusal text; ``omit`` → empty; ``redact`` → sanitized text;
    ``warn`` → original content. One mapping, every caller identical.
    """
    if not verdict.blocked:
        return content
    if verdict.disposal_action == "reject":
        return cfg.refusal_text
    if verdict.disposal_action == "omit":
        return ""
    if verdict.disposal_action == "redact":
        return verdict.sanitized_content if verdict.sanitized_content is not None else cfg.refusal_text
    return content  # warn


# -- streaming window driver ------------------------------------------------

def wrap_stream_events(
    review_window: Callable[[str], OutputVerdict],
    cfg: ReviewConfig,
    deltas: Iterable[str],
):
    """Streaming review over a delta iterable (sync generator).

    ``review_window(text)`` performs one ``role=output, stream_chunk=true``
    evaluate call per due window. Yields:

    - :class:`Delta` — reviewed content safe to forward (never the overlap);
    - :class:`Blocked` — stop forwarding, abort the upstream stream and emit
      a violation to the user;
    - :class:`Done` — every window passed.

    ``stream_first=True`` forwards each delta BEFORE its window verdict
    (lower latency; already-forwarded tokens cannot be retracted).
    """
    window = StreamReviewWindow(cfg.window_chars, cfg.context_chars)
    for delta in deltas:
        if cfg.stream_first and delta:
            yield Delta(delta)
        due = window.due(delta, False)
        if due is None:
            continue
        verdict = review_window(due)
        if verdict.blocked:
            window.reject()
            yield Blocked(verdict.reason_code, verdict.rule_id,
                          verdict.risk_score, verdict.disposal_action)
            return
        forward = window.accept()
        if not cfg.stream_first and forward:
            yield Delta(forward)
    due = window.due("", True)  # flush the tail window at end-of-stream
    if due is not None:
        verdict = review_window(due)
        if verdict.blocked:
            window.reject()
            yield Blocked(verdict.reason_code, verdict.rule_id,
                          verdict.risk_score, verdict.disposal_action)
            return
        forward = window.accept()
        if not cfg.stream_first and forward:
            yield Delta(forward)
    yield Done()


# -- internals -------------------------------------------------------------

def _payload(cfg: ReviewConfig, role: str, tool_name: str, content: str,
             stream_chunk: bool, **ctx) -> dict:
    payload = {
        "tenant_id": ctx.get("tenant_id", "default"),
        "role": role,
        "tool_name": tool_name,
        "content": content,
        "stream_chunk": bool(stream_chunk),
    }
    for key in ("session_id", "trace_id", "user_id"):
        value = ctx.get(key)
        if value:
            payload[key] = value
    return payload
