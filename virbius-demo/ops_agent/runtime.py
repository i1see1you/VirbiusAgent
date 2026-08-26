# -*- coding: utf-8 -*-
"""关防护直调 mcp_server；开防护走 ops proxy（含 -32011 / token）。"""
from dvla_agent import mcp_server, mcpproxy_client
from modules import protection
from ops_agent import store


def call_ops_tool(name: str, args: dict | None, challenge_token: str | None = None) -> dict:
    """Return {ok, text} | {ok: False, challenge} | {ok: False, blocked}."""
    args = args or {}
    store.set_current_rid(store.current_rid())
    if not protection.is_enabled():
        result = mcp_server.call_tool(name, args)
        if result.get("success"):
            text = result.get("result")
            return {"ok": True, "text": text if isinstance(text, str) else str(text)}
        return {"ok": False, "blocked": "[blocked] " + str(result.get("error") or "tool failed")}
    return mcpproxy_client.call_tool_ops(name, args, challenge_token=challenge_token)


def challenge_status(challenge_id: str) -> dict:
    return mcpproxy_client.get_challenge_status(challenge_id)
