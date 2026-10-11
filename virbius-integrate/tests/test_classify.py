from __future__ import annotations

from virbius_integrate.classify import classify_tool, classify_tools, RULE_TABLE_VERSION


def test_high_verb_gets_high_risk_and_mode_b():
    result = classify_tool("db_delete", "delete rows from table")
    assert result["risk_hint"] == "high"
    assert result["recommended_mode"] == "B"
    assert "verb:delete" in result["matched_rules"]
    assert result["confidence"] == "high"


def test_pay_send_publish_are_high():
    for name in ("pay_order", "send_email", "publish_post", "exec_shell"):
        result = classify_tool(name, "")
        assert result["risk_hint"] == "high", name


def test_read_only_is_low_by_default():
    result = classify_tool("get_time", "return current time")
    assert result["risk_hint"] == "low"
    assert result["recommended_mode"] == "A"


def test_read_sensitive_data_is_not_low():
    result = classify_tool("query_user", "query user", args_keys=["email", "phone"])
    assert result["risk_hint"] == "medium"
    assert result["recommended_mode"] == "B"
    assert any(m.startswith("sensitive:") for m in result["matched_rules"])


def test_egress_schema_key_upgrades():
    result = classify_tool("fetch_doc", "fetch a doc", args_keys=["url"])
    assert result["risk_hint"] == "medium"


def test_credential_key_is_high():
    result = classify_tool("setup_client", "", args_keys=["api_key"])
    assert result["risk_hint"] == "high"


def test_destructive_description_phrase_is_high():
    result = classify_tool("archive_records", "irreversible archive operation")
    assert result["risk_hint"] == "high"


def test_classify_tools_reads_tool_objects():
    class FakeTool:
        name = "drop_table"
        description = "drop it"

        args_schema = None

    rows = classify_tools([FakeTool()])
    assert rows[0]["tool_name"] == "drop_table"
    assert rows[0]["risk_hint"] == "high"
    assert rows[0]["rule_table_version"] == RULE_TABLE_VERSION
