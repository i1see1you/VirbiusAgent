"""Shared window vectors — same file the engine's Java test replays."""

from __future__ import annotations

import json
import unittest
from pathlib import Path

from virbius_review.window import StreamReviewWindow

VECTORS = Path(__file__).resolve().parents[2] / "schemas" / "review-window-vectors.json"


class StreamReviewWindowVectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cases = json.loads(VECTORS.read_text(encoding="utf-8"))["cases"]

    def _replay(self, case):
        window = StreamReviewWindow(case["windowChars"], case["contextChars"])
        for step in case["steps"]:
            due = window.due(step.get("delta", ""), step.get("done", False))
            if step.get("due") is None:
                self.assertIsNone(due, f"{case['name']}: due should not be ready")
            else:
                self.assertEqual(step["due"], due, f"{case['name']}: due text mismatch")
            then = step.get("then")
            if then == "accept":
                self.assertEqual(step["forward"], window.accept(),
                                 f"{case['name']}: forwarded text mismatch")
                self.assertEqual(step["tail"], window.reviewed_tail,
                                 f"{case['name']}: context tail mismatch")
            elif then == "reject":
                window.reject()
                self.assertEqual(0, window.pending_length(),
                                 f"{case['name']}: reject must drop pending")

    def test_replays_all_shared_vectors(self):
        self.assertGreaterEqual(len(self.cases), 5)
        for case in self.cases:
            with self.subTest(case=case["name"]):
                self._replay(case)


if __name__ == "__main__":
    unittest.main()
