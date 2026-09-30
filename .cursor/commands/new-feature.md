---
name: /new-feature
id: new-feature-worktree
category: Workflow
description: 写入需求 → 自动生成 change-id 与 worktree → 新窗口 Agent 引导 OpenSpec
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/new-feature.md`

**输出：** 先 Read `~/.cursor/skills/i-have-adhd/SKILL.md`（或项目 `.cursor/skills/i-have-adhd/SKILL.md`），按该 skill 简化对用户回复。缺则继续并提示 `/install-skills i-have-adhd`。详见 `conventions/adhd-output.md`。

## Input

`/new-feature` 后**整段文字**为 **`{requirement}`**（必填）。

## Worktree 路径（默认在工程外）

为避免大模型把 worktree 当成主工程改错代码，检出目录**不在仓库内**：

```
{工程根父目录}/{工程目录名}-worktree/<change-id>/
```

本仓库示例：`../VirbiusAgent-worktree/<change-id>/`（与 `VirbiusAgent/` 同级）

**自定义路径**（任选其一）：

1. 复制 `.cursor/worktree-parent.example` → `.cursor/worktree-parent`
2. 环境变量 `DEV_WORKFLOW_WORKTREE_PARENT`（绝对路径，优先级最高）

## 流程

```
A  由助手根据 {requirement} 生成 change-id（kebab-case，检查目录不冲突）
A2 analyze-worktree-deps.sh（按因子消费/改写对照已有需求；有先后则 AskQuestion，禁止盲建仓）
B  init-new-feature-worktree.sh（建 worktree、写需求、开新 Cursor）
C  新窗口 **/opsx-bootstrap**（**启动 dev 服务** → OpenSpec）— 本窗口结束
```

## 需求文件（在 worktree 检出根内）

```
<worktree-parent>/<change-id>/dev_workflow/new-feature/
├── requirement.md
├── meta.yaml
├── AGENT_START.md
└── .session-pending
```

## 阶段 A2（强制）

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
WT_ROOT="$("$SKILL_ROOT/scripts/resolve-openspec-git-root.sh")"
printf '%s' "<requirement>" > /tmp/new-feature-req.md
"$SKILL_ROOT/scripts/analyze-worktree-deps.sh" --root "$WT_ROOT" \
  --change-id "<change-id>" \
  --requirement-file /tmp/new-feature-req.md \
  --write --gate
```

| 结果 | 动作 |
|------|------|
| exit 0 | 继续 Phase B |
| exit 2 | 贴 `_session/worktree-deps.md`。**AskQuestion radio**：按序完成依赖 / 合并已有 worktree / 仍并行。选「仍并行」则 Phase B 加 `--force-deps`。空选重问。 |

## 阶段 B 脚本

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
WT_ROOT="$("$SKILL_ROOT/scripts/resolve-openspec-git-root.sh")"
cd "$WT_ROOT"
"$SKILL_ROOT/scripts/init-new-feature-worktree.sh" "<change-id>" --requirement-text "<requirement>"
# 若 A2 用户确认「仍并行」：追加 --force-deps
```

含子模块时自动 init，并为 **monorepo 下所有工程**（父仓库 + 各子模块）创建/切到同一分支 **`feature/<change-id>`**。

已有 worktree 可手动补齐子模块分支：

```bash
"$SKILL_ROOT/scripts/init-worktree-submodules.sh" "/path/to/worktree/<change-id>"
```

新窗口：打开 Agent 或 **`/opsx-bootstrap`**（先起服务，再 OpenSpec）。URL 见 **`dev_workflow/dev-worktree/services.json`**。

Cursor 会以 **`VirbiusAgent--<change-id>.code-workspace`** 打开（窗口标题可区分）；**不会修改**主目录 workspace。已有 worktree 可补生成：

```bash
"$SKILL_ROOT/scripts/write-worktree-code-workspace.sh" "<change-id>" "/path/to/worktree/<change-id>"
"$SKILL_ROOT/scripts/open-worktree-in-ide.sh" "/path/to/worktree/<change-id>"
```

若 OpenSpec 未安装：在新 worktree 窗口先 `/install-skills`。

功能完成后执行 **`/end-feature <change-id>`**（主 worktree 指定 change-id 即可；或在 feature worktree 内省略 change-id）。
