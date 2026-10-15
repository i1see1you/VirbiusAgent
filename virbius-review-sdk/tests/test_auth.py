"""SDK caller authentication (P0-min): token resolution + fail-closed."""

from __future__ import annotations

import unittest

from virbius_review import ReviewClient, ReviewError


def make_client(response=None, **client_kw):
    response = response or {"effective_action": "allow", "disposal_action": "warn"}

    def transport(payload):
        return response

    return ReviewClient("http://engine:8082", transport=transport, **client_kw)


class AuthTest(unittest.TestCase):
    def test_static_token_is_attached(self):
        client = make_client(auth_token="tok-123")
        client.review_output("hi")
        self.assertEqual("tok-123", client.current_token())

    def test_token_provider_is_evaluated_per_call(self):
        calls = {"n": 0}

        def provider():
            calls["n"] += 1
            return f"tok-{calls['n']}"

        client = make_client(token_provider=provider)
        client.review_input("a")
        self.assertEqual("tok-1", client.current_token())
        client.review_input("b")
        self.assertEqual("tok-2", client.current_token())

    def test_provider_failure_fails_closed(self):
        def bad():
            raise RuntimeError("no token")

        client = make_client(token_provider=bad)
        with self.assertRaises(ReviewError):
            client.review_output("hi")

    def test_unauthorized_fails_closed_by_default(self):
        client = make_client({"error": "unauthorized", "reason": "revoked"})
        with self.assertRaises(ReviewError) as ctx:
            client.review_output("hi")
        self.assertIn("revoked", str(ctx.exception))

    def test_unauthorized_opt_in_fail_open(self):
        client = make_client({"error": "unauthorized", "reason": "revoked"},
                             auth_fail_open=True)
        v = client.review_output("hi")
        self.assertFalse(v.blocked)
        self.assertIn("unauthorized", v.error)

    def test_no_auth_configured_sends_empty_token(self):
        client = make_client()
        client.review_output("hi")
        self.assertEqual("", client.current_token())


if __name__ == "__main__":
    unittest.main()
