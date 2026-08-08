# -*- coding: utf-8 -*-
"""对话检查器：把每轮发给模型的完整 messages 与原始返回，打包给前端展示。
为避免直接从 system prompt 抄到答案，会对 flag/密钥本体做打码（变形泄露不受影响）。"""
import re

MASK = "【flag·已隐藏】"


def mask(text, secrets):
    for s in secrets:
        if s:
            text = re.sub(re.escape(s), MASK, text, flags=re.IGNORECASE)
    return text


def build(messages, raw, secrets):
    """messages: 实际发送的消息数组；raw: 模型原始返回；secrets: 需打码的串列表。"""
    return {
        "sent": [{"role": m["role"], "content": mask(m["content"], secrets)}
                 for m in messages],
        "raw": mask(raw, secrets),
    }


def note(text):
    """未调用模型时（如被输入守卫拦截）的占位 debug。"""
    return {"sent": [{"role": "note", "content": text}], "raw": "(本轮未调用模型)"}
