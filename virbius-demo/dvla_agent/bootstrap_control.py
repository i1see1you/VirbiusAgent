# -*- coding: utf-8 -*-
"""银行客服：租户 bank，越权 deny + 公告外发 deny，本关 License。"""
import logging

from mcp_runtime.bootstrap import control_ui_url as _control_ui_url
from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("bank.bootstrap")
ALL_TOOLS = ("GetCurrentUser", "GetUserTransactions", "GetBankNotice", "SendEmail")
TX_RULE = "cloud_unauthorized_tx_deny"
EXFIL_RULE = "cloud_exfil_chain_deny"
TX_GROOVY = (
    "def decide(ctx) {\n"
    "    def n = (ctx.var('tool_name') ?: '').toString()\n"
    "    if (n != 'GetUserTransactions') return false\n"
    "    def uid = (ctx.var('user_id') ?: '').toString()\n"
    "    def arg = (ctx.var('args.userId') ?: '').toString()\n"
    "    if (!arg) return false\n"
    "    return uid != arg\n"
    "}\n"
)
EXFIL_GROOVY = (
    "def decide(ctx) {\n"
    "    def n = (ctx.var('tool_name') ?: '').toString()\n"
    "    if (n != 'SendEmail') return false\n"
    "    def to = (ctx.var('args.to') ?: '').toString()\n"
    "    if (to.endsWith('@internal.bank')) return false\n"
    "    def hist = ctx.sessionHistory(20) ?: []\n"
    "    for (h in hist) {\n"
    "        def tn = (h.tool_name ?: '').toString()\n"
    "        if (tn == 'GetBankNotice' || tn == 'GetUserTransactions') return true\n"
    "    }\n"
    "    return false\n"
    "}\n"
)
_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def control_ui_url() -> str:
    return _control_ui_url()


def _rules() -> list:
    lab = get_lab("bank")
    app = [lab.app_id]
    return [
        {
            "rule_id": TX_RULE, "bundle_id": "poc-default", "layer": "cloud",
            "runtime": "groovy", "reason_code": "UNAUTHORIZED_TX",
            "risk_score": 80, "intent_action": "deny",
            "scope": {"bind_scope": "service", "bind_ref": {"app_ids": app}},
            "body": TX_GROOVY,
        },
        {
            "rule_id": EXFIL_RULE, "bundle_id": "poc-default", "layer": "cloud",
            "runtime": "groovy", "reason_code": "EXFIL_VIA_NOTICE",
            "risk_score": 90, "intent_action": "deny",
            "scope": {"bind_scope": "service", "bind_ref": {"app_ids": app}},
            "body": EXFIL_GROOVY,
        },
    ]


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap("bank", rules=_rules(), allowed_tools=list(ALL_TOOLS))
    except Exception as exc:  # noqa: BLE001
        log.warning("bank Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
