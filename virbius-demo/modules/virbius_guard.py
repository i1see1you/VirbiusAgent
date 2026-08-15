# -*- coding: utf-8 -*-
"""VirbiusAgent 云层提示词防护（CTF 注入闯关用）。

CTF 里把"用户输入"先发给 virbius-engine 的 `/v1/evaluate` 做提示词安全评估。
engine 会自动执行两层防护（见 virbius-engine EvaluateOrchestrator）：
  1. LLM 语义检测（PromptInjectionDetector / PromptRunner）——qwen3guard 判断注入/越狱意图；
  2. 运营台 prompt 规则 + category-rule-mapping——命中分类后映射到具体规则，用规则的风险分/动作处置。

本模块只负责"调接口 + 判定是否拦截"，不参与 engine 内部逻辑（硬约束：不改 virbius-engine）。
"""
import logging
import requests

from modules import settings

logger = logging.getLogger(__name__)


def guard_prompt(content, session_id="ctf:5", user_id="ctf-player"):
    """调用 virbius-engine /v1/evaluate，返回拦截判定与信号详情。

    返回 dict：
      - blocked   : 是否拦截（effectiveAction 为 block/deny 且 enforceMode 为 full）
      - action    : 原始 effectiveAction
      - enforce   : 原始 enforceMode
      - risk_score: maxRiskScore
      - rule_id   : 命中主规则
      - reason    : reasonCode（如 llm:Jailbreak）
      - error     : 调用失败时为错误信息，其余为 None
    """
    engine_url = settings.get("VIRBIUS_ENGINE_URL")
    # engine 的 EvaluateRequestDto 期望 snake_case 字段（tenant_id/session_id/user_id），
    # 不能用 camelCase，否则 tenant_id 反序列化为 null，PromptRunner 按租户查不到 prompt 规则。
    payload = {
        "tenant_id": "default",
        "session_id": session_id,
        "content": content,
        "role": "user",
        "user_id": user_id,
    }
    try:
        r = requests.post(f"{engine_url}/v1/evaluate", json=payload, timeout=30)
        r.raise_for_status()
        data = r.json()
    except requests.RequestException as e:
        logger.warning("[virbius_guard] engine 不可达(%s) fail-open: %s", engine_url, e)
        # engine 不可达时 fail-open：不拦截，但返回错误信息供前端标注
        return {
            "blocked": False, "action": "allow", "enforce": "full",
            "risk_score": None, "rule_id": None, "reason": None,
            "error": f"virbius-engine 不可达（{engine_url}）：{e}",
        }
    except ValueError:
        logger.warning("[virbius_guard] engine 返回非 JSON(%s)", engine_url)
        return {
            "blocked": False, "action": "allow", "enforce": "full",
            "risk_score": None, "rule_id": None, "reason": None,
            "error": f"virbius-engine 返回非 JSON（{engine_url}）",
        }

    action = data.get("effective_action", "allow")
    enforce = data.get("enforce_mode", "full")
    blocked = action in ("block", "deny") and enforce == "full"
    logger.info(
        "[virbius_guard] evaluate input=%r -> action=%s enforce=%s blocked=%s "
        "rule=%s reason=%s risk=%s",
        content, action, enforce, blocked,
        data.get("rule_id"), data.get("reason_code"), data.get("max_risk_score"),
    )
    return {
        "blocked": blocked,
        "action": action,
        "enforce": enforce,
        "risk_score": data.get("max_risk_score"),
        "rule_id": data.get("rule_id"),
        "reason": data.get("reason_code"),
        "error": None,
    }
