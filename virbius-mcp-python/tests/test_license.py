import pytest

import virbius_mcp_python as v


def test_verify_license_returns_claims(license_creds):
    jwt, pem, app_id = license_creds
    info = v.verify_license(jwt, pem, app_id)
    assert info["app_id"] == app_id
    assert info["tenant_id"] == "tenant-1"
    assert "read_file" in info["allowed_tools"]


def test_verify_license_wrong_app_id_rejected(license_creds):
    jwt, pem, _ = license_creds
    with pytest.raises(ValueError):
        v.verify_license(jwt, pem, "some-other-agent")


def test_verify_license_expired_rejected(fixtures_dir):
    expired = (fixtures_dir / "license_expired.jwt").read_text().strip()
    pem = (fixtures_dir / "license_pub.pem").read_text()
    with pytest.raises(ValueError):
        v.verify_license(expired, pem, "test-agent")


def test_verify_license_malformed_rejected(license_creds):
    _, pem, app_id = license_creds
    with pytest.raises(ValueError):
        v.verify_license("not-a-jwt", pem, app_id)
