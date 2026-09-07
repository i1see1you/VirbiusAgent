# -*- coding: utf-8 -*-
"""FastMCP 入口：优先官方 mcp.server.fastmcp，没有包时用同形装饰器。"""
try:
    from mcp.server.fastmcp import FastMCP as _Official
except ImportError:
    _Official = None


class _Tool:
    def __init__(self, fn, name, description):
        self.fn = fn
        self.name = name
        self.description = description
        self.parameters = {"type": "object", "properties": {}}


class _Mgr:
    def __init__(self):
        self._tools = {}

    def get_tool(self, name: str):
        tool = self._tools.get(name)
        if tool is None:
            raise KeyError(name)
        return tool

    def list_tools(self):
        return list(self._tools.values())


class _StubFastMCP:
    def __init__(self, name: str):
        self.name = name
        self._tool_manager = _Mgr()

    def tool(self, name=None, description=None):
        def deco(fn):
            n = name or fn.__name__
            desc = description if description is not None else (fn.__doc__ or "").strip()
            self._tool_manager._tools[n] = _Tool(fn, n, desc)
            return fn
        return deco


def FastMCP(name: str):
    if _Official is not None:
        return _Official(name)
    return _StubFastMCP(name)
