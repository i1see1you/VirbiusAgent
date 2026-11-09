# VirbiusAgent 发布与目录提交清单

本文件是 2–4 周增长计划第 5–7 项的执行包：registry 发包、目录/榜单提交、HuggingFace 互链。
所有条目均已核对目标仓库的当前格式（2026-11）。标 **手动** 的步骤需要账号或 token。

## 0. 先决条件（一次性）

### 0.1 修好 GitHub About 元数据

- Homepage: `https://www.virbius.cn`（`CNAME` 已指向该域名）
- Description（≤160 字符，关键词靠前）：
  `Open-source Agent security platform: MCP proxy + edge SDK + eBPF runtime + LLM guardrails. Blocks prompt injection, tool abuse and privilege escalation.`
- Topics（全量替换现有 3 个）：

```
agent-security ai-security llm-security ai-agents mcp model-context-protocol
mcp-security mcp-proxy prompt-injection llm-guardrails ai-gateway guardrails
ebpf falco dlp zero-trust ai-safety security-tools
```

- 开启 Discussions；添加 issue/PR 模板；把 28 个 open issue 里能公开的标 `good first issue` / `help wanted`。

### 0.2 发布顺序（重要）

1. crates.io: `virbius-core` → 等索引同步 → `virbius-mcp-proxy`
2. PyPI: `virbius-review-sdk`、`virbius-integrate`、`virbius-mcp-python`
3. npm: `virbius-mcp-node`
4. GHCR: 由 `.github/workflows/publish-images.yml` 在 tag `v*` 或手动触发时自动推送

---

## 1. Registry 发包

> 当前仓库版本 `1.0.3`。以下命令在仓库根目录执行；token 只放在环境变量里，不要写进仓库。

### 1.1 crates.io（virbius-core、virbius-mcp-proxy）

已补齐 `repository` / `homepage` / `readme` / `keywords` / `categories`；`virbius-mcp-proxy` 对
`virbius-core` 的 path 依赖已加 `version = "1.0.3"`。本地 dry-run 已通过 core，proxy 需等 core
上架后才能通过校验（这是 crates.io 的正常顺序约束）。

```bash
export CARGO_REGISTRY_TOKEN="<crates.io token>"

# 1) core
cargo publish -p virbius-core

# 2) 等 30–90 秒，确认索引可查
cargo search virbius-core

# 3) proxy
cargo publish -p virbius-mcp-proxy
```

发布后检查：
- <https://crates.io/crates/virbius-core>
- <https://crates.io/crates/virbius-mcp-proxy>
- <https://docs.rs/virbius-core> / <https://docs.rs/virbius-mcp-proxy>

### 1.2 PyPI（review-sdk、integrate、mcp-python）

已补齐 license（review-sdk 由 `Proprietary` 改为 `MIT`）、classifiers、project.urls、README。
本地已构建并通过 `twine check`。

```bash
# 构建（建议在干净 venv 里）
python3 -m venv .venv-pypi && . .venv-pypi/bin/activate
pip install -U build twine maturin

python -m build --outdir dist-pypi virbius-review-sdk
python -m build --outdir dist-pypi virbius-integrate
(cd virbius-mcp-python && maturin build --release -o ../dist-pypi)

twine check dist-pypi/*

# 发布（推荐 PyPI Trusted Publishing；token 方式如下）
export TWINE_USERNAME=__token__
export TWINE_PASSWORD="pypi-<token>"
twine upload dist-pypi/*
```

说明：
- `virbius-mcp-python` 是 PyO3 扩展，`maturin build` 默认只产出当前平台的 wheel；要覆盖
  Linux/macOS/Windows 多版本，需在 CI 里跑 maturin 矩阵（可后补）。
- 三个名字当前均未被占用（已核查）。
- 发布后检查：`https://pypi.org/project/virbius-review-sdk/` 等。

### 1.3 npm（virbius-mcp-node）

已新增 `package.json`、`index.js`（平台/可选包加载器）、`index.d.ts`、`README.md`。
`npm pack --dry-run` 已通过。

