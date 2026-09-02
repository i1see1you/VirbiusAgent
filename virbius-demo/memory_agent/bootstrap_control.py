# -*- coding: utf-8 -*-
"""记忆靶场：租户 memory，四个工具 + 本关 License。Groovy 外发为可选第二闸。"""
import logging

from mcp_runtime.bootstrap import control_ui_url as _control_ui_url
from mcp_runtime.bootstrap import run_bootstrap
from mcp_runtime.labs import get as get_lab

log = logging.getLogger("memory.bootstrap")
ALL_TOOLS = ("get_calendar", "save_memory", "search_memory", "send_email")
_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def control_ui_url() -> str:
    return _control_ui_url()


def tool_upsert_bodies() -> list:
    return [{
        "tool_name": name, "risk_class": "low", "sandbox_type": "none",
        "timeout_ms": 8000, "fast_path": True,
        "description": "memory lab: " + name, "approval_mode": "lax",
    } for name in ALL_TOOLS]


def run() -> dict:
    global _STATUS
    try:
        _STATUS = run_bootstrap(
            "memory", tools=tool_upsert_bodies(), allowed_tools=list(ALL_TOOLS),
        )
        _ = get_lab("memory")
    except Exception as exc:  # noqa: BLE001
        log.warning("memory Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
