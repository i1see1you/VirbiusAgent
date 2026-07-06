# Agent 安全防护 — 端管核云四层架构设计

| 项目 | 说明 |
|------|------|
| 文档版本 | v2.7 |
| 状态 | 草案 |
| 关联 | [README.md](README.md) |
| 参考项目 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

---

## 目录

1. 总体架构
2. 端层 — Agent 工具调用预检与执行
3. 管层 — OpenResty 安全防火墙
4. 核层 — Falco 观测引擎
5. 云层 — 统一策略大脑
6. 跨层数据流
7. 策略一致性
8. 部署视图
9. 第三方技术栈依赖与稳定性
10. 与 VirbiusLLM 的关系
11. 路线图

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
[2] Gateway - OpenResty + virbius-gateway Lua plugin
    TLS/rate-limit/long-conn + allowlist + counter + engine call + HTTP block
  |
[3] Kernel - Falco observer (observation layer)
    eBPF driver (standard node) / plugin mode (serverless fallback)
    Tetragon enforcer (P2, eBPF available)
    observe: syscall/net/file + audit stream + session risk
    enforce(P2): Landlock + drop caps (edge) / Tetragon (kernel)
  |
[4] Cloud - virbius-engine + virbius-control
    engine: Groovy L3 + STI audit + tool-chain detect
    session: Redis (tool history + risk score + counters)
    control: rule CRUD + rollout + unified delivery
```

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **控制面统一** | 所有层的策略真源为 virbius-control，各层独立执行但配置同源 |
| **预检先于执行** | 端层预检 -> 管层/云层终判 -> 端层执行。工具在终判通过后才执行 |
| **观测与阻断分离** | 观测(eyes)和阻断(hands)由不同技术栈承担。观测随环境降级(eBPF->ptrace->plugin)，阻断始终由端层 Landlock + drop caps 保证(P2) |
| **观察先行** | P0 只实现观测(Falco + HTTP 层阻断 + session risk 累积)，P2 补 syscall 级阻断 |
| **eBPF 是增强非依赖** | eBPF 可用时叠加 Tetragon enforcer；不可用时端层 Landlock + drop caps 仍是完整可用的阻断 |
| **端层兜底** | 即使管层/云层被绕过，端层预检 + 沙箱仍限制进程行为 |
| **快速通道** | 低风险工具跳过云层 RPC，端层预检 + 管层本地规则直接放行，目标延迟 <5ms |
| **职责分离** | OpenResty 做路由 + 限流 + 安全预检；安全终判收敛到 virbius-engine |
| **渐进接入** | 各层可独立开关，兼容仅有端层或仅有管层的轻量部署 |

### 1.3 分阶段规划

| 阶段 | 观测(eyes) | 阻断(hands) |
|------|-----------|------------|
| **P0** | Falco(eBPF/plugin) + access log + Redis 审计流 + STI 审计 + Prompt Gateway(宪法注入) | HTTP 403 + allowlist + 计数 + schema 校验 + risk 阈值断连 + Runtime License 校验 |
| **P1** | STI Taint 小模型 + virbius-audit Falco 插件 + 审计完整性 | 人工审批流 + 自适应 risk 模型 + 记忆管控(Memory Interceptor) |
| **P2** | Tetragon observe(eBPF 可用时) | Landlock + drop caps + gVisor + Tetragon enforcer + TEE(金融级) |

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
| 管层 OpenResty 入口 | License 签名 + 过期 + 吊销状态 |
| 端层 virbius-core | License 的 allowed_tools 是否包含当前工具 |
| 云层 virbius-engine | 当前 session_risk_score 是否超过 License 的 risk_quota |

**许可证吊销**：通过 Redis pub/sub 实时通知各层。吊销后该 `app_id` 的所有后续请求被拒绝。
**会话中过期处理**：License 在会话进行中过期时，当前正在执行的工具调用允许完成（保持原子性），但完成后立即拒绝后续请求并通知 Agent 需要重新授权。端层 virbius-core 在每次预检时校验 License 剩余有效期，剩 5 分钟内到期时发出告警。


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
> 1. HTTP 层阻断（OpenResty allow/deny + engine 终判）
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

**SSRF 防护补偿**：Landlock 不能按 IP 限制 connect，但 subprocess 沙箱仅用于 `read_file`/`write_file`/`curl（白名单目标）`，不用于 `execute_python`/`shell`（后者走 gVisor）。`curl` 的 URL 在 HTTP 层（OpenResty）已做 schema 校验 + 白名单校验，不需要 syscall 级 connect 拦截。网络层由 K8s NetworkPolicy 兜底。

**多线程安全**：Agent 框架基于 tokio 异步运行时，是多线程的。禁止使用 fork()，改用 posix_spawn。

> **注意**：posix_spawn 配合 POSIX_SPAWN_SETSID 只创建新会话/进程组，**不创建新 PID namespace**。如需新 namespace 必须使用 clone3(CLONE_NEWPID | CLONE_NEWNS)。

**Landlock（P2 核心）**：

```rust
// virbius-core/src/sandbox/landlock.rs (P2)

