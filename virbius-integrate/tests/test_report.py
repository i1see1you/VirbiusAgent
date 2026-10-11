from __future__ import annotations

from virbius_integrate._config import IntegrateConfig
from virbius_integrate.report import report_tools


class FakeTool:
    def __init__(self, name, description=""):
        self.name = name
        self.description = description


def test_report_posts_inventory_with_binding_and_mapping():
    posts = []

    def transport(method, url, payload, headers):
        posts.append((url, payload))
        return 200, {"accepted": 2}

    config = IntegrateConfig(app_id="crm", tenant_id="acme", endpoint="http://c:8080", token="t")
    result = report_tools(
        [FakeTool("db_delete", "drop rows"), FakeTool("read_file", "read")],
        config,
        mcp_names={"read_file": "fs__read_file"},
        transport=transport,
    )
    assert result["ok"] is True
    url, payload = posts[0]
    assert url.endswith("/apps/crm/tool-inventory")
    tools = {row["tool_name"]: row for row in payload["tools"]}
    assert tools["db_delete"]["risk_hint"] == "high"
    assert tools["db_delete"]["bound_mode"] == "A"
    assert tools["read_file"]["mcp_name"] == "fs__read_file"
    assert tools["read_file"]["mcp_name"] is not None


def test_report_never_raises_on_server_error():
    def transport(method, url, payload, headers):
        return 500, {"err": 1}

    config = IntegrateConfig(app_id="crm", tenant_id="acme", endpoint="http://c:8080", retry_attempts=1)
    result = report_tools([FakeTool("x")], config, transport=transport)
    assert result["ok"] is False
    assert result["status"] == 500
