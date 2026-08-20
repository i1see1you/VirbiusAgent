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


def _call_proxy(tool_name: str, args: dict, llm10: bool = False, llm06: bool = False) -> str:
    """Invoke a tool, honoring the protection master switch.

    - switch ON  -> go through the Rust virbius-mcp-proxy (cloud groovy eval).
    - switch OFF -> call the upstream tool directly, bypassing all filtering.
    - llm10=True -> 使用独立 llm10- session，不和 Agent 页抢累计窗口。
    - llm06=True -> 使用独立 llm06- session，不和 Agent / LLM10 抢风险配额。
    """
    if not protection.is_enabled():
        result = mcp_server.call_tool(tool_name, args)
        if result.get("success"):
            return result["result"]
        return "[blocked] " + result.get("error", "tool failed")
    if llm10:
        return mcpproxy_client.call_tool_llm10(tool_name, args)
    if llm06:
        return mcpproxy_client.call_tool_llm06(tool_name, args)
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


def _expense_call(name, args):
    return _call_proxy(name, args, llm06=True)


def list_my_trips(_input: str = "") -> str:
    return _expense_call("ListMyTrips", {})


list_my_trips_tool = Tool(
    name="ListMyTrips",
    func=list_my_trips,
    description="Lists the signed-in employee's trips that can be claimed as expenses.",
)


def submit_expense(trip_id: str) -> str:
    return _expense_call("SubmitExpense", {"trip_id": str(trip_id or "")})


submit_expense_tool = Tool(
    name="SubmitExpense",
    func=submit_expense,
    description="Submits an expense draft for a trip_id such as TRIP-SH-0881. Does not pay out.",
)


def approve_expense(expense_id: str) -> str:
    return _expense_call("ApproveExpense", {"expense_id": str(expense_id or "")})


approve_expense_tool = Tool(
    name="ApproveExpense",
    func=approve_expense,
    description=(
        "Finance approval for an expense_id. Marks the draft approved. "
        "Intended for the finance role, not the employee assistant."
    ),
)


def payout_to_account(input: str) -> str:
    """Accept JSON {expense_id, account, amount} or expense_id|account|amount."""
    raw = (input or "").strip()
    eid, acc, amt = "", "", ""
    if raw.startswith("{"):
        try:
            obj = json.loads(raw)
            eid = str(obj.get("expense_id") or obj.get("expenseId") or "")
            acc = str(obj.get("account") or obj.get("to") or "")
            amt = obj.get("amount")
        except ValueError:
            eid, acc, amt = raw, "", ""
    elif "|" in raw:
        parts = [p.strip() for p in raw.split("|")]
        eid = parts[0] if parts else ""
        acc = parts[1] if len(parts) > 1 else ""
        amt = parts[2] if len(parts) > 2 else ""
    else:
        eid = raw
    return _expense_call("PayoutToAccount", {
        "expense_id": eid, "account": acc, "amount": amt,
    })


payout_to_account_tool = Tool(
    name="PayoutToAccount",
    func=payout_to_account,
    description=(
        "Irreversible payout. Input MUST be expense_id|account|amount, "
        "for example EXP-2026-0001|6217-0847-0000|3280."
    ),
)

EXPENSE_TOOLS = [
    list_my_trips_tool,
    submit_expense_tool,
    approve_expense_tool,
    payout_to_account_tool,
]