pub struct LandlockSandbox {
    rules: LandlockRules,
    timeout_ms: u64,
}

pub struct LandlockRules {
    // v1 (kernel 5.13+): 文件路径
    read_paths: Vec<PathGlob>,      // 只读路径，如 ["/usr/*", "/lib/*"]
    write_paths: Vec<PathGlob>,     // 读写路径，如 ["/tmp/workdir/*"]
    exec_paths: Vec<PathGlob>,      // 可执行路径，如 ["/usr/bin/*"]
    // v4 (kernel 6.7+): 网络端口（可选，不支持则跳过）
    bind_ports: Vec<u16>,           // 允许绑定的端口
    connect_ports: Vec<u16>,        // 允许连接的端口
}

impl LandlockSandbox {
    /// posix_spawn + LD_PRELOAD(Landlock + drop caps) -> 执行子进程
    pub fn execute(&self, program: &str, args: &[String]) -> Result<String> {
        let (stdout_pipe, stderr_pipe) = create_pipes()?;

        // posix_spawn 创建子进程
        let mut attrs = posix_spawn::SpawnAttrs::new();
        attrs.set_flags(PosixSpawnFlags::POSIX_SPAWN_SETSID);

        // 通过 LD_PRELOAD 注入 Landlock + drop caps 初始化
        let env = vec![
            ("VIRBIUS_LANDLOCK_RULES", serde_json::to_string(&self.rules)?),
            ("VIRBIUS_DROP_CAPS", "all"),
            ("LD_PRELOAD", "libvirbius_sandbox_preload.so"),
        ];

        let pid = posix_spawn::spawn(program, args, &env, &attrs)?;

        // 父进程：等待 + 超时 + 读 stdout（无 supervisor，无 /dev/seccomp）
        let output = wait_and_read(pid, stdout_pipe, self.timeout_ms)?;
        Ok(output)
    }
}
```

**LD_PRELOAD 注入器**（比原方案简单——只有 Landlock + drop caps，无 seccomp-notify）：

```c
// virbius-sandbox-preload.c — 编译为 .so，通过 LD_PRELOAD 注入
// 顺序：Landlock -> drop caps（两步，无 seccomp）

__attribute__((constructor))
static void virbius_sandbox_init(void) {
    // 1. Landlock: 创建 ruleset + 添加规则 + restrict_self
    //    检测 ABI 版本：v1(5.13+, 文件) / v4(6.7+, 网络)
    //    不支持网络 v4 则跳过网络规则，只做文件
    //    Landlock 无 audit 模式，只能 enforce（deny），不产生观测事件
    virbius_landlock_apply(getenv("VIRBIUS_LANDLOCK_RULES"));

    // 2. Capabilities: 丢弃所有 CAP_*
    //    Landlock 不覆盖的威胁面由 drop caps 补充：
    //    - CAP_NET_RAW: 禁止 raw socket（ping/抓包）
    //    - CAP_SYS_PTRACE: 禁止 ptrace 注入其他进程（防逃逸）
    //    - CAP_SYS_ADMIN: 禁止 mount/namespace 操作
    //    - CAP_NET_ADMIN: 禁止改 iptables/路由
    //    - CAP_SYS_MODULE: 禁止加载内核模块
    virbius_drop_all_capabilities();

    // 3. 禁止通过 setuid 二进制提权
    prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);

    // 4. 清理环境变量
    unsetenv("VIRBIUS_LANDLOCK_RULES");
    unsetenv("VIRBIUS_DROP_CAPS");
    unsetenv("LD_PRELOAD");
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
fn detect_landlock_abi_version() -> u32 {
    // 尝试创建 ruleset 测试支持的 ABI 版本
    // v1 (5.13+): 文件路径
    // v2 (5.19+): 文件 + 引用
    // v3 (6.2+):  文件 + 设备
    // v4 (6.7+): 文件 + 网络
    let fd = landlock_create_ruleset(&RulesetAttr {
        handled_access_fs: AccessFs::ALL,
        handled_access_net: AccessNet::ALL,  // v4 only
    }, 0);
    match fd {
        Ok(_) => { close(fd); 4 }
        Err(ENOTSUP) => { /* 重试不带网络 */ ... 1 }
        Err(_) => 0  // Landlock 不可用
    }
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
    warm_containers: HashMap<Language, Vec<WarmContainer>>,
    min_warm: usize,
    max_idle: usize,
}
```

**降级策略**：gVisor 不可用时，自动降级为 Landlock subprocess + 超时 5s 强制 kill + 限制内存 128MB。

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

端层 virbius-core 需要集成到 MCP Server(Python/Node)中，包裹工具执行：

| 框架 | 集成方式 | 拦截点 |
|------|---------|--------|
| **Python MCP Server** | virbius-core 编译为 PyO3 扩展，在 tool handler 内调 virbius_core.precheck() + sandbox_execute() | tool handler 内 |
| **Node MCP Server** | virbius-core 编译为 napi-rs 扩展，同理 | tool handler 内 |
| **通用 subprocess** | MCP Server 启动工具时 spawn("virbius-sandbox", ...) | 进程启动前 |
| **LangChain** | SandboxedTool<T> wrapper，包装 Tool::call() | Tool::call() 内 |
| **OpenAI SDK** | SandboxedOpenAIClient 代理，拦截 tool_calls | 请求序列化前 |
| **通用 MCP proxy** | 端层启动本地 MCP Server 作为 Agent 和真实工具的中间代理 | tools/call 请求 |

通用 MCP proxy 模式：

```
Agent <-> 本地 MCP Proxy (virbius-core sandbox)
              |
              +-- allow -> 转发到远端 MCP Server
              +-- deny  -> 返回 ToolError
