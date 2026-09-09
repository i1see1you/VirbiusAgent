---
name: /openKnowledgeBase
id: open-knowledge-base
category: Workflow
description: 开启或关闭 Hindsight（enabled 开关；不创建 bank，创建请用 /createKnowledgeBase）
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/hindsight-knowledge-base.md`

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. `python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" status --root "$ROOT"`

3. **若无 `[hindsight.connection]`**：收集 `base_url`、`api_key`，可先 `init-disabled` 再 `/createKnowledgeBase` 创建第一个 bank

4. **若无任何 `[[hindsight.banks]]`**：引导用户执行 **`/createKnowledgeBase`**（须含 bank 用途 `description`）

5. **开启**：

```bash
"$SKILL_ROOT/scripts/hindsight-set-enabled.sh" --root "$ROOT" --enable
python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" list-banks --root "$ROOT"
# 对 default_bank_id 或用户指定的 bank 做连接测试
"$SKILL_ROOT/scripts/hindsight-test-connection.sh" --root "$ROOT" --bank-id "<default>"
```

6. **关闭**：`hindsight-set-enabled.sh --disable`

7. 多 bank 路由见 `hindsight-multi-bank-routing.md`
