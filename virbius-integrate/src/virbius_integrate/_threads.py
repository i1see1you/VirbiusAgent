from __future__ import annotations

import os
import threading
from typing import Callable, Optional

_after_fork_handlers: list = []
_register_lock = threading.Lock()
_registered = False


def on_fork_child(handler: Callable[[], None]) -> None:
    global _registered
    with _register_lock:
        _after_fork_handlers.append(handler)
        if not _registered:
            os.register_at_fork(after_in_child=_run_handlers)
            _registered = True


def _run_handlers() -> None:
    for handler in list(_after_fork_handlers):
        try:
            handler()
        except Exception:
            pass


class ThreadManager:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._threads: dict[str, threading.Thread] = {}
        on_fork_child(self._reset)

    def _reset(self) -> None:
        self._lock = threading.Lock()
        self._threads = {}

    def ensure(self, name: str, target: Callable[[], None]) -> bool:
        with self._lock:
            thread = self._threads.get(name)
            if thread is not None and thread.is_alive():
                return False
            thread = threading.Thread(target=target, name=f"virbius-integrate-{name}", daemon=True)
            self._threads[name] = thread
            thread.start()
            return True

    def alive(self, name: str) -> bool:
        with self._lock:
            thread = self._threads.get(name)
            return thread is not None and thread.is_alive()

    def alive_names(self) -> list:
        with self._lock:
            return [name for name, t in self._threads.items() if t.is_alive()]


_shared_manager: Optional[ThreadManager] = None
_shared_lock = threading.Lock()


def shared_manager() -> ThreadManager:
    global _shared_manager
    with _shared_lock:
        if _shared_manager is None:
            _shared_manager = ThreadManager()
        return _shared_manager
