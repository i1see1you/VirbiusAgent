# 接入快速开始（5 分钟）

VirbiusAgent 支持 **三种集成模式**。按部署形态选，然后照抄片段。细节仍在
[PROXY_CONFIG.md](PROXY_CONFIG.md)、[USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3、
[virbius-mcp-python/README.md](virbius-mcp-python/README.md)。

## 选哪种?

| 维度 | **模式 1：MCP Proxy**（sidecar） | **模式 2：Higress**（远程网关） | **模式 3：SDK 嵌入**（进程内） |
|---|---|---|---|
| 接入工作量 | 跑一个进程；**无需逐工具代码** | 在网关上部署 WASM 插件；**无需工具代码**，但需让 Agent 走网关 | 每个工具一个装饰器 |
| 需要的设施 | proxy 进程（审计可选 Redis/Kafka）+ 你的上游 MCP | Higress 网关（+ 可达的 control/engine） | 除绑定外无 |
| 凭证位置 | **只在 proxy**——Agent 从不接触 license | 在网关插件配置里（control 签发） | 在你的应用进程里（env） |
| 隔离强度 | **强**（独立进程） | **强**（集群内、在路径上） | 软边界 / defense-in-depth（宿主被攻陷可绕过） |
| 流量 | 东西向（localhost） | 南北向（HTTPS 入口） | 进程内调用 |
| 延迟（快路径） | ~2ms | ~5ms | **<0.5ms** |
| 工具可见性 | 非允许工具被**过滤掉** | 工具**可见**；被拒调用返回结构化 MCP 错误 | 工具**可见**；被拒调用抛错 |
| 适用 | 多数用户；高危工具；不便改动的 Agent | 走集群入口的远程/SaaS Agent | 最快接入、进程内工具、最低延迟 + prompt/PII 能力 |

一句话：**能跑 sidecar → 模式 1。** 流量走**集群入口** → 模式 2。只想改**代码**、要最低延迟 → 模式 3。

三种模式都需要 **license**：proxy 的 `enroll` 帮你取，网关从 control 取，SDK 嵌入要你自己放 license 文件与公钥。

## 模式 1 — MCP Proxy（sidecar）

```bash
# 1. 一次性：取凭证 + 自检（admin 凭证仅走环境变量）
VIRBIUS_ADMIN_API_KEY=... virbius-mcp-proxy enroll \
  --control http://control:8080 --tenant acme --app-id crm-agent \
  --tools read_file,search --out-dir /etc/virbius
VIRBIUS_BASE_URL=http://control:8080 virbius-mcp-proxy doctor

# 2. 运行 proxy（stdio 或 SSE），置于 Agent 与其 MCP 工具之间
```

```python
from agents import Agent
from agents.mcp import MCPServerSse
from virbius_mcp_python import virbius_tool_error  # 原样揭示 proxy 的拒绝文案

server = MCPServerSse({"url": "http://127.0.0.1:9090/sse"},
                      failure_error_function=virbius_tool_error)
agent = Agent(name="app", instructions="...", model=model, mcp_servers=[server])
```

无需逐工具代码：allowlist、策略、审计都在 proxy 里，模型只看到被允许的工具。
完整配置见 [PROXY_CONFIG.md](PROXY_CONFIG.md)。

## 模式 2 — Higress 网关（远程）

适用于走集群入口的远程/SaaS Agent。执行点是网关上的 **WASM 插件**：

```yaml
apiVersion: extensions.higress.io/v1alpha1
kind: WasmPlugin
metadata:
  name: virbius-gateway
  namespace: higress-system
spec:
  defaultConfig:
    control_base_url: "http://virbius-control:8080"
    tenant_id: "default"
    evaluate: true
    rate_limit: true
    license_verify: true
    tool_precheck: true
  url: oci://registry.example.com/virbius-gateway-wasm:v1.0.2
```

请求经网关转发，网关在路径上预检每次工具调用并审查内容（拒绝时返回 HTTP 403 +
结构化 JSON-RPC 错误）。与 proxy 不同，它**不重写工具列表**，因此在 Agent 的 MCP
客户端上同样挂 `failure_error_function=virbius_tool_error`，把可读的拒绝理由透给模型
（而非让它只看到一个不透明错误）。见 [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3.2。

## 模式 3 — SDK 嵌入（进程内）

```bash
# 1. 安装绑定（或装 wheel）
pip install maturin && ( cd virbius-mcp-python && maturin develop --release )

# 2. 指向你的 license 材料
export VIRBIUS_LICENSE_FILE=/etc/virbius/license.jwt
export VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license_pub.pem
export VIRBIUS_APP_ID=crm-agent

# 3. 接线前先自检：env + license + 工具
python -m virbius_mcp_python doctor
```

```python
import virbius_mcp_python as v

def read_file(path: str) -> str:
    """Read a document."""
    return open(path).read()

# 框架无关——包装任意 callable；每次调用前预检：
safe_read_file = v.govern(read_file)        # 或：在 def 上 @v.govern

# OpenAI Agents SDK 适配器——同一套 enforcement，外加工具 schema：
guard = v.VirbiusGuard.from_env()           # 读上面那组环境变量
agent = Agent(..., tools=guard.openai_tools([read_file, search]))
```

`doctor`（及 `v.diagnose()`）列出 6 个环境变量、license 是否有效、能看到哪些工具，
且**坏配置不抛异常**——正是出问题时该跑的东西。（Rust / C-ABI / 其他语言见
[USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3.3。）

## 唯一需要记住的行为差异

**模式 1（proxy）** 过滤 `tools/list`：非允许工具**根本不在**模型的工具列表里，因此
不可能被调用。

**模式 2（网关）** 和 **模式 3（SDK）** 工具**可见**——网关只在路径上拦截每一次调用
（HTTP 403，JSON-RPC 错误 `-32006`，带 `data.tool_name` / `data.reason`），SDK 则抛
`VirbiusDenied`。两种情况都要挂上对应的 formatter，让拒绝作为**事实**透给模型（使其
改道而非空转）：模式 2 用 `virbius_tool_error`（MCP 客户端），模式 3 用适配器自带的
formatter（`openai_tool_error` / `langchain_tool_error`，`openai_tools` / `langchain_tools`
会自动挂好）。见 [PROXY_CONFIG.zh.md](PROXY_CONFIG.zh.md) 的错误形状一节。

## 延伸阅读

- [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3 — 全部三种模式，逐步说明
- [PROXY_CONFIG.md](PROXY_CONFIG.md) — proxy 配置 + `enroll` / `doctor`
- [virbius-mcp-python/README.md](virbius-mcp-python/README.md) — SDK 嵌入 API
- [NATIVE_TOOL_INTEGRATION.zh.md](NATIVE_TOOL_INTEGRATION.zh.md) — 观测/分类**原生**（非 Virbius）工具
