---
name: /downloadHindsightBanks
id: download-hindsight-banks
category: Workflow
description: 从 dev-workflow skill 下载 hindsight-banks 提示词到当前工程
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/hindsight-banks-sync.md`

## Input

```
/downloadHindsightBanks
/downloadHindsightBanks --bank-id code
/downloadHindsightBanks --dry-run
```

下载前可用 **`/listHindsightBanks`** 查看 skill 中有哪些模板。

## download 后能否直接用？

| 用途 | 是否够用 |
|------|----------|
| 本地 retain 提炼 / recall 路由（读 spec JSON） | ✅ 下载 + toml 已注册对应 bank |
| 新 bank 模板 | ⚠️ 须在 `hindsight.toml` 增加 `[[hindsight.banks]]` |
| Hindsight 云端 retain/recall API | ❌ 还须 `/openKnowledgeBase` + `/createKnowledgeBase` |

详见 `hindsight-banks-sync.md`。

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. 可选：先拉最新 skill（用户确认时）：

```bash
"$SKILL_ROOT/scripts/upgrade-dev-workflow.sh" hindsight --root "$ROOT"
```

3. **预览 diff**：

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" diff --root "$ROOT"
```

4. 展示 skill 与项目差异；若项目文件有本地 `evolutions` 将被覆盖 → **AskQuestion** 确认下载 / 取消

5. **下载**：

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" download --root "$ROOT" [--bank-id "<id>"]
```

6. 建议用户 **commit 项目** 中的 `hindsight-banks/`（助手勿自动 push 项目仓库，除非用户明确要求）

7. `list-banks` 验证配置可读：

```bash
python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" list-banks --root "$ROOT"
```

## 注意

- 下载 **覆盖** 项目中同名 JSON；仅改单个 bank 时用 `--bank-id`
- 不含 Hindsight API 凭证
