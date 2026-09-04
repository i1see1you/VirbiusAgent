# -*- coding: utf-8 -*-
"""按关卡拉起 virbius-mcp-proxy：各自 JWT、租户、公钥、SSE 端口。"""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import subprocess
import uuid
import urllib.parse
import urllib.request

from mcp_runtime.labs import Lab, get as get_lab
from modules import settings

_PROXY_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
log = logging.getLogger(__name__)
BIN_HINT = os.environ.get("VIRBIUS_MCP_PROXY_BIN", "").strip()

_clients: dict = {}
_meta: dict = {}


def _pem_fp(path: str) -> str:
    if not path or not os.path.isfile(path):
        return ""
    try:
        with open(path, "rb") as f:
            return hashlib.sha256(f.read()).hexdigest()[:16]
    except OSError:
        return ""


def _lab_meta(lab: Lab) -> dict:
    rec = settings.get_license(lab.id)
    slot = _meta.get(lab.id)
    sid = (slot or {}).get("session_id") or (lab.session_prefix + "init")
    pem = rec.get("pem_path") or ""
    return {
        "license_jwt": rec.get("jwt") or "",
        "tenant_id": lab.tenant_id,
        "app_id": lab.app_id,
        "user_id": lab.user_id,
        "session_id": sid,
        "pem_fp": _pem_fp(pem),
    }


def drop_lab(lab_id: str) -> None:
    client = _clients.pop(lab_id, None)
    if client is None:
        return
    try:
        client.close()
    except Exception:
        pass


def drop_all_proxy_clients() -> None:
    for lab_id in list(_clients.keys()):
        drop_lab(lab_id)


def _respawn_if_license_changed(lab_id: str, meta: dict):
    client = _clients.get(lab_id)
    if client is None:
        return
    old = (client.meta or {}).get("license_jwt") or ""
    new = (meta.get("license_jwt") or "").strip()
    old_pem = (client.meta or {}).get("pem_fp") or ""
    new_pem = (meta.get("pem_fp") or "")
    if old == new and old_pem == new_pem:
        return
    drop_lab(lab_id)


def bind_session(lab_id: str, session_id: str) -> str:
    lab = get_lab(lab_id)
    sid = (session_id or "").strip()
    if not sid:
        sid = lab.session_prefix + uuid.uuid4().hex[:12]
    slot = _meta.setdefault(lab.id, {})
    slot["session_id"] = sid
    client = _clients.get(lab.id)
    if client is not None and (client.meta or {}).get("session_id") != sid:
        drop_lab(lab.id)
    return sid


def new_session(lab_id: str) -> str:
    lab = get_lab(lab_id)
    return bind_session(lab_id, lab.session_prefix + uuid.uuid4().hex[:12])


def _file_magic(path: str) -> bytes:
    try:
        with open(path, "rb") as f:
            return f.read(4)
    except OSError:
        return b""


def _is_pe(path: str) -> bool:
    return _file_magic(path)[:2] == b"MZ"


def _is_elf(path: str) -> bool:
    return _file_magic(path) == b"\x7fELF"


def _to_wsl_path(path: str) -> str:
    abs_path = os.path.abspath(path)
    drive, rest = os.path.splitdrive(abs_path)
    return "/mnt/" + drive[0].lower() + rest.replace("\\", "/")


def _resolve_bin() -> str:
    if BIN_HINT:
        return BIN_HINT
    roots = [
        os.path.normpath(os.path.join(_PROXY_DIR, "..")),
        os.path.normpath(os.path.join(_PROXY_DIR, "..", "virbius-mcp-proxy")),
    ]
    triples = ("", "x86_64-pc-windows-msvc", "x86_64-pc-windows-gnu", "x86_64-pc-windows-gnullvm")
    candidates = []
    for root in roots:
        for triple in triples:
            target = os.path.join(root, "target", triple, "debug") if triple else os.path.join(root, "target", "debug")
            base = os.path.normpath(os.path.join(target, "virbius-mcp-proxy"))
            candidates.extend((base + ".exe", base))
    if os.name == "nt":
        for cand in candidates:
            if os.path.isfile(cand) and _is_pe(cand):
                return cand
        for cand in candidates:
            if os.path.isfile(cand) and _is_elf(cand):
                return cand
    else:
        for cand in candidates:
            if os.path.isfile(cand):
                return cand
    return os.path.normpath(os.path.join(roots[0], "target", "debug", "virbius-mcp-proxy"))


_WSL_HOST_CACHE = None


def _wsl_windows_host() -> str:
    global _WSL_HOST_CACHE
    env = (os.environ.get("VIRBIUS_WSL_WINDOWS_HOST") or "").strip()
    if env:
        return env
    if _WSL_HOST_CACHE:
        return _WSL_HOST_CACHE
    host = _windows_vethernet_wsl_ip() or _wsl_default_gateway()
    if not host:
        raise RuntimeError("cannot resolve Windows host IP for WSL mcp-proxy")
    _WSL_HOST_CACHE = host
    return host


