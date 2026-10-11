import json

import virbius_mcp_python as v


def test_scan_returns_wellformed_outcome():
    result = v.scan_edge("hello world", "sess-1")
    assert result["action"] in {"allow", "block", "review", "challenge"}
    assert result["effective_action"] == result["action"]
    assert result["trace_id"]


def test_scan_allow_without_matching_rule():
    # The fixture manifest ships no rules, so nothing should block.
    result = v.scan_edge("totally benign content", "sess-2")
    assert result["action"] == "allow"
