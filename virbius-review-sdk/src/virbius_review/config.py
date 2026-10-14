"""SDK configuration: engine endpoint, windowing and failure policy.

Everything that used to be engine-side chat-proxy config (window sizes,
refusal text, fail policy) now lives here — the caller owns the review loop.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class ReviewConfig:
    """Caller-side review configuration.

    Attributes:
        engine_url: base URL of virbius-engine, e.g. ``http://engine:8082``.
        timeout: per-request timeout in seconds for evaluate calls.
        fail_open: when the engine is unreachable/times out/limits the call,
            ``True`` (default) returns an allow verdict carrying ``error``;
            ``False`` raises :class:`virbius_review.ReviewError`.
        window_chars: streaming review window hard cap (mirrors the engine's
            ``StreamReviewWindow.windowChars``).
        context_chars: context overlap carried into the next window review.
        stream_first: forward tokens to the client BEFORE the window verdict
            (lower latency; **already-forwarded tokens cannot be retracted**).
        refusal_text: text substituted by :meth:`apply_disposal` on ``reject``.
        input_tool_name / output_tool_name: synthetic tool markers used for
            rule addressing (bind rules via tool scope in the console).
    """

    engine_url: str = "http://127.0.0.1:8082"
    timeout: float = 30.0
    fail_open: bool = True
    window_chars: int = 200
    context_chars: int = 50
    stream_first: bool = False
    refusal_text: str = "抱歉，该内容已被安全策略拦截。"
    input_tool_name: str = "chat_input"
    output_tool_name: str = "chat_completion"
    extra_headers: dict = field(default_factory=dict)