```

### 2.7 快速通道(低风险工具跳过云层)

对于低风险工具(search、计算器、格式化)，快速通道允许跳过云层 RPC：

```
低风险工具 (sandbox_type=none, fast_path=true)
  -> 端层预检 (参数校验 + allowlist)
  -> 管层 OpenResty (本地规则: 名单 + 计数 + schema)
  <- effective_action (本地决策，不调 virbius-engine)
  -> 端层执行 (同进程)
```

| 条件 | 说明 |
|------|------|
| sandbox_type == "none" | 无需沙箱隔离 |
| fast_path == true | 策略标记为快速通道 |
| session_risk_score < 30 | 当前会话风险分低于阈值 |
| tool_name in fast_allowlist | 工具在快速通道白名单中 |

任一条件不满足时，回退到全链路。

**冷启动防护**：新 session 默认 risk_score=0，但前 N 次调用强制走全链路(warmup)，N 次后若无异常才开放快速通道。

**fail-open/fail-closed**：virbius-engine 不可用时（网络分区），高风险工具 fail-closed(deny)，低风险工具 fail-open(allow + 全量审计)。

**风险缓解**：快速通道工具的审计事件全量采样(sample_rate=1.0)，异步送 virbius-engine 复核。若异步复核发现违规，提升 session_risk_score，后续请求自动退出快速通道。

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

**工具描述增强**——修改 Agent 发送给 LLM 的工具定义，嵌入约束：

```json
{
  "name": "curl",
  "description": "发起 HTTP 请求。限制: 仅允许连接 api.internal:443 和 cdn.internal:443。尝试连接其他主机将被阻断并记录。",
  "parameters": {...}
}
```

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
        let constitution = self.constitution_cache.read();
        let rules = constitution.select(ctx.scene, ctx.license.constitution_version);
        let system_augment = rules.render(ctx);
        self.prepend_system(messages, &system_augment)?;

        // 2. 动态上下文注入（append to system message）
        let dynamic_ctx = self.render_dynamic_context(ctx);
        self.append_system(messages, &dynamic_ctx)?;

        // 3. 工具描述增强
        if let Some(tools) = self.extract_tools(messages) {
            let augmented = self.augment_tool_descriptions(tools, ctx.license);
            self.replace_tools(messages, augmented)?;
        }

        // 4. PII 输入脱敏（仅 user/assistant 消息，不改 system）
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
| **MCP proxy 模式** | 复用 §2.6 MCP proxy，在转发前增强 | tools/call 前 |

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


---

## 3. 管层 — OpenResty 安全防火墙

### 3.1 职责

管层由 OpenResty 承担，复用现有 virbius-gateway Lua 插件：

```
端层 -> OpenResty (TLS/限流/安全预检) -> MCP Server (Python/Node)
         |
         +-- virbius-gateway Lua 插件
             +-- tool allowlist (lib/access_lists.lua)
             +-- 计数器 (lib/list_redis.lua)
             +-- 快速通道判断
             +-- 调 virbius-engine (cosocket POST /v1/evaluate)
             +-- HTTP 层阻断 (403)
```

| 能力 | 实现方式 |
|------|---------|
| TLS 终止 | OpenResty 原生 |
| 长连接/SSE 转发 | OpenResty proxy_pass |
| 限流 | OpenResty limit_req (已有) |
| tool allowlist | Lua 插件 lib/access_lists.lua (已有) |
| 计数器 | Lua 插件 lib/list_redis.lua (已有) |
| 调 virbius-engine | Lua cosocket POST /v1/evaluate |
| HTTP 阻断 | ngx.exit(403) + JSON-RPC error |

### 3.2 Lua 安全预检

复用并扩展现有 virbius-gateway/plugins/openresty/access.lua：

```lua
function _M.access(conf, ctx)
    local tool_name = ctx.tool_name
    local session_id = ctx.session_id

    -- 1. tool allowlist (复用 lib/access_lists.lua)
    if not access_lists.match("tool-allowlist", tool_name) then
        return deny("tool_not_allowed")
    end

    -- 2. 累计计数器 (复用 lib/list_redis.lua)
    local count = list_redis.get_cumulative(
        "tool:" .. tool_name .. "-session:" .. session_id)
    if count > 50 then
        return deny("tool_rate_exceeded")
    end

    -- 3. 快速通道判断
    if is_fast_path(tool_name) and get_session_risk(session_id) < 30 then
        return allow("fast_path")
    end

    -- 4. 调 virbius-engine 终判
    local decision = call_engine({
        trace_id = ctx.trace_id,
        tool_name = tool_name,
        session_id = session_id,
        args = ctx.args
    })
    if decision.action == "block" then
        return deny(decision.reason)
    end
    return allow("engine:" .. decision.action)
