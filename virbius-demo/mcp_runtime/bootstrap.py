# -*- coding: utf-8 -*-
"""按关卡写入 Control：建租户、工具、规则 publish full、本关 License + 公钥。

禁止 rotate-key。JSON 一律 snake_case。失败抛给调用方记 status。
"""
from __future__ import annotations

import json
import logging
import os
import re
import urllib.error
import urllib.request

from mcp_runtime.labs import get as get_lab
from modules import settings

log = logging.getLogger("mcp_runtime.bootstrap")
TOOL_NAME_RE = re.compile(r"^[a-z][a-z0-9_-]*$")


def decode_jwt_claims(jwt: str) -> dict:
    import base64
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


def control_base() -> str:
    return (settings.get("VIRBIUS_CONTROL_URL") or "http://localhost:8080").rstrip("/")


def control_ui_url() -> str:
    return control_base() + "/ui"


def http(method: str, path: str, body=None, timeout=12):
    url = control_base() + path
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


def unwrap(payload):
    if isinstance(payload, dict) and "data" in payload:
        return payload.get("data")
    return payload


def _create_tenant(lab) -> None:
    code, payload = http(
        "POST", "/api/v1/admin/tenants",
        {"tenant_id": lab.tenant_id, "name": lab.tenant_name},
    )
    if code == 409:
        log.info("tenant %s already exists", lab.tenant_id)
        return
    if code >= 400:
        raise RuntimeError("create tenant HTTP %s %s" % (code, payload))
    log.info("created tenant %s", lab.tenant_id)


def _patch_rollout(lab, rule_id: str, state: str, canary_percent=None) -> None:
    body = {
        "rollout_state": state,
        "force": True,
        "comment": "demo bootstrap " + lab.id,
    }
    if canary_percent is not None:
        body["canary_percent"] = canary_percent
    code, payload = http(
        "PATCH",
        "/api/v1/admin/tenants/%s/rules/%s/rollout" % (lab.tenant_id, rule_id),
        body,
    )
    if code >= 400:
        raise RuntimeError("rollout %s HTTP %s %s" % (state, code, payload))


def _promote_rule_to_full(lab, rule_id: str, current: str) -> None:
    state = (current or "draft").lower()
    if state in ("", "draft", "none"):
        pub_code, pub = http(
            "POST",
            "/api/v1/admin/tenants/%s/rules/%s/rollout/publish" % (lab.tenant_id, rule_id),
        )
        if pub_code >= 400:
            raise RuntimeError("rule publish HTTP %s %s" % (pub_code, pub))
        state = "dry_run"
    if state == "dry_run":
        _patch_rollout(lab, rule_id, "canary", canary_percent=100)
        state = "canary"
    if state == "canary":
        _patch_rollout(lab, rule_id, "full")
        state = "full"
    if state != "full":
        raise RuntimeError("could not promote rule %s, still %s" % (rule_id, state))
    log.info("rule %s tenant=%s promoted to full", rule_id, lab.tenant_id)


def _revoke_active_app_license(lab) -> None:
    code, payload = http(
        "GET",
        "/api/v1/admin/tenants/%s/licenses/list?status=active" % lab.tenant_id,
    )
    if code >= 400:
        raise RuntimeError("license list HTTP %s %s" % (code, payload))
    rows = unwrap(payload) or []
    if not isinstance(rows, list):
        rows = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        app = row.get("app_id") or row.get("appId")
        lid = row.get("license_id") or row.get("licenseId")
        if app != lab.app_id or not lid:
            continue
        rcode, rpay = http(
            "POST",
            "/api/v1/admin/tenants/%s/licenses/%s/revoke" % (lab.tenant_id, lid),
            {"reason": lab.id + "_bootstrap_reissue"},
        )
        if rcode >= 400:
            raise RuntimeError("license revoke HTTP %s %s" % (rcode, rpay))
        log.info("revoked %s license %s so a new JWT can be issued", lab.app_id, lid)
        return
    raise RuntimeError("license issue 409 but no active %s license to revoke" % lab.app_id)


def write_pem(tenant_id: str, pem_text: str) -> str:
    folder = os.path.join(settings.config_dir(), "licenses")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, tenant_id + ".pem")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write((pem_text or "").strip() + "\n")
    return path


def fetch_and_store_pem(lab) -> str:
    code, payload = http(
        "GET",
        "/api/v1/admin/tenants/%s/licenses/public-key" % lab.tenant_id,
    )
    if code >= 400:
        raise RuntimeError("public-key HTTP %s %s" % (code, payload))
    data = unwrap(payload) or {}
    pem = (data.get("public_key_pem") or data.get("publicKeyPem") or "").strip()
    if not pem:
        raise RuntimeError("public-key empty for tenant " + lab.tenant_id)
    return write_pem(lab.tenant_id, pem)


