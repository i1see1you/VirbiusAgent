from __future__ import annotations

import time
from typing import Optional

from ._config import IntegrateConfig, package_version
from ._http import Transport, default_transport, post_with_retry
from ._threads import shared_manager


class Heartbeat:
    def __init__(self, config: IntegrateConfig, emitter, transport: Optional[Transport] = None):
        self.config = config
        self.emitter = emitter
        self.transport = transport or default_transport
        self.last_beat_ts: float = 0.0
        self.failures = 0

    def start(self) -> None:
        shared_manager().ensure("heartbeat", self._run)

    def _run(self) -> None:
        while True:
            self.beat()
            time.sleep(self.config.heartbeat_interval)

    def beat(self) -> bool:
        stats = self.emitter.stats() if self.emitter else {}
        payload = {
            "tenant_id": self.config.tenant_id,
            "app_id": self.config.app_id,
            "device_id": self.config.device_id,
            "package_version": package_version(),
            "mode": self.config.mode,
            "dropped_total": stats.get("dropped_started", 0) + stats.get("dropped_completed", 0),
            "ts": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        ok, code, _ = post_with_retry(
            self.config.heartbeat_url(),
            payload,
            self.config.auth_headers(),
            self.transport,
            self.config.retry_attempts,
        )
        self.last_beat_ts = time.time()
        if not ok:
            self.failures += 1
        return ok
