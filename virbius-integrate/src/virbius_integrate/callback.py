from __future__ import annotations

import contextvars
import json
import threading
import time
import uuid
from collections import OrderedDict
from contextlib import contextmanager
from typing import Optional

try:
    from langchain_core.callbacks import BaseCallbackHandler
except ImportError:

    class BaseCallbackHandler:  # type: ignore[no-redef]
        pass


_SESSION_VAR = contextvars.ContextVar("virbius_session", default=None)


@contextmanager
def session(session_id: str):
    token = _SESSION_VAR.set(session_id)
    try:
        yield
    finally:
        _SESSION_VAR.reset(token)


def current_session() -> Optional[str]:
    return _SESSION_VAR.get()


def bounded_repr(value, limit: int = 2048) -> Optional[str]:
    if value is None:
        return None
    try:
        if isinstance(value, str):
            text = value
        elif isinstance(value, (dict, list, int, float, bool)):
            text = json.dumps(value, default=str, ensure_ascii=False)
        else:
            return f"{type(value).__name__}:{repr(value)[:128]}"
        return text[:limit]
    except Exception:
        return f"{type(value).__name__}:<unserializable>"


class _StartClock:
    def __init__(self, maxlen: int = 1024):
        self._map: "OrderedDict[str, float]" = OrderedDict()
        self._lock = threading.Lock()
        self._maxlen = maxlen

    def mark(self, key: str) -> float:
        now = time.time()
        with self._lock:
            self._map[key] = now
            while len(self._map) > self._maxlen:
                self._map.popitem(last=False)
        return now

    def elapsed_ms(self, key: str) -> Optional[int]:
        with self._lock:
            start = self._map.pop(key, None)
        if start is None:
            return None
        return int((time.time() - start) * 1000)


def _extract_session(metadata: Optional[dict]) -> Optional[str]:
    if isinstance(metadata, dict):
        for key in ("thread_id", "session_id", "virbius_session"):
            value = metadata.get(key)
            if isinstance(value, str) and value:
                return value[:128]
    return current_session()


class VirbiusAuditCallback(BaseCallbackHandler):
    def __init__(self, app_id: str, tenant_id: str, endpoint: str = "", token: str = "", tools=None, **kwargs):
        from ._config import IntegrateConfig

        config_keys = {
            "device_id", "strict", "handshake", "handshake_deep", "sample_rate", "heartbeat_interval",
            "batch_size", "flush_interval", "queue_size", "max_repr", "record_args", "spill", "mode",
            "retry_attempts",
        }
        extra = {k: v for k, v in kwargs.items() if k in config_keys}
        self.config = IntegrateConfig(app_id=app_id, tenant_id=tenant_id, endpoint=endpoint, token=token, **extra)
        self.trace_id = f"lc-{uuid.uuid4()}"
        self.handshake_state = None
        self._clock = _StartClock()
        self._emitter = kwargs.get("emitter")
        if self._emitter is None:
            from .emitter import Emitter

            self._emitter = Emitter(self.config, transport=kwargs.get("transport"))
        self._heartbeat = None
        self._report_transport = kwargs.get("transport")
        if kwargs.get("heartbeat", True):
            from .heartbeat import Heartbeat

            self._heartbeat = Heartbeat(self.config, self._emitter, transport=kwargs.get("transport"))
            self._heartbeat.start()
        if self.config.strict:
            self._startup_check()
        elif self.config.handshake:
            self._handshake_in_background()
        if tools is not None:
            self._report_in_background(tools, kwargs.get("mcp_names") or {})

    def emitter(self):
        return self._emitter

    def on_tool_start(self, serialized, input_str, *, run_id, parent_run_id=None, tags=None, metadata=None, inputs=None, **kwargs):
        try:
            tool_name = self._tool_name(serialized)
            run_key = str(run_id)
            self._clock.mark(run_key)
            self._emit({
                "phase": "started",
                "call_id": run_key,
                "tool_name": tool_name,
                "session_id": _extract_session(metadata),
                "args_repr": bounded_repr(inputs if inputs is not None else input_str, self.config.max_repr),
            })
        except Exception:
            pass

    def on_tool_end(self, output, *, run_id, parent_run_id=None, tags=None, metadata=None, **kwargs):
        try:
            run_key = str(run_id)
            self._emit({
                "phase": "completed",
                "call_id": run_key,
                "tool_name": None,
                "duration_ms": self._clock.elapsed_ms(run_key),
                "session_id": _extract_session(metadata),
                "result_repr": bounded_repr(output, self.config.max_repr),
            })
        except Exception:
            pass

    def on_tool_error(self, error, *, run_id, parent_run_id=None, tags=None, metadata=None, **kwargs):
        try:
            run_key = str(run_id)
            self._emit({
                "phase": "error",
                "call_id": run_key,
                "tool_name": None,
                "duration_ms": self._clock.elapsed_ms(run_key),
                "session_id": _extract_session(metadata),
                "error": f"{type(error).__name__}: {str(error)[:400]}",
            })
        except Exception:
            pass

    def _tool_name(self, serialized) -> Optional[str]:
        if isinstance(serialized, dict):
            name = serialized.get("name") or serialized.get("id")
            if isinstance(name, str):
                return name[:128]
            if isinstance(name, list) and name:
                return str(name[-1])[:128]
        return None

    def _emit(self, base: dict) -> None:
        base.setdefault("trace_id", self.trace_id)
        base.setdefault("observed_at", time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        base["session_id"] = base.get("session_id") or "<unscoped>"
        base["event_id"] = str(uuid.uuid4())
        self._emitter.enqueue(base)

    def _report_in_background(self, tools, mcp_names: dict) -> None:
        from ._threads import shared_manager

        transport = self._report_transport
        shared_manager().ensure(
            f"inventory-{id(self)}",
            lambda: _report_once(tools, mcp_names, self.config, transport),
        )

    def _startup_check(self) -> None:
        from .handshake import VirbiusIntegrateError, run_handshake

        result = run_handshake(
            self.config, transport=self._report_transport, deep=self.config.handshake_deep
        )
        self.handshake_state = result
        if not result.ok:
            raise VirbiusIntegrateError(result)

    def _handshake_in_background(self) -> None:
        from ._threads import shared_manager
        from .handshake import run_handshake

        def _run():
            try:
                self.handshake_state = run_handshake(
                    self.config, transport=self._report_transport, deep=self.config.handshake_deep
                )
            except Exception:
                pass

        shared_manager().ensure(f"handshake-{id(self)}", _run)

    def handshake(self, deep: bool = False):
        """Run (or return the cached) startup handshake. Blocking."""
        from .handshake import run_handshake

        if self.handshake_state is None or deep:
            self.handshake_state = run_handshake(
                self.config, transport=self._report_transport, deep=deep or self.config.handshake_deep
            )
        return self.handshake_state

    def shutdown(self, timeout: float = 2.0) -> None:
        self._emitter.shutdown(timeout=timeout)


def _report_once(tools, mcp_names: dict, config, transport) -> None:
    from .report import report_tools

    try:
        report_tools(tools, config, mcp_names=mcp_names, transport=transport)
    except Exception:
        pass
