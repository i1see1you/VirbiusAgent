"""Client behavior: verdicts, disposal mapping, fail_open, capability fallback."""

from __future__ import annotations

import unittest

from virbius_review import ReviewClient, ReviewError
from virbius_review.models import InputVerdict, OutputVerdict


def make_client(responses=None, fail_open=True, refusal_text="BLOCKED", **kw):
    calls = []

    def transport(payload):
        calls.append(payload)
        if callable(responses):
            return responses(payload)
        return responses

    client = ReviewClient("http://engine:8082", fail_open=fail_open,
                          refusal_text=refusal_text, transport=transport, **kw)
    return client, calls


class ReviewClientTest(unittest.TestCase):
    def test_review_input_blocked(self):
        client, calls = make_client({"effective_action": "deny",
                                     "reason_code": "llm:Jailbreak",
                                     "rule_id": "prompt-jailbreak",
                                     "max_risk_score": 90})
        v = client.review_input("jailbreak", tenant_id="default",
                                session_id="s", trace_id="t", user_id="u")
        self.assertIsInstance(v, InputVerdict)
        self.assertTrue(v.blocked)
        self.assertEqual("prompt-jailbreak", v.rule_id)
        self.assertEqual("input", calls[0]["role"])
        self.assertEqual("chat_input", calls[0]["tool_name"])
        self.assertEqual("jailbreak", calls[0]["content"])
        self.assertFalse(calls[0]["stream_chunk"])

    def test_review_output_disposal_and_sanitized(self):
        client, _ = make_client({"effective_action": "deny", "reason_code": "PII",
                                 "disposal_action": "redact",
                                 "sanitized_content": "mail [REDACTED_EMAIL]"})
        v = client.review_output("mail a@b.com", session_id="s", trace_id="t")
        self.assertIsInstance(v, OutputVerdict)
        self.assertTrue(v.blocked)
        self.assertEqual("redact", v.disposal_action)
        self.assertEqual("mail [REDACTED_EMAIL]", v.sanitized_content)
        self.assertEqual("mail [REDACTED_EMAIL]", client.apply_disposal("mail a@b.com", v))

    def test_apply_disposal_matrix(self):
        cfg_kw = {"refusal_text": "BLOCKED"}
        for disposal, blocked, sanitized, expected in [
            ("reject", True, None, "BLOCKED"),
            ("omit", True, None, ""),
            ("redact", True, "clean text", "clean text"),
            ("warn", False, None, "original text"),
        ]:
            client, _ = make_client({"disposal_action": disposal,
                                     "sanitized_content": sanitized},
                                    **cfg_kw)
            v = client.review_output("original text")
            # Force the blocked flag for non-warn rows (mock returns it implicitly).
            v.blocked = blocked
            v.disposal_action = disposal
            self.assertEqual(expected, client.apply_disposal("original text", v))

    def test_missing_disposal_action_is_a_contract_violation(self):
        # Engine and SDK ship together: a role=output response without
        # disposal_action is a contract violation, not a capability gap.
        client, _ = make_client({"effective_action": "allow"})
        with self.assertRaises(ReviewError):
            client.review_output("hello")

    def test_fail_open_verdict_derives_disposal(self):
        # Locally synthesized fail-open verdicts carry no engine contract;
        # they keep the derived disposal (warn when not blocked).
        def boom(payload):
            raise ConnectionError("engine down")
        client, _ = make_client(boom, fail_open=True)
        v = client.review_output("hello")
        self.assertFalse(v.blocked)
        self.assertEqual("warn", v.disposal_action)
        self.assertIn("engine down", v.error)

    def test_stream_chunk_flag_and_output_tool(self):
        client, calls = make_client({"effective_action": "allow", "disposal_action": "warn"})
        client.review_output("text", stream_chunk=True, session_id="s", trace_id="t")
        self.assertTrue(calls[0]["stream_chunk"])
        self.assertEqual("chat_completion", calls[0]["tool_name"])
        self.assertEqual("output", calls[0]["role"])

    def test_fail_open_on_transport_error(self):
        def boom(payload):
            raise ConnectionError("engine down")

        client, _ = make_client(boom, fail_open=True)
        v = client.review_output("anything")
        self.assertFalse(v.blocked)
        self.assertIn("engine down", v.error)

    def test_fail_closed_raises(self):
        def boom(payload):
            raise ConnectionError("engine down")

        client, _ = make_client(boom, fail_open=False)
        with self.assertRaises(ReviewError):
            client.review_output("anything")

    def test_rate_limited_fails_closed(self):
        # 429 is a deliberate throttle and always fails closed — fail-open
        # would let a flooding client forward windows unreviewed.
        client, _ = make_client({"error": "rate_limited", "retry_after_seconds": 12})
        with self.assertRaises(ReviewError) as ctx:
            client.review_output("anything")
        self.assertIn("rate_limited", str(ctx.exception))
        self.assertIn("12", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
