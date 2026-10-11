import json

import virbius_mcp_python as v


def test_licensed_tool_with_valid_args_allowed(license_creds):
    jwt, pem, app_id = license_creds
    result = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, app_id)
    assert result["allowed"] is True
    assert result["reason"] is None
    assert result["fast_path"] is True


def test_tool_not_in_license_allowlist_denied(license_creds):
    jwt, pem, app_id = license_creds
    result = v.precheck_tool("db_delete", json.dumps({"id": 1}), jwt, pem, app_id)
    assert result["allowed"] is False
    assert "allowlist" in (result["reason"] or "")


def test_args_schema_violation_denied(license_creds):
    jwt, pem, app_id = license_creds
    result = v.precheck_tool("read_file", json.dumps({}), jwt, pem, app_id)
    assert result["allowed"] is False
    assert "args validation" in (result["reason"] or "")


def test_invalid_args_json_raises(license_creds):
    import pytest

    jwt, pem, app_id = license_creds
    with pytest.raises(ValueError):
        v.precheck_tool("read_file", "{not json", jwt, pem, app_id)
