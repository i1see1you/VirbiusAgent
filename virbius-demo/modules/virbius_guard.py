# -*- coding: utf-8 -*-
"""VirbiusAgent 云层提示词防护（CTF 注入闯关用）。

CTF 里把"用户输入"先发给 virbius-engine 的 `/v1/evaluate` 做提示词安全评估。
engine 会自动执行两层防护（见 virbius-engine EvaluateOrchestrator）：
  1. LLM 语义检测（PromptInjectionDetector / PromptRunner）——VirbiusGuard 判断注入/越狱意图；
  2. 运营台 prompt 规则 + category-rule-mapping——命中分类后映射到具体规则，用规则的风险分/动作处置。

本模块只负责"调接口 + 判定是否拦截"，不参与 engine 内部逻辑（硬约束：不改 virbius-engine）。
"""
import logging
import threading
import time

import requests

from modules import settings

logger = logging.getLogger(__name__)

# Short-lived evaluate-token cache (P0-min). Keyed by license JWT.
_TOKEN_LOCK = threading.Lock()
_TOKEN_CACHE = {}


def _evaluate_token(license_jwt, control_url):
    """Exchange the License JWT for a short-lived evaluate token (cached)."""
    if not license_jwt or not control_url:
        return ""
    now = time.time()
    with _TOKEN_LOCK:
        cached = _TOKEN_CACHE.get(license_jwt)
        if cached and now < cached[1]:
            return cached[0]
    try:
        resp = requests.post(
            control_url.rstrip("/") + "/api/v1/auth/evaluate-token",
            json={"licenseJwt": license_jwt},
            timeout=10,
        )
        resp.raise_for_status()
        data = resp.json()
        payload = data.get("data", data) if isinstance(data, dict) else {}
        token = (payload or {}).get("access_token", "")
        ttl = float((payload or {}).get("expires_in", 900) or 900)
    except Exception as exc:  # noqa: BLE001
        logger.warning("[virbius_guard] evaluate-token exchange failed: %s", exc)
        return ""
    with _TOKEN_LOCK:
        _TOKEN_CACHE[license_jwt] = (token, now + max(1.0, ttl * 0.8))
    return token


def guard_prompt(content, session_id="ctf:5", user_id="ctf-player", license_jwt=""):
    """调用 virbius-engine /v1/evaluate，返回拦截判定与信号详情。

    返回 dict：
      - blocked   : 是否拦截（effectiveAction 为 block/deny 且 enforceMode 为 full）
      - action    : 原始 effectiveAction
      - enforce   : 原始 enforceMode
      - risk_score: maxRiskScore
      - rule_id   : 命中主规则
      - reason    : reasonCode（如 llm:Jailbreak）
      - error     : 调用失败时为错误信息，其余为 None

    P0-min 认证：当提供 license_jwt（或配置 VIRBIUS_EVAL_LICENSE_JWT）且
    VIRBIUS_CONTROL_URL 可用时，先用 license 换取短 token 再调引擎；否则不带
    认证头（适用于引擎 auth 关闭的环境）。
    """
    engine_url = settings.get("VIRBIUS_ENGINE_URL")
    control_url = settings.get("VIRBIUS_CONTROL_URL", "")
    license_jwt = license_jwt or settings.get("VIRBIUS_EVAL_LICENSE_JWT", "")
    # engine 的 EvaluateRequestDto 期望 snake_case 字段（tenant_id/session_id/user_id），
    # 不能用 camelCase，否则 tenant_id 反序列化为 null，PromptRunner 按租户查不到 prompt 规则。
    payload = {
        "tenant_id": "default",
        "session_id": session_id,
        "content": content,
        "role": "input",
        "user_id": user_id,
    }
    headers = {}
    token = _evaluate_token(license_jwt, control_url)
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        r = requests.post(f"{engine_url}/v1/evaluate", json=payload, headers=headers, timeout=30)
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
