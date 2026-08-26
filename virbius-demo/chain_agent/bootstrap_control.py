# -*- coding: utf-8 -*-
"""幂等写入 Control：4 工具、delete_file Groovy deny full、License 追加。

失败只记 status。禁止 rotate-key。JSON 一律 snake_case。无 listMatch。
"""
import base64
import json
import logging
import re
import urllib.error
import urllib.request

from chain_agent import ALL_TOOLS, HIGH_RISK_TOOLS, SAFE_TOOLS
from modules import settings

log = logging.getLogger("chain.bootstrap")

RULE_ID = "cloud_chain_protected_wipe"
TOOL_NAME_RE = re.compile(r"^[a-z][a-z0-9_-]*$")
GROOVY = (
    "def decide(ctx) {\n"
    "    def cur = (ctx.var('args.path') ?: '').toString().trim().replace('\\\\', '/')\n"
    "    if (!cur) return false\n"
    "    def hist = ctx.sessionHistory(10) ?: []\n"
    "    for (h in hist) {\n"
    "        if ((h.tool_name ?: '').toString() != 'move_file') continue\n"
    "        def raw = (h.args ?: '').toString()\n"
    "        def sm = (raw =~ /\"src\"\\s*:\\s*\"([^\"]*)\"/)\n"
    "        def dm = (raw =~ /\"dest\"\\s*:\\s*\"([^\"]*)\"/)\n"
    "        def src = sm.find() ? sm.group(1).replace('\\\\', '/').trim() : ''\n"
    "        def dest = dm.find() ? dm.group(1).replace('\\\\', '/').trim() : ''\n"
    "        if (dest && dest == cur && src.startsWith('cabinet/')) return true\n"
    "    }\n"
    "    return false\n"
    "}\n"
)

FALLBACK_ALLOWED = [
    "GetCurrentUser", "GetUserTransactions", "GetBankNotice", "SendEmail",
    "ListMyTrips", "SubmitExpense", "ApproveExpense", "PayoutToAccount",
    "get_calendar", "save_memory", "search_memory", "send_email",
    "list_incidents", "query_customers", "get_service_health",
    "drop_production_table", "execute_production_sql", "send_vendor_webhook",
    "list_briefs", "read_brief", "http_get", "send_mail",
]

_STATUS = {"ok": False, "error": "not started", "license_appended": False}


def status() -> dict:
    return dict(_STATUS)


def tool_upsert_bodies() -> list:
    bodies = []
    for name in SAFE_TOOLS:
        bodies.append({
            "tool_name": name,
            "risk_class": "low",
            "sandbox_type": "none",
            "timeout_ms": 5000,
            "fast_path": True,
            "description": "file cabinet read or compress: " + name,
            "approval_mode": "lax",
        })
    for name in HIGH_RISK_TOOLS:
        bodies.append({
            "tool_name": name,
            "risk_class": "high",
            "sandbox_type": "none",
            "timeout_ms": 8000,
            "fast_path": False,
            "description": "file cabinet mutate: " + name,
            "approval_mode": "strict",
        })
    return bodies


def rule_upsert_body() -> dict:
    return {
        "rule_id": RULE_ID,
        "bundle_id": "poc-default",
        "layer": "cloud",
        "runtime": "groovy",
        "reason_code": "CHAIN_MOVED_ORIGINAL_DELETE",
        "risk_score": 80,
        "intent_action": "deny",
        "scope": {
            "bind_scope": "tool",
            "bind_ref": {
                "tool_names": ["delete_file"],
                "app_ids": ["demo-app"],
            },
        },
        "body": GROOVY,
    }


def license_issue_body(existing_tools: list, risk_quota: int = 100) -> dict:
    merged = []
    seen = set()
    for name in list(existing_tools or []) + list(ALL_TOOLS):
        if not name or name in seen:
            continue
        seen.add(name)
        merged.append(name)
    return {
        "app_id": "demo-app",
        "agent_name": "demo-agent",
        "allowed_tools": merged,
        "risk_quota": int(risk_quota) if int(risk_quota) > 0 else 100,
    }


def decode_jwt_claims(jwt: str) -> dict:
    token = (jwt or "").strip()
    parts = token.split(".")
    if len(parts) < 2:
        return {}
    pad = "=" * ((4 - len(parts[1]) % 4) % 4)
    try:
        raw = base64.urlsafe_b64decode(parts[1] + pad)
        data = json.loads(raw.decode("utf-8"))
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _control_base() -> str:
    return (settings.get("VIRBIUS_CONTROL_URL") or "http://localhost:8080").rstrip("/")


def control_ui_url() -> str:
    return _control_base() + "/ui"


def _http(method: str, path: str, body=None, timeout=12):
    url = _control_base() + path
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(raw) if raw else {}
        except ValueError:
            parsed = {"message": raw[:300]}
        return exc.code, parsed
    except Exception as exc:  # noqa: BLE001
        raise RuntimeError("%s %s failed: %s" % (method, path, exc)) from exc


def _unwrap(payload):
    if isinstance(payload, dict) and "data" in payload:
        return payload.get("data")
    return payload


