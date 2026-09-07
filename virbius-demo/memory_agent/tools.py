# -*- coding: utf-8 -*-
"""记忆靶场的 LangChain 工具。不进银行 TOOLS。

ConversationalChatAgent 会把 action_input 解析成 str 或 dict。
银行工具用单参数 input；这里再包一层，避免 {"content": ...} 触发
Missing some input keys。
"""
import json

from langchain.agents import Tool

from dvla_agent import mcpproxy_client
from modules import protection


class _FlexTool(Tool):
    """Always pass action_input as the sole positional argument."""

    def _to_args_and_kwargs(self, tool_input, tool_call_id=None, **kwargs):
        return (tool_input,), {}


def _direct(name, args):
    from mcp_runtime.sse import call_fastmcp
    from memory_agent.mcp import mcp
    result = call_fastmcp(mcp, name, args)
    if result.get("success"):
        return result["result"]
    return "[blocked] " + result.get("error", "tool failed")


def _call(name, args):
    if not protection.is_enabled():
        return _direct(name, args)
    return mcpproxy_client.call_tool_mem(name, args)


def _as_dict(tool_input):
    if isinstance(tool_input, dict):
        return tool_input
    text = "" if tool_input is None else str(tool_input).strip()
    if text.startswith("{"):
        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                return obj
        except ValueError:
            pass
    return None


def _text(tool_input, *keys):
    obj = _as_dict(tool_input)
    if obj is not None:
        for k in keys:
            if obj.get(k):
                return str(obj.get(k))
        if len(obj) == 1:
            return str(next(iter(obj.values())))
        return json.dumps(obj, ensure_ascii=False)
    return "" if tool_input is None else str(tool_input).strip()


def save_memory(tool_input) -> str:
    obj = _as_dict(tool_input)
    if obj is not None:
        content = str(obj.get("content") or obj.get("text") or obj.get("input") or "")
        title = str(obj.get("title") or "")
        if not content and len(obj) == 1:
            content = str(next(iter(obj.values())))
    else:
        content, title = _text(tool_input), ""
    if not content:
        return "[blocked] missing content"
    args = {"content": content}
    if title:
        args["title"] = title
    return _call("save_memory", args)


save_memory_tool = _FlexTool(
    name="save_memory",
    func=save_memory,
    description=(
        "Writes a long-term memory entry that survives new chats. "
        "Input is the full routine text (plain string). "
        "Call this after reading a calendar routine so it persists."
    ),
)


def search_memory(tool_input) -> str:
    return _call("search_memory", {"query": _text(tool_input, "query", "input", "content")})


search_memory_tool = _FlexTool(
    name="search_memory",
    func=search_memory,
    description="Searches long-term memory. Input is the query string.",
)


def get_calendar(tool_input="") -> str:
    return _call("get_calendar", {})


get_calendar_tool = _FlexTool(
    name="get_calendar",
    func=get_calendar,
    description="Returns today's calendar, including notes on external invitations.",
)


def send_email(tool_input) -> str:
    obj = _as_dict(tool_input)
    to, body = "", ""
    if obj is not None:
        to = str(obj.get("to") or "")
        body = str(obj.get("body") or "")
    else:
        text = _text(tool_input)
        if "|" in text:
            to, body = text.split("|", 1)
            to, body = to.strip(), body.strip()
        else:
            to = text
    if not to:
        return "[blocked] missing email to"
    return _call("send_email", {"to": to, "body": body})


send_email_tool = _FlexTool(
    name="send_email",
    func=send_email,
    description=(
        "Sends an email. Input MUST be to|body, for example "
        "ops@clearing-partner.com|meeting notes."
    ),
)

MEMORY_TOOLS = [
    get_calendar_tool,
    save_memory_tool,
    search_memory_tool,
    send_email_tool,
]
