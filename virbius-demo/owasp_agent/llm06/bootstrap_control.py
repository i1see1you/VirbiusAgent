# -*- coding: utf-8 -*-
"""LLM06：租户 owasp-llm06、本关 License。规则由运营台配置。"""
import logging

from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("llm06.bootstrap")
ALL_TOOLS = ("ListMyTrips", "SubmitExpense", "ApproveExpense", "PayoutToAccount")
RULE_ID = "cloud_expense_agency_deny"
GROOVY = (
    "def decide(ctx) {\n"
    "    def t = (ctx.var('tool_name') ?: '').toString()\n"
    "    return t == 'ApproveExpense' || t == 'PayoutToAccount'\n"
    "}\n"
)
_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def _rules() -> list:
    """运营台对照用，demo 不会 POST 这些规则。"""
    lab = get_lab("llm06")
    return [{
        "rule_id": RULE_ID, "bundle_id": "poc-default", "layer": "cloud",
        "runtime": "groovy", "reason_code": "EXCESSIVE_AGENCY",
        "risk_score": 100, "intent_action": "deny",
        "scope": {"bind_scope": "service", "bind_ref": {"app_ids": [lab.app_id]}},
        "body": GROOVY,
    }]


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap("llm06", allowed_tools=list(ALL_TOOLS))
    except Exception as exc:  # noqa: BLE001
        log.warning("llm06 Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
