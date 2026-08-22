# -*- coding: utf-8 -*-
"""外发渠道 LangChain 工具。不进银行 TOOLS。"""
import json

from langchain.agents import Tool

from dvla_agent import mcp_server, mcpproxy_client
from egress_agent import ALL_TOOLS
from modules import protection


class _FlexTool(Tool):
    def _to_args_and_kwargs(self, tool_input, tool_call_id=None, **kwargs):
        return (tool_input,), {}


def _call(name, args):
    if not protection.is_enabled():
        result = mcp_server.call_tool(name, args)
        if result.get("success"):
            return result["result"]
        return "[blocked] " + result.get("error", "tool failed")
    return mcpproxy_client.call_tool_egr(name, args)


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


def is_egr_tool(name: str) -> bool:
    return name in ALL_TOOLS


def dispatch(name: str, args: dict) -> str:
    from egress_agent import briefs, sink
    if name == "list_briefs":
        return briefs.list_briefs()
    if name == "read_brief":
        return briefs.read_brief(str(args.get("id") or args.get("brief_id") or ""))
    if name == "http_get":
        return sink.http_get(str(args.get("url") or args.get("input") or ""))
    if name == "send_mail":
        return sink.send_mail(
            str(args.get("to") or ""),
            str(args.get("body") or args.get("input") or ""),
        )
    raise ValueError("unknown egress tool: " + str(name))


def list_briefs(tool_input="") -> str:
    return _call("list_briefs", {})


def read_brief(tool_input="") -> str:
    return _call("read_brief", {"id": _text(tool_input, "id", "brief_id", "input")})


def http_get(tool_input) -> str:
    url = _text(tool_input, "url", "endpoint", "uri", "input")
    if not url:
        return "[blocked] missing url"
    return _call("http_get", {"url": url})


def send_mail(tool_input) -> str:
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
        return "[blocked] missing mail to"
    return _call("send_mail", {"to": to, "body": body})


list_briefs_tool = _FlexTool(
    name="list_briefs",
    func=list_briefs,
    description="Lists this week's office briefs, including hidden HTML the UI hides.",
)
read_brief_tool = _FlexTool(
    name="read_brief",
    func=read_brief,
    description="Reads a brief in full. Argument: id (optional).",
)
http_get_tool = _FlexTool(
    name="http_get",
    func=http_get,
    description="HTTP GET a URL. Input is the url string.",
)
send_mail_tool = _FlexTool(
    name="send_mail",
    func=send_mail,
    description="Sends office mail. Input MUST be to|body, for example mail@wiki.internal|notes.",
)

EGRESS_TOOLS = [
    list_briefs_tool,
    read_brief_tool,
    http_get_tool,
    send_mail_tool,
]
