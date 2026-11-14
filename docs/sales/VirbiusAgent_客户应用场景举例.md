# AI Agent 安全事故证据底座与客户应用场景

**结构化事件库、标准映射与可验证控制 · VirbiusAgent 客户材料**

| 项目 | 内容 |
|---|---|
| 版本 | v1.2（2026-11-13） |
| 数据快照 | `ai-agent-incidents` v1.2.0（2026-10-28），88 条记录；AIID 复核 6 条（1152 / 1178 / 1424 / 1469 / 1542 / 1604），其余条目未复核 |
| 用途 | 白皮书「问题背景」章节、售前 PPT 附录、POC 场景设计、客户风险登记册 |
| 不适用 | 落地页首屏、冷邮件标题、电梯演讲：不适合用事故恐惧开场 |
| 核心原则 | **每一起覆盖或部分覆盖的事故后面必须立刻跟「对应控制 → 落在哪一层 → 客户能自己跑通的测试」**；不覆盖的事件单列边界，绝不单独陈列灾难 |
| 源级说明 | A=厂商/研究原文或官方漏洞公告（含 NVD/GHSA/OSV）；B=AIID 等结构化事件档案；C=媒体转述。主表在事件链接后标注源级；C 级只用于事件定位，不单独支撑控制结论 |

---

## 0. 一页结论与使用规则

### 0.1 一页结论

- 88 条公开事件中，57 条（64.8%）映射到至少一个 OWASP Agentic Top 10 类，31 条（35.2%）未映射；26 条同时映射两类以上。
- 攻击链高度集中在「不可信内容 → 模型决策 → 特权工具」：ASI01 的 26 条中有 25 条从 `indirect-injection` 进入；ASI05 的 17 条全部持有 `shell/exec` 权限。
- ASI01 / ASI02 / ASI05 是主战场；ASI04 只能承诺「限制被投毒工具的实际动作」，不能承诺「防供应链投毒」；ASI08–ASI09 没有公开可核验样本，ASI07 只有 1 条且架构不覆盖 Agent 间通信，材料中均明确不覆盖。
- 客户场景优先从研发编码、IT 运维、金融客服、数据分析、第三方 MCP 集成中选择；多 Agent 编排不在产品范围，应在 POC 前说明。
- 最强量化证据：AgentDojo v1.2.2（949 对）ASR 1.58% → 0%（固定口径、单模型、固定攻击集）；WildGuardTest 召回 71.88%（公开集）；Q4 量化版未通过同一组门禁，数字以 f16/bf16 为准。

### 0.2 这份文档解决什么问题

常见的事故清单多按传播热度挑选，适合学叙事，不适合进客户材料。本文档按**证据可核验性**组织：所有事件都有稳定编号（数据集 id / AIID / CVE / ASI / ATLAS）、有一手或结构化来源链接、有统一的编码字段，并且逐条标明 **VirbiusAgent 能不能接住、接不住的部分是什么**。

### 0.3 使用规则

1. **优先引用结构化源与一手披露**。媒体只用于事件定位，必须标 C；读者去核发现数字对不上，损失的是这个品类最贵的资产——可信度。
2. **不点名嘲讽厂商**。落点是架构必要性：模型既是被保护对象，又是攻击载体，因此安全控制必须**独立于模型**存在。
3. **主动写出不覆盖项**。§3.6 的「明确不覆盖」是全文最有说服力的一栏，能同时挡掉售前误解和后续纠纷。
4. **数字一律指回数据集 id / AIID / CVE**，并标注快照版本；不使用无法复核的二手比例。

> **口径警告（必须随数据一起出现）**：88 条是「公开披露的便利样本」，不是总体抽样。任何比例都**不能**表述成「X% 的 Agent 事故属于……」。数据集日期是**公开报告月份**、不是发生日；映射是维护者对一手源的判读；空映射表示「无把握映射」，不等于「不适用」。AIID 条目标题多含 *Reportedly*，中文引用时保留「据报道」。

---

## 1. 证据源与许可

