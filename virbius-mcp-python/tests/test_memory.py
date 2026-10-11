import virbius_mcp_python as v

WRITE_KEYS = {
    "allowed",
    "sanitized_content",
    "block_reason",
    "pii_found",
    "credential_detected",
    "need_llm_check",
}
READ_KEYS = {
    "allowed",
    "filtered_content",
    "block_reason",
    "credential_detected",
    "content_filtered",
    "need_llm_check",
}


def test_is_memory_write_tool_classification():
    assert v.is_memory_write_tool("memory_save") is True
    assert v.is_memory_write_tool("vector_store_write") is True
    assert v.is_memory_write_tool("read_file") is False


def test_is_memory_read_tool_classification():
    assert v.is_memory_read_tool("memory_search") is True
    assert v.is_memory_read_tool("db_delete") is False


def test_intercept_write_returns_shape():
    result = v.intercept_memory_write("hello", "sess-1", "trace-1", "memory_save")
    assert WRITE_KEYS <= set(result)


def test_intercept_read_returns_shape():
    result = v.intercept_memory_read("hello", "sess-1", "trace-1", "memory_search")
    assert READ_KEYS <= set(result)