def _windows_vethernet_wsl_ip() -> str:
    try:
        raw = subprocess.check_output(["ipconfig"], timeout=8)
    except Exception as exc:
        log.warning("ipconfig failed: %s", exc)
        return ""
    text = raw.decode("gbk", "replace") if os.name == "nt" else raw.decode("utf-8", "replace")
    idx = text.find("WSL")
    if idx < 0:
        return ""
    for match in re.finditer(r"(\d+\.\d+\.\d+\.\d+)", text[idx:idx + 800]):
        ip = match.group(1)
        if not ip.startswith("255.") and not ip.startswith("0."):
            return ip
    return ""


def _wsl_default_gateway() -> str:
    try:
        out = subprocess.check_output(
            ["wsl", "-e", "sh", "-c", "ip route show default | awk '{print $3}'"],
            text=True,
            timeout=20,
        )
    except Exception as exc:
        log.warning("wsl default gateway lookup failed: %s", exc)
        return ""
    return (out or "").strip().split()[0] if (out or "").strip() else ""


def _proxy_command(bin_path: str, lab: Lab) -> list[str]:
    rec = settings.get_license(lab.id)
    pem = rec.get("pem_path") or ""
    if os.name == "nt" and os.path.isfile(bin_path) and _is_elf(bin_path):
        host = _wsl_windows_host()
        engine = (settings.get("VIRBIUS_ENGINE_URL") or "").rstrip("/") or (
            "http://%s:8082" % host
        )
        control = (settings.get("VIRBIUS_CONTROL_URL") or "").rstrip("/")
        parts = [
            "wsl", "--cd", _PROXY_DIR, "-e", "env",
            "VIRBIUS_UPSTREAM_URL=http://%s:%d" % (host, lab.port),
            "VIRBIUS_ENGINE_URL=" + engine,
            "VIRBIUS_EGRESS_HOSTS=wiki.internal,mail.internal",
        ]
        if control:
            parts.append("VIRBIUS_CONTROL_URL=" + control)
        if pem:
            parts.append("VIRBIUS_LICENSE_PUBLIC_KEY=" + _to_wsl_path(pem))
        parts.append(_to_wsl_path(bin_path))
        log.info("mcp-proxy via WSL lab=%s host=%s port=%s", lab.id, host, lab.port)
        return parts
    return [bin_path]


class McpProxyClient:
    def __init__(self, bin_path: str, lab: Lab, meta: dict):
        self.lab = lab
        self.meta = meta
        env = os.environ.copy()
        env.setdefault("VIRBIUS_EGRESS_HOSTS", "wiki.internal,mail.internal")
        env["VIRBIUS_UPSTREAM_URL"] = "http://127.0.0.1:%d" % lab.port
        rec = settings.get_license(lab.id)
        if rec.get("pem_path"):
            env["VIRBIUS_LICENSE_PUBLIC_KEY"] = rec["pem_path"]
        engine = settings.get("VIRBIUS_ENGINE_URL")
        if engine:
            env["VIRBIUS_ENGINE_URL"] = engine
        self.proc = subprocess.Popen(
            _proxy_command(bin_path, lab),
            cwd=_PROXY_DIR,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
            env=env,
        )
        self._counter = 0
        self._initialize(meta)

    def _next_id(self) -> int:
        self._counter += 1
        return self._counter

    def _send(self, method: str, params: dict) -> dict:
        req = {
            "jsonrpc": "2.0",
            "id": self._next_id(),
            "method": method,
            "params": params,
        }
        self.proc.stdin.write(json.dumps(req) + "\n")
        self.proc.stdin.flush()
        while True:
            line = self.proc.stdout.readline()
            if not line:
                stderr = (self.proc.stderr.read() or "").strip() or "no output"
                raise RuntimeError("mcp-proxy exited: " + stderr)
            s = line.strip()
            if s.startswith("{") and s.endswith("}"):
                try:
                    return json.loads(s)
                except ValueError as exc:
                    raise RuntimeError("mcp-proxy invalid response: " + s) from exc

    def _initialize(self, meta: dict) -> None:
        params = {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "virbius-demo-" + self.lab.id, "version": "0.1.0"},
            "_meta": meta,
        }
        resp = self._send("initialize", params)
        if "error" in resp:
            raise RuntimeError("mcp-proxy initialize failed: " + str(resp["error"]))

    def call_tool_raw(self, tool_name: str, args: dict, extra_meta: dict | None = None) -> dict:
        params = {"name": tool_name, "arguments": args or {}}
        if extra_meta:
            params["_meta"] = extra_meta
        return self._send("tools/call", params)

    def call_tool(self, tool_name: str, args: dict) -> str:
        resp = self._send("tools/call", {"name": tool_name, "arguments": args})
        if "error" in resp:
            return _format_block(
                self.lab, tool_name, args, resp["error"],
                session_id=(self.meta or {}).get("session_id"),
            )
        content = resp.get("result", {}).get("content", [])
        if content:
            return content[0].get("text", "")
        return str(resp.get("result", ""))

    def close(self) -> None:
        try:
            self.proc.stdin.close()
        finally:
            self.proc.terminate()


