---
name: /opsx-bootstrap
id: opsx-bootstrap
category: Workflow
description: 启动 dev 服务 → 读取 requirement → 引导 OpenSpec（explore/new/ff/continue）
---

worktree 内 **`/new-feature`** / **`/auto-fix-bug`** 建仓后的标准入口。**硬触发**：先起服务，再 OpenSpec。

**自动（建仓打开新窗口）**：`open-worktree-in-ide.sh` 在存在 `.session-pending` 时默认 **后台** 跑 `run-opsx-bootstrap-services.sh`（子模块 + dev 服务）。  
**仍须在 Agent 发送 `/opsx-bootstrap`**（或首条消息触发 bootstrap 规则）完成 OpenSpec 四选一 AskQuestion——Cursor 无法在无用户操作时自动执行 slash 命令。

## 1. 子模块（若需要）

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/init-worktree-submodules.sh" "$(pwd)"
```

## 2. 启动 dev 服务（必须，除非已就绪）

若 **`.dev-worktree/services.json`** 不存在或 `status` ≠ `ready`：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/start-worktree-dev-services.sh" --worktree "$(pwd)"
```

| exit | 动作 |
|------|------|
| 0 | 读取 `services.json` 的 `backend_url` / `frontend_url`，**告知用户** |
| 2 | 日志疑似 **代码问题** → **AskQuestion radio**（排查代码 / 跳过启动 / 手动启动后继续），**不得擅自改业务代码** |
| 3 | 无项目钩子 → 提示手动启动或添加 `.cursor/dev-worktree-start.sh` |
| 1 | 环境/基础设施问题 → 报告日志路径，**AskQuestion radio** 是否重试 |

非代码问题（端口、依赖、子模块）脚本会 **自动修复并重试**。

已 `ready` 则跳过启动，直接读 URL 告知用户。

## 3. 读取需求

- `.new-feature/requirement.md`
- `.new-feature/meta.yaml` → `change_id`（及 `source: auto-fix-bug` 时的 `bug_id`）

## 4. OpenSpec 四选一

展示四条命令（需求原文嵌入）并 **AskQuestion radio**（`allow_multiple: false`，恰好选 1 个）：

| id | 命令 |
|----|------|
| `opsx-explore` | `/opsx-explore {requirement}` |
| `opsx-new` | `/opsx-new {requirement}` |
| `opsx-ff` | `/opsx-ff {requirement}` |
| `opsx-continue` | `/opsx-continue {change_id}` |

空选或多选 → 重问。用户选定后在 **本 worktree** 执行；删除 `.new-feature/.session-pending`（若存在）。

探索时更新 `openspec/changes/<change_id>/explore.md`。

**Hindsight：** 若 `hindsight.toml` 中 `enabled=true`，按 `dev-workflow/workflows/hindsight-design-recall.md` 先召回案例。

详见 `dev-workflow/conventions/worktree-dev-services.md`。
