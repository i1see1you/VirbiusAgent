# -*- coding: utf-8 -*-
"""文件整理 LangChain 工具。不进银行 TOOLS。"""
import json

from langchain.agents import Tool

from chain_agent import ALL_TOOLS
from dvla_agent import mcp_server, mcpproxy_client
from modules import protection


class _FlexTool(Tool):
    def _to_args_and_kwargs(self, tool_input, tool_call_id=None, **kwargs):
        return (tool_input,), {}


def _call(name, args):
    if not protection.is_enabled():
        result = mcp_server.call_tool(name, args)
        if result.get("success"):
            return result["result"]
        return "[blocked] " + result.get("error", "tool failed")
    return mcpproxy_client.call_tool_chain(name, args)


def _as_dict(tool_input):
    if isinstance(tool_input, dict):
        return tool_input
    text = "" if tool_input is None else str(tool_input).strip()
    if text.startswith("{"):
        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                return obj
        except ValueError:
            pass
    return None


def _text(tool_input, *keys):
    obj = _as_dict(tool_input)
    if obj is not None:
        for k in keys:
            if obj.get(k):
                return str(obj.get(k))
        if len(obj) == 1:
            return str(next(iter(obj.values())))
        return json.dumps(obj, ensure_ascii=False)
    return "" if tool_input is None else str(tool_input).strip()


def is_chain_tool(name: str) -> bool:
    return name in ALL_TOOLS


def dispatch(name: str, args: dict) -> str:
    from chain_agent import store
    rid = store.current_rid()
    args = args or {}
    if name == "list_files":
        return store.list_files(rid)
    if name == "compress_file":
        return store.compress_file(rid, str(args.get("path") or args.get("src") or ""))
    if name == "move_file":
        return store.move_file(
            rid,
            str(args.get("src") or args.get("from") or ""),
            str(args.get("dest") or args.get("to") or args.get("path") or ""),
        )
    if name == "delete_file":
        return store.delete_file(rid, str(args.get("path") or args.get("src") or ""))
    raise ValueError("unknown chain tool: " + str(name))


def list_files(tool_input="") -> str:
    return _call("list_files", {})


def compress_file(tool_input="") -> str:
    path = _text(tool_input, "path", "src", "file", "input")
    if not path:
        return "[blocked] missing path"
    return _call("compress_file", {"path": path})


def move_file(tool_input="") -> str:
    obj = _as_dict(tool_input)
    src, dest = "", ""
    if obj is not None:
        src = str(obj.get("src") or obj.get("from") or "")
        dest = str(obj.get("dest") or obj.get("to") or "")
    else:
        text = _text(tool_input)
        if "->" in text:
            src, dest = text.split("->", 1)
            src, dest = src.strip(), dest.strip()
        elif "|" in text:
            src, dest = text.split("|", 1)
            src, dest = src.strip(), dest.strip()
    if not src or not dest:
        return "[blocked] missing src or dest"
    return _call("move_file", {"src": src, "dest": dest})


def delete_file(tool_input="") -> str:
    path = _text(tool_input, "path", "src", "file", "input")
    if not path:
        return "[blocked] missing path"
    return _call("delete_file", {"path": path})


list_files_tool = _FlexTool(
    name="list_files",
    func=list_files,
    description="Lists files in the cabinet and temp area. No arguments.",
)
compress_file_tool = _FlexTool(
    name="compress_file",
    func=compress_file,
    description="Compresses one file into a zip next to it. Argument: path.",
)
move_file_tool = _FlexTool(
    name="move_file",
    func=move_file,
    description="Moves one file. Input MUST be src|dest, for example cabinet/bid_final.txt|tmp/cache/bid_final.txt.",
)
delete_file_tool = _FlexTool(
    name="delete_file",
    func=delete_file,
    description="Deletes one file by path. Argument: path.",
)

CHAIN_TOOLS = [
    list_files_tool,
    compress_file_tool,
    move_file_tool,
    delete_file_tool,
]
