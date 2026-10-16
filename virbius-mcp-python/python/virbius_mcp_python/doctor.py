"""Environment & license diagnostics — ``python -m virbius_mcp_python doctor``.

Answers "why doesn't ``VirbiusGuard.from_env()`` work, and what does it see?" in
one shot, for the in-process (SDK-embed) mode. Mirrors the MCP proxy's ``doctor``.

Programmatic use::

    import virbius_mcp_python as v
    report = v.diagnose()          # -> dict; report["ok"] tells you if it's ready
"""

from __future__ import annotations

import json
import os
import sys
from typing import Optional

# The env vars the SDK-embed mode reads (same names the MCP proxy reads, so one
# rigged environment serves both integration modes).
ENV_VARS = (
    "VIRBIUS_LICENSE_FILE",
    "VIRBIUS_LICENSE_JWT",
    "VIRBIUS_LICENSE_PUBLIC_KEY",
    "VIRBIUS_APP_ID",
    "VIRBIUS_TENANT_ID",
    "VIRBIUS_ENGINE_URL",
)


def diagnose(env: Optional[dict] = None, check_engine: bool = False, timeout: float = 2.0) -> dict:
    """Return a diagnostic report (no side effects beyond an optional TCP probe).

    Never raises for a bad/missing configuration — it reports instead, so it is
    safe to run exactly when things are broken.
    """
    e = os.environ if env is None else env
    present = {k: bool(str(e.get(k, "")).strip()) for k in ENV_VARS}
    report = {
        "mode": "sdk-embed",
        "env": present,
        "license": None,
        "allowed_tools": None,
        "engine": {"url": (e.get("VIRBIUS_ENGINE_URL") or "").strip() or None, "reachable": None},
        "problems": [],
        "ok": False,
    }

    if not (present["VIRBIUS_LICENSE_FILE"] or present["VIRBIUS_LICENSE_JWT"]):
        report["problems"].append("no license: set VIRBIUS_LICENSE_FILE or VIRBIUS_LICENSE_JWT")
    if not present["VIRBIUS_LICENSE_PUBLIC_KEY"]:
        report["problems"].append("no public key: set VIRBIUS_LICENSE_PUBLIC_KEY")
    if not present["VIRBIUS_APP_ID"]:
        report["problems"].append("no app id: set VIRBIUS_APP_ID")

    try:
        from .guard import VirbiusGuard

        guard = VirbiusGuard.from_env(dict(e))
    except Exception as exc:  # noqa: BLE001 — a doctor reports, it does not crash
        report["license"] = {"ok": False, "error": f"{type(exc).__name__}: {exc}"}
        if not report["problems"]:
            report["problems"].append(f"license rejected: {exc}")
        return report

    tools = list(getattr(guard, "claims", {}).get("allowed_tools", []))
    report["license"] = {
        "ok": True,
        "app_id": guard.app_id,
        "tenant_id": getattr(guard, "tenant_id", ""),
    }
    report["allowed_tools"] = tools
    report["ok"] = True

    if check_engine and report["engine"]["url"]:
        report["engine"]["reachable"] = _probe(report["engine"]["url"], timeout)
    return report


def _probe(url: str, timeout: float) -> Optional[bool]:
    import socket
    from urllib.parse import urlparse

    parsed = urlparse(url if "://" in url else f"http://{url}")
    host = parsed.hostname
    if not host:
        return None
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _mark(ok: bool) -> str:
    return "OK" if ok else "  "


def render(report: dict) -> str:
    """Human-readable rendering of a :func:`diagnose` report."""
    lines = [f"virbius doctor — mode={report['mode']}", "", "env:"]
    lines += [f"  [{_mark(ok)}] {name}" for name, ok in report["env"].items()]

    lic = report["license"]
    if lic and lic.get("ok"):
        lines.append(f"license: OK (app_id={lic['app_id']}, tenant_id={lic.get('tenant_id') or '-'})")
        tools = report["allowed_tools"] or []
        lines.append(f"allowed tools ({len(tools)}): {', '.join(tools) or '(none)'}")
    elif lic:
        lines.append(f"license: FAILED — {lic['error']}")

    eng = report["engine"]
    if eng.get("url"):
        reach = "" if eng.get("reachable") is None else f" (reachable={eng['reachable']})"
        lines.append(f"engine: {eng['url']}{reach}")

    lines.append("")
    if report["ok"]:
        lines.append("RESULT: OK — VirbiusGuard.from_env() is ready.")
    else:
        lines.append("RESULT: NOT READY")
        lines += [f"  - {p}" for p in report["problems"]]
    return "\n".join(lines)


def main(argv: Optional[list] = None) -> int:
    import argparse

    ap = argparse.ArgumentParser(
        prog="virbius-mcp-python doctor",
        description="Check the SDK-embed environment/license (in-process mode).",
    )
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--engine", action="store_true", help="also TCP-probe VIRBIUS_ENGINE_URL")
    ap.add_argument("--timeout", type=float, default=2.0, help="engine probe timeout (s)")
    args = ap.parse_args(argv)

    report = diagnose(check_engine=args.engine, timeout=args.timeout)
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print(render(report))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
