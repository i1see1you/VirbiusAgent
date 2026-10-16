"""`virbius_mcp_python doctor` (SDK-embed diagnostics) tests."""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "python"))

import virbius_mcp_python as v  # noqa: E402
from virbius_mcp_python.doctor import render  # noqa: E402

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"


def _good_env():
    return {
        "VIRBIUS_LICENSE_JWT": (FIXTURES / "license.jwt").read_text().strip(),
        "VIRBIUS_LICENSE_PUBLIC_KEY": (FIXTURES / "license_pub.pem").read_text(),
        "VIRBIUS_APP_ID": "test-agent",
    }


def test_diagnose_not_ready_when_empty():
    r = v.diagnose(env={})
    assert r["ok"] is False
    assert r["license"]["ok"] is False
    assert any("license" in p for p in r["problems"])
    assert not r["env"]["VIRBIUS_APP_ID"]
    assert "NOT READY" in render(r)


def test_diagnose_ok_with_valid_license():
    r = v.diagnose(env=_good_env())
    assert r["ok"] is True
    assert r["license"]["ok"] is True
    assert r["license"]["app_id"] == "test-agent"
    assert r["allowed_tools"] == ["read_file", "search"]
    assert "RESULT: OK" in render(r)


def test_diagnose_reports_missing_app_id_without_raising():
    env = _good_env()
    env.pop("VIRBIUS_APP_ID")
    r = v.diagnose(env=env)
    assert r["ok"] is False
    assert any("app id" in p for p in r["problems"])
    assert r["license"]["ok"] is False


def test_diagnose_engine_probe_flag_off_by_default():
    r = v.diagnose(env={**_good_env(), "VIRBIUS_ENGINE_URL": "http://127.0.0.1:8082"})
    assert r["engine"]["url"] == "http://127.0.0.1:8082"
    assert r["engine"]["reachable"] is None  # not probed unless asked
