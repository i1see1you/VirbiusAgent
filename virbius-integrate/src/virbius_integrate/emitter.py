from __future__ import annotations

import json
import random
import signal
import threading
import time
import uuid
from collections import deque
from typing import Callable, Optional

from ._config import SOURCE, IntegrateConfig, package_version
from ._http import Transport, default_transport, post_with_retry
from ._logging import get_logger
from ._threads import shared_manager
from .masker import mask

THREAD_NAME = "emitter"

logger = get_logger()


class TieredQueue:
    def __init__(self, maxlen: int):
        self._items = deque()
        self._lock = threading.Lock()
        self._maxlen = maxlen
        self.dropped_started = 0
        self.dropped_completed = 0

    def put(self, item: dict) -> None:
        with self._lock:
            if len(self._items) >= self._maxlen:
                self._evict_locked()
            self._items.append(item)

    def _evict_locked(self) -> None:
        for index, item in enumerate(self._items):
            if item.get("phase") != "started":
                del self._items[index]
                self.dropped_completed += 1
                return
        self._items.popleft()
        self.dropped_started += 1

    def drain(self, limit: int) -> list:
        with self._lock:
            taken = []
            while self._items and len(taken) < limit:
                taken.append(self._items.popleft())
            return taken

    def depth(self) -> int:
        with self._lock:
            return len(self._items)


class Emitter:
    def __init__(self, config: IntegrateConfig, transport: Optional[Transport] = None):
        self.config = config
        self.transport = transport or default_transport
        self.queue = TieredQueue(config.queue_size)
        self.last_flush_ts: float = 0.0
        self.last_success_ts: float = 0.0
        self.delivery_failures = 0
        self._flushed_total = 0
        self._sampled_out = 0
        self._first_flush_logged = False
        self._last_summary_ts = 0.0
        self._stop = threading.Event()
        self._flush_lock = threading.Lock()
        self._spill_path = f".virbius-integrate-spill-{os_pid()}.jsonl" if config.spill else None
        self._signal_installed = False

    def enqueue(self, event: dict) -> None:
        try:
            self.queue.put(event)
            shared_manager().ensure(THREAD_NAME, self._run)
        except Exception:
            pass

    def stats(self) -> dict:
        return {
            "dropped_started": self.queue.dropped_started,
            "dropped_completed": self.queue.dropped_completed,
            "queue_depth": self.queue.depth(),
            "delivery_failures": self.delivery_failures,
            "last_success_ts": self.last_success_ts,
            "sampled_out": self._sampled_out,
        }

    def _run(self) -> None:
        self._install_signal_handlers()
        while not self._stop.is_set():
            batch = self.queue.drain(self.config.batch_size)
            if batch:
                self._flush(batch)
            else:
                self._stop.wait(self.config.flush_interval)

    def _flush(self, batch: list) -> None:
        payload_events = []
        for raw in batch:
            event = self._finalize(raw)
            if event is None:
                continue
            payload_events.append(event)
        if not payload_events:
            return
        envelope = {"events": payload_events, "stats": self.stats()}
        ok, code, body = post_with_retry(
            self.config.observation_batch_url(),
            envelope,
            self.config.auth_headers(),
            self.transport,
            self.config.retry_attempts,
        )
        self.last_flush_ts = time.time()
        accepted = body.get("accepted") if isinstance(body, dict) else None
        if ok:
            self.last_success_ts = time.time()
            self._flushed_total += len(payload_events)
        else:
            self.delivery_failures += 1
            if self._spill_path:
                self._spill(payload_events)
        self._maybe_log_summary(ok, code, accepted, len(payload_events))

    def _maybe_log_summary(self, ok: bool, code: int, accepted, sent: int) -> None:
        """One INFO/WARNING on the first flush so silence is never ambiguous, then a
        periodic summary that only escalates to WARNING when data is actually lost."""
        now = time.time()
        if not self._first_flush_logged:
            self._first_flush_logged = True
            self._last_summary_ts = now
            if ok:
                logger.info(
                    "virbius-integrate first flush ok: flushed=%d accepted=%s dropped_started=%d"
                    " dropped_completed=%d sampled_out=%d queue_depth=%d app=%s endpoint=%s",
                    sent, accepted if accepted is not None else sent,
                    self.queue.dropped_started, self.queue.dropped_completed,
                    self._sampled_out, self.queue.depth(), self.config.app_id,
                    self.config.observation_batch_url(),
                )
            else:
                logger.warning(
                    "virbius-integrate first flush FAILED status=%s dropped_started=%d"
                    " dropped_completed=%d queue_depth=%d app=%s endpoint=%s (will retry best-effort)",
                    code, self.queue.dropped_started, self.queue.dropped_completed,
                    self.queue.depth(), self.config.app_id, self.config.observation_batch_url(),
                )
            return

        if now - self._last_summary_ts < max(1.0, float(self.config.heartbeat_interval)):
            return
        self._last_summary_ts = now
        dropped = self.queue.dropped_started + self.queue.dropped_completed
        log = logger.warning if (dropped or self.delivery_failures) else logger.debug
        log(
            "virbius-integrate flush summary: flushed=%d dropped=%d delivery_failures=%d"
            " queue_depth=%d sampled_out=%d",
            self._flushed_total, dropped, self.delivery_failures,
            self.queue.depth(), self._sampled_out,
        )

    def _finalize(self, raw: dict) -> Optional[dict]:
        phase = raw.get("phase")
        if phase == "completed" and self.config.sample_rate < 1.0:
            if random.random() > self.config.sample_rate:
                self._sampled_out += 1
                return None
        event = {
            "event_id": raw.get("event_id") or str(uuid.uuid4()),
            "call_id": raw.get("call_id"),
            "phase": phase,
            "tenant_id": self.config.tenant_id,
            "app_id": self.config.app_id,
            "device_id": self.config.device_id,
            "session_id": raw.get("session_id") or "<unscoped>",
            "trace_id": raw.get("trace_id"),
            "tool_name": raw.get("tool_name"),
            "duration_ms": raw.get("duration_ms"),
            "args_repr": None,
            "result_repr": None,
            "error": raw.get("error"),
            "source": SOURCE,
            "package_version": package_version(),
            "observed_at": raw.get("observed_at"),
        }
        if self.config.record_args and raw.get("args_repr") is not None:
            event["args_repr"] = mask(raw["args_repr"])[: self.config.max_repr]
        if raw.get("result_repr") is not None:
            event["result_repr"] = mask(raw["result_repr"])[: self.config.max_repr]
        return event

    def _spill(self, events: list) -> None:
        try:
            with open(self._spill_path, "a", encoding="utf-8") as handle:
                for event in events:
                    handle.write(json.dumps(event, ensure_ascii=False) + "\n")
        except Exception:
            pass

    def _install_signal_handlers(self) -> None:
        if self._signal_installed:
            return
        try:
            if threading.current_thread() is not threading.main_thread():
                return
            for sig in (signal.SIGTERM, signal.SIGINT):
                signal.signal(sig, self._on_signal)
            self._signal_installed = True
        except Exception:
            pass

    def _on_signal(self, signum, frame) -> None:
        self.shutdown(timeout=2.0)
        raise SystemExit(0)

    def shutdown(self, timeout: float = 2.0) -> None:
        self._stop.set()
        deadline = time.time() + timeout
        while self.queue.depth() and time.time() < deadline:
            batch = self.queue.drain(self.config.batch_size)
            if batch:
                self._flush(batch)
            else:
                break


def os_pid() -> int:
    import os

    return os.getpid()
