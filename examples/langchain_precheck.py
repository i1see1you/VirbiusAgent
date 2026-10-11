"""Example: wrap LangChain tools with virbius-core pre-check (Mode A / SDK-embed).

Run:  python examples/langchain_precheck.py
Requires a built `virbius_mcp_python` extension and license fixtures
(see virbius-mcp-python/README.md and scripts/mint_test_license.py).
"""

import json
import pathlib

import virbius_mcp_python as v

APP_ID = "test-agent"
FIXTURES = pathlib.Path(__file__).resolve().parents[1] / "virbius-mcp-python" / "tests" / "fixtures"

JWT = (FIXTURES / "license.jwt").read_text().strip()
PEM = (FIXTURES / "license_pub.pem").read_text()


def configure():
    v.configure_rules(json.dumps({
        "offline_manifest_path": str(FIXTURES / "edge-manifest.json"),
        "cache_dir": "./.virbius-edge-cache",
        "tenant_id": "tenant-1",
        "app_id": APP_ID,
    }))


def guarded_call(tool_name, args, fn):
    """Pre-check then execute. Raises PermissionError if the call is denied."""
    result = v.precheck_tool(tool_name, json.dumps(args), JWT, PEM, APP_ID)
    if not result["allowed"]:
        raise PermissionError(f"tool '{tool_name}' blocked: {result['reason']}")
    return fn(**args)


# --- Optional: expose as a LangChain tool wrapper -----------------------------
# pip install langchain-core
def as_langchain_tool(inner_tool):
    from langchain_core.tools import StructuredTool

    def _runner(**kwargs):
        return guarded_call(inner_tool.name, kwargs, inner_tool.func)

    return StructuredTool.from_function(
        func=_runner,
        name=inner_tool.name,
        description=inner_tool.description,
    )


if __name__ == "__main__":
    configure()
    # Allowed by license allowlist + args schema.
    print("read_file ->", guarded_call("read_file", {"path": "/tmp/a"}, lambda path: f"read {path}"))
    # Denied: not in license allowlist.
    try:
        guarded_call("db_delete", {"id": 1}, lambda id: f"deleted {id}")
    except PermissionError as e:
        print("db_delete -> blocked:", e)
