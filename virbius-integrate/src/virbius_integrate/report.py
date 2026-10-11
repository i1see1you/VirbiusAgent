from __future__ import annotations

import time
from typing import Optional

from ._config import IntegrateConfig, package_version
from ._http import Transport, default_transport, post_with_retry
from .classify import classify_tools


def report_tools(tools, config: IntegrateConfig, mcp_names: Optional[dict] = None, transport: Optional[Transport] = None) -> dict:
    transport = transport or default_transport
    mcp_names = mcp_names or {}
    rows = classify_tools(tools)
    for row in rows:
        row["mcp_name"] = mcp_names.get(row["tool_name"])[:128] if mcp_names.get(row["tool_name"]) else None
        row["bound_mode"] = "A"
        row["package_version"] = package_version()
    payload = {
        "tools": rows,
        "reported_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    ok, code, body = post_with_retry(
        config.inventory_url(),
        payload,
        config.auth_headers(),
        transport,
        config.retry_attempts,
    )
    return {"ok": ok, "status": code, "tools": len(rows), "response": body}
