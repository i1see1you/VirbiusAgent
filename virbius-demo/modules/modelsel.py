# -*- coding: utf-8 -*-
"""被攻击目标模型的选择层。用户可在右上角切换，选择存入会话。
小模型优先排序；DeepSeek 直连，其余国产/国外小模型走 OpenRouter。
每项: id(内部键) / label / note(产地·体量) / provider / model(真实 API id)。"""
from flask import session

MODELS = [
    # —— 本地部署 ——
    {"id": "local-qwen0.5b", "label": "Qwen2.5 0.5B（本地）", "note": "🖥️ 本地·Ollama",
     "provider": "local", "model": "qwen2.5:0.5b"},
    {"id": "local-deepseek-8b", "label": "DeepSeek-R1 8B（本地）", "note": "🖥️ 本地·Ollama",
     "provider": "local", "model": "deepseek-r1:8b"},
    # —— 国产 ——
    {"id": "deepseek-flash", "label": "DeepSeek V4 Flash", "note": "🇨🇳 国产·默认",
     "provider": "deepseek", "model": "deepseek-chat"},
    {"id": "qwen2.5-7b", "label": "通义千问 Qwen2.5 7B", "note": "🇨🇳 国产·7B",
     "provider": "openrouter", "model": "qwen/qwen-2.5-7b-instruct"},
    {"id": "qwen3-8b", "label": "通义千问 Qwen3 8B", "note": "🇨🇳 国产·8B",
     "provider": "openrouter", "model": "qwen/qwen3-8b"},
    {"id": "glm-4.7-flash", "label": "智谱 GLM-4.7 Flash", "note": "🇨🇳 国产·智谱",
     "provider": "openrouter", "model": "z-ai/glm-4.7-flash"},
    {"id": "minimax-m2.5", "label": "MiniMax M2.5", "note": "🇨🇳 国产·MiniMax",
     "provider": "openrouter", "model": "minimax/minimax-m2.5"},
    # —— 国外小模型 ——
    {"id": "llama3.2-1b", "label": "Llama 3.2 1B", "note": "🌍 国外·1B 最小",
     "provider": "openrouter", "model": "meta-llama/llama-3.2-1b-instruct"},
    {"id": "llama3.2-3b", "label": "Llama 3.2 3B", "note": "🌍 国外·3B",
     "provider": "openrouter", "model": "meta-llama/llama-3.2-3b-instruct"},
    {"id": "llama3.1-8b", "label": "Llama 3.1 8B", "note": "🌍 国外·8B",
     "provider": "openrouter", "model": "meta-llama/llama-3.1-8b-instruct"},
    {"id": "gemma3-4b", "label": "Google Gemma 3 4B", "note": "🌍 国外·谷歌",
     "provider": "openrouter", "model": "google/gemma-3-4b-it"},
    {"id": "phi4-mini", "label": "Microsoft Phi-4 Mini", "note": "🌍 国外·微软",
     "provider": "openrouter", "model": "microsoft/phi-4-mini-instruct"},
    {"id": "ministral-3b", "label": "Mistral Ministral 3B", "note": "🌍 国外·Mistral",
     "provider": "openrouter", "model": "mistralai/ministral-3b-2512"},
    {"id": "gpt5-nano", "label": "OpenAI GPT-5 Nano", "note": "🌍 国外·OpenAI",
     "provider": "openrouter", "model": "openai/gpt-5-nano"},
    {"id": "gpt4o-mini", "label": "OpenAI GPT-4o Mini", "note": "🌍 国外·OpenAI",
     "provider": "openrouter", "model": "openai/gpt-4o-mini"},
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
