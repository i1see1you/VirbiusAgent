# virbiusDemo · 容器化拆分与运行期配置 PRD

> 版本：v0.1（初稿） · 状态：待评审
> 关联文档：[PRD.md](./PRD.md)（演示平台整体 v0.1）
> 范围本：本 PRD 仅覆盖"demo 容器化拆分"与"demo 运行期配置"两个新增需求，不涉及现有 OWASP/CTF/Catalog/Agent 内容逻辑。

---

## 1. 背景与目标

### 1.1 背景

当前 `virbius-demo` 与 `virbius-agent`（control / engine / redis / mcp-proxy）被编排在**同一个根目录 docker-compose.yml** 中，共享网络、依赖与生命周期。但从职责边界看：

- **virbius-agent**：安全能力**提供方**（平台），向集成方输出防护能力。
- **virbius-demo**：能力**使用方**（集成方），内嵌 virbius-core SDK 作为"被保护的用户 agent"。

两者分属不同所有者与生命周期，应作为**两个独立部署单元**。

同时，demo 当前的大模型凭证（DeepSeek key 等）由部署者通过 `.env` 注入，既存在泄露风险，也缺少运行时灵活配置能力。

### 1.2 目标

1. 将 demo 从与 virbius-agent 的同一编排中拆出，成为**独立部署单元**。
2. 提供**基础设置页**，让用户在 demo 页面配置平台接入参数与大模型凭证，配置持久化到容器卷。
3. 保持**开发阶段同仓库**，demo 继续从 virbius-core 源码构建，core 变更即时同步。

### 1.3 非目标（明确不做）

- 不做 demo 与 virbius-agent 的**仓库拆分**（开发期保持同仓库）。
- 不做 demo **瘦客户端化**（端层 virbius-core SDK 保留，是设计本意）。
- 不做**小模型自动下载**（仅提示用户自主配置）。
- 不做 key 加密（明文存本地即可）。
- 不做复杂设置项（模型清单、关卡内容、flag 等保持配置文件可编辑，不做 Web 设置页）。

---

## 2. 已确认决策（勿再重复确认）

| 项 | 决策 |
|---|---|
| **拆分范围** | demo 与 virbius-agent 拆为两个独立部署单元（独立编排、独立网络边界、独立生命周期） |
| **开发期仓库** | 保持同仓库；demo 从 virbius-core 源码构建，core 变更即时同步 |
| **演进路径** | 本轮只做方案 A（拆 compose 编排），B/C 的"瘦客户端"方向不适用 |
| **端层防护** | 保留（内嵌 virbius-core SDK + 直连 control 拉规则，属 SDK 正当用法） |
| **key 存储** | 容器内 volume，持久化 |
| **key 加密** | 不做，明文 |
| **key 作用域** | 全局一份（单用户演示台） |
| **小模型** | 不做自动下载，仅提示用户配置 |
| **设置页交互** | 先选目标模型 → 联动只渲染该模型的配置表单 |
| **设置页复杂度** | 只做基础设置项，避免过度设计 |

---

## 3. 需求一：容器化拆分

### 3.1 目标形态

- **virbius-agent（平台）**：独立编排，对外暴露稳定接入地址（control / engine 服务名）。
- **virbius-demo（使用方）**：独立编排，通过平台稳定地址接入，不再与平台共享同一 compose 生命周期。

### 3.2 具体做法

1. 将 `virbius-demo` 服务从根目录 `docker-compose.yml` 移出，放入 demo 自己的 compose 文件（`virbius-demo/docker-compose.yml`）。
2. 通过**共享外部网络**与 virbius-agent 平台互连：

```yaml
# virbius-agent（平台侧）：声明可被外部复用的网络
networks:
  virbius-net:
    name: virbius-net
    external: false

# virbius-demo（使用方侧）：加入同一个外部网络
networks:
  virbius-net:
    external: true
```

3. demo 侧 `VIRBIUS_CONTROL_URL` / `VIRBIUS_ENGINE_URL` 指向平台服务名（如 `http://virbius-control:8080`、`http://virbius-engine:8082`）。
4. demo 容器新增一个命名卷，用于持久化运行期配置（见 §4）。

### 3.3 完成标准

- [ ] demo 与 virbius-agent 可各自独立 `docker compose up/down`。
- [ ] demo 通过共享网络访问到平台的 control / engine 服务。
- [ ] demo 从 virbius-core 源码构建，core 变更后重建 demo 镜像即同步最新代码。
- [ ] demo 容器重建后，运行期配置（key 等）不丢失。

---

## 4. 需求二：基础设置页（运行期配置）

### 4.1 目标

提供 demo 页面的设置入口，让用户运行期配置以下项，配置写入容器卷持久化，全局生效。

