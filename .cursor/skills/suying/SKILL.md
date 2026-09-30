---
name: suying
description: Orchestrates Suying bug reproduce → screenshot → record → close → re-verify → compare → local archive via existing MCP tools and npm scripts. Use when the user asks to 复现, 验证这次修复, 对比两次运行, or 归档报告.
---

# Suying

编排已有 MCP / npm，不新开控制面。合同见 [reference.md](reference.md)。

## Agent 场景：模型怎么用（默认）

宿主 Agent **已经**有大模型。MCP / Intake 进程**拿不到**它，不要假设会共用 Cursor 订阅。

| 路径 | 默认（无独立 API key） | 配了独立 key 时 |
|------|------------------------|-----------------|
| 复现规划 | **你**把用户自然语言 / `repro_nl.txt` 拆成 `steps`，只调 `run_case({ steps })` | 仍优先 `steps`；仅当用户明确要 MCP 侧规划，或你无法可靠拆步骤时，才传 `nl`（需 `SUYING_LLM_API_KEY`） |
| 对比 `fix` | 接受 `fix: unknown`，如实汇报 | MCP/`compare` 可用 `SUYING_LLM_*` 做可选 AI 判断 |
| Intake | 不配 `INTAKE_LLM_*`：模板对话即可；接到复现仍由你读 `repro_nl` 再出 `steps`。技能脚本挂了会开 `/chat`（`platform=dev-workflow`），在对话里补齐再 finalize | 有 `INTAKE_LLM_*` 才走服务端润色 |

**禁止**：在未确认 MCP 已配置 `SUYING_LLM_API_KEY` 时对 `run_case` 传 `nl`（会硬失败）。  
**禁止**：为了「省事」让用户去配 key，而你本可以把描述写成 `steps`。

步骤形状见 [reference.md](reference.md)：`goto|click|type|wait|shot`。缺 selector/url 就先问用户或从页面上下文补全，再调用工具。

## 顺序

1. `run_case`（**默认 `steps`**；`nl` 仅在有 `SUYING_LLM_API_KEY` 且符合上表时）— 打开 Oxi 后 **自动** 用 webreel 开始录屏（`recording: true`）
2. 可选 `run_steps` / `screenshot`
3. `close` → 停录屏，写出 `.suying/runs/<id>/record.mp4`（若成功）+ `report.md`
4. 验证修复：先 `close` 未关的 case（单例），再跑一遍 1–3，留下 after `case_id`
5. `compare_runs`（`before`, `after`）。无 MCP 时：`npm run compare -- --before <id|path> --after <id|path>`
6. `npm run archive -- --compare <compare_id|path>` → `.suying/archives/<id>/`

## 停下（硬失败）

原样报错，不假装闭环完成，不回退 Chromium。

| 错误含 | 动作 |
|---|---|
| `already open` | 先 `close` 已打开的；关不了就停 |
| `OxiBrowser is unavailable` | 停 |
| `before` / `after` evidence missing 或 `has no usable PNG` | 停，点名哪一侧。不要 archive |
| archive 点名 `compare` / `before` / `after` | 停，点名缺哪一步 |

录屏失败 **不是** 硬失败：`close` 可带 `record_error`，报告写 `record: skipped — …`，截图闭环继续。关录屏：`SUYING_RECORD=0`。

`compare: passed` + `pixels: unchanged` + `fix: unknown` **不是失败**。如实说：对比跑完，像素未变，是否修复未知。

## 录制

默认随 `run_case`→`close` 写入 `record.mp4`（webreel 0.1.4，10fps）。不要另调 record MCP 工具。关掉：环境变量 `SUYING_RECORD=0`。

## 归档

`npm run archive -- --compare <id|path>`。缺证据会硬失败并点名。成功目录可直接复制分享。不上云。
