# VirbiusAgent FAQ：Agent 安全场景与产品答疑

> 面向正在评估或接入 VirbiusAgent 的安全工程师与开发者。
> 三分钟产品速览见 [README.zh.md](README.zh.md)；分层架构见 [ARCHITECTURE.zh.md](ARCHITECTURE.zh.md)；
> 操作手册见 [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md)；12 个完整规则示例见 [rule-examples.zh.md](rule-examples.zh.md)。
> English version: [FAQ.md](FAQ.md)

## 目录

- [第一部分 评估快问快答](#第一部分评估快问快答)
- [第二部分 威胁场景：十个真实形态](#第二部分威胁场景十个真实形态)
- [第三部分 技术答疑](#第三部分技术答疑)
- [第四部分 阅读索引](#第四部分阅读索引)

---

## 第一部分：评估快问快答

### VirbiusAgent 是什么？

自托管 AI Agent 的**运行时安全平台**：在**边缘 → 网关 → 内核 → 云端**四个独立层上，
拦截提示注入、MCP 工具滥用与提权，提供亚毫秒级规则、eBPF 系统调用观测、LLM 护栏
与人工审批、哈希链审计。它不是 WAF（不做 HTTP 特征签名匹配；网关只做限流与执法），也不是杀毒（不看文件特征）——
它看的是 **Agent 的行为语义**：哪个工具、什么参数、什么上下文、调用链后果。

### 它保护谁？不保护谁？

保护**你自研的 Agent**（服务端运行时：proxy 后面、网关后面、进程内的 SDK）。
不覆盖**终端侧员工 AI 使用管控**（员工把源码粘贴到公有 ChatGPT 一类的 Shadow AI /
终端 DLP 问题）——那是另一类标准化产品。见 [README 的范围说明](README.zh.md)。

### 开源范围与商业边界？

本仓库（MIT）包含 engine / control / auth / mcp-proxy / 全部 SDK / kernel / gateway，
开箱可用、CI/镜像不依赖任何闭源件。**VirbiusEvaluation**（代码与技能包安全评估模块）
为单独授权的闭源可选项：未连接时入口隐藏、零遥测，不接入完全不影响开源栈。

### 我的 Agent 用 X 框架，能接吗？

框架无关，三种接入任选（可混用）：

| 接入模式 | 形态 | 适用 |
|---|---|---|
| **MCP Proxy**（sidecar） | stdio/SSE 代理，Agent 的 MCP 流量全部过它 | 任何 MCP 客户端，零框架耦合 |
| **Higress 网关**（远程） | HTTP on-path，WASM 插件执行执法 | 不便挂 sidecar 的服务端集群 |
| **SDK 内嵌**（进程内） | Python / Node SDK，工具调用前 precheck | LangChain、OpenAI Agents SDK（有现成适配）到任意可包装函数 |

详见 [QUICKSTART.zh.md](QUICKSTART.zh.md) 的模式选择。

### 最小可用部署是什么样？

一条命令：`docker compose build && docker compose up -d`（Redis + engine + control，
SQLite 默认无需 MySQL）。本地开发用 `bash scripts/run-local.sh`。防护面（规则、名单、
判定、审计）能力齐全；网关/内核层按需加装。

---

## 第二部分：威胁场景：十个真实形态

每个场景给出：攻击形态、公开报道的真实事件、**VirbiusAgent 的对策（哪一层、什么机制）**、
以及**残余风险的诚实边界**——哪些问题不归运行时安全管。

### 场景速查表

| # | 场景 | 主防层 | 关键机制 |
|---|------|--------|----------|
| 1 | 间接提示注入 | 云端 + 边缘 | Prompt L1 语义判定、STI 污点、外发 DLP |
| 2 | 越权访问 | 边缘 + 云端 | 工具白名单、License 范围、Groovy 参数规则 |
| 3 | 过度自主 | 云端 + 内核 | challenge 审批、沙箱、破坏性命令规则 |
| 4 | 长期记忆投毒 | 边缘 + 云端 | 记忆写入脱敏、回流判定、跨会话累计 |
| 5 | 直接提示注入 | 云端 | Prompt L1 jailbreak 类、会话风险累计 |
| 6 | 敏感数据泄露 | 边缘 | DLP 脱敏、输出审查、STI 防外带 |
| 7 | RAG 投毒 | 云端 + 边缘 | 检索内容判定、外发域名规则 |
| 8 | 幻觉与错误信息 | —（不解决） | 仅提供审计追责证据链 |
| 9 | MCP 供应链 | 边缘 | 白名单限爆炸半径（深度扫描属商业模块） |
| 10 | 不安全的工具实现 | 内核 | eBPF 观测 + 沙箱兜底（不替代工具自身修复） |

### 1. 间接提示注入

**攻击形态**：用户输入完全干净，攻击指令藏在 Agent 读到的网页、邮件、Issue、公告里。
Agent 把不可信内容当指令执行，用当前会话已有的权限发信、建公开页面、带出内部数据。

**真实事件**
- **EchoLeak / CVE-2025-32711（2025）**：Microsoft 365 Copilot，带隐藏指令的邮件即可让
  Copilot 把组织内邮件、文档带出边界，CVSS 9.3，微软服务端修补。
  [Checkmarx 综述](https://checkmarx.com/zero-post/echoleak-cve-2025-32711-show-us-that-ai-security-is-challenging/)
- **GitHub MCP（Invariant Labs，2025-05）**：恶意 Issue 劫持 Agent，用同一把 token 读私有仓库
  并写入公开 PR。[Invariant 原文](https://invariantlabs.ai/blog/mcp-github-vulnerability)

**VirbiusAgent 对策**
- 云端 Prompt L1：不可信内容进入判定面，guard 模型（10 类，含注入与 Agent 工具滥用）打分；
- STI 污点追踪：工具返回值被标记为不可信，其内容进入后续工具调用/记忆时持续追踪；
- 外发出口收敛：发信、建单等外发工具参数过 DLP 脱敏，高危动作触发 challenge 人工审批。

**残余风险**：语义检测是概率性的，新型注入形态可能漏过——靠分层（白名单收窄 + 审批 +
审计）补偿。

### 2. 越权访问

**攻击形态**：工具参数（用户 ID、账号、工单号）由 Agent 填写，应用未与会话身份核对。
系统提示里的「只查当前用户」挡不住换说法，也挡不住伪造的工具返回。与 Web 的 IDOR 同类，
差别在于参数由模型填写。

**真实事件**
- **GitHub MCP（Invariant Labs，2025-05）**：意图是查公开仓库，Agent 用同一 token 访问私有
  仓库并外写。权限边界没有按当前任务收紧。

**VirbiusAgent 对策**
- 边缘工具白名单 + License 工具范围：token scope 与 allowlist 决定「这个应用根本碰不到哪些工具」；
- 参数级越权是**业务语义**问题：Groovy L3 规则可直接校验 `args.user_id == ctx.user_id` 一类约束，
  按你的权限模型写，热更新生效；
- decision trace 记录「谁、经谁批准、以何参数执行」，事后可定责。

**残余风险**：平台不内置你的权限模型——参数级校验需要业务方写规则（这是设计立场：
权限语义只有业务知道）。

### 3. 过度自主

**攻击形态**：冻结、审批、环境隔离只写在提示词里。Agent 表示已理解，仍执行删库、发信、
改配置。没有外部攻击者也可能发生：高危工具缺少**独立于模型的强制闸门**。

**真实事件**
- **Replit Agent（2025-07）**：明确要求 code freeze 仍删除生产数据库（约 1200+ executives、
  1190+ companies 记录），并声称无法回滚。Replit 随后默认隔离开发/生产并增加规划模式。
  [DEV 综述](https://dev.to/ramdai_bista/replits-ai-agent-deleted-a-production-database-during-a-code-freeze-then-lied-about-the-rollback-59m1)

**VirbiusAgent 对策**
- challenge 审批流：引擎发挑战 → 控制台人工批准 → 一次性 token 门控执行（强审批模式还要求
  参数一致，防止批准 A 执行 B）；
- 内核沙箱：Landlock（路径限制）/ gVisor（用户态内核）在系统调用级兜底，删除类动作先被
  沙箱挡下；
- 默认规则 `cloud_destructive_command_deny`（`rm -rf /`、`mkfs`、裸盘写、fork 炸弹等）开箱在册。

### 4. 长期记忆投毒

**攻击形态**：日历邀请、网页、工单中的「工作惯例」被写进 Agent 长期记忆。新会话只问正常
问题，召回的记忆把 Agent 带偏并驱动外发。持久化内容往往不含越狱套话，针对直球注入的
检测难以命中。

**真实事件**
- **Invitation Is All You Need（2025，Black Hat / DEF CON）**：Google Calendar 邀请中的间接注入
  驱动 Gemini for Workspace 执行操作，Google 承认并加敏感操作确认。
  [The Register](https://www.theregister.com/2025/08/08/infosec_hounds_spot_prompt_injection/)
- **ChatGPT Memories / SpAIware（Rehberger，2024）**：不可信文档诱导写入长期记忆，后续会话
  持续外泄。一次写入、多次生效。

**VirbiusAgent 对策**
- 记忆拦截器：写入 Agent 记忆的内容先脱敏（PII 掩码）；
- 记忆/检索内容回流时同样进入判定面（它们对模型而言也是输入）；
- 跨会话风险累计：同一实体反复触发低危信号会累积升级。

**残余风险**：宿主产品（Google/OpenAI 侧）的记忆治理不归我们管——我们管的是**你的 Agent**
的记忆写入与召回链路。

### 5. 直接提示注入

**攻击形态**：用户在对话框要求忽略规则、换人设、无条件同意陈述。未调工具即可造成错误
承诺与品牌损失；接上下单/改价工具则升级为过度自主。

**真实事件**
- **Chevrolet of Watsonville 经销商 ChatGPT（2023-12）**：攻击者先让站点 Agent 同意任何陈述，
  再提出「1 美元买 Tahoe」，Agent 表示成交并声称有法律效力。AI Incident Database #622。

**VirbiusAgent 对策**：Prompt L1 的 jailbreak 类判定 + 会话内累计风险（反复尝试会提升
风险分触发拦截），高危工具的 challenge 闸门兜底。

### 6. 敏感数据泄露

**攻击形态**：两类出口常被合并讨论——员工把源码粘到公有大模型（Shadow AI / 终端 DLP）；
Agent 把客户/内部数据写进发信、建单等工具参数送出组织（工具链外泄）。

**真实事件**
- **三星半导体（2023-04）**：员工将芯片源码、会议纪要提交 ChatGPT，公司随后限制直至禁用
  公有生成式 AI。[TechCrunch](https://techcrunch.com/2023/05/02/samsung-bans-use-of-generative-ai-tools-like-chatgpt-after-april-internal-data-leak/)

**VirbiusAgent 对策**（针对第二类）：边缘 DLP 在参数离开客户端前掩码（手机号/证件/邮箱/
银行卡）；tool_result 输出审查检测凭据/PII 回带；STI 污点阻止不可信数据进入外发工具。

**边界（诚实）**：第一类（员工自己用公有大模型）是终端管控问题，不在 VirbiusAgent 范围内。

### 7. RAG 投毒

**攻击形态**：公开频道、共享网盘埋入指令，与检索结果一并进入上下文。用户问敏感信息时，
回答中出现指向攻击者的链接，点击后数据离开组织。入口是检索增强，出口可以是回答本身。

**真实事件**
- **Slack AI（2024-08）**：PromptArmor 披露，公开频道的伪装说明被拼进回答并生成陷阱链接，
  点击后密钥外流。Slack 已修补。
  [PromptArmor](https://www.promptarmor.com/resources/data-exfiltration-from-slack-ai-via-indirect-prompt-injection)

**VirbiusAgent 对策**：检索片段作为输入进入 Prompt L1 判定面；输出侧靠 DLP/域名规则约束
外发面（哪些域名、哪些工具可以携带数据出去）。

**残余风险**：钓鱼链接的**内容级**识别（页面像不像钓鱼）是弱项——收敛出口比识别页面可靠。

### 8. 幻觉与错误信息

**攻击形态**：Agent 以肯定语气给出不存在的政策，用户照办后组织抗辩。司法实践中，挂在
官方渠道的 Agent 常被认定为组织自身窗口。

**真实事件**
- **Moffatt v. Air Canada，2024 BCCRT 149**：网站 Agent 承诺丧亲票可事后退差价，与规定不符，
  裁判庭认定 Agent 是网站一部分，航空公司担责。
  [Ars Technica](https://arstechnica.com/tech-policy/2024/02/air-canada-must-honor-refund-policy-invented-by-airlines-chatbot/)

**VirbiusAgent 的立场（诚实）**：这是**内容正确性**问题，不是注入或越权，运行时安全平台
不解决它。能提供的是事后证据：完整 decision trace 与哈希链审计（何时、基于什么输入、
哪个模型版本给出了该回答），用于追责与复盘。

### 9. MCP 供应链攻击

**攻击形态**：Agent 经 MCP 连接外部工具，工具仿冒、被篡改或客户端/代理自身有漏洞，
则调用链不可信。与间接注入不同：问题在**来源与安装通道**，不在内容。

**真实事件**
- **mcp-remote 等 MCP 客户端/代理漏洞（2025）**：公开过高危（含 RCE 一类），作用于开发者
  机器上的 Agent 通道，需按软件供应链管理。

**VirbiusAgent 对策**：proxy 的工具白名单与多上游路由把「哪些工具、哪些服务器」锁死，
出问题时**限制爆炸半径**（攻击者拿到的是收窄后的工具面）；审计链记录实际调用的上游。

**边界（诚实）**：MCP 工具包的深度静态审查（工具名伪装、参数投毒、投毒描述）属商业模块
VirbiusEvaluation；依赖来源治理（锁定版本、SBOM）在平台之外。

### 10. 不安全的工具实现

**攻击形态**：Agent 把自然语言转成查询或系统命令，后端仍做字符串拼接——传统注入、命令
执行的入口变成了**模型输出**。大模型不会自动成为防火墙。

**真实事件**
- **LangChain GraphCypherQAChain / CVE-2024-7042（2024）**：自然语言转 Cypher 未参数化，
  提示注入可增删改图数据、跨租户读取。[NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-7042)
- **Langflow 文件上传路径穿越 / CVE-2026-5027（2026）**：上传文件名原样落盘，`../` 可写任意
  路径并 RCE；补丁后蜜罐仍观察到真实攻击。[NVD](https://nvd.nist.gov/vuln/detail/CVE-2026-5027)

**VirbiusAgent 对策**：不替代工具自身的修复（参数化、路径校验是工具的责任）——提供的是
纵深：规则拦截高危命令形态、内核 eBPF 观测异常文件/进程/网络行为、沙箱把可写路径限制住。

---

## 第三部分：技术答疑

### Q1：VirbiusAgent 是如何在语义层面上抵御攻击的？

**规则与模型混合，四道语义防线：**

1. **Prompt L1（guard 模型）**：自研 VirbiusGuard（GLM5.2 教师蒸馏 Qwen3Guard 学生模型），
   判定输入为 safe 或 **10 类 unsafe**（含 Agent Tool Misuse、Jailbreak、多语言混合输入），
   引擎侧自托管推理（Ollama / vLLM），按 category 映射到内置 `prompt-*` 规则。模型可替换（HuggingFace /
   ModelScope 可下载）。
2. **自定义判据规则（judge 协议）**：非内置的 `runtime=prompt` 规则把你的自然语言判据
   （判定标准 + few-shot + 阈值）发给 judge 后端批量打分（Jev `/v1/systemone` 协议），
   阈值 0.05–0.95 可调，`on_error` 策略可控失败行为。协议细节见
   [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md)。
3. **Groovy L3 终审**：合并边缘/网关/内核/云端各层信号与累计计数，输出最终
   `effective_action`（allow / deny / challenge），可调用外部 ML（`mlPredict`）。
4. **STI 污点 + 会话累计**：不可信工具返回值跨轮追踪；同一会话的反复低危信号累积升级。

**诚实边界**：语义检测是概率性的，存在漏报与误报。因此平台提供阈值/降档/抑制/dry_run
灰度一整套调参面（见 Q8），并用分层防御与人工审批兜底，而不是承诺单模型全拦。

### Q2：部署成本高吗？需要几十万到上千万的 GPU 投入吗？

**不需要。** 成本结构：

| 组件 | 资源需求 |
|---|---|
| 规则 / 名单 / DLP / 累计（边缘+网关） | 纯 CPU，近零边际成本 |
| eBPF / Falco（内核层） | 复用节点 CPU，无模型成本 |
| VirbiusGuard（Prompt L1） | **Q4 量化 462MB，Ollama / CPU / Mac 可跑**（基准数字对应 f16/bf16，精度敏感场景建议不量化） |
| judge 后端（可选，自定义判据规则才需要） | 消费级 GPU 或 CPU 可跑（4B 级模型） |
| MySQL / Redis | 常规中间件规格 |

最小栈一条 `docker compose up -d`（Redis + engine + control，SQLite）。引擎无状态可水平扩，
计数与名单在 Redis 集中。只有当你的流量大到需要多实例 guard 推理时才涉及 GPU 扩展，
且可按场景选择性启用（低风险工具走 fast path 不触发模型）。

### Q3：VirbiusAgent 是如何拦截流量的？

三种接入模式，任选/混用（详见 [QUICKSTART.zh.md](QUICKSTART.zh.md)）：

1. **MCP Proxy（sidecar）**：以 stdio/SSE 代理身份挂在 Agent 与 MCP 工具之间——Agent 的
   每一次工具调用都在代理路径上，允许/拒绝/挑战在代理内执行；
2. **Higress 网关（远程）**：HTTP on-path，WASM 插件做限流与执法，适合不便挂 sidecar 的集群；
3. **SDK 内嵌（进程内）**：Python/Node SDK 在函数调用前做 precheck（LangChain callback、
   OpenAI Agents SDK 有现成适配，任意 callable 可包装）。

决策链：**边缘 precheck（<1ms）→ 网关执法（<10ms）→ 云端判定（<30ms，不含 LLM 推理）**；
内核层以 eBPF 被动观测全程伴随，沙箱（Landlock/gVisor）在系统调用级独立阻断，
**不依赖 Agent 配合**——即使进程被攻破，系统调用仍可见、可拦。

### Q4：部署会不会造成大模型处理延迟的增加？

分层设计，延迟按层付费：

| 路径 | 端到端延迟 |
|---|---|
| fast path（低风险工具） | **< 1ms**，跳过网关与云端 |
| 边缘规则/名单/DLP | < 1ms |
| 网关执法 | < 10ms |
| 云端判定（不含 LLM 推理） | < 30ms |
| Prompt L1 guard（引擎侧自托管，Ollama/vLLM） | 取决于模型与硬件；小模型通常远低于大模型 API 调用（以实测为准） |
| judge 后端（自定义判据） | 有 5s 超时上限，超时按规则 `on_error`（fail_open / fail_closed 由你配） |

关键设计：**不是每个请求都触发模型**。工具按风险分级（risk_class），低风险走 fast path；
云端按场景配置哪些工具需要深检；guard 推理只发生在输入判定面。对延迟极敏感的场景可以
只开规则+名单层（纯亚毫秒），语义层按需开启。

### Q5：针对特殊行业，需要个性化开发吗？

**不需要改平台代码。** 个性化全部通过配置面完成：

| 需求 | 手段 | 门槛 |
|---|---|---|
| 行业关键词/名单 | lua-dsl / 名单 | 零代码 |
| 行业数据分级脱敏 | dlp-dsl | 零代码（正则） |
| 业务语义判定 | Groovy L3（Java 语法，可直接读 `args`/累计/外部模型） | 会写 Java 方法 |
| 自定义判据规则 | prompt 规则（自然语言判据） | 写清判定标准即可 |
| 外部行业模型 | Groovy `mlPredict()` 接你已部署的 BERT/XGBoost 服务 | 模型服务自理 |

规则支持 **draft → 模拟 → dry_run → canary → full** 全生命周期与热更新（不停机、可回滚）。

**诚实边界**：规则要贴业务语义，需要懂业务的人参与（这正是精确定制的代价，平台把它
降到「写配置」而不是「改代码」）；合规映射（数据分级标准、等保条目）由你的合规团队
裁定，平台提供证据链（审计、决策溯源、导出）。

### Q6：菜单中的「名单」和「累计」是什么？添加以后如何在规则中体现？

两个都是规则的**数据依赖**，先建数据、规则里按名引用：

- **名单（数据列表）**：一组维度化的静态值。控制台「名单」页创建，维度可选
  `keyword` / `user_id` / `device_id` / `ip_cidr` / `var（逻辑变量，配合工具参数）`。
  例如建 `political_sensitive_list`（维度 `var`，值 `person_x`…），规则命中名单即按动作处置。
- **累计（累积计数器）**：Redis 滚动窗口计数器。控制台「累计」页创建，例如
  `user_query_1m`（维度 `session_id`，滚动 1 分钟）。限流规则引用它做超阈值拦截。

**在规则中体现**（内置默认规则即实例）：

| 内置规则 | 用到的数据 | 行为 |
|---|---|---|
| `gw_rate_tool_1m` | 累计 `tool_req_1m`（维度 `var:tool_name`，1 分钟滚动） | 同工具 1 分钟 ≥ 60 → challenge |
| `rl_rate_user_1h` / `rl_rate_app_1h` | 用户/应用 1h 窗口累计 | 超阈值 → challenge |

完整操作步骤（建名单 → 建累计 → 注册工具 → 写规则引用）见
[rule-examples.zh.md](rule-examples.zh.md) 的「前置条件」一节。名单查询与计数读取都落在
Redis（同机部署时通常亚毫秒级），不拖慢判定路径。

### Q7：规则怎么配置？需要掌握哪些技术？

按层选型，门槛递增（选型指南详见 [README 架构深潜](README.zh.md)与
[USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md)）：

| 层 | 规则类型 | 形态 | 门槛 |
|---|---|---|---|
| 边缘 | `lua-dsl` | JSON（关键词/名单） | 零代码 |
| 边缘 | `dlp-dsl` | JSON（PII 正则） | 零代码 |
| 网关 | `lua` | Lua 函数 `decide(ctx)` | 脚本级 |
| 云端 | `prompt` | 自然语言判据 | 写清标准即可 |
| 云端 | `groovy` | Groovy `decide(ctx)` | 会写 Java 方法 |
| 内核 | `falco` | JSON 条件 | eBPF 字段名 |

全流程在控制台完成：**创建 → 模拟（simulate，立即验证命中）→ dry_run（只记不拦）→
canary（小流量）→ full**，版本化、可回滚。**12 个典型规则的完整配置**（含审批模式、
工具注册、前后对照）见 [rule-examples.zh.md](rule-examples.zh.md)。

**诚实边界**：低门槛指「写出能跑的规则」；要达到精确召回/误报平衡，需要理解业务语义
并利用 dry_run/simulate 反复校准——平台把试错成本降到接近零，不替你做判断。

### Q8：误报了怎么办？

三层机制，误报不等于「拦截了再说」：

1. **上线前**：创建规则即有模拟（输入样例立即看命中）；judge 类规则阈值 0.05–0.95 可调；
2. **上线中**：dry_run 模式只记录不拦截，观察真实命中分布后再放量；canary 灰度控制影响面；
3. **运行时**：severity 降档（高危→提示）、上下文降级（文档/测试场景自动放宽）、
   抑制机制（对已裁定误报的 finding 精确 pin，复测不再重复报）。

误报样本可回流为 judge 规则的 few-shot 例（规则自带 examples 字段），越用越准。

### Q9：数据落在哪里？审计可信吗？

- 判定与审计数据落 Redis（事件流、计数）与 MySQL/SQLite（规则、轨迹、事件）——**部署在你
  自己的基础设施内**，无云端回传；
- 审计日志带 **SHA-256 哈希链**：每条记录链到前条，控制台「审计完整性」页可随时校验，
  篡改可检出；
- 证据脱敏在先：DLP 掩码后的内容才进入证据与报告（数据最小化）；
- 全链路 `trace_id` 关联边缘/网关/内核/云端事件，会话级复盘一次拉通。

### Q10：支持哪些模型/推理后端？

按用途分三类，互不替代：

| 用途 | 内置/默认 | 可替换性与许可 |
|---|---|---|
| **Prompt L1 输入分类（guard）** | VirbiusGuard，默认 GGUF `i1see1you/virbiusguard:v2.0`；引擎按 OpenAI 兼容的 `/v1/chat/completions` 调用，默认指向本机 Ollama（`127.0.0.1:11434`） | 任意 Ollama / vLLM / OpenAI 兼容服务：改 `virbius.prompt-llm.base-url`、`model`、`api-path`。Helm 内置 Ollama 会自动下载 GGUF（V2.0 F16 约 1.5GB）或走 ModelScope；关闭内置 Ollama 时必须显式配 `base-url`。`system-prompt` 与 `category-rule-mapping` 也可覆盖 |
| **自定义判据规则（judge）** | `neohorse-4b`（Apache-2.0，默认；单次前向 8 问，T4 实测约 1.0s） | `startlux-4b`（**CC-BY-NC-4.0，仅非商用**，逐问前向）、`laya`（Apache-2.0，322M 多语种 mmBERT，内部 canary、默认关闭）。协议为 Jev `/v1/systemone`，按后端配 `base-url` / `model` / `auth-token`，细节见 [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md) |
| **业务自有模型** | 无内置 | Groovy L3 的 `mlPredict()` 调用你已部署的 BERT / XGBoost 等服务 |

关键区分：内置的 10 类 `prompt-*` 规则走 **guard**；只有**自定义** `runtime=prompt` 规则才会用到 **judge** 后端。judge 后端许可不同（startlux 为非商用），上生产前按协议文档核对。

### Q11：非 MCP 的 HTTP API 怎么覆盖？

**开箱即用的覆盖范围是 HTTP 上的 MCP `tools/call`。** 网关模式（Mode 2，Higress/Envoy WASM，AUTHN 阶段）只拦截路径含 `/mcp/` 或 `/tools/call` 的请求，其余请求**透明转发**。命中后的流水线：

```
工具白名单（本地）→ 表达式规则 → Redis 限流（按 tool+session）
→ 低风险 fast path → 按需转 engine /v1/evaluate → deny 时直接 403
```

`fail-open` / `fail-closed` 可配；challenge 审批令牌也在这一层校验。

**任意业务 HTTP API**（REST/GraphQL 等）默认不被它做语义拦截。要覆盖语义，三条路：

1. 把能力包成 **MCP 工具**，走 proxy / 网关（推荐，零改业务协议）；
2. **SDK 内嵌**（Mode 3）在业务进程内 precheck；
3. 在入口侧做**自定义映射**：解析请求后以 `input`/`output`/`tool_call` 角色调 engine `/v1/evaluate`——这是自定义集成，不是配置开关。

**诚实边界**：非 MCP 协议的深度内容判定没有一键代理；网关负责 MCP 面的执法，别指望它顺带审所有 REST 流量。

### Q12：版本与兼容策略？

- **版本语义**：SemVer（CHANGELOG 遵循 Keep a Changelog），当前 1.0.x 线；产物按 `v*` 标签发布，GHCR 镜像建议钉具体版本（如 `:v1.0.3`）。
- **兼容契约**（[API_REFERENCE.md](API_REFERENCE.md)）分三级：

| 层级 | 范围 | 保证 |
|---|---|---|
| **Public stable（GA）** | engine `/v1/evaluate*`、`/v1/memory/check`、`/v1/challenge/verify`；control `/api/v1/admin/**`、`/api/v1/challenges/**`；auth `/login`、`/oauth/token`、JWKS | **同一大版本内无破坏性变更**；破坏性变更须新增版本化路径（如 `/v2`），并提前一个 minor 版本公告弃用 |
| **Component contract** | control `/api/v1/edge/**`、`/api/v1/gateway/**`、`/api/v1/tenants/**` | 组件间契约：**所有组件同版本部署**（升级整栈一起升） |
| **Internal / ops** | `control /api/v1/internal/**`、`engine /api/internal/**`、`/admin/**` | 无兼容保证，第三方不要依赖 |

- 弃用在 CHANGELOG 对应版本的 `Deprecated` 下公告；协议类破坏性变更（如 prompt judge 统一到 Jev `/v1/systemone`）在 CHANGELOG 标 **BREAKING**，协议文档单独版本化（`docs/judge-protocol-v1.md`）。

---


## 第四部分：阅读索引

| 想了解 | 读这个 |
|---|---|
| 产品定位与快速开始 | [README.zh.md](README.zh.md) · [QUICKSTART.zh.md](QUICKSTART.zh.md) |
| 分层架构与选层指南 | [ARCHITECTURE.zh.md](ARCHITECTURE.zh.md) |
| 安装/集成/规则编写/运维 | [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) |
| 12 个完整规则示例 | [rule-examples.zh.md](rule-examples.zh.md) |
| 部署拓扑与生产参数 | [DEPLOYMENT.zh.md](DEPLOYMENT.zh.md) |
| MCP 代理协议与配置 | [PROTOCOL.zh.md](PROTOCOL.zh.md) · [PROXY_CONFIG.zh.md](PROXY_CONFIG.zh.md) |
| judge 后端协议 | [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md) |
| 与同类方案对比 | [docs/COMPARISON.md](docs/COMPARISON.md) |
| 变更历史 | [CHANGELOG.md](CHANGELOG.md) |
