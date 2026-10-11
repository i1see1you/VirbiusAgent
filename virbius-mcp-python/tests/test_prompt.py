import json

import virbius_mcp_python as v


def test_enhance_prompt_returns_message_json():
    messages = ["system: you are a helpful agent", "user: hi"]
    context = {"app_id": "test-agent", "session_id": "sess-1", "risk_score": 0}
    out = v.enhance_prompt(json.dumps(messages), json.dumps(context))
    parsed = json.loads(out)
    assert isinstance(parsed, list)
    assert len(parsed) >= len(messages)