end
```

### 3.3 Nginx upstream 配置自动生成

MCP 路由由 virbius-control 编译为 Nginx upstream + location 配置：

```
virbius-control -> mcp_routes 表 -> virbius-compiler -> Nginx config -> nginx -s reload
```

示例 Nginx 配置：

```nginx
upstream mcp_github {
    server mcp-github.internal:8080;
    keepalive 32;
}

location /mcp/github {
    access_by_lua_block { require("virbius-gateway").access() }
    proxy_pass http://mcp_github/sse;
    proxy_set_header Connection "";
    proxy_http_version 1.1;
}
```

### 3.4 schema 校验和 PII 脱敏的职责下沉

| 能力 | 位置 | 理由 |
|------|------|------|
| schema 校验 | 端层 virbius-core (Rust jsonschema crate) | Lua JSON Schema 库能力弱 |
| 输入 PII 脱敏 | 端层 virbius-core dlp/engine.rs (已有) | 发送 LLM 前脱敏 |
| 输出 PII 脱敏 | 端层 virbius-core (工具返回前) | 避免管层重复脱敏 |
| tool allowlist | 管层 OpenResty Lua | HTTP 层第一道防线 |
| 计数器 | 管层 OpenResty Lua | HTTP 层频控 |
| engine 终判 | 云层 virbius-engine | 复杂语义判断 |

> **删除原设计的 AgentGateway**：OpenResty 已承担 MCP 路由 + 负载均衡，不需要额外组件。原 §3.3 AgentGateway 集成和 §3.4 对比表已删除。

---

## 4. 核层 — Falco 观测引擎

### 4.1 职责

核层是运行时观测层，P0 只实现观测(eyes)，P2 补阻断(hands)。

| 范围 | P0 观测 | P2 阻断 |
|------|---------|---------|
| Agent 进程内 syscall | Falco eBPF 观测(可用时) | Landlock 文件路径阻断 |
| 容器逃逸检测 | Falco eBPF 观测(可用时) | Tetragon enforcer kill |
| SSRF / 内网扫描 | Falco eBPF 观测 connect | NetworkPolicy 网络阻断 |
| 基础设施异常 | Falco plugin (k8saudit + cloudtrail) | 云厂商原生 enforcement |

**P0 安全模型**：核层只观测不阻断。发现异常 -> 上报审计流 -> 提升 session risk score -> 管层 HTTP 层阻断后续请求。这是"检测 -> 累积风险 -> 阻断后续"模型，对 Agent 多轮调用场景有效。

### 4.2 Falco 驱动降级链

```
detect_mode()
  |
  +-- 有 CAP_BPF + 内核 5.8+ + BTF + KPROBE_OVERRIDE
  |    -> Tetragon enforcer (P2, 完整 enforcement)
  |
  +-- 有 CAP_BPF + 内核 5.8+ + BTF, 无 KPROBE_OVERRIDE
  |    -> Falco eBPF 驱动 (观测 only)
  |
  +-- 无 CAP_BPF, 有 CAP_SYS_PTRACE
  |    -> Falco userspace 驱动 (ptrace, 性能差 5-10x)
  |
  +-- 无任何特权
       -> Falco plugin 模式 (k8saudit + filetail + 自定义插件)
```

**为什么选 Falco 而非 Tetragon 做眼睛**：Falco 有驱动降级链(eBPF -> userspace -> plugin)，eBPF 不可用时不至于完全失明。Tetragon 无降级，eBPF 一断就瞎。Tetragon 在 P2 作为 enforcement 增强使用。

### 4.3 Tetragon 检测逻辑

```rust
// virbius-kernel/src/detect.rs

