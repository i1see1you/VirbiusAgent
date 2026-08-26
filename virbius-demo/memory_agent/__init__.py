# -*- coding: utf-8 -*-
"""ASI06 跨会话记忆投毒：行程助手的长期笔记本 / 假日历 / 假邮箱。

与银行客服隔离：不改 agent_range / 银行 TOOLS，发信也不走 dvla_agent.inbox。
"""
from memory_agent import calendar, mailbox, store

FLAG = calendar.FLAG
EXFIL_TO = calendar.EXFIL_TO


def snapshot():
    return {
        "calendar": calendar.snapshot(),
        "memory": store.snapshot(),
        "mailbox": mailbox.snapshot(),
        "flag": FLAG,
        "exfil_to": EXFIL_TO,
    }


def reset_long_term():
    """清空长期记忆 + 假邮箱，并把日历邀请恢复成未处理。"""
    store.clear()
    mailbox.clear()
    calendar.reset()


def exported_flag():
    return mailbox.exported_flag(FLAG)