```bash
cd virbius-mcp-node
npm install
npm run build          # 需要 Rust + @napi-rs/cli；产出 virbius-mcp-node.<platform>-<arch>.node
npm publish            # 包名 virbius-mcp-node（未被占用）
```

注意：
- `npm publish` 前必须先在目标平台执行 `npm run build`，否则包里没有 `.node`。
- 真正跨平台分发需要为每个 target 发布 `virbius-mcp-node-<platform>-<arch>` 平台包
  （napi-rs 标准做法）；当前 `package.json` 的 `napi.triples` 已列出 5 个目标，可作为
  CI 矩阵的输入。未做平台包之前，npm 包只保证构建平台可用——这一点请在 README 里如实说明。

### 1.4 GHCR（control / engine / auth / mcp-proxy）

已新增 `.github/workflows/publish-images.yml`：push `v*` tag 或手动 dispatch 时，按 Dockerfile
的 target 构建多架构镜像并推送到：

```
ghcr.io/i1see1you/virbius-engine
ghcr.io/i1see1you/virbius-control
ghcr.io/i1see1you/virbius-auth
ghcr.io/i1see1you/virbius-mcp-proxy
```

无需额外 secret（使用 `GITHUB_TOKEN`）。首次推送后，在 GitHub 包设置里把可见性改为 Public
（默认可能继承仓库可见性；仓库是 public 则通常为 public）。

发布后验证：

```bash
docker pull ghcr.io/i1see1you/virbius-control:v1.0.3
docker pull ghcr.io/i1see1you/virbius-engine:v1.0.3
docker pull ghcr.io/i1see1you/virbius-mcp-proxy:v1.0.3
```

> 现有 `release.yml` 仍会推送一个 `ghcr.io/i1see1you/virbiusagent` 镜像（Dockerfile 默认
> target 实际是 mcp-proxy），语义容易混淆。建议后续删除该 push，统一用上面的 per-service 镜像。

---

## 2. 目录 / 榜单提交

### 2.1 punkpeye/awesome-mcp-servers（★97k，最大入口）

- 目标：`README.md` → `### 🔒 Security` 小节（约第 4123 行）
- 方式：Fork + PR，按现有一行格式追加（可放在该小节末尾，避免大规模重排）
- 条目（可直接用）：

```markdown
- [i1see1you/VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) 🦀 ☁️ 🏠 🍎 🪟 🐧 - Open-source Agent security platform with an MCP proxy: license and tool-allowlist precheck, prompt-injection/DLP checks, engine adjudication, human approval, and hash-chained audit, plus eBPF/Falco kernel observability.
```

- 备注：该列表主体是 MCP Server；VirbiusAgent 以 MCP proxy/gateway 身份进入 Security 小节。
  若维护者要求必须是可直接启动的 MCP Server，可同时附上 `docker compose --profile full up`
  的启动方式。

### 2.2 scadastrangelove/awesome-ai-security-tools（★1.7k）

- 重要：README 是生成物，**不要改 README**。按 `CONTRIBUTING` 改 `data/sections.json`
  （schema 见 `data/schema.json`），然后运行：
  `python3 scripts/update_github_metrics.py && python3 gen_readme.py && python3 gen_readme.py --check`
- 目标小节：`Runtime Protection & Enforcement`
- JSON 条目：

```json
{
  "name": "VirbiusAgent",
  "repo": "i1see1you/VirbiusAgent",
  "status": ["open_source"],
  "license": "MIT",
  "desc": "Open-source Agent security platform spanning an edge SDK, MCP proxy, gateway and eBPF/Falco kernel observation, combining deterministic rules with LLM-based prompt-injection detection, DLP masking, human approval and hash-chained audit.",
  "related": [
    {"label": "VirbiusGuard safety model", "url": "https://huggingface.co/i1see1you/VirbiusGuard"}
  ]
}
```

### 2.3 corca-ai/awesome-llm-security（★1.7k）

- 目标：`README.md` → `## Tools` 小节
- 条目：

```markdown
- [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent): open-source Agent security platform with an MCP proxy, edge SDK, LLM guardrails and eBPF runtime observability. ![GitHub Repo stars](https://img.shields.io/github/stars/i1see1you/VirbiusAgent?style=social)
```

