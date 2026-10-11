import json
import pathlib

import pytest

import virbius_mcp_python as v

FIXTURES = pathlib.Path(__file__).parent / "fixtures"


@pytest.fixture(scope="session", autouse=True)
def _configure_edge(tmp_path_factory):
    """Load the offline edge manifest once for the whole test session."""
    cfg = {
        "offline_manifest_path": str(FIXTURES / "edge-manifest.json"),
        "cache_dir": str(tmp_path_factory.mktemp("edge-cache")),
        "tenant_id": "tenant-1",
        "app_id": "test-agent",
    }
    result = v.configure_rules(json.dumps(cfg))
    assert result["ok"] is True
    assert result["mode"] == "offline"
    yield


@pytest.fixture(scope="session")
def fixtures_dir():
    return FIXTURES


@pytest.fixture(scope="session")
def license_creds():
    """(jwt, public_key_pem, app_id) of the valid test license fixture."""
    jwt = (FIXTURES / "license.jwt").read_text().strip()
    pem = (FIXTURES / "license_pub.pem").read_text()
    return jwt, pem, "test-agent"
