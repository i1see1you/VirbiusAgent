# VirbiusAgent 架构设计 — ARCHITECTURE

[English](ARCHITECTURE.md)

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.6 |
| 状态 | 正式 |
| 关联 | [DESIGN.zh.md](DESIGN.zh.md)（索引） · [PROTOCOL.zh.md](PROTOCOL.zh.md) · [DEPLOYMENT.zh.md](DEPLOYMENT.zh.md) · [CHANGELOG.md](CHANGELOG.md)（英文） |
| 参考项目 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

> 本文件包含 §1 总体架构 · §2 端层 · §3 管层 · §4 核层 · §5 云层。
> §2.6 MCP Proxy 完整技术方案已拆分至 [PROTOCOL.zh.md](PROTOCOL.zh.md)。

---

## 1. 总体架构

### 1.1 四层总览

```
Agent Framework (LangChain / OpenAI SDK / AutoGen / ...)
  | tool_call
  v
[1] Edge - virbius-core (extended)
    precheck: args + allowlist + JSON Schema
    execute:  P0 in-process / P2 Landlock + drop caps + gVisor
  |
[2] Gateway - Higress + virbius-gateway WASM plugin
    TLS/rate-limit/long-conn + allowlist + counter + engine call + HTTP block
  |
[3] Kernel - Falco observer (observation layer)
    eBPF driver (standard node); unprivileged env -> Disabled (plugin mode removed)
    observe: syscall/net/file + audit stream + session risk
    enforce(P2): Landlock + drop caps (edge) / gVisor (edge)
  |
[4] Cloud - virbius-engine + virbius-control
    engine: Groovy L3 + STI audit + tool-chain detect
    session: Redis (tool history + risk score + counters)
    control: rule CRUD + rollout + unified delivery
```

**流量拓扑——南北向 vs 东西向**：

端管核云四层在流量方向上分为两类，需明确区分以避免拓扑冲突：

```
┌─────────────────────────────────────────────────────────────┐
│  东西向流量（East-West，本地/同 Pod）                          │
│                                                              │
│  Agent ──MCP/JSON-RPC──> [端层] MCP Proxy (Sidecar)          │
│    localhost:9090，stdiorSSE                                 │
│    职责：License 校验 + 预检 + 安全管线 + 工具执行              │
│                                                              │
│  特点：Agent 与 Proxy 同进程组，流量不出 Pod，不经过管层        │
├─────────────────────────────────────────────────────────────┤
│  南北向流量（North-South，跨网络）                             │
│                                                              │
│  远程 Agent ──HTTPS──> [管层] Higress (Ingress) ──> MCP Server  │
│                         TLS/限流/allowlist/engine 调用         │
│                                                              │
│  Agent/curl工具 ──HTTP──> [管层] Higress (Egress) ──> 外部 API  │
│                         或端层 Egress 拦截（Sidecar 模式）      │
│                                                              │
│  特点：非 Sidecar 模式跨网络流量必经管层；Sidecar 模式 Egress  │
│  由端层 Proxy 代发（见 §3.5）                                   │
└─────────────────────────────────────────────────────────────┘
```

| 流量类型 | 方向 | 拦截层 | 部署模式 |
|---------|------|--------|---------|
| MCP 工具调用（Sidecar 模式） | 东西向 | 端层 MCP Proxy | Agent 与 Proxy 同 Pod |
| MCP 工具调用（远程模式） | 南北向 | 管层 Higress (Ingress) | Agent 远程连接 |
| Agent 外部 HTTP 请求（curl 等） | 南北向（Egress） | 端层 Egress 拦截 / 管层 Egress Proxy | 见 §3.5 |

