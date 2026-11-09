# 与相邻 Agent 安全项目的对比

最后核对：2026-11-09。本文仅基于公开仓库与文档，**不是**动手实测基准。
各产品定位不同，能力也会随上游版本变化；做选型前请以各项目最新文档为准。

| 项目 | 仓库 | 公开许可证 |
|---|---|---|
| VirbiusAgent | <https://github.com/i1see1you/VirbiusAgent> | MIT |
| Microsoft Agent Governance Toolkit (AGT) | <https://github.com/microsoft/agent-governance-toolkit> | MIT |
| Meta LlamaFirewall | <https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall> | Llama Community License |
| IBM ContextForge（`mcp-context-forge`） | <https://github.com/IBM/mcp-context-forge> | Apache-2.0 |

## 能力概览

| 能力 | VirbiusAgent | Microsoft AGT | Meta LlamaFirewall | IBM ContextForge |
|---|---|---|---|---|
| **主要定位** | 四层 Agent 运行时安全 | 进程内 Agent 治理 | 提示/Agent 护栏扫描器 | MCP/A2A/REST 网关与注册中心 |
| **纵深防御** | 端层预检 → 网关执行 → 内核观测 → 云端裁决 | 可选策略、身份、沙箱、审计层 | 进程内多层扫描器 | 网关侧鉴权、限流与插件 |
| **检测方式** | 确定性规则 + ML/LLM 模型 | 策略/规则（YAML/OPA/Cedar）+ 注入审计 | ML 分类器 + LLM 推理审计 | 配置 + 插件，以确定性为主 |
| **规则引擎** | Lua DSL、Groovy L3、Falco eBPF 规则 | 策略引擎（YAML/OPA/Cedar） | 扫描器配置与正则 | 插件/配置引擎 |
| **运行时观测** | eBPF/Falco 系统调用级事件 | 应用层审计轨迹 | 进程内扫描结果/日志 | 应用层追踪 |
| **MCP 协议支持** | 原生 stdio/SSE 代理 | MCP 安全网关 | Agent 框架护栏（OpenAI/LangChain） | 原生 MCP + A2A + REST/gRPC 联邦 |
| **工具调用安全** | 工具调用链路上的预检 → 裁决 → 审计 | 应用内确定性拦截 | 扫描 Agent 推理/输出 | 网关鉴权/限流 + 插件护栏 |
| **人工审批** | Challenge → 审批 → token 门控执行 | 审批策略 | 公开文档未提及 | 公开文档未提及 |
| **决策链路** | 全链路因果可视化 | 防篡改审计日志 | 扫描日志/结果 | OpenTelemetry 追踪 |
| **DLP / PII 脱敏** | 端层脱敏，可离线 | 公开文档未提及 | 公开文档未提及 | 公开文档未提及 |
| **沙箱隔离** | Landlock / gVisor 配置 | 执行沙箱 | 公开文档未提及 | 公开文档未提及 |
| **部署方式** | 自托管 sidecar、远程代理、SDK | SDK/库（pip/npm/NuGet） | Python SDK | 自托管（PyPI/Docker/Helm） |

## 阅读说明

- **“公开文档未提及”** 仅表示核对时未在公开文档中找到，不代表该能力一定不存在。
- **定位不同**：VirbiusAgent 与 Microsoft AGT 侧重 Agent 行为治理；LlamaFirewall
  侧重进程内护栏扫描；ContextForge 侧重网关/注册中心联邦。
- **观测位置不同**：网关、进程内 SDK、内核传感器看到的事件不同。应按自己的威胁模型
  比较，而不是只数功能勾选项。

## 可复现的 VirbiusGuard 指标

模型侧安全指标（gold_1000、WildGuardTest、XSTest、ToxicChat、AgentDojo）见主
README，并可用 [`scripts/benchmarks/`](../scripts/benchmarks/) 复现。竞品模型指标
未纳入本文，因为需要各自的 harness 与许可证。

## 纠错

如果本文有失效或不准确之处，欢迎带公开来源链接提 issue 或 PR。
