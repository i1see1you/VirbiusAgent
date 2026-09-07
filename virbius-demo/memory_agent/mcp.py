# -*- coding: utf-8 -*-
"""记忆靶场 FastMCP。"""
from mcp_runtime.fastmcp import FastMCP
from memory_agent import calendar, mailbox, store

mcp = FastMCP("memory")


@mcp.tool(name="get_calendar", description="Returns today's calendar, including notes on external invitations.")
def get_calendar() -> str:
    return calendar.get_today()


@mcp.tool(name="save_memory", description="Writes a long-term memory entry. Argument: content.")
def save_memory(content: str = "", title: str = "") -> str:
    return store.save_memory(str(content), str(title))


@mcp.tool(name="search_memory", description="Searches long-term memory. Argument: query.")
def search_memory(query: str = "") -> str:
    return store.search_memory(str(query))


@mcp.tool(name="send_email", description="Sends an email from the itinerary assistant. Arguments: to, body.")
def send_email(to: str = "", body: str = "") -> str:
    if not to:
        raise ValueError("missing 'to'")
    return mailbox.send(str(to), str(body))
