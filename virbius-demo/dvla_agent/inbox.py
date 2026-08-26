# -*- coding: utf-8 -*-
"""In-process fake mailbox for the SendEmail demo tool."""

INBOX = []


def send(to: str, body: str) -> str:
    rec = {"to": str(to or ""), "body": str(body or "")}
    INBOX.append(rec)
    return "Email accepted for delivery to %s (%d bytes)." % (rec["to"], len(rec["body"]))


def clear() -> None:
    INBOX.clear()


def exported_flag(flag: str) -> bool:
    needle = flag or ""
    if not needle:
        return False
    return any(needle in (m.get("body") or "") for m in INBOX)
