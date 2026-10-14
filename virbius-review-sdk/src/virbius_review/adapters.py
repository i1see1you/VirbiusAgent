"""Provider stream adapters: turn OpenAI SSE / Ollama NDJSON lines into
plain text-delta iterables. The review core is provider-agnostic — it only
ever consumes ``Iterable[str]`` of content deltas.

Usage::

    lines = (line.decode() for line in resp.iter_lines())
    deltas = from_openai_sse(lines)          # or from_ollama_ndjson(lines)
    for ev in client.wrap_stream(deltas, session_id=s, trace_id=t):
        ...
"""

from __future__ import annotations

import json
from typing import Iterable, Iterator


def from_openai_sse(lines: Iterable[str]) -> Iterator[str]:
    """OpenAI-compatible SSE lines → text deltas (ignores non-content chunks)."""
    for line in lines:
        line = line.strip()
        if not line or not line.startswith("data:"):
            continue
        data = line[len("data:"):].strip()
        if data == "[DONE]":
            return
        try:
            chunk = json.loads(data)
        except Exception:
            continue
        choices = chunk.get("choices") or []
        if not choices:
            continue
        delta = (choices[0] or {}).get("delta") or {}
        content = delta.get("content")
        if isinstance(content, str) and content:
            yield content


def from_ollama_ndjson(lines: Iterable[str]) -> Iterator[str]:
    """Ollama /api/chat NDJSON lines → text deltas (stops on done=true)."""
    for line in lines:
        line = line.strip()
        if not line:
            continue
        try:
            chunk = json.loads(line)
        except Exception:
            continue
        message = chunk.get("message") or {}
        content = message.get("content")
        if isinstance(content, str) and content:
            yield content
        if chunk.get("done"):
            return
