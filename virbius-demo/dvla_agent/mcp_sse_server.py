# -*- coding: utf-8 -*-
"""dvla 工具 MCP server —— SSE 传输版。

这是 Rust 版 virbius-mcp-proxy 的 upstream：它通过 SSE 长连接连到本进程，
再 POST JSON-RPC 消息执行真正的 dvla 工具（复用 dvla_agent.mcp_server.call_tool）。

协议（MCP over SSE，与 Rust proxy 的 upstream.rs 客户端匹配）：
  - GET  /sse?session_id=xxx        建立 SSE 长连接，发送 `endpoint` 事件
  - POST /messages/?session_id=xxx  接收 JSON-RPC，执行工具，经 SSE `message` 事件回传

独立进程运行：python -m dvla_agent.mcp_sse_server [port]
"""
import json
import os
import queue
import sys
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from dvla_agent import mcp_server

TOOLS = [
    {"name": "GetCurrentUser", "description": "Returns the current user (userId)."},
    {"name": "GetUserTransactions",
     "description": "Returns the transactions associated to the provided userId."},
    {"name": "GetBankNotice",
     "description": "Returns a bank notice. topic is reconcile (default) or urgent."},
    {"name": "SendEmail",
     "description": "Sends an email. Arguments: to (address), body (text)."},
]

# session_id -> queue.Queue（SSE 出站消息队列）
_SESSIONS = {}
_SESSIONS_LOCK = threading.Lock()


def _get_queue(sid: str) -> queue.Queue:
    with _SESSIONS_LOCK:
        q = _SESSIONS.get(sid)
        if q is None:
            q = queue.Queue()
            _SESSIONS[sid] = q
        return q


def _dispatch(request):
    """Handle a JSON-RPC MCP method and return a response dict."""
    if not isinstance(request, dict):
        return {"jsonrpc": "2.0", "id": None,
                "error": {"code": -32700, "message": "parse error"}}
    method = request.get("method")
    rid = request.get("id")
    params = request.get("params") or {}

    if method == "initialize":
        return {
            "jsonrpc": "2.0", "id": rid,
            "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "serverInfo": {"name": "dvla-mcp-sse", "version": "0.1.0"},
            },
        }
    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}}
    if method == "tools/call":
        name = params.get("name", "")
        args = params.get("arguments") or {}
        result = mcp_server.call_tool(name, args)
        if result.get("success"):
            return {
                "jsonrpc": "2.0", "id": rid,
                "result": {"content": [{"type": "text", "text": result["result"]}]},
            }
        return {"jsonrpc": "2.0", "id": rid,
                "error": {"code": -32000, "message": result.get("error", "tool failed")}}
    return {"jsonrpc": "2.0", "id": rid,
            "error": {"code": -32601, "message": "method not found" if method else "no method"}}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send_sse(self, event: str, data: str):
        self.wfile.write(("event: %s\ndata: %s\n\n" % (event, data)).encode("utf-8"))
        self.wfile.flush()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path.rstrip("/") != "/sse":
            self.send_error(404)
            return
        qs = urllib.parse.parse_qs(parsed.query)
        # Bearers that omit session_id (e.g. the Rust mcp-proxy upstream client)
        # all fall back to the same "default" slot, so concurrent connections
        # would share one queue and steal each other's responses. Give each
        # anonymous connection its own unique session_id to isolate them.
        sid = qs.get("session_id", [""])[0].strip() or ("sess-" + uuid.uuid4().hex)
        q = _get_queue(sid)

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()

        endpoint = "/messages/?session_id=%s" % urllib.parse.quote(sid)
        self.send_sse("endpoint", endpoint)

        while True:
            try:
                msg = q.get(timeout=30)
            except queue.Empty:
                # heartbeat to keep the SSE connection alive
                self.wfile.write(b": ping\n\n")
                self.wfile.flush()
                continue
            self.send_sse("message", msg)

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path.rstrip("/") != "/messages":
            self.send_error(404)
            return
        qs = urllib.parse.parse_qs(parsed.query)
        sid = qs.get("session_id", ["default"])[0]

        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        try:
            request = json.loads(body)
        except ValueError:
            request = None

        response = _dispatch(request)
        _get_queue(sid).put(json.dumps(response))

        self.send_response(202)
        self.send_header("Content-Type", "application/json")
        self.end_headers()


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9091
    # 默认 0.0.0.0：WSL mcp-proxy 打 Windows 宿主 IP，127.0.0.1 会连不上。
    host = os.environ.get("VIRBIUS_SSE_BIND", "0.0.0.0").strip() or "0.0.0.0"
    server = ThreadingHTTPServer((host, port), Handler)
    print("dvla mcp SSE server listening on %s:%d" % (host, port), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()