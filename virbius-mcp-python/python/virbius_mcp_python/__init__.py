"""VirbiusAgent edge enforcement primitives for Python.

The Rust extension lives in the ``_native`` submodule (PyO3 binding of
``virbius-core``); this package re-exports it and adds the :class:`VirbiusGuard`
stateful wrapper plus framework adapters — so callers stop passing
``jwt``/``pem``/``app_id`` on every single call.
"""

from ._native import (
    configure_rules,
    desensitize,
    enhance_prompt,
    intercept_memory_read,
    intercept_memory_write,
    is_memory_read_tool,
    is_memory_write_tool,
    precheck_tool,
    reload_rules,
    restore,
    scan_edge,
    verify_license,
)
from .doctor import diagnose
from .guard import (
    VirbiusDecision,
    VirbiusDenied,
    VirbiusGuard,
    govern,
    langchain_tool_error,
    openai_tool_error,
    policy_blocked_message,
    virbius_tool_error,
)

__all__ = [
    "VirbiusDecision",
    "VirbiusDenied",
    "VirbiusGuard",
    "diagnose",
    "govern",
    "langchain_tool_error",
    "openai_tool_error",
    "policy_blocked_message",
    "virbius_tool_error",
    "configure_rules",
    "desensitize",
    "enhance_prompt",
    "intercept_memory_read",
    "intercept_memory_write",
    "is_memory_read_tool",
    "is_memory_write_tool",
    "precheck_tool",
    "reload_rules",
    "restore",
    "scan_edge",
    "verify_license",
]
