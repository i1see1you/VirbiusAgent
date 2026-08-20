# -*- coding: utf-8 -*-
"""Rust virbius-mcp-proxy 的 demo 侧 stdio 客户端。

复用 Rust 版 mcp-proxy（非 Python 重复实现）：demo 的 LangChain 工具通过本客户端
拉起 Rust 二进制，用 stdio 先发 `initialize`（带 `_meta`：license/tenant/app_id/user_id）
建立会话，再用 `tools/call` 触发云端 engine（/v1/evaluate）越权评估与工具执行。

- engine 云端规则拒绝时，Rust proxy 返回 JSON-RPC error，本客户端原样回传为拦截文本。
- 未配置 license 时走 Rust proxy 的 minimum_privilege 兜底（fail-open），demo 仍可用。
"""
import json
import logging
import os
import re
import subprocess
import uuid
import urllib.request

from modules import settings

_PROXY_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # virbius-demo
log = logging.getLogger(__name__)

# ---- 从 运行期设置/环境 读取 demo 侧需要回填的配置（占位符见 .env.example）----
META = {
    "license_jwt": settings.get("VIRBIUS_LICENSE_JWT").strip(),
    "tenant_id": os.environ.get("VIRBIUS_TENANT_ID", "default").strip(),
    "app_id": os.environ.get("VIRBIUS_APP_ID", "demo-app").strip(),
    "user_id": os.environ.get("VIRBIUS_SESSION_USER_ID", "1").strip(),
    "session_id": "demo-session",
}
BIN_HINT = os.environ.get("VIRBIUS_MCP_PROXY_BIN", "").strip()


def new_session() -> str:
    """Rotate engine session_id so leftover risk quota does not block every tool."""
    global _client
    META["session_id"] = "demo-" + uuid.uuid4().hex[:12]
    if _client is not None:
        try:
            _client.close()
        except Exception:
            pass
        _client = None
    return META["session_id"]


LLM10_META = {
    "license_jwt": settings.get("VIRBIUS_LICENSE_JWT").strip(),
    "tenant_id": os.environ.get("VIRBIUS_TENANT_ID", "default").strip(),
    "app_id": os.environ.get("VIRBIUS_APP_ID", "demo-app").strip(),
    "user_id": os.environ.get("VIRBIUS_SESSION_USER_ID", "1").strip(),
    "session_id": "llm10-init",
}
_llm10_client = None


def bind_llm10_session(session_id: str) -> str:
    """让当前请求用指定 engine session_id。和 Flask cookie 对齐，避免进程级单例把窗口串给所有人。"""
    global _llm10_client
    sid = (session_id or "").strip()
    if not sid:
        sid = "llm10-" + uuid.uuid4().hex[:12]
    LLM10_META["license_jwt"] = settings.get("VIRBIUS_LICENSE_JWT").strip()
    LLM10_META["session_id"] = sid
    if _llm10_client is not None and _llm10_client.meta.get("session_id") != sid:
        try:
            _llm10_client.close()
        except Exception:
            pass
        _llm10_client = None
    return sid


def new_llm10_session() -> str:
    """换一条 llm10 engine session，不和 Agent 页、其他浏览器抢累计窗口。"""
    return bind_llm10_session("llm10-" + uuid.uuid4().hex[:12])


LLM06_META = {
    "license_jwt": settings.get("VIRBIUS_LICENSE_JWT").strip(),
    "tenant_id": os.environ.get("VIRBIUS_TENANT_ID", "default").strip(),
    "app_id": os.environ.get("VIRBIUS_APP_ID", "demo-app").strip(),
    "user_id": os.environ.get("VIRBIUS_SESSION_USER_ID", "1").strip(),
    "session_id": "llm06-init",
}
_llm06_client = None


def bind_llm06_session(session_id: str) -> str:
    """让当前请求用指定 engine session_id。和 Flask cookie 对齐。"""
    global _llm06_client
    sid = (session_id or "").strip()
    if not sid:
        sid = "llm06-" + uuid.uuid4().hex[:12]
    LLM06_META["license_jwt"] = settings.get("VIRBIUS_LICENSE_JWT").strip()
    LLM06_META["session_id"] = sid
    if _llm06_client is not None and _llm06_client.meta.get("session_id") != sid:
        try:
            _llm06_client.close()
        except Exception:
            pass
        _llm06_client = None
    return sid


def new_llm06_session() -> str:
    """换一条 llm06 engine session，不和 Agent / LLM10 抢配额。"""
    return bind_llm06_session("llm06-" + uuid.uuid4().hex[:12])


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
    """Resolve the Rust proxy binary path (env override, else default build output)."""
    if BIN_HINT:
        return BIN_HINT
    # Workspace root (VirbiusAgent) and crate-local target dirs
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
    # Windows: prefer a real PE so we never CreateProcess a Linux ELF.
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


