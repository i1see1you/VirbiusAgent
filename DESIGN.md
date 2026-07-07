# Agent 安全防护 — 端管核云四层架构设计

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.2 |
| 状态 | 草案 |
| 关联 | [README.md](README.md) |
| 参考项目 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

---

## 文档结构

本设计文档拆分为以下文件，本文件为索引并包含跨层与辅助章节：

| 文件 | 内容 | 简述 |
|------|------|------|
| **[ARCHITECTURE.md](ARCHITECTURE.md)** | §1 总体架构 · §2 端层 · §3 管层 · §4 核层 · §5 云层 | 四层架构核心设计（端管核云） |
| **[PROTOCOL.md](PROTOCOL.md)** | §2.6 MCP Server 集成 · §2.6.1 MCP Proxy 完整技术方案 | MCP 协议代理、安全管线、会话管理、错误码 |
| **[DEPLOYMENT.md](DEPLOYMENT.md)** | §8 部署视图 | 组件端口、部署拓扑（Sidecar / 远程 / SDK）、接入方式对比、四层全覆盖组合部署 |
| **[ROADMAP.md](ROADMAP.md)** | §11 路线图 · 变更日志 | P0/P1/P2 分阶段规划 + 版本历史 |
| **DESIGN.md**（本文件） | §6 跨层数据流 · §7 策略一致性 · §9 第三方依赖 · §10 与 VirbiusLLM 关系 | 索引 + 跨层与辅助章节 |

## 目录

