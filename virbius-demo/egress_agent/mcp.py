# -*- coding: utf-8 -*-
"""外发渠道 FastMCP。"""
from mcp_runtime.fastmcp import FastMCP
from egress_agent.tools import dispatch

mcp = FastMCP("egress")


@mcp.tool(name="list_briefs", description="Lists this week's office briefs, including hidden HTML the UI hides.")
def list_briefs() -> str:
    return dispatch("list_briefs", {})


@mcp.tool(name="read_brief", description="Reads a brief in full. Argument: id (optional).")
def read_brief(id: str = "") -> str:
    return dispatch("read_brief", {"id": id})


@mcp.tool(name="http_get", description="HTTP GET a URL. Argument: url.")
def http_get(url: str = "") -> str:
    return dispatch("http_get", {"url": url})


@mcp.tool(name="send_mail", description="Sends office mail. Arguments: to, body.")
def send_mail(to: str = "", body: str = "") -> str:
    return dispatch("send_mail", {"to": to, "body": body})