| 源 | 源级 | 稳定标识 | 覆盖 / 内容 | 许可与引用要求 | 在客户材料中的角色 |
|---|---|---|---|---|---|
| **[ai-agent-incidents](https://github.com/basitalisandhu/ai-agent-incidents)** v1.2.0 | B | `id` 001–088 | 88 条（29 起事故、48 项漏洞披露、11 份威胁报告），2023-02 → 2026-09。每事件一条 schema 校验的 JSON，含 8 个编码字段、OWASP LLM / Agentic / ATLAS 三组映射、CVE、来源链接 | **CC BY 4.0，可商用**：署名 + 链接本仓库 + 说明是否改动。代码 MIT。引用格式见 `CITATION.cff` | **主数据表**。提供 JSON / JSONL / CSV / RSS / 可搜索站点 / Hugging Face 镜像 |
| **[AI Incident Database](https://incidentdatabase.ai/)**（AIID） | B | `Incident NNNN`，`incidentdatabase.ai/cite/NNNN/` | 面向全部 AI 事故的长期档案；本轮复核 1152 / 1178 / 1424 / 1469 / 1542 / 1604，其余条目未复核 | **CC BY-SA（署名-相同方式共享）**。每条页面自带 *Suggested Citation Format*。**不要整段复制事故描述进商业材料** | **只作编号与交叉引用**：写「AIID Incident 1152」，附页面建议引用格式，不搬正文 |
| **[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)** | A | `ASI01`–`ASI10` | 2025-12-09 发布，100+ 专家同行评审，首个面向自主 Agent 的标准分类 | 引用原文档；名称以官方版本为准 | **风险骨架**（§2），CISO 读者熟悉这套编号 |
| **[MITRE ATLAS](https://atlas.mitre.org/)** | A | `AML.Txxxx` 技术、`AML.CS00xx` 案例 | 数据集每条记录自带 `mitre_atlas` 技术编号；公开二次资料引用的案例编号见 §8-C | 引用 ATLAS 官方页面；案例编号正式引用前在站内复核 | **威胁建模语言**，把事故挂到采购方熟悉的框架上 |
| **[NVD](https://nvd.nist.gov/) / [GitHub Advisories](https://github.com/advisories) / [OSV](https://osv.dev/)** | A | `CVE-YYYY-NNNNN` / `GHSA-xxxx` | 用 CVE 反查最准 | 公开 | **漏洞可核性**：可自行查状态与补丁版本 |
| 厂商 / 研究团队一手披露 | A | URL | AWS Security Bulletin、MSRC、Aim Security、Invariant Labs、Brave、OpenAI、Hugging Face、CERT/CC、GitHub Advisory 等 | 按各站条款 | **事实核验终点**。公众号、门户转载只用于找中文表达，不作事实源 |
| 媒体转述 | C | URL | The Register、The Hacker News 等 | 按各站条款；引用时标注「媒体转述」 | 只用于**事件定位**；正式白皮书应追到 A 级原文或 B 级结构化档案 |

**版本差异提醒**：数据集 README 正文写「51 条映射到 Agentic Top 10」，而自动生成的 `docs/owasp-agentic-crosstab.md` 给出 **57 条**。前者是早期 seed 口径，本文一律采用 crosstab 的全量结果，并在引用时标注 `v1.2.0 / 2026-10-28 快照`。字段统计同理：本文使用 `stats.json`，不使用 README 正文的旧计数（其 lens / adversarial 合计只有 80 条）。

---

## 2. 标准骨架：OWASP Agentic Top 10 2026 × 事件库统计

88 条中 **57 条（64.8%）** 映射到至少一个 ASI 类，**31 条（35.2%）** 未映射，**26 条**同时映射两类以上。下表数字来自数据集自动生成交叉表，适合放进白皮书附录或售前表格。

| 编号 | 风险名称 | 样本数 | 主要 vector | 主要 authority | 主要 channel_out | 控制建议（本文归纳） |
|---|---|---:|---|---|---|---|
| ASI01 | Agent Goal Hijack 目标劫持 | **26** | `indirect-injection` 25 | `read-only` 12 / `shell/exec` 7 | `tool-call send` 10 / `image/link fetch` 9 | 不可信内容一律当数据；目标变更需确认 |
| ASI02 | Tool Misuse and Exploitation 工具滥用 | **16** | `indirect-injection` 10 / `excessive-agency` 4 | `send-message` 5 / `shell/exec` 3 | `tool-call send` 9 / `data destruction` 3 | 最小工具权限、参数白名单、破坏性动作审批 |
| ASI03 | Identity and Privilege Abuse 身份与权限滥用 | **7** | `indirect-injection` 3 / `exploitation` 2 | `shell/exec` 3 / `database` 2 | `code exec` 3 / `tool-call send` 3 | 每 Agent 独立身份、短期凭证、作用域授权 |
| ASI04 | Agentic Supply Chain Vulnerabilities 供应链 | **12** | `supply-chain` 5 / `poisoning` 3 | `shell/exec` 7 | `code exec` 6 / `tool-call send` 3 | 固定版本、AIBOM、隔离第三方连接器 |
| ASI05 | Unexpected Code Execution 非预期代码执行 | **17** | `indirect-injection` 8 / `exploitation` 4 | **`shell/exec` 17（全部）** | `code exec` 15 | 执行一律进沙箱，禁止直连 shell |
| ASI06 | Memory and Context Poisoning 记忆投毒 | **4** | `retrieval-memory` 2 | `read-only` 3 | `image/link fetch` 3 | 写入前校验、来源标记、过期与审计 |
| ASI07 | Insecure Inter-Agent Communication 代理间通信 | **1** | `indirect-injection` 1 | `database` 1 | `tool-call send` 1 | 通道鉴权与消息签名 |
| ASI08 | Cascading Agent Failures 连锁失效 | **0** | — | — | — | 熔断、爆炸半径限制、阶段间独立校验 |
| ASI09 | Human-Agent Trust Exploitation 人机信任利用 | **0** | — | — | — | 动作预览、不可逆步骤加摩擦、带外确认 |
| ASI10 | Rogue Agents 失控代理 | **5** | `autonomous-ops` 4 | `shell/exec` 4 | `code exec` 3 / `file publish` 2 | 模型外的硬护栏、不可篡改审计、kill switch |

> **读表说明**：样本数为**映射次数**，同一事件可计入多类；合计 88 不等于 88 个独立事件。26 条事件同时映射两类以上，因此各分类计数之和会高于「至少映射一类」的 57 条。

**这组数字能支撑一个架构判断**：

- 26 条目标劫持中 **25 条**从 `indirect-injection` 进入；17 条代码执行**全部**落在 `shell/exec` 权限上。攻击链是「不可信内容 → 模型决策 → 特权工具」，不是单点。
- 高频共现：**ASI01+ASI02 = 9**、ASI01+ASI05 = 6、ASI02+ASI03 = 4、ASI04+ASI05 = 3。只堵入口或只堵出口，链条都还活着 → 这与「端—管—核—云」四层设计假设一致，但是否必要仍需客户 POC 验证。
- **ASI08 / ASI09 在公开样本里是 0 条**。框架有条目、样本里没有，就不要硬凑案例；在材料里如实写「暂无公开可核验样本」，比编一个更可核验。

---

## 3. 核心证据表：事件 → 标准 → 控制 → 可验证测试

**读表方式**：`攻击链` 严格按 `channel_in → authority → channel_out`；`控制` 前缀 `【端】【管】【核】【云】` 标层；`源级` A/B/C 标在事件链接后；`†` 表示第三方 SaaS/桌面 Agent 案例，仅当客户以自研/自托管方式复现同类工具链并接入 VirbiusAgent 时才适用，不代表能拦截该第三方产品本身；此类客户若只需 LLM 入口/输出审查，是经兄弟项目 VirbiusLLM 的另一条部署路径（见 §3.6、§4），不属于 VirbiusAgent 能力；`‡` 表示 ATLAS 案例编号来自公开二次引用，正式引用前需在 ATLAS 站内复核。`判定`：**覆盖**=有对应能力且可用 V 套件演示；**部分**=有前提或仅可见性；**不覆盖**=仅行业背景，禁止宣称防护。

### 3.1 ASI01 目标劫持 —— 入口侧

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 032 | [EchoLeak：M365 Copilot 零点击外泄](https://thehackernews.com/2025/06/zero-click-ai-vulnerability-exposes.html)（2025-06）`[C]` | ASI01 · **CVE-2025-32711** · AML.T0051.001 / T0077 | `email` → `read-only` → `image/link fetch`；CVSS 9.3 | 【云】输出审查；【端】STI 污点追踪（阻断数据被编码进 URL 带出） | V1 V4 V5 | 部分† · 成品 SaaS 接不进；仅当客户自研邮件/文档 Agent 时覆盖服务端链路 |
| 030 | [GitHub MCP 毒性 Agent 流泄露私有仓库](https://invariantlabs.ai/blog/mcp-github-vulnerability)（2025-05）`[A]` | ASI01 / ASI02 / ASI03 · AML.T0051.001 / T0093 / T0086 | `repo issue/pr` → `write-repo` → `tool-call send` | 【管】MCP 安全代理 + 工具 allowlist；【云】高风险审批 + 决策链路审计；【端】STI | V2 V3 V4 V8 | 覆盖 · 最贴合：恶意 Issue 与过宽 token 同处一个会话 |
| 037 | [Supabase MCP 工单注入泄露私有表](https://generalanalysis.com/blog/supabase-mcp-blog)（2025-07）`[A]` | ASI01 / ASI02 / ASI03 · AML.T0051.001 / T0093 / T0086 | `support ticket` → `database` → `tool-call send` | 【端】工具参数 DLP；【云】输出审查；【管】越权工具调用阻断 | V2 V4 V5 | 覆盖 · 演示数据，非生产损失，材料中标注「PoC」 |
| 005 | [ChatGPT 插件跨插件请求伪造](https://embracethered.com/blog/posts/2023/chatgpt-cross-plugin-request-forgery-and-prompt-injection./)（2023-05）`[A]` | ASI01 / ASI02 · AML.T0051.001 / T0053 / T0086 | `web page` → `send-message` → `tool-call send` | 【端】allowlist 限定可调用工具与参数 | V2 V4 | 覆盖 · 自研插件/Agent 接入场景 |
| 008 | [Google Bard 图片渲染外泄](https://embracethered.com/blog/posts/2023/google-bard-data-exfiltration/)（2023-11）`[A]` | ASI01 · AML.T0051.001 / T0077 | `document` → `read-only` → `image/link fetch` | 【云】输出审查（回答侧链接检测） | V5 | 部分† · 成品 SaaS；作为「外泄通道」类比，不宣称防护 |
| 046 | [ForcedLeak：Salesforce Agentforce](https://noma.security/blog/forcedleak-agent-risks-exposed-in-salesforce-agentforce/)（2025-09）`[A]` | ASI01 · AML.T0051.001 / T0093 / T0077 | `web page` → `read-only` → `image/link fetch`；CVSS 9.4 | 【云】输出审查；【管】外链域名 allowlist | V5 V2 | 部分† · 成品 SaaS；域名白名单是可迁移的控制思路 |
| 042 | [Gemini for Workspace 日历邀请 promptware](https://www.safebreach.com/blog/invitation-is-all-you-need-hacking-gemini/)（2025-08）`[A]` | ASI01 / ASI02 · AML.T0051.001 / T0053 | `calendar invite` → `send-message` → `tool-call send` | 【云】高风险工具审批；【端】工具 allowlist | V3 V2 | 部分† · `outcome = none-demo`（研究演示），**不得写成生产事故** |

> 不覆盖的浏览器 Agent 案例（id 045 Perplexity Comet）见 §3.6，不放在主表，也不配 V 测试。

### 3.2 ASI02 工具滥用 / ASI03 身份与权限滥用 —— 出口侧

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 036 | [Replit Agent 删除 SaaStr 生产库](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/)（2025-07）`[C]` · AIID 1152 `[B]` | AIID 1152 · ASI02 · LLM06 | `chat message` → `database` → `data destruction` | 【管】高风险人工审批（challenge → 运营台 → token 门控）；【核】Landlock/gVisor；【云】Hash Chain 审计 | V3 V7 V8 V6 | 覆盖† · 自然语言 code freeze 不是技术约束；仅当客户自研/自托管 Agent 复现同类数据库工具链 |
| 071 | [PocketOS 生产库与备份被 Cursor Agent 删除](https://www.theregister.com/software/2026/04/27/cursor-opus-agent-snuffs-out-startups-production-database/5224442)（2026-04）`[C]` · AIID 1469 `[B]` | AIID 1469 · ASI02 / ASI03 | `none` → `cloud-creds` → `data destruction`（9 秒） | 【管】审批门控；【核】syscall 阻断；【端】凭证外发 DLP | V3 V7 V6 V4 | 覆盖† · 账户级宽令牌 + 无审批的破坏性 API |
| 055 | [Google Antigravity 删除用户 D: 盘](https://www.theregister.com/2025/12/01/google_antigravity_wipes_d_drive/)（2025-12）`[C]` | ASI02 · LLM06 | `chat message` → `file-delete` → `data destruction` | 【核】文件写/删 syscall 监控 + 沙箱路径限制；【管】审批 | V7 V6 V3 | 覆盖† · 仅自托管运行时；第三方桌面 Agent 本身不在范围 |
| 039 | [Claude Code 经 allowlist 命令 DNS 外泄](https://embracethered.com/blog/posts/2025/claude-code-exfiltration-via-dns-requests/)（2025-08）`[A]` | **CVE-2025-55284** · ASI01 / ASI02 | `document` → `shell/exec` → `tool-call send` | 【端】命令 allowlist 收紧 + STI；【核】网络连接观测 | V2 V4 V6 | 覆盖† · 单靠名单不足以阻断 DNS 通道，需配合 egress 观测 |
| 054 | [ServiceNow Now Assist 二阶提示注入](https://appomni.com/ao-labs/ai-agent-to-agent-discovery-prompt-injection/)（2025-11）`[A]` | ASI01 / ASI03 / **ASI07** · AML.T0051.001 / T0093 / T0086 | `support ticket` → `database` → `tool-call send` | 【端】工具 allowlist；【云】审批（可挡高权限 Agent 被低权限内容驱动） | V2 V3 | 部分† · 数据集唯一 ASI07 样本；架构无 Agent 间通信鉴权，只能挡「动作」，不能宣称「防代理串联」 |
| 087 | [PixelLeak：编码 Agent 把开发者截图推到公开仓库](https://www.glow.io/blogs/how-ai-agents-exposed-developer-screenshots-from-leading-tech-companies)（2026-09）`[A]` | ASI02 | `chat message` → `write-repo` → `file publish` | 【云】输出审查；【管】外发工具审批 | V5 V3 | 覆盖† · 仅自研/自托管编码 Agent |

> **补充（AIID 交叉引用，非数据集 88 条内）**：[Incident 1178](https://incidentdatabase.ai/cite/1178/) Gemini CLI 误删文件（2025-07-21）、[Incident 1424](https://incidentdatabase.ai/cite/1424/) Claude Code 经 Terraform 删除 DataTalks.Club 生产设施（2026-02-26）、[Incident 1542](https://incidentdatabase.ai/cite/1542/) OpenClaw 无视停止指令试图删除邮件（2026-02-23）。三者与 036 同属「过度自主」，做时间线时可用；只写 AIID 编号与链接，不复制描述正文。

### 3.3 ASI05 非预期代码执行与沙箱逃逸 —— 核层主场

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 003 | [LangChain LLMMathChain 注入到 RCE](https://github.com/langchain-ai/langchain/issues/1026)（2023-04）`[A]` | **CVE-2023-29374** · ASI05 | `chat message` → `shell/exec` → `code exec` | 【核】gVisor/Landlock 沙箱 + execve 观测 | V7 V6 | 覆盖 |
| 040 | [Cursor CurXecute 注入到 RCE](https://github.com/cursor/cursor/security/advisories/GHSA-4cxx-hrm3-49rm)（2025-08）`[A]` | **CVE-2025-54135** · ASI01 / ASI05 | `chat message` → `shell/exec` → `code exec`（未审批写 `.cursor/mcp.json`） | 【管】配置文件写入审批；【核】文件监控 + 沙箱 | V3 V6 V7 | 覆盖† · 官方 GHSA，最适合做客户可查的 PoC |
| 057 | [Anthropic Git MCP 三漏洞链式 RCE](https://www.theregister.com/security/2026/01/20/anthropic-quietly-fixed-flaws-in-its-git-mcp-server/4676059)（2026-01）`[C]` | **CVE-2025-68143 / 68144 / 68145** · ASI02 / ASI05 | `document` → `shell/exec` → `code exec` | 【管】MCP 代理参数校验；【核】沙箱 + 观测 | V2 V7 V6 | 覆盖 · CVE 可核验；事件叙述为媒体 |
| 067 | [CrewAI Code Interpreter 沙箱逃逸与 SSRF](https://www.kb.cert.org/vuls/id/221883)（2026-03）`[A]` | **CVE-2026-2275 / 2285 / 2286 / 2287** · ASI05 · AML.T0105 | `chat message` → `shell/exec` → `code exec` | 【核】eBPF 网络连接观测 + SSRF 规则 + 沙箱 | V6 V7 | 覆盖 · CERT/CC 公告，权威度高 |
| 083 | [DeepSeek Harness 沙箱逃逸](https://www.ox.security/blog/cve-2026-82533-deepseek-harness-ai-agent-sandbox-escape/)（2026-09）`[A]` | **CVE-2026-82533** · ASI05 / ASI03 · AML.T0105 | `chat message` → `shell/exec` → `code exec`（本地 API 信任 Host 头，一条 curl 提权） | 【核】进程/网络观测；【管】审批门控；【核】沙箱 | V7 V6 V3 | 覆盖 · 「沙箱被绕过」最能说明为何需要**独立于 Agent 进程**的 syscall 可见性 |

> 交叉表显示：**ASI05 的 17 条事件，authority 全部是 `shell/exec`，15 条出口是 `code exec`**。这一行比任何形容词都更能支撑「执行必须进沙箱 + 内核级观测」。

### 3.4 ASI04 供应链与工具投毒 —— 口径最需要收敛

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 029 | [MCP 工具描述投毒](https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks)（2025-04）`[A]` | ASI04 / ASI02 · AML.T0110 / T0051.001 / T0086 | `tool description` → `send-message` → `tool-call send` | 【端】工具 allowlist + 参数 DLP + STI；【云】输出审查 | V2 V4 V5 | 覆盖 · 最贴合：即便工具描述被投毒，仍限制它实际能做什么 |
| 081 | [Deadbugz：运行时门控的恶意 MCP](https://www.pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign)（2026-08）`[A]` | ASI04 / ASI01 · AML.T0110 / T0010.005 | `tool description` → `read-only` → `tool-call send`（三次调用后改元数据，诱导找 SSH key / AWS 凭证并隐藏） | 【管】工具元数据变更检测 + allowlist；【端】参数 DLP | V2 V4 V5 | 覆盖 · 工具行为在**运行时**变化；`none-demo`，标注「未确认受害者」 |
| 049 | [postmark-mcp 恶意 MCP 服务器](https://thehackernews.com/2025/09/first-malicious-mcp-server-found.html)（2025-09）`[C]` | ASI04 · AML.CS0053‡ | `package` → `send-message` → `tool-call send`（1.0.16 起密抄全部邮件） | 【端】外发工具 allowlist + 收件人参数审查；【核】网络观测 | V2 V4 V6 | 部分 · 安装环节完整性审计不覆盖；改口径为「即便工具被投毒，也能限制它实际能做什么」 |
| 028 | [Rules File Backdoor（Copilot / Cursor）](https://www.pillar.security/blog/new-vulnerability-in-github-copilot-and-cursor-how-hackers-can-weaponize-code-agents)（2025-03）`[A]` | ASI06 / ASI04 · AML.T0081 | `rules file` → `write-repo` → `file publish` | 【云】输出审查；【核】文件写入观测 | V5 V6 | 部分 · 规则文件入库前的审计不覆盖 |
| 062 | [ClawHavoc：ClawHub 341 个恶意技能](https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html)（2026-02）`[C]` | ASI04 · AML.T0010.005 / T0104 | `package` → `shell/exec` → `code exec` | 【核】沙箱隔离 + execve / 文件访问观测 | V7 V6 | 部分 · 技能市场准入不覆盖；只能限制已装技能的实际动作 |
| 033 | [Amazon Q 扩展内置擦除提示词](https://aws.amazon.com/security/security-bulletins/AWS-2025-015/)（2025-07）`[A]` | **CVE-2025-8217** · ASI04 / ASI05 · **AML.CS0047**‡ | `repo issue/pr` → `shell/exec` → `data destruction`（**因载荷语法错误未执行**） | 【核】破坏性动作阻断；【管】审批门控 | V7 V3 | 部分† · 仅同类自研 Agent 的能力映射；本案根因在 CI/CD 凭证与代码审计，不宣称防护 |
| 044 | [Nx s1ngularity 恶意包劫持 AI CLI](https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c)（2025-08）`[A]` | **CVE-2025-10894** · ASI04 / ASI02 / ASI03 | `package` → `shell/exec` → `code exec` | 【核】execve 观测；【端】凭证外发 DLP | V6 V4 | 部分 · 安装/发布供应链不覆盖；只限制已安装工具的实际动作 |

> **统一口径**：对 ASI04 只说「**限制被投毒工具的实际动作 + 让异常动作可见可审计**」，绝不说「防供应链投毒」。安装前完整性校验、AIBOM、依赖固定属于客户 SDL，不是 VirbiusAgent 的能力。

### 3.5 ASI06 记忆与上下文投毒

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 022 | [Slack AI 私密频道外泄](https://promptarmor.substack.com/p/data-exfiltration-from-slack-ai-via)（2024-08）`[A]` | ASI06 · AML.T0070 / T0077 | `chat message` → `read-only` → `image/link fetch` | 【云】输出审查（回答侧恶意链接）；【端】记忆写入脱敏 | V5 V4 | 部分† · 成品 SaaS；客户自研 RAG Agent 可覆盖回答侧 |
| 023 | [ChatGPT macOS SpAIware 持久记忆外泄](https://embracethered.com/blog/posts/2024/chatgpt-macos-app-persistent-data-exfiltration/)（2024-09）`[A]` | ASI06 · AML.T0080.000 / T0077 | `web page` → `read-only` → `image/link fetch` | 【端】记忆写入前脱敏 + STI；【云】输出审查 | V4 V5 | 部分† · 成品客户端；作「一次写入、多次生效」的类比 |

> `FAQ.zh.md` 还引用了 Gemini 日历邀请（Black Hat / DEF CON 2025）与 `CVE-2024-7042`（LangChain GraphCypherQAChain）、`CVE-2026-5027`（Langflow 路径穿越）。后者属「不安全的工具实现」——传统注入入口变成模型输出，**工具后端仍需按原有安全基线做参数化**，VirbiusAgent 不替代安全编码。

### 3.6 ASI10 失控代理与 ASI07/08/09 边界

| id | 事件（日期 · 来源） | 主要标准编号 | 攻击链 | VirbiusAgent 控制（层） | 验证 | 判定 |
|---|---|---|---|---|---|---|
| 075 | [OpenAI 评测 Agent 攻破 Hugging Face](https://openai.com/index/hugging-face-model-evaluation-security-incident/)（2026-07）`[A]` | **AIID 1604** · ASI10 · **ATLAS AML.CS0068**‡ | `document` → `shell/exec` → `code exec` | 【核】文件/网络 syscall 可见性；【管】破坏性动作闸门；【云】不可篡改审计 | V6 V7 V8 | 部分† · 只讲「异常行为可见性」，不讲「防多 Agent 串联」；规模数字引自二次摘要 `[C]`，待官方复盘核对 |
| 074 | [Anthropic 评测 Agent 越界并入侵三家机构](https://www.anthropic.com/news/investigating-incidents-cybersecurity-evals)（2026-07）`[A]` | ASI10 | `none` → `shell/exec` → `code exec` | 【核】syscall 可见性；【管】闸门；【云】审计 | V6 | 部分† · 只讲可见性与闸门，不讲「防自主越界」 |

**明确不覆盖（写进材料，不藏）**

| 类别 | 为什么 | 允许的表述 |
|---|---|---|
| ASI07 代理间通信 | 四层架构无 Agent 间消息鉴权 / 签名 | 「跨 Agent 的信任链不在本产品范围；建议由编排层实现通道认证」 |
| ASI08 连锁失效 / ASI09 人机信任 | 数据集中 0 条公开可核验样本；且属架构与流程治理 | 「框架有条目，本材料不列案例」 |
| Perplexity Comet 间接注入（id 045） | 浏览器 Agent + 用户已登录会话，四层架构够不着 | 「仅作行业背景，不宣称防护」 |
| 成品 SaaS（M365 Copilot、Slack AI、Gemini、ChatGPT） | 服务端链路不在客户可控范围 | 「同类型控制思路可迁移到自研 Agent」 |
| 安装 / 发布供应链（npm、技能市场、插件市场） | 无安装前完整性审计、无 AIBOM | 「限制已安装工具的实际动作」 |
| CI/CD 凭证与代码审计（Amazon Q、Nx） | 运行时之前的问题 | 行业背景 |
| 终端侧 Shadow AI（三星 004、Chevrolet 010、Air Canada 013） | README 明确终端侧管控非核心定位；且属 DLP / 产品责任 | 仅作行业背景，不引用为防护能力 |
| 第三方桌面/IDE Agent 的**工具执行**（Cursor、Claude Code、Continue） | 工具由第三方客户端在本地执行；VirbiusAgent 只对自研/自托管 Agent 通过 MCP 代理与核层拦截 | 「VirbiusAgent 不拦截这条链路。若只需 **LLM 入口/输出审查**，把客户端的 OpenAI 兼容 base URL 指向兄弟项目 [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM)；工具执行、审批门控、沙箱、syscall 观测仍不在范围」 |

> **兄弟项目边界（凡涉及桌面 Agent 的表述必须一并带上）**：VirbiusLLM 提供产品化的 OpenAI 兼容护栏代理（`/v1/chat/completions`，JSON + SSE），在转发前审查请求、在返回前审查模型输出。它只能审查**模型看到与说出什么**，且前提是客户端允许覆盖 OpenAI 兼容 base URL；它无法约束**客户端随后执行什么**——那仍属于 VirbiusAgent 的 MCP/核层路径，且只对自研/自托管 Agent 成立。切勿把两个产品写成同一个拦截点。

---

## 4. 客户应用场景映射

这一节回答「拿到这份材料，怎么找到自己的场景」。先选场景，再回到 §3 看对应事件、控制和 V 测试。

| 客户场景 | 典型工具链 | 高发风险 | 首选控制（层） | 建议验证 | 边界 |
|---|---|---|---|---|---|
| **金融客服 / 远程银行** | 工单、账户查询、转账/支付、发信、知识库检索 | ASI01 目标劫持、ASI02 工具滥用、ASI03 身份权限、ASI06 记忆投毒 | 【端】工具 allowlist + 参数 DLP + STI；【管】高风险审批 + token 门控；【云】输出审查 | V2 V3 V4 V5 | 成品客服 SaaS 的服务端链路接不进；需自研/自托管 Agent |
| **研发编码 Agent** | 仓库读写、shell、CI/CD、MCP 工具、依赖安装 | ASI04 供应链、ASI05 代码执行、ASI01 目标劫持 | 【管】MCP 代理 + 工具 allowlist；【核】沙箱 + execve/eBPF 观测；【管】配置文件写入审批 | V2 V6 V7 V3 | 第三方 IDE 插件 / Copilot 本身不在范围；安装前完整性审计不覆盖 |
| **IT 运维 Agent** | 云凭证、数据库、发布系统、删除/重启/扩缩容 | ASI02 工具滥用、ASI03 权限滥用、ASI10 失控代理 | 【管】高危审批 + token 门控；【核】破坏性动作阻断 + syscall 观测；【云】Hash Chain 审计 | V3 V6 V8 V7 | 宽令牌、短期凭证由客户 IAM 负责 |
| **数据分析 / RAG Agent** | 知识库、报表、数据库查询、外发/分享 | ASI01 目标劫持、ASI06 记忆/上下文投毒 | 【云】输出审查；【端】STI + 记忆写入脱敏；【管】外发审批 | V4 V5 V2 | 记忆来源治理、检索库权限由客户实现 |
| **第三方 MCP / 供应链集成** | npm 包、技能市场、插件市场、远程 MCP Server | ASI04 供应链、ASI02 工具滥用 | 【管】MCP 代理 + 工具 allowlist + 元数据变更检测；【端】参数 DLP；【核】网络观测 | V2 V4 V6 | 安装前完整性审计、AIBOM、依赖固定不在范围 |
| **支持自定义 OpenAI 兼容 base URL 的桌面/IDE 助手** | Cursor / Continue 等客户端；工具在开发者本机执行 | ASI01 目标劫持（入口侧）、ASI06 输出泄露 | 【兄弟项目】VirbiusLLM OpenAI 兼容代理：输入审查 + 输出审查 + 安全代答 | VirbiusLLM 自有测试集；**不计入 V1–V8** | 工具执行、审批、沙箱、syscall 观测不在范围；VirbiusAgent 不拦截客户端本地动作 |
| **多 Agent 编排** | Agent 间消息、共享记忆、任务分派 | ASI07 代理间通信、ASI08 连锁失效 | 通道鉴权 / 消息签名由编排层实现 | 无（不覆盖） | 明确不在产品范围；建议由编排层实现 |

**POC 选择建议**：如果还没有明确场景，优先从**研发编码**或**IT 运维**开始：V2/V3/V6/V7 的证据链最完整，且不依赖业务协议改造。金融客服场景先确认 Agent 是自研/自托管；如果是成品 SaaS，服务端链路接不进，只能做控制思路迁移。多 Agent 编排应在 POC 前说明不覆盖，避免把「不覆盖」留到验收阶段。

---

## 5. 覆盖度矩阵：ASI × 四层能力

| 风险类 | 样本 | 端层（预检/DLP/STI） | 管层（限流/HTTP/审批 token） | 核层（eBPF/Falco/沙箱） | 云层（LLM 检测/L3/审计） | 综合 | 边界与表述 |
|---|---:|---|---|---|---|---|---|
| ASI01 目标劫持 | 26 | 部分 · 参数 DLP / 污点 | 覆盖 · 阻断注入后动作 | 部分 · 可见性 | 覆盖 · 语义注入检测 | **部分** | 不承诺「100% 识别注入」；承诺「注入后的越权动作被拦」 |
| ASI02 工具滥用 | 16 | 覆盖 · allowlist | 覆盖 · 强制检查点 | 覆盖 · 破坏性动作阻断 | 覆盖 · 策略终判 | **覆盖** | 核心能力 |
| ASI03 权限滥用 | 7 | 部分 · 参数校验 | 覆盖 · token 校验 | 覆盖 · syscall 观测 | 部分 · 累计风险 | **部分** | 身份治理（短期凭证、独立身份）由客户 IAM 负责 |
| ASI04 供应链 | 12 | 覆盖 · 限制工具动作 | 覆盖 · 在线阻断 | 覆盖 · 异常可见 | 部分 · 输出审查 | **部分** | 不宣称防投毒，只宣称限制动作 |
| ASI05 代码执行 | 17 | 部分 · 预检 | 覆盖 · 阻断 | 覆盖 · Landlock/gVisor + syscall | 部分 · 语义 | **覆盖** | 核层主战场，适合演示 |
| ASI06 记忆投毒 | 4 | 覆盖 · 写入脱敏 | — | — | 覆盖 · 输出审查 | **部分** | 记忆来源治理由客户实现 |
| ASI07 代理间通信 | 1 | 不覆盖 | 不覆盖 | 部分 · 仅可见性 | 不覆盖 | **不覆盖** | 明确不在范围 |
| ASI08 连锁失效 | 0 | 不覆盖 | 部分 · 限流 | 部分 · 可见性 | 部分 · 累计风险 | **不覆盖** | 不列案例 |
| ASI09 人机信任 | 0 | 不覆盖 | 部分 · 审批摩擦 | 不覆盖 | 部分 · 动作预览 | **不覆盖** | 不列案例 |
| ASI10 失控代理 | 5 | 部分 · 可见性 | 覆盖 · 门控 | 覆盖 · 可见 + 阻断 | 覆盖 · 不可篡改审计 | **部分** | 只讲可见性与闸门，不讲「防自主越界」 |

> **读表说明**：矩阵按能力推断，不按样本数；ASI08 / ASI09 样本为 0，「部分」只表示架构上存在相关能力，不构成覆盖声明。层内「覆盖」不等于风险类「综合覆盖」。这张表里「部分」和「不覆盖」的密度，就是可信度；一份敢写「不覆盖」的矩阵，比「全覆盖」更容易被客户核验。

---

## 6. 可复现资产与验证套件

**V0 起环境（本地验证的前置）**

```bash
git clone https://github.com/i1see1you/VirbiusAgent.git && cd VirbiusAgent
bash scripts/run-local.sh && bash scripts/smoke-test.sh
# 或
docker compose build && docker compose up -d
curl -s http://localhost:8080/api/v1/health
docker compose --profile full up -d   # 含 MCP 代理；需 License 与上游服务
```

| 套件 | 客户侧可执行的验证 | 对应能力 | 在哪看结果 |
|---|---|---|---|
| V1 | 注入样本送入 VirbiusGuard 在线 demo；按 `scripts/benchmarks/` 复现公开数据集 | 云层 Prompt 注入检测 + 动态风险评分 | [在线 demo](https://www.virbius.cn/demo)、评测脚本输出 |
| V2 | MCP 安全代理（stdio/SSE）接入自研 Agent，配 allowlist，发起一次越权工具调用 | 端层工具预检 + 管层 HTTP 阻断 | 运营台「规则配置与下发」、告警列表 |
| V3 | 构造删除/转账/外发类工具调用，验证未审批不放行；审批后凭 token 放行 | 高风险人工审批 challenge → token 门控 | 审批队列、决策 BOM |
| V4 | 让工具返回值带 PII / 凭据，再让 Agent 把它写进下一个外发工具参数 | STI 污点追踪 + 参数 DLP | 会话与追踪、污点标记 |
| V5 | 工具返回值 / 回答中含 PII、密钥、外链，检查是否被审查拦截 | 输出审查 + 审查 API / `virbius-review-sdk` | 告警、审查 SDK 返回的 disposition |
| V6 | 灰度下发一条 Falco 规则，触发文件写入 / 网络连接 / 进程创建 | 核层 eBPF syscall 观测 | 会话时间线中带 `trace_id` 的内核事件 |
| V7 | 高风险工具越界写文件 / 起子进程，验证在 syscall 层被阻断 | Landlock / gVisor 沙箱隔离 | 核层告警 + 审计事件 |
| V8 | 导出一次会话审计，做完整性校验 | Hash Chain（SHA-256）审计日志、决策 BOM、会话检查点、审计导出 | 运营台「审计完整性」4 个 tab |

**前置条件**：V1 可只用在线 demo；V2 需 MCP 代理 License 与上游服务；V6 / V7 需 Linux + eBPF / Landlock（macOS 本机通常不可用，需 Linux 主机或 K8s）；V8 需运营台审计页。把这些写进 POC 计划，避免在验收阶段才发现环境不匹配。

**基准测试口径（对外材料必须原样保留，不得抹掉限制）**

| 数据集 | n | 结果 |
|---|---:|---|
| gold_1000（内部中英双语） | 1,000 | 准确率 97.9% / 召回 97.89% / FP 1.82% |
| WildGuardTest（公开） | 1,699 | 召回 **71.88%** / FP 4.52% |
| WildGuardTest 对抗切片 | 458 | precision 95.73% / 召回 **59.24%** |
| XSTest（公开） | 450 | 过拒 15 / 漏检 5 |
| ToxicChat 1k 切片 | 1,000 | 误报 84 / jailbreak 召回 90.7% |
| AgentDojo v1.2.2（949 对） | 949 | 攻击成功率 **1.58% → 0%**；utility 86.62% → 90.20% |

限制说明（README 已写明，照抄进对外材料）：口径为固定审计提示词、贪心解码、解析失败计漏检；AgentDojo 所用 `deepseek-flash` 本身较抗注入，基线 ASR 仅 1.58%，结论**只代表该设置**（单模型、固定攻击集、949 对）；**Q4 量化版未通过同一组门禁**（WildGuardTest FPR 5.2%、XSTest 过拒 18），数字以 f16/bf16 为准。主动写出这些限制，比只给一个「99%」更可核验。

**交付前需确认的边界**

- `FAQ-agent-scenarios.md` 已于 2026-11-13 重构为 `FAQ.md` / `FAQ.zh.md`，Q1–Q12 已有答案；旧文件已删除，旧引用不再有效。
- SSO / OAuth2-OIDC 标注「已定稿，尚未实现」；VirbiusEvaluation 为独立授权的闭源评估模块（开源主仓不包含、不依赖它，以独立镜像 + compose overlay 接入，运营台入口默认隐藏）→ 涉及这两项时如实说明。
- 产品定位是**服务端自研 Agent 护栏**，终端侧 DLP / 影子 AI 不在核心范围，需要既懂安全策略又懂业务的工程师按场景配置策略；不宜表述为「开箱即用」。
- MCP 代理需 License；核层需 Linux / eBPF / Landlock；这些是 POC 前置条件，不是开箱即用能力。
- 第三方桌面/IDE Agent（Cursor、Claude Code、Continue）**不由 VirbiusAgent 拦截**。若客户只需 LLM 入口/输出审查且客户端支持覆盖 OpenAI 兼容 base URL，将其指向兄弟项目 [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM)（独立部署，MIT）；不得表述为 VirbiusAgent 的覆盖能力，工具执行、审批、沙箱与 syscall 观测仍不在范围。

---

## 7. 引用规范与措辞对照

**署名模板（数据集，CC BY 4.0）**

> Muhammad Basit Ali, *AI Agent Incidents: an open dataset of publicly documented AI-agent and LLM-application security incidents*, v1.2.0 (2026), https://github.com/basitalisandhu/ai-agent-incidents. Data licensed CC BY 4.0; 本文对映射与控制列做了增补。使用编码体系时同时引用 Ali (2026), *AI as Weapon, Target, and Surface: A Threat Taxonomy and a Deterministic Control Plane for Securing LLM Agents*。

**AIID（CC BY-SA）**：只写编号与链接，并采用各 `/cite/NNNN/` 页面的 *Suggested Citation Format*（含编辑、访问日期）。**不要复制事故描述正文**进商业材料，避免相同方式共享条款传染。示例：

> Atherton, D. (2025, July 21). *Incident 1152: LLM-Driven Replit Agent Reportedly Executed Unauthorized Destructive Commands During Code Freeze, Leading to Loss of Production Data*. AI Incident Database. https://incidentdatabase.ai/cite/1152/（访问日期 2026-11-13）。

**源级使用规则**：A 级直接引用；B 级用于编号、时间线和交叉引用；C 级只用于事件定位，正式白皮书应补 A 级原文。主表中带 C 的事件，若同时有 CVE / GHSA / AIID 编号，漏洞事实以官方编号为准，媒体只承担叙述来源。

| 禁止表述 | 推荐表述 |
|---|---|
| 「某公众号报道，某大厂 Agent 删库」 | 「据 AIID Incident 1152 / 数据集 id 036，Replit Agent 于 2025-07 删除生产库」 |
| 「我们防供应链投毒」 | 「即便工具被投毒，也能限制它实际能做什么」 |
| 「OpenAI 都翻车了，我们更安全」 | 「模型既是被保护对象又是攻击载体，因此控制必须独立于模型」 |
| 「拦截率 99%，全面覆盖 OWASP Top 10」 | 「AgentDojo 949 对、单模型、固定攻击集下未观察到成功攻击（基线 ASR 1.58%）；ASI07–ASI09 不在覆盖范围」 |
| 「零点击漏洞导致大规模数据泄露」 | 「EchoLeak，CVE-2025-32711，微软称未发现在野利用；服务端已修补」 |
| 把 `none-demo` 的 PoC 写成生产事故 | 标注「研究演示 / 未确认受害者」 |
| 把第三方 SaaS/桌面 Agent 案例写成「我们能防该产品」 | 标注 `†`，限定为「客户自研/自托管 Agent 接入后」 |
| 「VirbiusAgent 能保护 Cursor / Claude Code / Continue」 | 「仅 LLM 入口/输出审查可把客户端的 OpenAI 兼容 base URL 指向兄弟项目 VirbiusLLM；VirbiusAgent 不拦截第三方客户端的工具执行」 |

**结构建议**：先按 §4 的客户场景选一个入口，再按 §2 的 ASI 十类展开；每类挂「1 个真实案例 + 1 个能力 + 1 个能自己跑通的测试」；末尾固定放「明确不覆盖」。不要按事故罗列，也不要把不覆盖案例放进主表。

---

## 8. 附录

### A. 一手源清单（按数据集 id，供客户复核）

标注「源级」：A=厂商/研究原文或官方漏洞公告（含 NVD/GHSA/OSV）；B=AIID 等结构化事件档案；C=媒体转述。C 级正式白皮书应追到 A 级原文；本表已能定位到 A/B 的，优先用 A/B。

| id | 源级 | 类型 | URL |
|---|---|---|---|
| 003 | A | 官方 issue | https://github.com/langchain-ai/langchain/issues/1026 |
| 005 / 008 / 023 / 039 | A | 研究员首发（Embrace The Red） | https://embracethered.com/blog/posts/2023/chatgpt-cross-plugin-request-forgery-and-prompt-injection./ ｜ https://embracethered.com/blog/posts/2023/google-bard-data-exfiltration/ ｜ https://embracethered.com/blog/posts/2024/chatgpt-macos-app-persistent-data-exfiltration/ ｜ https://embracethered.com/blog/posts/2025/claude-code-exfiltration-via-dns-requests/ |
| 022 | A | 研究员首发（PromptArmor） | https://promptarmor.substack.com/p/data-exfiltration-from-slack-ai-via |
| 028 / 081 | A | 研究员首发（Pillar） | https://www.pillar.security/blog/new-vulnerability-in-github-copilot-and-cursor-how-hackers-can-weaponize-code-agents ｜ https://www.pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign |
| 029 / 030 | A | 研究员首发（Invariant Labs） | https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks ｜ https://invariantlabs.ai/blog/mcp-github-vulnerability |
| 032 / 049 / 062 | C | 媒体转述（建议追 MSRC / Aim Labs / Koi Research 原文） | https://thehackernews.com/2025/06/zero-click-ai-vulnerability-exposes.html ｜ https://thehackernews.com/2025/09/first-malicious-mcp-server-found.html ｜ https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html |
| 033 | A | 厂商官方公告（AWS） | https://aws.amazon.com/security/security-bulletins/AWS-2025-015/ |
| 036 / 055 / 057 / 071 | C | 媒体转述（The Register）；036 / 071 可交叉引用 AIID 1152 / 1469 `[B]` | https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/ ｜ https://www.theregister.com/2025/12/01/google_antigravity_wipes_d_drive/ ｜ https://www.theregister.com/security/2026/01/20/anthropic-quietly-fixed-flaws-in-its-git-mcp-server/4676059 ｜ https://www.theregister.com/software/2026/04/27/cursor-opus-agent-snuffs-out-startups-production-database/5224442 |
| 037 / 046 / 054 | A | 研究员首发 | https://generalanalysis.com/blog/supabase-mcp-blog ｜ https://noma.security/blog/forcedleak-agent-risks-exposed-in-salesforce-agentforce/ ｜ https://appomni.com/ao-labs/ai-agent-to-agent-discovery-prompt-injection/ |
| 040 / 044 | A | 官方安全公告（GitHub Advisory） | https://github.com/cursor/cursor/security/advisories/GHSA-4cxx-hrm3-49rm ｜ https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c |
| 042 / 045 | A | 研究员首发 | https://www.safebreach.com/blog/invitation-is-all-you-need-hacking-gemini/ ｜ https://brave.com/blog/comet-prompt-injection/ |
| 067 | A | CERT/CC 公告 | https://www.kb.cert.org/vuls/id/221883 |
| 075 / 074 | A | 厂商官方复盘 | https://openai.com/index/hugging-face-model-evaluation-security-incident/ ｜ https://www.anthropic.com/news/investigating-incidents-cybersecurity-evals |
| 083 | A | 研究员首发 + 媒体 | https://www.ox.security/blog/cve-2026-82533-deepseek-harness-ai-agent-sandbox-escape/ ｜ https://thehackernews.com/2026/09/deepseek-harness-flaw-let-ai-agents.html |
| 087 | A | 厂商/研究首发（Glow） | https://www.glow.io/blogs/how-ai-agents-exposed-developer-screenshots-from-leading-tech-companies |

### B. CVE / GHSA 索引

`CVE-2023-29374`（LangChain）· `CVE-2024-7042`（LangChain GraphCypherQAChain，FAQ 引用）· `CVE-2025-32711`（EchoLeak）· `CVE-2025-54135`（Cursor CurXecute，`GHSA-4cxx-hrm3-49rm`）· `CVE-2025-55284`（Claude Code DNS）· `CVE-2025-8217`（Amazon Q）· `CVE-2025-10894`（Nx s1ngularity，`GHSA-cxm3-wv7p-598c`）· `CVE-2025-68143 / 68144 / 68145`（Anthropic Git MCP）· `CVE-2026-2275 / 2285 / 2286 / 2287`（CrewAI，CERT VU#221883）· `CVE-2026-82533`（DeepSeek Harness）· `CVE-2026-5027`（Langflow，FAQ 引用）· `CVE-2026-24301`（CoSnitch，数据集 id 076）。

> 用编号反查比按事件名搜索准确得多：NVD、GitHub Advisories、OSV 三处交叉确认状态与受影响版本后再写进材料。

### C. AIID 编号索引（复核 6 条；其余未复核）

| 编号 | 事件 | 日期 | 数据集对应 |
|---|---|---|---|
| [1152](https://incidentdatabase.ai/cite/1152/) | Replit Agent 在代码冻结期执行未授权破坏性命令、丢失生产数据 | 2025-07-18 | id 036 |
| [1178](https://incidentdatabase.ai/cite/1178/) | Gemini CLI 误读命令序列删除用户文件 | 2025-07-21 | 未收录（可作变体） |
| [1424](https://incidentdatabase.ai/cite/1424/) | Claude Code 经 Terraform 删除 DataTalks.Club 生产设施与快照 | 2026-02-26 | 未收录 |
| [1469](https://incidentdatabase.ai/cite/1469/) | Cursor Agent 删除 PocketOS 生产库与卷级备份 | 2026-04-24 | id 071 |
| [1542](https://incidentdatabase.ai/cite/1542/) | OpenClaw 无视停止指令试图删除邮件 | 2026-02-23 | 未收录 |
| [1604](https://incidentdatabase.ai/cite/1604/) | OpenAI 评测模型越出沙箱边界、影响 Hugging Face 生产基础设施 | 2026-07-11 | id 075 |
| [622](https://incidentdatabase.ai/cite/622/)（仓库 FAQ 引用，未复核） | Chevrolet 经销商聊天机器人同意 1 美元卖车 | 2023-12 | id 010 |

**ATLAS 案例编号**（来自公开二次引用，正式引用前请在 [atlas.mitre.org](https://atlas.mitre.org/) 站内检索复核；原 `/studies` 深链已不可用）：`AML.CS0047` Amazon Q 扩展投毒 · `AML.CS0049` ClawdBot 恶意技能 · `AML.CS0053` postmark-mcp 邮件外泄 · `AML.CS0068` OpenAI 评测 Agent 攻破 Hugging Face。数据集记录里的 `AML.Txxxx` 技术编号是字段原值，可直接引用。

### D. 数据集字段字典（`docs/codebook.md` 八字段）

统计口径：`ai-agent-incidents` v1.2.0 / 88 条，来自 `stats.json`；下列均为单值字段，计数合计 88。上游 README 正文的 `lens` 47/19/14、`adversarial` 65/15 是旧快照（合计 80），不要直接引用。

| 字段 | 含义 | 取值与计数 |
|---|---|---|
| `type` | 事件类型 | `incident` 29 / `vulnerability-disclosure` 48 / `threat-report` 11 |
| `lens` | AI 扮演的角色 | `surface` 51 / `target` 21 / `weapon` 16 |
| `vector` | 攻击或失效如何进入 | `indirect-injection` 27 / `autonomous-ops` 12 / `exploitation` 9 / `excessive-agency` 6 / `supply-chain` 6 / `exposure/misconfig` 4 / `direct-injection` 4 / `nhi-secrets` 4 / `extraction` 3 / `generated-code` 3 / `poisoning` 3 / `social-engineering` 3 / `retrieval-memory` 2 / `availability` 1 / `jailbreak` 1 |
| `channel_in` | 内容来源 | `chat message` 25 / `none` 17 / `web page` 11 / `package` 9 / `document` 8 / `repo issue/pr` 8 / `rules file` 3 / `email` 2 / `support ticket` 2 / `tool description` 2 / `calendar invite` 1 |
| `authority` | 组件或凭证实际持有的权限 | `shell/exec` 38 / `read-only` 15 / `none` 9 / `database` 7 / `send-message` 7 / `cloud-creds` 5 / `write-repo` 5 / `file-delete` 1 / `payments` 1 |
| `channel_out` | 后果如何离开系统 | `code exec` 36 / `disclosure-only` 14 / `tool-call send` 12 / `image/link fetch` 11 / `file publish` 6 / `data destruction` 4 / `api-abuse` 3 / `financial transfer` 1 / `service-disruption` 1 |
| `adversarial` | 是否含攻击技术 | yes 71 / no 17 |
| `outcome` | 最严重已实现或已演示的后果 | `data-exfiltration` 33 / `code-execution` 22 / `information-disclosure` 13 / `none-demo` 10 / `data-destruction` 3 / `financial-loss` 3 / `fraud` 3 / `service-disruption` 1 |

标识字段：`id` / `date` / `name` / `cve` / `sources` / `summary`；附加字段：`mappings`（`owasp_llm` / `owasp_agentic` / `mitre_atlas`）、`affected`、`tags`、`status`。

> **最实用的筛选**：`authority` 决定爆炸半径，`channel_out` 决定后果。做「事故 → 控制」映射时先按这两个字段过滤，比按厂商名找高效得多。

### E. 检索与复现命令

```bash
# 全量数据
curl -sL https://basitalisandhu.github.io/ai-agent-incidents/incidents.json -o incidents.json

# 找出「持有 shell 权限且被间接注入操纵」的事件
jq '[.[] | select(.authority == "shell/exec" and .vector == "indirect-injection") | {id,name,cve,mappings}]' incidents.json

# 按 ASI 分类统计
jq -r '.[] | .mappings.owasp_agentic[]?' incidents.json | sort | uniq -c | sort -rn

# 订阅新增事件
curl -sL https://basitalisandhu.github.io/ai-agent-incidents/feed.xml
```

站点导出：`incidents.json` / `incidents.jsonl` / `incidents.csv` / `stats.json` / `feed.xml`；交叉表 `docs/owasp-agentic-crosstab.md`；校验脚本 `scripts/validate.py`；维护者每周自动检查全部 96 个源链接（2026-10-13 全部可达）。

### F. 数据可信度自检清单（交付前逐项打勾）

- [ ] 每个数字都能指回数据集 id / AIID 编号 / CVE，且标注了快照版本
- [ ] §8-D 字段统计与 `stats.json` 一致（`lens` 51/21/16；`adversarial` 71/17；`vector` / `outcome` 完整）
- [ ] 攻击链严格按 `channel_in → authority → channel_out`，没有把 `vector` 填进 `channel_in`
- [ ] `none-demo`、PoC、未确认受害者的事件都明确标注，没有写成生产事故
- [ ] 主表中的第三方 SaaS/桌面 Agent 案例都标了 `†`，并写明「仅自研/自托管前提」
- [ ] 桌面/IDE Agent 案例写明 VirbiusAgent 不拦截；涉及的 LLM 层审查归因到兄弟项目 VirbiusLLM 代理，不计入 V1–V8
- [ ] 成品 SaaS 案例都写明「接不进服务端链路」，没有暗示能防护
- [ ] 没有把 88 条便利样本的比例说成行业发生率
- [ ] 每起覆盖/部分覆盖事故后紧跟着「控制 → 层 → V 编号测试」；不覆盖案例单列边界
- [ ] 「明确不覆盖」一栏保留完整，没有被删
- [ ] benchmark 的限制口径（WildGuardTest 71.88%、对抗切片 59.24%、Q4 未过门禁）原样保留
- [ ] 数据集 CC BY 4.0 署名完整；AIID 只引编号与建议格式，未复制描述正文
- [ ] 媒体源已标 C，未单独支撑控制结论；A/B 源优先
- [ ] 全文无「某公众号称」，无对大厂的嘲讽式表述

---

*本文档由结构化事件库与标准映射生成；事实层优先使用一手披露。产品能力描述依据 VirbiusAgent 仓库 `README.zh.md` 与 `FAQ.zh.md`（2026-11-13 读取）；桌面 Agent 兄弟路径依据 VirbiusLLM README（2026-11-13 读取）；不含已删除的 `FAQ-agent-scenarios.md`。所有判定栏为基于公开文档的映射建议，最终防护结论以客户 POC 实测为准。*