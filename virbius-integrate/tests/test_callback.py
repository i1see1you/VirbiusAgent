from __future__ import annotations

import json
import uuid

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.callback import VirbiusAuditCallback, bounded_repr, session


def _make_callback(transport, **kwargs):
    return VirbiusAuditCallback(
        app_id="crm-agent",
        tenant_id="acme",
        endpoint="http://control:8080",
        token="t0",
        heartbeat=False,
        transport=transport,
        **kwargs,
    )


def _ok_transport(method, url, payload, headers):
    return 200, {"ok": True}


def test_started_emitted_immediately_with_call_id():
    callback = _make_callback(_ok_transport)
    captured = []
    callback.emitter().enqueue = lambda event: captured.append(event)
    run_id = uuid.uuid4()
    callback.on_tool_start({"name": "mail__send"}, "x", run_id=run_id, inputs={"to": "a@b.c"})
    assert captured
    assert captured[0]["phase"] == "started"
    assert captured[0]["call_id"] == str(run_id)
    assert captured[0]["tool_name"] == "mail__send"
    assert captured[0]["session_id"] == "<unscoped>"


def test_two_point_events_share_run_id():
    callback = _make_callback(_ok_transport)
    captured = []
    callback.emitter().enqueue = lambda event: captured.append(event)
    run_id = uuid.uuid4()
    callback.on_tool_start({"name": "t"}, None, run_id=run_id, inputs={"q": 1})
    callback.on_tool_end({"rows": 3}, run_id=run_id)
    assert captured[0]["phase"] == "started"
    assert captured[1]["phase"] == "completed"
    assert captured[0]["call_id"] == captured[1]["call_id"]
    assert captured[1]["duration_ms"] is not None


def test_error_phase_recorded():
    callback = _make_callback(_ok_transport)
    captured = []
    callback.emitter().enqueue = lambda event: captured.append(event)
    run_id = uuid.uuid4()
    callback.on_tool_start({"name": "t"}, None, run_id=run_id)
    try:
        raise ValueError("boom")
    except ValueError as exc:
        callback.on_tool_error(exc, run_id=run_id)
    assert captured[-1]["phase"] == "error"
    assert "ValueError" in captured[-1]["error"]


def test_session_from_metadata_then_contextvar_then_unscoped():
    callback = _make_callback(_ok_transport)
    captured = []
    callback.emitter().enqueue = lambda event: captured.append(event)
    callback.on_tool_start({"name": "t"}, None, run_id=uuid.uuid4(), metadata={"thread_id": "th-1"})
    assert captured[-1]["session_id"] == "th-1"
    with session("ctx-1"):
        callback.on_tool_start({"name": "t"}, None, run_id=uuid.uuid4())
    assert captured[-1]["session_id"] == "ctx-1"
    callback.on_tool_start({"name": "t"}, None, run_id=uuid.uuid4())
    assert captured[-1]["session_id"] == "<unscoped>"


def test_handler_never_raises():
    callback = _make_callback(_ok_transport)
    callback.emitter().enqueue = lambda event: (_ for _ in ()).throw(RuntimeError("enqueue broken"))
    callback.on_tool_start({"name": "t"}, None, run_id=uuid.uuid4())
    callback.on_tool_end("ok", run_id=uuid.uuid4())
    callback.on_tool_error(Exception("x"), run_id=uuid.uuid4())


def test_bounded_repr_arbitrary_object():
    class Cursor:
        def __repr__(self):
            return "<cursor to very long thing>" * 100

    value = bounded_repr(Cursor())
    assert value.startswith("Cursor:")
    assert len(value) < 200
    assert bounded_repr({"a": 1}) == '{"a": 1}'
    assert bounded_repr(None) is None


def test_tools_reported_at_construction():
    posts = []

    def transport(method, url, payload, headers):
        posts.append((url, payload))
        return 200, {"ok": True}

    class FakeTool:
        name = "drop_table"
        description = "drop"

    callback = _make_callback(transport, tools=[FakeTool()])
    import time

    deadline = time.time() + 2
    inventory_posts = []
    while time.time() < deadline:
        inventory_posts = [p for u, p in posts if "tool-inventory" in u]
        if inventory_posts:
            break
        time.sleep(0.05)
    assert inventory_posts, "inventory not reported"
    tools = inventory_posts[0]["tools"]
    assert tools[0]["tool_name"] == "drop_table"
    assert tools[0]["risk_hint"] == "high"
    assert tools[0]["bound_mode"] == "A"
