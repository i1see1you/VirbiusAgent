# VirbiusAgent 路线图 — ROADMAP

| 项目 | 说明 |
|------|------|
| 文档版本 | v3.2 |
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

| 任务 | 说明 |
|------|------|
| 端层快速通道（低风险工具跳过云层） | 延迟优化 |
| 自定义 virbius-audit Falco 插件 | 消费 Redis Stream，Agent 专用规则 |
| 审计大盘 | session risk + 工具调用 + 告警可视化 |
| STI 语义审计（Taint 维度调小模型） | 工具返回值注入检测 |
| Prompt 入侵检测（prompt runtime 重新定位） | 用户输入越狱/注入检测，与 STI Taint 共享 qwen3guard 模型 |
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
| P1 | STI Taint + Prompt 入侵检测 + virbius-audit 插件 + 审计完整性 | 人工审批 + 自适应 risk + 记忆管控 | 记忆管控 + prompt 越狱检测 |
| P2 | Tetragon observe | Landlock + gVisor + Tetragon enforcer + TEE | syscall 级阻断 + 硬件安全 |

---

## 变更日志

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-07-04 | 初始设计：端管核云四层架构 |
| v1.1 | 2026-07-05 | 新增预检/执行两阶段、快速通道 |
| v2.0 | 2026-07-06 | 重大修订：1) 管层改为 Higress+WASM(删除 gateway-agent+AgentGateway) 2) 核层改为 Falco 观测引擎(眼睛/手分离) 3) 新增 Tetragon 检测+Falco 降级链 4) 新增 Falco plugin 模式(serverless 降级) 5) 删除 sidecar 部署模式 6) P0 只实现观测，seccomp-notify/Landlock/gVisor 推迟至 P2 7) 修正 posix_spawn/ seccomp 白名单/Groovy 逻辑 bug/eBPF IPv6 等技术问题 8) 新增 §9 第三方技术栈依赖与稳定性 |
| v2.1 | 2026-07-06 | 新增 §1.4 身份标识体系：app_id 即 agent_id，不区分类型与实例；新增 Agent 运行许可证(Runtime License)机制 |
| v2.2 | 2026-07-06 | 新增 §2.8 Prompt Gateway（提示增强）：宪法约束注入 + 动态上下文注入 + 工具描述增强 + PII 输入脱敏 |
| v2.3 | 2026-07-06 | P2 subprocess 沙箱简化：seccomp-notify + Landlock 改为 Landlock + drop caps。删除 seccomp-notify supervisor（消除 TOCTOU/SPOF 风险），SSRF 防护由 HTTP 层 URL 校验 + NetworkPolicy 承担 |
| v2.4 | 2026-07-06 | 路线图修订：1) P0 新增 Runtime License + Prompt Gateway + 宪法 v1 2) P0 快速通道/Falco 插件/审计大盘移至 P1 3) P1 新增记忆管控 4) P2 新增 TEE 硬件安全根 5) P2 合并重复任务 6) macOS 降级说明改为不做沙箱 |
| v2.5 | 2026-07-06 | 新增 §9.4 与 VirbiusLLM 的关系：文件级代码参考策略，35 个文件可参考 VirbiusLLM 实现 |
| v2.6 | 2026-07-06 | 全面修正：1) access.lua 移出直接复用表（已在需扩展表）2) §9.4 独立为 §10，路线图重编号为 §11 3) 复用计数修正(25+7+13) 4) 补充 License 会话中过期处理 5) 补充 isInternalHost() 定义 6) 修正 Tetragon 降级引用 7) 项目结构图补充缺失文件 8) §1.4 增加对 VirbiusLLM 关系的前向引用 9) §2.2 沙箱流程改按隔离级别排序 |
| v2.7 | 2026-07-06 | 路线图修订：Tetragon 检测 + detect_mode 从 P0 移至 P2（Tetragon 是阻断层能力，P0 只做观测） |
| v2.8 | 2026-07-07 | 新增 §2.8.7 Prompt 入侵检测：将 VirbiusLLM 的 prompt runtime 重新定位为用户输入越狱/注入检测层（非 LLM 内容审核）。与 Prompt Gateway（预防）互补形成"预防 + 检测"prompt 纵深；与 STI Taint（工具返回值检测）分工覆盖 prompt 注入两个入口；更新 §2.8 纵深防御表 |
| v2.9 | 2026-07-07 | 1) 新增 §1.5 三层安全架构：身份管控层 + 运行时防护层（意图研判/提示增强/记忆管控/工具拦截/输出审查）+ 基础设施层，含与端管核云映射关系 2) 新增 §2.9 记忆管控（Memory Interceptor）：拦截点 + 数据模型 + 脱敏策略 + 注入检测 + session risk 联动 3) 新增 §2.10 输出审查（Output Review）：PII 泄露 + 凭据泄露 + 内容安全 + 策略合规四维审查 4) 新增 §2.6.1 MCP Proxy 完整技术方案：架构 + 协议处理 + 拦截流程 + 会话管理 + 配置 + 部署模式 + 实现结构 + 核心代码 5) 修复 MCP Proxy 无 License 绕过漏洞：audit-only 模式改为 Fallback 默认策略（minimum_privilege/default_deny/audit_only），默认 minimum_privilege（高风险工具阻断 + DLP/schema 仍生效），audit_only 需显式配置且禁止生产使用 |
| v3.0 | 2026-07-07 | 文档拆分：原 DESIGN.md（2660 行）拆分为 5 个文件——ARCHITECTURE.md（§1-§5，四层架构核心设计）、PROTOCOL.md（§2.6 MCP Proxy 完整技术方案）、DEPLOYMENT.md（§8 部署视图）、ROADMAP.md（§11 路线图 + 变更日志）、DESIGN.md（索引 + §6 跨层数据流 + §7 策略一致性 + §9 第三方依赖 + §10 与 VirbiusLLM 关系）。各文件保留原始章节编号，跨文件引用已修复为 Markdown 链接 |
| v3.1 | 2026-07-07 | Egress 管控策略修订：工具级管控而非进程级断网。原方案"Agent 不具备直接网络出站能力，所有 HTTP 由 Proxy 代发"改为三分法——1) MCP 业务工具请求（curl/web_search 等）走 Proxy 代发 + URL 白名单 2) Agent 框架隐式请求（配置拉取/模型下载/心跳等）由 Agent 自身发起，受 K8s NetworkPolicy 限制到白名单目标 3) P2 进程级全量出站由 eBPF/iptables 透明劫持兜底。同步更新 §1.1、§2.6.1、§3.5，新增 NetworkPolicy 配置示例和 Proxy 代发 HTTP 能力边界表（P0/P1 分级） |
| v3.2 | 2026-07-07 | 管层技术栈迁移：OpenResty+Lua → Higress+WASM。1) 安全插件从 Lua cosocket 改为 WASM 异步回调（Go, proxy-wasm-go-sdk）2) 路由配置从 Nginx upstream+location 改为 Higress CRD（McpBridge/McpServer/WasmPlugin）3) 热更新从 nginx -s reload 改为 CRD 更新触发 xDS 热加载（连接无损，SSE 不中断）4) 协议路由新增 Higress MCP Gateway 原生支持（Streamable HTTP/SSE）5) virbius-gateway 项目结构从 plugins/openresty/ 改为 wasm/ 6) virbius-compiler 输出从 Nginx config 改为 Higress CRD YAML 7) §9 依赖表更新（LuaJIT → Envoy/WASM） |
