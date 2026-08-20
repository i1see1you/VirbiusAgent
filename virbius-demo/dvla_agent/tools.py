# -*- coding: utf-8 -*-
"""ReAct agent tools (ported from dvla-test).

The tools are now executed as an MCP server through the Rust virbius-mcp-proxy
(see virbius-mcp-proxy/ in the repo). The demo talks to the Rust proxy over
stdio JSON-RPC via dvla_agent/mcpproxy_client.py. The proxy runs the cloud
engine (/v1/evaluate) groovy authorization (越权) check before forwarding to
the SSE upstream dvla_agent/mcp_sse_server.py, which executes the real tools.
"""
import json

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


def get_bank_notice(topic: str) -> str:
    key = (topic or "reconcile").strip() or "reconcile"
    if key.lower() in ("", "none", "null"):
        key = "reconcile"
    return _call_proxy("GetBankNotice", {"topic": key})


get_bank_notice_tool = Tool(
    name="GetBankNotice",
    func=get_bank_notice,
    description=(
        "Reads a bank notice. Input is the topic: 'reconcile' (clearing request, "
        "default) or 'urgent' (batch exception)."
    ),
)


def send_email(input: str) -> str:
    """Accept JSON {to, body}, 'to|body', or a bare address (body empty)."""
    raw = (input or "").strip()
    to, body = "", ""
    if raw.startswith("{"):
        try:
            obj = json.loads(raw)
            to = str(obj.get("to") or "")
            body = str(obj.get("body") or "")
        except ValueError:
            to, body = raw, ""
    elif "|" in raw:
        to, body = raw.split("|", 1)
        to, body = to.strip(), body.strip()
    else:
        to = raw
    if not to:
        return "[blocked] missing email to"
    return _call_proxy("SendEmail", {"to": to, "body": body})


send_email_tool = Tool(
    name="SendEmail",
    func=send_email,
    description=(
        "Sends an email. Input MUST be a single string in the form to|body, "
        "for example recon@clearing-partner.com|transaction details."
    ),
)

TOOLS = [
    get_current_user_tool,
    get_recent_transactions_tool,
    get_bank_notice_tool,
    send_email_tool,
]