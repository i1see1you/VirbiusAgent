---
name: /end-feature
id: end-feature-worktree
category: Workflow
description: 可选 OpenSpec 归档 → 推送远程 → 可选合并主 worktree → 确认后删除 feature worktree 并关闭窗口
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/end-feature.md`

**输出：** 先 Read `~/.cursor/skills/i-have-adhd/SKILL.md`（或项目 `.cursor/skills/i-have-adhd/SKILL.md`），按该 skill 简化对用户回复。缺则继续并提示 `/install-skills i-have-adhd`。详见 `conventions/adhd-output.md`。

## 分支术语（勿写死具体分支名）

| 符号 | 含义 | 来源 |
|------|------|------|
| `CHANGE_ID` | OpenSpec / worktree 标识 | 用户选择或 `dev_workflow/new-feature/meta.yaml` |
| `FEATURE_BRANCH` | **功能分支** | feature worktree 当前检出分支（`resolve-feature-worktree-context.sh` 输出） |
| `MAIN_BRANCH` | **集成目标分支** | 主 worktree 当前检出分支 |
| `SUBMODULE_BRANCH` | 子模块集成目标 | `.gitmodules` 中该子模块的 `branch` |

助手描述合并/删除/推送时，用「`FEATURE_BRANCH` → `MAIN_BRANCH`」，**以 dry-run 与脚本输出的实际分支名为准**；不要假设固定前缀（如 `feature/`）或固定目标名（如 `develop`）。

## Input

```
/end-feature
/end-feature <change-id>
```

| 运行位置 | change-id |
|----------|-----------|
| **主 worktree**（`VirbiusAgent/`） | **可选** — 未指定时先列出 worktree 供用户选择 |
| feature worktree | 可省略（自动推断） |

## 主 worktree 未指定 change-id 时（助手必做）

1. 运行列举脚本：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/list-feature-worktrees.sh" --worktree "$(pwd)"
```

2. 用 **AskQuestion** 让用户选择 **change-id**（选项文案含两个并列状态：**提交** `未提交` | `未推送` | `无提交` | `已推送` | `已推送·脏`；**合并** `已合并` | `未合并` | `无变更`）。
3. 用户选定后，后续步骤均带 `--change-id <所选>`。

`resolve-feature-worktree-context.sh` 在主 worktree 且无 `--change-id` 时会 **exit 2** 并附带 worktree 列表。

完整流程、Hindsight、归档、合并、删仓脚本见 `workflows/end-feature.md`。

## 脚本（主 worktree，已选定 change-id）

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
CID="<change-id>"
ROOT="$(pwd)"

eval "$("$SKILL_ROOT/scripts/resolve-feature-worktree-context.sh" --change-id "$CID" --worktree "$ROOT")"
"$SKILL_ROOT/scripts/stop-worktree-dev-services.sh" --worktree "$FEATURE_WT"

"$SKILL_ROOT/scripts/end-feature-sync-remote.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run

"$SKILL_ROOT/scripts/end-feature-merge-main.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run

"$SKILL_ROOT/scripts/end-feature-openspec-archive.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run
```

用户确认归档 / 合并后：

```bash
"$SKILL_ROOT/scripts/end-feature-worktree.sh" \
  --change-id "$CID" --worktree "$ROOT" \
  --confirm-archive --confirm-merge \
  --commit-message "feat(${CID}): <摘要>"
```

用户确认删除后（**须先 dry-run 校验合并**）：

```bash
"$SKILL_ROOT/scripts/end-feature-remove-worktree.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run

"$SKILL_ROOT/scripts/end-feature-remove-worktree.sh" \
  --change-id "$CID" --worktree "$ROOT" --confirm --close-ide
```

## 安全原则（最高优先级）

- **不丢代码**：有未提交、未推送或未合并（**含各子模块**）时不删除 worktree / 本地分支
- **归档要做完**：选了 OpenSpec 归档就必须 verify + commit/push
- **合并须确认**：合并进主 worktree 必须经用户 AskQuestion
- 不 force push；合并冲突时中止
- 删除 worktree **必须**经用户确认（AskQuestion）
