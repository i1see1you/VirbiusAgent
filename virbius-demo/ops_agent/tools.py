# -*- coding: utf-8 -*-
"""值班工具分发：只改会话副本，不碰银行 TOOLS。"""
from ops_agent import ALL_TOOLS
from ops_agent import store, tickets


def dispatch(name: str, args: dict | None) -> str:
    args = args or {}
    rid = store.current_rid()
    if name == "list_incidents":
        return tickets.list_incidents()
    if name == "query_customers":
        return store.query_customers(rid, args.get("limit") or 8)
    if name == "get_service_health":
        return store.get_service_health(rid)
    if name == "drop_production_table":
        return store.drop_production_table(rid, args.get("table") or "customers")
    if name == "execute_production_sql":
        return store.execute_production_sql(rid, args.get("sql") or args.get("query") or "")
    if name == "send_vendor_webhook":
        return store.send_vendor_webhook(
            rid, args.get("url") or "", args.get("body") or args.get("payload") or "",
        )
    raise KeyError(name)


def is_ops_tool(name: str) -> bool:
    return name in ALL_TOOLS
