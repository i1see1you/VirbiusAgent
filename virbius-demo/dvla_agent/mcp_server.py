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

from dvla_agent import inbox, notices
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

    return {"success": False, "error": "unknown tool: " + str(tool_name)}


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