# virbius-mcp-python

`virbius-core`（Rust 端层引擎）的 **Python 绑定**（PyO3），把 Virbius 的进程内端层
防护原语暴露给 Python —— 对应部署文档里的「**方式 3：SDK 嵌入**」。

> 命名说明：虽然叫 `mcp-python`，它**不是 MCP Server**，而是给框架集成（LangChain、
> OpenAI SDK 等）用的**进程内**能力绑定。

## 能力（`import virbius_mcp_python`）

| 组 | 函数 | 作用 |
|---|---|---|
| 规则/生命周期 | `configure_rules` / `reload_rules` | 加载 edge manifest（offline/control/cloud）；热更新 |
| 框架无关入口 | `govern(fn)` / `VirbiusGuard` | 包装任意 callable 或框架工具，逐调用预检；拒绝抛 `VirbiusDenied`（`from_decision` 可带结构化判定 + 可选 `audit_ref`） |
| 工具预检 | `precheck_tool` / `verify_license` | License allowlist + args schema 预检（`VirbiusDecision`）；License 校验 |
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

## govern（框架无关的门面，最快）

不绑定任何 agent 框架：`govern(fn)` 包装**任意** callable，每次调用前先预检，拒绝抛
`VirbiusDenied`。凭证默认读环境变量（`from_env()`，惰性且缓存），也可显式传 `guard=`：

```python
from virbius_mcp_python import govern

safe_search = govern(my_search)                 # 工具名 = 函数名
safe_read   = govern(my_read, name="read_file") # 或指定名字

@govern(name="read_file")                        # 装饰器：裸用或带 name
def read(path: str) -> str: ...

# 显式凭证（等价于默认的环境变量路径）：
# safe = govern(my_tool, guard=v.VirbiusGuard.from_env())
```

`async def` 同样支持：预检在 `await` **之前**执行。下面的 OpenAI / LangChain 适配器即基于
它做薄封装——只用标准库函数时，`govern` 就是全部。

## VirbiusGuard（有状态封装 + 框架适配器）

裸 `precheck_tool` 每次调用都要传 `(tool_name, args_json, jwt, pem, app_id)`。
`VirbiusGuard` 把凭证装进对象，读与 MCP proxy **同名**的环境变量，并提供一行式框架适配：

```python
import virbius_mcp_python as v

guard = v.VirbiusGuard.from_env()
#   读 VIRBIUS_LICENSE_FILE（或 VIRBIUS_LICENSE_JWT 内联）
#      + VIRBIUS_LICENSE_PUBLIC_KEY（路径或内联 PEM）
#      + VIRBIUS_APP_ID [, VIRBIUS_TENANT_ID]

guard.guard("read_file", {"path": "/tmp/a"})     # 拒绝时抛 VirbiusDenied（PermissionError 子类，带 reason_code）
guard.precheck("read_file", {...})               # -> VirbiusDecision（d.allowed / d.reason_code；也支持 d["allowed"]）

@guard.openai_tool          # OpenAI Agents SDK：装饰完即是受管控的 FunctionTool，
def read_file(path: str) -> str:   # 原始签名/文档串自动成为工具 schema
    """Read a document from disk."""
    ...

tools = guard.openai_tools([read_file, search])   # 一次包装一整组（批量版）

@guard.langchain_tool       # LangChain：即受管控的 StructuredTool
def search(query: str) -> str: ...

lc_tools = guard.langchain_tools([read_file, search])   # LangChain 批量版

@guard.guarded              # 任意框架/裸函数通用装饰器（等价 govern(fn, guard=guard)）
def ping(host: str) -> str: ...
```

两个框架适配器（OpenAI Agents SDK / LangChain）与 `guarded` 都是对 `govern` 的薄封装：**同一套
enforcement 核心**，只是额外补上框架各自的工具 schema 与拒绝透传（OpenAI 的 `FunctionTool` /
LangChain 的 `StructuredTool` + `ToolException`）。

### 类型化结果与结构化拒绝

