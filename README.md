# VirbiusAgent

Agent 安全防护工具 — 端管核云四层架构。

基于 [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) 基础平台，为 AI Agent 提供工具调用安全防护。

## 架构

| 层 | 组件 | 职责 |
|----|------|------|
| 端层 | virbius-core | 工具调用预检（参数校验 + allowlist + JSON Schema） |
| 管层 | Higress + virbius-gateway WASM | TLS/限流/安全预检/HTTP 阻断 |
| 核层 | Falco + Tetragon(P2) | 运行时观测（eBPF/plugin 降级链） |
| 云层 | virbius-engine + virbius-control | Groovy L3 终判 + STI 语义审计 + 策略管理 |

## 核心能力

| 能力 | 阶段 | 说明 |
|------|------|------|
| MCP 安全代理 | P0 | stdio/SSE 代理 + 安全管线（License + allowlist + engine 终判） + 多上游路由 |
| 快速通道 | P0/P1 | 低风险工具跳过云层，延迟优化 |
| 高风险人工审批 | P1 | engine challenge → 运营台审批 → token 验证放行 |
| Agent 决策链路追踪 | P1 | tool_call/tool_result 全链路 trace，session 时间线 + 因果链可视化 |
| 运营台审计大盘 | P1 | session risk + 工具调用 + 告警 + 审批队列 + 决策链路可视化 |

## 设计文档

详见 [DESIGN.md](DESIGN.md)。

## 分阶段规划

- **P0**：观测（eyes）+ HTTP 层 enforcement
- **P1**：增强观测（STI Taint + 自定义 Falco 插件 + 人工审批 + 决策链路追踪）
- **P2**：阻断（hands）（seccomp-notify + Landlock + gVisor + Tetragon enforcer）

## License

MIT
