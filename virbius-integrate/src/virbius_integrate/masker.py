from __future__ import annotations

import re

PATTERNS: list[tuple[str, re.Pattern]] = [
    ("CN_PHONE", re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")),
    ("CN_ID_CARD", re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)")),
    ("BANK_CARD", re.compile(r"(?<!\d)\d{13,19}(?!\d)")),
    ("EMAIL", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
]


def mask(text):
    if not text:
        return text
    masked = text
    for name, pattern in PATTERNS:
        masked = pattern.sub(_replace, masked)
    return masked


def _replace(match: re.Match) -> str:
    value = match.group(0)
    if "@" in value:
        local, _, domain = value.partition("@")
        head = local[:1] if local else ""
        return f"{head}***@{domain}"
    if len(value) <= 6:
        return "*" * len(value)
    return f"{value[:3]}****{value[-3:]}"
