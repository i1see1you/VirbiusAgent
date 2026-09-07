# -*- coding: utf-8 -*-
"""LLM10：租户 owasp-llm10，累计 user_query_1m + Groovy 第 4 次 deny。"""
import logging

from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("llm10.bootstrap")
ALL_TOOLS = ("GetUserTransactions",)
RULE_ID = "cloud_query_rate_deny"
GROOVY = (
    "def decide(ctx) {\n"
    "    def n = (ctx.var('tool_name') ?: '').toString()\n"
    "    if (n != 'GetUserTransactions') return false\n"
    "    return ctx.getCumulative('user_query_1m') >= 3\n"
    "}\n"
)
_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def _rules() -> list:
    lab = get_lab("llm10")
    return [{
        "rule_id": RULE_ID, "bundle_id": "poc-default", "layer": "cloud",
        "runtime": "groovy", "reason_code": "QUERY_RATE",
        "risk_score": 100, "intent_action": "deny",
        "scope": {"bind_scope": "service", "bind_ref": {"app_ids": [lab.app_id]}},
        "body": GROOVY,
    }]


def _extra(http, lab):
    body = {
        "description": "session GetUserTransactions count, 1 minute rolling",
        "dimension": "session_id",
        "window_kind": "rolling",
        "window_minutes": 1,
        "status": "active",
        "priority": 0,
    }
    code, payload = http(
        "PUT",
        "/api/v1/admin/tenants/%s/cumulatives/user_query_1m" % lab.tenant_id,
        body,
    )
    if code >= 400:
        raise RuntimeError("cumulative upsert HTTP %s %s" % (code, payload))


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap(
            "llm10", rules=_rules(), allowed_tools=list(ALL_TOOLS), extra=_extra,
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("llm10 Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