def _patch_rollout(state: str, canary_percent=None) -> None:
    body = {
        "rollout_state": state,
        "force": True,
        "comment": "chain demo bootstrap",
    }
    if canary_percent is not None:
        body["canary_percent"] = canary_percent
    code, payload = _http(
        "PATCH",
        "/api/v1/admin/tenants/default/rules/%s/rollout" % RULE_ID,
        body,
    )
    if code >= 400:
        raise RuntimeError("rollout %s HTTP %s %s" % (state, code, payload))


def _promote_rule_to_full(current: str) -> None:
    state = (current or "draft").lower()
    if state in ("", "draft", "none"):
        pub_code, pub = _http(
            "POST",
            "/api/v1/admin/tenants/default/rules/%s/rollout/publish" % RULE_ID,
        )
        if pub_code >= 400:
            raise RuntimeError("rule publish HTTP %s %s" % (pub_code, pub))
        state = "dry_run"
    if state == "dry_run":
        _patch_rollout("canary", canary_percent=100)
        state = "canary"
    if state == "canary":
        _patch_rollout("full")
        state = "full"
    if state != "full":
        raise RuntimeError("could not promote rule, still " + state)
    log.info("chain rule promoted to full")


def _revoke_active_demo_license() -> None:
    code, payload = _http("GET", "/api/v1/admin/tenants/default/licenses/list?status=active")
    if code >= 400:
        raise RuntimeError("license list HTTP %s %s" % (code, payload))
    rows = _unwrap(payload) or []
    if not isinstance(rows, list):
        rows = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        app = row.get("app_id") or row.get("appId")
        lid = row.get("license_id") or row.get("licenseId")
        if app != "demo-app" or not lid:
            continue
        rcode, rpay = _http(
            "POST",
            "/api/v1/admin/tenants/default/licenses/%s/revoke" % lid,
            {"reason": "chain_bootstrap_append_tools"},
        )
        if rcode >= 400:
            raise RuntimeError("license revoke HTTP %s %s" % (rcode, rpay))
        log.info("revoked previous demo-app license so a new JWT can append chain tools")
        return
    raise RuntimeError("license issue 409 but no active demo-app license to revoke")


def run() -> dict:
    """Idempotent. Never rotate-key. Never log JWT."""
    global _STATUS
    try:
        for body in tool_upsert_bodies():
            if not TOOL_NAME_RE.match(body["tool_name"]):
                raise RuntimeError("illegal tool_name: " + body["tool_name"])
            code, payload = _http("POST", "/api/v1/admin/tenants/default/tools", body)
            if code >= 400:
                raise RuntimeError("tool upsert %s: HTTP %s %s" % (body["tool_name"], code, payload))

        rule = rule_upsert_body()
        existing_code, existing = _http(
            "GET", "/api/v1/admin/tenants/default/rules/" + RULE_ID,
        )
        existing_data = _unwrap(existing) if existing_code < 400 else None
        rollout = ""
        if isinstance(existing_data, dict):
            rollout = str(existing_data.get("rollout_state") or "")

        if existing_code >= 400:
            code, payload = _http("POST", "/api/v1/admin/tenants/default/rules", rule)
            if code >= 400 and code != 409:
                raise RuntimeError("rule upsert HTTP %s %s" % (code, payload))
            rollout = "draft"

        if rollout != "full":
            _promote_rule_to_full(rollout)
        else:
            log.info("chain rule already full, skip rollout")

        jwt = settings.get("VIRBIUS_LICENSE_JWT")
        claims = decode_jwt_claims(jwt)
        current = list(claims.get("allowed_tools") or [])
        if not current:
            current = list(FALLBACK_ALLOWED)
        missing = [n for n in ALL_TOOLS if n not in current]
        appended = False
        if missing:
            quota = claims.get("risk_quota") or 100
            body = license_issue_body(current, risk_quota=quota)
            if "appId" in body:
                raise RuntimeError("license body must be snake_case")
            code, payload = _http(
                "POST", "/api/v1/admin/tenants/default/licenses/issue", body,
            )
            if code == 409:
                _revoke_active_demo_license()
                code, payload = _http(
                    "POST", "/api/v1/admin/tenants/default/licenses/issue", body,
                )
            if code >= 400:
                raise RuntimeError("license issue HTTP %s %s" % (code, payload))
            data = _unwrap(payload) or {}
            new_jwt = (data.get("jwt") or data.get("license_jwt") or "").strip()
            if not new_jwt:
                raise RuntimeError("license issue returned empty jwt")
            settings.save({"VIRBIUS_LICENSE_JWT": new_jwt})
            from dvla_agent import mcpproxy_client
            mcpproxy_client.drop_all_proxy_clients()
            appended = True
            log.info("chain license appended %d tools (quota=%s)", len(missing), quota)
        else:
            log.info("chain license already contains 4 tool names, skip issue")

        _STATUS = {"ok": True, "error": "", "license_appended": appended}
    except Exception as exc:  # noqa: BLE001
        log.warning("chain Control bootstrap failed: %s", exc)
        _STATUS = {"ok": False, "error": str(exc), "license_appended": False}
    return status()
