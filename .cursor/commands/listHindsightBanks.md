---
name: /listHindsightBanks
id: list-hindsight-banks
category: Workflow
description: 列出 dev-workflow skill 中所有 hindsight-banks 模板及项目注册状态
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/hindsight-banks-sync.md`

## Input

```
/listHindsightBanks
```

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. 可选：先拉最新 skill（用户希望看远程最新模板时）：

```bash
"$SKILL_ROOT/scripts/upgrade-dev-workflow.sh" --root "$ROOT"
```

3. **列出 skill 模板**：

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" list --root "$ROOT"
```

4. 向用户展示表格化摘要：

| bank_id | evolutions | 项目已有 | 与项目一致 | 已在 hindsight.toml 注册 |
|---------|------------|----------|------------|--------------------------|

5. 若用户想使用某模板 → 说明见下方「download 后能否直接用」→ 引导 `/downloadHindsightBanks` 或 `/createKnowledgeBase`

## download 后能否直接用？

| 层级 | download 是否足够 | 说明 |
|------|-------------------|------|
| **本地提示词**（retain 提炼 / recall 路由） | ✅ 基本足够 | JSON 落到 `hindsight-banks/<id>.json` 即可被 `list-banks` 读取 |
| **hindsight.toml 注册** | ⚠️ 新 bank 需补 | 须在 `hindsight.toml` 有 `[[hindsight.banks]]` + `spec = "hindsight-banks/<id>.json"`；默认仅有 `code` |
| **Hindsight API 远程 bank** | ❌ 不够 | 须 `/createKnowledgeBase` 或已有远程 bank；download **只同步提示词**，不创建云端记忆库 |
| **开关与凭证** | ❌ 不够 | `hindsight.toml` 中 `enabled=true` + `[hindsight.connection]` |

**结论**：`/downloadHindsightBanks` = 同步**提示词模板**；要真正 retain/recall 还须 **toml 注册 + 开启 + 远程 bank**。
