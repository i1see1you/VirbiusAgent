from __future__ import annotations

import logging

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.emitter import Emitter

LOGGER = "virbius_integrate"


def _config(**kw):
    return IntegrateConfig(app_id="a", tenant_id="t", endpoint="http://control:8080", **kw)


def _event(i: int) -> dict:
    return {"phase": "started", "call_id": f"c{i}", "tool_name": "t", "observed_at": "x"}


def test_first_flush_logs_info_on_success(caplog):
    emitter = Emitter(_config(retry_attempts=1), transport=lambda m, u, p, h: (200, {"accepted": 1}))
    with caplog.at_level(logging.INFO, logger=LOGGER):
        emitter._flush([_event(0)])
    messages = [r.getMessage() for r in caplog.records]
    assert any("first flush ok" in m for m in messages)
    assert any("flushed=1" in m for m in messages)


def test_first_flush_logs_warning_on_failure(caplog):
    emitter = Emitter(_config(retry_attempts=1), transport=lambda m, u, p, h: (401, {}))
    with caplog.at_level(logging.INFO, logger=LOGGER):
        emitter._flush([_event(0)])
    assert any(
        r.levelno == logging.WARNING and "first flush FAILED" in r.getMessage()
        for r in caplog.records
    )


def test_first_flush_logged_only_once(caplog):
    emitter = Emitter(_config(retry_attempts=1), transport=lambda m, u, p, h: (200, {"accepted": 1}))
    with caplog.at_level(logging.INFO, logger=LOGGER):
        emitter._flush([_event(0)])
        emitter._flush([_event(1)])
    first = [r for r in caplog.records if "first flush" in r.getMessage()]
    assert len(first) == 1


def test_sampled_out_counted_separately_from_dropped():
    emitter = Emitter(_config(sample_rate=0.0), transport=lambda m, u, p, h: (200, {}))
    assert emitter._finalize({"phase": "completed", "call_id": "c", "observed_at": "x"}) is None
    stats = emitter.stats()
    assert stats["sampled_out"] == 1
    assert stats["dropped_started"] == 0 and stats["dropped_completed"] == 0