pub enum KernelMode {
    Tetragon,        // 完整 eBPF + enforcement (P2)
    FalcoEbpf,       // eBPF 观测，无 enforcement
    FalcoUserspace,  // ptrace 驱动
    FalcoPlugin,     // 纯日志/审计
    Disabled,
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
        return KernelMode::FalcoPlugin;
    }

    let kver = kernel_version().unwrap_or((0, 0));
    let btf_ok = std::fs::metadata("/sys/kernel/btf/vmlinux")
        .map(|m| m.len() > 0).unwrap_or(false);
    let kprobe_override = kernel_config("CONFIG_BPF_KPROBE_OVERRIDE");

    if kver < (5, 8) || !btf_ok {
        return if has_sys_ptrace { KernelMode::FalcoUserspace }
               else { KernelMode::FalcoPlugin };
    }

    if kprobe_override && has_sys_admin {
        return KernelMode::Tetragon;
    }

    KernelMode::FalcoEbpf
}
```

Tetragon 硬性要求：

| 检测项 | 要求 | 常见失败原因 |
|--------|------|------------|
| 内核版本 | >= 5.8 (推荐 5.10+) | 老内核 |
| **BTF**(最关键) | /sys/kernel/btf/vmlinux 存在且 > 0 字节 | CONFIG_DEBUG_INFO_BTF 未开启 |
| 内核 config | CONFIG_BPF=y, CONFIG_KPROBES=y, CONFIG_TRACING=y | 硬化内核裁剪 |
| enforcement 额外要求 | CONFIG_BPF_KPROBE_OVERRIDE=y | 多数云内核默认关 |
| 权限 | CAP_SYS_ADMIN 或 CAP_BPF+CAP_PERFMON | serverless / PSA restricted |
| tracefs | /sys/kernel/tracing/ 已挂载 | 容器内未映射 |
| bpffs | /sys/fs/bpf/ 已挂载 | 容器内未映射 |

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
| agent_pids | BPF_MAP_TYPE_HASH | 当前受保护 Agent 的 PID 集合 |

> **注**：connect_allowlist 分为 IP(LPM_TRIE) 和 Port(HASH) 两个 map，因为 LPM_TRIE 只能匹配 IP 前缀，不能匹配 IP:Port。

### 4.5 Falco plugin 模式(serverless 降级)

当 eBPF 不可用时，Falco 降级为 plugin 模式，消费日志/审计事件：

| 插件 | 数据源 | 监控内容 |
|------|--------|---------|
| **k8saudit** | K8s API Server audit log | privileged Pod 创建、secret 访问、RBAC 变更、exec into pod |
| **filetail** | OpenResty access log | 工具调用频次、调用链异常、4xx/5xx 突增 |
| **filetail** | MCP Server 应用日志 | 工具执行失败率、返回值过大、执行超时 |
| **自定义 virbius-audit** | Redis Stream 审计流 | session risk 累积、Landlock 连续 deny、批量攻击 |
| **cloudtrail** | AWS CloudTrail | IAM 变更、S3 访问、安全组修改 |

> **限制**：plugin 模式无 syscall 可见性。覆盖"谁在动 Agent 基础设施"的威胁面，不覆盖"Agent 运行时做了什么"(后者由端层 Landlock 在 P2 承担)。

### 4.6 PID -> trace_id 映射

| 存储 | Key | Value | 生命周期 |
|------|-----|-------|---------|
| Redis pid_trace:{pid} | PID | trace_id + session_id + start_ts | TTL 1h |
| eBPF agent_pids map | PID | 1(标记受监控) | Agent 启动时写入、退出时删除 |

> **stale mapping 防护**：Agent 崩溃时无法清理 PID 映射，TTL 内 PID 可能被复用。注册时写入 PID + 启动时间戳，查询时校验时间戳。

### 4.7 部署模式

| 模式 | 判定条件 | 观测 | 阻断 |
|------|---------|------|------|
| host | 裸机/自管 VM + root | Falco eBPF + Tetragon(P2) | Tetragon(P2) + Landlock(P2) |
| daemonset | K8s 标准节点池 + privileged | 同上 | 同上 |
| pod-observe | serverless(Fargate/Autopilot) | Falco plugin + 云厂商告警 | 端层 Landlock(P2) + NetworkPolicy |
| audit-only | 前期观测 | 上述观测的只读子集 | 无 |

> **删除原设计的 sidecar 模式**：sidecar 模式自相矛盾——Falco 也需 eBPF 特权，Landlock 不能由 sidecar 应用到其他容器（必须在 Pod spec 中声明）。serverless 环境下 Landlock profile 通过 mutating admission webhook 注入 Pod spec。

---

## 5. 云层 — 统一策略大脑

### 5.1 职责

参考 VirbiusLLM 的 virbius-engine + virbius-control 设计并做了大量扩展以适应 Agent 专属场景（详见 §10）。

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
| pid_trace:{pid} | String | 1h | PID -> trace_id + session_id 映射 |

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

**Groovy ctx 扩展 API(新增)**：

| 函数 | 说明 | 数据来源 |
|------|------|---------|
| ctx.sessionHistory(n) | 最近 N 次工具调用 | 预加载自 Redis LRANGE |
| ctx.sessionRiskScore() | 当前会话风险分 | 预加载自 Redis GET |
| ctx.incrementRiskScore(delta) | 提升风险分 | 异步写 Redis INCRBY |
| ctx.recordToolCall(tool_name, args) | 记录本次工具调用 | 异步写 Redis LPUSH + LTRIM |
| ctx.toolName() | 当前工具名 | 请求上下文 |
| ctx.lastToolResult() | 上一个工具的返回值摘要 | 预加载自 Redis LRANGE 0 0 |
| ctx.isInternalHost(url) | 判断 URL 是否指向内部网络 | 根据 License 或策略中配置的 CIDR/域名列表判断 |

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
  +-- tb_mcp_routes           -> 管层 Nginx upstream + location 配置
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
  |   +-- -> OpenResty: 名单 + 计数 (via virbius-gateway manifest)
  |
  +-- REST (新增)
  |   +-- -> virbius-kernel: Falco 规则 + eBPF maps
  |
  +-- Nginx config (新增)
      +-- -> OpenResty: upstream + location 配置 (virbius-compiler 生成)
```

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
[2] 管层 (OpenResty + virbius-gateway Lua)
    +-- tool allowlist 校验 (lib/access_lists.lua)
    +-- 累计计数器 (lib/list_redis.lua)
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
[2] 管层 (OpenResty) 执行决策
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
[2] 管层 (OpenResty)
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
  |   +-- -> OpenResty: 名单 + 参数 schema (via virbius-gateway manifest)
  |
  +-- REST (新增)
  |   +-- -> virbius-kernel: Falco 规则 + eBPF maps
  |
  +-- Nginx config (新增，替代 xDS)
      +-- -> OpenResty: upstream + location 配置 (virbius-compiler 生成)
