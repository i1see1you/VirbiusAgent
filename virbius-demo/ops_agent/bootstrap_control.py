# -*- coding: utf-8 -*-
"""幂等写入 Control：租户 ops、challenge 规则 publish to full、本关 License。"""
import logging

from mcp_runtime.bootstrap import control_ui_url as _control_ui_url
from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab
from ops_agent import ALL_TOOLS, HIGH_RISK_TOOLS, SAFE_TOOLS

log = logging.getLogger("ops.bootstrap")
RULE_ID = "cloud_ops_irreversible_challenge"
GROOVY = (
    "def decide(ctx) {\n"
    "    def n = (ctx.var('tool_name') ?: '').toString()\n"
    "    return n in ['drop_production_table', 'execute_production_sql', 'send_vendor_webhook']\n"
    "}\n"
)
_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def control_ui_url() -> str:
    return _control_ui_url()


def tool_upsert_bodies() -> list:
    bodies = []
    for name in SAFE_TOOLS:
        bodies.append({
            "tool_name": name, "risk_class": "low", "sandbox_type": "none",
            "timeout_ms": 5000, "fast_path": True,
            "description": "ops read-only: " + name, "approval_mode": "lax",
        })
    for name in HIGH_RISK_TOOLS:
        bodies.append({
            "tool_name": name, "risk_class": "high", "sandbox_type": "none",
            "timeout_ms": 8000, "fast_path": False,
            "description": "ops irreversible: " + name, "approval_mode": "strict",
        })
    return bodies


def rule_upsert_body() -> dict:
    lab = get_lab("ops")
    return {
        "rule_id": RULE_ID, "bundle_id": "poc-default", "layer": "cloud",
        "runtime": "groovy", "reason_code": "OPS_IRREVERSIBLE_CHALLENGE",
        "risk_score": 90, "intent_action": "challenge",
        "scope": {
            "bind_scope": "tool",
            "bind_ref": {"tool_names": list(HIGH_RISK_TOOLS), "app_ids": [lab.app_id]},
        },
        "body": GROOVY,
    }


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap(
            "ops", tools=tool_upsert_bodies(), rules=[rule_upsert_body()],
            allowed_tools=list(ALL_TOOLS),
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("ops Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
