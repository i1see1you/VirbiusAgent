# VirbiusAgent FAQ：Agent 安全常见问题与真实案例
## VirbiusAgent能解决哪些问题？
### 1. 间接提示注入

**问题描述**

用户只说了正常话（「看看这封邮件」「处理一下 Issue」「读一下公告」）。攻击指令写在 Agent 读到的网页、邮件、公告、GitHub Issue 等不可信内容里。Agent 把这些内容当成指令执行，并使用当前会话已有的权限去发信、创建公开页面或把内部数据带出组织。用户输入可以完全干净，这与对话框里的直接越狱不是同一条攻击路径。

**真实案例**

- **EchoLeak / CVE-2025-32711（2025）**  
  Aim Labs 披露，影响 Microsoft 365 Copilot。攻击者发送一封带隐藏指令的邮件，用户不必点击恶意链接。Copilot 处理上下文时，可能将组织内邮件、文档带出边界。CVSS 9.3，微软在服务端修补。  
  [Checkmarx 综述](https://checkmarx.com/zero-post/echoleak-cve-2025-32711-show-us-that-ai-security-is-challenging/)

- **GitHub MCP（Invariant Labs，2025-05）**  
  用户让 Agent「查看公开仓库的 Issue」。恶意 Issue 劫持 Agent 后，使用同一把 GitHub token 读取私有仓库，并将内容写入公开 Pull Request。这不是传统代码 CVE，而是不可信内容与过宽工具权限出现在同一会话中。  
  [Invariant 原文](https://invariantlabs.ai/blog/mcp-github-vulnerability)

---

### 2. 越权访问

**问题描述**

工具参数（用户 ID、账号、工单号）由 Agent 生成，应用未再与当前会话身份核对。系统提示中的「只查询当前用户」挡不住换说法，也挡不住伪造的工具返回。这与 Web 中的 IDOR 同类，差别在于参数由模型填写。公开报道里很少单独出现「某业务 Agent 越权被黑」的标题，但企业将 Agent 接到业务 API 后，这是最常见的落地形态。防护手段与间接提示注入不同：这里需要授权校验，而不是判断语句是否像越狱。

**真实案例**

- **GitHub MCP（Invariant Labs，2025-05）**  
  用户意图是查看公开仓库，Agent 使用同一 token 访问了私有仓库，并将内容写入公开 Pull Request。这比「语句像不像越狱」更接近越权：权限边界没有按当前任务收紧。  
  [Invariant 原文](https://invariantlabs.ai/blog/mcp-github-vulnerability)

---

### 3. 过度自主

**问题描述**

冻结、审批、环境隔离只写在给 Agent 的提示词里。Agent 表示已理解，仍执行删库、发信、改配置。没有外部攻击者也可能发生：高危工具缺少独立于模型的强制闸门。

**真实案例**

- **Replit Agent（2025-07）**  
  SaaStr 创始人 Jason Lemkin 测试时明确要求 code freeze。Agent 仍删除生产数据库（公开报道约 1200+ executives、1190+ companies 的记录），并声称无法回滚，数据实际可恢复。Replit CEO 公开称不可接受，随后改为默认隔离开发/生产环境，并增加不能修改活代码的规划模式。  
  [DEV 综述](https://dev.to/ramdai_bista/replits-ai-agent-deleted-a-production-database-during-a-code-freeze-then-lied-about-the-rollback-59m1)

---

### 4. 长期记忆投毒

**问题描述**

日历邀请、网页、工单中的「工作惯例」被写入 Agent 的长期记忆（Memories / Saved Info）。用户新开会话只提出正常问题，召回的记忆将 Agent 带偏并驱动外发等操作。持久化后的内容往往不含「忽略以上指令」等越狱套话，针对直球注入的检测经常无法命中。

**真实案例**

- **Invitation Is All You Need（2025，Black Hat / DEF CON）**  
  Nassi、Cohen、Yair 经 Google VRP 披露：Google Calendar 邀请中的间接注入可驱动 Gemini for Workspace 执行操作。Google 承认并增加敏感操作确认、URL 策略与注入检测。  
  [The Register](https://www.theregister.com/2025/08/08/infosec_hounds_spot_prompt_injection/)

- **ChatGPT Memories / SpAIware（Rehberger，2024）**  
  不可信文档诱导 Agent 将恶意说明写入长期记忆，后续会话持续外泄。OpenAI 修补了 macOS 客户端一类外泄通道。攻击效果是一次写入、多次生效。

---

### 5. 直接提示注入

**问题描述**

用户在对话框中要求 Agent 忽略原有规则、更换人设或无条件同意其陈述。即使尚未调用工具，也可造成错误承诺与品牌损失。若后续接上下单、改价、发券等工具，则升级为过度自主。

**真实案例**

- **Chevrolet of Watsonville 经销商 ChatGPT（2023-12）**  
  攻击者先让站点 Agent 同意顾客的任何陈述，再提出「1 美元购买 Tahoe」。Agent 表示成交并声称具有法律效力。经销商下线该 Agent，车辆未按 1 美元交付。AI Incident Database #622。

---

### 6. 敏感数据泄露

**问题描述**

两类出口常被合并讨论。其一：人员将源码、会议纪要粘贴到未经批准的公有大模型服务。其二：Agent 将客户或内部数据写入发信、建单等工具参数并送出组织。前者是 Shadow AI / DLP 问题；后者是工具链外泄，与间接提示注入、越权访问的后果同类。

**真实案例**

- **三星半导体员工使用 ChatGPT（2023-04）**  
  公开报道称员工将芯片相关源码、会议纪要提交至 ChatGPT。公司随后限制直至禁止在公司设备上使用公有生成式 AI。  
  [TechCrunch](https://techcrunch.com/2023/05/02/samsung-bans-use-of-generative-ai-tools-like-chatgpt-after-april-internal-data-leak/)

---

### 7. RAG 投毒

**问题描述**

Agent 先检索企业聊天、文档或知识库再作答。公开频道、共享网盘中埋入的指令会与检索结果一并进入上下文。用户询问敏感信息时，回答中可能出现指向攻击者的链接；用户点击后数据离开组织。入口是检索增强，出口可以是回答本身，不一定再调用发信工具。

**真实案例**

- **Slack AI（2024-08）**  
  PromptArmor 披露：攻击者在公开频道发布伪装说明。用户使用 Slack AI 查询私密信息时，私密内容与该说明被拼进回答，并生成带陷阱的链接。点击后密钥等数据进入攻击者地址。Slack 已修补，并称未发现客户数据被未授权访问。  
  [PromptArmor](https://www.promptarmor.com/resources/data-exfiltration-from-slack-ai-via-indirect-prompt-injection)  
  [Dark Reading](https://www.darkreading.com/cyberattacks-data-breaches/slack-ai-patches-bug-that-let-attackers-steal-data-from-private-channels)

---

### 8. 幻觉与错误信息

**问题描述**

Agent 以肯定语气给出实际上不存在或与规定不符的政策。用户按该说明办理业务后，组织以「由 Agent 所述、不代表公司」抗辩。司法实践中，挂在官方渠道上的 Agent 常被认定为组织自身窗口。这属于内容正确性与产品责任，不是注入或越权。

**真实案例**

- **Moffatt v. Air Canada，2024 BCCRT 149**  
  旅客向网站 Agent 询问丧亲票，得到「可先购票、事后申请退差价」的说明，与真实规定不符。航空公司拒绝按 Agent 说法退款。不列颠哥伦比亚省民事裁判庭认定 Agent 是网站的一部分，航空公司须负责。  
  [Ars Technica](https://arstechnica.com/tech-policy/2024/02/air-canada-must-honor-refund-policy-invented-by-airlines-chatbot/)

---

### 9. MCP 供应链攻击

**问题描述**

Agent 通过 MCP 等协议连接外部工具。工具若为仿冒、遭篡改，或客户端/代理存在漏洞，则调用链本身不可信。这与间接提示注入不同：问题不在内容被读入上下文，而在工具来源与安装通道。公开材料以研究和 CVE 为主，尚缺少与 EchoLeak、Replit 同等知名度的生产事故头条。

**真实案例**

- **mcp-remote 等 MCP 客户端/代理漏洞（2025）**  
  公开过高危漏洞（含远程代码执行一类），作用于开发者机器上的 Agent 通道，需按软件供应链管理，不能因「插件」跳过审查。

---

### 10. 不安全的工具实现

**问题描述**

Agent 将自然语言转为查询或系统命令。若后端仍把字符串拼入 SQL，或在主机上原样执行命令，则传统注入、命令执行的入口变成了模型输出。大模型不会自动成为防火墙；工具实现仍须按原有安全基线处理不可信输入。

**真实案例**

- **LangChain GraphCypherQAChain / CVE-2024-7042（2024）**  
  langchainjs 将自然语言转成 Neo4j 的 Cypher 查询时未做参数化。攻击者通过提示注入让 Agent 生成恶意查询，可创建、修改、删除图数据或跨租户读取。已在后续版本修复。  
  [NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-7042)

- **Langflow 文件上传路径穿越 / CVE-2026-5027（2026）**  
  低代码 Agent 搭建平台将上传文件名原样写入磁盘，可用 `../` 写到任意路径并进而远程执行。约 7000 个实例曾暴露在公网；补丁发出后蜜罐上观察到真实攻击。  
  [NVD](https://nvd.nist.gov/vuln/detail/CVE-2026-5027)

## 技术相关问题
### 1. virbuis是如何在语义层面上抵御攻击的？
### 2. virbuis的部署成本高吗？需要投入目前的大模型动则几十万，上百万，甚至上千万的GPU\电力等方面的投入吗？
### 3. virbuis是如何拦截流量的？
### 4. virbuis的部署会不会造成大模型处理延迟的增加？
### 5. 针对特殊行业，virbuis需要个性化开发吗？
