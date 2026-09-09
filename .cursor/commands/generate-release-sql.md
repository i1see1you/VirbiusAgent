---
name: /generate-release-sql
id: generate-release-sql
category: Workflow
description: 根据单功能 SQL 与功能范围生成上线增量脚本
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/`

按 `workflows/generate-release-sql.md` 执行。命名规范：`conventions/sql-script-naming.md`、`conventions/release-sql.md`。

## 步骤摘要

1. 定位上次上线批次；处理 pending（须用户确认）。
2. **AskQuestion — 功能来源（单选）**：
   - `openspec-archive` — 从 `openspec/changes/archive/` 列举已归档变更
   - `git` — 从 `base_ref` 以来的分支 / 合并 / `sql/*.sql` 提交列举
3. 按所选来源构建功能列表（**勿自动切换**另一种来源）。
4. **AskQuestion — 多选功能**（或全选 / 仅 pending / 自定义 SQL 文件）。
5. 用户确认后再生成 `sql/releases/{YYYYMMDD}/` 下的 aggregate + `manifest.yaml`（`feature_source` 写入 manifest）。

**禁止**：未让用户选择来源就直接生成；未让用户选择功能范围就写入 aggregate。
