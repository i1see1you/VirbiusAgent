from __future__ import annotations

import pytest

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.callback import VirbiusAuditCallback
from virbius_integrate.handshake import VirbiusIntegrateError, run_handshake


def _config(**kw):
    return IntegrateConfig(app_id="a", tenant_id="t", endpoint="http://control:8080", **kw)


def test_handshake_ok_on_2xx():
    result = run_handshake(_config(), transport=lambda m, u, p, h: (200, {"ok": True}))
    assert result.ok is True
    assert result.stage == "ok"
    assert result.app_id == "a" and result.tenant_id == "t"


def test_handshake_auth_failure_on_401():
    result = run_handshake(_config(), transport=lambda m, u, p, h: (401, {}))
    assert result.ok is False
    assert result.stage == "auth"
    assert result.status == 401


def test_handshake_reach_failure_on_connection_error():
    result = run_handshake(_config(), transport=lambda m, u, p, h: (0, {"error": "refused"}))
    assert result.ok is False
    assert result.stage == "reach"


def test_handshake_route_failure_on_404():
    result = run_handshake(_config(), transport=lambda m, u, p, h: (404, {}))
    assert result.ok is False
    assert result.stage == "route"


def test_deep_handshake_reads_probe_back():
    urls = []

    def transport(method, url, payload, headers):
        urls.append(url)
        if "heartbeat" in url:
            return 200, {"ok": True}
        if method == "POST":
            return 200, {"accepted": 1}
        return 200, {"events": [{"phase": "started"}]}

    result = run_handshake(_config(), transport=transport, deep=True)
    assert result.ok is True
    assert result.deep is True
    assert any("tool-observations/batch" in u for u in urls)
    assert any("call_id=hs-" in u for u in urls)


def test_deep_handshake_fails_when_probe_not_readable_back():
    def transport(method, url, payload, headers):
        if "heartbeat" in url:
            return 200, {"ok": True}
        if method == "POST":
            return 200, {"accepted": 1}
        return 200, {"events": []}

    result = run_handshake(_config(), transport=transport, deep=True)
    assert result.ok is False
    assert result.stage == "ingest"


def test_strict_callback_raises_on_handshake_failure():
    with pytest.raises(VirbiusIntegrateError):
        VirbiusAuditCallback(
            app_id="a",
            tenant_id="t",
            endpoint="http://control:8080",
            token="x",
            strict=True,
            heartbeat=False,
            transport=lambda m, u, p, h: (401, {}),
        )


def test_non_strict_callback_does_not_raise_and_records_state():
    callback = VirbiusAuditCallback(
        app_id="a",
        tenant_id="t",
        endpoint="http://control:8080",
        token="x",
        heartbeat=False,
        handshake=False,
        transport=lambda m, u, p, h: (401, {}),
    )
    result = callback.handshake()
    assert result.ok is False
    assert callback.handshake_state is result
