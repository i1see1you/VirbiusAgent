---
name: /implementation-qa
id: implementation-qa
category: Workflow
description: 实现完成门禁 — BMAD TEA（测试设计+自动化+执行）+ ponytail-review，输出 Done Report
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/implementation-qa.md`

**硬触发**：用户执行本命令时，必须跑完整 QA 流程，不可直接宣称「完成」。

`/qa` 留给 mattpocock/skills 的 QA 会话（报现象 → 开 GitHub issue）。本命令是实现门禁，不要走那套开场。

## Input

```
/implementation-qa
/implementation-qa <change-id>
/implementation-qa --skip-e2e
/implementation-qa --review-only
```

## 助手必做（按 workflows/implementation-qa.md 顺序）

1. **解析 change-id**（参数 → `.new-feature/meta.yaml` → `openspec/changes/` → `feature/*` 分支 → AskQuestion）
2. **确定 diff 范围**（本 feature 改动文件）
3. **BMAD TEA**（除非 `--review-only`）：
   - 读并执行 `bmad-testarch-test-design` → `docs/test/bmad-tea/<change-id>/test-design.md`
   - 读并执行 `bmad-testarch-automate`（或 `atdd`）→ 实现并 **运行** P0 用例
   - 后端：按仓库探测语言再跑（`go test` / `cargo test` / `mvn` / `pytest` / …），**禁止默认 Java**
   - 有 UI 且未 `--skip-e2e`：跑项目已有的 E2E（见 `e2e/README.md` 或 CI）
   - 可选：该栈自带覆盖率，或 `/test-coverage`（仅 Maven / npm）
4. **ponytail-review**（除非 `--skip-review`）：对实现 diff 做精简审查
5. **输出 Done Report**（见 workflow 模板）→ 再建议 `/end-feature` 或修失败项

## 前置

- `/install-skills bmad,ponytail`（缺则提示安装）
- 与 `.cursor/rules/post-implementation-qa.mdc` 内容一致；**本命令为显式入口**

## 豁免

用户同轮说 **skip QA** / **hotfix no tests** / 纯 docs-config → 按 workflow 豁免处理
