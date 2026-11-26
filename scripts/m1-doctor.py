#!/usr/bin/env python3
"""M1 doctor / acceptance probe (single-tenant, SSE).

Out-of-band operator tool — NOT a model-callable MCP tool.

Checks:
  1. GET /sse + initialize succeed
  2. initialize advertises capabilities.virbiusProxy.localExec
  3. tools/list is reachable; with a license, the granted canonical tools appear
  4. optional --call-shell: virbius__shell executes and reports sandbox_used=gvisor

Usage:
  python3 scripts/m1-doctor.py --url http://127.0.0.1:9090
  python3 scripts/m1-doctor.py --url http://127.0.0.1:9090 \
      --license-jwt-file /path/license.jwt --expect-canonical virbius__shell
  python3 scripts/m1-doctor.py --url http://127.0.0.1:9090 \
      --license-jwt-file /path/license.jwt --call-shell

Exit code 0 = all checks passed, 1 = failure.
"""

import argparse
import base64
import json
import queue
import sys
import threading
import time
import urllib.error
import urllib.request

CANONICAL = ("virbius__shell", "virbius__python", "virbius__node")

results = []


def check(ok: bool, name: str, detail: str = "") -> None:
    results.append(ok)
    print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail and not ok else ""))


def decode_jwt_claims(jwt: str):
    try:
        payload = jwt.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        raw = base64.urlsafe_b64decode(payload.replace("-", "+").replace("_", "/"))
        return json.loads(raw)
    except Exception:
        return None


class SseClient:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.q = queue.Queue()
        self.events = []
        self.endpoint = None
        self._resp = None

    def connect(self, timeout: float = 15.0) -> None:
        self._resp = urllib.request.urlopen(self.base + "/sse", timeout=timeout)
        threading.Thread(target=self._read, args=(self._resp,), daemon=True).start()
        self.endpoint = self._wait_event("endpoint", timeout)
        self.post_url = self.base + self.endpoint

    def _read(self, resp) -> None:
        event, data = None, []
        try:
            for raw in resp:
                line = raw.decode("utf-8", "replace").rstrip("\r\n")
                if line == "":
                    if event is not None:
                        joined = "\n".join(data)
                        self.q.put((event, joined))
                        self.events.append((event, joined))
                    event, data = None, []
                    continue
                if line.startswith(":"):
                    continue
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip())
        except Exception:
            pass
        finally:
            self.q.put(("__eof__", ""))

    def _wait_event(self, kind: str, timeout: float) -> str:
        end = time.time() + timeout
        while time.time() < end:
            try:
                ev, d = self.q.get(timeout=max(0.05, end - time.time()))
            except queue.Empty:
                break
            if ev == kind:
                return d
        raise RuntimeError(f"timeout waiting for SSE event '{kind}' (got {self.events[-3:]})")

    def rpc(self, msg_id, method, params, timeout: float = 30.0):
        body = json.dumps(
            {"jsonrpc": "2.0", "id": msg_id, "method": method, "params": params}
        ).encode()
        req = urllib.request.Request(
            self.post_url, data=body, headers={"Content-Type": "application/json"}
        )
        urllib.request.urlopen(req, timeout=timeout).read()
        end = time.time() + timeout
        while time.time() < end:
            ev, d = self.q.get(timeout=max(0.05, end - time.time()))
            if ev != "message":
                continue
            msg = json.loads(d)
            if msg.get("id") == msg_id:
                return msg
        raise RuntimeError(f"timeout waiting for response id={msg_id}")

    def close(self):
        try:
            if self._resp is not None:
                self._resp.close()
        except Exception:
            pass


def main() -> int:
    ap = argparse.ArgumentParser(description="Single-tenant M1 doctor (SSE)")
    ap.add_argument("--url", default="http://127.0.0.1:9090", help="proxy base URL")
    ap.add_argument("--license-jwt-file", default="", help="license JWT to present at initialize")
    ap.add_argument(
        "--expect-canonical",
        action="append",
        default=[],
        help="canonical tool expected in tools/list (repeatable)",
    )
    ap.add_argument("--call-shell", action="store_true", help="execute virbius__shell once")
    ap.add_argument("--command", default="echo m1-ok", help="command for --call-shell")
    args = ap.parse_args()

    jwt = ""
    if args.license_jwt_file:
        with open(args.license_jwt_file) as f:
            jwt = f.read().strip()

    client = SseClient(args.url)
    try:
        client.connect()
    except (urllib.error.URLError, OSError, RuntimeError) as e:
        check(False, "connect GET /sse", str(e))
        return 1
    check(True, "connect GET /sse")

    meta = {}
    if jwt:
        claims = decode_jwt_claims(jwt)
        if claims and claims.get("app_id"):
            meta["app_id"] = claims["app_id"]
        meta["license_jwt"] = jwt

    init = client.rpc(
        1,
        "initialize",
        {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "m1-doctor", "version": "1"},
            "_meta": meta,
        },
    )
    check(init.get("result") is not None, "initialize", json.dumps(init)[:300])
    caps = (init.get("result") or {}).get("capabilities", {}).get("virbiusProxy", {})
    local = caps.get("localExec") if isinstance(caps, dict) else None
    check(isinstance(local, dict), "capabilities.virbiusProxy.localExec present", json.dumps(caps)[:300])
    if isinstance(local, dict):
        check(
            local.get("namespace") == "virbius",
            "localExec.namespace == virbius",
            json.dumps(local)[:200],
        )

    listing = client.rpc(2, "tools/list", {})
    tools = [t.get("name") for t in (listing.get("result") or {}).get("tools", [])]
    check(listing.get("result") is not None, "tools/list", json.dumps(listing)[:300])
    print(f"       tools: {tools}")

    if jwt:
        for name in args.expect_canonical:
            check(name in tools, f"granted canonical tool visible: {name}", f"tools={tools}")
        # A wildcard license grants nothing local: canonical tools must be absent.
        claims = decode_jwt_claims(jwt) or {}
        if not claims.get("allowed_tools"):
            check(
                not any(n in tools for n in CANONICAL),
                "wildcard license advertises no local tools",
                f"tools={tools}",
            )

    if args.call_shell:
        check(bool(jwt), "--call-shell requires --license-jwt-file")
        if jwt:
            call = client.rpc(
                3,
                "tools/call",
                {"name": "virbius__shell", "arguments": {"command": args.command}},
            )
            if call.get("error"):
                err = call["error"]
                reason = (err.get("data") or {}).get("reason_code")
                check(False, "virbius__shell executes", f"{reason or err.get('message')}")
            else:
                meta_out = (call.get("result") or {}).get("_meta") or {}
                check(
                    meta_out.get("sandbox_used") == "gvisor",
                    "virbius__shell reports sandbox_used=gvisor",
                    json.dumps(meta_out)[:300],
                )
                check(
                    "truncated" in meta_out,
                    "local result carries the frozen _meta contract",
                    json.dumps(meta_out)[:300],
                )
                text = ""
                for item in (call.get("result") or {}).get("content", []):
                    if item.get("type") == "text":
                        text += item.get("text", "")
                print(f"       output: {text.strip()[:200]}")

    client.close()
    failed = results.count(False)
    print(f"\n{'OK' if failed == 0 else 'FAILED'}: {results.count(True)} passed, {failed} failed")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
