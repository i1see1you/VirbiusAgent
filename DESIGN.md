# Agent 安全防护 — 端管核云四层架构设计

| 项目 | 说明 |
|------|------|
| 文档版本 | v2.0 |
| 状态 | 草案 |
| 关联 | [README.md](README.md) |
| 基础平台 | [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) |

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
10. 路线图

---

## 1. 总体架构

### 1.1 四层总览

```
Agent Framework (LangChain / OpenAI SDK / AutoGen / ...)
  | tool_call
  v
[1] Edge - virbius-core (extended)
    precheck: args + allowlist + JSON Schema
    execute:  P0 in-process / P2 seccomp-notify + Landlock + gVisor
  |
[2] Gateway - OpenResty + virbius-gateway Lua plugin
    TLS/rate-limit/long-conn + allowlist + counter + engine call + HTTP block
  |
[3] Kernel - Falco observer (observation layer)
    eBPF driver (standard node) / plugin mode (serverless fallback)
    Tetragon enforcer (P2, eBPF available)
    observe: syscall/net/file + audit stream + session risk
    enforce(P2): seccomp-notify + Landlock (edge) / Tetragon (kernel)
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
| **观测与阻断分离** | 观测(eyes)和阻断(hands)由不同技术栈承担。观测随环境降级(eBPF->ptrace->plugin)，阻断始终由端层 seccomp-notify+Landlock 保证(P2) |
| **观察先行** | P0 只实现观测(Falco + HTTP 层阻断 + session risk 累积)，P2 补 syscall 级阻断 |
| **eBPF 是增强非依赖** | eBPF 可用时叠加 Tetragon enforcer；不可用时端层 seccomp-notify 仍是完整可用的阻断 |
| **端层兜底** | 即使管层/云层被绕过，端层预检 + 沙箱仍限制进程行为 |
| **快速通道** | 低风险工具跳过云层 RPC，端层预检 + 管层本地规则直接放行，目标延迟 <5ms |
| **职责分离** | OpenResty 做路由 + 限流 + 安全预检；安全终判收敛到 virbius-engine |
| **渐进接入** | 各层可独立开关，兼容仅有端层或仅有管层的轻量部署 |

### 1.3 分阶段规划

| 阶段 | 观测(eyes) | 阻断(hands) |
|------|-----------|------------|
| **P0** | Falco(eBPF/plugin) + access log + Redis 审计流 + STI 审计 | HTTP 403 + allowlist + 计数 + schema 校验 + risk 阈值断连 |
| **P1** | STI Taint 小模型 + virbius-audit Falco 插件 + 审计完整性 | 人工审批流 + 自适应 risk 模型 |
| **P2** | Tetragon observe(eBPF 可用时) | seccomp-notify + Landlock + gVisor + Tetragon enforcer |

---

## 2. 端层 — Agent 工具调用预检与执行

### 2.1 职责

| 阶段 | 动作 | 延迟 |
|------|------|------|
| **预检** | 参数校验、tool allowlist、JSON Schema 校验、本地规则匹配 | <0.5ms |
| **执行** | P0: 同进程执行 / P2: seccomp-notify / gVisor 沙箱 | P0: <0.1ms / P2: 见 §2.2 |

**关键约束**：预检阶段不执行任何工具逻辑。只有终判返回 allow 后才进入执行阶段。

### 2.2 分层隔离策略(P0 -> P2 渐进)

```
ToolCallRequest { name, args }
  |
  +-- P0: sandbox_type = "none"
  |    同进程执行（只做预检，不隔离）
  |    适用：所有工具（P0 阶段不区分沙箱类型）
  |    延迟：冷 <0.1ms / 热 <0.1ms
  |    安全保障：HTTP 层阻断 + session risk 累积 + Falco 观测
  |
  +-- P2: sandbox_type = "subprocess"
  |    posix_spawn + seccomp-notify + Landlock
  |    适用：read_file、write_file、curl（白名单目标）
  |    延迟：冷 ~2ms / 热 ~1ms + notify 决策 ~10-50us/syscall
  |
  +-- P2: sandbox_type = "gvisor"
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

### 2.3 P2: seccomp-notify + Landlock 子进程(Linux)

> **P2 实现，P0 不涉及。** 以下为长期设计参考。

**多线程安全**：Agent 框架基于 tokio 异步运行时，是多线程的。禁止使用 fork()，改用 posix_spawn 或 clone3(CLONE_NEWPID)。

> **注意**：posix_spawn 配合 POSIX_SPAWN_SETSID 只创建新会话/进程组，**不创建新 PID namespace**。如需新 namespace 必须使用 clone3(CLONE_NEWPID | CLONE_NEWNS)。