| 章节 | 文件 |
|------|------|
| §1 总体架构 | [ARCHITECTURE.md](ARCHITECTURE.md#1-总体架构) |
| §2 端层 — Agent 工具调用预检与执行 | [ARCHITECTURE.md](ARCHITECTURE.md#2-端层--agent-工具调用预检与执行) |
| §2.6 MCP Server 集成（MCP Proxy） | [PROTOCOL.md](PROTOCOL.md) |
| §3 管层 — Higress 南北向安全网关 | [ARCHITECTURE.md](ARCHITECTURE.md#3-管层--higress-南北向安全网关) |
| §4 核层 — Falco 观测引擎 | [ARCHITECTURE.md](ARCHITECTURE.md#4-核层--falco-观测引擎) |
| §5 云层 — 统一策略大脑 | [ARCHITECTURE.md](ARCHITECTURE.md#5-云层--统一策略大脑) |
| §6 跨层数据流 | [本文件 §6](#6-跨层数据流) |
| §7 策略一致性 | [本文件 §7](#7-策略一致性) |
| §8 部署视图（含接入方式对比 §8.3 + 四层全覆盖 §8.4） | [DEPLOYMENT.md](DEPLOYMENT.md) |
| §9 第三方技术栈依赖与稳定性 | [本文件 §9](#9-第三方技术栈依赖与稳定性) |
| §10 与 VirbiusLLM 的关系 | [本文件 §10](#10-与-virbiusllm-的关系) |
| §11 路线图 | [ROADMAP.md](ROADMAP.md) |
| 变更日志 | [ROADMAP.md](ROADMAP.md#变更日志) |

---

## 6. 跨层数据流

### 6.1 工具调用请求路径

```
Agent Framework
  |
  v
[1] 端层预检 (virbius-core)
    +-- 参数校验 + tool allowlist + JSON Schema 校验
    |     v 预检通过
    |     (预检不通过 -> 直接 deny)
    v
[2] 管层 (Higress + virbius-gateway WASM)
    +-- tool allowlist 校验 (WASM allowlist 模块)
    +-- 累计计数器 (WASM Redis 模块)
    +-- 快速通道判断 (低风险 + session_risk < 30)
    |     +-- 是 -> allow (跳过云层，进入执行)
    |     +-- 否 -> 调用云层
    v
[4] 云层 (virbius-engine)
    +-- 记录工具调用到 Redis session history
    +-- Groovy L3 终判 (工具链检测 + STI 审计)
    +-- 更新 session risk score
    |     v effective_action
    v
[2] 管层 (Higress) 执行决策
    +-- allow -> 转发到 MCP Server
    +-- block -> 403 JSON-RPC error
    +-- review -> allow + 异步审计
    v
[1] 端层执行 (virbius-core, P0: 同进程)
    +-- P0: sandbox_type=none -> 同进程执行
    +-- P2: sandbox_type=subprocess -> Landlock + drop caps
    +-- P2: sandbox_type=gvisor -> gVisor 预热池
    |     v 执行结果
    v
[2] 管层 (Higress)
    +-- 输出 PII 脱敏 (端层已做，管层不重复)
    +-- MCP/A2A 路由 -> MCP Server
    v
[3] 核层 (Falco) — 旁路
    +-- 全程旁路监控: syscall/网络/文件事件 -> Redis Audit Stream
    +-- session_risk > 80 时告警 + 通知管层断连
```

### 6.2 审计事件流

```
各层 -> Redis Audit Stream -> virbius-engine (异步消费)
                              +-- session risk score 更新
                              +-- 告警触发
                              +-- 运营台展示

核层 Falco 事件 (PID) -> daemon 查 Redis pid_trace:{pid} -> 补全 trace_id -> 审计流
```

审计事件格式（统一 trace_id）：

```json
{
  "trace_id": "uuid",
  "layer": "edge | gateway | kernel | engine",
  "event_type": "tool_call | syscall | policy_match | falco_alert",
  "tool_name": "read_file",
  "action": "allow | block | review",
  "rule_id": "rule-xxx",
  "rollout_state": "full",
  "reason": "arg_schema_violation",
  "exec_time_ms": 12,
  "agent_pid": 12345,
  "session_id": "sess_xxx",
  "falco_mode": "ebpf | plugin | userspace",
  "timestamp": "2026-07-06T10:00:00Z"
}
```

### 6.3 控制面下发

```
virbius-control
  |
  +-- REST (现有)
  |   +-- -> virbius-engine: Groovy L3 + Prompt L1 规则
  |   +-- -> Higress: 名单 + 计数 (via WasmPlugin CRD)
  |
  +-- REST (新增)
  |   +-- -> virbius-kernel: Falco 规则 + eBPF maps
  |
  +-- Higress CRD (新增，替代 xDS)
      +-- -> Higress: MCP route + WasmPlugin 配置 (virbius-compiler 生成)
```

> **删除原设计的 xDS 适配器**：Higress 使用 CRD（WasmPlugin / McpServer）声明式配置，由 virbius-compiler 生成 CRD YAML，K8s APIServer 更新触发 WASM 插件热加载（连接无损）。不需要 xDS 协议。

---

## 7. 策略一致性

### 7.1 冲突检测

端层拆为预检 + 执行两阶段，冲突解决分阶段处理：

**预检阶段**（工具未执行，无副作用）：

| 场景 | 处置 | 说明 |
|------|------|------|
| 端层预检 deny | deny（不进入管层） | 最快拦截 |
| 管层 block, 云层 allow | block | 管层有本地规则，优先 |
| 管层 allow, 云层 deny | deny | 云层有语义信息，覆盖管层 |
| 核层 Falco 检测异常 | 不直接阻断(P0)；提升 risk score -> 后续请求阻断 | P2 可同步阻断 |

**执行阶段**（P2，终判已返回 allow）：

| 场景 | 处置 |
|------|------|
| Landlock deny | 子进程收到 -EPERM，工具返回 Error |
| Tetragon enforcer kill | 进程被 kill，告警 |

> **关键约束**：终判 deny 时工具不执行，不存在"工具已执行但 deny"的副作用。

### 7.2 放量一致性

各层放量状态可能不同步（如端层 canary=10%、管层 full）。

**一致性保证**：
- virbius-control 发布时标注 release_id，各层缓存同一版本
- 出现版本偏差时，以最严格的可用版本为准
- 快速通道工具审计事件全量采样(sample_rate=1.0)，异步送 engine 复核
- 异步复核发现违规 -> 提升 session_risk_score -> 该 session 后续退出快速通道

---

## 9. 第三方技术栈依赖与稳定性

### 9.1 依赖清单

| 层 | 技术 | 用途 | 稳定性 | 替代方案 |
|----|------|------|--------|---------|
| 端 | Landlock | 文件路径限制(P2) | 较新(文件 5.13/2021, 网络 6.7/2024) | AppArmor |
| 端 | drop caps | capabilities 丢弃(P2) | 极稳定(内核 2.2, 1999) | 无 |
| 端 | gVisor | 不可信代码沙箱(P2) | 稳定(Google, GKE 使用) | Kata Containers |
| 端 | PyO3 / napi-rs | Rust<->Python/Node 绑定 | 稳定(广泛使用) | subprocess |
| 管 | Higress + WASM | AI 网关 + 安全插件 | 稳定(基于 Envoy, 阿里巴巴生产) | APISIX / Envoy |
| 核 | eBPF + BTF/CO-RE | 内核观测 | 极稳定(行业标准) | 无 |
| 核 | Falco | 观测引擎(CNCF 毕业) | 极稳定(CNCF Graduated) | Tracee |
| 核 | Tetragon | eBPF enforcement(P2) | 较新(Isovalent/Cisco) | Falco + Landlock |
| 云 | Groovy | L3 规则脚本 | 稳定但 declining(Apache) | Python sandbox |
| 云 | Redis | session + 审计流 | 极稳定 | KeyDB |
| 云 | Spring Boot | engine/control 框架 | 极稳定 | Quarkus |
| 云 | qwen3guard:0.6B | STI Taint 小模型(P1) | 较新 | 任意 guard 模型 |
| 协议 | MCP | 工具调用协议 | 较新(Anthropic, 2024) | 自定义 JSON-RPC |

### 9.2 风险评估

**Tier 1 极稳定(无风险)**：eBPF, Redis, Envoy, Spring Boot, K8s, drop caps

**Tier 2 稳定(需关注)**：

| 技术 | 风险 | 缓解 |
|------|------|------|
| Higress/Envoy | Envoy 社区活跃; WASM 生态发展中 | 核心功能已稳定; WASM 插件可跨网关移植 |
| Falco | 4 套驱动维护负担; kmod 驱动将弃用 | 只用 eBPF + plugin 两种 |
| gVisor | Google 依赖; 性能开销 | P2 才引入; Kata 备选 |

**Tier 3 较新(需密切关注)**：

| 技术 | 风险 | 缓解 |
|------|------|------|
| Landlock 网络(v4) | 内核 6.7+, 2 年, 部署少 | P2 才引入; 文件版优先 |
| Tetragon | Cisco 收购后可能商业化; 社区小 | P2 才引入; Falco+seccomp 替代 |
| MCP 协议 | Anthropic 控制, 非 IETF 标准; spec 演进中 | 设计不绑死 MCP; 通用 JSON-RPC 兼容 |
| qwen3guard | 模型可能更新/弃用 | mlPredict 抽象层, 模型可替换 |

### 9.3 关键路径依赖

**不可替代(失败则系统不可用)**：
- Redis — session 状态 + 审计流(建议 Sentinel/Cluster)
- Higress — 管层全部安全检查(可迁 APISIX/Envoy)
- virbius-engine — 云层终判

**可降级(失败有 fallback)**：
- Falco eBPF 驱动 -> userspace -> plugin 降级链
- gVisor -> Landlock subprocess 降级
- Tetragon -> Falco + Landlock subprocess(P2) 替代
- qwen3guard -> 任意 guard 模型

---

## 10. 与 VirbiusLLM 的关系

VirbiusAgent 采用**文件级复用**策略，不作为 VirbiusLLM 的项目依赖。两个项目独立演进，VirbiusAgent 从 VirbiusLLM 拷贝所需代码后自行维护。

**决策理由**：virbius-engine/virbius-control/virbius-compiler 需要大幅扩展（加 License、宪法、Agent 规则、Redis session、Higress CRD 编译），作为依赖不如直接拷贝修改。virbius-core 虽能完整复用，但其 EdgeManifest/EngineClient 等结构需扩展字段，依赖关系下只能 fork 或提 PR。两个项目同属一个团队维护，拷贝后独立演进更灵活。

#### 直接复用（零改动，拷贝即用）

| 来源 | 文件 | 功能 | VirbiusAgent 位置 |
|------|------|------|------------------|
| virbius-core | `src/dlp/engine.rs` | PII 脱敏(desensitize_in/out) | virbius-core/src/dlp/ |
| virbius-core | `src/dlp/entity.rs` | 实体识别(手机号/身份证/邮箱/银行卡) | virbius-core/src/dlp/ |
| virbius-core | `src/dlp/vault.rs` | 脱敏 token 保险柜 | virbius-core/src/dlp/ |
| virbius-core | `src/sync.rs` | manifest 同步(版本检查→canary→sha256→原子写) | virbius-core/src/sync.rs |
| virbius-core | `src/bootstrap.rs` | 初始化流程 | virbius-core/src/bootstrap.rs |
| virbius-core | `src/runtime.rs` | 审计 flush loop | virbius-core/src/runtime.rs |
| virbius-core | `src/audit.rs` | 审计上报 | virbius-core/src/audit.rs |
| virbius-core | `src/trace.rs` | trace_id 管理 | virbius-core/src/trace.rs |
| virbius-core | `src/engine.rs` | EngineClient(调 /v1/evaluate) | virbius-core/src/engine.rs |
| virbius-core | `src/matcher.rs` | 规则匹配 | virbius-core/src/matcher.rs |
| virbius-gateway | `lib/*.lua` (11 个文件) | access_lists/list_redis/effective/scene_registry/trace/context_vars/config_redis/json_util/file_cache/uri_match/prompt | virbius-gateway/lib/ |
| virbius-policy | `ActionMerge.java` | 动作合并 | virbius-policy/ |
| virbius-policy | `IntentAction.java` | 意图归一化 | virbius-policy/ |
| virbius-policy | `ListMatcher.java` | 名单匹配 | virbius-policy/ |
| virbius-policy | `audit/RedisStreamAuditSink.java` | Redis Stream 审计 | virbius-policy/ |

#### 需扩展（拷贝后修改）

| 来源 | 文件 | 已有能力 | 需新增 |
|------|------|---------|--------|
| virbius-core | `src/manifest.rs` | EdgeManifest(rules/dlp_rules/sdk_config) | 加 tool_policies + landlock_profiles 字段 |
| virbius-groovy-l3 | `PolicyContext.java` | listMatch/getCumulative/riskScore/scene/sessionId | 加 sessionHistory(n)/sessionRiskScore()/incrementRiskScore()/recordToolCall()/lastToolResult()/toolName() |
| virbius-gateway | `wasm/access.go` | WASM access 阶段 | 加 tool allowlist + tool 计数 + engine 调用 |
| virbius-control | `RuleService.java` | 规则 CRUD | 加 Agent 规则类型 + License CRUD + 宪法管理 |
| virbius-control | `ArtifactService.java` | 产物编译 | 加 Higress CRD + Landlock profile + Constitution template 编译 |
| virbius-control | `PublishOrchestrator.java` | 4 阶段发布 | 加各层独立放量(端层 device_id/管层 tenant_id/核层 PID) |
| virbius-compiler | 编译器 | edge manifest + gateway JSON + engine input | 加 Higress CRD + Landlock profile + Constitution template 输出 |

#### 需新建（VirbiusAgent 原创）

| 组件 | 语言 | 功能 |
|------|------|------|
| `virbius-core/src/prompt_gateway.rs` | Rust | Prompt Gateway(宪法注入 + PII 脱敏) |
| `virbius-core/src/license.rs` | Rust | License 校验(签名/过期/吊销) |
| `virbius-core/src/sandbox/landlock.rs` | Rust | P2: Landlock + drop caps 沙箱 |
| `virbius-core/src/sandbox/gvisor_pool.rs` | Rust | P2: gVisor 预热池 |
| virbius-core MCP 绑定 | Rust | PyO3 / napi-rs 绑定 |
| `virbius-mcp-proxy` | Rust | MCP 协议代理（stdio/SSE 传输 + 安全管线 + 会话管理） |
| `virbius-control` License 模块 | Java | License 签发(EdDSA) + 吊销(pub/sub) |
| `virbius-control` 宪法模块 | Java | 宪法规则管理 + 编译为 prompt 模板 |
| `virbius-control` Memory Interceptor | Java | P1: 记忆读写拦截 |
| `virbius-kernel/` | Rust/YAML | Falco 部署 + Tetragon 检测 + 降级逻辑 |
| virbius-audit Falco 插件 | Go | 自定义 Falco 插件(消费 Redis Stream) |

#### VirbiusAgent 项目结构

```
VirbiusAgent/
|
+-- virbius-core/              # 拷贝自 VirbiusLLM + 扩展
|   +-- src/dlp/               # 直接复用
|   +-- src/sync.rs            # 直接复用
|   +-- src/bootstrap.rs       # 直接复用
|   +-- src/runtime.rs         # 直接复用
|   +-- src/matcher.rs         # 直接复用
|   +-- src/manifest.rs        # 复用 + 加 tool_policies/landlock_profiles
|   +-- src/audit.rs           # 直接复用
|   +-- src/trace.rs           # 直接复用
|   +-- src/engine.rs          # 直接复用
|   +-- src/prompt_gateway.rs  # 新建
|   +-- src/license.rs         # 新建
|   +-- src/sandbox/           # 新建 (P2)
|   +-- src/mcp/               # 新建 (PyO3/napi-rs)
|
+-- virbius-mcp-proxy/         # 新建 (MCP 协议代理)
|   +-- src/transport/         # stdio + SSE 传输
|   +-- src/pipeline.rs        # 安全管线
|   +-- src/session.rs         # 会话管理
|
+-- virbius-gateway/           # 拷贝自 VirbiusLLM (Lua 逻辑参考，重写为 WASM)
|   +-- lib/                   # Lua 逻辑参考 (11 个文件，重写为 Go WASM)
|   +-- wasm/                  # WASM 插件 (Go, proxy-wasm-go-sdk)
|
+-- virbius-engine/            # 拷贝自 VirbiusLLM + 扩展
|   +-- (加 Redis session + Agent 规则 + ctx 扩展)
|
+-- virbius-control/           # 拷贝自 VirbiusLLM + 扩展
|   +-- (加 License + 宪法 + Agent 规则 + 新发布逻辑)
|
+-- virbius-groovy-l3/         # 拷贝自 VirbiusLLM + 扩展
|   +-- PolicyContext.java     # 复用 + 加 session API
|
+-- virbius-compiler/          # 拷贝自 VirbiusLLM + 扩展
|   +-- (加 Higress CRD + Landlock + Constitution 编译)
|
+-- virbius-policy/            # 拷贝自 VirbiusLLM
|   +-- (直接复用，零改动)
|
+-- virbius-kernel/            # 全新
|   +-- Falco 部署 + Tetragon 检测
|
+-- DESIGN.md
+-- README.md
```

#### 复用率

```
直接复用(零改动)   ████████████████████████  ~56%  (25 个文件)
需扩展(拷贝+改)    ██████                    ~16%  (7 个文件)
需新建            ███████████               ~30%  (13 个组件)
```

---

