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
        fail_open: when the engine is unreachable/times out, ``True`` (default)
            returns an allow verdict carrying ``error``; ``False`` raises
            :class:`virbius_review.ReviewError`. Rate limiting (HTTP 429) is
            NOT covered by this switch — a throttle always fails closed.
        window_chars: streaming review window hard cap (mirrors the engine's
            ``StreamReviewWindow.windowChars``).
        context_chars: context overlap carried into the next window review.
        stream_first: forward tokens to the client BEFORE the window verdict
            (lower latency; **already-forwarded tokens cannot be retracted**).
        refusal_text: text substituted by :meth:`apply_disposal` on ``reject``.
        input_tool_name / output_tool_name: synthetic tool markers used for
            rule addressing (bind rules via tool scope in the console).
        auth_token: a static short-lived evaluate token (rarely needed — prefer
            ``token_provider`` or ``license_jwt`` so it is refreshed for you).
        license_jwt: the caller's long-lived License JWT. When set with
            ``auth_url``, the SDK exchanges it ONCE for a short-lived evaluate
            token and refreshes automatically — the License JWT itself never
            travels per-request.
        auth_url: control-plane base URL hosting ``POST /api/v1/auth/evaluate-token``.
        token_provider: a zero-arg callable returning a fresh bearer token,
            evaluated before every call (supports mid-stream refresh).
        auth_fail_open: policy for HTTP 401/403 (auth failure). Default ``False``
            (fail CLOSED — a rejected credential must never pass content
            unreviewed); set ``True`` only when unauthenticated fallback is
            explicitly acceptable.
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
    auth_token: str = ""
    license_jwt: str = ""
    auth_url: str = ""
    token_provider: object = None
    auth_fail_open: bool = False
