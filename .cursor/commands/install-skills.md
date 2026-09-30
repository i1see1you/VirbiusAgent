---
name: /install-skills
id: install-project-skills
category: Workflow
description: 检测并自动安装缺失的 bmad、openspec、ponytail、review-security、improve-codebase-architecture、archify、graphify、dbhub、sea、suying、auto-fix-bug、核心斜杠命令
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/`

**输出：** 先 Read `~/.cursor/skills/i-have-adhd/SKILL.md`（或项目 `.cursor/skills/i-have-adhd/SKILL.md`），按该 skill 简化对用户回复。缺则继续并提示 `/install-skills i-have-adhd`。详见 `conventions/adhd-output.md`。

读取 `workflows/install-skills.md` 并执行：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/install-project-skills.sh" all --dry-run
# 确认后
"$SKILL_ROOT/scripts/install-project-skills.sh" [packs]
```

可选环境变量：`BMAD_MODULES`（默认 `bmm`）。

`all` 含 pack **`core`**：写入 `/new-feature`、`/end-feature`、`/upgrade-dev-workflow`、`/test-coverage`、`/generate-release-sql`、`/opsx-bootstrap`。

**升级 dev-workflow：** 使用 **`/upgrade-dev-workflow`** — pull `~/.cursor/skills/dev-workflow` 并以 `--upgrade` 刷新模板 rules/skills（详见 `workflows/upgrade-dev-workflow.md`）。

**复现：** pack **`suying`** 替代 ego-lite（`/install-skills suying` 或随 `auto-fix-bug`）。