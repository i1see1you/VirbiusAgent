from __future__ import annotations

import threading
import time
import uuid

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.emitter import Emitter, TieredQueue


def _config(**kw):
    return IntegrateConfig(app_id="a", tenant_id="t", endpoint="http://control:8080", **kw)


def test_tiered_queue_prefers_dropping_completed():
    queue = TieredQueue(4)
    queue.put({"phase": "started", "i": 1})
    queue.put({"phase": "started", "i": 2})
    queue.put({"phase": "started", "i": 3})
    queue.put({"phase": "completed", "i": 4})
    queue.put({"phase": "started", "i": 5})
    assert queue.dropped_completed == 1
    assert queue.dropped_started == 0
    remaining = queue.drain(10)
    assert len(remaining) == 4
    assert all(item["phase"] == "started" for item in remaining)


def test_tiered_queue_drops_oldest_started_when_only_started():
    queue = TieredQueue(2)
    queue.put({"phase": "started", "i": 1})
    queue.put({"phase": "started", "i": 2})
    queue.put({"phase": "started", "i": 3})
    assert queue.dropped_started == 1
    remaining = queue.drain(10)
    assert [item["i"] for item in remaining] == [2, 3]


def test_finalize_masks_args_and_result():
    emitter = Emitter(_config(), transport=lambda *a: (200, {}))
    event = emitter._finalize({
        "phase": "started",
        "call_id": "c1",
        "tool_name": "t",
        "args_repr": "phone=13812345678",
        "observed_at": "2026-10-11T08:00:00Z",
    })
    assert "13812345678" not in event["args_repr"]
    assert event["tenant_id"] == "t"
    assert event["app_id"] == "a"


def test_sample_rate_drops_completed_only():
    emitter = Emitter(_config(sample_rate=0.0), transport=lambda *a: (200, {}))
    completed = emitter._finalize({"phase": "completed", "call_id": "c", "observed_at": "x"})
    started = emitter._finalize({"phase": "started", "call_id": "c", "observed_at": "x"})
    assert completed is None
    assert started is not None


def test_flush_posts_envelope_with_stats_and_counts_failure():
    posts = []

    def transport(method, url, payload, headers):
        posts.append(payload)
        return 500, {"err": 1}

    emitter = Emitter(_config(retry_attempts=1), transport=transport)
    emitter._flush([
        {"phase": "started", "call_id": "c", "tool_name": "t", "observed_at": "x"},
    ])
    assert posts, "envelope posted"
    envelope = posts[0]
    assert "events" in envelope and "stats" in envelope
    assert emitter.delivery_failures == 1


def test_flush_requeues_nothing_on_413_but_counts():
    def transport(method, url, payload, headers):
        return 413, {}

    emitter = Emitter(_config(retry_attempts=3), transport=transport)
    started = time.time()
    emitter._flush([{"phase": "started", "call_id": "c", "observed_at": "x"}])
    assert time.time() - started < 0.6
    assert emitter.delivery_failures == 1


def test_run_flushes_batch_and_stops():
    posted = []

    def transport(method, url, payload, headers):
        posted.append(payload)
        return 200, {}

    emitter = Emitter(_config(batch_size=10, flush_interval=0.05), transport=transport)
    for i in range(3):
        emitter.enqueue({"phase": "started", "call_id": f"c{i}", "tool_name": "t", "observed_at": "x"})
    deadline = time.time() + 2
    total = 0
    while time.time() < deadline:
        total = sum(len(p["events"]) for p in posted)
        if total >= 3:
            break
        time.sleep(0.05)
    emitter.shutdown(timeout=1.0)
    total = sum(len(p["events"]) for p in posted)
    assert total == 3


def test_concurrent_enqueue_is_safe():
    emitter = Emitter(_config(queue_size=128), transport=lambda *a: (200, {}))
    errors = []

    def worker():
        try:
            for i in range(200):
                emitter.enqueue({"phase": "completed", "call_id": str(i), "observed_at": "x"})
        except Exception as exc:
            errors.append(exc)

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert not errors
    assert emitter.queue.dropped_completed + emitter.queue.depth() <= 800
