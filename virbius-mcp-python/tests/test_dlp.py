import virbius_mcp_python as v


def test_desensitize_passthrough_without_rules():
    # Fixture manifest has no dlp_rules → text is returned unchanged.
    assert v.desensitize("no pii here", "trace-1") == "no pii here"


def test_restore_returns_shape():
    result = v.restore("trace-1", "sess-1", "hello")
    assert isinstance(result["text"], str)
    assert isinstance(result["unresolved_tokens"], list)
