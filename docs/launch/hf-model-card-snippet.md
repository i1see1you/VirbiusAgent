# HuggingFace / ModelScope 模型卡互链

目标：让 `i1see1you/VirbiusGuard` 模型页成为 VirbiusAgent 的稳定流量入口，并让 README 反向
链接模型。仓库 README 已经链接了 HF 与 ModelScope（`README.md` 的 “VirbiusGuard model
download” 一行），缺的是模型卡一侧的说明与回链。

> 当前环境无法直连 huggingface.co（HTTP 000），所以未自动改写模型卡；请用
> `hf` CLI 或有 HF token 的网页操作执行。

## 1. 在模型卡顶部/末尾加入

```markdown
## VirbiusAgent

VirbiusGuard is the prompt-safety classifier used by
[VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) — an open-source
four-tier **Edge–Gateway–Kernel–Cloud** security platform for AI Agents
(MCP proxy, edge SDK, eBPF runtime observation, hash-chained audit).

- GitHub: https://github.com/i1see1you/VirbiusAgent
- Website: https://www.virbius.tech
- License: MIT
```

如需更短的版本：

```markdown
Part of [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) — an open-source
Agent security platform (MCP proxy + edge SDK + eBPF runtime + LLM guardrails).
```

## 2. 同步到 ModelScope

同一段文案加到 `https://modelscope.cn/models/i1see1you/VirbiusGuard` 的模型介绍，
保证两个模型页都回链 GitHub。

## 3. 仓库侧（已具备，可选加强）

`README.md` 第 95 行已有：

```markdown
> **VirbiusGuard model download**: [HuggingFace](https://huggingface.co/i1see1you/VirbiusGuard) | [ModelScope](https://modelscope.cn/models/i1see1you/VirbiusGuard)
```

可选：在 Releases 的 release notes 里也加同样一行，增加反向链接面。

## 4. 验证

- `https://huggingface.co/i1see1you/VirbiusGuard` 页面出现 GitHub 回链
- `https://modelscope.cn/models/i1see1you/VirbiusGuard` 同上
- 从 HF/ModelScope 点击后 2 跳内能到达仓库 README 的 Quick Start
