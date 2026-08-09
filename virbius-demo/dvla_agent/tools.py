# -*- coding: utf-8 -*-
"""ReAct agent tools (ported from dvla-test).

The tools are now executed as an MCP server through the Rust virbius-mcp-proxy
(see virbius-mcp-proxy/ in the repo). The demo talks to the Rust proxy over
stdio JSON-RPC via dvla_agent/mcpproxy_client.py. The proxy runs the cloud
engine (/v1/evaluate) groovy authorization (越权) check before forwarding to
the SSE upstream dvla_agent/mcp_sse_server.py, which executes the real tools.
"""
from langchain.agents import Tool

from dvla_agent import mcp_server, mcpproxy_client
from modules import protection


def _call_proxy(tool_name: str, args: dict) -> str:
    """Invoke a tool, honoring the protection master switch.

    - switch ON  -> go through the Rust virbius-mcp-proxy (cloud groovy eval).
    - switch OFF -> call the upstream tool directly, bypassing all filtering.
    """
    if not protection.is_enabled():
        result = mcp_server.call_tool(tool_name, args)
        if result.get("success"):
            return result["result"]
        return "[blocked] " + result.get("error", "tool failed")
    return mcpproxy_client.call_tool(tool_name, args)


def get_current_user(input: str) -> str:
    return _call_proxy("GetCurrentUser", {})


get_current_user_tool = Tool(
    name="GetCurrentUser",
    func=get_current_user,
    description="Returns the current user (userId) for fetching their transactions.",
)


def get_transactions(userId: str) -> str:
    return _call_proxy("GetUserTransactions", {"userId": userId})


get_recent_transactions_tool = Tool(
    name="GetUserTransactions",
    func=get_transactions,
    description=(
        "Returns the transactions associated to the provided userId by running: "
        "SELECT * FROM Transactions WHERE userId = provided_userId."
    ),
)

TOOLS = [get_current_user_tool, get_recent_transactions_tool]