**seccomp-notify(SECCOMP_RET_USER_NOTIF)** 是 P2 的核心 enforcement 机制。与 seccomp strict mode(只能 kill)不同，notify 模式允许 userspace supervisor 在 syscall 执行前做策略决策：

```rust
// virbius-core/src/sandbox/notify.rs (P2)

pub struct NotifySupervisor {
    seccomp_filter: BpfProgram,      // SECCOMP_RET_USER_NOTIF for connect/open/execve
    connect_allowlist: Vec<IpCidr>,   // 允许连接的 IP 范围
    file_allowlist: Vec<PathPattern>, // 允许访问的文件路径
}

impl NotifySupervisor {
    /// posix_spawn + seccomp-notify -> 同步 userspace 决策
    pub fn execute(&self, program: &str, args: &[String]) -> Result<String> {
        // 1. 子进程安装 seccomp filter（安全关键 syscall 走 USER_NOTIF）
        //    read/write/brk 等高频 syscall 走 strict filter（直接 allow）
        // 2. 父进程通过 /dev/seccomp 接收通知
        // 3. 对每个通知的 syscall：
        //    - 解析参数（connect 的 sockaddr -> 目标 IP:Port）
        //    - ioctl(SECCOMP_IOCTL_NOTIF_ID_VALID) 防 TOCTOU
        //    - 查 allowlist -> allow (CONTINUE) / deny (RETURN -EPERM)
        // 4. 子进程执行完毕，收集结果
    }
}
```

**TOCTOU 防护**：supervisor 读取 syscall 参数后、决策前，参数内存可能被篡改。必须在决策前调用 ioctl(SECCOMP_IOCTL_NOTIF_ID_VALID) 校验 notification 仍有效。

**Supervisor 高可用**：supervisor 崩溃会导致所有被监控进程的 syscall 永久挂起。supervisor 必须极简 + watchdog；崩溃时降级为 SECCOMP_RET_KILL（kill 而非 hang）。

**Landlock(P2)**：

```c
// virbius-sandbox-preload.c — 编译为 .so，通过 LD_PRELOAD 注入
// 顺序：Landlock -> drop caps -> seccomp（seccomp 必须最后应用）

__attribute__((constructor))
static void virbius_sandbox_init(void) {
    // 1. Landlock: 只读 /usr, /lib; 读写仅 /tmp/workdir
    //    Landlock 无 audit 模式，只能 enforce（deny），不产生观测事件
    virbius_landlock_apply(getenv("VIRBIUS_LANDLOCK_PATHS"));

    // 2. Capabilities: 丢弃所有 CAP_*
    virbius_drop_all_capabilities();

    // 3. seccomp-notify: 安全关键 syscall 走 USER_NOTIF，其余走 strict allow
    //    注意：landlock/capset 初始化 syscall 已在前面完成
    //    不需要在运行期 seccomp 白名单中
    virbius_seccomp_notify_apply(getenv("VIRBIUS_SECCOMP_RULES"));

    unsetenv("VIRBIUS_SECCOMP_RULES");
    unsetenv("VIRBIUS_LANDLOCK_PATHS");
}
```

**seccomp 白名单**(P2 read_file 工具运行期，strict allow 部分)：

```json
{
  "tool_name": "read_file",
  "exec_env": {
    "sandbox_type": "subprocess",
    "strict_allow_syscalls": [
      "read", "write", "openat", "close", "fstat", "newfstatat",
      "mmap", "munmap", "mprotect", "brk",
      "rt_sigaction", "rt_sigprocmask", "getrandom",
      "pread64", "preadv", "ioctl", "rseq",
      "exit_group", "exit"
    ],
    "notify_syscalls": ["connect", "open", "openat", "execve", "execveat"],
    "allowed_file_paths": ["/tmp/data/*", "/home/user/workdir/*"],
    "timeout_ms": 5000
  }
}
```

> **注**：白名单已包含 mprotect/rt_sigaction/getrandom/pread64/ioctl/rseq/newfstatat，覆盖 glibc 2.36+ 动态链接二进制的启动需求。

关于 macOS：不支持 seccomp/Landlock，降级为 sandbox_init() + 环境变量清理。syscall 级限制依赖核层 Falco 或跳过。

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

