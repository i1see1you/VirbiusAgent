"""Streaming wrap_stream: pass-through, mid-stream block, tail flush,
stream_first semantics and failure handling."""

from __future__ import annotations

import unittest

from virbius_review import ReviewClient
from virbius_review.models import Blocked, Delta, Done


class StreamReviewTest(unittest.TestCase):
    def _client(self, verdict_fn, **kw):
        def transport(payload):
            return verdict_fn(payload)

        return ReviewClient("http://engine:8082", transport=transport, **kw)

    def _events(self, client, deltas, **ctx):
        return list(client.wrap_stream(deltas, **ctx))

    def test_passthrough_forwards_only_reviewed_content(self):
        client = self._client(lambda p: {"effective_action": "allow", "disposal_action": "warn"},
                              window_chars=5, context_chars=2)
        events = self._events(client, ["abcde", "fghij"],
                               session_id="s", trace_id="t")
        # Both windows passed and were forwarded verbatim (never the overlap).
        self.assertEqual([Delta("abcde"), Delta("fghij"), Done()], events)

    def test_mid_stream_block_terminates(self):
        verdicts = [
            {"effective_action": "allow", "disposal_action": "warn"},
            {"effective_action": "deny", "reason_code": "OUTPUT_XSS",
             "disposal_action": "reject"},
        ]
        idx = {"i": 0}

        def transport(payload):
            v = verdicts[min(idx["i"], len(verdicts) - 1)]
            idx["i"] += 1
            return v

        client = ReviewClient("http://engine:8082", transport=transport,
                              window_chars=5, context_chars=2)
        events = self._events(client, ["abcde", "fghij", "klmno"],
                               session_id="s", trace_id="t")
        # First window passed; second was blocked: third delta never reviewed
        # nor forwarded, stream terminates with Blocked.
        self.assertEqual([Delta("abcde"), Blocked("OUTPUT_XSS", None, 0, "reject")], events)

    def test_tail_window_flushed_at_end(self):
        client = self._client(lambda p: {"effective_action": "allow", "disposal_action": "warn"},
                              window_chars=10, context_chars=3)
        events = self._events(client, ["hi", " there", "!"],
                               session_id="s", trace_id="t")
        # 9 chars never fill the 10-char window mid-stream; the tail is
        # flushed at end-of-stream as a single window.
        self.assertEqual([Delta("hi there!"), Done()], events)

    def test_stream_first_forwards_before_verdict(self):
        client = self._client(lambda p: {"effective_action": "allow", "disposal_action": "warn"},
                              window_chars=5, context_chars=2, stream_first=True)
        events = self._events(client, ["abcde", "fghij"],
                               session_id="s", trace_id="t")
        # stream_first: every delta is forwarded eagerly; the verdict still
        # runs per window (but nothing extra is forwarded on accept).
        self.assertEqual([Delta("abcde"), Delta("fghij"), Done()], events)

    def test_stream_first_cannot_retract_blocked_tokens(self):
        verdicts = [{"effective_action": "deny", "disposal_action": "reject"}]

        def transport(payload):
            return verdicts[0]

        client = ReviewClient("http://engine:8082", transport=transport,
                              window_chars=5, context_chars=2, stream_first=True)
        events = self._events(client, ["abcde", "fghij"], session_id="s", trace_id="t")
        types = [type(e) for e in events]
        self.assertIn(Delta, types)  # already forwarded — documented irreversibility
        self.assertIn(Blocked, types)

    def test_window_payload_carries_stream_chunk_and_trace(self):
        seen = []

        def transport(payload):
            seen.append(payload)
            return {"effective_action": "allow", "disposal_action": "warn"}

        client = ReviewClient("http://engine:8082", transport=transport,
                              window_chars=5, context_chars=2)
        self._events(client, ["abcde"], session_id="s-1", trace_id="t-1", user_id="u-1")
        self.assertTrue(seen[0]["stream_chunk"])
        self.assertEqual("t-1", seen[0]["trace_id"])
        self.assertEqual("s-1", seen[0]["session_id"])
        self.assertEqual("u-1", seen[0]["user_id"])

    def test_fail_open_stream_passes_through(self):
        def boom(payload):
            raise ConnectionError("engine down")

        client = ReviewClient("http://engine:8082", transport=boom, fail_open=True,
                              window_chars=5, context_chars=2)
        events = self._events(client, ["abcde"], session_id="s", trace_id="t")
        self.assertEqual([Delta("abcde"), Done()], events)


if __name__ == "__main__":
    unittest.main()
