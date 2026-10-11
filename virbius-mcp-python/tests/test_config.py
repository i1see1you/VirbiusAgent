import json

import virbius_mcp_python as v


def test_configure_offline_mode(tmp_path):
    cfg = {
        "offline_manifest_path": str(_manifest()),
        "cache_dir": str(tmp_path),
        "tenant_id": "tenant-1",
        "app_id": "test-agent",
    }
    result = v.configure_rules(json.dumps(cfg))
    assert result["ok"] is True
    assert result["mode"] == "offline"
    assert result["sync_error"] is None


def test_reload_rules_does_not_raise():
    v.reload_rules()


def _manifest():
    import pathlib

    return pathlib.Path(__file__).parent / "fixtures" / "edge-manifest.json"
