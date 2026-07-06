# VirbiusAgent

Agent 安全防护工具 — 端管核云四层架构。

基于 [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) 基础平台，为 AI Agent 提供工具调用安全防护。

## 架构

| 层 | 组件 | 职责 |
|----|------|------|
| 端层 | virbius-core | 工具调用预检（参数校验 + allowlist + JSON Schema） |
| 管层 | OpenResty + virbius-gateway Lua | TLS/限流/安全预检/HTTP 阻断 |
| 核层 | Falco + Tetragon(P2) | 运行时观测（eBPF/plugin 降级链） |
| 云层 | virbius-engine + virbius-control | Groovy L3 终判 + STI 语义审计 + 策略管理 |

## 设计文档

详见 [DESIGN.md](DESIGN.md)。

## 分阶段规划

- **P0**：观测（eyes）+ HTTP 层 enforcement
- **P1**：增强观测（STI Taint + 自定义 Falco 插件 + 人工审批）
- **P2**：阻断（hands）（seccomp-notify + Landlock + gVisor + Tetragon enforcer）

## License

MIT
