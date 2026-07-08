# VirbiusAgent 路线图 — ROADMAP

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.3 |
| 状态 | 草案 |
| 关联 | [DESIGN.md](DESIGN.md)（索引） · [ARCHITECTURE.md](ARCHITECTURE.md) |

> 本文件包含 §11 路线图（P0/P1/P2 分阶段规划）+ 变更日志。

---

## 11. 路线图

### P0 — 核心安全链路（身份 + 观测 + HTTP 阻断 + Prompt Gateway）

| 任务 | 组件 | 估计 |
|------|------|------|
| Runtime License 签发 + 校验 + 吊销 | control + 全层 | 3w |
| Prompt Gateway 基础版（宪法注入 + PII 脱敏） | virbius-core | 3w |
| 企业 AI 智能体宪法 v1（规则定义 + 编译） | control + compiler | 2w |
| 端层预检（参数校验 + allowlist + JSON Schema） | virbius-core | 2w |
| 端层 MCP Server 集成（PyO3 / napi-rs / subprocess） | virbius-core | 3w |
| MCP Proxy（stdio/SSE 代理 + 安全管线 + 会话管理） | virbius-mcp-proxy | 3w |
| 管层 Higress WASM 插件（allowlist + 计数 + engine 调用） | virbius-gateway | 3w |
| 管层 Higress 路由配置自动生成（control -> compiler） | control + compiler | 2w |
| 云层 Redis session 状态（history + risk + count） | engine | 3w |
| 云层 Groovy L3 Agent 规则（工具链检测 + 场景匹配） | engine | 2w |
| 云层 Groovy ctx 扩展（sessionHistory / riskScore，内存预加载） | engine | 2w |
| 控制面 Agent 规则 CRUD + 发布 | control | 2w |
| 核层 Falco 部署 + eBPF 驱动（标准节点池） | virbius-kernel | 2w |
| 核层 Falco plugin 模式（serverless 降级: k8saudit + filetail） | virbius-kernel | 2w |
| 核层 PID->trace_id 映射 + 审计上报 | virbius-kernel | 1w |
| 端到端集成测试 | 全组件 | 3w |
| **P0 合计** | | **~36w** |

### P1 — 增强观测 + 记忆管控

| 任务 | 说明 | 状态 |
|------|------|------|
| 端层快速通道（低风险工具跳过云层） | 延迟优化 | ✅ 已完成 |
| 自定义 virbius-audit Falco 插件 | 消费 Redis Stream，Agent 专用规则 | |
| 审计大盘 | session risk + 工具调用 + 告警可视化 | ✅ 已完成 |
| STI 语义审计（Taint 维度调小模型） | 工具返回值注入检测 | |
| Prompt 入侵检测（prompt runtime 重新定位） | 用户输入越狱/注入检测，与 STI Taint 共享 qwen3guard 模型 | |
| 输出 PII 脱敏（端层，工具返回前） | 复用 virbius-core dlp/engine.rs | |
| Falco 规则库扩充（Agent 专用规则集） | 工具调用模式、SSRF 特征、数据外泄 | |
| 高风险工具人工审批流 | engine -> 审批 UI -> 超时 deny | ✅ 已完成 |
| session risk 自适应模型 | 从规则阈值升级为加权累积 | |
| 审计完整性（hash chain） | 防篡改 | |
| 记忆管控（Memory Interceptor） | Agent 记忆读写拦截 + 脱敏 + 注入检测 | |
| Agent 决策链路追踪 | input → reasoning → tool_call → tool_result → output 全链路 trace | ✅ 已完成 |

### P2 — 阻断(hands) + TEE

| 任务 | 说明 |
|------|------|
| Landlock + drop caps 沙箱 | 文件路径限制 + capabilities 丢弃 + ABI 版本适配 |
| gVisor 预热池 | 不可信代码执行沙箱 |
| Tetragon 检测 + 降级逻辑（detect_mode） | 内核能力自动检测 + 模式选择 |
| Tetragon enforcer（eBPF 可用时） | 宿主级 enforcement 叠加 |
| eBPF 自定义观测程序（execveat + IPv6） | 补充 Falco 内置规则 |
| 端到端红队测试 | 安全验证 |

### 各阶段对照

| 阶段 | 观测(eyes) | 阻断(hands) | 新增能力 |
|------|-----------|------------|---------|
| P0 | Falco + access log + Redis 审计 + STI + Prompt Gateway | HTTP 403 + License + allowlist + 计数 + schema + risk 断连 | 身份管控 + 提示增强 |
| P1 | STI Taint + Prompt 入侵检测 + virbius-audit 插件 + 审计完整性 + 决策链路追踪 | 人工审批 + 自适应 risk + 记忆管控 | 记忆管控 + prompt 越狱检测 + 决策链路可视化 |
| P2 | Tetragon observe | Landlock + gVisor + Tetragon enforcer + TEE | syscall 级阻断|

---

## 变更日志

### v3.3 (2026-07-08)

**新增功能**

- **Agent 决策链路追踪系统**：全链路记录 Agent 从输入到输出的每一步决策，支持 session 级时间线、trace 级因果链、工具维度搜索
  - DB：`tb_agent_trace` 表（V6 迁移）+ `tb_trace_ingest_checkpoint` 检查点表
  - Proxy：`trace_collector.rs` 模块（TraceEvent + TraceCollector + Redis XADD），`session.rs` 增加 `step_seq` / `last_step_id` 步骤追踪字段，`router.rs` 在 `tool_call` 和 `tool_result` 两个关键点采集 trace 事件
  - Control：`TraceIngestService` 消费 Redis Stream 写入 DB，`TraceQueryService` 提供 session 时间线 / trace 链路 / 搜索查询，REST API 挂载在 `/api/v1/admin/tenants/{tenantId}/trace/*`
  - 运营台：新增「决策链路」面板，支持搜索 + 时间线可视化 + Ingest 健康状态
- **高风险工具人工审批流（P1）**：全链路闭环已完成
  - Engine：`ChallengeService` Redis 状态机（create → approve/reject → verify token），`EvaluateOrchestrator` 在 `challenge` action 时自动创建审批记录
  - Control：`ChallengeController` 代理 Engine API 到运营台
  - Proxy：`PipelineResult::Challenge` 拦截 + `challenge_token` 重试验证
  - DB：`tb_challenge_audit` 审计持久化（V5 迁移）
  - 运营台：审批队列面板（5s 轮询 + approve/reject）

**文档更新**

- 新增 [DESIGN.md §12](DESIGN.md#12-agent-安全风险评估框架) Agent 安全风险评估框架：七维风险评估 + 评估方法论（5 步）+ 安全保障对照表
- 新增 [DESIGN.md §13](DESIGN.md#13-p1-功能详细设计方案) P1 功能详细设计方案：覆盖 7 项 P1 功能（Prompt 注入检测、STI Taint、Session Risk 自适应、审计完整性 hash chain、记忆管控、输出审查、virbius-audit Falco 插件 + 规则库）+ 实现优先级建议

### v3.2

- 初始路线图发布
