from __future__ import annotations

import json
import os
import uuid

from virbius_integrate._config import IntegrateConfig

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "contract_events.json")


def test_config_urls_and_env_token(monkeypatch):
    monkeypatch.setenv("VIRBIUS_INTEGRATE_TOKEN", "envtok")
    config = IntegrateConfig(app_id="a", tenant_id="t", endpoint="http://c:8080/")
    assert config.endpoint == "http://c:8080"
    assert config.auth_headers()["Authorization"] == "Bearer envtok"
    assert config.observation_batch_url().endswith(
        "/api/v1/internal/integrate/tenants/t/apps/a/tool-observations/batch"
    )
    assert config.heartbeat_url().endswith("/heartbeat")
    assert config.inventory_url().endswith("/tool-inventory")


def test_config_requires_ids():
    try:
        IntegrateConfig(app_id="", tenant_id="t", endpoint="http://c")
    except ValueError:
        return
    raise AssertionError("expected ValueError")
