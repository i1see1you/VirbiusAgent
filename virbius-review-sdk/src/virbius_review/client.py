"""Sync review client (requests) and async client (httpx, optional extra).

Both share the mapping/streaming core in :mod:`virbius_review.core`; only the
HTTP call layer differs, so behavior is identical.
"""

from __future__ import annotations

from typing import Callable, Iterable, Optional

from . import core
from ._token import TokenManager
from .config import ReviewConfig
from .models import Blocked, Delta, Done, InputVerdict, OutputVerdict
from .window import StreamReviewWindow

__all__ = ["ReviewClient", "AsyncReviewClient", "ReviewAsyncUnavailable"]


class ReviewClient:
    """Synchronous review client (primary; uses ``requests``).

    Inject ``transport`` (a ``dict -> dict`` callable) for testing or custom
    routing; by default it POSTs to ``{engine_url}/v1/evaluate``. Custom
    transports must return the engine's JSON body for HTTP 429 instead of
    raising — a raised 429 is indistinguishable from a transport failure and
    then follows the ``fail_open`` policy instead of failing closed.
    """

    def __init__(
        self,
        engine_url: str = "http://127.0.0.1:8082",
        *,
        timeout: float = 30.0,
        fail_open: bool = True,
        window_chars: int = 200,
        context_chars: int = 50,
        stream_first: bool = False,
        refusal_text: str = "抱歉，该内容已被安全策略拦截。",
        transport: Optional[Callable[[dict], dict]] = None,
        session=None,
        **kwargs,
    ) -> None:
        self.config = ReviewConfig(
            engine_url=engine_url.rstrip("/"),
            timeout=timeout,
            fail_open=fail_open,
            window_chars=window_chars,
            context_chars=context_chars,
            stream_first=stream_first,
            refusal_text=refusal_text,
            **kwargs,
        )
        self._session = session
        self._transport = transport or self._requests_post
        self._tokens = TokenManager(self.config)
        self._current_token = ""

    def current_token(self) -> str:
        """The bearer token attached to the most recent call (custom transports
        read this to attach ``Authorization`` themselves)."""
        return self._current_token

    # -- review API --------------------------------------------------------

    def review_input(self, content: str, **ctx) -> InputVerdict:
        """Pre-flight input review (``role=input``) — call BEFORE the LLM."""
        payload = core.input_payload(self.config, content, **ctx)
        return core.input_verdict(self._call(payload))

    def review_output(self, content: str, stream_chunk: bool = False, **ctx) -> OutputVerdict:
        """Output review (``role=output``) — after the answer (or per window)."""
        payload = core.output_payload(self.config, content, stream_chunk, **ctx)
        return core.output_verdict(self._call(payload))

    def apply_disposal(self, content: str, verdict: OutputVerdict) -> str:
        return core.apply_disposal(self.config, content, verdict)

    def wrap_stream(self, deltas: Iterable[str], **ctx) -> Iterable[object]:
        """Streamed output review — yields Delta / Blocked / Done events.

        On :class:`Blocked`, break out of the loop and abort the upstream
        stream: the generator stops consuming and nothing further is yielded.
        """
        def review_window(text: str) -> OutputVerdict:
            return self.review_output(text, stream_chunk=True, **ctx)

        return core.wrap_stream_events(review_window, self.config, deltas)

    # -- call layer --------------------------------------------------------

    def _call(self, payload: dict) -> dict:
        # Resolve the bearer token BEFORE the transport (may itself refresh).
        self._current_token = self._tokens.current() if self._tokens.configured else ""
        try:
            data = self._transport(payload)
        except Exception as exc:  # transport error / timeout / 5xx
            return core.raise_or_fail_open(self.config, str(exc))
        if not isinstance(data, dict):
            return core.raise_or_fail_open(self.config, f"unexpected engine response: {data!r}")
        if data.get("error") == "rate_limited":
            # Deliberate throttle: always fails closed — never a fail-open
            # verdict, otherwise flooding the limiter would bypass review.
            core.rate_limited_error(data.get("retry_after_seconds"))
        if data.get("error") == "unauthorized":
            # Auth failure: fails closed by default (AGT design principle 4).
            if self.config.auth_fail_open:
                return core.allow_on_error(
                    self.config, f"unauthorized ({data.get('reason', 'unknown')})")
            core.auth_error(data.get("reason", "unknown"))
        return data

    def _requests_post(self, payload: dict) -> dict:
        import requests

        headers = dict(self.config.extra_headers)
        if self._current_token:
            headers["Authorization"] = "Bearer " + self._current_token
        kwargs = {"timeout": self.config.timeout}
        if headers:
            kwargs["headers"] = headers
        resp = (self._session or requests).post(
            self.config.engine_url + "/v1/evaluate", json=payload, **kwargs)
        if resp.status_code in (401, 403):
            # Return the engine's auth-error body so _call applies the
            # (default fail-closed) auth policy instead of treating it as a
            # transport failure subject to fail_open.
            try:
                return resp.json()
            except Exception:
                pass
        if resp.status_code == 429:
            # Return the engine's rate-limit body instead of raising so _call
            # applies the (default fail-closed) rate-limit policy — a raised
            # 429 would be indistinguishable from a transport failure.
            try:
                return resp.json()
            except Exception:
                pass
        resp.raise_for_status()
        return resp.json()


class ReviewAsyncUnavailable(ImportError):
    pass


