---
name: /init-git-hooks
id: init-git-hooks
category: Workflow
description: 初始化 dev-workflow git 钩子（graphify post-commit/post-checkout）
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/scripts/init-git-hooks.sh`

## 何时使用

- 新 clone / 新 worktree 后图谱不自动更新
- `graphify hook status` 显示 `not installed`
- 合并/切换分支后 hook 丢失，需修复

## 命令

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"

# 检测
"$SKILL_ROOT/scripts/init-git-hooks.sh" --dry-run

# 根仓 + 已知子模块（backend / backend-page / mini-program / ruleengine）
"$SKILL_ROOT/scripts/init-git-hooks.sh" --root "$(git rev-parse --show-toplevel)"

# 强制重装（先 uninstall 再 install）
"$SKILL_ROOT/scripts/init-git-hooks.sh" --force

# feature worktree 额外路径
"$SKILL_ROOT/scripts/init-git-hooks.sh" /path/to/worktree/post-loan-backend
```

## 与 /install-skills 的关系

`/install-skills graphify` 或 `all` **会**在以下目录执行 `graphify hook install`：

- monorepo 根目录
- `post-loan-backend`、`post-loan-backend-page`、`post-loan-mini-program`、`ruleengine`

**已安装则跳过**（不会每次 `--upgrade` 都重装）。新 worktree 或 hook 损坏时用本命令或 `--force`。

## 验证

```bash
cd post-loan-backend && graphify hook status
# 期望：post-commit: installed / post-checkout: installed
```