def _issue_license(lab, allowed_tools: list) -> bool:
    rec = settings.get_license(lab.id)
    jwt = rec.get("jwt") or ""
    claims = decode_jwt_claims(jwt)
    current = list(claims.get("allowed_tools") or [])
    tenant_ok = (claims.get("tenant_id") or "") == lab.tenant_id
    app_ok = (claims.get("app_id") or "") == lab.app_id
    have_all = all(n in current for n in allowed_tools)
    if jwt and tenant_ok and app_ok and have_all:
        pem_path = rec.get("pem_path") or fetch_and_store_pem(lab)
        if pem_path != rec.get("pem_path"):
            settings.save_license(lab.id, jwt, pem_path)
        log.info("lab %s license already has %d tools, skip issue", lab.id, len(allowed_tools))
        return False
    body = {
        "app_id": lab.app_id,
        "agent_name": lab.agent_name,
        "allowed_tools": list(allowed_tools),
        "risk_quota": 100,
    }
    if "appId" in body:
        raise RuntimeError("license body must be snake_case")
    path = "/api/v1/admin/tenants/%s/licenses/issue" % lab.tenant_id
    code, payload = http("POST", path, body)
    if code == 409:
        _revoke_active_app_license(lab)
        code, payload = http("POST", path, body)
    if code >= 400:
        raise RuntimeError("license issue HTTP %s %s" % (code, payload))
    data = unwrap(payload) or {}
    new_jwt = (data.get("jwt") or data.get("license_jwt") or "").strip()
    if not new_jwt:
        raise RuntimeError("license issue returned empty jwt")
    pem_path = fetch_and_store_pem(lab)
    settings.save_license(lab.id, new_jwt, pem_path)
    from mcp_runtime import proxy_client
    proxy_client.drop_lab(lab.id)
    log.info("lab %s license issued tools=%d", lab.id, len(allowed_tools))
    return True


def run_bootstrap(lab_id: str, *, tools=None, rules=None, allowed_tools=None, extra=None) -> dict:
    """幂等。返回 {ok, error, license_appended}。不 rotate-key。"""
    lab = get_lab(lab_id)
    _create_tenant(lab)
    for body in tools or []:
        name = body.get("tool_name") or ""
        if not TOOL_NAME_RE.match(name):
            log.info("skip tool upsert (name regex) %s", name)
            continue
        code, payload = http(
            "POST", "/api/v1/admin/tenants/%s/tools" % lab.tenant_id, body,
        )
        if code >= 400:
            raise RuntimeError("tool upsert %s: HTTP %s %s" % (name, code, payload))
    if extra:
        extra(http, lab)
    for rule in rules or []:
        rule_id = rule.get("rule_id")
        if not rule_id:
            raise RuntimeError("rule missing rule_id")
        existing_code, existing = http(
            "GET", "/api/v1/admin/tenants/%s/rules/%s" % (lab.tenant_id, rule_id),
        )
        existing_data = unwrap(existing) if existing_code < 400 else None
        rollout = ""
        if isinstance(existing_data, dict):
            rollout = str(existing_data.get("rollout_state") or "")
        if existing_code >= 400:
            code, payload = http(
                "POST", "/api/v1/admin/tenants/%s/rules" % lab.tenant_id, rule,
            )
            if code >= 400 and code != 409:
                raise RuntimeError("rule upsert HTTP %s %s" % (code, payload))
            rollout = "draft"
        if rollout != "full":
            _promote_rule_to_full(lab, rule_id, rollout)
        else:
            log.info("rule %s already full", rule_id)
    appended = _issue_license(lab, list(allowed_tools or []))
    return {"ok": True, "error": "", "license_appended": appended}


_LAB_MODULES = {
    "bank": "dvla_agent.bootstrap_control",
    "llm10": "owasp_agent.llm10.bootstrap_control",
    "llm06": "owasp_agent.llm06.bootstrap_control",
    "memory": "memory_agent.bootstrap_control",
    "ops": "ops_agent.bootstrap_control",
    "egress": "egress_agent.bootstrap_control",
    "chain": "chain_agent.bootstrap_control",
}


def run_lab(lab_id: str) -> dict:
    import importlib
    mod_name = _LAB_MODULES.get(lab_id)
    if not mod_name:
        raise KeyError("unknown lab: " + str(lab_id))
    return importlib.import_module(mod_name).run()
