# -*- coding: utf-8 -*-
"""幂等写入 Control：租户 chain、delete_file Groovy deny full、本关 License。"""
import logging

from chain_agent import ALL_TOOLS, HIGH_RISK_TOOLS, SAFE_TOOLS
from mcp_runtime.bootstrap import control_ui_url as _control_ui_url
from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("chain.bootstrap")
RULE_ID = "cloud_chain_protected_wipe"
GROOVY = (
    "def decide(ctx) {\n"
    "    def cur = (ctx.var('args.path') ?: '').toString().trim().replace('\\\\', '/')\n"
    "    if (!cur) return false\n"
    "    def hist = ctx.sessionHistory(10) ?: []\n"
    "    for (h in hist) {\n"
    "        if ((h.tool_name ?: '').toString() != 'move_file') continue\n"
    "        def raw = (h.args ?: '').toString()\n"
    "        def sm = (raw =~ /\"src\"\\s*:\\s*\"([^\"]*)\"/)\n"
    "        def dm = (raw =~ /\"dest\"\\s*:\\s*\"([^\"]*)\"/)\n"
    "        def src = sm.find() ? sm.group(1).replace('\\\\', '/').trim() : ''\n"
    "        def dest = dm.find() ? dm.group(1).replace('\\\\', '/').trim() : ''\n"
    "        if (dest && dest == cur && src.startsWith('cabinet/')) return true\n"
    "    }\n"
    "    return false\n"
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
            "description": "file cabinet read or compress: " + name, "approval_mode": "lax",
        })
    for name in HIGH_RISK_TOOLS:
        bodies.append({
            "tool_name": name, "risk_class": "high", "sandbox_type": "none",
            "timeout_ms": 8000, "fast_path": False,
            "description": "file cabinet mutate: " + name, "approval_mode": "strict",
        })
    return bodies


def rule_upsert_body() -> dict:
    lab = get_lab("chain")
    return {
        "rule_id": RULE_ID, "bundle_id": "poc-default", "layer": "cloud",
        "runtime": "groovy", "reason_code": "CHAIN_MOVED_ORIGINAL_DELETE",
        "risk_score": 80, "intent_action": "deny",
        "scope": {
            "bind_scope": "tool",
            "bind_ref": {"tool_names": ["delete_file"], "app_ids": [lab.app_id]},
        },
        "body": GROOVY,
    }


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap(
            "chain", tools=tool_upsert_bodies(), rules=[rule_upsert_body()],
            allowed_tools=list(ALL_TOOLS),
        )
    except Exception as exc:  # noqa: BLE001
        log.warning("chain Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
