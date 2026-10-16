"""VirbiusGuard (stateful wrapper + decorators) tests."""

import pathlib
import sys

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "python"))

import virbius_mcp_python as v  # noqa: E402

FIXTURES = pathlib.Path(__file__).resolve().parent / "fixtures"
JWT = (FIXTURES / "license.jwt").read_text().strip()
PEM = (FIXTURES / "license_pub.pem").read_text()


def make_guard(**over):
    env = {
        "VIRBIUS_LICENSE_JWT": JWT,
        "VIRBIUS_LICENSE_PUBLIC_KEY": PEM,
        "VIRBIUS_APP_ID": "test-agent",
    }
    env.update(over)
    return v.VirbiusGuard.from_env(env)


# ── from_env ────────────────────────────────────────────────────────────


def test_from_env_inline_and_missing_pieces():
    g = make_guard()
    assert g.app_id == "test-agent"
    assert g.claims["allowed_tools"] == ["read_file", "search"]

    with pytest.raises(ValueError, match="no license"):
        make_guard(VIRBIUS_LICENSE_JWT="", VIRBIUS_LICENSE_FILE="")
    with pytest.raises(ValueError, match="no public key"):
        make_guard(VIRBIUS_LICENSE_PUBLIC_KEY="")
    with pytest.raises(ValueError, match="VIRBIUS_APP_ID"):
        make_guard(VIRBIUS_APP_ID="")


def test_from_env_reads_files(tmp_path):
    jwt_file = tmp_path / "license.jwt"
    pem_file = tmp_path / "pub.pem"
    jwt_file.write_text(JWT)
    pem_file.write_text(PEM)
    g = v.VirbiusGuard.from_env(
        {
            "VIRBIUS_LICENSE_FILE": str(jwt_file),
            "VIRBIUS_LICENSE_PUBLIC_KEY": str(pem_file),
            "VIRBIUS_APP_ID": "test-agent",
        }
    )
    assert g.precheck("read_file", {"path": "/tmp/a"})["allowed"] is True


# ── precheck / guard ────────────────────────────────────────────────────


def test_precheck_accepts_dict_and_json_string():
    g = make_guard()
    via_dict = g.precheck("read_file", {"path": "/tmp/a"})
    via_json = g.precheck("read_file", '{"path": "/tmp/a"}')
    assert via_dict["allowed"] is True
    assert via_json["allowed"] is True


def test_guard_raises_permission_error_with_reason():
    g = make_guard()
    with pytest.raises(PermissionError, match="not in license allowlist"):
        g.guard("db_delete", {"id": 1})


def test_verify_returns_claims():
    g = make_guard()
    claims = g.verify()
    assert claims["app_id"] == "test-agent"
    assert "read_file" in claims["allowed_tools"]


def test_app_id_mismatch_error_names_both_sides():
    g = v.VirbiusGuard(JWT, PEM, "other-agent")
    with pytest.raises(ValueError, match="app_id mismatch"):
        g.precheck("read_file", {"path": "/tmp/a"})


# ── decorators ──────────────────────────────────────────────────────────


def test_guarded_decorator_preserves_signature_and_denies():
    g = make_guard()

    @g.guarded
    def read_file(path: str) -> str:
        """read a file"""
        return f"content of {path}"

    import inspect

    assert str(inspect.signature(read_file)) == "(path: str) -> str"
    assert read_file.__name__ == "read_file"
    assert read_file(path="/tmp/a") == "content of /tmp/a"

    @g.guarded
    def db_delete(id: int) -> str:
        """delete"""
        return "deleted"

    with pytest.raises(PermissionError, match="db_delete"):
        db_delete(id=1)


def test_guarded_explicit_name():
    g = make_guard()

    @g.guarded(name="read_file")
    def aliased(path: str) -> str:
        return path

    assert aliased(path="x") == "x"


def test_openai_tool_adapter_keeps_schema():
    agents = pytest.importorskip("agents")
    g = make_guard()

    @g.openai_tool
    def read_file(path: str, verbose: bool = False) -> str:
        """Read a document from disk."""
        return path

    assert isinstance(read_file, agents.FunctionTool)
    assert read_file.name == "read_file"
    schema = read_file.params_json_schema
    assert schema["properties"]["path"]["type"] == "string"
    assert schema["properties"]["verbose"]["type"] == "boolean"


# ── error → LLM propagation ─────────────────────────────────────────────


def test_guard_raises_virbius_denied_with_reason_code():
    g = make_guard()
    with pytest.raises(v.VirbiusDenied) as ei:
        g.guard("db_delete", {"id": 1})
    # Still a PermissionError (back-compat), but typed + carries the machine code.
    assert isinstance(ei.value, PermissionError)
    assert ei.value.reason_code == "not_in_allowlist"
    assert "blocked by policy" in str(ei.value)


