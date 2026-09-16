"""Load B³ tool JSON into OpenAI-style function tools."""

from __future__ import annotations

import json

from scorer import load_tool_specs


def openai_tools(names: list[str]) -> list[dict]:
    specs = load_tool_specs()
    out = []
    for name in names:
        spec = specs.get(name)
        if not spec:
            continue
        out.append(
            {
                "type": "function",
                "function": {
                    "name": spec["name"],
                    "description": spec["description"],
                    "parameters": spec.get("inputSchema") or {"type": "object", "properties": {}},
                },
            }
        )
    return out


def format_tool_calls(message: dict) -> str:
    text = message.get("content") or ""
    for call in message.get("tool_calls") or []:
        fn = (call.get("function") or {}).get("name") or call.get("name") or ""
        args = (call.get("function") or {}).get("arguments") or call.get("arguments") or {}
        if not isinstance(args, str):
            args = json.dumps(args, ensure_ascii=False)
        text = (text + f"\n调用工具 {fn}\n参数：{args}").strip()
    return text
