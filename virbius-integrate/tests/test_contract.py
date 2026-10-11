from __future__ import annotations

import json
import os
import uuid

from virbius_integrate._config import IntegrateConfig

FIXTURE_PATH = os.path.join(os.path.dirname(__file__), "fixtures", "contract_events.json")

EVENT_FIELDS = {
    "event_id", "call_id", "phase", "tenant_id", "app_id", "device_id",
    "session_id", "trace_id", "tool_name", "duration_ms", "args_repr",
    "result_repr", "error", "source", "package_version", "observed_at",
}

PHASES = {"started", "completed", "error"}


def _fixture() -> dict:
    with open(FIXTURE_PATH, encoding="utf-8") as handle:
        return json.load(handle)


def test_fixture_events_have_contract_fields():
    data = _fixture()
    assert len(data["events"]) == 3
    for event in data["events"]:
        assert set(event.keys()) == EVENT_FIELDS
        assert event["phase"] in PHASES
        assert event["source"] == "langchain-native"


def test_emitter_output_matches_contract_fields():
    from virbius_integrate.emitter import Emitter

    config = IntegrateConfig(app_id="crm-agent", tenant_id="acme", endpoint="http://control:8080")
    emitter = Emitter(config, transport=lambda *a: (200, {}))
    for fixture_event in _fixture()["events"]:
        raw = dict(fixture_event)
        raw["args_repr"] = "phone=13812345678"
        finalized = emitter._finalize(raw)
        assert set(finalized.keys()) == EVENT_FIELDS
        assert finalized["app_id"] == "crm-agent"
        assert finalized["source"] == "langchain-native"
        assert finalized["session_id"] in ("thread-abc", "<unscoped>")
        assert "13812345678" not in (finalized["args_repr"] or "")


def test_started_completed_share_call_id():
    data = _fixture()
    started = [e for e in data["events"] if e["phase"] == "started"]
    completed = [e for e in data["events"] if e["phase"] == "completed"]
    assert started and completed
    assert started[0]["call_id"] == completed[0]["call_id"]
    assert started[0]["event_id"] != completed[0]["event_id"]


def test_masked_fixture_has_no_raw_pii():
    data = _fixture()
    for event in data["events"]:
        for field in ("args_repr", "result_repr"):
            value = event.get(field)
            if value:
                assert "13812345678" not in value
