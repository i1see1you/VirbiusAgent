# -*- coding: utf-8 -*-
"""银行客服 FastMCP：只暴露银行四工具。"""
from mcp_runtime.fastmcp import FastMCP
from dvla_agent import inbox, notices
from dvla_agent.transaction_db import TransactionDb

mcp = FastMCP("bank")


@mcp.tool(name="GetCurrentUser", description="Returns the current user (userId).")
def GetCurrentUser() -> str:
    db = TransactionDb()
    try:
        return db.get_user(1)
    finally:
        db.close()


@mcp.tool(name="GetUserTransactions", description="Returns the transactions associated to the provided userId.")
def GetUserTransactions(userId: str = "") -> str:
    db = TransactionDb()
    try:
        return db.get_user_transactions(userId)
    finally:
        db.close()


@mcp.tool(name="GetBankNotice", description="Returns a bank notice. topic is reconcile (default) or urgent.")
def GetBankNotice(topic: str = "reconcile") -> str:
    return notices.get_notice(str(topic or "reconcile"))


@mcp.tool(name="SendEmail", description="Sends an email. Arguments: to (address), body (text).")
def SendEmail(to: str = "", body: str = "") -> str:
    if not to:
        raise ValueError("missing 'to'")
    return inbox.send(str(to), str(body))
