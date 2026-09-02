# -*- coding: utf-8 -*-
"""兼容入口：按关卡转调 mcp_runtime.proxy_client。"""
from mcp_runtime.proxy_client import (  # noqa: F401
    McpProxyClient,
    call_tool_ops,
    drop_all_proxy_clients,
    drop_lab,
    get_challenge_status,
    get_client,
    parse_challenge,
)
from mcp_runtime.proxy_client import bind_session as _bind
from mcp_runtime.proxy_client import call_tool as _call
from mcp_runtime.proxy_client import new_session as _new


def new_session() -> str:
    return _new("bank")


def bind_llm10_session(session_id: str) -> str:
    return _bind("llm10", session_id)


def new_llm10_session() -> str:
    return _new("llm10")


def bind_llm06_session(session_id: str) -> str:
    return _bind("llm06", session_id)


def new_llm06_session() -> str:
    return _new("llm06")


def bind_mem_session(session_id: str) -> str:
    return _bind("memory", session_id)


def new_mem_session() -> str:
    return _new("memory")


def bind_ops_session(session_id: str) -> str:
    return _bind("ops", session_id)


def new_ops_session() -> str:
    return _new("ops")


def bind_egr_session(session_id: str) -> str:
    return _bind("egress", session_id)


def new_egr_session() -> str:
    return _new("egress")


def bind_chain_session(session_id: str) -> str:
    return _bind("chain", session_id)


def new_chain_session() -> str:
    return _new("chain")


def call_tool(tool_name: str, args: dict) -> str:
    return _call("bank", tool_name, args)


def call_tool_llm10(tool_name: str, args: dict) -> str:
    return _call("llm10", tool_name, args)


def call_tool_llm06(tool_name: str, args: dict) -> str:
    return _call("llm06", tool_name, args)


def call_tool_mem(tool_name: str, args: dict) -> str:
    return _call("memory", tool_name, args)


def call_tool_egr(tool_name: str, args: dict) -> str:
    return _call("egress", tool_name, args)


def call_tool_chain(tool_name: str, args: dict) -> str:
    return _call("chain", tool_name, args)
