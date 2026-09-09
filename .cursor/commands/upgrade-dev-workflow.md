---
name: /upgrade-dev-workflow
id: upgrade-dev-workflow
category: Workflow
description: 拉取最新 dev-workflow 并刷新项目 rules/skills 模板（implementation-qa、dbhub、ponytail 等）
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/`

读取 `workflows/upgrade-dev-workflow.md` 并执行：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/upgrade-dev-workflow.sh" --dry-run
# 确认后
"$SKILL_ROOT/scripts/upgrade-dev-workflow.sh"
```

可选：`--skip-pull`（只刷新项目）、`--skip-install`（只 pull skill 仓库）、`[packs]`（如 `dbhub,implementation-qa`）。

完成后 **Reload Window**（Cmd+Shift+P → Developer: Reload Window）。

与 `/install-skills` 区别：upgrade 会 **覆盖** 已由 dev-workflow 模板管理的 rules/skills，即使已安装过。
