"""Sync review client (requests) and async client (httpx, optional extra).

Both share the mapping/streaming core in :mod:`virbius_review.core`; only the
HTTP call layer differs, so behavior is identical.
"""

from __future__ import annotations

from typing import Callable, Iterable, Optional

from . import core
from .config import ReviewConfig
from .models import Blocked, Delta, Done, InputVerdict, OutputVerdict
from .window import StreamReviewWindow

__all__ = ["ReviewClient", "AsyncReviewClient", "ReviewAsyncUnavailable"]


class ReviewClient:
    """Synchronous review client (primary; uses ``requests``).

    Inject ``transport`` (a ``dict -> dict`` callable) for testing or custom
    routing; by default it POSTs to ``{engine_url}/v1/evaluate``.
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
        try:
            data = self._transport(payload)
        except Exception as exc:  # transport error / 429 / timeout
            return core.raise_or_fail_open(self.config, str(exc))
        if not isinstance(data, dict):
            return core.raise_or_fail_open(self.config, f"unexpected engine response: {data!r}")
        if data.get("error") == "rate_limited":
            return core.raise_or_fail_open(
                self.config, f"rate_limited (retry_after {data.get('retry_after_seconds')}s)")
        return data

    def _requests_post(self, payload: dict) -> dict:
        import requests

        kwargs = {"timeout": self.config.timeout}
        if self.config.extra_headers:
            kwargs["headers"] = dict(self.config.extra_headers)
        resp = (self._session or requests).post(
            self.config.engine_url + "/v1/evaluate", json=payload, **kwargs)
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
        if transport is None:
            try:
                import httpx  # noqa: F401
            except ImportError as exc:  # pragma: no cover
                raise ReviewAsyncUnavailable(
                    "httpx is required for AsyncReviewClient: "
                    "pip install 'virbius-review-sdk[async]'") from exc

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
        try:
            data = await self._post(payload)
        except Exception as exc:
            return core.raise_or_fail_open(self.config, str(exc))
        if not isinstance(data, dict):
            return core.raise_or_fail_open(self.config, f"unexpected engine response: {data!r}")
        if data.get("error") == "rate_limited":
            return core.raise_or_fail_open(
                self.config, f"rate_limited (retry_after {data.get('retry_after_seconds')}s)")
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
        resp = await self._http.post(
            self.config.engine_url + "/v1/evaluate", json=payload)
        resp.raise_for_status()
        return resp.json()


async def _aiter(iterable):
    if hasattr(iterable, "__aiter__"):
        async for item in iterable:
            yield item
    else:
        for item in iterable:
            yield item
