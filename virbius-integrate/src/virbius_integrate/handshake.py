from __future__ import annotations

import time
import uuid
from dataclasses import dataclass
from typing import Optional

from ._config import IntegrateConfig, package_version
from ._http import Transport, default_transport, get_json, post_with_retry
from ._logging import get_logger

logger = get_logger()

PROBE_TOOL_NAME = "virbius_handshake_probe"
HEALTHCHECK_SOURCE = "langchain-healthcheck"


class VirbiusIntegrateError(RuntimeError):
    """Raised by a strict startup handshake when the observer cannot reach control."""

    def __init__(self, result: "HandshakeResult"):
        super().__init__(result.reason or f"handshake failed at stage {result.stage}")
        self.result = result


@dataclass
class HandshakeResult:
    ok: bool
    stage: str
    status: int = 0
    reason: str = ""
    app_id: str = ""
    tenant_id: str = ""
    endpoint: str = ""
    deep: bool = False
    elapsed_ms: int = 0

    def as_dict(self) -> dict:
        return {
            "ok": self.ok,
            "stage": self.stage,
            "status": self.status,
            "reason": self.reason,
            "app_id": self.app_id,
            "tenant_id": self.tenant_id,
            "endpoint": self.endpoint,
            "deep": self.deep,
            "elapsed_ms": self.elapsed_ms,
        }


def _stage_for_status(status: int) -> tuple:
    if status == 0:
        return "reach", "connection refused or timeout"
    if status in (401, 403):
        return "auth", "invalid integrate token"
    if status == 404:
        return "route", "unknown tenant/app path"
    if status == 413:
        return "ingest", "batch too large"
    if status >= 400:
        return "reach", f"http {status}"
    return "ok", ""


def run_handshake(
    config: IntegrateConfig,
    transport: Optional[Transport] = None,
    deep: bool = False,
) -> HandshakeResult:
    """Verify reachability, token, and tenant/app route (stage 1); optionally an
    ingest round-trip (stage 2). Uses the same transport/headers as the emitter so
    it exercises the real path. Logs one line (INFO ok / WARNING failed)."""
    transport = transport or default_transport
    started = time.time()
    headers = config.auth_headers()

    def _finish(ok: bool, stage: str, status: int, reason: str, deep_used: bool) -> HandshakeResult:
        result = HandshakeResult(
            ok=ok,
            stage=stage,
            status=status,
            reason=reason,
            app_id=config.app_id,
            tenant_id=config.tenant_id,
            endpoint=config.endpoint,
            deep=deep_used,
            elapsed_ms=int((time.time() - started) * 1000),
        )
        _log(result)
        return result

    # Stage 1: reachability + token + tenant/app route (idempotent heartbeat upsert).
    beat_payload = {
        "tenant_id": config.tenant_id,
        "app_id": config.app_id,
        "device_id": config.device_id,
        "package_version": package_version(),
        "mode": config.mode,
        "dropped_total": 0,
        "ts": _now(),
    }
    ok, status, _ = post_with_retry(config.heartbeat_url(), beat_payload, headers, transport, 1)
    if not ok:
        stage, reason = _stage_for_status(status)
        return _finish(False, stage, status, reason, False)

    if not deep:
        return _finish(True, "ok", status, "", False)

    # Stage 2: ingest round-trip — the probe event must be readable back.
    probe = _probe_event(config)
    ok2, status2, _ = post_with_retry(
        config.observation_batch_url(),
        {"events": [probe], "stats": {"dropped_started": 0, "dropped_completed": 0, "queue_depth": 0}},
        headers,
        transport,
        1,
    )
    if not ok2:
        stage, reason = _stage_for_status(status2)
        return _finish(False, stage or "ingest", status2, reason or "probe ingest failed", True)

    query_url = f"{config.observation_query_url()}?call_id={probe['call_id']}&limit=10"
    qcode, qbody = get_json(query_url, headers, transport)
    events = qbody.get("events") if isinstance(qbody, dict) else None
    if qcode != 200 or not events:
        return _finish(False, "ingest", qcode, "probe event not readable back", True)

    return _finish(True, "ok", 200, "", True)


def _probe_event(config: IntegrateConfig) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "call_id": f"hs-{uuid.uuid4()}",
        "phase": "started",
        "tenant_id": config.tenant_id,
        "app_id": config.app_id,
        "device_id": config.device_id,
        "session_id": "handshake",
        "trace_id": f"lc-{uuid.uuid4()}",
        "tool_name": PROBE_TOOL_NAME,
        "duration_ms": None,
        "args_repr": None,
        "result_repr": None,
        "error": None,
        "source": HEALTHCHECK_SOURCE,
        "package_version": package_version(),
        "observed_at": _now(),
    }


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _log(result: HandshakeResult) -> None:
    if result.ok:
        logger.info(
            "virbius-integrate handshake ok: app=%s tenant=%s control=%s token=valid ingest=%s (%dms)",
            result.app_id,
            result.tenant_id,
            result.endpoint,
            "ok" if result.deep else "unverified",
            result.elapsed_ms,
        )
    else:
        logger.warning(
            "virbius-integrate handshake FAILED stage=%s status=%s reason=%s; events will be dropped (best-effort)",
            result.stage,
            result.status,
            result.reason,
        )