def _proxy_command(bin_path: str) -> list[str]:
    """On Windows, run a Linux ELF through WSL; otherwise spawn the binary directly.

    WSL NAT 的 127.0.0.1 不是 Windows。Windows 的 Popen env 也不会进 Linux 进程，
    必须写在 `wsl -e env VAR=...` 里。
    """
    if os.name == "nt" and os.path.isfile(bin_path) and _is_elf(bin_path):
        host = _wsl_windows_host()
        log.info("mcp-proxy via WSL, host=%s", host)
        return [
            "wsl", "--cd", _PROXY_DIR, "-e",
            "env",
            "VIRBIUS_UPSTREAM_URL=http://%s:9091" % host,
            "VIRBIUS_ENGINE_URL=http://%s:8082" % host,
            _to_wsl_path(bin_path),
        ]
    return [bin_path]


_WSL_HOST_CACHE = None


def _wsl_windows_host() -> str:
    """WSL NAT 里访问 Windows 要用 vEthernet (WSL) 地址，不是 127.0.0.1。

    不要每次 fork `wsl ip route`：Docker Desktop 卡住或 WSL 冷启动时 5s 就会
    让页面报 Command timed out。优先读本机网卡，WSL 只作兜底。
    """
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
    """Windows 上 vEthernet (WSL*) 的 IPv4 = WSL 默认网关。不进 Linux。

    中文 ipconfig 会在适配器名和 IPv4 之间插空行，不能按空行切块。
    """
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


class McpProxyClient:
    """Long-lived Rust mcp-proxy subprocess talking JSON-RPC over stdio."""

    def __init__(self, bin_path: str, meta: dict):
        self.meta = meta
        self.proc = subprocess.Popen(
            _proxy_command(bin_path),
            cwd=_PROXY_DIR,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
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
        # The Rust proxy prints tracing logs to stdout, which would pollute the
        # JSON-RPC stream. Skip any non-JSON line until we get a full response.
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
            "clientInfo": {"name": "dvla-demo", "version": "0.1.0"},
            "_meta": meta,
        }
        resp = self._send("initialize", params)
        if "error" in resp:
            raise RuntimeError("mcp-proxy initialize failed: " + str(resp["error"]))

    def call_tool(self, tool_name: str, args: dict) -> str:
        """Call a tool through the proxy. Returns the text result, or a block message."""
        resp = self._send("tools/call", {"name": tool_name, "arguments": args})
        if "error" in resp:
            return _format_block(
                tool_name, args, resp["error"],
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


# 进程级单例：demo 多轮对话复用同一 Rust proxy 会话
_client = None


def get_client() -> McpProxyClient:
    global _client
    if _client is None or _client.proc.poll() is not None:
        if _client is not None:
            _client.close()
        _client = McpProxyClient(_resolve_bin(), META)
    return _client


def call_tool(tool_name: str, args: dict) -> str:
    return get_client().call_tool(tool_name, args)


def get_llm10_client() -> McpProxyClient:
    global _llm10_client
    LLM10_META["license_jwt"] = settings.get("VIRBIUS_LICENSE_JWT").strip()
    if not LLM10_META["session_id"] or LLM10_META["session_id"] == "llm10-init":
        new_llm10_session()
    if _llm10_client is None or _llm10_client.proc.poll() is not None:
        if _llm10_client is not None:
            _llm10_client.close()
        _llm10_client = McpProxyClient(_resolve_bin(), dict(LLM10_META))
    return _llm10_client


def call_tool_llm10(tool_name: str, args: dict) -> str:
    return get_llm10_client().call_tool(tool_name, args)


def get_llm06_client() -> McpProxyClient:
    global _llm06_client
    LLM06_META["license_jwt"] = settings.get("VIRBIUS_LICENSE_JWT").strip()
    if not LLM06_META["session_id"] or LLM06_META["session_id"] == "llm06-init":
        new_llm06_session()
    if _llm06_client is None or _llm06_client.proc.poll() is not None:
        if _llm06_client is not None:
            _llm06_client.close()
        _llm06_client = McpProxyClient(_resolve_bin(), dict(LLM06_META))
    return _llm06_client


def call_tool_llm06(tool_name: str, args: dict) -> str:
    return get_llm06_client().call_tool(tool_name, args)


def _format_block(tool_name: str, args: dict, err: dict, session_id=None) -> str:
    """Proxy JSON-RPC 常把 rule_id 填成 null，再问一次 engine（不带 content）把规则名拿回来。"""
    msg = err.get("message") or "tool blocked"
    data = err.get("data") or {}
    parts = [str(msg)]
    reason = data.get("reason")
    if reason and reason != msg:
        parts.append(str(reason))
    rule = data.get("rule_id")
    peek = None if rule else _peek_evaluate(tool_name, args, session_id=session_id)
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


def _peek_evaluate(tool_name: str, args: dict, session_id=None):
    engine = (settings.get("VIRBIUS_ENGINE_URL") or "").rstrip("/")
    if not engine:
        return None
    payload = {
        "tenant_id": META.get("tenant_id") or "default",
        "session_id": session_id or META.get("session_id") or "demo-session",
        "user_id": META.get("user_id") or "1",
        "tool_name": tool_name,
        "role": "tool_call",
        "args_json": json.dumps(args or {}),
        "vars": {"app_id": META.get("app_id") or "demo-app"},
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