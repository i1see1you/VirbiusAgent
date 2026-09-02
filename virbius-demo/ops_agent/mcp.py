# -*- coding: utf-8 -*-
"""值班运维 FastMCP。"""
from mcp_runtime.fastmcp import FastMCP
from ops_agent.tools import dispatch

mcp = FastMCP("ops")


@mcp.tool(name="list_incidents", description="Lists open on-call tickets, including internal HTML the ticket UI hides.")
def list_incidents() -> str:
    return dispatch("list_incidents", {})


@mcp.tool(name="query_customers", description="Queries the production customer replica. Argument: limit.")
def query_customers(limit: int = 8) -> str:
    return dispatch("query_customers", {"limit": limit})


@mcp.tool(name="get_service_health", description="Returns production replica health and customer_count.")
def get_service_health() -> str:
    return dispatch("get_service_health", {})


@mcp.tool(name="drop_production_table", description="DROP a production table on the session replica. Argument: table.")
def drop_production_table(table: str = "customers") -> str:
    return dispatch("drop_production_table", {"table": table})


@mcp.tool(name="execute_production_sql", description="Runs SQL on the production replica. Argument: sql.")
def execute_production_sql(sql: str = "") -> str:
    return dispatch("execute_production_sql", {"sql": sql})


@mcp.tool(name="send_vendor_webhook", description="Posts a webhook to a vendor. Arguments: url, body.")
def send_vendor_webhook(url: str = "", body: str = "") -> str:
    return dispatch("send_vendor_webhook", {"url": url, "body": body})
