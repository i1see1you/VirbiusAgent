---
name: /end-feature
id: end-feature-worktree
category: Workflow
description: 推送远程 → 合并主 worktree → 确认后删除 feature worktree 并关闭窗口
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/end-feature.md`

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

## 流程

```
0  （主 wt 且无 change-id）list-feature-worktrees → AskQuestion 选择
1  解析 change-id → 定位 ../VirbiusAgent-worktree/<change-id>/
2  dry-run 预览 sync + merge
3  若未推送：子模块先 commit+push，再父仓库 commit+push；已同步则跳过
4  若未合并：在主 worktree 合并 feature/<id>；已合并则跳过
5  AskQuestion：是否删除 worktree 并关闭 feature 的 Cursor 窗口？
6  用户确认后：git worktree remove + --close-ide
```

## 脚本（主 worktree，已选定 change-id）

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
CID="<change-id>"
ROOT="$(pwd)"

"$SKILL_ROOT/scripts/end-feature-sync-remote.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run

"$SKILL_ROOT/scripts/end-feature-merge-main.sh" \
  --change-id "$CID" --worktree "$ROOT" --dry-run

"$SKILL_ROOT/scripts/end-feature-sync-remote.sh" \
  --change-id "$CID" --worktree "$ROOT" \
  --commit-message "feat(${CID}): <摘要>"

"$SKILL_ROOT/scripts/end-feature-merge-main.sh" \
  --change-id "$CID" --worktree "$ROOT"
```

用户确认删除后：

```bash
"$SKILL_ROOT/scripts/end-feature-remove-worktree.sh" \
  --change-id "$CID" --worktree "$ROOT" --confirm --close-ide
```

## 安全原则（最高优先级）

- **不丢代码**：有未提交、未推送或未合并时不删除 worktree
- **不提交本地工具数据**：`end-feature-sync` 自动排除 `graphify-out/`、`.new-feature/`、`VirbiusAgent--*.code-workspace`、`_bmad/config.user.toml` 等，不会 `git add -A` 误推
- 不 force push；合并冲突时中止，提示用户手动解决
- 删除 worktree **必须**经用户确认（AskQuestion）
- 主 worktree 的 workspace 名称与配置**不变**

## 子模块

含子模块时先 push/merge 子仓库，再更新父仓库指针。详见 `dev-workflow/conventions/end-feature-submodules.md`。
