---
name: /nl-site-extract
id: nl-site-extract
category: Workflow
description: 用自然语言从指定网站取信息 — Crawl4AI crwl（公开页）+ ego-lite（登录/交互）
---

**硬触发**：用户执行本命令时，必须先读并执行个人技能 **`nl-site-extract`**，不可手写爬虫、不可跳过依赖安装。

技能路径：`~/.cursor/skills/nl-site-extract/SKILL.md`

```bash
SKILL_ROOT="$HOME/.cursor/skills/nl-site-extract"
```

## Input

斜杠后的参数是 **URL（或站点名）+ 自然语言需求**。可带开关：

```
/nl-site-extract
/nl-site-extract https://docs.crawl4ai.com/ 安装步骤是什么
/nl-site-extract https://example.com/pricing 提取所有套餐名和价格
/nl-site-extract --json https://example.com/pricing 套餐名和价格
/nl-site-extract --login https://admin.example.com/orders 今天有几笔单
/nl-site-extract --md https://example.com 只要正文
```

| 开关 | 含义 |
|------|------|
| （无） | 按 query 自动选 `-q` 或 `-j`；公开页默认 crawl4ai |
| `--json` / `-j` | `crwl URL -j "需求" -o json` |
| `--md` / `--markdown` | `crwl URL -o markdown`，由助手总结 |
| `--login` / `--ego` | 直接走 ego-lite（已登录态 / 要点击） |
| 无参数 | 向用户要 URL 和需求，不要猜站点 |

## 助手必做（按 SKILL.md）

1. **读** `$SKILL_ROOT/SKILL.md`（需要 CLI 细节再读 `crwl.md`）
2. **装依赖**：`bash "$SKILL_ROOT/scripts/ensure_deps.sh"`  
   - `need_onboarding: true` → 让用户完成 ego lite 引导后再跑一次  
   - 公开页且 `crawl4ai.ok` 可先抓，不必等 ego
3. **解析** URL + query；站点名含糊则先确认 URL
4. **选引擎并执行**  
   - 公开页：`$SKILL_ROOT/.venv/bin/crwl`（`-q` / `-j` / `-o markdown`，见 SKILL / crwl.md）  
   - `--login`、需点击、或 crwl 空页/登录墙/403 → ego-browser heredoc  
   - `-q`/`-j` 前先 `crwl config set`，禁止卡在交互要 key
5. **按 SKILL 的 Answer format 回答**（结论 / 依据含 URL / 未覆盖）。不要编造。

## 前置

- 个人技能已在 `~/.cursor/skills/nl-site-extract/`
- 缺 crawl4ai / ego-browser 时由 `ensure_deps.sh` 安装，不要改用 Playwright 顶替