class AsyncReviewClient:
    """Async review client (uses ``httpx``; install ``virbius-review-sdk[async]``).

    Usage::

        async with AsyncReviewClient("http://engine:8082") as client:
            v = await client.review_input(msg, session_id=s, trace_id=t)
            async for ev in client.wrap_stream_async(deltas, session_id=s, trace_id=t):
                ...
    """

    def __init__(
        self,
        engine_url: str = "http://127.0.0.1:8082",
        *,
        timeout: float = 30.0,
        fail_open: bool = True,
        window_chars: int = 200,
        context_chars: int = 50,
        stream_first: bool = False,
        refusal_text: str = "抱歉，该内容已被安全策略拦截。",
        transport: Optional[Callable] = None,
        **kwargs,
    ) -> None:
        self.config = ReviewConfig(
            engine_url=engine_url.rstrip("/"),
            timeout=timeout,
            fail_open=fail_open,
            window_chars=window_chars,
            context_chars=context_chars,
            stream_first=stream_first,
            refusal_text=refusal_text,
            **kwargs,
        )
        self._transport = transport
        self._http = None
        self._tokens = TokenManager(self.config)
        self._current_token = ""
        if transport is None:
            try:
                import httpx  # noqa: F401
            except ImportError as exc:  # pragma: no cover
                raise ReviewAsyncUnavailable(
                    "httpx is required for AsyncReviewClient: "
                    "pip install 'virbius-review-sdk[async]'") from exc

    def current_token(self) -> str:
        """The bearer token attached to the most recent call (custom transports
        read this to attach ``Authorization`` themselves)."""
        return self._current_token

    async def __aenter__(self) -> "AsyncReviewClient":
        return self

    async def __aexit__(self, *exc) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        """Close the underlying HTTP client (also aborts an in-flight stream)."""
        if self._http is not None:
            await self._http.aclose()
            self._http = None

    # -- review API --------------------------------------------------------

    async def review_input_async(self, content: str, **ctx) -> InputVerdict:
        payload = core.input_payload(self.config, content, **ctx)
        return core.input_verdict(await self._call(payload))

    async def review_output_async(self, content: str, stream_chunk: bool = False, **ctx) -> OutputVerdict:
        payload = core.output_payload(self.config, content, stream_chunk, **ctx)
        return core.output_verdict(await self._call(payload))

    async def wrap_stream_async(self, deltas, **ctx):
        """Async generator mirroring ``ReviewClient.wrap_stream``."""
        cfg = self.config
        window = StreamReviewWindow(cfg.window_chars, cfg.context_chars)

        async def review_window(text: str) -> OutputVerdict:
            return await self.review_output_async(text, stream_chunk=True, **ctx)

        async for delta in _aiter(deltas):
            if cfg.stream_first and delta:
                yield Delta(delta)
            due = window.due(delta, False)
            if due is None:
                continue
            verdict = await review_window(due)
            if verdict.blocked:
                window.reject()
                yield Blocked(verdict.reason_code, verdict.rule_id,
                              verdict.risk_score, verdict.disposal_action)
                return
            forward = window.accept()
            if not cfg.stream_first and forward:
                yield Delta(forward)
        due = window.due("", True)
        if due is not None:
            verdict = await review_window(due)
            if verdict.blocked:
                window.reject()
                yield Blocked(verdict.reason_code, verdict.rule_id,
                              verdict.risk_score, verdict.disposal_action)
                return
            forward = window.accept()
            if not cfg.stream_first and forward:
                yield Delta(forward)
        yield Done()

    # -- call layer --------------------------------------------------------

    async def _call(self, payload: dict) -> dict:
        self._current_token = self._tokens.current() if self._tokens.configured else ""
        try:
            data = await self._post(payload)
        except Exception as exc:
            return core.raise_or_fail_open(self.config, str(exc))
        if not isinstance(data, dict):
            return core.raise_or_fail_open(self.config, f"unexpected engine response: {data!r}")
        if data.get("error") == "rate_limited":
            # Deliberate throttle: always fails closed (see core).
            core.rate_limited_error(data.get("retry_after_seconds"))
        if data.get("error") == "unauthorized":
            if self.config.auth_fail_open:
                return core.allow_on_error(
                    self.config, f"unauthorized ({data.get('reason', 'unknown')})")
            core.auth_error(data.get("reason", "unknown"))
        return data

    async def _post(self, payload: dict) -> dict:
        if self._transport is not None:
            result = self._transport(payload)
            if hasattr(result, "__await__"):
                return await result
            return result
        if self._http is None:
            import httpx

            self._http = httpx.AsyncClient(timeout=self.config.timeout)
        headers = dict(self.config.extra_headers)
        if self._current_token:
            headers["Authorization"] = "Bearer " + self._current_token
        resp = await self._http.post(
            self.config.engine_url + "/v1/evaluate", json=payload,
            headers=headers or None)
        if resp.status_code in (401, 403):
            try:
                return resp.json()
            except Exception:
                pass
        if resp.status_code == 429:
            # Return the rate-limit body so _call applies the fail-closed
            # policy instead of treating 429 as a transport failure.
            try:
                return resp.json()
            except Exception:
                pass
        resp.raise_for_status()
        return resp.json()


async def _aiter(iterable):
    if hasattr(iterable, "__aiter__"):
        async for item in iterable:
            yield item
    else:
        for item in iterable:
            yield item
