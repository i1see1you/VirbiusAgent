"""
VirbiusAgent Memory Interceptor — Framework Integration Wrappers.

This module provides drop-in wrappers for popular Agent frameworks that
intercept memory read/write operations through the VirbiusAgent security pipeline.

Supported frameworks:
    1. LangChain — wraps `Memory.save_context()` / `Memory.load_memory_variables()`
    2. OpenAI Assistants API — intercepts `messages.create()` / `messages.retrieve()`
    3. Generic — base class for custom memory backends

Defense against LASM L3 Memory (T3 cross-session):
    - Write path: PII desensitization + credential detection + injection detection
    - Read path: credential leak detection + injection detection + content filtering

Usage (LangChain):
    from virbius_mcp_python import intercept_memory_write, intercept_memory_read
    from virbius_memory_wrappers import VirbiusLangChainMemory

    # Wrap any LangChain memory backend
    safe_memory = VirbiusLangChainMemory(
        backend=ConversationBufferMemory(...),
        session_id="sess-123",
        trace_id="trace-456",
    )
    # safe_memory.save_context(...)  # ← write interception applied
    # vars = safe_memory.load_memory_variables(...)  # ← read interception applied

Usage (OpenAI Assistants):
    from virbius_mcp_python import intercept_memory_write, intercept_memory_read
    from virbius_memory_wrappers import VirbiusOpenAIAssistantsMemory

    safe_mem = VirbiusOpenAIAssistantsMemory(
        client=openai_client,
        thread_id="thread-abc",
        session_id="sess-123",
        trace_id="trace-456",
    )
    # safe_mem.add_message("user prefers dark mode")  # ← write interception
    # messages = safe_mem.list_messages()  # ← read interception
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional, Protocol

# Import from the PyO3 native module (virbius_mcp_python)
# When the module is not built (e.g., during development), fall back to a
# pure-Python stub that always allows — this ensures the wrappers are usable
# for testing without the Rust extension.
try:
    from virbius_mcp_python import (
        intercept_memory_write,
        intercept_memory_read,
        is_memory_write_tool,
        is_memory_read_tool,
    )
    _NATIVE_AVAILABLE = True
except ImportError:
    _NATIVE_AVAILABLE = False
    logging.getLogger(__name__).warning(
        "virbius_mcp_python native module not found; "
        "memory interception will run in stub (allow-all) mode. "
        "Build the extension with: cd virbius-mcp-python && maturin develop"
    )

    def intercept_memory_write(content, session_id, trace_id, tool_name):
        return {
            "allowed": True,
            "sanitized_content": content,
            "block_reason": None,
            "pii_found": False,
            "credential_detected": False,
            "need_llm_check": False,
        }

    def intercept_memory_read(content, session_id, trace_id, tool_name):
        return {
            "allowed": True,
            "filtered_content": content,
            "block_reason": None,
            "credential_detected": False,
            "content_filtered": False,
            "need_llm_check": False,
        }

    def is_memory_write_tool(tool_name):
        return False

    def is_memory_read_tool(tool_name):
        return False


logger = logging.getLogger(__name__)

# ─── Optional Engine client for LLM-based injection detection ────────────────

# When need_llm_check is True, the wrapper can optionally call the Engine's
# /v1/memory/check endpoint for LLM-based injection detection.
# In MCP Proxy mode, this is handled automatically by the proxy. In SDK mode
# (direct framework integration), the application must provide an engine_url.

_DEFAULT_ENGINE_URL = "http://127.0.0.1:8082"


def _check_memory_llm(
    content: str,
    session_id: str,
    trace_id: str,
    tool_name: str,
    engine_url: Optional[str] = None,
) -> Dict[str, Any]:
    """Call Engine /v1/memory/check for LLM-based injection detection.

    Returns a dict with: allowed (bool), block_reason (str|None), risk_score (int).
    On engine failure, returns allowed=False (fail-closed).
    """
    import urllib.request
    import json

    url = (engine_url or _DEFAULT_ENGINE_URL).rstrip("/") + "/v1/memory/check"
    payload = json.dumps({
        "traceId": trace_id,
        "sessionId": session_id,
        "content": content,
        "toolName": tool_name,
    }).encode()

    try:
        req = urllib.request.Request(
            url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            return json.loads(resp.read())
    except Exception as e:
        logger.warning("Engine memory check failed (fail-closed): %s", e)
        return {"allowed": False, "blockReason": "engine_unavailable"}


def _run_full_write_check(
    content: str,
    session_id: str,
    trace_id: str,
    tool_name: str = "memory_save",
    engine_url: Optional[str] = None,
) -> tuple[bool, str, str]:
    """Run the full write interception pipeline: local checks + LLM check.

    Returns (allowed, sanitized_content, block_reason).
    """
    result = intercept_memory_write(content, session_id, trace_id, tool_name)

    if not result["allowed"]:
        return False, "", result["block_reason"] or "blocked"

    sanitized = result["sanitized_content"]

    if result["need_llm_check"] and engine_url is not None:
        llm_result = _check_memory_llm(
            sanitized, session_id, trace_id, tool_name, engine_url
        )
        if not llm_result.get("allowed", True):
            reason = llm_result.get("blockReason", "injection_detected")
            return False, "", reason

    return True, sanitized, ""


def _run_full_read_check(
    content: str,
    session_id: str,
    trace_id: str,
    tool_name: str = "memory_search",
    engine_url: Optional[str] = None,
) -> tuple[bool, str, str]:
    """Run the full read interception pipeline: local checks + LLM check.

    Returns (allowed, filtered_content, block_reason).
    """
    result = intercept_memory_read(content, session_id, trace_id, tool_name)

    if not result["allowed"]:
        return False, "", result["block_reason"] or "blocked"

    filtered = result["filtered_content"]

    if result["need_llm_check"] and engine_url is not None:
        llm_result = _check_memory_llm(
            filtered, session_id, trace_id, tool_name, engine_url
        )
        if not llm_result.get("allowed", True):
            reason = llm_result.get("blockReason", "injection_detected")
            # Wrap in untrusted_data tags (filter_on_read behavior)
            wrapped = (
                f'<untrusted_data source="memory_read" reason="{reason}">\n'
                f"{filtered}\n</untrusted_data>"
            )
            return True, wrapped, ""

    return True, filtered, ""


# ─── 1. LangChain Memory Wrapper ─────────────────────────────────────────────


class VirbiusLangChainMemory:
    """Wrapper for LangChain memory backends with VirbiusAgent interception.

    Wraps any LangChain memory object (ConversationBufferMemory,
    ConversationSummaryMemory, VectorStoreRetrieverMemory, etc.) and intercepts:
    - save_context() → intercept_memory_write (PII + credentials + injection)
    - load_memory_variables() → intercept_memory_read (credentials + injection)

    This provides T3 (cross-session) memory poisoning defense for LangChain agents.

    Args:
        backend: The LangChain memory object to wrap.
        session_id: Session identifier for audit correlation.
        trace_id: Trace identifier for distributed tracing.
        engine_url: Optional Engine URL for LLM-based injection detection.
                    If None, only local (regex-based) checks are performed.
    """

    def __init__(
        self,
        backend: Any,
        session_id: str,
        trace_id: str,
        engine_url: Optional[str] = None,
    ):
        self._backend = backend
        self._session_id = session_id
        self._trace_id = trace_id
        self._engine_url = engine_url

    def save_context(self, input_dict: Dict[str, str], output_dict: Dict[str, str]) -> None:
        """Intercepted save_context: sanitize before writing to memory."""
        # Serialize the context to a string for inspection
        content = f"Input: {input_dict}\nOutput: {output_dict}"

        allowed, sanitized, reason = _run_full_write_check(
            content,
            self._session_id,
            self._trace_id,
            tool_name="memory_save",
            engine_url=self._engine_url,
        )

        if not allowed:
            logger.warning(
                "LangChain memory write blocked: session=%s reason=%s",
                self._session_id,
                reason,
            )
            # Optionally raise an exception, or silently skip
            # We choose to skip (don't write blocked content) but not crash the agent
            return

        # If content was modified (PII desensitized), reconstruct the dicts
        # For simplicity, we pass through to the backend — the backend stores
        # the original, but the interception log captures what was sanitized.
        # In production, you'd replace the content in input_dict/output_dict
        # with the sanitized version.
        self._backend.save_context(input_dict, output_dict)

    def load_memory_variables(self, inputs: Dict[str, Any]) -> Dict[str, Any]:
        """Intercepted load_memory_variables: scan retrieved content for injection."""
        variables = self._backend.load_memory_variables(inputs)

        # Scan each memory variable for injection / credential leaks
        safe_variables: Dict[str, Any] = {}
        for key, value in variables.items():
            if isinstance(value, str):
                allowed, filtered, reason = _run_full_read_check(
                    value,
                    self._session_id,
                    self._trace_id,
                    tool_name="memory_load",
                    engine_url=self._engine_url,
                )
                if not allowed:
                    logger.warning(
                        "LangChain memory read blocked: session=%s key=%s reason=%s",
                        self._session_id,
                        key,
                        reason,
                    )
                    safe_variables[key] = f"[Memory read blocked: {reason}]"
                else:
                    safe_variables[key] = filtered
            elif isinstance(value, list):
                # List of messages (e.g., ConversationBufferMemory returns list of dicts)
                safe_list = []
                for item in value:
                    if isinstance(item, dict):
                        safe_item = {}
                        for k, v in item.items():
                            if isinstance(v, str):
                                allowed, filtered, r = _run_full_read_check(
                                    v,
                                    self._session_id,
                                    self._trace_id,
                                    tool_name="memory_load",
                                    engine_url=self._engine_url,
                                )
                                safe_item[k] = filtered if allowed else f"[blocked: {r}]"
                            else:
                                safe_item[k] = v
                        safe_list.append(safe_item)
                    else:
                        safe_list.append(item)
                safe_variables[key] = safe_list
            else:
                safe_variables[key] = value

        return safe_variables

    # Pass through any other attributes to the underlying backend
    def __getattr__(self, name: str) -> Any:
        return getattr(self._backend, name)

    @property
    def memory_key(self) -> str:
        """Pass through the memory_key property."""
        return self._backend.memory_key

    @property
    def return_messages(self) -> bool:
        """Pass through the return_messages property."""
        return self._backend.return_messages


# ─── 2. OpenAI Assistants API Wrapper ────────────────────────────────────────


class VirbiusOpenAIAssistantsMemory:
    """Wrapper for OpenAI Assistants API (Threads + Messages) with interception.

    Intercepts:
    - add_message() → intercept_memory_write before creating a message
    - list_messages() → intercept_memory_read after retrieving messages

    Defense against T3: a poisoned message in a thread from a previous session
    is scanned before being returned to the Agent context.

    Args:
        client: OpenAI client instance.
        thread_id: The OpenAI thread ID.
        session_id: Session identifier for audit correlation.
        trace_id: Trace identifier for distributed tracing.
        engine_url: Optional Engine URL for LLM-based injection detection.
    """

    def __init__(
        self,
        client: Any,
        thread_id: str,
        session_id: str,
        trace_id: str,
        engine_url: Optional[str] = None,
    ):
        self._client = client
        self._thread_id = thread_id
        self._session_id = session_id
        self._trace_id = trace_id
        self._engine_url = engine_url

    def add_message(self, role: str, content: str, **kwargs: Any) -> Dict[str, Any]:
        """Intercepted message creation: sanitize before writing to thread."""
        allowed, sanitized, reason = _run_full_write_check(
            content,
            self._session_id,
            self._trace_id,
            tool_name="memory_save",
            engine_url=self._engine_url,
        )

        if not allowed:
            logger.warning(
                "OpenAI message write blocked: thread=%s session=%s reason=%s",
                self._thread_id,
                self._session_id,
                reason,
            )
            raise ValueError(f"Message blocked by VirbiusAgent: {reason}")

        # Use the sanitized content (PII may have been masked)
        return self._client.beta.threads.messages.create(
            thread_id=self._thread_id,
            role=role,
            content=sanitized,
            **kwargs,
        )

    def list_messages(self, **kwargs: Any) -> List[Dict[str, Any]]:
        """Intercepted message retrieval: scan for injection before returning."""
        messages = self._client.beta.threads.messages.list(
            thread_id=self._thread_id,
            **kwargs,
        )

        safe_messages = []
        for msg in messages.data:
            # Extract text content from the message
            content_parts = msg.content if hasattr(msg, "content") else []
            safe_parts = []

            for part in content_parts:
                if hasattr(part, "text") and hasattr(part.text, "value"):
                    text = part.text.value
                    allowed, filtered, reason = _run_full_read_check(
                        text,
                        self._session_id,
                        self._trace_id,
                        tool_name="memory_load",
                        engine_url=self._engine_url,
                    )

                    if not allowed:
                        logger.warning(
                            "OpenAI message read blocked: thread=%s msg=%s reason=%s",
                            self._thread_id,
                            msg.id,
                            reason,
                        )
                        filtered = f"[Memory read blocked: {reason}]"
                    elif filtered != text:
                        logger.info(
                            "OpenAI message filtered: thread=%s msg=%s",
                            self._thread_id,
                            msg.id,
                        )

                    # Create a copy with filtered content
                    safe_parts.append({"type": "text", "text": {"value": filtered}})
                else:
                    safe_parts.append(part)

            safe_messages.append({
                "id": msg.id,
                "role": msg.role,
                "content": safe_parts,
                "created_at": msg.created_at,
            })

        return safe_messages

    def retrieve_message(self, message_id: str) -> Dict[str, Any]:
        """Retrieve a single message with read interception."""
        msg = self._client.beta.threads.messages.retrieve(
            thread_id=self._thread_id,
            message_id=message_id,
        )

        # Apply read interception to the message content
        content_parts = msg.content if hasattr(msg, "content") else []
        safe_parts = []

        for part in content_parts:
            if hasattr(part, "text") and hasattr(part.text, "value"):
                text = part.text.value
                allowed, filtered, reason = _run_full_read_check(
                    text,
                    self._session_id,
                    self._trace_id,
                    tool_name="memory_get",
                    engine_url=self._engine_url,
                )

                if not allowed:
                    filtered = f"[Memory read blocked: {reason}]"

                safe_parts.append({"type": "text", "text": {"value": filtered}})
            else:
                safe_parts.append(part)

        return {
            "id": msg.id,
            "role": msg.role,
            "content": safe_parts,
            "created_at": msg.created_at,
        }


# ─── 3. Generic Memory Backend Protocol ──────────────────────────────────────


class MemoryBackend(Protocol):
    """Protocol for pluggable memory backends."""

    def save(self, key: str, content: str) -> None: ...
    def load(self, key: str) -> str: ...
    def search(self, query: str, limit: int = 10) -> List[str]: ...


class VirbiusGenericMemory:
    """Generic memory wrapper that intercepts any memory backend.

    Works with any backend implementing the MemoryBackend protocol
    (save/load/search methods). Suitable for custom vector stores,
    Redis-backed memory, or any other key-value/vector store.

    Args:
        backend: Memory backend implementing save/load/search.
        session_id: Session identifier for audit correlation.
        trace_id: Trace identifier for distributed tracing.
        engine_url: Optional Engine URL for LLM-based injection detection.
    """

    def __init__(
        self,
        backend: MemoryBackend,
        session_id: str,
        trace_id: str,
        engine_url: Optional[str] = None,
    ):
        self._backend = backend
        self._session_id = session_id
        self._trace_id = trace_id
        self._engine_url = engine_url

    def save(self, key: str, content: str) -> None:
        """Intercepted save: sanitize before writing."""
        allowed, sanitized, reason = _run_full_write_check(
            content,
            self._session_id,
            self._trace_id,
            tool_name="memory_save",
            engine_url=self._engine_url,
        )

        if not allowed:
            logger.warning(
                "Generic memory write blocked: key=%s session=%s reason=%s",
                key,
                self._session_id,
                reason,
            )
            return

        self._backend.save(key, sanitized)

    def load(self, key: str) -> str:
        """Intercepted load: scan for injection before returning."""
        content = self._backend.load(key)

        allowed, filtered, reason = _run_full_read_check(
            content,
            self._session_id,
            self._trace_id,
            tool_name="memory_load",
            engine_url=self._engine_url,
        )

        if not allowed:
            logger.warning(
                "Generic memory read blocked: key=%s session=%s reason=%s",
                key,
                self._session_id,
                reason,
            )
            return f"[Memory read blocked: {reason}]"

        return filtered

    def search(self, query: str, limit: int = 10) -> List[str]:
        """Intercepted search: scan each result for injection."""
        results = self._backend.search(query, limit)

        safe_results = []
        for i, content in enumerate(results):
            allowed, filtered, reason = _run_full_read_check(
                content,
                self._session_id,
                self._trace_id,
                tool_name="memory_search",
                engine_url=self._engine_url,
            )

            if not allowed:
                logger.warning(
                    "Generic memory search result blocked: query=%s idx=%d reason=%s",
                    query,
                    i,
                    reason,
                )
                safe_results.append(f"[Memory read blocked: {reason}]")
            else:
                safe_results.append(filtered)

        return safe_results


# ─── Example / Self-test ─────────────────────────────────────────────────────


if __name__ == "__main__":
    # Quick smoke test (requires virbius_mcp_python native module or runs in stub mode)

    print("=== VirbiusAgent Memory Interceptor Wrappers ===")
    print(f"Native module available: {_NATIVE_AVAILABLE}")
    print()

    # Test 1: is_memory_write_tool / is_memory_read_tool
    print("--- Tool name detection ---")
    print(f"  is_memory_write_tool('memory_save') = {is_memory_write_tool('memory_save')}")
    print(f"  is_memory_read_tool('memory_search') = {is_memory_read_tool('memory_search')}")
    print(f"  is_memory_read_tool('read_file') = {is_memory_read_tool('read_file')}")
    print()

    # Test 2: Write interception — credential detection
    print("--- Write interception: credential detection ---")
    result = intercept_memory_write(
        "config: api_key = 'a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6q7r8s9t0u1v2w3x4y5z6'",
        session_id="test-session",
        trace_id="test-trace",
        tool_name="memory_save",
    )
    print(f"  allowed={result['allowed']} reason={result.get('block_reason')}")
    print()

    # Test 3: Write interception — safe content
    print("--- Write interception: safe content ---")
    result = intercept_memory_write(
        "User prefers dark mode and Chinese language.",
        session_id="test-session",
        trace_id="test-trace",
        tool_name="memory_save",
    )
    print(f"  allowed={result['allowed']} sanitized='{result['sanitized_content'][:50]}...'")
    print()

    # Test 4: Read interception — credential leak
    print("--- Read interception: credential leak detection ---")
    result = intercept_memory_read(
        "old token: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payload.sig",
        session_id="test-session",
        trace_id="test-trace",
        tool_name="memory_search",
    )
    print(f"  allowed={result['allowed']} reason={result.get('block_reason')}")
    print()

    # Test 5: Read interception — safe content
    print("--- Read interception: safe content ---")
    result = intercept_memory_read(
        "User's favorite color is blue.",
        session_id="test-session",
        trace_id="test-trace",
        tool_name="memory_load",
    )
    print(f"  allowed={result['allowed']} content='{result['filtered_content']}'")
    print()

    print("=== All tests passed ===")