> **设计决策：Sidecar 模式下管层不参与 MCP 工具调用链**
>
> 当端层以 MCP Proxy Sidecar 模式部署时，Agent 的 MCP 工具调用走 localhost 直达 Proxy，不经过管层 Higress。这是设计预期行为：端层 Proxy 已内嵌完整安全管线（License + 预检 + engine 终判），管层在此场景下不重复拦截。
>
> 管层 Higress 的职责聚焦于：
> 1. **Ingress**：远程 Agent（非 Sidecar）访问 MCP Server 的南北向流量
> 2. **Egress**：Agent 业务工具的外部 HTTP 请求（如 `curl` 工具访问外部 API）的网络层管控。注意：仅 MCP 业务工具请求走 Proxy 代发，Agent 框架底层的隐式网络请求（配置拉取/模型下载/心跳等）受 NetworkPolicy 限制，不由 Proxy 代发（详见 [§3.5](#35-egress-流量管控)）
>
> 对于 Sidecar 模式下的 Egress 流量，采用**工具级管控**：MCP 业务工具请求（如 `curl`）由端层 Proxy 在 `tools/call` 拦截阶段做 URL 白名单校验并代发（P0）；Agent 框架底层的隐式网络请求受 K8s NetworkPolicy 限制到白名单目标（P0）；P2 可叠加 eBPF/iptables 透明劫持实现进程级全量出站阻断。

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **控制面统一** | 所有层的策略真源为 virbius-control，各层独立执行但配置同源 |
| **预检先于执行** | 端层预检 -> 管层/云层终判 -> 端层执行。工具在终判通过后才执行 |
| **观测与阻断分离** | 观测(eyes)和阻断(hands)由不同技术栈承担。观测随环境降级(eBPF->ptrace)，阻断始终由端层 Landlock + drop caps 保证(P2) |
| **观察先行** | P0 只实现观测(Falco + HTTP 层阻断 + session risk 累积)，P2 补 syscall 级阻断 |
| **eBPF 是增强非依赖** | eBPF 可用时增强观测精度；不可用时端层 Landlock + drop caps + gVisor 仍是完整可用的阻断 |
| **端层兜底** | 即使管层/云层被绕过，端层预检 + 沙箱仍限制进程行为 |
| **快速通道** | 低风险工具跳过云层 RPC，端层预检 + 管层本地规则直接放行，目标延迟 <5ms |
| **职责分离** | Higress 做路由 + 限流 + 安全预检；安全终判收敛到 virbius-engine |
| **南北东西分离** | 端层（Sidecar）处理东西向 MCP 工具调用，管层（Higress）处理南北向 Ingress/Egress 流量。Sidecar 模式下 MCP 调用不经管层，管层聚焦网络边界安全 |
| **渐进接入** | 各层可独立开关，兼容仅有端层或仅有管层的轻量部署 |

### 1.3 分阶段规划

| 阶段 | 观测(eyes) | 阻断(hands) |
|------|-----------|------------|
| **P0** | Falco(eBPF) + access log + Redis 审计流 + STI 审计 + Prompt Gateway(宪法注入) | HTTP 403 + allowlist + 计数 + schema 校验 + risk 阈值断连 + Runtime License 校验 |
| **P1** | STI Taint 小模型 + Falco http_output 三级关联 + 审计完整性 | 人工审批流 + 自适应 risk 模型 + 记忆管控(Memory Interceptor) |
| **P2** | eBPF 自定义观测(execveat + IPv6) | Landlock + drop caps + gVisor + TEE(金融级) |

### 1.4 身份标识体系

本设计沿用 VirbiusLLM 的 `app_id` 作为 **Agent 身份标识（agent_id）**，不区分 Agent 类型与运行实例。

VirbiusAgent 代码实现参考 VirbiusLLM，详细复用关系见 10。

| 层级 | 标识 | 说明 | 示例 |
|------|------|------|------|
| 租户 | `tenant_id` | 组织/租户 | "公司A" |
| **Agent** | `app_id` | **Agent 身份标识（即 agent_id）** | "code-review-agent" |
| 会话 | `session_id` | 单次对话/工具调用链 | "sess_abc" |
| 设备 | `device_id` | 客户端设备（canary 灰度用） | "device_xxx" |
| 追踪 | `trace_id` | 单次请求追踪 | "uuid" |

> **设计决策**：`app_id` 就是 `agent_id`。在 Agent 安全场景下，每个 `app_id` 对应一个具体的 Agent 实体（如"代码审查智能体"、"数据分析智能体"），不需要"类型 vs 实例"的分离。Runtime License、策略、risk_score 均绑 `app_id`。

**Agent 运行许可证（Runtime License）**：

virbius-control 为每个 `app_id` 签发运行许可证，各层在关键路径上校验：

```
virbius-control 签发 License（JWT 签名）：
{
  "app_id": "code-review-agent",      // Agent 身份
  "tenant_id": "公司A",
  "allowed_tools": ["read_file", "search", "curl"],
  "allowed_scenes": ["code_review", "data_analysis"],
  "risk_quota": 60,                    // 最大允许的 session_risk_score
  "tool_rate_limit": 50,               // 每分钟最大工具调用数
  "expiry": "2026-07-06T12:00:00Z",
  "signature": "RS256..."
}
```

| 校验点 | 校验内容 |
|--------|---------|
| 管层 Higress 入口 | License 签名 + 过期 + 吊销状态 |
| 端层 virbius-core | License 的 allowed_tools 是否包含当前工具 |
| 云层 virbius-engine | 当前 session_risk_score 是否超过 License 的 risk_quota |

**许可证吊销**：通过 Redis pub/sub 实时通知各层。吊销后该 `app_id` 的所有后续请求被拒绝。
**会话中过期处理**：License 在会话进行中过期时，当前正在执行的工具调用允许完成（保持原子性），但完成后立即拒绝后续请求并通知 Agent 需要重新授权。端层 virbius-core 在每次预检时校验 License 剩余有效期，剩 5 分钟内到期时发出告警。

### 1.5 三层安全架构

端管核云是**部署拓扑视角**（组件在哪里运行），三层安全架构是**功能管控视角**（安全能力如何编排）。两者正交，同一功能可跨多层部署。

```
┌─────────────────────────────────────────────────────────┐
│  第一层：身份管控层                                       │
│  统一身份体系 + 智能体运行许可证                           │
│  (§1.4)                                                  │
├─────────────────────────────────────────────────────────┤
│  第二层：运行时防护层                                     │
│  ┌─────────┬─────────┬─────────┬─────────┬─────────┐    │
│  │意图研判 │提示增强 │记忆管控 │工具拦截 │输出审查 │    │
│  │§5.3+5.4 │§2.8     │§2.9     │§2.1+3.2 │§2.10    │    │
│  │+§2.8.7  │         │         │+§5.3    │         │    │
│  └─────────┴─────────┴─────────┴─────────┴─────────┘    │
├─────────────────────────────────────────────────────────┤
│  第三层：基础设施层                                       │
│  Landlock沙箱 + 命名空间隔离 + eBPF过滤   │
│  (§2.3 + §2.4 + §4 + §2.3.1)                            │
└─────────────────────────────────────────────────────────┘
```

**与端管核云的映射关系**：

| 三层架构 | 端层(Edge) | 管层(Gateway) | 核层(Kernel) | 云层(Cloud) |
|---------|-----------|--------------|-------------|------------|
| **身份管控层** | License 校验(allowed_tools) | License 校验(签名/过期/吊销) | — | License 签发 + risk_quota 校验 |
| **运行时防护层** | 预检 + Prompt Gateway + 记忆管控 + 输出审查 | allowlist + 计数 + engine 调用 | — | Groovy L3 + STI + Prompt 入侵检测 |
| **基础设施层** | Landlock + gVisor + 命名空间隔离 | — | Falco + eBPF | — |

**第一层：身份管控层**

建立统一身份体系（tenant_id → app_id → session_id → device_id → trace_id，§1.4），以 Runtime License 为核心实现 Agent 身份的全生命周期管控：签发（control）、校验（端/管/云三层）、吊销（Redis pub/sub）、过期处理（原子性保证）。详见 §1.4。

**第二层：运行时防护层**

以"企业 AI 智能体宪法"为准则，通过五层策略实现 Agent 运行时全流程管控：

| 策略 | 职责 | 设计章节 | 性质 |
|------|------|---------|------|
| **意图研判** | 判定 Agent 意图与工具调用链是否安全 | §5.3 Groovy L3 + §5.4 STI Suitability + §2.8.7 Prompt 入侵检测 | 检测 |
| **提示增强** | 注入宪法约束，预防危险意图产生 | §2.8 Prompt Gateway | 预防 |
| **记忆管控** | Agent 记忆读写拦截 + 脱敏 + 注入检测 | §2.9 Memory Interceptor | 预防+检测 |
| **工具拦截** | 参数校验 + allowlist + schema + 工具链检测 | §2.1 端层预检 + §3.2 管层 WASM + §5.3 云层 L3 | 检测+阻断 |
| **输出审查** | 工具结果内容安全审查 + Agent 最终响应审查 | §2.10 Output Review | 检测+阻断 |

运行时防护流程：

```
用户输入
  → [意图研判] Prompt 入侵检测（§2.8.7）+ session risk 评估
  → [提示增强] Prompt Gateway 宪法注入 + PII 脱敏（§2.8）
  → LLM 推理
  → [记忆管控] Memory Interceptor 拦截记忆读写（§2.9）
  → [工具拦截] 端层预检 → 管层规则 → 云层 L3 终判（§2.1 + §3.2 + §5.3）
  → 工具执行
  → [输出审查] STI Taint + 最终响应审查（§5.4 + §2.10）
  → 返回用户
```

**第三层：基础设施层**

构建安全可信 Agent OS，为运行时防护层提供 syscall 级隔离与观测基座：

| 能力 | 技术 | 设计章节 | 阶段 |
|------|------|---------|------|
| 文件路径隔离 | Landlock | §2.3 | P2 |
| 命名空间隔离 | clone3(CLONE_NEWPID \| CLONE_NEWNS \| CLONE_NEWNET) | §2.3.1 | P2 |
| capabilities 丢弃 | drop caps | §2.3 | P2 |
| 不可信代码沙箱 | gVisor runsc 预热池 | §2.4 | P2 |
| 内核观测 | Falco eBPF | §4 | P0 |


---

## 2. 端层 — Agent 工具调用预检与执行

### 2.1 职责

| 阶段 | 动作 | 延迟 |
|------|------|------|
| **预检** | 参数校验、tool allowlist、JSON Schema 校验、本地规则匹配 | <0.5ms |
| **执行** | P0: 同进程执行 / P2: Landlock / gVisor 沙箱 | P0: <0.1ms / P2: 见 §2.2 |

**关键约束**：预检阶段不执行任何工具逻辑。只有终判返回 allow 后才进入执行阶段。

### 2.2 分层隔离策略(P0 -> P2 渐进)

```
ToolCallRequest { name, args }
  |
  +-- sandbox_type = "none" (P0)
  |    同进程执行（只做预检，不隔离）
  |    适用：所有工具（P0 阶段不区分沙箱类型）
  |    延迟：冷 <0.1ms / 热 <0.1ms
  |    安全保障：HTTP 层阻断 + session risk 累积 + Falco 观测
  |
  +-- sandbox_type = "subprocess" (P2)
  |    posix_spawn + Landlock + drop caps
  |    适用：read_file、write_file、curl（白名单目标）
  |    延迟：冷 ~2ms / 热 ~1ms
  |
  +-- sandbox_type = "gvisor" (P2)
  |    gVisor runsc 容器（预热池）
  |    适用：execute_python、shell、任意不可信代码
  |    延迟：冷 1-5s / 热 ~50ms（预热池命中）
  |
  +-- deny
       直接拒绝，不执行
```

> **P0 安全模型**：无 syscall 级隔离。安全保障依赖：
> 1. HTTP 层阻断（端层 Proxy allow/deny 或管层 Higress allow/deny + engine 终判，取决于部署模式）
> 2. 参数 schema 校验（path 白名单等）
> 3. session risk 累积（Falco 检测异常 -> 风险分升高 -> 后续请求阻断）
> 4. 高风险工具（execute_python、shell）P0 阶段强制人工审批或禁用

### 2.3 P2: Landlock + drop caps 子进程(Linux)

> **P2 实现，P0 不涉及。** 以下为长期设计参考。

**设计决策**：P2 subprocess 沙箱采用 Landlock + drop caps，不使用 seccomp-notify。

| 维度 | Landlock + drop caps | seccomp-notify（原方案，已弃用） |
|------|---------------------|-------------------------------|
| 文件路径限制 | ✅ Landlock 文件规则 | ✅ open/openat 拦截 |
| 网络 IP 限制（SSRF） | ❌ Landlock v4 只限端口不限 IP | ✅ connect 拦截 |
| supervisor SPOF | ✅ 无 supervisor | ❌ 崩溃=进程挂起 |
| TOCTOU 风险 | ✅ 内核强制，无竞态 | ❌ 需 ioctl 校验 |
| 实现复杂度 | ~5w | ~16w |

**SSRF 防护补偿**：Landlock 不能按 IP 限制 connect，但 subprocess 沙箱仅用于 `read_file`/`write_file`/`curl（白名单目标）`，不用于 `execute_python`/`shell`（后者走 gVisor）。`curl` 的 URL 在应用层已做 schema 校验 + 白名单校验（Sidecar 模式由端层 MCP Proxy 代发时校验，非 Sidecar 模式由管层 Higress Egress 校验，见 [§3.5](#35-egress-流量管控)），不需要 syscall 级 connect 拦截。网络层由 K8s NetworkPolicy 兜底。

**多线程安全**：Agent 框架基于 tokio 异步运行时，是多线程的。子进程创建使用 `std::process::Command::spawn`（内部走 `fork+exec`），并通过 `pre_exec` hook 在 fork 与 exec 之间应用 Landlock 限制，避免在多线程进程中长期存活子进程的 race。

> **架构变更（方案 B）**：原设计使用 LD_PRELOAD 注入 C 共享库 (`libvirbius_sandbox_preload.so`) 来在子进程 `main()` 前应用 Landlock。已改为 `std::os::unix::process::CommandExt::pre_exec` 在 Rust 侧 fork 与 exec 之间直接调用 Landlock syscall。这样：
> 1. 零额外构建产物（无需编译/部署 `.so`）
> 2. 单一构建系统（纯 Cargo）
> 3. 错误可观测（`pre_exec` 返回 `Err` 会让 `spawn()` 失败，不像 LD_PRELOAD 缺失只打警告继续跑）
> 4. 所有堆分配在父进程 `PreparedRules::compile` 完成，子进程 `pre_exec` 闭包只读遍历 + raw syscall，遵守 async-signal-safety

**Landlock（P2 核心）**：

```rust
// virbius-core/src/sandbox/landlock.rs (P2)
//
// 所有堆分配在父进程的 PreparedRules::compile 里完成；
// pre_exec 闭包在 fork 后、exec 前的子进程里执行，只能用
// async-signal-safe 操作（raw syscall / open / close，无 malloc / Mutex）。

pub struct LandlockSandbox {
    config: SandboxConfig,
    abi: LandlockAbi,
}

pub struct LandlockRules {
    // v1 (kernel 5.13+): 文件路径（glob，父进程展开为具体路径）
    pub read_paths: Vec<String>,      // 只读路径，如 ["/usr/*", "/lib/*"]
    pub write_paths: Vec<String>,     // 读写路径，如 ["/tmp/workdir/*"]
    pub exec_paths: Vec<String>,      // 可执行路径，如 ["/usr/bin/*"]
    // v4 (kernel 6.7+): 网络端口（可选，不支持则跳过）
    pub bind_ports: Vec<u16>,         // 允许绑定的端口
    pub connect_ports: Vec<u16>,     // 允许连接的端口
}

/// 父进程预编译：glob 展开 + 转 CString，供 pre_exec 闭包只读使用
struct PreparedRules {
    abi: LandlockAbi,
    read_paths: Vec<CString>,   // glob 展开后的具体路径
    write_paths: Vec<CString>,
    exec_paths: Vec<CString>,
    bind_ports: Vec<u16>,
    connect_ports: Vec<u16>,
}

impl LandlockSandbox {
    /// spawn + pre_exec(Landlock + drop caps) -> 执行子进程
    pub fn execute(&self, program: &str, args: &[String]) -> Result<SandboxResult, String> {
        // 父进程：预编译规则（安全分配）
        let prepared = PreparedRules::compile(&self.config.rules);
        let prepared_for_hook = prepared.clone();

        let mut child = Command::new(program)
            .args(args)
            .pre_exec(move || {
                // 子进程内，fork 后 exec 前。async-signal-safe。
                // 1. landlock_create_ruleset + add_rule(path/net) + restrict_self
                // 2. capset(drop ALL) + prctl(PR_SET_NO_NEW_PRIVS)
                apply_landlock(&prepared_for_hook)
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()?;

        // 父进程：等待 + 超时 + 读 stdout（无 supervisor，无 /dev/seccomp）
        // ...
    }
}
```

**pre_exec hook 的工作**（全部用 raw syscall，async-signal-safe）：

```rust
// virbius-core/src/sandbox/landlock.rs
//
// 顺序：Landlock -> drop caps（两步，无 seccomp）

fn apply_landlock(rules: &PreparedRules) -> io::Result<ApplyReport> {
    if rules.abi == LandlockAbi::None {
        // 降级：只 drop caps
        return drop_caps_and_no_new_privs();
    }

    // 1. Landlock: 创建 ruleset + 添加规则 + restrict_self
    //    检测 ABI 版本：v1(5.13+, 文件) / v4(6.7+, 网络)
    //    不支持网络 v4 则跳过网络规则，只做文件
    //    Landlock 无 audit 模式，只能 enforce（deny），不产生观测事件
    let fd = syscall(SYS_landlock_create_ruleset, &attr, size, 0)?;
    for path in &rules.read_paths  { add_path_rule(fd, path, ACCESS_FS_READ)?; }
    for path in &rules.write_paths { add_path_rule(fd, path, ACCESS_FS_WRITE)?; }
    for path in &rules.exec_paths  { add_path_rule(fd, path, ACCESS_FS_EXECUTE)?; }
    if rules.abi.supports_net() {
        for port in &rules.bind_ports     { add_net_rule(fd, *port, ACCESS_NET_BIND_TCP)?; }
        for port in &rules.connect_ports  { add_net_rule(fd, *port, ACCESS_NET_CONNECT_TCP)?; }
    }
    syscall(SYS_landlock_restrict_self, fd, 0)?;
    close(fd);

    // 2. Capabilities: 丢弃所有 CAP_*
    //    Landlock 不覆盖的威胁面由 drop caps 补充：
    //    - CAP_NET_RAW: 禁止 raw socket（ping/抓包）
    //    - CAP_SYS_PTRACE: 禁止 ptrace 注入其他进程（防逃逸）
    //    - CAP_SYS_ADMIN: 禁止 mount/namespace 操作
    //    - CAP_NET_ADMIN: 禁止改 iptables/路由
    //    - CAP_SYS_MODULE: 禁止加载内核模块
    drop_caps_and_no_new_privs()?;

    // 3. prctl(PR_SET_NO_NEW_PRIVS) 已包含在上一步
    // 4. 环境变量无需清理（不再用 LD_PRELOAD / VIRBIUS_* env 传递规则）
    Ok(ApplyReport { landlock_applied: true, caps_dropped: true })
}
```

**Landlock + drop caps 的职责分工**：

| 威胁 | Landlock 覆盖 | drop caps 覆盖 |
|------|-------------|---------------|
| 读越权文件 | ✅ 路径规则 | - |
| 写越权文件 | ✅ 路径规则 | - |
| 执行越权二进制 | ✅ 路径规则 | - |
| raw socket（ping/抓包） | ❌ | ✅ 去 CAP_NET_RAW |
| ptrace 注入其他进程（逃逸） | ❌ | ✅ 去 CAP_SYS_PTRACE |
| mount 伪造文件系统 | ❌ | ✅ 去 CAP_SYS_ADMIN |
| 改 iptables/路由（流量劫持） | ❌ | ✅ 去 CAP_NET_ADMIN |
| 加载内核模块 | ❌ | ✅ 去 CAP_SYS_MODULE |

**Landlock ABI 版本适配**：

```rust
pub fn detect_abi_version() -> LandlockAbi {
    // 尝试创建 ruleset 测试支持的 ABI 版本
    // v1 (5.13+): 文件路径
    // v2 (5.19+): 文件 + 引用
    // v3 (6.2+):  文件 + 设备
    // v4 (6.7+): 文件 + 网络
    if try_create_ruleset(with_net = true)  { return LandlockAbi::V4; }
    if try_create_ruleset(with_net = false) { return LandlockAbi::V1; }
    LandlockAbi::None
}
```

> **注**：Landlock 网络(v4)需要内核 6.7+，2026 年仍有大量内核不满足。P2 先只做文件路径限制(v1, 5.13+)，网络限制由 NetworkPolicy 承担。

**Landlock 规则示例**（read_file 工具）：

```json
{
  "tool_name": "read_file",
  "exec_env": {
    "sandbox_type": "subprocess",
    "landlock_rules": {
      "read_paths": ["/tmp/data/*", "/home/user/workdir/*", "/usr/lib/*"],
      "write_paths": [],
      "exec_paths": ["/usr/bin/cat", "/usr/bin/head"]
    },
    "drop_caps": "all",
    "timeout_ms": 5000
  }
}
```

关于 macOS：不支持 Landlock。macOS 为开发环境，P2 沙箱不启用，降级为同进程执行 + 告警日志。生产环境部署在 Linux/K8s，Landlock 可用。

### 2.4 P2: gVisor 子进程 + 预热池

> **P2 实现，P0 不涉及。**

对于不可信代码执行，通过 gVisor runsc 启动隔离容器。gVisor 冷启动 1-5 秒，**必须使用预热池**：

```rust
// virbius-core/src/sandbox/gvisor_pool.rs (P2)
pub struct GvisorPool {
    config: GvisorPoolConfig,
    warm: Arc<Mutex<HashMap<Language, Vec<WarmContainer>>>>,
    runsc_available: bool,
}

pub struct GvisorPoolConfig {
    pub runsc_path: String,
    pub rootfs_path: String,
    pub min_warm: usize,       // 每语言最小预热容器数
    pub max_idle: usize,       // 每语言最大空闲容器数
    pub memory_limit_bytes: u64,
    pub cpu_quota: f64,
    pub network_disabled: bool,
    pub exec_timeout: Duration,
}
```

**执行流程**：`execute(language, code)` → 从预热池获取容器（热路径 ~50ms）→ 写入 stdin → 读取 stdout → 销毁已用容器 → 后台补充新容器。冷启动 1-5s。

**降级策略**：gVisor 不可用时（runsc 未安装），自动降级为 Landlock subprocess + 超时 5s 强制 kill + 限制内存。

### 2.5 与 virbius-control 的同步

端层策略复用 virbius-core 现有 manifest 同步机制：

```rust
// 扩展 EdgeManifest
struct EdgeManifest {
    #[serde(default)]
    rules: Vec<EdgeRule>,               // 已有：关键词规则
    #[serde(default)]
    dlp_rules: Vec<DlpRule>,            // 已有：DLP 规则
    #[serde(default)]
    tool_policies: Vec<ToolPolicy>,     // 新增：工具策略（allowlist + schema + fast_path）
    #[serde(default)]
    landlock_profiles: HashMap<String, LandlockProfile>, // P2：Landlock 模板
    sdk_config: SdkConfig,
}
```

> **注**：landlock_profiles 可能体积较大，建议提供独立 fetch 端点 /api/v1/edge/landlock-profiles，不随主 manifest 全量拉取。


### 2.6 MCP Server 集成

> **本节已拆分至独立文档**：[PROTOCOL.zh.md](PROTOCOL.zh.md) — 包含 MCP Proxy 完整技术方案（架构、协议处理、拦截流程、会话管理、Fallback 策略、错误码定义、配置、部署模式、实现结构、核心代码）。

---

### 2.7 快速通道(低风险工具跳过云层)

对于低风险工具(search、计算器、格式化)，快速通道允许跳过云层 RPC：

```
低风险工具 (fast_path=true)
  -> 端层预检 (参数校验 + allowlist)
  -> 本地 risk 缓存判断 (< threshold?)
  <- allow (不调 virbius-engine)
  -> 端层执行 (同进程)
```

**判断条件（3 项，全部满足才走快速通道）**：

| 条件 | 说明 | 数据来源 |
|------|------|---------|
| `fast_path == true` | ToolPolicy 标记为快速通道 | 本地 manifest 缓存 |
| `sandbox_type == "none"` | 无需沙箱隔离 | 本地 manifest 缓存 |
| `local_risk < threshold` | 本地缓存的 session 风险分低于阈值（默认 30） | 本地 SessionStateCache |

任一条件不满足时，回退到全链路。

> **简化说明**：原设计有 4 个条件（含 `tool_name in fast_allowlist`），但 `fast_path == true` 已是 per-tool 策略标记，与 `fast_allowlist` 语义重复，故合并为 3 项。

**SessionStateCache——本地风险分缓存**：

快速通道的核心问题是：`session_risk_score` 由云层 engine 计算，端层如何低延迟获取？答案是端层维护本地缓存，不实时查云层。

```
┌─── 端层 Proxy / 管层 Higress ──────────────────────┐
│                                                      │
│  SessionStateCache (内存 LRU, TTL=60s)               │
│  ┌──────────────────────────────────────────────┐    │
│  │ session_id -> { risk_score, last_updated }   │    │
│  │                                              │    │
│  │ "sess_abc" -> { risk: 15, updated: 12:00:30 }│    │
│  │ "sess_def" -> { risk: 45, updated: 12:00:15 }│    │
│  └──────────────────────────────────────────────┘    │
│                                                      │
│  更新来源（3 种，互为补充）：                           │
│  1. 全链路返回时回填：engine /v1/evaluate 响应含        │
│     risk_score -> 同步更新本地缓存                      │
│  2. Redis pub/sub 异步推送：engine 计算完风险后发布     │
│     到 channel "risk:{session_id}" -> 端层订阅更新     │
│  3. TTL 过期兜底：缓存条目 60s 过期后，下次请求强制      │
│     走全链路（回退到来源 1 回填）                        │
│                                                      │
│  读取：快速通道判断时直接读内存，零网络开销               │
└──────────────────────────────────────────────────────┘
```

| 更新机制 | 延迟 | 触发条件 | 说明 |
|---------|------|---------|------|
| 全链路回填 | 实时 | 每次全链路调用返回 | engine 响应 body 含 `risk_score`，Proxy 同步写入缓存 |
| Redis pub/sub | ~1ms | engine 异步风险更新 | engine 发布 `risk:{session_id}` → Proxy 订阅更新 |
| TTL 过期 | 60s | 缓存条目超时 | 过期后 `local_risk` 视为 `None`，强制走全链路 |

> **设计决策：不主动查 Redis/Engine 获取 risk_score**
>
> 快速通道的目标是"零网络开销决策"。每次调用都查 Redis（~1ms）或 Engine（~10-50ms）会抵消快速通道的意义。改为：
> - **写时更新**：全链路调用返回时回填缓存（被动更新）
> - **推送更新**：engine 异步计算后通过 Redis pub/sub 推送（主动更新）
> - **过期回退**：缓存 TTL 过期后强制走全链路（安全兜底）
>
> 最坏情况：risk_score 滞后 60s。在此期间若 session 风险已升高但缓存未更新，快速通道可能放行本应拦截的请求。缓解措施：快速通道工具的审计事件全量采样（sample_rate=1.0），engine 异步复核发现违规后通过 pub/sub 提升风险分，后续请求立即退出快速通道。

**冷启动防护**：新 session 无缓存条目（`local_risk == None`），前 N 次调用强制走全链路（warmup），N 次后缓存填充完毕且 risk < threshold 才开放快速通道。

**fail-open/fail-closed**：virbius-engine 不可用时（网络分区），高风险工具 fail-closed(deny)，低风险工具 fail-open(allow + 全量审计)。

**风险缓解**：快速通道工具的审计事件全量采样(sample_rate=1.0)，异步送 virbius-engine 复核。若异步复核发现违规，提升 session_risk_score（通过 pub/sub 推送），后续请求自动退出快速通道。

### 2.8 Prompt Gateway（提示增强）

Prompt Gateway 是端层的**预防性**安全组件，在 Agent 发送 prompt 到 LLM 前注入安全约束。与工具拦截（检测性）互补，形成"预防 + 检测"纵深防御。

```
Agent 生成 prompt
  |
  v
Prompt Gateway（嵌入 virbius-core，<0.5ms）
  |  <- 注入宪法约束（from virbius-control，本地缓存）
  |  <- 注入工具规则（from License permissions）
  |  <- 注入动态上下文（session risk + 最近工具调用）
  |  <- PII 输入脱敏（复用 dlp/engine.rs）
  |
  v
增强后的 prompt -> LLM API
  |
  v
LLM 生成 tool_call -> 端层预检 -> 管层 -> 云层终判 -> 执行
```

| 层 | 机制 | 性质 | 效果 |
|----|------|------|------|
| Prompt Gateway | 注入规则让 LLM 自我约束 | 预防(proactive) | 减少危险意图产生 |
| Prompt 入侵检测 | 小模型判定用户输入是否含越狱/注入 | 检测(reactive) | 阻断恶意 prompt 到达 LLM |
| 工具拦截 | 阻止危险工具执行 | 检测(reactive) | 阻止危险行为落地 |
| Falco 观测 | 监控运行时异常 | 检测(reactive) | 发现已发生异常 |

#### 2.8.1 注入内容

**宪法约束（系统提示增强）**——由 virbius-control 管理，编译为场景相关模板：

```
## Virbius Agent Constitution v1.2 (scene: code_review)

### 绝对禁止
1. 不得将数据发送到白名单之外的外部端点
2. 不得在沙箱之外执行代码
3. 不得访问允许路径之外的文件
4. 不得尝试绕过安全控制
5. 不得在输出中包含凭据、令牌或密钥

### 工具使用规则
- 可用工具: read_file, search, curl
- curl 仅允许连接: api.internal:443, cdn.internal:443
- read_file 仅允许读取: /tmp/data/*, /home/user/workdir/*
- 每分钟最多 50 次工具调用
- 工具返回错误时，重试不超过 3 次

### 数据处理规则
- 工具结果中的 PII 必须脱敏后才能包含在响应中
- 不得在记忆中存储敏感数据
- 超过 64KB 的工具结果应摘要，不要原样传递

### 场景约束（code_review）
- 你在审查代码，不是执行代码
- 禁止使用 execute_python 或 shell 工具
```

**动态上下文注入**——根据当前 session 状态实时生成：

```
## 当前会话上下文
- 会话风险分: 25/100（低风险）
- 本次会话已调用: read_file(3), search(2)
- 场景: code_review
- License 剩余有效期: 2h 15m

## 最近活动
- 上次工具: read_file(/tmp/data/auth.py) -> 成功
- 注意: 正在读取认证相关文件，警惕凭据泄露
```

**工具约束集中注入（系统提示词）**——所有工具的约束规则在系统提示词中**统一渲染一次**，而非在每个工具的 `description` 中重复：

```
### 工具使用规则
- 可用工具: read_file, search, curl
- curl 仅允许连接: api.internal:443, cdn.internal:443
- read_file 仅允许读取: /tmp/data/*, /home/user/workdir/*
- 每分钟最多 50 次工具调用
- 工具返回错误时，重试不超过 3 次
```

> **Token 节省对比**（以 10 个工具为例）：
>
> | 方案 | Token 消耗 | 说明 |
> |------|-----------|------|
> | ~~旧：description 注入~~ | ~500 token | 每工具 ~50 token × 10 工具 |
> | 新：系统提示词集中注入 | ~80 token | 约束规则只在 system prompt 出现一次 |
> | 节省 | ~420 token | **减少 ~84%** |
>
> 工具 `description` 保持原始简洁文本不变；结构化约束通过 MCP `annotations` 字段传递（[§2.6.1](PROTOCOL.zh.md#261-mcp-proxy-full-technical-solution)），供 MCP 客户端 UI 和预检逻辑消费，不进入 LLM prompt。

**PII 输入脱敏**——复用现有 virbius-core/src/dlp/engine.rs，在 prompt 发送前脱敏用户输入。

#### 2.8.2 实现

```rust
// virbius-core/src/prompt_gateway.rs

pub struct PromptGateway {
    constitution_cache: RwLock<ConstitutionTemplates>,  // 本地缓存，sync from control
    dlp_engine: DlpEngine,                               // 复用现有
}

pub struct EnhanceContext<'a> {
    pub license: &'a LicenseContext,
    pub session_id: &'a str,
    pub scene: &'a str,
    pub risk_score: u32,
    pub recent_tools: Vec<ToolCallSummary>,
}

impl PromptGateway {
    /// 增强 prompt，返回增强后的 messages
    pub fn enhance(
        &self,
        messages: &mut Vec<ChatMessage>,
        ctx: &EnhanceContext,
    ) -> Result<()> {
        // 1. 宪法约束注入（prepend to system message）
        //    含：禁止规则 + 工具使用规则（集中渲染，不修改各工具 description）
        let constitution = self.constitution_cache.read();
        let rules = constitution.select(ctx.scene, ctx.license.constitution_version);
        let system_augment = rules.render(ctx);  // 渲染含 tool_constraints 的完整宪法
        self.prepend_system(messages, &system_augment)?;

        // 2. 动态上下文注入（append to system message）
        let dynamic_ctx = self.render_dynamic_context(ctx);
        self.append_system(messages, &dynamic_ctx)?;

        // （已移除）工具描述增强 —— 不再修改各工具 description，避免 token 膨胀
        // 工具约束改为在 step 1 的宪法系统提示词中集中渲染（§2.8.1）

        // 3. PII 输入脱敏（仅 user/assistant 消息，不改 system）
        for msg in messages.iter_mut() {
            if msg.role == Role::User || msg.role == Role::Assistant {
                msg.content = self.dlp_engine.desensitize_in(
                    &msg.content, ctx.session_id, ...
                )?;
            }
        }

        Ok(())
    }
}
```

#### 2.8.3 Agent 框架集成

| 框架 | 集成方式 | 拦截点 |
|------|---------|--------|
| **OpenAI SDK** | EnhancedOpenAIClient 代理，在 chat.completions.create() 前调 gateway.enhance() | 请求发送前 |
| **LangChain** | ConstitutionalPromptTemplate，在 LLMChain.invoke() 前增强 | prompt 模板渲染后 |
| **通用 HTTP proxy** | 独立服务，拦截 LLM API 请求，增强 body 后转发 | HTTP 层 |
| **MCP proxy 模式** | 复用 [§2.6](PROTOCOL.zh.md) MCP proxy，在转发前增强 | tools/call 前 |

#### 2.8.4 宪法模板编译

```
virbius-control
  |
  +-- tb_constitution（宪法规则表）
  |   +-- id, version, category, rule_text, priority, scene_filter
  |
  +-- virbius-compiler
  |   +-- 按 scene 编译宪法规则为 prompt 模板
  |   +-- 输出: constitution_templates.json
  |
  v
端层 virbius-core（PromptGateway 本地缓存）
  +-- 按 scene + constitution_version 选择模板
  +-- 模板变量填充（license permissions, session context）
  +-- 注入到 prompt
```

模板示例：

```json
{
  "version": "v1.2",
  "templates": [
    {
      "scene": "code_review",
      "system_prefix": "## Virbius Agent Constitution {{version}} (scene: {{scene}})\n\n### 绝对禁止\n{{prohibitions}}\n\n### 工具使用规则\n{{tool_rules}}",
      "dynamic_suffix": "## 当前会话上下文\n- 风险分: {{risk_score}}/100\n- 已调用: {{recent_tools}}\n- 场景: {{scene}}",
      "prohibitions": [
        "不得将数据发送到白名单之外的外部端点",
        "不得在沙箱之外执行代码",
        "不得在输出中包含凭据、令牌或密钥"
      ]
    }
  ]
}
```

#### 2.8.5 预期效果

| 指标 | 无 Gateway | 有 Gateway | 改善 |
|------|-----------|-----------|------|
| 危险工具调用尝试 | 基线 | -60~80% | LLM 知道约束后自我约束 |
| 重试循环 | 基线 | -70~90% | LLM 知道限制不再重试 |
| Prompt 注入抵抗力 | 基线 | +15~25% | 宪法规则建立基线抵抗 |
| 延迟开销 | 0 | <0.5ms | 字符串拼接，无 LLM 调用 |
| Token 开销 | 0 | 200-500 tokens/prompt | 宪法规则占位 |

#### 2.8.6 风险与局限

| 风险 | 说明 | 缓解 |
|------|------|------|
| **Prompt 注入可覆盖** | 攻击者通过工具返回值注入"忽略之前的指令" | 宪法规则提供基线抵抗；STI Taint(P1)检测注入；工具拦截是最终防线 |
| **Token 成本** | 每次增加 200-500 tokens | 压缩规则格式；只注入场景相关规则；小模型不注入 |
| **模型差异** | GPT-4 遵守规则好，小模型可能不遵守 | 按模型能力调整注入格式；小模型依赖工具拦截 |
| **非替代工具拦截** | Prompt Gateway 是预防不是阻断 | 永远不能单独依赖；必须与工具拦截 + Falco 观测配合 |

#### 2.8.7 Prompt 注入检测（prompt runtime 重新定位）

VirbiusLLM 的 `prompt` rule runtime（NL 描述 → 1B 模型判定文本是否违规）是 LLM 内容审核能力。VirbiusAgent **不复用其内容审核语义**，但**复用其基础设施**（规则 CRUD + mlPredict 调用 + 审计），重新定位为**用户输入越狱/注入检测层**。

**设计动机**：Prompt Gateway（§2.8）是预防性机制（注入宪法约束，靠 LLM 自觉遵守），对用户输入本身无检测性判定。`prompt` runtime 重新定位后填补此缺口，形成"预防 + 检测"的 prompt 纵深：

```
用户输入 prompt
  |
  v
[检测] prompt runtime（小模型判定越狱/注入）
  |     +-- 命中 → block 或提升 session_risk_score
  |     +-- 未命中 → 继续
  v
[预防] Prompt Gateway（注入宪法约束 + PII 脱敏）
  |
  v
增强后的 prompt -> LLM API
  |
  v
LLM 生成 tool_call -> 工具拦截（Groovy L3 + schema + allowlist）
```

**与 VirbiusLLM prompt runtime 的区别**：

| 维度 | VirbiusLLM（原用法） | VirbiusAgent（重新定位） |
|------|---------------------|------------------------|
| 判定对象 | prompt + response 文本 | 仅用户输入 prompt |
| 判定目标 | 内容安全（暴力/色情/违规） | 越狱/注入（DAN/ignore previous/角色劫持） |
| 模型 | 1B 内容分类模型 | qwen3guard:0.6b（复用 STI Taint 同模型） |
| 命中动作 | block + 审计 | block 或提升 session_risk_score + 审计 |
| 规则配置 | NL 描述（运营台 prompt runtime） | 同（复用现有规则 UI） |
| 与 Prompt Gateway 关系 | 无 | 互补：Gateway 预防，runtime 检测 |

**规则配置**：复用现有运营台 cloud 层 `prompt` runtime 的规则 CRUD（NL 描述 → 触发条件）。运营人员编写如"检测 DAN 越狱尝试"、"检测 ignore previous instructions 注入"等规则，engine 通过 mlPredict 调用小模型判定。

**成本控制**：与 STI Taint 共享 qwen3guard:0.6b 小模型（本地 Ollama 部署，单次 <200ms）。仅对用户输入触发，不对工具返回值触发（后者由 STI Taint 覆盖）。

**命中策略**：

| session_risk_score | 命中动作 | 说明 |
|-------------------|---------|------|
| < 30 | block + 审计 | 低风险 session 直接阻断 |
| 30-60 | allow + 提升 risk_score + 审计 | 中风险允许但累积风险 |
| > 60 | block + 审计 | 高风险 session 直接阻断 |

**与 STI 的分工**：

| 检测层 | 作用对象 | 触发条件 | 机制 |
|--------|---------|---------|------|
| **prompt runtime（本节）** | 用户输入 prompt | 每次用户输入 | 小模型判定越狱/注入 |
| **STI Taint（§5.4）** | 工具返回值 | 返回值 >2KB 或含注入标记 | 小模型判定返回值是否含注入指令 |

两者共同覆盖 prompt 注入的两个入口（用户输入 + 工具返回值），与 Prompt Gateway（预防）和工具拦截（执行阻断）构成四层纵深。

### 2.9 记忆管控（Memory Interceptor）

> **P1 实现。** Agent 记忆（long-term memory / vector store）是 prompt 注入的持久化载体——攻击者可通过工具返回值将恶意指令写入记忆，在后续会话中被召回执行。Memory Interceptor 拦截 Agent 记忆的读写，实现脱敏 + 注入检测 + 审计。

**拦截点**：

```
Agent 记忆操作
  |
  +-- 写入（write/save/embed）
  |    → [脱敏] PII 检测 + 替换（复用 dlp/engine.rs）
  |    → [注入检测] 小模型判定是否含恶意指令
  |    → [审计] 记录原始/脱敏后内容 + 检测结果
  |    → 通过 → 写入记忆存储
  |    → 拦截 → 丢弃 + 提升 session_risk_score
  |
  +-- 读取（read/search/recall）
       → [注入检测] 判定召回内容是否含注入标记
       → [审计] 记录召回内容 + 检测结果
       → 通过 → 返回 Agent
       → 拦截 → 过滤恶意片段 + 告警
```

**框架集成**：

| 框架 | 集成方式 | 拦截点 |
|------|---------|--------|
| **LangChain** | MemoryInterceptor wrapper，包装 Memory.save_context() / Memory.load_memory_variables() | 记忆读写 API |
| **OpenAI SDK** | 拦截 Assistants API 的 message create/retrieve | API 调用层 |
| **通用** | 独立记忆代理服务，Agent 记忆操作经代理转发 | HTTP/gRPC proxy |

**数据模型**：

```rust
// virbius-core/src/memory_interceptor.rs (P1)

pub struct MemoryInterceptor {
    dlp_engine: DlpEngine,                              // 复用现有 PII 脱敏
    guard_model: GuardModelClient,                      // qwen3guard:0.6b，复用 STI Taint
    policies: MemoryPolicies,                           // from virbius-control
}

pub struct MemoryPolicies {
    pub desensitize_on_write: bool,                     // 写入时脱敏
    pub detect_injection_on_write: bool,                // 写入时注入检测
    pub detect_injection_on_read: bool,                 // 读取时注入检测
    pub max_memory_entry_size: usize,                   // 单条记忆大小上限（默认 4KB）
    pub blocked_patterns: Vec<String>,                  // 禁止写入的模式
}

pub struct MemoryAuditEvent {
    pub trace_id: String,
    pub session_id: String,
    pub operation: MemoryOp,                            // Write / Read
    pub original_size: usize,
    pub desensitized: bool,
    pub injection_detected: bool,
    pub action: MemoryAction,                           // Allow / Filter / Block
    pub risk_delta: u32,
}
```

**脱敏策略**：

| 场景 | 处理 |
|------|------|
| 写入记忆含 PII（手机号/身份证/邮箱/银行卡） | 脱敏后写入（复用 dlp/engine.rs desensitize_in），原始值存 vault |
| 写入记忆含凭据/密钥模式 | 直接 block + 审计 |
| 读取召回内容含 PII | 不脱敏（Agent 需原始值执行工具），但审计记录 |

**注入检测**：

| 检测项 | 机制 | 命中动作 |
|--------|------|---------|
| "ignore previous instructions" 等注入标记 | qwen3guard 小模型判定 | 写入：block；读取：过滤片段 |
| 工具返回值原样写入记忆（未摘要） | 规则：内容与工具返回值 hash 匹配 | block + 提示 Agent 摘要后写入 |
| 记忆内容超过 max_memory_entry_size | 规则：size 检查 | block + 提示摘要 |

**与 session risk 联动**：记忆注入检测命中时，session_risk_score +15。同一 session 累计命中 3 次记忆注入，强制断开连接。

### 2.10 输出审查（Output Review）

> **工具结果审查已实现；Agent 最终输出审查为设计建议，待应用层集成。** 实际实现复用 Engine `/v1/evaluate` 端点，而非新建独立 `OutputReviewer` 类。工具结果审查已在 MCP Proxy 中实现；Agent 最终输出审查（方案 B）需应用层自行调用 `/v1/evaluate`，目前代码库中未包含应用层集成代码。详见 [DESIGN.zh.md §13.7](DESIGN.zh.md#137-输出审查output-review)。

> STI Taint（§5.4）审查的是**工具返回值**，输出审查审查的是 **Agent 最终返回给用户的响应**——经过 LLM 汇总工具结果后生成的内容。两者覆盖不同阶段。

**审查流程**：

```
工具返回结果（egress / non-egress 两条路径）
  |
  v
mask_pii_in_response()    ← PII 脱敏（已有）
  |
  v
tag_tool_result()          ← 信任边界标签（已有）
  |
  v
review_tool_output()       ← 内容安全审查（新增）
  +-- extract_result_text()        从 resp.result.content[].text 提取文本
  +-- should_review_output()       条件触发：text.len() ≥ 512 || risk_score ≥ 50
  +-- pipeline.review_output()    调用 POST /v1/evaluate { content, role: "output" }
  |   +-- Engine 复用 PromptRunner (qwen3guard) + ScriptRuleRunner (groovy) -> PolicyMerger
  +-- 若 deny -> replace_result_text() 替换为安全提示
      若 engine 不可用 -> 根据 fail_open 决定放行或拦截

Agent 最终响应（方案 B：应用层调用，⏳ 设计建议/待应用层集成）
  |
  v
应用层 POST /v1/evaluate { content: "<Agent 输出>", role: "output" }
  +-- Engine 同一管线分类 -> deny 则脱敏/拦截
```

**审查维度**：

| 维度 | 机制 | 触发条件 | 命中动作 |
|------|------|---------|---------|
| **PII 泄露** | dlp/engine.rs 实体识别（`mask_pii_in_response`） | 每次工具输出 | 脱敏后返回 + 审计 |
| **凭据泄露** | 正则（API key/token/password 模式） + 小模型辅助 | 每次工具输出 | 脱敏后返回 + 审计 |
| **内容安全** | qwen3guard 小模型（复用 Engine `prompt` runtime） | 输出 >512 字符 或 session_risk > 50 | block + 审计 + 提升 risk_score |
| **策略合规** | Groovy 规则引擎（场景相关输出约束） | 每次工具输出 | block 或 challenge + 审计 |

**与 STI Taint 的分工**：

| 检测层 | 作用对象 | 阶段 | 机制 |
|--------|---------|------|------|
| **STI Taint（§5.4）** | 工具返回值 | 工具执行后、Agent 汇总前 | 小模型判定注入 |
| **工具结果审查（本节）** | 工具返回值 | PII 脱敏 + 信任标签之后 | 复用 Engine 规则管线（qwen3guard + groovy） |
| **Agent 输出审查（方案 B）** | Agent 最终响应 | Agent 汇总后、返回用户前 | 应用层调用 `/v1/evaluate`（⏳ 设计建议/待应用层集成） |

> 三层覆盖从工具返回到最终输出的完整审查链路。

**实现**（MCP Proxy 侧，非 virbius-core）：

```rust
// virbius-mcp-proxy/src/pipeline.rs

/// Review tool output content via the Engine (reuses POST /v1/evaluate).
pub(crate) async fn review_output(
    &self,
    session: &Session,
    tool_name: &str,
    content: &str,
) -> Result<EvaluateResponse, EngineError> {
    let req = EvaluateRequest {
        trace_id: &session.trace_id,
        session_id: &session.session_id,
        app_id: &session.app_id,
        tenant_id: &session.tenant_id,
        tool_name,
        args: &serde_json::Value::Null,
        args_json: String::new(),
        license_risk_quota: 100,
        content: Some(content),
        role: Some("output"),
    };
    self.engine.evaluate(&req).await
}

/// Check if output review should be triggered.
pub fn should_review_output(&self, text: &str, session_risk_score: u32) -> bool {
    if !self.output_review.enabled {
        return false;
    }
    text.len() >= self.output_review.min_text_length
        || session_risk_score >= self.output_review.min_risk_score
}
```

```rust
// virbius-mcp-proxy/src/router.rs

async fn review_tool_output(
    resp: &mut Value,
    session: &Session,
    tool_name: &str,
    pipeline: &SecurityPipeline,
) {
    let text = extract_result_text(resp);
    if text.is_empty() || !pipeline.should_review_output(&text, session.session_risk_score) {
        return;
    }
    match pipeline.review_output(session, tool_name, &text).await {
        Ok(eval_resp) => {
            if eval_resp.effective_action == "block" || eval_resp.effective_action == "deny" {
                replace_result_text(resp, &format!("[Content blocked: {}]", ...));
            }
        }
        Err(_) => {
            if !cfg.fail_open {
                replace_result_text(resp, "[Content blocked: safety review unavailable]");
            }
        }
    }
}
```

**配置**：

```toml
# virbius-mcp-proxy.toml
[security.output_review]
enabled = true
min_text_length = 512
min_risk_score = 50
fail_open = true
```

**成本控制**：PII/凭据检测为规则+正则，无 LLM 调用。内容安全检测复用 qwen3guard 小模型，仅高风险触发（输出 >512 字符 或 session_risk > 50），非每次调用。


---

## 3. 管层 — Higress 南北向安全网关

### 3.1 职责

管层由 Higress 承担，定位为**南北向流量网关**（Ingress + Egress），基于 Envoy/WASM 实现安全插件：

```
=== Ingress（入站）===
远程 Agent -> Higress (TLS/限流/安全预检) -> MCP Server (Python/Node)
              |
              +-- virbius-gateway WASM 插件
                  +-- tool allowlist (WASM allowlist 模块)
                  +-- 计数器 (WASM Redis 模块)
                  +-- 快速通道判断
                  +-- 调 virbius-engine (Envoy HTTP client POST /v1/evaluate)
                  +-- HTTP 层阻断 (Envoy direct response 403)

=== Egress（出站，非 Sidecar 模式）===
Agent -> Higress (Egress Proxy) -> 外部 API
           |
           +-- URL 白名单校验
           +-- 出站限流
           +-- 审计日志
```

> **拓扑说明**：管层处理南北向（跨网络）流量。Sidecar 模式下 MCP 工具调用为东西向流量，不经管层（§1.1）。管层在以下场景发挥作用：
> - **Ingress**：远程 Agent（非 Sidecar 部署）通过 HTTPS 访问 MCP Server
> - **Egress**：非 Sidecar 模式下 Agent 发起的外部 HTTP 请求经过管层 Egress Proxy
> - **Sidecar 模式 Egress**：由端层 Proxy 代发 HTTP 请求（[§2.6.1](PROTOCOL.zh.md#261-mcp-proxy-full-technical-solution)），管层不参与

| 能力 | 方向 | 实现方式 |
|------|------|---------|
| TLS 终止 | Ingress | Higress 原生（Envoy） |
| MCP 协议路由 | Ingress | Higress MCP Gateway（原生 Streamable HTTP/SSE） |
| 限流 | Ingress + Egress | Envoy rate limit |
| tool allowlist | Ingress | WASM 插件 allowlist 模块 |
| 计数器 | Ingress | WASM 插件 Redis 模块 |
| 调 virbius-engine | Ingress | WASM HTTP call POST /v1/evaluate |
| HTTP 阻断 | Ingress | Envoy direct response 403 + JSON-RPC error |
| URL 白名单 | Egress | WASM 插件 egress_url_check |

### 3.2 WASM 安全预检

基于 Higress WASM 插件实现安全预检（Go 语言，proxy-wasm-go-sdk）：

```go
// virbius-gateway/wasm/access.go

func (p *VirbiusPlugin) onHttpRequestHeaders(ctx wrapper.HttpContext) types.Action {
    toolName := ctx.Headers().Get("x-mcp-tool-name")
    sessionID := ctx.Headers().Get("x-mcp-session-id")

    // 1. tool allowlist (WASM allowlist 模块)
    if !p.allowlist.Match("tool-allowlist", toolName) {
        return p.deny(ctx, "tool_not_allowed")
    }

    // 2. 累计计数器 (WASM Redis 异步查询)
    count, err := p.redis.Incr("tool:" + toolName + "-session:" + sessionID)
    if err != nil || count > 50 {
        return p.deny(ctx, "tool_rate_exceeded")
    }

    // 3. 快速通道判断
    if p.isFastPath(toolName) && p.getSessionRisk(sessionID) < 30 {
        return types.ActionContinue // allow, 跳过 engine
    }

    // 4. 调 virbius-engine 终判 (HTTP 异步调用)
    decision, err := p.callEngine(ctx, toolName, sessionID)
    if err != nil {
        return p.deny(ctx, "engine_error")
    }
    if decision.Action == "block" {
        return p.deny(ctx, decision.Reason)
    }
    return types.ActionContinue
}
```

> **WASM vs Lua 差异**：WASM 插件中 Redis 和 HTTP 调用均为异步回调模式（不能阻塞），需通过 callback chain 实现顺序逻辑。相比 Lua cosocket 的同步写法，代码结构稍复杂，但获得连接无损热更新能力。组合部署时管层可配置 `evaluate=false`，仅做 allowlist + 限流，避免 WASM 异步回调复杂度。

### 3.3 Higress 路由配置自动生成

MCP 路由由 virbius-control 编译为 Higress CRD 配置：

```
virbius-control -> mcp_routes 表 -> virbius-compiler -> Higress CRD -> K8s APIServer
```

示例 Higress CRD 配置：

```yaml
# McpBridge — MCP Server 注册
apiVersion: networking.higress.io/v1
kind: McpBridge
metadata:
  name: mcp-github
spec:
  registries:
    - name: github-mcp
      type: static
      domain: mcp-github.internal
      port: 8080
---
# McpServer — MCP 路由 + WASM 插件
apiVersion: networking.higress.io/v1
kind: McpServer
metadata:
  name: github-mcp-server
spec:
  bridgeRef: mcp-github
  pathPrefix: /mcp/github
  wasmPlugins:
    - name: virbius-gateway
```

> **热更新**：Higress CRD 更新后，Envoy 通过 xDS 下发新配置，WASM 插件热加载，**SSE 长连接不中断**。相比 Nginx `nginx -s reload`（会短暂断开连接），Higress 实现真正的连接无损热更新。

### 3.4 schema 校验和 PII 脱敏的职责下沉

| 能力 | 位置 | 理由 |
|------|------|------|
| schema 校验 | 端层 virbius-core (Rust jsonschema crate) | WASM JSON Schema 库能力弱 |
| 输入 PII 脱敏 | 端层 virbius-core dlp/engine.rs (已有) | 发送 LLM 前脱敏 |
| 输出 PII 脱敏 | 端层 virbius-core (工具返回前) | 避免管层重复脱敏 |
| tool allowlist | 管层 Higress WASM | HTTP 层第一道防线 |
| 计数器 | 管层 Higress WASM | HTTP 层频控 |
| engine 终判 | 云层 virbius-engine | 复杂语义判断 |

> **删除原设计的 AgentGateway**：Higress 已承担 MCP 路由 + 负载均衡 + 协议转换，不需要额外组件。原 §3.3 AgentGateway 集成和 §3.4 对比表已删除。

### 3.5 Egress 流量管控

Agent 发起的外部 HTTP 请求属于南北向 Egress 流量，分为两类：

| 流量类型 | 来源 | 示例 | 管控方式 |
|---------|------|------|----------|
| **业务工具请求** | MCP `tools/call` 中的显式工具 | `curl`/`web_search`/`http_request` | Proxy 代发 + URL 白名单（§3.5 Sidecar 模式）或管层 Egress Proxy（§3.5 非 Sidecar 模式） |
| **框架隐式请求** | Agent 框架/SDK 底层 | 配置拉取、模型下载、心跳检测、遥测上报 | K8s NetworkPolicy 限制到最小白名单目标 |

> **设计决策：工具级管控而非进程级断网**
>
> 原方案规定“Agent 不具备直接网络出站能力，所有 HTTP 由 Proxy 代发”。但这会破坏存量 Agent 框架兼容性——LangChain、AutoGen、OpenAI SDK 等会隐式发起网络请求，直接断网会导致无法运行。且全量代发（代理 Agent 所有网络流量）需支持 WebSocket 双工、大文件分块上传、HTTP/2 多路复用等全部 HTTP 语义，开发成本极高。
>
> 修订后的方案：
> - **业务工具请求**（`curl`/`web_search` 等显式 MCP 工具）走 Proxy 代发 + URL 白名单校验
> - **框架隐式请求**（配置拉取、模型下载、心跳等）由 Agent 自身发起，受 NetworkPolicy 限制到白名单目标
> - **进程级全量出站**（P2 兜底）由 eBPF/iptables 透明劫持
>
> 这三分法匹配威胁模型：安全威胁来自 Agent 通过业务工具发起的**可控外部请求**，而非框架底层的**固定目标**网络调用。工具级代发只需支持 GET/POST + 流式响应透传（chunked/SSE），reqwest `bytes_stream()` 即可实现，开发成本可控。详见 [§2.6.1](PROTOCOL.zh.md#261-mcp-proxy-full-technical-solution) Egress 流量管控。

#### Sidecar 模式——工具级 Proxy 代发

Sidecar 模式下，MCP 业务工具调用（`curl` 等）通过 `tools/call` 交给 Proxy 代发。Agent 框架底层的隐式网络请求不由 Proxy 代发，受 NetworkPolicy 限制：

```
Agent ──tools/call("curl", {url: "https://api.internal/..."})──> MCP Proxy
  |
  +-- 1. 解析 url 参数
  +-- 2. URL 白名单校验（License allowed_hosts + ToolPolicy allowed_args_schema）
  +-- 3. 安全管线（预检 -> engine 终判）
  +-- 4. allow -> Proxy 发起 HTTP 请求（reqwest） -> 外部 API
  +-- 4. deny  -> 返回 JSON-RPC error
  |
  v
外部 API
```

```rust
// virbius-mcp-proxy/src/egress.rs

/// 校验 curl 工具的目标 URL 是否在白名单内
fn validate_egress_url(args: &Value, license: &License) -> Result<(), String> {
    let url_str = args.get("url")
        .and_then(|u| u.as_str())
        .ok_or("missing 'url' parameter")?;
    let url = url::Url::parse(url_str).map_err(|e| format!("invalid url: {e}"))?;
    let host = url.host_str().ok_or("url has no host")?;

    // 校验 License allowed_hosts
    let allowed_hosts = license.claims.allowed_hosts
        .iter()
        .filter_map(|h| {
            let parts: Vec<&str> = h.splitn(2, ':').collect();
            Some((parts[0], parts.get(1).and_then(|p| p.parse::<u16>().ok())))
        })
        .collect::<Vec<_>>();

    let port = url.port_or_known_default().unwrap_or(443);
    let matched = allowed_hosts.iter().any(|(h, p)| {
        host == *h && (p.is_none() || *p == Some(port))
    });

    if !matched {
        return Err(format!("host '{}' not in egress allowlist", host));
    }
    Ok(())
}

/// 流式代发 HTTP 请求：使用 reqwest bytes_stream() 避免大响应 OOM
///
/// 支持两种响应模式：
/// - 普通响应（JSON/HTML/...）：流式读取 chunk，累计到上限后返回
/// - SSE 响应（text/event-stream）：逐条解析 event，透传给 Agent
async fn proxy_egress_request(
    client: &reqwest::Client,
    url: &str,
    method: &str,
    body: Option<&Value>,
    max_bytes: usize,  // 默认 50MB
) -> Result<EgressResponse, EgressError> {
    let mut req = match method {
        "GET" => client.get(url),
        "POST" => {
            let r = client.post(url);
            match body {
                Some(v) => r.json(v),
                None => r,
            }
        }
        _ => return Err(EgressError::UnsupportedMethod(method.into())),
    };

    let resp = req.send().await.map_err(EgressError::Http)?;
    let status = resp.status();
    if !status.is_success() {
        let body = resp.text().await.unwrap_or_default();
        return Err(EgressError::Status(status.as_u16(), body));
    }

    let content_type = resp.headers()
        .get("content-type")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();

    // 流式读取响应体，避免大响应 OOM
    use futures_util::StreamExt;
    let mut stream = resp.bytes_stream();
    let mut buf = Vec::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(EgressError::Http)?;
        if buf.len() + chunk.len() > max_bytes {
            return Err(EgressError::TooLarge(max_bytes));
        }
        buf.extend_from_slice(&chunk);
    }

    Ok(EgressResponse {
        status: status.as_u16(),
        content_type,
        body: buf,
    })
}
```

> License 需扩展 `allowed_hosts` 字段：
> ```json
> {
>   "app_id": "code-review-agent",
>   "allowed_tools": ["read_file", "search", "curl"],
>   "allowed_hosts": ["api.internal:443", "cdn.internal:443"],
>   ...
> }
> ```

#### 非 Sidecar 模式——管层 Egress Proxy

非 Sidecar 模式（独立服务部署）下，Agent 直接发起 HTTP 请求，需经过管层 Egress Proxy：

```yaml
# Higress Egress 路由 + WASM 插件
apiVersion: networking.higress.io/v1
kind: HttpRoute
metadata:
  name: egress-proxy
spec:
  parentRefs:
    - name: higress-gateway
  hostnames: ["egress.internal"]
  rules:
    - matches:
        - path:
            type: PathPrefix
            value: /egress/
      filters:
        - type: ExtensionRef
          extensionRef:
            name: virbius-gateway-egress  # WASM 插件
```

```go
// virbius-gateway/wasm/egress.go

func (p *VirbiusPlugin) checkUrl(uri, sessionID string) bool {
    // 从 /egress/<host>/<path> 中解析目标 host
    parts := strings.SplitN(strings.TrimPrefix(uri, "/egress/"), "/", 2)
    if len(parts) == 0 || parts[0] == "" {
        return false
    }
    targetHost := parts[0]

    // 查 Redis 获取 egress allowlist（from License）
    allowed, err := p.redis.SIsMember("egress:allowlist:"+sessionID, targetHost)
    if err != nil || !allowed {
        return false
    }
    return true
}
```

#### P2 增强——eBPF 透明劫持

P0 对业务工具请求依赖 Proxy 代发，对框架隐式请求依赖 NetworkPolicy。如果 Agent 进程绕过 MCP 协议直接发起 TCP 连接（如通过 `shell` 工具执行 `curl` 命令），且目标在 NetworkPolicy 白名单内，P0 无法拦截。P2 通过内核级流量劫持兜底：

```
Agent 进程
  |
  +-- 正常路径: tools/call -> Proxy 代发（P0 已覆盖）
  |
  +-- 绕过路径: 直接 TCP connect()（P2 兜底）
       |
       v
    eBPF sock_ops / TPROXY
       |
       +-- 劫持出站 TCP -> 重定向到 Proxy (:9091)
       +-- 或 iptables REDIRECT -> Egress Gateway
       |
       v
    URL 白名单校验 -> allow / deny
```

| 机制 | 阶段 | 覆盖范围 | 依赖 |
|------|------|---------|------|
| 工具级 Proxy 代发 + URL 白名单 | P0 | MCP 业务工具调用（`curl`/`web_search` 等） | 无内核依赖 |
| K8s NetworkPolicy | P0 | Agent 框架隐式出站（配置拉取、模型下载等） | K8s CNI 支持 |
| 管层 Egress Proxy | P0 | 非 Sidecar 模式的外部 HTTP | Higress 部署 |
| eBPF sock_ops 透明劫持 | P2 | 进程级所有 TCP 出站 | 内核 5.8+ + CAP_BPF |
| iptables TPROXY | P2 | 进程级所有 TCP 出站 | NET_ADMIN |
| NetworkPolicy（增强） | P2 | Pod 级网络隔离 | K8s CNI 支持 |

### 3.6 网关可移植性 — 切换其他 MCP 网关

管层设计为**可插拔**架构。Higress 是默认实现，但架构允许切换到其他网关（APISIX、Kong、Envoy、Nginx 等），代码改动量很小。项目中已有 APISIX Emitter 作为此设计的验证。

#### 3.6.1 耦合面分析

Higress 在项目中的耦合仅限于 **3 个点**，其余模块完全无关：

| 耦合点 | 文件 | 耦合程度 | 说明 |
|--------|------|---------|------|
| ① WASM 插件 | `virbius-gateway/wasm/main.go` | 中 | 依赖 `higress/plugins/wasm-go` wrapper 和 `proxy-wasm-go-sdk` |
| ② CRD Emitter | `virbius-compiler/.../HigressCrdEmitter.java` | 低 | 生成 Higress 专有 CRD（McpBridge、McpServer、WasmPlugin） |
| ③ 文档/配置 | `ARCHITECTURE.md`、`DEPLOYMENT.md` | 无（仅描述性） | 文档引用 |

**切换网关时不需要改动的模块**：

| 模块 | 原因 |
|------|------|
| `virbius-mcp-proxy`（Rust） | Sidecar 模式下拥有独立安全管线（License + precheck + Engine 调用），东西向流量不经管层 |
| `virbius-engine`（Java） | 标准 HTTP API（`POST /v1/evaluate`），对调用方无感知 |
| `virbius-control`（Java） | 通过 Redis 投递 artifact（access-lists + scene-registry），与网关实现无关 |
| `virbius-core`（Rust） | 端层嵌入式 precheck SDK，与管层无关 |
| `virbius-policy`（Java） | 策略匹配引擎，与管层无关 |

#### 3.6.2 已有的多网关支持

编译器已内置 `-g`（网关后端）参数和两套 Emitter 实现：

```
virbius-compiler/src/main/java/io/virbius/compiler/
  ├── HigressCrdEmitter.java        ← Higress CRD 生成（默认）
  ├── GatewayApisixEmitter.java     ← APISIX 路由生成（已存在）
  └── CompilerCli.java              ← -g higress | apisix 切换
```

```java
// CompilerCli.java
@Option(names = {"-g", "--gateway"}, defaultValue = "higress",
        description = "gateway backend: higress | apisix")
private String gateway;
```

#### 3.6.3 切换网关需要实现的代码

切换到其他网关（如 Kong、Nginx 或自研网关），需实现以下 **3 项**：

**① 新增 Emitter**（约 100–200 行 Java）

参照已有的 `GatewayApisixEmitter`，新建 `GatewayXxxEmitter.java`。Emitter 读取同一份规则 bundle JSON，输出目标网关的路由/插件配置：

```java
// 示例: GatewayKongEmitter.java
public final class GatewayKongEmitter {
    static int emitRoutes(JsonNode root, Path gatewayDir, ObjectMapper json) throws IOException {
        // 1. 从 bundle gateway.routes 生成 Kong Route + Service JSON
        // 2. 配置 Kong Plugin（allowlist / rate-limit / engine-call）
    }
}
```

在 `CompilerCli.java` 中注册（一行）：

```java
} else if ("kong".equals(gw)) {
    GatewayKongEmitter.emitRoutes(root, gwDir, json);
}
```

**② 安全插件**（约 300–400 行 Go/Lua/JS）

当前 `virbius-gateway/wasm/main.go` 实现了 5 个核心函数，需在目标网关的插件语言中重新实现：

| 函数 | 职责 | Engine 交互 |
|------|------|------------|
| 请求头/体拦截 | 从 JSON-RPC 提取 `tool_name`、`session_id` | 无 |
| Tool 白名单检查 | 本地匹配配置 | 无 |
| 限流 | Redis INCR per tool+session | Redis |
| 快速通道放行 | 低风险工具跳过 Engine | 无 |
| Engine 终判 | `POST /v1/evaluate` 调用 virbius-engine | HTTP → Engine |
| HTTP 403 阻断 | 直接响应 + JSON-RPC error | 无 |
| Challenge 响应 | 返回 `-32011` + `challenge_id` | 无 |

按目标网关评估实现工作量：

| 目标网关 | 插件语言 | 代码复用度 | 工作量 |
|---------|---------|-----------|--------|
| APISIX | Lua | 逻辑复用，API 重写 | 中 |
| Kong | Lua | 逻辑复用，API 重写 | 中 |
| Envoy（纯） | Go WASM | 高 — 仅替换 wrapper 层 | 小 |
| Nginx + njs | JavaScript | 逻辑复用，API 重写 | 中 |
| 自研 Go 网关 | Go | 高 — 直接代码复用 | 小 |

**③ Artifact 投递适配**（可选，约 50 行）

如果新网关支持从 Redis 拉取 artifact（access-lists + scene-registry），则 `GatewayDeliveryService` 无需改动。否则需适配投递方式（如 HTTP push 到 Kong Admin API）。

#### 3.6.4 工作量估算

| 工作项 | 代码量 | 难度 | 时间 |
|--------|-------|------|------|
| 新增 `GatewayXxxEmitter` | ~150 行 | 低（模板化） | 2–3 小时 |
| `CompilerCli` 加分支 | ~5 行 | 极低 | 5 分钟 |
| 新增网关安全插件 | ~350 行 | 中（接口清晰） | 4–6 小时 |
| Artifact 投递适配（如需） | ~50 行 | 低 | 1 小时 |
| 部署配置/文档 | — | 低 | 1 小时 |
| **合计** | **~550 行** | | **1–2 人天** |

#### 3.6.5 支撑可移植性的关键设计

1. **MCP Proxy 独立于管层** — Sidecar 模式下，完整安全管线（License 验证 → precheck → Engine 终判 → challenge）在 `virbius-mcp-proxy` 内闭环，不经过管层
2. **Engine 是标准 HTTP API** — 任何网关只要能发 HTTP `POST /v1/evaluate` 就能接入 virbius-engine
3. **编译器有多网关架构** — `--gateway` 参数 + Emitter 模式，新增网关只需加一个 Emitter 类
4. **Artifact 投递走 Redis** — Control Plane 不直接耦合任何网关实现
5. **安全插件接口定义清晰** — 5 个核心函数（allowlist、rate-limit、fast-path、engine-call、block）有明确契约，可用任何语言重新实现

---

## 4. 核层 — Falco 观测引擎

### 4.1 职责

核层是运行时观测层，P0 只实现观测(eyes)，P2 补阻断(hands)。

| 范围 | P0 观测 | P2 阻断 |
|------|---------|---------|
| Agent 进程内 syscall | Falco eBPF 观测(可用时) | Landlock 文件路径阻断 |
| 容器逃逸检测 | Falco eBPF 观测(可用时) | gVisor 容器隔离 + Landlock 路径阻断 |
| SSRF / 内网扫描 | Falco eBPF 观测 connect | NetworkPolicy 网络阻断 |
| 基础设施异常 | 云审计日志（k8s audit / cloudtrail） | 云厂商原生 enforcement |

**P0 安全模型**：核层只观测不阻断。发现异常 -> 上报审计流 -> 提升 session risk score -> 管层 HTTP 层阻断后续请求。这是"检测 -> 累积风险 -> 阻断后续"模型，对 Agent 多轮调用场景有效。

### 4.2 Falco 驱动降级链

```
detect_mode()
  |
  +-- 有 CAP_BPF + 内核 5.8+ + BTF
  |    -> Falco eBPF 驱动 (观测 only)
  |
  +-- 无 CAP_BPF, 有 CAP_SYS_PTRACE
  |    -> Falco userspace 驱动 (ptrace, 性能差 5-10x)
  |
  +-- 无任何特权
       -> Disabled (无 syscall 可见性)
```

> **架构变更（方案 A）**：已移除 `FalcoPlugin` 模式和自定义 `virbius-audit` Go 插件。Falco 退回纯系统级 syscall 观测角色，跨层关联由 Engine `FalcoAlertController` 通过 Redis pidmap 反查完成。无特权环境下 `detect_mode()` 返回 `Disabled`，不降级到 plugin 模式。

**观测与阻断职责分离**：核层（Falco）只负责观测，不做 enforcement。阻断由端层 Landlock + drop caps（文件路径限制）和 gVisor（不可信代码隔离）承担。这种分离确保观测层故障不影响阻断能力，阻断层故障仍留有观测可见性。

### 4.3 Falco 模式检测逻辑

```rust
// virbius-kernel/src/detect.rs

pub enum KernelMode {
    FalcoEbpf,       // eBPF 观测
    FalcoUserspace,  // ptrace 驱动
    Disabled,         // 无特权，无 syscall 可见性
}

pub fn detect() -> KernelMode {
    let is_root = unsafe { libc::geteuid() } == 0;
    let cap_eff = read_cap_effective().unwrap_or(0);
    let has_sys_admin = cap_eff & (1 << 21) != 0;
    let has_bpf = cap_eff & (1 << 39) != 0;
    let has_perfmon = cap_eff & (1 << 38) != 0;
    let has_sys_ptrace = cap_eff & (1 << 19) != 0;
    let has_caps = is_root || has_sys_admin || (has_bpf && has_perfmon);

    if !has_caps && !has_sys_ptrace {
        return KernelMode::Disabled;
    }

    let kver = kernel_version().unwrap_or((0, 0));
    let btf_ok = std::fs::metadata("/sys/kernel/btf/vmlinux")
        .map(|m| m.len() > 0).unwrap_or(false);

    if kver < (5, 8) || !btf_ok {
        return if has_sys_ptrace { KernelMode::FalcoUserspace }
               else { KernelMode::Disabled };
    }

    KernelMode::FalcoEbpf
}
```

> **变更**：`FalcoPlugin` 枚举变体已移除。无特权环境直接返回 `Disabled`，不再降级到 plugin 模式。

Falco eBPF 模式硬性要求：

| 检测项 | 要求 | 常见失败原因 |
|--------|------|------------|
| 内核版本 | >= 5.8 (推荐 5.10+) | 老内核 |
| **BTF**(最关键) | /sys/kernel/btf/vmlinux 存在且 > 0 字节 | CONFIG_DEBUG_INFO_BTF 未开启 |
| 内核 config | CONFIG_BPF=y, CONFIG_KPROBES=y, CONFIG_TRACING=y | 硬化内核裁剪 |
| 权限 | CAP_SYS_ADMIN 或 CAP_BPF+CAP_PERFMON | serverless / PSA restricted |
| tracefs | /sys/kernel/tracing/ 已挂载 | 容器内未映射 |
| bpffs | /sys/fs/bpf/ 已挂载 | 容器内未映射 |

> **注**：不再使用 Tetragon 做内核级 enforcement。阻断由端层 Landlock + drop caps（文件路径隔离）和 gVisor（不可信代码沙箱）承担，核层 Falco 专注观测。这简化了部署依赖（无需 CONFIG_BPF_KPROBE_OVERRIDE），且观测与阻断解耦——Falco 故障不影响 Landlock/gVisor 阻断能力。

### 4.4 eBPF 观测程序(P2, eBPF 可用时)

> **P2 实现。** P0 使用 Falco 内置 eBPF 程序，不自研。

eBPF 观测点(Falco 内置 + 自定义补充)：

- execve / execveat 监控（Falco 已覆盖 execve，补充 execveat）
- tcp_v4_connect + tcp_v6_connect 监控（补 IPv6）
- mount / nsenter / ptrace 容器逃逸检测

eBPF Maps(策略数据)：

| Map 名称 | 类型 | 用途 |
|----------|------|------|
| exec_allowlist | BPF_MAP_TYPE_HASH | 允许执行的二进制路径 |
| connect_allowlist_ip | BPF_MAP_TYPE_LPM_TRIE | 允许连接的 IP 前缀 |
| connect_allowlist_port | BPF_MAP_TYPE_HASH | 允许连接的端口 |
| agent_cgroups | BPF_MAP_TYPE_HASH | 当前受保护 Agent 的 cgroup_id 集合（容器级，fork/exec 不变） |

> **注**：connect_allowlist 分为 IP(LPM_TRIE) 和 Port(HASH) 两个 map，因为 LPM_TRIE 只能匹配 IP 前缀，不能匹配 IP:Port。

### 4.5 ~~Falco plugin 模式~~（已移除）

> **架构变更（方案 A）**：自定义 `virbius-audit` Go 插件和 `FalcoPlugin` 模式已移除。原 plugin 模式设计为在 serverless 环境下降级消费日志/审计事件，但实际部署中发现：
> 1. 插件模式无 syscall 可见性，与 Falco 核心价值冲突
> 2. 跨层联合判断（syscall 事件 + Agent 上下文在一个条件表达式里）通过 Engine 事后关联即可实现
> 3. Go C-shared library 构建和维护成本高
>
> **替代方案**：Falco 退回纯系统级 syscall 观测，通过 `http_output` 将告警发送到 Engine `FalcoAlertController`，由 Engine 完成 pidmap 反查和 session 关联。无特权环境返回 `Disabled`。

### 4.6 PID -> trace_id 映射

PID 映射的查询路径是端层到核层之间延迟最敏感的链路——Agent 进程启动到首次 syscall 可能 <1ms，不能依赖 Redis 网络 I/O。

#### Host PID vs Namespace PID 问题

在容器环境中，存在两类 PID：

```
┌─── 容器内 (PID namespace) ───┐     ┌─── 宿主机 (init namespace) ───┐
│                              │     │                               │
│  Agent 进程: PID = 42        │ ←→ │  Agent 进程: Host PID = 12345  │
│  Proxy 进程: PID = 43        │     │  Proxy 进程: Host PID = 12346  │
│                              │     │                               │
│  getpid() → 42              │     │  Falco/eBPF 看到 → 12345       │
└──────────────────────────────┘     └───────────────────────────────┘

virbius-core (容器内) 调 getpid() → 42 (Namespace PID)
Falco (宿主机) 事件 → host_pid = 12345

如果 pidmap 以 42 为 key，Falco 查 12345 → miss！
```

| PID 类型 | 谁看到 | 获取方式 | Falco 事件中的字段 |
|---------|--------|---------|------------------|
| **Host PID** | 宿主机/内核/Falco/eBPF | `bpf_get_current_pid_tgid() >> 32` | `proc.vpid` (Falco) |
| **Namespace PID** | 容器内进程 | `getpid()` / `libc::getpid()` | `proc.pid` (Falco) |

**解决方案**：pidmap 以 **Host PID** 为主键（与 Falco 事件对齐），**cgroup ID** 为辅助索引（容器级关联，fork/exec 不变）。

```
virbius-core register_agent(ns_pid=getpid())
  |
  +-- 自动检测 Host PID: 读 /proc/self/status → NSpid 行
  |   例: "NSpid:\t42\t12345" → host_pid = 12345 (最后一个)
  |
  +-- 自动检测 cgroup ID: 读 /proc/self/cgroup → "0::/kubepods/..." 
  |   → stat("/sys/fs/cgroup/kubepods/...") → st_ino = cgroup_id
  |
  +-- 写入 pidmap:
  |   by_host_pid[12345] = { host_pid: 12345, ns_pid: 42, cgroup_id: 98765, ... }
  |   by_cgroup[98765]   = { ... 同上 ... }
  |
  +-- 异步 Redis 备份: SET pid_trace:12345 '{...}' EX 3600
  |                       SET cgroup_trace:98765 '{...}' EX 3600  (cgroup 反向索引, P1)

Falco 事件到达 (host_pid=12345)
  → Engine FalcoAlertController 三级关联链:
    1. lookupSessionByHostPid(12345) → pid_trace:12345 → session_id  ✅ 命中
    2. (未命中时) lookupSessionByCgroup(cgroup_id) → cgroup_trace:{id} → session_id
    3. (未命中时) lookupSessionByHostPid(ppid) → pid_trace:{ppid} → session_id (ppid fallback)

eBPF 程序 (bpf_get_current_cgroup_id()=98765)
  → lookup_by_cgroup(98765) → 命中 by_cgroup[98765] → 补全 trace_id
```

#### 三级关联链（P1 实现）

Engine `FalcoAlertController` 对每条 Falco 告警执行三级 session 关联，优先级从高到低：

| 优先级 | 关联键 | Redis Key | 覆盖场景 | resolved_by |
|--------|--------|-----------|---------|-------------|
| 1 | `proc.pid` (Host PID) | `pid_trace:{host_pid}` | Agent 主进程 | `pid` |
| 2 | `proc.cgroup.id` | `cgroup_trace:{cgroup_id}` | 孙子进程、setsid detach、exec 后 | `cgroup` |
| 3 | `proc.ppid` | `pid_trace:{ppid}` | 直接子进程（ppid 是 Agent 主进程） | `ppid` |

**cgroup 优先于 ppid 的原因**：cgroup 是容器级身份（同一容器内 fork/exec 不变），ppid 是进程级身份（深度>1 或 setsid 后断链）。cgroup 能覆盖孙子进程和 detach 场景，ppid 只能覆盖直接子进程。

**降级策略**：
- cgroup v2 + Falco 0.37+：三级关联完整可用
- cgroup v1 / 旧 Falco / macOS：`proc.cgroup.id=0` 自动跳过 cgroup 查找，降级到 ppid fallback
- 无 Redis：全部降级，告警被忽略（不影响 Agent 正常运行）

#### 存储层级

| 存储 | Key | Value | 生命周期 | 读取延迟 |
|------|-----|-------|---------|---------|
| **进程内 pidmap** `by_host_pid`（内存 HashMap, virbius-kernel） | Host PID | trace_id + session_id + ns_pid + cgroup_id | Agent 生命周期 | <1μs（零延迟） |
| **进程内 pidmap** `by_cgroup`（辅助索引） | cgroup_id | 同上 | 同上 | <1μs |
| **eBPF agent_cgroups map**（核层, eBPF 可用时, P2） | cgroup_id | 1(标记受监控) | Agent 启动时写入、退出时删除 | <1μs（内核查表） |
| Redis `pid_trace:{host_pid}`（异步备份） | Host PID | trace_id + session_id + host_pid + cgroup_id | TTL 1h | Engine FalcoAlertController 查询 |
| Redis `cgroup_trace:{cgroup_id}`（cgroup 反向索引, P1） | cgroup_id | 同上（与 pid_trace 共用 value） | TTL 1h | Engine FalcoAlertController cgroup 关联查询 |

> **eBPF map 改用 cgroup_id 而非 PID**：原设计的 `agent_pids` map 以 PID 为 key，在容器环境中 Host PID 频繁变化（进程 fork/exec）。改用 `agent_cgroups` map 以 cgroup_id 为 key——cgroup 在容器生命周期内不变，eBPF 程序用 `bpf_get_current_cgroup_id()` 查表，无需 PID 翻译。

**查询优先级**：进程内 pidmap `by_host_pid`（最快）→ `by_cgroup`（辅助）→ Redis（兜底）

**注册时机**：Agent 启动后、执行任何工具前，virbius-core 在 `bootstrap()` 中调用 `register_agent()`。该函数自动从 `/proc/self/status` 和 `/proc/self/cgroup` 检测 Host PID 和 cgroup ID，无需调用方感知容器环境。注册是内存操作，<1μs 完成。

**fork/exec 安全性**：
- **cgroup_id 不变**：同一容器内的 fork/exec 不会改变 cgroup_id，`by_cgroup` 索引始终有效。
- **Host PID 变化**：Agent fork 出子进程时，子进程有新的 Host PID。若需追踪子进程，由端层 Proxy 在 `posix_spawn` 后立即注册子进程 PID（P2 沙箱场景）。
- **竞态窗口**：`register_agent` 在 fork 后、子进程执行任何工具前调用。窗口内 Falco 可能观察到未注册的子进程事件——此时事件无 trace_id 关联，但端层 License/预检仍在生效，核层观测为"匿名事件"（标注 `unregistered_pid`）。

> **stale mapping 防护**：Agent 崩溃时无法清理 PID 映射。进程内 pidmap 随进程销毁自动释放；eBPF `agent_cgroups` map 由 cgroup 销毁时内核自动回收；Redis 备份依赖 TTL。

### 4.7 部署模式

| 模式 | 判定条件 | 观测 | 阻断 |
|------|---------|------|------|
| host | 裸机/自管 VM + root | Falco eBPF(P2) | Landlock(P2) + gVisor(P2) |
| daemonset | K8s 标准节点池 + privileged | 同上 | 同上 |
| pod-observe | serverless(Fargate/Autopilot) | 云厂商告警（无 syscall 可见性） | 端层 Landlock(P2) + NetworkPolicy |
| audit-only | 前期观测 | 上述观测的只读子集 | 无 |

> **删除原设计的 sidecar 模式**：sidecar 模式自相矛盾——Falco 也需 eBPF 特权，Landlock 不能由 sidecar 应用到其他容器（必须在 Pod spec 中声明）。serverless 环境下 Landlock profile 通过 mutating admission webhook 注入 Pod spec。

### 4.8 自定义 Falco 规则管理

Falco 规则通过云层控制面统一管理，复用 `tb_rules` / `tb_rules_current` 表（`layer='falco'`, `runtime='falco'`），无需独立表。

#### 4.8.1 规则格式

`body` 字段为 JSON：

```json
{
  "condition": "evt.type=open and fd.name contains /etc/shadow",
  "output": "Shadow file accessed by %proc.name (pid=%proc.pid)",
  "priority": "CRITICAL",
  "tags": ["filesystem", "security"]
}
```

| 字段 | 类型 | 说明 | 默认值 |
|------|------|------|--------|
| `condition` | string | Falco 条件表达式 | `evt.num > 0` |
| `output` | string | 告警输出模板 | `Falco rule triggered (rule=...)` |
| `priority` | string | 取自规则行的 `reason_code`，未填时默认 `WARNING` | `WARNING` |
| `tags` | string[] | 标签数组（可选） | 空 |

#### 4.8.2 灰度部署

复用 deploy-rollout 状态机（`PENDING → CANARY → FULL → FINALIZED`），使用节点标签 `virbius-falco-canary=true` 区分 canary/stable 池：

```
virbius-control
  +-- FalcoConfigBuilder    读取 tb_rules_current(layer='falco') → 生成 Falco YAML
  +-- FalcoArtifactStore    Redis 存储规则 YAML + Stream 通知
  +-- DeployRolloutService  状态机编排
  |
  +-- Redis Stream
      +-- :canary  → canary Falco 节点
      +-- :full     → stable Falco 节点

Falco 节点 Pod 内：
  config-subscriber (Rust sidecar)
    Redis Stream 消费 → 写 /etc/falco/falco_rules.d/{tenant}-{target}.yaml → SIGHUP 重载
```

#### 4.8.3 Falco http_output 配置（方案 A）

Falco 通过 `http_output` 将告警发送到 Engine `FalcoAlertController`，替代原 `program_output` 模式：

```yaml
# virbius-kernel/deploy/falco-config.yaml
http_output:
  enabled: true
  url: "http://virbius-engine.virbius-system.svc.cluster.local:8080/api/internal/falco-alert"
  user_agent: "falco/virbius"
  connection_keepalive: true
  retry_wait_seconds: 5

rules_file:
  - /etc/falco/falco_rules.d/   # config-subscriber 热重载目录
```

**数据流**：
```
Falco eBPF → 告警触发 → http_output POST → Engine FalcoAlertController
  → 三级关联 (pid → cgroup → ppid) → SessionRiskManager.onFalcoAlert()
  → Redis INCR session:{id}:falco_pending → 下次 updateRiskScore() 消费
```

**Falco 规则 output 字段要求**：规则 `output` 模板必须包含 `%proc.cgroup.id`，否则 cgroup 关联路径无法生效（`proc.cgroup.id` 需要 Falco 0.37+ modern eBPF driver）。

#### 4.8.4 示例

详见 [README.zh.md](README.zh.md#快速开始) 或 ops.html 前端操作界面。

### 4.9 统一沙箱规则管理（Falco + Landlock + gVisor）

核层的三类规则——Falco 观测规则、Landlock 文件隔离规则、gVisor 沙箱配置——统一通过 `tb_rules` 表管理，复用同一套 CRUD + 发布 + 灰度部署流程。

#### 4.9.1 规则体系对照

| 规则类型 | `layer` | `runtime` | `body_json` 内容 | 下发目标 | 下发方式 |
|----------|---------|-----------|------------------|---------|---------|
| **Falco 观测** | `falco` | `falco` | condition/output/priority/tags | 核层 Falco 节点 | Redis Stream → config-subscriber → YAML |
| **Landlock 隔离** | `sandbox` | `landlock` | tool_name/read_paths/write_paths/exec_paths | 端层 EdgeManifest | REST → manifest JSON → SDK 拉取 |
| **gVisor 沙箱** | `sandbox` | `gvisor` | runsc_path/memory_limit/cpu_quota/... | 端层 EdgeManifest | REST → manifest JSON → SDK 拉取 |

#### 4.9.2 Landlock 规则格式

每条 Landlock 规则绑定一个 `tool_name`，定义该工具在沙箱中可访问的路径白名单：

```json
{
  "tool_name": "read_file",
  "read_paths": ["/tmp/data/*", "/home/user/workdir/*", "/usr/lib/*"],
  "write_paths": [],
  "exec_paths": ["/usr/bin/cat", "/usr/bin/head"]
}
```

运营台操作流程：新建规则 → 选择 `sandbox` 层 → 选择 `landlock` runtime → 编辑 JSON body → 保存 → 策略上线 → 部署 → Edge SDK 拉取 manifest → P2 沙箱执行时生效。

#### 4.9.3 gVisor 规则格式

gVisor 规则为全局配置（首个 `full` 状态的规则生效），定义不可信代码执行容器的资源限制：

```json
{
  "runsc_path": "/usr/local/bin/runsc",
  "rootfs_path": "/opt/virbius/rootfs",
  "min_warm": 2,
  "max_idle": 5,
  "memory_limit_bytes": 268435456,
  "cpu_quota": 1.0,
  "network_disabled": true,
  "exec_timeout_ms": 30000
}
```

#### 4.9.4 下发链路

```
virbius-control（唯一真源）
  |
  +-- tb_rules (layer='falco')    → FalcoConfigBuilder → YAML → Redis Stream → Falco 节点
  +-- tb_rules (layer='sandbox')  → ArtifactService.buildLandlockProfiles() / buildGvisorConfig()
  |                                  → EdgeManifest JSON → REST API → Edge SDK 拉取
  |
  +-- 运营台 ops.html
      +-- 导航：🦅 falco / 🔒 沙箱 sandbox
      +-- 规则编辑器：JSON body + 校验 + 预览
      +-- 策略上线：draft → dry_run → canary → full（复用现有状态机）
```

#### 4.9.5 运营台集成

| 功能 | 实现方式 |
|------|---------|
| 规则导航 | ops.html 导航栏新增 `🔒 沙箱 sandbox` 按钮，与 `🦅 falco` 并列 |
| 层/运行时 | `LAYER_RUNTIMES.sandbox = ['landlock', 'gvisor']`，运营台自动适配 |
| 规则编辑 | JSON body 编辑器（与 falco 规则编辑体验一致），支持 landlock/gvisor 模板 |
| 规则校验 | 保存时解析 JSON body，校验必填字段（tool_name / read_paths 等） |
| 策略上线 | 复用 `draft → dry_run → canary → full` 状态机，与 falco/edge/cloud 规则一致 |
| 灰度部署 | sandbox 层加入 `DeployRolloutController.diff-rules` 的 layer 列表 |
| Manifest 下发 | `ArtifactService.writeEdgeManifestFile` 新增 `landlock_profiles` + `gvisor_config` 字段 |

---

## 5. 云层 — 统一策略大脑

### 5.1 职责

参考 VirbiusLLM 的 virbius-engine + virbius-control 设计并做了大量扩展以适应 Agent 专属场景（详见 [§10](DESIGN.zh.md#10-与-virbiusllm-的关系)）。

### 5.2 新增规则类型

| 规则类型 | 说明 | 示例 |
|----------|------|------|
| **tool-allowlist** | 允许 Agent 调用的工具白名单 | allow: [read_file, search, curl] |
| **tool-arg-schema** | 工具参数的 JSON Schema 校验规则 | read_file.path 必须匹配正则 |
| **tool-rate-limit** | 按 session/工具维度的频率限制 | read_file: 50/min |
| **tool-chain-detect** | 危险工具调用链检测 | read_secret -> curl = block |
| **session-risk-threshold** | 会话级风险分阈值 | session_risk > 80 = 断开连接 |
| **ebpf-policy** | 核层 eBPF/Falco 的白名单配置 | exec_allowlist: [python3, node] |

### 5.3 Groovy L3 Agent 规则

> **架构变更**：现有 virbius-engine 每次 /v1/evaluate 调用是无状态的。Agent 安全需要跨请求的 session 上下文。新增 Redis session 存储。

**Session 状态存储(Redis)**：

| Key | 类型 | TTL | 用途 |
|-----|------|-----|-----|
| session:{id}:tool_history | List | 1h | 最近 N 次工具调用记录 |
| session:{id}:risk_score | String | 1h | 会话风险分(0-100) |
| session:{id}:tool_count:{tool_name} | Counter | 1h | 按工具维度的调用计数 |
| pid_trace:{host_pid} | String | 1h | Host PID -> trace_id + session_id + cgroup_id 映射 |

**Redis I/O 优化**：engine 在 evaluate 入口预加载 session 上下文到内存，Groovy ctx 从内存读，不直接查 Redis。避免 N 条规则 = N 次 Redis I/O。

```groovy
// 规则 ID: agent-tool-chain-detect
def decide(ctx) {
    def history = ctx.sessionHistory(5)
    def tools = history.collect { it.tool_name }

    // 危险链：先读敏感数据，再发到外部（检查顺序）
    def readSecretIdx = tools.indexOf("read_file")
    def curlIdx = tools.indexOf("curl")
    if (readSecretIdx >= 0 && curlIdx >= 0 && readSecretIdx < curlIdx) {
        def curlTarget = history[curlIdx]?.args?.url
        if (curlTarget && !isInternalHost(curlTarget)) {
            ctx.audit("dangerous chain: read_file -> curl to external")
            ctx.incrementRiskScore(20)
            return true  // block
        }
    }

    // 重复调用链：最近 N 次工具调用都是同一类
    if (tools.size() >= 10 && tools.every { it == "search" }) {
        ctx.audit("possible data exfiltration via repeated searches")
        ctx.incrementRiskScore(15)
        return true
    }

    return false
}
```

**Groovy ctx API**：

| 函数 | 说明 | 数据来源 |
|------|------|---------|
| ctx.var(name) | 读取原生变量（如 `tool_name`、`tool_session_key`） | 引擎自动注入 + 调用方传入的 `vars` 映射 |
| ctx.vars() | 返回全部原生变量只读视图 | 同上 |
| ctx.sessionHistory(n) | 最近 N 次工具调用 | 预加载自 Redis LRANGE |
| ctx.sessionRiskScore() | 当前会话风险分 | 预加载自 Redis GET |
| ctx.incrementRiskScore(delta) | 提升风险分 | 异步写 Redis INCRBY |
| ctx.isInternalHost(url) | 判断 URL 是否指向内部网络 | 根据 License 或策略中配置的 CIDR/域名列表判断 |

**`ctx.var()` 原生变量列表**：

请求因子（`tb_context_bindings`）和扩展因子（`tb_extended_vars`）已从代码中移除，`ctx.var()` 不再支持用户自定义的上下文绑定和表达式派生变量，仅保留以下引擎自动注入的原生变量：

| 变量名 | 注入时机 | 说明 | 示例值 |
|--------|---------|------|--------|
| `tool_name` | `EvaluateOrchestrator` 始终注入 | 当前被调用的工具名 | `read_file`、`curl` |
| `tool_session_key` | `EvaluateOrchestrator` 在 `toolName` 和 `sessionId` 均非空时注入 | 每个工具在每个会话中的唯一复合 key，用于累计聚合 | `tool:read_file-session:sess-001` |

其他变量（如 `app_id`、`user_id`、`ip`、`command` 等）由调用方（网关 / SDK / 测试脚本）通过请求的 `vars` 字段显式传入，引擎不做任何自动解析或派生。规则脚本需要在 `decide(ctx)` 中先判空再使用：

```groovy
def appId = ctx.var("app_id")
if (appId != null && appId == "restricted-app") {
    return true
}
```

### 5.4 语义审计 — STI 协议

在 Groovy L3 中实现 STI(Suitability、Taint、Integrity)语义审计，仅对高风险场景按需调用 LLM：

| 维度 | 触发条件 | LLM 调用 | 说明 |
|------|---------|---------|------|
| **Suitability** | session_risk > 50 或工具首次在该 scene 调用 | 否(规则) | 校验 tool_name + args 是否符合场景最小权限 |
| **Taint** | 工具返回值长度 > 2KB 或含注入标记 | 是(LLM) | 检查工具返回值中是否含外部注入指令 |
| **Integrity** | 参数类型与 schema 不符或含 Base64/Hex | 否(规则) | 校验参数是否被篡改 |

> **成本控制**：STI 的 LLM 调用通过 mlPredict 走专用小模型(qwen3guard:0.6b)，不走大模型。小模型本地部署(Ollama)，单次调用 <200ms。

### 5.5 控制面统一下发

```
virbius-control（唯一真源）
  |
  +-- tb_tool_policies        -> 端层 tool policy + schema
  +-- tb_mcp_routes           -> 管层 Higress MCP route 配置
  +-- tb_kernel_policies      -> 核层 Falco 规则 + eBPF 白名单 maps
  +-- tb_rules_current        -> 云层 Groovy L3 + Prompt L1 规则（已有）
  +-- tb_app_licenses         -> Agent 运行许可证（app_id -> license）
  |
  +-- 运行时状态（Redis，非数据库）
      +-- session:{id}:tool_history
      +-- session:{id}:risk_score
      +-- session:{id}:tool_count:*
      +-- pid_trace:{pid}
      +-- license:revoked:{app_id}  -> 吊销标记（pub/sub 通知各层）
```

发布流程复用现有 PublishOrchestrator：draft -> dry_run -> canary -> full

各层独立放量：
- 端层 canary：按 device_id hash 灰度
- 管层 canary：按 tenant_id 灰度
- 核层 canary：按 Agent PID hash 灰度
- 云层 canary：按 session_id 灰度（已有）

控制面下发方式：

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
  +-- Higress CRD (新增)
      +-- -> Higress: MCP route + WasmPlugin 配置 (virbius-compiler 生成)
```

### 5.6 审计完整性（Hash Chain）

> **✅ 已实现。** 位于 `virbius-control/src/main/java/io/virbius/control/audit/`，详见 [DESIGN.zh.md §13.5](DESIGN.zh.md#135-审计完整性hash-chain)。

防篡改审计链：每条审计事件包含前一条的 SHA-256 hash，形成**按租户隔离**的链式结构。任何篡改都会导致链断裂，可被验证检测。

**核心组件**：

| 组件 | 职责 |
|------|------|
| `HashChainOrchestrator` | 为审计事件附加 `audit_seq` / `prev_hash` / `curr_hash`，Redis Lua CAS 原子更新 + MySQL 乐观锁降级 |
| `HashChainVerifier` | 从 DB 读取事件逐条校验序号连续性 + prev_hash 链 + curr_hash 重算 |
| `HashChainVerifyTask` | `@Scheduled` 每小时自动验证所有租户近 7 天审计链 |
| `AuditAdminController` | REST API：`POST /audit/verify`（手动验证）+ `GET /audit/chain/status`（链状态查询） |

**数据流**：

```
各层审计事件
  │
  ▼
virbius-control AuditService
  ├── HashChainOrchestrator.chainBatch(tenantId, events)
  │     ├── Redis: HSET virbius:audit:chain:{tenantId} (Lua CAS, 3 次重试)
  │     └── MySQL: tb_audit_chain_state (乐观锁 version, 降级)
  ▼
写入 tb_audit_events (含 audit_seq, prev_hash, curr_hash)
  │
  ▼
HashChainVerifyTask (每小时) → HashChainVerifier → 重算 + 比对 → log.error on break
```

**Hash 计算**（13 字段）：`prev_hash | seq | tenant_id | trace_id | event_id | effective_action | layer | reason_code | rule_id | scene | user_id | device_id | intercepted_at`

**DB 迁移**：`V8__audit_hash_chain.sql` — `tb_audit_events` 增加 3 列 + `tb_audit_chain_state` 链状态表。

---