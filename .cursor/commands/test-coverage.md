---
name: /test-coverage
id: test-coverage
category: Workflow
description: 运行测试并统计覆盖率（Maven/JaCoCo 或 npm/Vitest），输出摘要
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/test-coverage.md`

**输出：** 先 Read `~/.cursor/skills/i-have-adhd/SKILL.md`（或项目 `.cursor/skills/i-have-adhd/SKILL.md`），按该 skill 简化对用户回复。缺则继续并提示 `/install-skills i-have-adhd`。详见 `conventions/adhd-output.md`。

## Input

```
/test-coverage
/test-coverage .
/test-coverage <subdir>
/test-coverage <subdir> --module <module>
```

## 助手必做

1. 未指定目标时：运行 `--list-targets`，**AskQuestion** 选择子项目。
2. 默认先 `--dry-run` 展示命令（用户急用可跳过）。
3. 跑完后汇报 **LINE 覆盖率**；可选 `--open` 打开 HTML 报告。

## 脚本

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
ROOT="$(pwd)"

"$SKILL_ROOT/scripts/test-coverage.sh" --list-targets --root "$ROOT"
"$SKILL_ROOT/scripts/test-coverage.sh" --root "$ROOT" --dry-run
"$SKILL_ROOT/scripts/test-coverage.sh" --root "$ROOT"
```

## 说明

- Maven 通过 JaCoCo CLI 生成报告，**无需改 pom.xml**。
- npm/Vitest 需要 `@vitest/coverage-v8`（或项目已有 Istanbul）。
- 报告目录通常已在 `.gitignore`，不要提交。
- 覆盖率不足时建议补测，**不自动**改阈值或阻塞 `/end-feature`（除非团队有明确要求）。
