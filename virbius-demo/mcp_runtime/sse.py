# -*- coding: utf-8 -*-
"""FastMCP 工具表 + 与 virbius-mcp-proxy 对齐的旧 SSE 握手。

官方 FastMCP 的 Starlette SSE 路径/endpoint 事件和现有 Rust proxy 不完全同构，
所以 @mcp.tool() 负责工具定义，握手仍用 GET /sse → endpoint → POST /messages/。
"""
from __future__ import annotations

import inspect
import json
import os
import queue
import threading
import urllib.parse
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def _json_schema(tool) -> dict:
    schema = getattr(tool, "parameters", None)
    if schema is None:
        schema = getattr(tool, "inputSchema", None)
    if hasattr(schema, "model_dump"):
        try:
            schema = schema.model_dump()
        except Exception:
            schema = None
    if not isinstance(schema, dict):
        return {"type": "object", "properties": {}}
    return schema


def _tool_items(mcp) -> list:
    mgr = getattr(mcp, "_tool_manager", None)
    if mgr is None:
        return []
    raw = []
    if hasattr(mgr, "list_tools"):
        try:
            raw = list(mgr.list_tools())
        except Exception:
            raw = []
    if not raw and hasattr(mgr, "_tools"):
        raw = list(mgr._tools.values())
    items = []
    for tool in raw:
        name = getattr(tool, "name", "") or ""
        if not name:
            continue
        items.append({
            "name": name,
            "description": getattr(tool, "description", None) or "",
            "inputSchema": _json_schema(tool),
        })
    return items


def _get_tool(mcp, name: str):
    mgr = getattr(mcp, "_tool_manager", None)
    if mgr is None:
        return None
    if hasattr(mgr, "get_tool"):
        try:
            return mgr.get_tool(name)
        except Exception:
            pass
    tools = getattr(mgr, "_tools", None) or {}
    return tools.get(name)


def _coerce(param, value):
    ann = param.annotation
    if ann is inspect.Parameter.empty or value is None:
        return value
    if ann is int:
        try:
            return int(value)
        except (TypeError, ValueError):
            return value
    if ann is float:
        try:
            return float(value)
        except (TypeError, ValueError):
            return value
    return value


def call_fastmcp(mcp, name: str, args: dict) -> dict:
    """同步调用 FastMCP 注册的工具。返回 {success, result|error}。"""
    tool = _get_tool(mcp, name)
    if tool is None:
        return {"success": False, "error": "unknown tool: " + str(name)}
    fn = getattr(tool, "fn", None) or getattr(tool, "handler", None) or getattr(tool, "func", None)
    if fn is None:
        return {"success": False, "error": "no handler for " + str(name)}
    kwargs = {}
    try:
        sig = inspect.signature(fn)
        for pname, param in sig.parameters.items():
            if pname in ("self", "cls"):
                continue
            if args and pname in args:
                kwargs[pname] = _coerce(param, args[pname])
            elif param.default is inspect.Parameter.empty and args:
                # 常见别名：userId / trip_id 等已在函数签名里
                pass
        result = fn(**kwargs)
    except TypeError:
        try:
            result = fn(**(args or {}))
        except Exception as exc:  # noqa: BLE001
            return {"success": False, "error": str(exc)}
    except Exception as exc:  # noqa: BLE001
        return {"success": False, "error": str(exc)}
    if result is None:
        text = ""
    elif isinstance(result, str):
        text = result
    else:
        text = json.dumps(result, ensure_ascii=False)
    return {"success": True, "result": text}


def run_sse(mcp, port: int, server_name: str = "fastmcp") -> None:
    """阻塞监听。host 默认 0.0.0.0，给 WSL proxy 打宿主 IP。"""
    host = os.environ.get("VIRBIUS_SSE_BIND", "0.0.0.0").strip() or "0.0.0.0"
    sessions = {}
    lock = threading.Lock()

    def get_queue(sid: str) -> queue.Queue:
        with lock:
            q = sessions.get(sid)
            if q is None:
                q = queue.Queue()
                sessions[sid] = q
            return q

    def dispatch(request):
        if not isinstance(request, dict):
            return {"jsonrpc": "2.0", "id": None,
                    "error": {"code": -32700, "message": "parse error"}}
        method = request.get("method")
        rid = request.get("id")
        params = request.get("params") or {}
        if isinstance(method, str) and method.startswith("notifications/"):
            return None
        if method == "initialize":
            return {
                "jsonrpc": "2.0", "id": rid,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "serverInfo": {"name": server_name, "version": "0.1.0"},
                },
            }
        if method == "tools/list":
            return {"jsonrpc": "2.0", "id": rid, "result": {"tools": _tool_items(mcp)}}
        if method == "tools/call":
            name = params.get("name", "")
            args = params.get("arguments") or {}
            result = call_fastmcp(mcp, name, args)
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
            sid = qs.get("session_id", [""])[0].strip() or ("sess-" + uuid.uuid4().hex)
            q = get_queue(sid)
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
            resp = dispatch(request)
            if resp is not None:
                get_queue(sid).put(json.dumps(resp, ensure_ascii=False))
            self.send_response(202)
            self.send_header("Content-Type", "application/json")
            self.end_headers()

    server = ThreadingHTTPServer((host, int(port)), Handler)
    print("fastmcp SSE %s listening on %s:%d" % (server_name, host, int(port)), flush=True)
    server.serve_forever()
