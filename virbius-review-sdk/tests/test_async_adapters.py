"""Async client + provider stream adapters."""

from __future__ import annotations

import asyncio
import unittest

from virbius_review import AsyncReviewClient, Delta, Done
from virbius_review.adapters import from_ollama_ndjson, from_openai_sse


class AsyncClientTest(unittest.TestCase):
    def test_async_review_and_stream(self):
        async def transport(payload):
            return {"effective_action": "allow", "disposal_action": "warn"}

        async def run():
            async with AsyncReviewClient("http://engine:8082",
                                         transport=transport,
                                         window_chars=5, context_chars=2) as client:
                v = await client.review_input_async("hi", session_id="s", trace_id="t")
                self.assertFalse(v.blocked)

                async def deltas():
                    for d in ("abcde", "fghij"):
                        yield d

                events = []
                async for ev in client.wrap_stream_async(deltas(), session_id="s", trace_id="t"):
                    events.append(ev)
                return events

        events = asyncio.run(run())
        self.assertEqual([Delta("abcde"), Delta("fghij"), Done()], events)

    def test_async_rate_limited_fails_closed(self):
        async def transport(payload):
            return {"error": "rate_limited", "retry_after_seconds": 5}

        async def run():
            client = AsyncReviewClient("http://engine:8082", transport=transport)
            await client.review_input_async("hi", session_id="s", trace_id="t")

        from virbius_review import ReviewError
        with self.assertRaises(ReviewError):
            asyncio.run(run())

    def test_async_stream_block(self):
        verdicts = [{"effective_action": "allow", "disposal_action": "warn"},
                    {"effective_action": "deny", "disposal_action": "reject"}]
        idx = {"i": 0}

        async def transport(payload):
            v = verdicts[min(idx["i"], len(verdicts) - 1)]
            idx["i"] += 1
            return v

        async def run():
            client = AsyncReviewClient("http://engine:8082", transport=transport,
                                       window_chars=5, context_chars=2)
            events = []
            async for ev in client.wrap_stream_async(["abcde", "fghij"],
                                                     session_id="s", trace_id="t"):
                events.append(ev)
            return events

        events = asyncio.run(run())
        self.assertEqual(2, len(events))  # Delta then Blocked — stream terminated


class AdaptersTest(unittest.TestCase):
    def test_openai_sse(self):
        lines = [
            'data: {"choices":[{"delta":{"content":"Hel"}}]}',
            "",
            'data: {"choices":[{"delta":{"content":"lo"}}]}',
            'data: {"choices":[{"delta":{}}]}',
            "data: [DONE]",
        ]
        self.assertEqual(["Hel", "lo"], list(from_openai_sse(lines)))

    def test_ollama_ndjson(self):
        lines = [
            '{"message":{"role":"assistant","content":"Hel"},"done":false}',
            '{"message":{"role":"assistant","content":"lo"},"done":false}',
            '{"message":{"role":"assistant","content":""},"done":true}',
            '{"message":{"role":"assistant","content":"ignored"}}',
        ]
        self.assertEqual(["Hel", "lo"], list(from_ollama_ndjson(lines)))

    def test_malformed_lines_ignored(self):
        self.assertEqual([], list(from_openai_sse(["junk", "", "data: not-json"])))
        self.assertEqual([], list(from_ollama_ndjson(["junk"])))


if __name__ == "__main__":
    unittest.main()
