# -*- coding: utf-8 -*-
"""文件整理 FastMCP：只暴露本关四个工具。"""
from mcp_runtime.fastmcp import FastMCP
from chain_agent.tools import dispatch

mcp = FastMCP("chain")


@mcp.tool(name="list_files", description="Lists files in the cabinet and temp area. No arguments.")
def list_files() -> str:
    return dispatch("list_files", {})


@mcp.tool(name="compress_file", description="Compresses one file into a zip next to it. Argument: path.")
def compress_file(path: str = "") -> str:
    return dispatch("compress_file", {"path": path})


@mcp.tool(name="move_file", description="Moves one file. Arguments: src, dest.")
def move_file(src: str = "", dest: str = "") -> str:
    return dispatch("move_file", {"src": src, "dest": dest})


@mcp.tool(name="delete_file", description="Deletes one file by path. Argument: path.")
def delete_file(path: str = "") -> str:
    return dispatch("delete_file", {"path": path})
