# 原生工具集成（模式 A 观测 / 模式 B 执法）

> 一句话：`virbius-integrate` 提供**模式 A（观测）**——"看得见、能审计、可盘点"；需要**执法**时上**模式 B**（SDK 前置预检或 MCP 代理）。建议流程：**先 A 打底，再对风险工具叠加 B**。

本文覆盖 LangChain 原生工具的两条接入路径、覆盖分级与警示、上报/查询接口、存储与保留、以及可观测性边界。MCP 代理与 SDK 的部署细节见 [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3 与 [PROXY_CONFIG.zh.md](PROXY_CONFIG.zh.md)。

---

## 1. 两种模式

| | 模式 A：观测（advisory） | 模式 B：执法（enforce） |
|---|---|---|
| 实现 | `virbius-integrate`（LangChain `BaseCallbackHandler`） | `virbius-mcp-python`（SDK `precheck`）/ `virbius-mcp-proxy` |
| 接入方式 | 构造期一行 `callbacks=[VirbiusAuditCallback(...)]` | 用 `govern(fn)` / `guard.precheck` 包装工具（拒绝抛 `VirbiusDenied`），或经代理 |
| 能力 | 看得见、能审计、可盘点；风险分级 + 推荐模式 | 前置拦截：License allowlist、参数 schema、Guard 裁决 |
| 是否阻断 | **否**（不阻断、不执法） | **是** |
| 进审计链 | 否（独立通道，claim 级） | 是（走审计 / 决策链） |
| Agent 代码变更 | 一行 | 工具调用点改造（SDK）；或零变更（代理） |

> **术语澄清**：这里的"模式 A/B"是**接入**轴（观测 vs 执法）；[DEPLOYMENT.zh.md](DEPLOYMENT.zh.md) §8.3 的"模式 A/B"是**部署**轴（Sidecar vs 远程）。两者独立，勿混用。

---

## 2. 建议的落地流程

1. **模式 A 打底**：一行接入 `virbius-integrate` → 得到工具清单、风险分级、观测时间轴与集成健康。
2. **看覆盖警示**：控制台"覆盖与清单"会标出 `recommended_mode=B` 却 `bound_mode=A` 的工具。
3. **对风险工具叠加模式 B**：用 `virbius-mcp-python`（或 MCP 代理）对该工具执法。
4. **A 不撤**：上线 B 之后保留 A，用于审计留痕与持续盘点。

**四条要点**：

- **A 不是安全网**：A 阶段高危工具仍**全量可执行**。A→B 之间是无保护窗口——高危工具不要停在 A。
- **不是切换，而是叠加**：A 给可见性（审计/盘点），B 给拦截，两者互补。
- **B 补 A 的盲区**：A 是协作式钩子（直调 `tool.func`、非 callback 的副作用不可见）；B 拦在执行边界。
- **分级是启发式、warn-only**：用于**优先级排序**，不是自动切换依据。

---

## 3. 模式 A：LangChain callback 观测接入

### 3.1 安装与接入

从本仓安装后，在 Agent 构造期一行接入：

```bash
pip install ./virbius-integrate
```

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

两点事件：`on_tool_start`（`started`，立即发）与 `on_tool_end` / `on_tool_error`（`completed`/`error`，补充），`call_id = run_id`。

### 3.2 配置（`IntegrateConfig`）

| 字段 | 默认 | 说明 |
|---|---|---|
| `app_id` / `tenant_id` | 必填 | 上报路由键 |
| `endpoint` | 必填 | control 基址（可取自 `VIRBIUS_INTEGRATE_ENDPOINT`） |
| `token` | `""` | 上报 token（可取自 `VIRBIUS_INTEGRATE_TOKEN`） |
| `device_id` | `$HOSTNAME` | 设备标识（L1 关联键） |
| `strict` | `False` | 启动期同步握手，失败 raise（默认后台握手只告警） |
| `handshake` | `True` | 启动握手：校验可达/鉴权/`tenant/app` 路由 |
| `handshake_deep` | `False` | 深握手：额外做一次摄取往返（探针事件写入并读回） |
| `sample_rate` | `1.0` | 采样率 `[0,1]` |
| `heartbeat_interval` | `60.0` | 心跳间隔（秒） |
| `batch_size` / `flush_interval` | `50` / `0.5` | 批量与刷写间隔 |
| `queue_size` | `512` | 上报队列上限 |
| `max_repr` | `2048` | args/result 有界截断长度 |
| `record_args` | `True` | 是否记录参数预览 |
| `spill` | `False` | 本地落盘缓冲（默认关） |
| `mode` | `"observe"` | 观测模式 |

环境变量：`VIRBIUS_INTEGRATE_ENDPOINT`、`VIRBIUS_INTEGRATE_TOKEN`（与 control `virbius.integrate.ingest.token` 同值）。

### 3.3 工具清单与分级

```python
from virbius_integrate import report_tools, classify_tool

report_tools(tools, config, mcp_names={"read_file": "mcp.read_file"})  # 上报清单（含分级）
classify_tool("delete_records", "irreversible delete", ["sql"])
# -> {"risk_hint":"high", "matched_rules":[...], "confidence":"high",
#     "recommended_mode":"B", "rule_table_version":"integrate-rules-v1"}
```

分级规则：命中高危动词 / 凭证类参数 / 破坏性描述 → `high`；破坏性/外联/敏感数据 schema → `medium`；否则 `low`。`recommended_mode = "B" if risk ∈ {high, medium} else "A"`。

### 3.4 doctor（接入自证）

```bash
python -m virbius_integrate.doctor --endpoint http://virbius-control:8080 \
  --app-id crm-agent --tenant acme --token $VIRBIUS_INTEGRATE_TOKEN
```

检出"未注册 callback / 端点不可达 / token 失效"等接入问题（**拦不住**故意移除钩子）。

### 3.5 会话标注

`session(session_id)` / `current_session()`：给观测事件打 `session_id`（thread_id），便于按会话聚合时间轴。

### 3.6 启动握手与首帧摘要日志

- **启动握手**：接入后自动校验 `endpoint` 可达 + `token` 有效 + `tenant/app` 路由，日志给出结论。
  `strict=True` 时**同步**执行、失败抛 `VirbiusIntegrateError`（启动即暴露）；默认**后台**执行、仅告警不阻断。
  `handshake_deep=True` 时额外做摄取往返（探针事件 `source=langchain-healthcheck` 写入并读回）。
  ```
  INFO  virbius-integrate handshake ok: app=crm-agent tenant=acme control=http://… token=valid ingest=unverified (23ms)
  WARN  virbius-integrate handshake FAILED stage=auth status=401 reason=invalid integrate token; events will be dropped (best-effort)
  ```
- **首帧摘要日志**：首次 flush 后打一条（成功 `INFO`/失败 `WARNING`），其后按心跳间隔汇总，仅当有丢弃或投递失败才升 `WARNING`；采样丢弃单列 `sampled_out`。
  ```
  WARN  virbius-integrate flush summary: flushed=1024 dropped=3 delivery_failures=1 queue_depth=0 sampled_out=12
  ```
- 日志走 Python `logging`（`logger="virbius_integrate"`）：未配置 logging 时 WARNING+ 经 `logging.lastResort` 输出到 stderr（失败开箱可见），INFO/DEBUG 需应用配置。

---

## 4. 模式 B：执法接入

`virbius-mcp-python`（PyO3 绑定 `virbius-core`）在工具执行边界做前置预检，详见 [virbius-mcp-python/README.md](virbius-mcp-python/README.md)。

```python
import json
import virbius_mcp_python as v

v.configure_rules(json.dumps({
    "offline_manifest_path": "/etc/virbius/edge-manifest.json",
    "cache_dir": "./.virbius-edge-cache",
    "app_id": "crm-agent",
}))

r = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, "crm-agent")
if not r["allowed"]:
    raise PermissionError(r["reason"])
```

> **推荐封装**：框架无关的 `govern(fn)` 可包装任意 callable（无需框架；拒绝抛 `VirbiusDenied`）；
> `VirbiusGuard.from_env()` 把 license 材料装进对象（读取与 proxy 同名的环境变量），不必每次调用
> 都传 `jwt/pem/app_id`；一行式框架适配为 `@guard.openai_tool` / `@guard.langchain_tool` /
> `@guard.guarded`（见 [virbius-mcp-python/README.md](virbius-mcp-python/README.md)）。

- 校验顺序：**License allowlist →（有 `tool_policy` 时）参数 schema → 快速通道/沙箱标记**。
- 规则来源：`configure_rules` 支持 **offline（本地 manifest）** 与 **control/cloud（从控制面拉取并缓存）**；`reload_rules()` 重新拉取。
- **零代码方案**：`virbius-mcp-proxy`（见 [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3.1、[PROXY_CONFIG.zh.md](PROXY_CONFIG.zh.md)）。

---

## 5. 覆盖分级与警示

- 分级器输出 `recommended_mode`（high/medium→B，否则 A）与 `bound_mode`（当前由哪条路径接入，模式 A 记为 `"A"`）。
- 控制台"覆盖与清单"对 `recommended_mode=B && bound_mode=A` 的工具出**警示（warn-only）**："推荐强模式却绑定轻量模式"。
- 处置：把该工具改用模式 B（SDK 或代理），或人工 override 分级并留档。

---

## 6. 数据与可观测

### 6.1 控制台

运营台 `/integrate`「原生工具集成」：选择 app 后三个页签——**观测时间轴**（按 `call_id` 聚合 `started → completed/error`，可叠加 B 侧审计抽样事件）、**覆盖与清单**、**集成健康**（设备 `live`/`stale`/`never-seen`）。

### 6.2 上报通道（插件 → control，内部）

基址 `/api/v1/internal/integrate/tenants/{tenantId}/apps/{appId}`：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/tool-observations/batch` | body `{ "events": [...] }`，单批 ≤ `virbius.integrate.ingest.max-batch-size`；返回 `{ "accepted": N }` |
| POST | `/tool-inventory` | body `{ "tools": [...] }`；返回 `{ "accepted": N }` |
| POST | `/heartbeat` | body `{ device_id, package_version, mode, dropped_total, ts }` |
| GET | `/tool-observations` | 查询（`call_id`/`since`/`until`/`limit`） |
| GET | `/liveness` | 设备存活 |
| GET | `/coverage` | 覆盖清单 + 警示 |

鉴权：`Authorization: Bearer <token>` 或 `X-Virbius-Integrate-Token`；control 侧 `virbius.integrate.ingest.token` 为空则不校验。

### 6.3 查询通道（控制台 / 管理）

基址 `/api/v1/admin/tenants/{tenantId}/integrate`：`GET /apps`、`GET /apps/{appId}/observations`、`GET /apps/{appId}/coverage`、`GET /apps/{appId}/liveness`。

### 6.4 存储与保留

三张独立表（迁移 `V12__tool_observation.sql`）：

- `tb_tool_observation`：两点事件（`event_id` 去重键、`call_id` 串联、`phase ∈ {started,completed,error}`）。
- `tb_tool_inventory`：工具清单（含 `risk_class`、`recommended_mode`、`bound_mode`）。
- `tb_obs_app_liveness`：设备心跳 / 最近事件 / 丢弃计数。

时间戳为 RFC3339 字符串（与审计路径一致，支持字典序范围查询）。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `virbius.integrate.ingest.token` | 空 | 上报鉴权 token（空 = 不校验） |
| `virbius.integrate.ingest.max-batch-size` | 100 | 单批事件上限 |
| `virbius.integrate.retention-days` | 14 | 观测事件保留天数 |
| `virbius.integrate.retention.cron` | `0 0 3 * * *` | 清理任务 cron |
| `virbius.integrate.liveness.heartbeat-interval-secs` | 60 | 心跳判活阈值（×2） |
| `virbius.integrate.liveness.event-window-secs` | 300 | 事件判活窗口 |

存活状态：`live`（心跳 ≤ 2×间隔 或 最近事件 ≤ 窗口）、`stale`、`never-seen`。

---

## 7. 限制与信任边界（模式 A）

- **纯观测**：不阻断、不执法。
- **claim 级**：事件由被观测进程自身生成，进程被攻陷可伪造/抑制；token 只校验"谁能写"，不证明"是谁"。
- **无链完整性**：不进 Hash Chain、不进 session 风险，不作为权威聚合或阻断决策的输入。
- **协作式覆盖**：`callbacks=False`、未注册 config、直调 `tool.func`、非工具副作用、spawn 子进程 → **不可见**。
- **best-effort 投递**：队列 512，满时优先丢 `completed/error`、保 `started`；丢弃计数随包络上报并在 doctor/控制台可见。
- **分级为 advisory**：误报需人工 override；执法用途须人工确认。

完整边界见 [virbius-integrate/LIMITATIONS.md](virbius-integrate/LIMITATIONS.md)。

---

## 8. 相关文档

- 模块：[virbius-integrate/README.md](virbius-integrate/README.md)、[virbius-mcp-python/README.md](virbius-mcp-python/README.md)
- 部署 / 代理：[USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) §3、[PROXY_CONFIG.zh.md](PROXY_CONFIG.zh.md)
- 架构：[ARCHITECTURE.zh.md](ARCHITECTURE.zh.md) §2.6
- 协议：[PROTOCOL.zh.md](PROTOCOL.zh.md)