def test_denial_message_is_single_sourced():
    # guard() must build the refusal via policy_blocked_message(), and that must
    # match the real reason (tool name) the Rust precheck emits — this ties the
    # model-visible wording (and the A/B harness) to one source of truth.
    g = make_guard()
    with pytest.raises(v.VirbiusDenied) as ei:
        g.guard("db_delete", {"id": 1})
    assert str(ei.value) == v.policy_blocked_message("tool 'db_delete' not in license allowlist")


def test_virbius_tool_error_is_pass_through():
    # Proxy mode: the proxy authors a human, sanitized message; the formatter
    # reveals it verbatim (no rewriting, no detection, no injected advice).
    msg = "The tool call was blocked by policy: tool 'x' not in license allowlist."
    assert v.virbius_tool_error(None, RuntimeError(msg)) == msg


def test_virbius_tool_error_falls_back_only_when_empty():
    assert v.virbius_tool_error(None, RuntimeError("")) == "An error occurred while running the tool."


def test_openai_tool_formatter_is_type_based():
    pytest.importorskip("agents")
    g = make_guard()

    @g.openai_tool
    def read_file(path: str) -> str:
        """read"""
        return path

    ffe = getattr(read_file, "_failure_error_function", None)
    assert ffe is not None and ffe is not v.virbius_tool_error  # the type-based one
    # A Virbius denial is revealed; any other exception stays generic (no leak).
    denied = v.VirbiusDenied("The tool call was blocked by policy: nope.", reason_code="not_in_allowlist")
    assert "blocked by policy" in ffe(None, denied)
    assert ffe(None, ValueError("connection reset by peer")) == "An error occurred while running the tool."


def test_openai_tool_error_is_public_and_type_based():
    # The OpenAI adapter's formatter is public and symmetric with virbius_tool_error.
    assert v.openai_tool_error(None, v.VirbiusDenied("The tool call was blocked by policy: x.")) == (
        "The tool call was blocked by policy: x."
    )
    assert v.openai_tool_error(None, ValueError("user tool secret")) == "An error occurred while running the tool."


def test_openai_tools_wraps_all_with_type_based_formatter():
    agents = pytest.importorskip("agents")
    g = make_guard()

    def read_file(path: str) -> str:
        """read"""
        return path

    def search(query: str) -> str:
        """search"""
        return query

    tools = g.openai_tools([read_file, search])
    assert len(tools) == 2
    assert all(isinstance(t, agents.FunctionTool) for t in tools)
    assert {t.name for t in tools} == {"read_file", "search"}
    assert all(getattr(t, "_failure_error_function", None) is v.openai_tool_error for t in tools)


# ── framework-agnostic govern() ─────────────────────────────────────────


def test_govern_wraps_any_callable_allow_and_deny():
    g = make_guard()

    def read_file(path: str) -> str:
        return f"content {path}"

    safe = v.govern(read_file, guard=g)
    assert safe(path="/tmp/a") == "content /tmp/a"

    def db_delete(id: int) -> str:
        return "gone"

    risky = v.govern(db_delete, guard=g)
    with pytest.raises(v.VirbiusDenied) as ei:
        risky(id=1)
    # Same single-sourced wording as guard()/adapters.
    assert str(ei.value) == v.policy_blocked_message("tool 'db_delete' not in license allowlist")


def test_govern_decorator_with_name_preserves_signature():
    import inspect

    g = make_guard()

    @v.govern(name="read_file", guard=g)
    def aliased(path: str) -> str:
        """doc"""
        return path

    assert str(inspect.signature(aliased)) == "(path: str) -> str"
    assert aliased.__name__ == "aliased"
    assert aliased(path="x") == "x"


def test_guarded_and_govern_share_one_core():
    g = make_guard()

    @g.guarded
    def db_delete(id: int) -> str:
        return "x"

    @v.govern(name="db_delete", guard=g)
    def also_denied(id: int) -> str:
        return "x"

    with pytest.raises(v.VirbiusDenied) as e1:
        db_delete(id=1)
    with pytest.raises(v.VirbiusDenied) as e2:
        also_denied(id=1)
    assert str(e1.value) == str(e2.value)


def test_govern_async_prechecks_before_await():
    import asyncio

    g = make_guard()
    ran = []

    @v.govern(name="read_file", guard=g)
    async def read_file(path: str) -> str:
        ran.append("read")
        return f"async {path}"

    assert asyncio.run(read_file(path="/tmp/a")) == "async /tmp/a"

    @v.govern(name="db_delete", guard=g)
    async def delete(id: int) -> str:
        ran.append("delete")
        return "no"

    with pytest.raises(v.VirbiusDenied):
        asyncio.run(delete(id=1))
    assert ran == ["read"]  # the denied coroutine was never awaited


# ── LangChain adapter ───────────────────────────────────────────────────