**降级策略**：gVisor 不可用时，自动降级为 seccomp-notify subprocess + 超时 5s 强制 kill + 限制内存 128MB。

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
    seccomp_profiles: HashMap<String, SeccompProfile>, // P2：seccomp 模板
    sdk_config: SdkConfig,
}
```

> **注**：seccomp_profiles 可能体积较大，建议提供独立 fetch 端点 /api/v1/edge/seccomp-profiles，不随主 manifest 全量拉取。

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
| Agent 进程内 syscall | Falco eBPF 观测(可用时) | seccomp-notify 同步阻断 |
| 容器逃逸检测 | Falco eBPF 观测(可用时) | Tetragon enforcer kill |
| SSRF / 内网扫描 | Falco eBPF 观测 connect | seccomp-notify connect 阻断 |
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
| **自定义 virbius-audit** | Redis Stream 审计流 | session risk 累积、seccomp 连续 deny、批量攻击 |
| **cloudtrail** | AWS CloudTrail | IAM 变更、S3 访问、安全组修改 |

> **限制**：plugin 模式无 syscall 可见性。覆盖"谁在动 Agent 基础设施"的威胁面，不覆盖"Agent 运行时做了什么"(后者由端层 seccomp-notify 在 P2 承担)。

### 4.6 PID -> trace_id 映射

| 存储 | Key | Value | 生命周期 |
|------|-----|-------|---------|
| Redis pid_trace:{pid} | PID | trace_id + session_id + start_ts | TTL 1h |
| eBPF agent_pids map | PID | 1(标记受监控) | Agent 启动时写入、退出时删除 |

> **stale mapping 防护**：Agent 崩溃时无法清理 PID 映射，TTL 内 PID 可能被复用。注册时写入 PID + 启动时间戳，查询时校验时间戳。

### 4.7 部署模式

| 模式 | 判定条件 | 观测 | 阻断 |
|------|---------|------|------|
| host | 裸机/自管 VM + root | Falco eBPF + Tetragon(P2) | Tetragon(P2) + seccomp-notify(P2) |
| daemonset | K8s 标准节点池 + privileged | 同上 | 同上 |
| pod-observe | serverless(Fargate/Autopilot) | Falco plugin + 云厂商告警 | 端层 seccomp-notify(P2) + NetworkPolicy |
| audit-only | 前期观测 | 上述观测的只读子集 | 无 |

> **删除原设计的 sidecar 模式**：sidecar 模式自相矛盾——Falco 也需 eBPF 特权，seccomp 不能由 sidecar 应用到其他容器（必须在 Pod spec 中声明）。serverless 环境下 seccomp profile 通过 mutating admission webhook 注入 Pod spec。

---

## 5. 云层 — 统一策略大脑

### 5.1 职责

完全复用 VirbiusLLM 的 virbius-engine + virbius-control，覆盖 Agent 专属场景。

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
  |
  +-- 运行时状态（Redis，非数据库）
      +-- session:{id}:tool_history
      +-- session:{id}:risk_score
      +-- session:{id}:tool_count:*
      +-- pid_trace:{pid}
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
    +-- P2: sandbox_type=subprocess -> seccomp-notify + Landlock
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
| seccomp-notify deny | 子进程收到 -EPERM，工具返回 Error |
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
|  +-- P2: seccomp-notify + Landlock 沙箱                   |
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
| 端 | seccomp-bpf | syscall 过滤(P2) | 极稳定(内核 3.5, 2012) | 无 |
| 端 | seccomp-notify | 同步 userspace 决策(P2) | 稳定(内核 5.0, 2019) | strict mode 降级 |
| 端 | Landlock | 文件/网络路径限制(P2) | 较新(文件 5.13/2021, 网络 6.7/2024) | AppArmor |
| 端 | gVisor | 不可信代码沙箱(P2) | 稳定(Google, GKE 使用) | Kata Containers |
| 端 | PyO3 / napi-rs | Rust<->Python/Node 绑定 | 稳定(广泛使用) | subprocess |
| 管 | OpenResty + LuaJIT | 反向代理 + 安全插件 | 稳定(10年+生产) | APISIX / Envoy |
| 核 | eBPF + BTF/CO-RE | 内核观测 | 极稳定(行业标准) | 无 |
| 核 | Falco | 观测引擎(CNCF 毕业) | 极稳定(CNCF Graduated) | Tracee |
| 核 | Tetragon | eBPF enforcement(P2) | 较新(Isovalent/Cisco) | Falco + seccomp |
| 云 | Groovy | L3 规则脚本 | 稳定但 declining(Apache) | Python sandbox |
| 云 | Redis | session + 审计流 | 极稳定 | KeyDB |
| 云 | Spring Boot | engine/control 框架 | 极稳定 | Quarkus |
| 云 | qwen3guard:0.6B | STI Taint 小模型(P1) | 较新 | 任意 guard 模型 |
| 协议 | MCP | 工具调用协议 | 较新(Anthropic, 2024) | 自定义 JSON-RPC |

### 9.2 风险评估

**Tier 1 极稳定(无风险)**：seccomp-bpf, eBPF, Redis, Nginx, Spring Boot, K8s

**Tier 2 稳定(需关注)**：

| 技术 | 风险 | 缓解 |
|------|------|------|
| seccomp-notify | TOCTOU; supervisor 崩溃=进程挂起 | ioctl 校验; watchdog + 降级 KILL |
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
- gVisor -> seccomp subprocess 降级
- Tetragon -> Falco + seccomp-notify 替代
- qwen3guard -> 任意 guard 模型

---

## 10. 路线图

### P0 — 观测(eyes) + HTTP 层 enforcement

| 任务 | 组件 | 估计 |
|------|------|------|
| 端层预检(参数校验 + allowlist + JSON Schema) | virbius-core | 2w |
| 端层 MCP Server 集成(PyO3 / napi-rs / subprocess) | virbius-core | 3w |
| 管层 OpenResty Lua 插件(allowlist + 计数 + engine 调用) | virbius-gateway | 3w |
| 管层 Nginx upstream 自动生成(control -> compiler) | control + compiler | 2w |
| 云层 Redis session 状态(history + risk + count) | engine | 3w |
| 云层 Groovy L3 Agent 规则(工具链检测 + 场景匹配) | engine | 2w |
| 云层 Groovy ctx 扩展(sessionHistory / riskScore，内存预加载) | engine | 2w |
| 控制面 Agent 规则 CRUD + 发布 | control | 2w |
| 端层快速通道(低风险工具跳过云层) | core + gateway | 2w |
| 核层 Falco 部署 + eBPF 驱动(标准节点池) | virbius-kernel | 2w |
| 核层 Falco plugin 模式(serverless 降级: k8saudit + filetail) | virbius-kernel | 2w |
| 核层 Tetragon 检测 + 降级逻辑(detect_mode) | virbius-kernel | 1w |
| 核层 PID->trace_id 映射 + 审计上报 | virbius-kernel | 1w |
| 核层自定义 virbius-audit Falco 插件(消费 Redis Stream) | virbius-kernel | 2w |
| 审计大盘(session risk + 工具调用 + 告警) | control | 2w |
| 端到端集成测试 | 全组件 | 3w |
| **P0 合计** | | **~34w** |

### P1 — 增强观测

| 任务 | 说明 |
|------|------|
| STI 语义审计(Taint 维度调小模型) | 工具返回值注入检测 |
| 输出 PII 脱敏(端层，工具返回前) | 复用 virbius-core dlp/engine.rs |
| Falco 规则库扩充(Agent 专用规则集) | 工具调用模式、SSRF 特征、数据外泄 |
| 高风险工具人工审批流 | engine -> 审批 UI -> 超时 deny |
| session risk 自适应模型 | 从规则阈值升级为加权累积 |
| 审计完整性(hash chain) | 防篡改 |

### P2 — 阻断(hands)

| 任务 | 说明 |
|------|------|
| seccomp-notify supervisor | 同步 userspace 决策(connect/open/execve) |
| Landlock 文件路径限制 | 工具进程文件访问白名单 |
| gVisor 预热池 | 不可信代码执行沙箱 |
| Tetragon enforcer(eBPF 可用时) | 宿主级 enforcement 叠加 |
| TOCTOU 防护 + supervisor 高可用 | seccomp-notify 生产化 |
| eBPF 自定义观测程序(execveat + IPv6) | 补充 Falco 内置规则 |
| 端到端红队测试 | 安全验证 |

---

## 变更日志

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-04 | 初始设计：端管核云四层架构 |
| v1.1 | 2026-07-05 | 新增预检/执行两阶段、快速通道 |
| v2.0 | 2026-07-06 | 重大修订：1) 管层改为 OpenResty+Lua(删除 gateway-agent+AgentGateway) 2) 核层改为 Falco 观测引擎(眼睛/手分离) 3) 新增 Tetragon 检测+Falco 降级链 4) 新增 Falco plugin 模式(serverless 降级) 5) 删除 sidecar 部署模式 6) P0 只实现观测，seccomp-notify/Landlock/gVisor 推迟至 P2 7) 修正 posix_spawn/ seccomp 白名单/Groovy 逻辑 bug/eBPF IPv6 等技术问题 8) 新增 §9 第三方技术栈依赖与稳定性 |