### 2.4 raphabot/awesome-cybersecurity-agentic-ai（★600，垂直度高）

- 目标：`README.md` → `## Tools` 小节
- 条目：

```markdown
- [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) - Open-source security platform for AI agents: MCP proxy and edge SDK with license/allowlist precheck, prompt-injection detection, DLP, human approval, hash-chained audit, and eBPF/Falco kernel observability.
```

### 2.5 OWASP GenAI Security Project（手动）

- 组织：<https://github.com/GenAI-Security-Project>
- 资源站：<https://genai.owasp.org/>
- 没有单一的“工具榜单” PR 入口。建议路径：
  1. 在相关 initiative（如 `GenAI-Agent-Security-Initiative`）开 Discussion/Issue，附项目一句话简介与链接；
  2. 在 OWASP GenAI Slack / 邮件列表提交“community tool”介绍；
  3. 若后续有 Agentic AI Top 10 的映射文档，可提交 VirbiusAgent 作为控制措施实现案例。
- 提交文案用下方 §3 的 short description。

### 2.6 MCP 目录（手动，需账号）

这些站点的提交表单通常要求“可运行的 MCP server 命令”。VirbiusAgent 是代理/网关，提交前先确认
目标站点是否接受 gateway；不接受就跳过，把精力放在 2.1–2.4。

| 站点 | 入口 | 提交要点 |
|---|---|---|
| Smithery | <https://smithery.ai/>（Add server） | 需要 `command`/URL；可填 Docker 启动命令，或先发布一个 wrapper CLI |
| mcp.so | <https://mcp.so/submit> | 名称、GitHub URL、一句话描述、分类 Security |
| Glama | <https://glama.ai/mcp/servers>（Submit） | 同上；Glama 会生成 score badge，可回填 README |
| PulseMCP | <https://www.pulsemcp.com/submit> | 名称、URL、描述、分类 |

建议先做 2.1，等 awesome-mcp-servers 合并后再提交这些目录，成功率更高。

---

## 3. 通用文案（复制即用）

**Short (EN, ≤160 chars)**
> Open-source Agent security platform: MCP proxy + edge SDK + eBPF runtime + LLM guardrails. Blocks prompt injection, tool abuse and privilege escalation.

**Short (中文)**
> 开源 AI Agent 安全平台：MCP 代理 + 端层 SDK + eBPF 内核观测 + LLM 护栏，实时拦截提示注入、工具滥用与越权逃逸。

**Long (EN)**
> VirbiusAgent is a four-tier Edge–Gateway–Kernel–Cloud security platform for self-hosted AI agents. It combines a Rust edge SDK (license/allowlist precheck, DLP, taint tracking), a security-aware MCP proxy, an on-path gateway, and eBPF/Falco kernel observability with a cloud policy engine (rules + LLM guardrails). It supports human approval, hash-chained audit, and per-layer progressive rollout.

**分类/标签**
`Security` · `AI/ML` · `MCP` · `Agent` · `Rust` · `Java` · `eBPF` · `Guardrails` · `DLP`

**License**
MIT（与仓库根 `LICENSE` 一致；`virbius-review-sdk` 已同步为 MIT）

---

## 4. 完成检查表

- [ ] GitHub About homepage + topics + description 已更新
- [ ] Discussions / issue / PR 模板已开启
- [ ] `virbius-core` 已发布，`cargo search` 可见
- [ ] `virbius-mcp-proxy` 已发布
- [ ] 3 个 PyPI 包已发布且页面可访问
- [ ] `virbius-mcp-node` 已发布（含目标平台 `.node`）
- [ ] GHCR 四个镜像可 `docker pull`
- [ ] awesome-mcp-servers PR 已提交
- [ ] awesome-ai-security-tools PR（改 `data/sections.json`）已提交
- [ ] awesome-llm-security PR 已提交
- [ ] awesome-cybersecurity-agentic-ai PR 已提交
- [ ] OWASP GenAI 渠道已联系
- [ ] HF 模型卡已互链（见 `hf-model-card-snippet.md`）
