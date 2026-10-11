from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Optional

DEFAULT_BATCH_SIZE = 50
DEFAULT_FLUSH_INTERVAL = 0.5
DEFAULT_QUEUE_SIZE = 512
DEFAULT_MAX_REPR = 2048
DEFAULT_HEARTBEAT_INTERVAL = 60.0
DEFAULT_SAMPLE_RATE = 1.0
DEFAULT_RETRY_ATTEMPTS = 3
SOURCE = "langchain-native"

_PACKAGE_VERSION = "0.1.0"


def package_version() -> str:
    return _PACKAGE_VERSION


@dataclass
class IntegrateConfig:
    app_id: str
    tenant_id: str
    endpoint: str
    token: str = ""
    device_id: str = field(default_factory=lambda: os.environ.get("HOSTNAME") or "unknown-device")
    strict: bool = False
    handshake: bool = True
    handshake_deep: bool = False
    sample_rate: float = DEFAULT_SAMPLE_RATE
    heartbeat_interval: float = DEFAULT_HEARTBEAT_INTERVAL
    batch_size: int = DEFAULT_BATCH_SIZE
    flush_interval: float = DEFAULT_FLUSH_INTERVAL
    queue_size: int = DEFAULT_QUEUE_SIZE
    max_repr: int = DEFAULT_MAX_REPR
    record_args: bool = True
    spill: bool = False
    mode: str = "observe"
    retry_attempts: int = DEFAULT_RETRY_ATTEMPTS

    def __post_init__(self) -> None:
        if not self.app_id or not self.tenant_id:
            raise ValueError("app_id and tenant_id are required")
        if not self.endpoint:
            self.endpoint = os.environ.get("VIRBIUS_INTEGRATE_ENDPOINT", "")
        if not self.endpoint:
            raise ValueError("endpoint is required (param or VIRBIUS_INTEGRATE_ENDPOINT)")
        if not self.token:
            self.token = os.environ.get("VIRBIUS_INTEGRATE_TOKEN", "")
        if not 0.0 <= self.sample_rate <= 1.0:
            raise ValueError("sample_rate must be in [0, 1]")
        self.endpoint = self.endpoint.rstrip("/")

    def auth_headers(self) -> dict:
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
            headers["X-Virbius-Integrate-Token"] = self.token
        return headers

    def _app_base(self) -> str:
        return f"{self.endpoint}/api/v1/internal/integrate/tenants/{self.tenant_id}/apps/{self.app_id}"

    def observation_batch_url(self) -> str:
        return f"{self._app_base()}/tool-observations/batch"

    def observation_query_url(self) -> str:
        return f"{self._app_base()}/tool-observations"

    def heartbeat_url(self) -> str:
        return f"{self._app_base()}/heartbeat"

    def inventory_url(self) -> str:
        return f"{self._app_base()}/tool-inventory"

    def liveness_url(self) -> str:
        return f"{self._app_base()}/liveness"
