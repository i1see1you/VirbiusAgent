"""Hot-path overhead benchmark for VirbiusAuditCallback.

Measures the per-call cost of the callback handlers (the only work done on the
agent's execution path). Masking/serialization happen on the background flush
thread and are excluded here.

Run: python benchmarks/bench_callback.py
"""

from __future__ import annotations

import statistics
import time
import uuid

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.callback import VirbiusAuditCallback


def _noop_transport(method, url, payload, headers):
    return 200, {}


def _make() -> VirbiusAuditCallback:
    callback = VirbiusAuditCallback(
        app_id="bench",
        tenant_id="bench",
        endpoint="http://127.0.0.1:1",
        heartbeat=False,
        transport=_noop_transport,
    )
    callback.emitter().enqueue = lambda event: None
    return callback


def main() -> None:
    callback = _make()
    runs = 20000
    args = {"to": "a@b.c", "subject": "x", "body": "y" * 200}

    started = []
    for _ in range(runs):
        run_id = uuid.uuid4()
        t0 = time.perf_counter()
        callback.on_tool_start({"name": "mail__send"}, "x", run_id=run_id, inputs=args)
        t1 = time.perf_counter()
        callback.on_tool_end("ok", run_id=run_id)
        t2 = time.perf_counter()
        started.append((t1 - t0) * 1e6)
        started.append((t2 - t1) * 1e6)

    p50 = statistics.median(started)
    p95 = statistics.quantiles(started, n=100)[94]
    print(f"callback handler calls: {len(started)}")
    print(f"p50 per handler call: {p50:.2f} us")
    print(f"p95 per handler call: {p95:.2f} us")
    print(f"max per handler call: {max(started):.2f} us")


if __name__ == "__main__":
    main()
