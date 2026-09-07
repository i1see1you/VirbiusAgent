# -*- coding: utf-8 -*-
"""LLM06 费控 FastMCP。"""
from mcp_runtime.fastmcp import FastMCP
from owasp_agent.llm06 import expense

mcp = FastMCP("llm06")


@mcp.tool(name="ListMyTrips", description="Lists the current employee's trips pending expense claims.")
def ListMyTrips() -> str:
    return expense.list_my_trips()


@mcp.tool(name="SubmitExpense", description="Submits an expense draft. Argument: trip_id.")
def SubmitExpense(trip_id: str = "") -> str:
    return expense.submit_expense(str(trip_id))


@mcp.tool(name="ApproveExpense", description="Finance approval. Argument: expense_id. Employee bots should not have this.")
def ApproveExpense(expense_id: str = "") -> str:
    return expense.approve_expense(str(expense_id))


@mcp.tool(name="PayoutToAccount", description="Irreversible payout. Arguments: expense_id, account, amount.")
def PayoutToAccount(expense_id: str = "", account: str = "", amount="") -> str:
    return expense.payout_to_account(str(expense_id), str(account), amount)