### 4.2 配置项（基础范围，避免过度设计）

分为两组：

**A. 平台接入**

| 配置项 | 说明 | 现状来源 |
|---|---|---|
| control 地址 | virbius-agent 控制平面地址 | `VIRBIUS_CONTROL_URL` / virbius.json |
| engine 地址 | virbius-agent 引擎地址 | `VIRBIUS_ENGINE_URL` |
| License | 接入凭证 | `VIRBIUS_LICENSE_JWT` |

**B. 模型配置**

| 配置项 | 说明 | 现状来源 |
|---|---|---|
| DeepSeek key | 直连 DeepSeek 用 | `DEEPSEEK_API_KEY` |
| OpenRouter key | 国产/国外小模型聚合用 | `OPENROUTER_API_KEY` |
| 本地 Ollama 地址 | 本地模型用（无需 key） | `OLLAMA_BASE_URL` |

### 4.3 交互（联动式）

设置页采用"先选目标模型，再渲染对应配置表单"的联动交互，避免一股脑展示所有配置项：

1. 页面上先放置"目标模型"选择（对齐现有右上角模型选择语义）。
2. 用户选择模型后，**仅渲染该模型 provider 对应的配置表单**：

| 用户所选模型 | 渲染的配置表单 | 免配项 |
|---|---|---|
| DeepSeek 模型 | DeepSeek key | — |
| 国产/国外小模型（OpenRouter） | OpenRouter key | — |
| 本地模型（Ollama） | 本地 Ollama 地址 | key |

### 4.4 配置优先级

运行时页面配置 > 环境变量（compose 注入）> 默认值兜底。

- 用户未在页面设置时，使用 compose 注入的环境变量或默认值，保证"开箱即用"。
- 用户在页面设置后，以页面值为准，并持久化到容器卷。

### 4.5 持久化与作用域

- 配置写入 demo 容器的命名卷（如 `/data/config.json`）。
- 启动时从卷读取并载入内存全局变量；运行期修改同步写盘。
- 全局一份，单用户演示台。

### 4.6 修改生效时机

配置修改后**立即生效**（动态刷新），无需重启 demo 容器。

### 4.7 完成标准

- [ ] 设置页可访问，包含平台接入 + 模型配置两大部分。
- [ ] 选择目标模型后，仅显示对应 provider 的配置表单。
- [ ] 配置写入容器卷，demo 容器重建后配置不丢失。
- [ ] 配置修改后立即生效。
- [ ] 未配置时使用默认值/环境变量，demo 可开箱即用。

---

## 5. 需求三：运行时 key 校验与提示（小模型）

### 5.1 目标

- 用户选择 DeepSeek 且 key 未配置/无效时，触发聊天操作前弹出配置对话框，引导用户先配置。
- 用户选择本地小模型但本机无对应模型时，仅提示用户自主配置，**不做自动下载**。

### 5.2 完成标准

- [ ] 选择 DeepSeek 且未配置 key 时，触发聊天前弹出配置提示。
- [ ] 选择本地模型且本机无模型时，给出配置提示（不自动下载）。

---

## 6. 目录 / 文件影响（规划）

```
VirbiusAgent/
├── docker-compose.yml           # 移除 virbius-demo 服务（平台保留 redis/engine/control/mcp-proxy）
├── virbius-demo/
│   ├── docker-compose.yml       # 独立编排 + 共享外部网络 + 配置卷
│   ├── Dockerfile               # 保持源码构建（从 virbius-core）
│   ├── config.py                # 读运行期配置（卷）叠加环境变量
│   ├── modules/
│   │   └── settings.py          # （新增）设置读取/持久化逻辑
│   ├── templates/
│   │   └── settings.html        # （新增）设置页模板
│   └── docs/
│       └── PRD-container-settings.md  # 本文档
```

> 注：以上为规划，最终文件路径/命名以实现为准。

---

## 7. 风险与待确认

| 风险/事项 | 说明 | 状态 |
|---|---|---|
| 配置修改即时生效 | 平台地址/License 改动需重新初始化对应模块（端层拉规则、mcp-proxy 鉴权），实现时要处理动态刷新 | 待实现确认 |
| 卷路径与权限 | demo 容器卷挂载位置、容器内写权限需在实现时验证 | 待实现确认 |
| config.py 与现有 .env 加载 | 需兼容现有 `.env` 加载，页面配置优先 | 待实现确认 |

---

## 8. 打开即用：给下一个 agent 的速览

> 新 agent 接手时，先读本文件明确本次需求边界，再读 `docker-compose.yml`、`virbius-demo/config.py`、现有 `modules/*.py` 与 `templates/`。已锁定决策见 §2，功能需求见 §3–§5。