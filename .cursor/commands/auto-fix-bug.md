---
name: /auto-fix-bug
id: auto-fix-bug
category: Workflow
description: 拉 bug → 粗分 cluster → 建 worktree → bootstrap 后深度根因/复现/修复
---

使用通用技能 **`dev-workflow`**：`~/.cursor/skills/dev-workflow/workflows/auto-fix-bug.md`

**硬触发**：主 checkout **禁止具体实施**（禁止深度根因 / 复现 / 改代码）；只做到 **粗分 cluster + 选 cluster + 建 worktree**。实施一律在 worktree：`/opsx-bootstrap` → **2.5w 深度根因** → 5b-repro → OpenSpec。

**顺序（强制）**：拉列表 →（可选本地搜索）→ **多选 bug** → `record-bug-picker-selection.sh` → **2.5 粗分 cluster** → **勾选 cluster** → **依赖分析** → **建 worktree** → **`/opsx-bootstrap`** → **2.5w 深度根因** → **5b-repro** → OpenSpec → 验证。  
**选定前禁止分组。主仓在 opsx-bootstrap 前禁止具体实施。**

**AskQuestion UI**：多选 = checkbox（`allow_multiple: true`）；单选 = radio（`allow_multiple: false`）。详见 `conventions/askquestion-ui.md`。

## Input

```
/auto-fix-bug
/auto-fix-bug <source-id>
/auto-fix-bug <source-id> <bug-id>
/auto-fix-bug <source-id> <bug-id>,<bug-id>,...
/auto-fix-bug --all
/auto-fix-bug --add-source
/auto-fix-bug [参数…] {description}
```

**`{description}`（可选）**：同条消息里除参数外的自由文本（可多行），写入对话并保存到 `.cursor/bug-evidence/_session/user-context.md`，供根因分析 / worktree requirement 引用。无则跳过。

**负责人范围（默认本人）**：拉列表时只取 **当前登录用户负责**、且缺陷状态为 **未完成、未验证、未取消、非无法复现** 的 bug。要看全部负责人须 **明确** 加 `--all` 或说「所有人的 bug」。

**进度标志**：worktree 内写满根因起算「本地在修」。主仓贴 `picker-list-table.md` 后直接 checkbox；对话中说任意词可本地过滤。

**阶段耗时**：每阶段 `log-bug-phase.sh --event start|end`；超时 `needs_optimize` 须优化。汇总：`log-bug-phase.sh --event summary-all`。

**合并规则**：相同模块 / 菜单节点 / 页面 / 功能的 bug **放一起**（`write-bug-fix-clusters.sh` → 一个 `--bug-ids` worktree）；在 **worktree 内**一起复现与修改。

**并行 worktree**：不同 cluster 可同时开多个窗口。`suggest-parallel-worktrees.sh` 按 CPU/内存建议个数；**AskQuestion radio** 选并行度或自定义；`--choose N` → `_session/parallel-worktrees.json`（waves）。满员后 `wait-event.sh --topic parallel.slot.freed`。

**事件总线**：`emit-event.sh` / `wait-event.sh`（`<worktree-parent>/.dev-workflow-bus/`）；服务 ready、阶段、进度、槽位释放均会发事件。

## 两窗口流程

| 窗口 | 阶段 |
|------|------|
| **主 checkout** | 0–1b → **多选** → **2.5 粗分** → **勾选 cluster** → **建 worktree**（**禁止具体实施**） |
| **各 worktree** | **`/opsx-bootstrap`** → **2.5w 深度根因** → **5b-repro** → OpenSpec → 6–7 |

## worktree 窗口

1. **`/opsx-bootstrap`**（先起服务）
2. **2.5w**：写满 `root-cause.md`（主仓未做）
3. **5b-repro**：组内每 bug 一 subagent；`before/` + replay
4. `check-bug-evidence.sh --phase before` → OpenSpec 四选一
5. 修完后全程录屏（`start --name verify` 包住回放）→ after（主拍 web/H5/小程序/App 交互；接口/日志为附加）

## 主 checkout（选定后）

0. 若有 `{description}`：`save-auto-fix-user-context.sh` 保存并在对话中复述
1. **`prepare-bug-picker-list.sh`** → **贴列表** → 直接 checkbox（对话中说词可 `search-bug-picker-list.sh` 过滤）
2. 勾选 bug → **立即** `record-bug-picker-selection.sh`
3. 抓详情摘要 → **2.5 粗分**（不写深度根因）→ `write-bug-fix-clusters.sh` → 确认分组
4. 勾选 cluster → **`analyze-worktree-deps.sh`**（有先后则 AskQuestion）→ **建 worktree** → 提示用户开窗口 `/opsx-bootstrap`（主仓到此停止实施）

## 前置

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
SEA_ROOT="$HOME/.cursor/skills/nl-site-extract"
ROOT="$(git rev-parse --show-toplevel)"

[[ -f "$SEA_ROOT/SKILL.md" ]] || "$SKILL_ROOT/scripts/install-sea-skill.sh"
bash "$SEA_ROOT/scripts/ensure_deps.sh"
"$SKILL_ROOT/scripts/list-bug-sources.sh" "$ROOT"
"$SKILL_ROOT/scripts/list-in-progress-bug-fixes.sh" --root "$ROOT"
"$SKILL_ROOT/scripts/set-bug-fix-progress.sh" --root "$ROOT" --list
```

## 建 worktree（Phase 5a，可并行）

```bash
"$SKILL_ROOT/scripts/suggest-parallel-worktrees.sh" --root "$ROOT"
"$SKILL_ROOT/scripts/suggest-parallel-worktrees.sh" --root "$ROOT" --choose <N> \
  --cluster-id <id1> --cluster-id <id2>
# 每个将建仓的 cluster：
"$SKILL_ROOT/scripts/analyze-worktree-deps.sh" --root "$ROOT" \
  --cluster-id <id> --change-id "$CHANGE_ID" --write --gate
```

`sequence`（当前使用的因子被其它需求改写）→ **AskQuestion radio**（按序 / 合并 / 仍并行）。未确认禁止 init。选仍并行则 `init` 加 `--force-deps`。

对当前 wave 每个 **已放行** cluster 跑 `init-bug-fix-worktree.sh`（**默认不检查 before/**），每个路径开一个 Cursor 窗口 `/opsx-bootstrap`。

详见 **workflows/auto-fix-bug.md**、**conventions/bug-fix-artifacts.md**、**conventions/worktree-dev-services.md**.
