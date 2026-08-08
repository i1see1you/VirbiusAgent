"""多提供商 LLM 调用封装。OpenAI 兼容 /chat/completions。
provider: 'deepseek'(直连) | 'openrouter'(聚合众多国产/国外小模型)。"""
import requests
import config

_SESSION = requests.Session()
# 本地推理专用 session：完全忽略环境代理（避开 Clash SOCKS 劫持 localhost）
_LOCAL_SESSION = requests.Session()
_LOCAL_SESSION.trust_env = False


class LLMError(Exception):
    pass


def _provider_conf(provider):
    if provider == "openrouter":
        return (config.OPENROUTER_BASE_URL + "/chat/completions",
                config.OPENROUTER_API_KEY,
                {"HTTP-Referer": "http://localhost", "X-Title": "LLM SecRange"})
    if provider == "local":
        # Ollama 本地，无需真实 key
        return (config.OLLAMA_BASE_URL + "/chat/completions", "ollama", {})
    # 默认 deepseek 直连
    return (config.DEEPSEEK_BASE_URL + "/chat/completions",
            config.DEEPSEEK_API_KEY, {})


def chat(messages, temperature=0.7, max_tokens=1024, model=None,
         provider="deepseek", timeout=90):
    """messages: [{"role","content"}]。返回字符串内容。"""
    url, key, extra = _provider_conf(provider)
    # 本地推理较慢，且 R1 蒸馏会先输出思维链，给更高的 token/超时上限
    if provider == "local":
        max_tokens = max(max_tokens, 2048)
        timeout = max(timeout, 240)
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json", **extra}
    payload = {
        "model": model or config.DEEPSEEK_MODEL,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
    }
    # 本地 Ollama 用忽略环境代理的 session（Clash SOCKS 会劫持 localhost）
    sess = _LOCAL_SESSION if provider == "local" else _SESSION
    try:
        r = sess.post(url, json=payload, headers=headers, timeout=timeout)
    except requests.RequestException as e:
        raise LLMError(f"请求 {provider} 失败：{e}")
    if r.status_code != 200:
        raise LLMError(f"{provider} 返回 {r.status_code}: {r.text[:300]}")
    try:
        data = r.json()
        return data["choices"][0]["message"]["content"]
    except (KeyError, IndexError, ValueError) as e:
        raise LLMError(f"解析 {provider} 响应失败：{e}; body={r.text[:300]}")


def ask(system, user, **kw):
    """便捷：单轮 system + user。"""
    msgs = []
    if system:
        msgs.append({"role": "system", "content": system})
    msgs.append({"role": "user", "content": user})
    return chat(msgs, **kw)
