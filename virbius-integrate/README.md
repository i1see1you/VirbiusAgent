# virbius-integrate

VirbiusAgent 原生工具集成插件（P0）：**看得见、能审计、可盘点**——advisory 级。

> 完整集成指南（模式 A 观测 / 模式 B 执法、覆盖分级与警示、上报/查询接口、存储与信任边界）见 [../NATIVE_TOOL_INTEGRATION.zh.md](../NATIVE_TOOL_INTEGRATION.zh.md)。本插件是**模式 A**；需要**执法**时叠加**模式 B**（[virbius-mcp-python](../virbius-mcp-python/README.md) 或 MCP 代理）。

## 能力

| 能力 | 机制 |
|---|---|
| 看得见 | LangChain 官方 `BaseCallbackHandler`，两点事件（`started` 立即发 + `completed`/`error` 补充，`call_id=run_id`），有界安全捕获 |
| 能审计 | HTTP 批量 → control 独立通道 `tb_tool_observation`（不触碰审计流与 Hash Chain），分级丢弃保 `started`、丢弃可见 |
| 可盘点 | 规则分级器（warn-only）+ 工具注册表上报（B1）+ doctor 自证 + 心跳 liveness |
| 可自证 | 启动握手（可达/鉴权/路由，可选深校验摄取往返）+ 首帧摘要日志（flushed/dropped） |

## 接入（构造期一行）

```python
import os
from virbius_integrate import VirbiusAuditCallback

agent = create_react_agent(llm, tools, callbacks=[VirbiusAuditCallback(
    app_id="crm-agent",
    tenant_id="acme",
    endpoint="http://virbius-control:8080",
    token=os.environ["VIRBIUS_INTEGRATE_TOKEN"],
    tools=tools,
)])
```

## 环境变量

| 变量 | 用途 |
|---|---|
| `VIRBIUS_INTEGRATE_TOKEN` | 摄取端点 token（control `integrate.ingest.token` 同值） |
| `VIRBIUS_INTEGRATE_ENDPOINT` | control 基址（可替代构造参数） |

## 工具清单与分级

```python
from virbius_integrate import report_tools, classify_tool

# 上报工具清单（规则分级 + recommended_mode + bound_mode="A"）
report_tools(tools, config, mcp_names={"read_file": "mcp.read_file"})

# 单个工具分级（不落库，本地调用）
classify_tool("delete_records", "irreversible delete", ["sql"])
# -> {"risk_hint":"high", "matched_rules":[...], "confidence":"high",
#     "recommended_mode":"B", "rule_table_version":"integrate-rules-v1"}
```

`recommended_mode = "B" if risk ∈ {high, medium} else "A"`。控制台"覆盖与清单"会对
`recommended_mode=B` 却 `bound_mode=A` 的工具给出**警示（warn-only）**。

## 启动握手与首帧日志

- **启动握手**：接入后自动校验 `endpoint` 可达 + `token` 有效 + `tenant/app` 路由（`heartbeat`），
  并由一条日志给出结论；`handshake_deep=True` 时额外做一次摄取往返（探针事件写入并可读回）。
  - `strict=True`：**同步**握手，失败抛 `VirbiusIntegrateError`（启动即暴露问题）。
  - 默认（`strict=False`）：**后台**握手，只 `WARNING`，不阻断业务。
  - `callback.handshake()` 可随时取回结果；`handshake=False` 关闭。
  ```
  INFO  virbius-integrate handshake ok: app=crm-agent tenant=acme control=http://… token=valid ingest=unverified (23ms)
  WARN  virbius-integrate handshake FAILED stage=auth status=401 reason=invalid integrate token; events will be dropped (best-effort)
  ```
- **首帧摘要日志**：第一次 flush 后打一条（成功 `INFO` / 失败 `WARNING`），之后按心跳间隔汇总，
  仅在有丢弃或投递失败时升为 `WARNING`；采样丢弃单列 `sampled_out`，不计入 `dropped`。
  ```
  INFO  virbius-integrate first flush ok: flushed=1 accepted=1 dropped_started=0 dropped_completed=0 sampled_out=0 queue_depth=0 app=crm-agent
  WARN  virbius-integrate flush summary: flushed=1024 dropped=3 delivery_failures=1 queue_depth=0 sampled_out=12
  ```
  日志走 Python `logging`（`logger="virbius_integrate"`）：**未配置 logging 时 WARNING+ 经 `logging.lastResort` 打到 stderr**（失败开箱可见），INFO/DEBUG 保持静默直到应用配置。

## doctor

```bash
python -m virbius_integrate.doctor --endpoint http://...:8080 \
  --app-id crm-agent --tenant acme --token $VIRBIUS_INTEGRATE_TOKEN
```

## 边界

见 [LIMITATIONS.md](LIMITATIONS.md)。**本插件为纯观测（advisory）：不阻断、不执法；事件为 claim 级、不进 Hash Chain、不进 session 风险。**

需要**执法**（前置拦截）时，请对风险工具叠加**模式 B**：[virbius-mcp-python](../virbius-mcp-python/README.md)（进程内预检）或 [MCP 代理](../PROXY_CONFIG.zh.md)（强隔离）。详见 [../NATIVE_TOOL_INTEGRATION.zh.md](../NATIVE_TOOL_INTEGRATION.zh.md) §2。