def get_client(lab_id: str) -> McpProxyClient:
    lab = get_lab(lab_id)
    meta = _lab_meta(lab)
    _respawn_if_license_changed(lab.id, meta)
    sid = meta.get("session_id") or ""
    if not sid or sid.endswith("init"):
        new_session(lab.id)
        meta = _lab_meta(lab)
    client = _clients.get(lab.id)
    if client is None or client.proc.poll() is not None:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass
        client = McpProxyClient(_resolve_bin(), lab, dict(meta))
        _clients[lab.id] = client
    return client


def call_tool(lab_id: str, tool_name: str, args: dict) -> str:
    return get_client(lab_id).call_tool(tool_name, args)


def parse_challenge(err: dict) -> dict | None:
    if not isinstance(err, dict):
        return None
    code = err.get("code")
    msg = str(err.get("message") or "")
    if code != -32011 and "challenge_required" not in msg:
        return None
    data = err.get("data") or {}
    return {
        "challenge_id": data.get("challenge_id"),
        "tool_name": data.get("tool_name"),
        "args_hash": data.get("args_hash"),
        "rule_id": data.get("rule_id"),
        "reason": data.get("reason"),
    }


def call_tool_ops(tool_name: str, args: dict, challenge_token: str | None = None) -> dict:
    extra = {"challenge_token": challenge_token} if challenge_token else None
    client = get_client("ops")
    resp = client.call_tool_raw(tool_name, args, extra_meta=extra)
    if "error" in resp:
        ch = parse_challenge(resp["error"])
        if ch:
            return {"ok": False, "challenge": ch, "error": resp["error"]}
        return {
            "ok": False,
            "blocked": _format_block(
                client.lab, tool_name, args, resp["error"],
                session_id=(client.meta or {}).get("session_id"),
            ),
        }
    content = resp.get("result", {}).get("content", [])
    if content:
        return {"ok": True, "text": content[0].get("text", "")}
    return {"ok": True, "text": str(resp.get("result", ""))}


def get_challenge_status(challenge_id: str) -> dict:
    cid = (challenge_id or "").strip()
    if not cid:
        return {"status": "not_found"}
    engine = (settings.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
    if not engine:
        return {"status": "not_found"}
    url = engine + "/v1/challenge/" + urllib.parse.quote(cid, safe="") + "/status"
    req = urllib.request.Request(url, method="GET", headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            data = json.load(resp)
            return data if isinstance(data, dict) else {"status": "not_found"}
    except Exception as exc:  # noqa: BLE001
        log.warning("challenge status failed: %s", exc)
        return {"status": "not_found"}


def _format_block(lab: Lab, tool_name: str, args: dict, err: dict, session_id=None) -> str:
    msg = err.get("message") or "tool blocked"
    data = err.get("data") or {}
    parts = [str(msg)]
    reason = data.get("reason")
    if reason and reason != msg:
        parts.append(str(reason))
    rule = data.get("rule_id")
    peek = None if rule else _peek_evaluate(lab, tool_name, args, session_id=session_id)
    if peek:
        rule = rule or peek.get("rule_id")
        rc = peek.get("reason_code")
        if rc and str(rc) not in parts:
            parts.append(str(rc))
    if rule:
        parts.append("rule=" + str(rule))
    risk = data.get("session_risk_score")
    if risk is not None:
        parts.append("risk=" + str(risk))
    return "[blocked] " + " | ".join(parts)


def _peek_evaluate(lab: Lab, tool_name: str, args: dict, session_id=None):
    engine = (settings.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
    if not engine:
        return None
    payload = {
        "tenant_id": lab.tenant_id,
        "session_id": session_id or (lab.session_prefix + "peek"),
        "user_id": lab.user_id,
        "tool_name": tool_name,
        "role": "tool_call",
        "args_json": json.dumps(args or {}),
        "vars": {"app_id": lab.app_id},
    }
    req = urllib.request.Request(
        engine + "/v1/evaluate",
        data=json.dumps(payload).encode(),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=8) as resp:
            return json.load(resp)
    except Exception as exc:
        log.warning("peek evaluate failed: %s", exc)
        return None
