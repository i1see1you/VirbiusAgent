---
name: /backfillKnowledgeBase
id: backfill-knowledge-base
category: Workflow
description: 补写 Hindsight 记忆（change-id/当前会话 → 架构师提炼 → retain）
---

使用 **`dev-workflow`**：

- `~/.cursor/skills/dev-workflow/workflows/hindsight-backfill-retain.md`
- `~/.cursor/skills/dev-workflow/workflows/hindsight-retain-synthesis.md`
- `~/.cursor/skills/dev-workflow/workflows/hindsight-multi-bank-routing.md`

## Input

```
/backfillKnowledgeBase
/backfillKnowledgeBase <change-id>
/backfillKnowledgeBase --bank-id <bank_id>
```

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. **检查配置**：

```bash
"$SKILL_ROOT/scripts/hindsight-backfill-context.sh" --root "$ROOT" [--change-id "<id>"]
```

- 未启用 / 无 bank → 引导 `/openKnowledgeBase` 或 `/createKnowledgeBase`
- `enabled=true` 且连接失败 → 修正后再继续

3. **AskQuestion 补写来源**（若命令行未带 change-id）：
   - 当前会话
   - 指定 change-id（可让用户输入）
   - 用户粘贴摘要
   - 指定 bank_id（跳过路由）

4. 若 change-id：重新跑 context 脚本，阅读 OpenSpec proposal/design 与 `git_log_grep`

5. `list-banks` → 按 `description` 路由（或用户指定 bank）

6. **AskQuestion（提炼前）**：是否进行 **经验提炼**？用户确认后再继续

7. **架构师提炼**（见 `hindsight-retain-synthesis.md`，含 **Bank 提示词进化**）→ 展示提炼稿 → AskQuestion 确认写入 / **修改**

   用户修改且为通用规则、现有 `principles`/`dimensions`/`evolutions` 未覆盖时：

```bash
python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" evolve-bank \
  --root "$ROOT" --bank-id "<id>" --text "<精简规则>" --source "backfill-review"
```

8. **写入**：

```bash
python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" retain \
  --root "$ROOT" --bank-id "<id>" --text "<架构师提炼全文>"
```

9. 汇报结果；可选对刚写入内容做一次 `recall` 验证召回

## 注意

- 补写 **不** 替代 `/end-feature` 的 sync/归档/合并
- 与 end-feature retain 相同：**先确认是否提炼 → 再架构师提炼 → 展示稿确认 → 后 API**
