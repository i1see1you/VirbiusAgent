---
name: /uploadHindsightBanks
id: upload-hindsight-banks
category: Workflow
description: 上传项目 hindsight-banks 提示词到 dev-workflow skill 仓库
---

使用 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/hindsight-banks-sync.md`

## Input

```
/uploadHindsightBanks
/uploadHindsightBanks --bank-id code
/uploadHindsightBanks --dry-run
```

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. **预览 diff**（必做）：

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" diff --root "$ROOT"
```

3. 向用户展示与 skill 模板 **有差异** 的文件；**AskQuestion**：确认上传 / 取消

4. **上传**（默认仅复制到 skill `templates/hindsight-banks/`，不自动 push）：

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" upload --root "$ROOT" [--bank-id "<id>"]
```

5. **AskQuestion**：是否在 skill 仓库 **commit**？
   - 是 → `--commit --message "chore(hindsight): sync hindsight-banks from <项目名>"`
   - 否 → 结束（用户可自行在 skill 目录提交）

6. **AskQuestion**（已 commit 时）：是否 **push** 到远程？

```bash
"$SKILL_ROOT/scripts/hindsight-banks-sync.sh" upload --root "$ROOT" --commit --push \
  --message "chore(hindsight): sync hindsight-banks from post-loan"
```

7. 汇报 `files` 与 `git` 字段（copied / committed / pushed）

## 注意

- 上传的是 **提示词 spec**（`description`、`retain` 等），不含 `hindsight.toml` 密钥
- skill 仓库路径默认 `~/.cursor/skills/dev-workflow`
