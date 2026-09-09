---
name: /closeKnowledgeBase
id: close-knowledge-base
category: Workflow
description: 关闭 Hindsight 记忆库（hindsight.toml enabled=false，保留凭证）
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/hindsight-knowledge-base.md`

## 助手必做

1. 解析项目根：`ROOT="$(git rev-parse --show-toplevel)"`
2. 若不存在 `hindsight.toml`，告知用户尚未配置，无需关闭
3. 执行：

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/hindsight-set-enabled.sh" --root "$ROOT" --disable
```

4. 向用户确认：Hindsight 已关闭；凭证仍保留，可用 `/openKnowledgeBase` 重开
