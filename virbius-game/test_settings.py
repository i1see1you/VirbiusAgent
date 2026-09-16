"""Settings persist + mask, no raw key in API payload."""

import json
import os
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("SNAPSHOT_SECRET", "test-secret")

import settings  # noqa: E402
from app import create_app  # noqa: E402
import llm  # noqa: E402


class SettingsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        settings.CONFIG_PATH = Path(self.tmp.name) / "config.json"

    def tearDown(self):
        self.tmp.cleanup()

    def test_save_masks_and_keeps_blank_key(self):
        rec = settings.save("deepseek", "deepseek-chat", "https://api.deepseek.com", "sk-testdemo1234")
        self.assertEqual(rec["key"], "sk-testdemo1234")
        self.assertEqual(settings.key_hint(rec["key"]), "sk-…1234")
        again = settings.save("deepseek", "deepseek-chat", "https://api.deepseek.com", "")
        self.assertEqual(again["key"], "sk-testdemo1234")

    def test_reject_unknown_provider(self):
        with self.assertRaises(ValueError):
            settings.save("nope", "", "", "")

    def test_providers_are_domestic(self):
        ids = set(settings.PROVIDERS)
        self.assertEqual(ids, {"deepseek", "qwen", "zhipu", "kimi", "doubao"})

    def test_completions_url(self):
        self.assertEqual(
            llm.completions_url("https://api.deepseek.com"),
            "https://api.deepseek.com/v1/chat/completions",
        )
        self.assertEqual(
            llm.completions_url("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        )
        self.assertEqual(
            llm.completions_url("https://open.bigmodel.cn/api/paas/v4"),
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
        )

    def test_blank_save_copies_env_when_no_prev(self):
        old = os.environ.get("SNAPSHOT_API_KEY")
        os.environ["SNAPSHOT_API_KEY"] = "sk-fromenv12345"
        try:
            rec = settings.save("deepseek", "deepseek-chat", "https://api.deepseek.com", "")
            self.assertEqual(rec["key"], "sk-fromenv12345")
        finally:
            if old is None:
                os.environ.pop("SNAPSHOT_API_KEY", None)
            else:
                os.environ["SNAPSHOT_API_KEY"] = old

    def test_public_config_hides_raw_key(self):
        settings.save("deepseek", "deepseek-chat", "https://api.deepseek.com", "sk-neverleak9999")
        pub = llm.public_config()
        dumped = json.dumps(pub)
        self.assertNotIn("sk-neverleak9999", dumped)
        self.assertNotIn("key", pub)
        self.assertTrue(pub["has_key"])
        self.assertIn("…", pub["key_hint"])

    def test_api_get_settings_hides_raw_key(self):
        settings.save("deepseek", "deepseek-chat", "https://api.deepseek.com", "sk-neverleak9999")
        client = create_app().test_client()
        resp = client.get("/api/settings")
        self.assertEqual(resp.status_code, 200)
        body = resp.get_data(as_text=True)
        self.assertNotIn("sk-neverleak9999", body)
        data = resp.get_json()
        self.assertNotIn("key", data)
        self.assertTrue(data["has_key"])


if __name__ == "__main__":
    unittest.main()
