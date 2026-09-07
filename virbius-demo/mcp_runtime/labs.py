# -*- coding: utf-8 -*-
"""走 mcp-proxy 的关卡目录：一关一租户、一 app、一 SSE 端口。"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Lab:
    id: str
    label: str
    tenant_id: str
    tenant_name: str
    app_id: str
    agent_name: str
    port: int
    session_prefix: str
    user_id: str = "1"


LABS = (
    Lab("bank", "银行客服", "bank", "银行客服", "bank-app", "bank-agent", 9091, "bank-"),
    Lab("llm10", "OWASP LLM10", "owasp-llm10", "OWASP 无限制消耗", "llm10-app", "llm10-agent", 9092, "llm10-"),
    Lab("llm06", "OWASP LLM06", "owasp-llm06", "OWASP 过度代理", "llm06-app", "llm06-agent", 9093, "llm06-"),
    Lab("memory", "记忆", "memory", "行程记忆", "memory-app", "memory-agent", 9094, "mem-"),
    Lab("ops", "值班运维", "ops", "值班运维", "ops-app", "ops-agent", 9095, "ops-"),
    Lab("egress", "外发渠道", "egress", "外发渠道", "egress-app", "egress-agent", 9096, "egr-"),
    Lab("chain", "文件整理", "chain", "文件整理", "chain-app", "chain-agent", 9097, "chain-"),
)

BY_ID = {lab.id: lab for lab in LABS}


def get(lab_id: str) -> Lab:
    lab = BY_ID.get(lab_id)
    if lab is None:
        raise KeyError("unknown lab: " + str(lab_id))
    return lab