`precheck()` 返回 **`VirbiusDecision`**（对齐 AGT 的 `evaluation`/`verdict`）：属性优先
（`d.allowed` / `d.reason_code` / `d.fast_path` / `d.sandbox_type`），同时保留下标与 `get`
（`d["allowed"]`），`d.to_dict()` 可序列化。拒绝则经 **`VirbiusDenied.from_decision(decision, *, audit_ref=None)`**
构造，携带结构化 `decision` 与可选 `audit_ref`（`exc.audit_record()`）。进程内 `precheck` **不**同步产出
审计记录，故 `audit_ref` 由调用方按需传入（如 `trace_id`/`session_id`）。

### 自检（doctor）

接入前/出问题时一条命令看全：6 个环境变量是否齐、license 是否有效、能看到哪些工具、engine 是否可达。

```bash
python -m virbius_mcp_python doctor            # 人读输出；未就绪退出码 1
python -m virbius_mcp_python doctor --engine   # 附带 TCP 探测 VIRBIUS_ENGINE_URL
python -m virbius_mcp_python doctor --json     # 机器可读
```

程序内等价物：`v.diagnose()` → dict（`report["ok"]` 表示是否就绪）——**坏配置不抛异常**，正是
它该跑的时候。

### 让拦截结果对模型可见

Agents SDK / LangChain 默认会把工具报错藏成通用文案（"出错了请重试"），会埋掉"为什么被拦"。
两个适配器都已内置处理：

- **OpenAI Agents SDK**：`guard.openai_tool` / `guard.openai_tools([...])` 自动挂 `openai_tool_error`
  —— 拒绝抛 `VirbiusDenied`（`PermissionError` 子类，带 `reason_code`，并携带结构化 `decision` 与可选 `audit_ref`）并把人话透给模型；
  **其他异常**回落通用文案，不泄漏工具内部错误。
- **LangChain**：`guard.langchain_tool` / `guard.langchain_tools([...])` 把拒绝转成 `ToolException` +
  `handle_tool_error=langchain_tool_error`，模型读到**同一句**人话；其他异常照常上抛，不泄漏。
- **proxy（MCP）模式**：proxy 已为每个错误产出**人话 `message`**（机器码在 `data.reason_code`）。
  Agents SDK 默认会把它替换掉，传入**透传** formatter 即可原样揭示：

```python
from virbius_mcp_python import virbius_tool_error
# MCPServerSse({"url": url}, failure_error_function=virbius_tool_error)
```

模型会看到如 `The tool call was blocked by policy: tool 'x' not in license allowlist.`——事实性
陈述，据此改道而非空转。

**formatter 按框架/模式命名（用了适配器就自动生效，你不必直接调）**：

| 场景 | formatter | 谁调用 |
|---|---|---|
| OpenAI Agents SDK | `openai_tool_error` | **自动**：`guard.openai_tool(s)` 内置 |
| LangChain | `langchain_tool_error` | **自动**：`guard.langchain_tool(s)` 内置 |
| proxy（MCP） | `virbius_tool_error` | **你**：`MCPServerSse({...}, failure_error_function=virbius_tool_error)` |

三者语义一致：原样给出**我们**写好的拒绝消息（proxy 是代理产出的人话；两个适配器是把
`VirbiusDenied` 显出），其余异常一律回落通用文案，绝不泄漏工具内部错误。用 `guard.*_tools([...])`
时 formatter 选择被完全隐去。

拒绝文案由 `virbius_mcp_python.policy_blocked_message(reason)` **单点构造**（`guard()` 构造；
A/B 脚本直接驱动真实 `VirbiusGuard`，文案与 reason 都不会漂移）。要不要换掉 SDK 默认文案？有实测支撑——同一 Agent、温度 0，
仅改文案：SDK 默认的"请重试"会诱导模型**重试策略拒绝**，Virbius 文案不会。复现：
`bash scripts/test-error-wording-ab.sh --trials 6`（无 key 时 SKIP，退出 77；`--self-test` 免调用）。

完整样例见 [`examples/oss-agent-target/`](../examples/oss-agent-target/)（OpenAI Agents SDK
靶 Agent，同一 Agent 验证 proxy / SDK 两种接入方式）。

框架集成（LangChain 工具包装）另见 [`examples/langchain_precheck.py`](../examples/langchain_precheck.py)。

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