def test_langchain_tool_adapter_keeps_schema_and_surfaces_denial():
    pytest.importorskip("langchain_core.tools")
    g = make_guard()

    def read_file(path: str) -> str:
        """Read a document."""
        return f"content {path}"

    tool = g.langchain_tool(read_file)
    assert tool.name == "read_file"
    assert "path" in tool.args
    assert tool.invoke({"path": "/tmp/a"}) == "content /tmp/a"

    def db_delete(id: int) -> str:
        """delete"""
        return "gone"

    denied = g.langchain_tool(db_delete).invoke({"id": 1})
    assert "blocked by policy" in denied and "db_delete" in denied


def test_langchain_tools_wraps_all_and_non_policy_errors_propagate():
    pytest.importorskip("langchain_core.tools")
    g = make_guard()

    def read_file(path: str) -> str:
        """read"""
        return path

    def search(query: str) -> str:
        """search"""
        return query

    tools = g.langchain_tools([read_file, search])
    assert [t.name for t in tools] == ["read_file", "search"]

    def broken(path: str) -> str:
        """read"""
        raise ValueError("disk I/O error at /var/secret")

    broken.__name__ = "read_file"  # allowed, so the pre-check passes and the body raises
    with pytest.raises(ValueError) as ei:
        g.langchain_tool(broken).invoke({"path": "/tmp/a"})
    # A user tool's internal error is not laundered into a policy message.
    assert "disk I/O error" in str(ei.value)
    assert "blocked by policy" not in str(ei.value)


def test_govern_env_guard_is_lazy_and_cached(monkeypatch):
    import virbius_mcp_python.guard as guard_mod

    for k in (
        "VIRBIUS_LICENSE_JWT",
        "VIRBIUS_LICENSE_FILE",
        "VIRBIUS_LICENSE_PUBLIC_KEY",
        "VIRBIUS_APP_ID",
    ):
        monkeypatch.delenv(k, raising=False)
    guard_mod._DEFAULT_GUARD = None

    @v.govern  # bare decorator -> env guard, resolved lazily
    def read_file(path: str) -> str:
        return path

    # No env yet: wrapping already succeeded, the failure surfaces on first call.
    with pytest.raises(ValueError, match="no license"):
        read_file(path="/tmp/a")

    monkeypatch.setenv("VIRBIUS_LICENSE_JWT", JWT)
    monkeypatch.setenv("VIRBIUS_LICENSE_PUBLIC_KEY", PEM)
    monkeypatch.setenv("VIRBIUS_APP_ID", "test-agent")
    try:
        assert read_file(path="/tmp/a") == "/tmp/a"
        assert guard_mod._DEFAULT_GUARD is not None  # built once, then cached
    finally:
        guard_mod._DEFAULT_GUARD = None


# ── typed decision + denial factory ─────────────────────────────────────


def test_precheck_returns_typed_decision():
    import json

    g = make_guard()
    allow = g.precheck("read_file", {"path": "/tmp/a"})
    assert isinstance(allow, v.VirbiusDecision)
    assert allow.allowed is True
    assert allow["allowed"] is True                 # subscript still works
    assert "allowed" in allow
    assert set(allow.to_dict()) == {
        "allowed", "reason", "reason_code", "fast_path", "sandbox_type",
    }
    json.dumps(allow.to_dict())                      # serializable

    deny = g.precheck("db_delete", {"id": 1})
    assert deny.allowed is False
    assert deny["reason_code"] == "not_in_allowlist"
    assert "not in license allowlist" in (deny.reason or "")


def test_decision_from_raw_tolerates_binding_code():
    d = v.VirbiusDecision.from_raw(
        {"allowed": False, "reason": "nope", "code": "not_in_allowlist"}
    )
    assert d.reason_code == "not_in_allowlist"       # raw `code` -> reason_code
    assert d["reason"] == "nope"


def test_denial_from_decision_carries_structured_context():
    decision = v.VirbiusDecision(
        allowed=False, reason="tool 'x' not in license allowlist",
        reason_code="not_in_allowlist",
    )
    exc = v.VirbiusDenied.from_decision(decision, audit_ref="trace-123")
    assert str(exc) == v.policy_blocked_message("tool 'x' not in license allowlist")
    assert exc.reason_code == "not_in_allowlist"
    assert exc.decision is decision
    assert exc.audit_record() == "trace-123"
    assert isinstance(exc, PermissionError)


def test_guard_raises_decision_backed_denial():
    g = make_guard()
    with pytest.raises(v.VirbiusDenied) as ei:
        g.guard("db_delete", {"id": 1})
    assert ei.value.decision is not None
    assert ei.value.decision.allowed is False
    assert ei.value.reason_code == ei.value.decision.reason_code
    assert ei.value.audit_record() is None           # no audit ref wired in-process
