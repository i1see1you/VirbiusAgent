# -*- coding: utf-8 -*-
"""幂等写入 Control：租户 egress、工具、公司名单、本关 License。规则由运营台配置。"""
import logging

from egress_agent import ALL_TOOLS, HIGH_RISK_TOOLS, SAFE_TOOLS
from mcp_runtime.bootstrap import TOOL_NAME_RE
from mcp_runtime.bootstrap import control_ui_url as _control_ui_url
from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("egress.bootstrap")
RULE_ID = "cloud_egress_external_deny"
LIST_NAME = "company_mail_allow"
LIST_ENTRIES = ["mail.internal", "wiki.internal"]
GROOVY = (
    "def decide(ctx) {\n"
    "    def to = (ctx.var('args.to') ?: '').toString()\n"
    "    def at = to.lastIndexOf('@')\n"
    "    def host = at >= 0 ? to.substring(at + 1).trim() : to.trim()\n"
    "    return !ctx.listMatch('company_mail_allow', host)"
    " && !ctx.isInternalHost('https://' + host)\n"
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
            "description": "office brief read-only: " + name, "approval_mode": "lax",
        })
    for name in HIGH_RISK_TOOLS:
        bodies.append({
            "tool_name": name, "risk_class": "high", "sandbox_type": "none",
            "timeout_ms": 8000, "fast_path": False,
            "description": "office egress: " + name, "approval_mode": "strict",
        })
    return bodies


def rule_upsert_body() -> dict:
    """运营台对照用，demo 不会 POST 这条规则。"""
    lab = get_lab("egress")
    return {
        "rule_id": RULE_ID, "bundle_id": "poc-default", "layer": "cloud",
        "runtime": "groovy", "reason_code": "EGRESS_EXTERNAL_DENY",
        "risk_score": 90, "intent_action": "deny",
        "scope": {
            "bind_scope": "tool",
            "bind_ref": {"tool_names": ["send_mail"], "app_ids": [lab.app_id]},
        },
        "body": GROOVY,
    }


def list_meta_body() -> dict:
    return {"dimension": "keyword", "remark": "company domains that office mail may reach"}


def list_entries_body() -> dict:
    return {"values": list(LIST_ENTRIES)}


def license_issue_body(extra_tools=None, risk_quota=100) -> dict:
    lab = get_lab("egress")
    names = list(ALL_TOOLS)
    for t in extra_tools or []:
        if t not in names:
            names.append(t)
    return {
        "app_id": lab.app_id,
        "agent_name": lab.agent_name,
        "allowed_tools": names,
        "risk_quota": risk_quota,
    }


def _extra(http, lab):
    code, payload = http(
        "PUT",
        "/api/v1/admin/tenants/%s/lists/%s" % (lab.tenant_id, LIST_NAME),
        list_meta_body(),
    )
    if code >= 400:
        raise RuntimeError("list meta HTTP %s %s" % (code, payload))
    code, payload = http(
        "PUT",
        "/api/v1/admin/tenants/%s/lists/%s/entries" % (lab.tenant_id, LIST_NAME),
        list_entries_body(),
    )
    if code >= 400:
        raise RuntimeError("list entries HTTP %s %s" % (code, payload))
    http("POST", "/api/v1/admin/tenants/%s/lists/push-engine" % lab.tenant_id, {})


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap(
            "egress", tools=tool_upsert_bodies(),
            allowed_tools=list(ALL_TOOLS), extra=_extra,
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("egress Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
