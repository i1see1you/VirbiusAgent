---
name: /createKnowledgeBase
id: create-knowledge-base
category: Workflow
description: 创建 Hindsight 记忆库（bank_id、用途描述、API 创建、写入 hindsight.toml）
---

使用 **`dev-workflow`**：

- `~/.cursor/skills/dev-workflow/workflows/hindsight-knowledge-base.md`
- `~/.cursor/skills/dev-workflow/workflows/hindsight-multi-bank-routing.md`

## 助手必做

1. `ROOT="$(git rev-parse --show-toplevel)"`，`SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"`

2. **连接信息**：若已有 `hindsight.toml` 的 `[hindsight.connection]`，复用；否则向用户收集 `base_url`、`api_key`（`tenant` 默认 `default`）

3. **bank_id**：默认 `code`（若已存在则 AskQuestion 新 id）

4. **用途描述（必填）**：向用户收集 — 该记忆库**存什么、不存什么**（写入 `description`，供 recall/retain 路由）。默认 `code` bank 建议：

   > 技术架构与方案 trade-off、数据结构、数据库设计、编码规范、性能优化方法与项目惯例；提炼为可复用通用经验；不含产品需求、运营文案与调试流水账。

   retain 写入前会按 `dev-workflow/workflows/hindsight-retain-synthesis.md` 做架构师视角提炼。

5. **Bank API 配置**：展示默认 profile 摘要，**AskQuestion**：
   - 接受默认 JSON 创建
   - 自定义 JSON（`--config` 路径）
   - 仅注册配置、远程 bank 已存在（跳过 ensure-bank）

6. **创建远程 bank**（用户确认后）：

```bash
"$SKILL_ROOT/scripts/hindsight-ensure-bank.sh" \
  --base-url "<url>" --api-key "<key>" --bank-id "<id>" \
  [--config "<profile.json>"]
```

7. **写入本地配置**（生成 `hindsight-banks/<id>.json` + 注册 `hindsight.toml`）：

```bash
python3 "$SKILL_ROOT/scripts/lib/hindsight-config.py" register-bank \
  --root "$ROOT" \
  --bank-id "<id>" \
  --description "<用户描述的用途>" \
  [--spec "hindsight-banks/<id>.json"] \
  [--profile "hindsight-bank.default.json"] \
  --skill-root "$SKILL_ROOT" \
  --base-url "<url>" --api-key "<key>" \
  [--enabled true] \
  [--set-default]
```

注册后可编辑 `hindsight-banks/<id>.json` 的 `retain.dimensions` 扩展提炼维度。

8. `hindsight-test-connection.sh --bank-id "<id>"`

9. 首个 bank 时确认 `enabled=true`（或引导 `/openKnowledgeBase`）
