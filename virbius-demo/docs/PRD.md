# virbiusDemo 演示平台 · 产品需求与设计文档（PRD）

> 版本：v0.1（初稿） · 状态：正式
> 用途：给后续任何 agent / 会话提供**秒懂上下文**的单一入口，避免重复确认。
> 定位：以 **OWASP LLM Top 10** 为核心内容的攻防演示台，最终目标是演示 **VirbiusAgent 如何防护**这些漏洞。

---

## 1. 项目定位与两步走计划

本项目分两步推进，**第一步先搭脆弱靶场框架，第二步接入 VirbiusAgent 做防护对比**。两步相互独立，先后顺序不可颠倒。

### 第一步（当前）：框架 + 内容
- fork 现有 llm-sec-range（本地路径 `d:\workspace\vbagent\llm-sec-range`，MIT License，Copyright (c) 2026 gatsby），改名为 **virbiusDemo**。

### 授权（重要）
- 本项目基于 llm-sec-range fork，其 **MIT License** 要求：**必须保留原版权声明**（`Copyright (c) 2026 gatsby`）与本授权文本。
- 做法：在 `virbius-demo` 根目录保留官方 `LICENSE` 文件（含原作者版权），在此之上可改名 / 换 logo / 增删内容。
- 禁止：删除或篡改原版权行、伪造作者身份。
- 保留 **Home + OWASP LLM Top 10（全部 10 个 lab）** 作为首版内容。
- 搭好框架、跑通即可，后续再逐步加入更多 case。

### 第二步（后续）：VirbiusAgent 防护
- 在 OWASP 的 `chat()` 拦截点插入 **VirbiusAgent 防护引擎**。
- 用全局开关实现"同一攻击：无防护 / 有防护"的 A/B 对比。
- 让使用者直观看到每个攻击被 VirbiusAgent 的哪一层（端/管/核/云）拦截、命中哪条规则、输出什么 `effective_action`。

---

## 2. 已确定的决策（勿再重复确认）

| 项 | 决策 |
|---|---|
| **项目目录** | `d:\workspace\vbagent\virbius-demo`（沿用仓库 `-` 命名风格） |
| **品牌名** | **virbiusDemo**；标题 / logo / 页脚统一替换，去掉 llm-sec-range 字样 |
| **授权** | **必须保留 MIT LICENSE 归属**：原版权 `Copyright (c) 2026 gatsby`；根目录保留官方 `LICENSE` 文件，仅允许改名换 logo，不得删除/篡改原版权行（详见 §1 授权） |
| **技术栈** | Flask + llm-sec-range 现有架构（app.py / modules / templates / llm_client / modelsel / conversations / inspect_util / data） |
| **首版内容** | 保留 Home + OWASP Top 10（10 个 lab 全保留） |
| **保留交互** | 多模型切换（modelsel）、逐轮 inspect 检查器、会话记忆 |
| **首版裁减** | 不注册 CTF / Catalog / Agent 三个模块（代码文件保留仓库备用，仅从导航移除） |
| **LLM06 处理** | 原跳转 `/agent/`，首版改为 guided 讲解关（去掉跳转链接），后续再加 Agent 模块 |
| **模型选择层** | 首版保留多模型切换（DeepSeek + OpenRouter + 本地 Ollama），暂不精简 |

---

## 3. 全局"VirbiusAgent 防护"开关（新增需求）

### 3.1 目标
在演示台上提供一个**全局开关**，控制是否启用 VirbiusAgent 防护。开启防护后，攻击请求会先经过防护引擎评估，可能被拦截；关闭则直连脆弱模型、暴露漏洞。用于直观演示"有防护 vs 无防护"的差异。

### 3.2 行为约定
- **开关形态**：布尔开关，全局生效（跨 lab 页共享，类似模型切换），放在 Home 顶部 + 每个 lab 页顶部。
- **开关状态持久化**：存于会话（`session`），切换后无需刷新即可作用于后续对话。
- **默认值**：默认**关闭**（先演示脆弱状态），用户可手动开启。

### 3.3 第一步的实现范围（占位）
- 第一步**只做开关 UI 与开关量**，防护引擎为**占位实现**。
- 开关行为：
  - `关闭` → 直接调用模型（现状，走漏洞路径）。
  - `开启` → 先进入防护引擎占位层：**记录一次评估但默认放行**（不拦截），并在响应中附带"VirbiusAgent 防护已启用（引擎待第二步实现）"的标注，保证首版可跑、可被清楚看到开关生效。
- 第二步再替换占位层为真实防护引擎（见 §5）。

### 3.4 关键接缝点
防护引擎的挂载点固定在 OWASP 的 `chat()` 中、调用模型之前（`modules/owasp.py` 的 `chat()`）。这是第二步接入的唯一位置，第一步保持该调用点**结构不变、只加开关判断**。

---

## 4. 目录结构（规划）

```
virbius-demo/
├── app.py                  # Flask 入口（改品牌名）
├── config.py               # 配置 + .env 加载
├── llm_client.py           # 模型调用封装（复用）
├── modules/
│   ├── owasp.py            # OWASP Top 10 蓝图（含防护开关拦截点）
│   ├── modelsel.py         # 多模型切换（复用）
│   ├── conversations.py    # 会话记忆（复用）
│   ├── inspect_util.py     # 逐轮 inspect 检查器（复用）
│   └── (ctf/catalog/agent_range 代码保留，首版不注册)
├── data/
│   └── owasp_labs.py       # OWASP 关卡数据（复用，首版全保留）
├── templates/ static/      # 前端（改 logo/标题/virbiusDemo 品牌）
├── docs/
│   └── PRD.md              # 本文档
└── LICENSE                 # 保留官方 MIT LICENSE（含原作者归属）
```

---

## 5. 第二步预留：VirbiusAgent 防护引擎（占位说明）

> 本节点仅占位，供第一步实现时保持结构，第二步再填充。

- 防护引擎接口（建议）：`evaluate(消息, 上下文) -> {action: allow|deny|challenge, layer, rule, effective_action, risk_delta}`。
- 挂载流程（第二步）：
  ```
  用户输入
    → chat() 调用模型前
    → 若开关=开启：VirbiusAgent 防护引擎评估
         ├─ allow      → 调模型（可附带"已放行"标注）
         ├─ deny       → 直接返回拦截结果（不调模型）
         └─ challenge  → 进入人工审批（后续）
    → 若开关=关闭：直连模型（脆弱路径）
  ```

---

## 6. 完成标准（第一步）

- [ ] `virbius-demo` 可启动，`Home` 页正常展示，品牌为 **virbiusDemo**。
- [ ] OWASP Top 10 全部 10 个 lab 可访问、可对话、可提交 flag。
- [ ] 多模型切换、逐轮 inspect、会话记忆正常。
- [ ] 全局"VirbiusAgent 防护"开关可见、可切换、默认关；开启后走占位防护层并附标注，不破坏现有功能。
- [ ] 未注册 CTF / Catalog / Agent（导航移除，代码保留）。
- [ ] 保留 MIT LICENSE 归属。

---

## 7. 打开即用：给下一个 agent 的速览

> 新 agent 接手时，先读 `docs/PRD.md`（本文件），再读 `app.py` 与 `modules/owasp.py` 即可。已锁定的决策见 §2，防护开关见 §3，第二步接入点见 §5。