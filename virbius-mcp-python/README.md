# virbius-mcp-python

`virbius-core`（Rust 端层引擎）的 **Python 绑定**（PyO3），把 Virbius 的进程内端层
防护原语暴露给 Python —— 对应部署文档里的「**方式 3：SDK 嵌入**」。

> 命名说明：虽然叫 `mcp-python`，它**不是 MCP Server**，而是给框架集成（LangChain、
> OpenAI SDK 等）用的**进程内**能力绑定。

## 能力（`import virbius_mcp_python`）

| 组 | 函数 | 作用 |
|---|---|---|
| 规则/生命周期 | `configure_rules` / `reload_rules` | 加载 edge manifest（offline/control/cloud）；热更新 |
| 工具预检 | `precheck_tool` / `verify_license` | License allowlist + args schema 预检；License 校验 |
| 内容扫描 | `scan_edge` | 端层规则扫描 → `action ∈ allow/block/review/challenge` |
| Prompt/DLP | `enhance_prompt` / `desensitize` / `restore` | 宪法注入、PII 输入脱敏与输出还原 |
| 记忆防护 | `intercept_memory_write` / `intercept_memory_read`（+ `is_memory_*_tool`） | 长时记忆写入/读取拦截（凭证/PII/投毒） |

## 安装

```bash
# 从源码（开发）
pip install maturin
maturin develop --release          # 在本目录执行

# 构建 wheel
maturin build --release -o dist
pip install dist/*.whl
```

## 快速开始

```python
import json
import virbius_mcp_python as v

# 1) 装载规则（offline = 读本地 manifest；control/cloud = 从运营台拉取并缓存）
v.configure_rules(json.dumps({
    "offline_manifest_path": "./edge-manifest.json",
    "cache_dir": "./.virbius-edge-cache",
    "tenant_id": "acme",
    "app_id": "crm-agent",
}))

# 2) 工具调用前置预检（License allowlist + args schema）
jwt = open("agent-license.jwt").read()
pem = open("license_pub.pem").read()
r = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, "crm-agent")
if not r["allowed"]:
    raise PermissionError(r["reason"])
```

框架接入（LangChain 工具包装）见 [`examples/langchain_precheck.py`](../examples/langchain_precheck.py)。

## 模式

- **offline**：`offline_manifest_path` 指向本地 manifest，无网络依赖（测试/内网）。
- **control / cloud**：`control_base_url` + `app_id`（+ `edge_api_key`），从运营台拉取并缓存到 `cache_dir`；拉取失败回退到最后缓存的 manifest。

## 边界

- **这是"软边界"（defense-in-depth）**：进程内调用，进程被攻陷即可绕过。
- 高危工具请走 **MCP Proxy**（强隔离，独立进程）。
- 与本仓 `virbius-integrate`（LangChain 观测插件）的分工：后者只做**可见性/审计（advisory）**，不做拦截；本模块提供**可拦截**的预检原语。

## 相关文档

- 集成总览（模式 A 观测 / 模式 B 执法、覆盖分级与警示、推荐流程）：[../NATIVE_TOOL_INTEGRATION.zh.md](../NATIVE_TOOL_INTEGRATION.zh.md)
- 模块 [virbius-integrate](../virbius-integrate/README.md)（模式 A 观测插件）
- 部署方式 3（SDK 嵌入）：[USAGE_GUIDE.zh.md §3.3](../USAGE_GUIDE.zh.md)

## 测试

```bash
pip install -e ".[test]"      # 或先构建并安装 wheel
pytest tests -q
```

夹具（`tests/fixtures/`）由 `scripts/mint_test_license.py` 生成（依赖 `openssl`，无需 Python 加密库）。
