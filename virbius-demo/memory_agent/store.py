# -*- coding: utf-8 -*-
"""长期记忆笔记本：JSON 文件，跨聊天会话仍在。检索走关键词/全量召回。"""
import json
import os
import threading
import time
import uuid

from memory_agent import calendar

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_PATH = os.path.join(_ROOT, "data", "memory", "notebook.json")
_lock = threading.Lock()
_entries = []


def _ensure_dir():
    os.makedirs(os.path.dirname(_PATH), exist_ok=True)


def _persist():
    _ensure_dir()
    tmp = _PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump({"entries": _entries}, f, ensure_ascii=False, indent=2)
    os.replace(tmp, _PATH)


def _load():
    global _entries
    try:
        with open(_PATH, encoding="utf-8") as f:
            data = json.load(f)
        _entries = list(data.get("entries") or [])
    except (OSError, ValueError):
        _entries = []


_load()


def snapshot():
    with _lock:
        return {"entries": list(_entries), "count": len(_entries)}


def save_memory(content, title=""):
    text = (content or "").strip()
    if not text:
        return "save_memory 失败：缺少 content。"
    rec = {
        "id": "mem-" + uuid.uuid4().hex[:8],
        "title": (title or "工作惯例").strip() or "工作惯例",
        "content": text,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "fresh": True,
    }
    with _lock:
        for old in _entries:
            old["fresh"] = False
        _entries.append(rec)
        _persist()
    calendar.mark_processed()
    return json.dumps({"ok": True, "saved": rec}, ensure_ascii=False)


def search_memory(query):
    """小笔记本：全量召回。query 只用于日志/拦截链，不靠 embedding。"""
    _ = (query or "").strip()
    with _lock:
        items = list(_entries)
    if not items:
        return "（长期记忆为空）"
    blob = []
    for e in items:
        blob.append("- [%s] %s\n  %s" % (e.get("id"), e.get("title"), e.get("content")))
    return "长期记忆召回 %d 条：\n%s" % (len(items), "\n".join(blob))


def mark_persisted():
    """新会话之后：条目还在，但不再标「刚写入」。"""
    with _lock:
        for e in _entries:
            e["fresh"] = False
        if _entries:
            _persist()


def clear():
    global _entries
    with _lock:
        _entries = []
        _persist()
