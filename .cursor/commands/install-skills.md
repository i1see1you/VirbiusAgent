---
name: /install-skills
id: install-project-skills
category: Workflow
description: 检测并自动安装缺失的 bmad、openspec、ponytail、review-security、improve-codebase-architecture
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/`

读取 `workflows/install-skills.md` 并执行：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/install-project-skills.sh" all --dry-run
# 确认后
"$SKILL_ROOT/scripts/install-project-skills.sh" [packs]
```

可选环境变量：`BMAD_MODULES`（默认 `bmm`）。

**新增 pack（含在 `all` 默认安装）：**

| Pack | 用途 |
|------|------|
| `review-security` | 代码 diff 专项安全审查（注入、鉴权、加密等） |
| `improve-codebase-architecture` | 扫描架构摩擦点，输出 deepening 改进建议 |

**ponytail 自动生效：** 靠 `.cursor/rules/ponytail.mdc`（`alwaysApply`），安装后会同步到各子模块；详见 `dev-workflow/workflows/install-skills.md`。