```

> **删除原设计的 xDS 适配器**：OpenResty 使用 Nginx 原生配置，由 virbius-compiler 生成 upstream + location 配置，nginx -s reload 热加载。不需要 xDS 协议。

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

## 8. 部署视图

### 8.1 组件端口

| 组件 | 端口 | 部署位置 |
|------|------|---------|
| **Agent 应用** | 动态 | 用户侧 / Serverless 容器 |
| **virbius-core** (端) | 嵌入 MCP Server 进程 | 与 MCP Server 同进程 |
| **OpenResty** + virbius-gateway Lua | 80/443 | 独立部署 / K8s |
| **MCP Server** (Python/Node) | 8080+ | 独立部署 / K8s |
| **virbius-engine** | 8082 | 云侧 |
| **virbius-control** | 8080 | 云侧 |
| **Falco** (核层观测) | 无（DaemonSet） | Agent 所在宿主机 |
| **virbius-kernel-daemon** | 9090 | Agent 所在宿主机 |
| **Redis** | 6379 | 云侧 |
| **Database** | — | 云侧 |

> **删除原设计的 AgentGateway (9080)**：MCP 路由由 OpenResty 承担。
> **删除原设计的 virbius-gateway-agent (9070)**：安全预检由 OpenResty Lua 插件承担。

### 8.2 部署拓扑

```
Agent Client
  | MCP / JSON-RPC over HTTPS
  v
+----------------------------------------------------------+
|  OpenResty (:443)                                        |
|  +-- TLS 终止                                             |
|  +-- 限流 / 长连接 / SSE 转发                              |
|  +-- virbius-gateway Lua 插件 (安全预检)                   |
|  +-- Nginx upstream -> MCP Server 路由                    |
+----------------------------------------------------------+
  | allow -> 转发；block -> 403
  v
+----------------------------------------------------------+
|  MCP Server (Python/Node) (:8080+)                       |
|  +-- 接收 tools/call，执行工具逻辑                         |
|  +-- virbius-core (端层预检 + P0 同进程执行)               |
|  +-- P2: Landlock + drop caps 沙箱                   |
+----------------------------------------------------------+
  | 核层旁路
  v
+----------------------------------------------------------+
|  Falco DaemonSet (宿主机)                                 |
|  +-- eBPF 驱动 (标准节点池) / plugin 模式 (serverless)     |
|  +-- 事件 -> Redis Audit Stream                           |
+----------------------------------------------------------+

