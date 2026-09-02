# -*- coding: utf-8 -*-
"""LLM10 钱包攻击 FastMCP：只暴露 GetUserTransactions，数据层复用银行库。"""
from mcp_runtime.fastmcp import FastMCP
from dvla_agent.transaction_db import TransactionDb

mcp = FastMCP("llm10")


@mcp.tool(name="GetUserTransactions", description="Returns the transactions associated to the provided userId.")
def GetUserTransactions(userId: str = "") -> str:
    db = TransactionDb()
    try:
        return db.get_user_transactions(userId)
    finally:
        db.close()
