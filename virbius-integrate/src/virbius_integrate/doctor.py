from __future__ import annotations

import argparse
import json
import sys
import uuid

from ._config import IntegrateConfig, package_version
from ._http import default_transport, get_json, post_with_retry
from ._threads import shared_manager
from .callback import VirbiusAuditCallback
from .emitter import Emitter


def doctor(config: IntegrateConfig, callback: VirbiusAuditCallback = None) -> dict:
    checks = []

    def check(name, ok, detail=""):
        checks.append({"name": name, "ok": bool(ok), "detail": str(detail)[:256]})

    check("package_version", True, package_version())
    try:
        import langchain_core

        check("langchain_core_version", True, langchain_core.__version__)
    except ImportError:
        check("langchain_core_version", False, "not installed")

    headers = config.auth_headers()
    emitter = callback.emitter() if callback else None
    if emitter is not None:
        stats = emitter.stats()
        check("queue_depth", stats["queue_depth"] < config.queue_size, stats)
        check("dropped_visible", True, f"started={stats['dropped_started']} completed={stats['dropped_completed']}")
        check("delivery", stats["delivery_failures"] == 0, f"failures={stats['delivery_failures']}")
    threads = shared_manager().alive_names()
    check("threads_alive", "emitter" in threads or emitter is None, threads)

    call_id = f"doctor-{uuid.uuid4()}"
    probe_payload = {
        "events": [
            _probe_event(config, call_id, "started"),
            _probe_event(config, call_id, "completed"),
        ],
        "stats": {"dropped_started": 0, "dropped_completed": 0, "queue_depth": 0},
    }
    ok, code, body = post_with_retry(
        config.observation_batch_url(), probe_payload, headers, default_transport, config.retry_attempts
    )
    check("ingest_endpoint", ok, f"status={code}")
    if ok:
        query_url = f"{config.observation_query_url()}?call_id={call_id}&limit=10"
        qcode, qbody = get_json(query_url, headers, default_transport)
        events = qbody.get("events") if isinstance(qbody, dict) else None
        check("read_back", qcode == 200 and events and len(events) >= 2, f"status={qcode} count={len(events or [])}")

    passed = all(item["ok"] for item in checks)
    return {"ok": passed, "checks": checks}


def _probe_event(config: IntegrateConfig, call_id: str, phase: str) -> dict:
    return {
        "event_id": str(uuid.uuid4()),
        "call_id": call_id,
        "phase": phase,
        "tenant_id": config.tenant_id,
        "app_id": config.app_id,
        "device_id": config.device_id,
        "session_id": "doctor",
        "trace_id": f"lc-{uuid.uuid4()}",
        "tool_name": "virbius_doctor_probe",
        "duration_ms": 0 if phase == "completed" else None,
        "args_repr": None,
        "result_repr": None,
        "error": None,
        "source": "langchain-native",
        "package_version": package_version(),
        "observed_at": _now(),
    }


def _now() -> str:
    import time

    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def main() -> int:
    parser = argparse.ArgumentParser(prog="virbius_integrate.doctor")
    parser.add_argument("--endpoint", required=True)
    parser.add_argument("--app-id", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--token", default="")
    args = parser.parse_args()
    config = IntegrateConfig(
        app_id=args.app_id,
        tenant_id=args.tenant,
        endpoint=args.endpoint,
        token=args.token,
    )
    result = doctor(config)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
