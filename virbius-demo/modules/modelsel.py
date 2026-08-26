# -*- coding: utf-8 -*-
"""被攻击目标模型的选择层。用户可在右上角切换，选择存入会话。
小模型优先排序；DeepSeek 直连，其余国产/国外小模型走 OpenRouter。
每项: id(内部键) / label / note(产地·体量) / provider / model(真实 API id)。"""
from flask import session

MODELS = [
    # —— 本地部署 ——
    {"id": "local-qwen0.5b", "label": "Qwen2.5 0.5B（本地）", "note": "🖥️ 本地·Ollama",
     "provider": "local", "model": "qwen2.5:0.5b"},
    # —— 云端默认 ——
    {"id": "deepseek-flash", "label": "DeepSeek V4 Flash", "note": "🇨🇳 国产·默认",
     "provider": "deepseek", "model": "deepseek-chat"},
]
# 默认用稳定的云端 Flash（本地模型可能还在下载/推理慢，不设为默认）
DEFAULT = "deepseek-flash"
_BY_ID = {m["id"]: m for m in MODELS}


def current():
    """返回当前选中模型的内部 id。"""
    m = session.get("target_model")
    return m if m in _BY_ID else DEFAULT


def current_entry():
    """返回当前选中模型的完整配置 dict。"""
    return _BY_ID[current()]


def set_model(m):
    if m in _BY_ID:
        session["target_model"] = m
        return True
    return False
