# -*- coding: utf-8 -*-
"""轻量会话记忆层：按 (浏览器会话 sid, 上下文 ctx) 维护多轮对话历史。
ctx 形如 'ctf:3' / 'owasp:LLM01' / 'agent'。服务端内存存储，支持清空/开新会话。"""
import threading
import secrets
from flask import session

_STORE = {}          # { sid: { ctx: [ {role, content}, ... ] } }
_LOCK = threading.Lock()
MAX_TURNS = 16       # 每个会话最多保留的消息条数（user+assistant 合计），防止 token 膨胀


def _sid():
    if "sid" not in session:
        session["sid"] = secrets.token_hex(8)
    return session["sid"]


def get(ctx):
    """返回该上下文的历史消息列表（副本）。"""
    sid = _sid()
    with _LOCK:
        return list(_STORE.get(sid, {}).get(ctx, []))


def append(ctx, role, content):
    sid = _sid()
    with _LOCK:
        msgs = _STORE.setdefault(sid, {}).setdefault(ctx, [])
        msgs.append({"role": role, "content": content})
        if len(msgs) > MAX_TURNS:
            del msgs[: len(msgs) - MAX_TURNS]


def clear(ctx):
    """清空某个上下文的会话记忆（开启新会话）。"""
    sid = _sid()
    with _LOCK:
        if sid in _STORE and ctx in _STORE[sid]:
            _STORE[sid][ctx] = []


def count(ctx):
    """返回轮数（user 消息数）。"""
    return sum(1 for m in get(ctx) if m["role"] == "user")
