---
name: /upgrade-dev-workflow
id: upgrade-dev-workflow
category: Workflow
description: 拉取最新 dev-workflow 并刷新项目 rules/skills 模板（implementation-qa、dbhub、ponytail、核心斜杠命令等）
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/`

**输出：** 先 Read `~/.cursor/skills/i-have-adhd/SKILL.md`（或项目 `.cursor/skills/i-have-adhd/SKILL.md`），按该 skill 简化对用户回复。缺则继续并提示 `/install-skills i-have-adhd`。详见 `conventions/adhd-output.md`。

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
