from __future__ import annotations

import json
import time
from typing import Callable, Optional

import httpx


Transport = Callable[[str, str, Optional[dict], dict], tuple[int, dict]]


def default_transport(method: str, url: str, payload: Optional[dict], headers: dict) -> tuple[int, dict]:
    client = httpx.Client(timeout=5.0)
    try:
        body = json.dumps(payload, ensure_ascii=False) if payload is not None else None
        response = client.request(method, url, content=body, headers=headers)
        try:
            parsed = response.json()
        except Exception:
            parsed = {"raw": response.text[:512]}
        return response.status_code, parsed
    finally:
        client.close()


def post_with_retry(
    url: str,
    payload: dict,
    headers: dict,
    transport: Transport,
    attempts: int,
    backoff_base: float = 0.5,
) -> tuple[bool, int, dict]:
    last_code = 0
    last_body: dict = {}
    for attempt in range(max(1, attempts)):
        try:
            code, body = transport("POST", url, payload, headers)
        except Exception as exc:
            code, body = 0, {"error": str(exc)[:256]}
        last_code, last_body = code, body
        if 200 <= code < 300:
            return True, code, body
        if code in (413, 401, 403, 404):
            return False, code, body
        time.sleep(backoff_base * (2**attempt))
    return False, last_code, last_body


def get_json(url: str, headers: dict, transport: Transport) -> tuple[int, dict]:
    try:
        return transport("GET", url, None, headers)
    except Exception as exc:
        return 0, {"error": str(exc)[:256]}
