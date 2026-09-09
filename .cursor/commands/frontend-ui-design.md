---
name: /frontend-ui-design
id: frontend-ui-design
category: Workflow
description: 前端动手前 UI/UE 设计门禁 — ui-ux-pro-max + 现有风格提炼或用户确认
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/frontend-ui-design.md`

**硬触发**：用户执行本命令时，必须完成 UI/UE 设计产出，**不可**直接写页面/组件代码。

## Input

```
/frontend-ui-design
/frontend-ui-design <page-or-feature-name>
/frontend-ui-design --new-project
/frontend-ui-design --stack vue
```

## 助手必做（按 workflows/frontend-ui-design.md 顺序）

1. **判定范围**：目标子项目（管理端 / 小程序 / H5 / app）、页面或功能名
2. **安装检查**：缺 `ui-ux-pro-max` → 提示 `/install-skills ui-ux-pro-max`
3. **风格来源**：
   - 已有工程 → 读同类页面 + 通用组件，写「继承风格摘要」
   - 新工程 / `--new-project` → AskQuestion 收集 UI/UE 偏好
4. **ui-ux-pro-max**：`--design-system`（必要时 `--persist --page "<name>"`）
5. **输出设计说明**（布局、组件、交互、响应式、a11y）→ 用户确认后再写代码

## 前置

- `/install-skills ui-ux-pro-max`（缺则提示安装）
- 与 `.cursor/rules/pre-frontend-ui-design.mdc` 一致；**本命令为显式入口**

## 豁免

用户同轮说 **skip UI design** / **logic only** / **hotfix no UI** → 按 workflow 豁免
