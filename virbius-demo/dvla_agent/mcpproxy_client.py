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
import subprocess

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


def _resolve_bin() -> str:
    """Resolve the Rust proxy binary path (env override, else default build output)."""
    if BIN_HINT:
        return BIN_HINT
    # Workspace root (VirbiusAgent) and crate-local target dirs
    roots = [
        os.path.normpath(os.path.join(_PROXY_DIR, "..")),
        os.path.normpath(os.path.join(_PROXY_DIR, "..", "virbius-mcp-proxy")),
    ]
    for root in roots:
        base = os.path.normpath(os.path.join(root, "target", "debug", "virbius-mcp-proxy"))
        for cand in (base + ".exe", base):
            if os.path.isfile(cand):
                return cand
    return os.path.normpath(os.path.join(roots[0], "target", "debug", "virbius-mcp-proxy"))


class McpProxyClient:
    """Long-lived Rust mcp-proxy subprocess talking JSON-RPC over stdio."""

    def __init__(self, bin_path: str, meta: dict):
        self.proc = subprocess.Popen(
            [bin_path],
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
            err = resp["error"]
            msg = err.get("message", "tool blocked")
            return "[blocked] " + msg
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