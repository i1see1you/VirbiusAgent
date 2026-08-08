"""全局配置。密钥从 .env / 环境变量读取（不入库，避免泄露与被自动吊销）。"""
import os
import secrets


def _load_dotenv():
    """极简 .env 加载器（无三方依赖）。"""
    path = os.path.join(os.path.dirname(__file__), ".env")
    if not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


_load_dotenv()

# ---- 被渗透的目标大模型 ----
# DeepSeek 直连（OpenAI 兼容接口）
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "sk-REPLACE-ME")

# OpenRouter 聚合（国产 + 国外众多小模型走这里）
OPENROUTER_API_KEY = os.environ.get("OPENROUTER_API_KEY", "")
OPENROUTER_BASE_URL = os.environ.get("OPENROUTER_BASE_URL", "https://openrouter.ai/api/v1")

# 本地模型（Ollama，OpenAI 兼容端点，无需鉴权）
OLLAMA_BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434/v1")
DEEPSEEK_BASE_URL = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com")
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-chat")

# Flask
SECRET_KEY = os.environ.get("SECRET_KEY", secrets.token_hex(16))

# Web 服务端口：固定为 8000（演示/联调时需要稳定地址），可用 PORT 环境变量覆盖
PORT = int(os.environ.get("PORT", "8000"))

# Agent 沙箱：是否走 Docker 容器执行（生产隔离）。本地快速体验可关。
AGENT_USE_DOCKER = os.environ.get("AGENT_USE_DOCKER", "0") == "1"
AGENT_DOCKER_IMAGE = os.environ.get("AGENT_DOCKER_IMAGE", "llm-sec-range-agent-sandbox")
