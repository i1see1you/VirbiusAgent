#!/usr/bin/env python3
"""Minimal stdio MCP server used by the proxy integration tests.

Zero dependencies: newline-delimited JSON-RPC on stdin/stdout. Handles the
handshake, tools/list and tools/call. `tools/call` echoes its arguments and
the filtered environment so tests can assert on both.
"""
import json
import os
import sys


def respond(msg_id, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": msg_id, "result": result}) + "\n")
    sys.stdout.flush()


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = req.get("method", "")
        msg_id = req.get("id")

        if method == "initialize":
            respond(msg_id, {
                "protocolVersion": "2025-06-18",
                "capabilities": {},
                "serverInfo": {"name": "echo-mcp-stdio", "version": "0.1.0"},
            })
        elif method == "notifications/initialized":
            continue
        elif method == "tools/list":
            respond(msg_id, {"tools": [
                {"name": "echo", "description": "Echo args"},
                {"name": "read_doc", "description": "Read a document"},
            ]})
        elif method == "tools/call":
            args = (req.get("params") or {}).get("arguments") or {}
            respond(msg_id, {
                "content": [{"type": "text", "text": json.dumps(args)}],
                "isError": False,
                "env": {
                    k: os.environ.get(k)
                    for k in ("VIRBIUS_LICENSE_FILE", "VIRBIUS_REDIS_URL", "FS_ROOT")
                },
            })
        elif method == "crash":
            sys.exit(7)
        elif msg_id is not None:
            sys.stdout.write(json.dumps({
                "jsonrpc": "2.0",
                "id": msg_id,
                "error": {"code": -32601, "message": "method not found"},
            }) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
