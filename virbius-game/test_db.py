"""Minimal checks for register / progress / leaderboard."""

import os
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("SNAPSHOT_SECRET", "test-secret")

import db  # noqa: E402


class DbTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        db.DATA = Path(self.tmp.name)
        db.DB_PATH = db.DATA / "arena.db"
        db.init()

    def tearDown(self):
        self.tmp.cleanup()

    def test_register_and_board(self):
        u = db.register("alice", "secret1")
        db.record_attempt(
            u["id"],
            db.APP_SOLACE,
            "L1",
            "hello",
            "hi",
            {"score": 80, "passed": True, "hits": 4, "tokens": 10},
        )
        db.record_attempt(
            u["id"],
            db.APP_SOLACE,
            "L3",
            "again",
            "hi",
            {"score": 90, "passed": True, "hits": 0, "tokens": 0},
        )
        board = db.leaderboard()
        self.assertEqual(board[0]["username"], "alice")
        self.assertEqual(board[0]["total"], 170)
        self.assertTrue(db.progress_for(u["id"])["L3"]["cleared"])
        self.assertIn("solace_profane_chat", db.progress_all(u["id"]))
        out = db.export_attempts_jsonl(db.DATA / "out.jsonl")
        text = out.read_text(encoding="utf-8")
        self.assertIn("hello", text)
        self.assertIn("jailbreak", text)

    def test_list_attempts_keeps_failures(self):
        u = db.register("bob", "secret1")
        db.record_attempt(
            u["id"],
            db.APP_SOLACE,
            "L1",
            "fail-prompt",
            "nope",
            {"score": 10, "passed": False, "hits": 0, "tokens": 3},
        )
        db.record_attempt(
            u["id"],
            db.APP_SOLACE,
            "L1",
            "win-prompt",
            "ok",
            {"score": 80, "passed": True, "hits": 2, "tokens": 4},
        )
        db.record_attempt(
            u["id"],
            db.APP_SOLACE,
            "L2",
            "other-level",
            "x",
            {"score": 20, "passed": False, "hits": 0, "tokens": 1},
        )
        rows = db.list_attempts(u["id"], db.APP_SOLACE, "L1")
        self.assertEqual([r["attack"] for r in rows], ["win-prompt", "fail-prompt"])
        self.assertFalse(rows[1]["passed"])
        self.assertEqual(rows[0]["score"], 80)


if __name__ == "__main__":
    unittest.main()
