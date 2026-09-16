"""OpenAI-compatible chat. Optional tools; returns text plus serialized tool calls."""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from pathlib import Path

import settings

ROOT = Path(__file__).resolve().parent


def _load_dotenv() -> None:
    path = ROOT / ".env"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        key, val = key.strip(), val.strip().strip('"').strip("'")
        os.environ.setdefault(key, val)


def completions_url(base: str) -> str:
    b = (base or "").rstrip("/")
    if b.endswith("/chat/completions"):
        return b
    last = b.rsplit("/", 1)[-1]
    if last in {"v1", "v3", "v4"}:
        return b + "/chat/completions"
    return b + "/v1/chat/completions"


def configured() -> dict:
    _load_dotenv()
    stored = settings.load()
    provider = str(stored.get("provider") or "deepseek")
    if provider not in settings.PROVIDERS:
        provider = "deepseek"
    preset = settings.PROVIDERS[provider]
    key = str(stored.get("key") or os.environ.get("SNAPSHOT_API_KEY") or os.environ.get("DEEPSEEK_API_KEY") or "")
    base = str(stored.get("base") or os.environ.get("SNAPSHOT_API_BASE") or preset["base"]).rstrip("/")
    model = str(stored.get("model") or os.environ.get("SNAPSHOT_MODEL") or preset["model"])
    ready = bool(key)
    return {
        "provider": provider,
        "key": key,
        "base": base,
        "model": model,
        "ready": ready,
        "needs_key": preset["needs_key"],
    }


def public_config() -> dict:
    cfg = configured()
    return {
        "provider": cfg["provider"],
        "model": cfg["model"],
        "base": cfg["base"],
        "has_key": bool(cfg["key"]),
        "key_hint": settings.key_hint(cfg["key"]) if cfg["needs_key"] else "",
        "ready": cfg["ready"],
        "needs_key": cfg["needs_key"],
        "providers": [
            {
                "id": pid,
                "label": spec["label"],
                "base": spec["base"],
                "model": spec["model"],
                "needs_key": spec["needs_key"],
            }
            for pid, spec in settings.PROVIDERS.items()
        ],
    }


def chat(
    system_prompt: str,
    user_text: str,
    timeout: int = 60,
    tools: list[dict] | None = None,
    temperature: float = 0.7,
    tool_choice: str = "auto",
) -> str:
    from tools_spec import format_tool_calls

    cfg = configured()
    if not cfg["key"]:
        raise RuntimeError("还没配模型。打开「设置」选类型并填 Key 后保存")
    url = completions_url(cfg["base"])
    payload = {
        "model": cfg["model"],
        "temperature": temperature,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_text},
        ],
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = tool_choice
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {cfg['key']}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")[:400]
        raise RuntimeError(f"模型接口 {e.code}: {detail}") from e
    except urllib.error.URLError as e:
        raise RuntimeError(f"模型接口连不上: {e.reason}") from e
    try:
        return format_tool_calls(data["choices"][0]["message"])
    except (KeyError, IndexError, TypeError) as e:
        raise RuntimeError("模型返回格式不对") from e