+----------------------------------------------------------+
|  云侧                                                     |
|  +-- virbius-engine (:8082) — Groovy L3 终判              |
|  +-- virbius-control (:8080) — 规则管理 + 发布             |
|  +-- Redis (:6379) — session 状态 + 审计流                |
|  +-- Database — 规则持久化                                 |
+----------------------------------------------------------+
```

---

## 9. 第三方技术栈依赖与稳定性

### 9.1 依赖清单

| 层 | 技术 | 用途 | 稳定性 | 替代方案 |
|----|------|------|--------|---------|
| 端 | Landlock | 文件路径限制(P2) | 较新(文件 5.13/2021, 网络 6.7/2024) | AppArmor |
| 端 | drop caps | capabilities 丢弃(P2) | 极稳定(内核 2.2, 1999) | 无 |
| 端 | gVisor | 不可信代码沙箱(P2) | 稳定(Google, GKE 使用) | Kata Containers |
| 端 | PyO3 / napi-rs | Rust<->Python/Node 绑定 | 稳定(广泛使用) | subprocess |
| 管 | OpenResty + LuaJIT | 反向代理 + 安全插件 | 稳定(10年+生产) | APISIX / Envoy |
| 核 | eBPF + BTF/CO-RE | 内核观测 | 极稳定(行业标准) | 无 |
| 核 | Falco | 观测引擎(CNCF 毕业) | 极稳定(CNCF Graduated) | Tracee |
| 核 | Tetragon | eBPF enforcement(P2) | 较新(Isovalent/Cisco) | Falco + Landlock |
| 云 | Groovy | L3 规则脚本 | 稳定但 declining(Apache) | Python sandbox |
| 云 | Redis | session + 审计流 | 极稳定 | KeyDB |
| 云 | Spring Boot | engine/control 框架 | 极稳定 | Quarkus |
| 云 | qwen3guard:0.6B | STI Taint 小模型(P1) | 较新 | 任意 guard 模型 |
| 协议 | MCP | 工具调用协议 | 较新(Anthropic, 2024) | 自定义 JSON-RPC |

### 9.2 风险评估

**Tier 1 极稳定(无风险)**：eBPF, Redis, Nginx, Spring Boot, K8s, drop caps

**Tier 2 稳定(需关注)**：

| 技术 | 风险 | 缓解 |
|------|------|------|
| OpenResty/LuaJIT | Mike Pall 半退休; 迭代放缓 | 核心功能已稳定; 可迁 APISIX |
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
- OpenResty — 管层全部安全检查(可迁 APISIX)
- virbius-engine — 云层终判

**可降级(失败有 fallback)**：
- Falco eBPF 驱动 -> userspace -> plugin 降级链
- gVisor -> Landlock subprocess 降级
- Tetragon -> Falco + Landlock subprocess(P2) 替代
- qwen3guard -> 任意 guard 模型

---

## 10. 与 VirbiusLLM 的关系

VirbiusAgent 采用**文件级复用**策略，不作为 VirbiusLLM 的项目依赖。两个项目独立演进，VirbiusAgent 从 VirbiusLLM 拷贝所需代码后自行维护。

**决策理由**：virbius-engine/virbius-control/virbius-compiler 需要大幅扩展（加 License、宪法、Agent 规则、Redis session、Nginx config 编译），作为依赖不如直接拷贝修改。virbius-core 虽能完整复用，但其 EdgeManifest/EngineClient 等结构需扩展字段，依赖关系下只能 fork 或提 PR。两个项目同属一个团队维护，拷贝后独立演进更灵活。

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
| virbius-gateway | `plugins/openresty/access.lua` | 通用 access 阶段 | 加 tool allowlist + tool 计数 + engine 调用 |
| virbius-control | `RuleService.java` | 规则 CRUD | 加 Agent 规则类型 + License CRUD + 宪法管理 |
| virbius-control | `ArtifactService.java` | 产物编译 | 加 Nginx config + Landlock profile + Constitution template 编译 |
| virbius-control | `PublishOrchestrator.java` | 4 阶段发布 | 加各层独立放量(端层 device_id/管层 tenant_id/核层 PID) |
| virbius-compiler | 编译器 | edge manifest + gateway JSON + engine input | 加 Nginx upstream + Landlock profile + Constitution template 输出 |

#### 需新建（VirbiusAgent 原创）

| 组件 | 语言 | 功能 |
|------|------|------|
| `virbius-core/src/prompt_gateway.rs` | Rust | Prompt Gateway(宪法注入 + PII 脱敏) |
| `virbius-core/src/license.rs` | Rust | License 校验(签名/过期/吊销) |
| `virbius-core/src/sandbox/landlock.rs` | Rust | P2: Landlock + drop caps 沙箱 |
| `virbius-core/src/sandbox/gvisor_pool.rs` | Rust | P2: gVisor 预热池 |
| virbius-core MCP 绑定 | Rust | PyO3 / napi-rs 绑定 |
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
+-- virbius-gateway/           # 拷贝自 VirbiusLLM
|   +-- lib/                   # 直接复用 (11 个 Lua 文件)
|   +-- plugins/openresty/     # 复用 + 扩展 access.lua
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
|   +-- (加 Nginx config + Landlock + Constitution 编译)
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

## 11. 路线图

### P0 — 核心安全链路（身份 + 观测 + HTTP 阻断 + Prompt Gateway）

| 任务 | 组件 | 估计 |
|------|------|------|
| Runtime License 签发 + 校验 + 吊销 | control + 全层 | 3w |
| Prompt Gateway 基础版（宪法注入 + PII 脱敏） | virbius-core | 3w |
| 企业 AI 智能体宪法 v1（规则定义 + 编译） | control + compiler | 2w |
| 端层预检（参数校验 + allowlist + JSON Schema） | virbius-core | 2w |
| 端层 MCP Server 集成（PyO3 / napi-rs / subprocess） | virbius-core | 3w |
| 管层 OpenResty Lua 插件（allowlist + 计数 + engine 调用） | virbius-gateway | 3w |
| 管层 Nginx upstream 自动生成（control -> compiler） | control + compiler | 2w |
| 云层 Redis session 状态（history + risk + count） | engine | 3w |
| 云层 Groovy L3 Agent 规则（工具链检测 + 场景匹配） | engine | 2w |
| 云层 Groovy ctx 扩展（sessionHistory / riskScore，内存预加载） | engine | 2w |
| 控制面 Agent 规则 CRUD + 发布 | control | 2w |
| 核层 Falco 部署 + eBPF 驱动（标准节点池） | virbius-kernel | 2w |
| 核层 Falco plugin 模式（serverless 降级: k8saudit + filetail） | virbius-kernel | 2w |
| 核层 PID->trace_id 映射 + 审计上报 | virbius-kernel | 1w |
| 端到端集成测试 | 全组件 | 3w |
| **P0 合计** | | **~33w** |

### P1 — 增强观测 + 记忆管控

| 任务 | 说明 |
|------|------|
| 端层快速通道（低风险工具跳过云层） | 延迟优化 |
| 自定义 virbius-audit Falco 插件 | 消费 Redis Stream，Agent 专用规则 |
| 审计大盘 | session risk + 工具调用 + 告警可视化 |
| STI 语义审计（Taint 维度调小模型） | 工具返回值注入检测 |
| 输出 PII 脱敏（端层，工具返回前） | 复用 virbius-core dlp/engine.rs |
| Falco 规则库扩充（Agent 专用规则集） | 工具调用模式、SSRF 特征、数据外泄 |
| 高风险工具人工审批流 | engine -> 审批 UI -> 超时 deny |
| session risk 自适应模型 | 从规则阈值升级为加权累积 |
| 审计完整性（hash chain） | 防篡改 |
| 记忆管控（Memory Interceptor） | Agent 记忆读写拦截 + 脱敏 + 注入检测 |

### P2 — 阻断(hands) + TEE

| 任务 | 说明 |
|------|------|
| Landlock + drop caps 沙箱 | 文件路径限制 + capabilities 丢弃 + ABI 版本适配 |
| gVisor 预热池 | 不可信代码执行沙箱 |
| Tetragon 检测 + 降级逻辑（detect_mode） | 内核能力自动检测 + 模式选择 |
| Tetragon enforcer（eBPF 可用时） | 宿主级 enforcement 叠加 |
| eBPF 自定义观测程序（execveat + IPv6） | 补充 Falco 内置规则 |
| TEE 硬件安全根（金融级） | SGX/SEV-SNP enclave + 远程证明 |
| 端到端红队测试 | 安全验证 |

### 各阶段对照

| 阶段 | 观测(eyes) | 阻断(hands) | 新增能力 |
|------|-----------|------------|---------|
| P0 | Falco + access log + Redis 审计 + STI + Prompt Gateway | HTTP 403 + License + allowlist + 计数 + schema + risk 断连 | 身份管控 + 提示增强 |
| P1 | STI Taint + virbius-audit 插件 + 审计完整性 | 人工审批 + 自适应 risk + 记忆管控 | 记忆管控 |
| P2 | Tetragon observe | Landlock + gVisor + Tetragon enforcer + TEE | syscall 级阻断 + 硬件安全 |

---

## 变更日志

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-04 | 初始设计：端管核云四层架构 |
| v1.1 | 2026-07-05 | 新增预检/执行两阶段、快速通道 |
| v2.0 | 2026-07-06 | 重大修订：1) 管层改为 OpenResty+Lua(删除 gateway-agent+AgentGateway) 2) 核层改为 Falco 观测引擎(眼睛/手分离) 3) 新增 Tetragon 检测+Falco 降级链 4) 新增 Falco plugin 模式(serverless 降级) 5) 删除 sidecar 部署模式 6) P0 只实现观测，seccomp-notify/Landlock/gVisor 推迟至 P2 7) 修正 posix_spawn/ seccomp 白名单/Groovy 逻辑 bug/eBPF IPv6 等技术问题 8) 新增 §9 第三方技术栈依赖与稳定性 |
| v2.1 | 2026-07-06 | 新增 §1.4 身份标识体系：app_id 即 agent_id，不区分类型与实例；新增 Agent 运行许可证(Runtime License)机制 |
| v2.2 | 2026-07-06 | 新增 §2.8 Prompt Gateway（提示增强）：宪法约束注入 + 动态上下文注入 + 工具描述增强 + PII 输入脱敏 |
| v2.3 | 2026-07-06 | P2 subprocess 沙箱简化：seccomp-notify + Landlock 改为 Landlock + drop caps。删除 seccomp-notify supervisor（消除 TOCTOU/SPOF 风险），SSRF 防护由 HTTP 层 URL 校验 + NetworkPolicy 承担 |
| v2.4 | 2026-07-06 | 路线图修订：1) P0 新增 Runtime License + Prompt Gateway + 宪法 v1 2) P0 快速通道/Falco 插件/审计大盘移至 P1 3) P1 新增记忆管控 4) P2 新增 TEE 硬件安全根 5) P2 合并重复任务 6) macOS 降级说明改为不做沙箱 |
| v2.5 | 2026-07-06 | 新增 §9.4 与 VirbiusLLM 的关系：文件级代码参考策略，35 个文件可参考 VirbiusLLM 实现 |
| v2.6 | 2026-07-06 | 全面修正：1) access.lua 移出直接复用表（已在需扩展表）2) §9.4 独立为 §10，路线图重编号为 §11 3) 复用计数修正(25+7+13) 4) 补充 License 会话中过期处理 5) 补充 isInternalHost() 定义 6) 修正 Tetragon 降级引用 7) 项目结构图补充缺失文件 8) §1.4 增加对 VirbiusLLM 关系的前向引用 9) §2.2 沙箱流程改按隔离级别排序 |
| v2.7 | 2026-07-06 | 路线图修订：Tetragon 检测 + detect_mode 从 P0 移至 P2（Tetragon 是阻断层能力，P0 只做观测） |
