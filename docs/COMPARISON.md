# Comparison with adjacent Agent-security projects

Last reviewed: 2026-11-09. This page is based on public repositories and
documentation only; it is not a hands-on benchmark. The products have different
scopes, and capabilities change as upstreams evolve. Verify current behavior
against each project's own docs before making a decision.

| Project | Repository | License (as published) |
|---|---|---|
| VirbiusAgent | <https://github.com/i1see1you/VirbiusAgent> | MIT |
| Microsoft Agent Governance Toolkit (AGT) | <https://github.com/microsoft/agent-governance-toolkit> | MIT |
| Meta LlamaFirewall | <https://github.com/meta-llama/PurpleLlama/tree/main/LlamaFirewall> | Llama Community License |
| IBM ContextForge (`mcp-context-forge`) | <https://github.com/IBM/mcp-context-forge> | Apache-2.0 |

## At a glance

| Capability | VirbiusAgent | Microsoft AGT | Meta LlamaFirewall | IBM ContextForge |
|---|---|---|---|---|
| **Primary scope** | Agent runtime security across four layers | In-process agent governance | Prompt/agent guardrail scanners | MCP/A2A/REST gateway and registry |
| **Defense-in-depth** | Edge precheck → gateway enforcement → kernel observation → cloud adjudication | Optional policy, identity, sandbox and audit layers | Layered in-process scanners | Gateway-centric auth, rate limits and plugins |
| **Detection method** | Deterministic rules + ML/LLM models | Policy/rules (YAML/OPA/Cedar) + prompt-injection audit | ML classifiers + LLM reasoning audit | Config + plugins; mostly deterministic |
| **Rule engine** | Lua DSL, Groovy L3, Falco eBPF rules | Policy engine (YAML/OPA/Cedar) | Scanner config and regex filters | Plugin/config engine |
| **Runtime observability** | eBPF/Falco syscall-level events | App-level audit trail | In-process scan results/logs | App-level tracing |
| **MCP protocol support** | Native stdio/SSE proxy | MCP security gateway | Agent-framework guardrails (OpenAI/LangChain) | Native MCP + A2A + REST/gRPC federation |
| **Tool-call security** | Precheck → adjudicate → audit on the tool-call path | Deterministic in-app interception | Scans agent reasoning/output | Gateway auth/rate limits + plugin guardrails |
| **Human-in-the-loop** | Challenge → approve → token-gated execution | Approval policies | Not documented | Not documented |
| **Decision trace** | Full-chain causal visualization | Tamper-evident audit log | Scan logs/results | OpenTelemetry tracing |
| **DLP / PII masking** | Edge-layer masking, offline | Not documented | Not documented | Not documented |
| **Sandbox isolation** | Landlock / gVisor profiles | Execution sandbox | Not documented | Not documented |
| **Deployment** | Self-hosted sidecar, remote proxy, SDK | SDK/library (pip/npm/NuGet) | Python SDK | Self-hosted (PyPI/Docker/Helm) |

## How to read this table

- **"Not documented"** means the capability was not found in the public docs at
  review time; it does not prove the capability is absent.
- **Scope differs.** VirbiusAgent and Microsoft AGT focus on governing agent
  behavior; LlamaFirewall focuses on in-process guardrail scanners; ContextForge
  focuses on gateway/registry federation.
- **Placement differs.** A gateway, an in-process SDK, and a kernel-level sensor
  observe different events. Compare them on the threat model you need, not on a
  single feature count.

## Reproducible VirbiusGuard numbers

Model-level safety numbers (gold_1000, WildGuardTest, XSTest, ToxicChat, AgentDojo)
are reported in the main README and can be reproduced with
[`scripts/benchmarks/`](../scripts/benchmarks/). Competitor model numbers are not
included here because they require separate harnesses and licenses.

## Corrections

If any statement here is out of date or inaccurate, please open an issue or PR
with a public source link.
