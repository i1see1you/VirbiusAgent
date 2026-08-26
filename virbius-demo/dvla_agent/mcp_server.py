# -*- coding: utf-8 -*-
"""dvla bank tool MCP server.

The tool is implemented AS an MCP server: it exposes `call_tool(tool_name, args)`
backed by the (intentionally vulnerable) transaction_db.

It is reached by the Rust virbius-mcp-proxy through the SSE upstream
(dvla_agent/mcp_sse_server.py), which runs:
  Agent(ReAct) -> Rust mcp-proxy(stdio) -> engine(/v1/evaluate 云端 groovy 越权评估)
                                             -> mcp_sse_server(SSE) -> this server
"""
import json
import sys

from dvla_agent import expense, inbox, notices
from dvla_agent.transaction_db import TransactionDb


def call_tool(tool_name: str, args: dict) -> dict:
    """Execute a dvla bank tool. Returns a dict with success + result/error."""
    if tool_name == "GetCurrentUser":
        db = TransactionDb()
        try:
            return {"success": True, "result": db.get_user(1)}
        finally:
            db.close()

    if tool_name == "GetUserTransactions":
        db = TransactionDb()
        try:
            return {"success": True, "result": db.get_user_transactions(args.get("userId", ""))}
        finally:
            db.close()

    if tool_name == "GetBankNotice":
        topic = args.get("topic") or args.get("input") or "reconcile"
        return {"success": True, "result": notices.get_notice(str(topic))}

    if tool_name == "SendEmail":
        to = args.get("to") or ""
        body = args.get("body") or args.get("input") or ""
        if not to:
            return {"success": False, "error": "missing 'to'"}
        return {"success": True, "result": inbox.send(str(to), str(body))}

    if tool_name == "ListMyTrips":
        return {"success": True, "result": expense.list_my_trips()}

    if tool_name == "SubmitExpense":
        trip_id = args.get("trip_id") or args.get("tripId") or args.get("input") or ""
        return {"success": True, "result": expense.submit_expense(str(trip_id))}

    if tool_name == "ApproveExpense":
        eid = args.get("expense_id") or args.get("expenseId") or args.get("input") or ""
        return {"success": True, "result": expense.approve_expense(str(eid))}

    if tool_name == "PayoutToAccount":
        eid = args.get("expense_id") or args.get("expenseId") or ""
        acc = args.get("account") or args.get("to") or ""
        amt = args.get("amount")
        raw = args.get("input")
        if raw and not (eid and acc):
            eid, acc, amt = _split_payout(raw, eid, acc, amt)
        return {"success": True, "result": expense.payout_to_account(str(eid), str(acc), amt)}

    if tool_name == "get_calendar":
        from memory_agent import calendar
        return {"success": True, "result": calendar.get_today()}

    if tool_name == "save_memory":
        from memory_agent import store
        content = args.get("content") or args.get("text") or args.get("input") or ""
        title = args.get("title") or ""
        return {"success": True, "result": store.save_memory(str(content), str(title))}

    if tool_name == "search_memory":
        from memory_agent import store
        query = args.get("query") or args.get("input") or args.get("content") or ""
        return {"success": True, "result": store.search_memory(str(query))}

    if tool_name == "send_email":
        from memory_agent import mailbox
        to = args.get("to") or ""
        body = args.get("body") or args.get("input") or ""
        if not to:
            return {"success": False, "error": "missing 'to'"}
        return {"success": True, "result": mailbox.send(str(to), str(body))}

    from ops_agent.tools import is_ops_tool, dispatch as ops_dispatch
    if is_ops_tool(tool_name):
        try:
            return {"success": True, "result": ops_dispatch(tool_name, args)}
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": str(exc)}

    from egress_agent.tools import is_egr_tool, dispatch as egr_dispatch
    if is_egr_tool(tool_name):
        try:
            return {"success": True, "result": egr_dispatch(tool_name, args)}
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": str(exc)}

    from chain_agent.tools import is_chain_tool, dispatch as chain_dispatch
    if is_chain_tool(tool_name):
        try:
            return {"success": True, "result": chain_dispatch(tool_name, args)}
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": str(exc)}

    return {"success": False, "error": "unknown tool: " + str(tool_name)}


def _split_payout(raw, eid, acc, amt):
    text = str(raw or "").strip()
    if text.startswith("{"):
        try:
            obj = json.loads(text)
            return (
                eid or obj.get("expense_id") or obj.get("expenseId") or "",
                acc or obj.get("account") or obj.get("to") or "",
                amt if amt is not None else obj.get("amount"),
            )
        except ValueError:
            pass
    parts = [p.strip() for p in text.split("|")]
    if len(parts) >= 3:
        return eid or parts[0], acc or parts[1], amt if amt is not None else parts[2]
    return eid, acc, amt


def _text_result(text: str) -> dict:
    return {"content": [{"type": "text", "text": text}]}


def _handle(request: dict) -> dict:
    req_id = request.get("id", 1)
    params = request.get("params", {}) or {}
    name = params.get("name", "")
    arguments = params.get("arguments", {}) or {}

    result = call_tool(name, arguments)
    if result.get("success"):
        return {"jsonrpc": "2.0", "id": req_id, "result": _text_result(result["result"])}

    return {
        "jsonrpc": "2.0",
        "id": req_id,
        "error": {"code": -32000, "message": result.get("error", "tool failed")},
    }


def main() -> None:
    """stdio JSON-RPC loop: one request line per response line."""
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            response = _handle(request)
        except Exception as exc:  # noqa: BLE001
            response = {
                "jsonrpc": "2.0",
                "id": 1,
                "error": {"code": -32700, "message": "parse error: " + str(exc)},
            }
        sys.stdout.write(json.dumps(response) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()