---
name: /test-coverage
id: test-coverage
category: Workflow
description: 运行测试并打印行覆盖率摘要（Maven JaCoCo 或 npm Vitest/Istanbul）
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/test-coverage.md`

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/test-coverage.sh" --list-targets --root "$(pwd)"
"$SKILL_ROOT/scripts/test-coverage.sh" --root "$(pwd)"
```

可选：`--dry-run` 只显示将要执行的命令；`--open` 打开 HTML 报告。
