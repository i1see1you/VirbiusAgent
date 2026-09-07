# -*- coding: utf-8 -*-
"""关防护直调本包 dispatch；开防护走 ops proxy（含 -32011 / token）。"""
from dvla_agent import mcpproxy_client
from modules import protection
from ops_agent import store
from ops_agent.tools import dispatch


def call_ops_tool(name: str, args: dict | None, challenge_token: str | None = None) -> dict:
    """Return {ok, text} | {ok: False, challenge} | {ok: False, blocked}."""
    args = args or {}
    store.set_current_rid(store.current_rid())
    if not protection.is_enabled():
        try:
            text = dispatch(name, args)
            return {"ok": True, "text": text if isinstance(text, str) else str(text)}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "blocked": "[blocked] " + str(exc)}
    return mcpproxy_client.call_tool_ops(name, args, challenge_token=challenge_token)


def challenge_status(challenge_id: str) -> dict:
    return mcpproxy_client.get_challenge_status(challenge_id)
