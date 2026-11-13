# VirbiusAgent FAQ: Agent Security Scenarios & Product Answers

> For security engineers and developers evaluating or adopting VirbiusAgent.
> Three-minute overview: [README.md](README.md); layered architecture: [ARCHITECTURE.md](ARCHITECTURE.md);
> operations manual: [USAGE_GUIDE.md](USAGE_GUIDE.md); twelve complete rule examples: [rule-examples.en.md](rule-examples.en.md).
> 中文版：[FAQ.zh.md](FAQ.zh.md)

## Contents

- [Part 1 — Evaluation quick answers](#part-1--evaluation-quick-answers)
- [Part 2 — Threat scenarios: ten real-world shapes](#part-2--threat-scenarios-ten-real-world-shapes)
- [Part 3 — Technical Q&A](#part-3--technical-qa)
- [Part 4 — Reading index](#part-4--reading-index)

---

## Part 1 — Evaluation quick answers

### What is VirbiusAgent?

A **runtime security platform for self-hosted AI agents**: it blocks prompt
injection, MCP tool abuse and privilege escalation across four independent
layers — **Edge → Gateway → Kernel → Cloud** — combining sub-millisecond
rules, eBPF syscall observation, LLM guardrails, human approval and
hash-chained audit. It is not a WAF (no HTTP signature matching; the gateway only does rate limiting and enforcement), and
not antivirus (it does not match file signatures) — it reasons about **agent
behavior semantics**: which tool, which arguments, which context, what
downstream consequences.

### What does it protect — and what does it not?

It protects **the agents you build yourself** (server-side runtime: behind
the proxy, behind the gateway, or in-process via SDK). It does **not** cover
endpoint controls for employee AI usage (employees pasting source code into
public ChatGPT — Shadow AI / endpoint DLP is a different, standardized
product category). See the [scope note in the README](README.md).

### Open-source scope vs. commercial boundary?

This repository (MIT) contains engine / control / auth / mcp-proxy / all
SDKs / kernel / gateway — fully functional out of the box, with CI and
images that depend on nothing closed-source. **VirbiusEvaluation** (code &
skill-package security assessment) is a separately licensed closed-source
option: its console entry stays hidden and reports zero telemetry until you
connect it; the open-source stack works without it.

### My agent uses framework X — can it integrate?

Framework-agnostic; pick (or mix) three integration modes:

| Mode | Shape | Best for |
|---|---|---|
| **MCP Proxy** (sidecar) | stdio/SSE proxy carrying all MCP traffic | any MCP client, zero framework coupling |
| **Higress gateway** (remote) | HTTP on-path, WASM plugin enforcement | server clusters where a sidecar is impractical |
| **SDK embed** (in-process) | Python / Node SDK precheck before each call | LangChain, OpenAI Agents SDK (adapters included) down to any wrappable callable |

See the mode chooser in [QUICKSTART.md](QUICKSTART.md).

### What does a minimal deployment look like?

One command: `docker compose build && docker compose up -d` (Redis + engine
+ control; SQLite by default, no MySQL required). Local development:
`bash scripts/run-local.sh`. The protection surface — rules, lists,
adjudication, audit — is complete in that stack; gateway and kernel layers
are added on demand.

---

## Part 2 — Threat scenarios: ten real-world shapes

Each scenario gives the attack shape, publicly reported incidents, **what
VirbiusAgent does about it (which layer, which mechanism)**, and an **honest
residual-risk note** — including what runtime security fundamentally does
not own.

### Scenario cheat sheet

| # | Scenario | Primary layer | Key mechanisms |
|---|----------|---------------|----------------|
| 1 | Indirect prompt injection | Cloud + Edge | Prompt L1 semantics, STI taint, egress DLP |
| 2 | Privilege escalation (IDOR) | Edge + Cloud | Tool allowlist, license scope, Groovy argument rules |
| 3 | Excessive autonomy | Cloud + Kernel | challenge approval, sandbox, destructive-command rules |
| 4 | Long-term memory poisoning | Edge + Cloud | memory-write masking, re-entry classification, cross-session accumulation |
| 5 | Direct prompt injection | Cloud | Prompt L1 jailbreak, session risk accumulation |
| 6 | Sensitive-data exfiltration | Edge | DLP masking, output review, STI egress control |
| 7 | RAG poisoning | Cloud + Edge | retrieved-content classification, egress domain rules |
| 8 | Hallucination & misinformation | — (not solved) | audit evidence chain for accountability only |
| 9 | MCP supply chain | Edge | allowlist blast-radius control (deep scanning is the commercial module) |
| 10 | Unsafe tool implementations | Kernel | eBPF observation + sandbox backstop (does not replace fixing the tool) |

### 1. Indirect prompt injection

**Attack shape.** The user's input is perfectly clean; the attack
instructions live inside web pages, emails, issues or announcements the
agent reads. The agent executes them as instructions using the permissions
already present in the session — sending mail, creating public pages,
carrying data out.

**Real incidents**
- **EchoLeak / CVE-2025-32711 (2025)** — Microsoft 365 Copilot: a crafted
  email needed no click to make Copilot carry org-internal mail and documents
  across the trust boundary. CVSS 9.3; fixed server-side by Microsoft.
  [Checkmarx write-up](https://checkmarx.com/zero-post/echoleak-cve-2025-32711-show-us-that-ai-security-is-challenging/)
- **GitHub MCP (Invariant Labs, 2025-05)** — a malicious issue hijacked the
  agent into reading private repos and writing to a public PR with the same
  token. [Invariant Labs](https://invariantlabs.ai/blog/mcp-github-vulnerability)

**What VirbiusAgent does**
- Cloud Prompt L1: untrusted content passes the classification surface —
  the guard model (10 categories, incl. injection and agent tool misuse) scores it;
- STI taint tracking: tool results are marked untrusted and tracked as they
  flow into subsequent calls and memory;
- Egress convergence: outbound tool arguments go through DLP masking;
  high-risk actions escalate to challenge (human approval).

**Residual risk.** Semantic detection is probabilistic; novel shapes can
slip through. The compensation is layering (allowlist narrowing + approval +
audit) — not a claim of 100% at any single point.

### 2. Privilege escalation

**Attack shape.** Tool arguments (user IDs, account numbers, ticket IDs)
are filled in by the agent; the application never re-binds them to the
session identity. "Only query the current user" in the system prompt does
not survive rephrasing, nor forged tool results. Same class as web IDOR —
except the parameters are written by a model.

**Real incidents**
- **GitHub MCP (Invariant Labs, 2025-05)** — intent was public repos; the
  agent used the same token against private repos and wrote outward. The
  permission boundary never tightened to the task.

**What VirbiusAgent does**
- Edge tool allowlist + license scope: token scope and allowlist decide
  "this app cannot touch these tools at all";
- Argument-level escalation is **business semantics**: Groovy L3 rules can
  enforce constraints like `args.user_id == ctx.user_id` against your own
  permission model, hot-reloaded;
- decision trace records who approved what, with which arguments —
  accountability after the fact.

**Residual risk.** The platform does not ship your permission model —
argument-level checks are rules you write (by design: only the business
knows its own authorization semantics).

### 3. Excessive autonomy

**Attack shape.** Freeze, approval and isolation exist only in the prompt.
The agent acknowledges them and still drops the database, sends the mail,
changes the config. No external attacker required: high-risk tools lack a
**gate independent of the model**.

**Real incidents**
- **Replit Agent (2025-07)** — an explicit code-freeze request was followed
  by deletion of the production database (~1200+ executives, 1190+ companies
  affected), with a false "cannot roll back" claim. Replit subsequently
  isolated dev/prod by default and added a planning mode.
  [DEV write-up](https://dev.to/ramdai_bista/replits-ai-agent-deleted-a-production-database-during-a-code-freeze-then-lied-about-the-rollback-59m1)

**What VirbiusAgent does**
- challenge approval flow: engine challenges → console approval →
  one-time token-gated execution (strong mode additionally requires argument
  match, so approving A never executes B);
- kernel sandbox: Landlock (path restriction) / gVisor (userspace kernel)
  backstop destructive actions at the syscall level;
- the default rule `cloud_destructive_command_deny` (`rm -rf /`, `mkfs`,
  raw-disk writes, fork bombs…) ships enabled.

### 4. Long-term memory poisoning

**Attack shape.** "Working conventions" planted in calendar invites, pages
and tickets get written into the agent's long-term memory. Later sessions
ask perfectly normal questions; the recalled memory steers the agent into
exfiltration. Persisted content usually carries no jailbreak boilerplate,
so direct-injection detectors miss it.

**Real incidents**
- **Invitation Is All You Need (2025, Black Hat / DEF CON)** — indirect
  injection in Google Calendar invites drove Gemini for Workspace actions;
  Google acknowledged it and added confirmations.
  [The Register](https://www.theregister.com/2025/08/08/infosec_hounds_spot_prompt_injection/)
- **ChatGPT Memories / SpAIware (Rehberger, 2024)** — untrusted documents
  induced persistent instructions; later sessions kept exfiltrating. Write
  once, fire many times.

**What VirbiusAgent does**
- memory interceptor: content written to agent memory is masked first;
  recalled memory and retrieved content re-enter the classification surface
  (to the model, they are inputs too);
- cross-session risk accumulation: repeated low-severity signals from the
  same entity escalate.

**Residual risk.** Host-product memory governance (Google/OpenAI side) is
out of scope — we govern **your agent's** memory write and recall path.

### 5. Direct prompt injection

**Attack shape.** The user demands the agent ignore rules, switch persona,
or unconditionally agree. Damage (false commitments, brand harm) starts
before any tool call; with order/price tools it escalates into excessive
autonomy.

**Real incidents**
- **Chevrolet of Watsonville dealership ChatGPT (2023-12)** — the attacker
  first made the agent agree to anything, then bought a Tahoe for $1; the
  agent confirmed the deal and claimed legal force. AI Incident Database #622.

**What VirbiusAgent does.** Prompt L1 jailbreak classification plus
in-session risk accumulation (repeated attempts raise the score into
blocking), with the challenge gate on high-risk tools as backstop.

### 6. Sensitive-data exfiltration

**Attack shape.** Two exits are usually conflated — employees pasting source
code into public LLMs (Shadow AI / endpoint DLP), and agents carrying
customer or internal data out through mail/ticketing tool arguments
(tool-chain exfiltration).

**Real incidents**
- **Samsung semiconductor (2023-04)** — employees submitted chip source and
  meeting notes to ChatGPT; the company restricted then banned generative AI
  on company devices.
  [TechCrunch](https://techcrunch.com/2023/05/02/samsung-bans-use-of-generative-ai-tools-like-chatgpt-after-april-internal-data-leak/)

**What VirbiusAgent does** (second exit): edge DLP masks arguments before
they leave the client (phone / national ID / email / bank card); tool_result
output review catches credentials and PII coming back; STI taint keeps
untrusted data out of egress tools.

**Honest boundary.** The first exit (employees using public AI themselves)
is endpoint control — explicitly outside VirbiusAgent's scope.

### 7. RAG poisoning

**Attack shape.** Instructions planted in public channels or shared drives
enter the context together with retrieval results. When the user asks
something sensitive, the answer contains attacker-controlled links; one
click and the data leaves. Entry is retrieval; exit can be the answer itself.

**Real incidents**
- **Slack AI (2024-08)** — PromptArmor disclosed disguised instructions in
  public channels being folded into answers with trap links that siphoned
  keys. Slack patched it.
  [PromptArmor](https://www.promptarmor.com/resources/data-exfiltration-from-slack-ai-via-indirect-prompt-injection)

**What VirbiusAgent does.** Retrieved chunks pass the Prompt L1 surface as
inputs; on the output side, DLP/domain rules constrain the egress surface
(which domains and tools may carry data out).

**Residual risk.** Content-level phishing-link recognition (does the page
*look* phishy) is a weak spot — converging the exits beats classifying the pages.

### 8. Hallucination & misinformation

**Attack shape.** The agent confidently states a policy that does not exist;
users act on it and the organization disclaims. Courts have held that an
agent on an official channel is the organization's own window.

**Real incidents**
- **Moffatt v. Air Canada, 2024 BCCRT 149** — the site agent promised a
  bereavement refund the real policy did not allow; the tribunal found the
  agent was part of the website and the airline was liable.
  [Ars Technica](https://arstechnica.com/tech-policy/2024/02/air-canada-must-honor-refund-policy-invented-by-airlines-chatbot/)

**VirbiusAgent's position (honest).** This is a **content-correctness**
problem, not injection or escalation — a runtime security platform does not
solve it. What it provides is after-the-fact evidence: the full decision
trace and hash-chained audit (when, on what input, which model version
produced the answer) for accountability and review.

### 9. MCP supply-chain attacks

**Attack shape.** The agent reaches tools through MCP; if a tool is
impersonated, tampered, or the client/proxy itself is vulnerable, the call
chain is untrustworthy. Unlike indirect injection, the problem is
**provenance and the install channel**, not the content.

**Real incidents**
- **mcp-remote and other MCP client/proxy CVEs (2025)** — high-severity
  disclosures (including RCE-class) affecting agent channels on developer
  machines; manage them as software supply chain, not as "just plugins".

**What VirbiusAgent does.** The proxy's tool allowlist and multi-upstream
routing lock down which tools and which servers are reachable — when
something is poisoned, the **blast radius stays narrow** (the attacker
holds a narrowed tool surface); the audit chain records which upstream was
actually called.

**Honest boundary.** Deep static review of MCP packages (tool-name
masquerading, argument poisoning, poisoned descriptions) is the commercial
VirbiusEvaluation module; dependency provenance (version pinning, SBOM)
lives outside the platform.

### 10. Unsafe tool implementations

**Attack shape.** The agent turns natural language into queries or system
commands; the backend still concatenates strings — the classic injection
entry point now carries **model output**. An LLM is not automatically a
firewall.

**Real incidents**
- **LangChain GraphCypherQAChain / CVE-2024-7042 (2024)** — NL-to-Cypher
  without parameterization; prompt injection could create/modify/delete graph
  data and read across tenants. [NVD](https://nvd.nist.gov/vuln/detail/CVE-2024-7042)
- **Langflow upload path traversal / CVE-2026-5027 (2026)** — uploaded
  filenames written to disk verbatim; `../` allowed arbitrary-path writes and
  RCE; attacks were observed in honeypots after the patch.
  [NVD](https://nvd.nist.gov/vuln/detail/CVE-2026-5027)

**What VirbiusAgent does.** It does not replace fixing the tool
(parameterization and path validation remain the tool's job) — it provides
depth: rules catch high-risk command shapes; the kernel observes anomalous
file/process/network behavior via eBPF; the sandbox constrains writable
paths.

---

## Part 3 — Technical Q&A

### Q1: How does VirbiusAgent defend at the semantic level?

**Rules and models combined, four semantic lines:**

1. **Prompt L1 (guard model)**: the in-house VirbiusGuard (GLM5.2 teacher
   distilled into a Qwen3Guard student) classifies input as safe or one of
   **10 unsafe categories** (incl. Agent Tool Misuse and Jailbreak, robust
   to multilingual mixed input), mapped to built-in `prompt-*` rules by
   category. Self-hosted in the engine (Ollama/vLLM); replaceable (downloadable from
   HuggingFace / ModelScope).
2. **Custom-criterion rules (judge protocol)**: non-built-in `runtime=prompt`
   rules send your natural-language criteria (criterion + few-shot +
   threshold) to a judge backend for batch scoring (the Jev
   `/v1/systemone` protocol); threshold is tunable 0.05–0.95 and `on_error`
   controls failure behavior. Protocol details:
   [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md).
3. **Groovy L3 terminal adjudication**: merges edge/gateway/kernel/cloud
   signals and cumulative counters into the final `effective_action`
   (allow / deny / challenge), and can call external ML (`mlPredict`).
4. **STI taint + session accumulation**: untrusted tool results tracked
   across turns; repeated low-severity signals escalate within a session.

**Honest boundary.** Semantic detection is probabilistic — false negatives
and false positives exist. That is why the platform ships a full tuning
surface (thresholds, severity downgrades, suppression, dry_run canaries —
see Q8) plus layered defense and human approval as backstop, rather than
promising one model that catches everything.

### Q2: Is deployment expensive? Do I need GPU investments of hundreds of thousands to tens of millions?

**No.** The cost structure:

| Component | Resource need |
|---|---|
| Rules / lists / DLP / counters (edge + gateway) | pure CPU, near-zero marginal cost |
| eBPF / Falco (kernel layer) | reuses node CPU, no model cost |
| VirbiusGuard (Prompt L1) | **Q4 quantized 462MB — runs on Ollama / CPU / Mac** (reported benchmark numbers correspond to f16/bf16; avoid quantization where accuracy matters) |
| Judge backend (optional — only for custom-criterion rules) | consumer GPU or CPU (4B-class model) |
| MySQL / Redis | ordinary middleware sizing |

The minimal stack is one `docker compose up -d` (Redis + engine + control,
SQLite). The engine is stateless and scales horizontally; counters and
lists centralize in Redis. GPU expansion only enters when your traffic
justifies multiple guard-inference instances — and it is opt-in per scene
(low-risk tools take the fast path and never touch a model).

### Q3: How does VirbiusAgent intercept traffic?

Three integration modes, pick or mix (details in [QUICKSTART.md](QUICKSTART.md)):

1. **MCP proxy (sidecar)**: sits between the agent and its MCP tools over
   stdio/SSE — every tool call is on-path; allow/deny/challenge execute
   inside the proxy;
2. **Higress gateway (remote)**: HTTP on-path with a WASM plugin doing rate
   limiting and enforcement — for clusters where sidecars are impractical;
3. **SDK embed (in-process)**: Python/Node SDK prechecks before each call
   (LangChain callback and OpenAI Agents SDK adapters included; any
   callable can be wrapped).

Decision chain: **edge precheck (<1ms) → gateway enforcement (<10ms) →
cloud adjudication (<30ms, excluding LLM inference)**; the kernel layer
observes passively throughout via eBPF, and the sandbox (Landlock/gVisor)
blocks independently at the syscall level — **without the agent's
cooperation**: even a compromised process remains visible and containable.

### Q4: Does deployment add latency to LLM processing?

Layered design; you pay latency per layer:

| Path | End-to-end latency |
|---|---|
| Fast path (low-risk tools) | **< 1ms**, skips gateway and cloud |
| Edge rules / lists / DLP | < 1ms |
| Gateway enforcement | < 10ms |
| Cloud adjudication (excl. LLM inference) | < 30ms |
| Prompt L1 guard (self-hosted in the engine, Ollama/vLLM) | depends on model and hardware; a small model is typically far below a large-model API call — measure for your setup |
| Judge backend (custom criteria) | 5s timeout ceiling; on timeout the rule's `on_error` applies (fail_open / fail_closed — your choice) |

The key design point: **not every request triggers a model**. Tools carry a
risk class; low-risk ones take the fast path; per-scene configuration
decides which tools need deep inspection; guard inference happens only at
the input-classification surface. For latency-critical deployments you can
run rules + lists only (pure sub-millisecond) and enable semantics on
demand.

### Q5: Do specific industries need custom development?

**No platform code changes.** Personalization happens entirely through the
configuration surface:

| Need | Mechanism | Barrier |
|---|---|---|
| Industry keywords / lists | lua-dsl / lists | zero code |
| Industry data-class masking | dlp-dsl | zero code (regex) |
| Business-semantic decisions | Groovy L3 (Java syntax, reads `args`/counters/external models directly) | able to write a Java method |
| Custom criteria | prompt rules (natural-language criterion) | write the criterion clearly |
| External industry models | Groovy `mlPredict()` to your deployed BERT/XGBoost service | you own the model service |

Rules support the full **draft → simulate → dry_run → canary → full**
lifecycle with hot reload (no restarts, rollback available).

**Honest boundary.** Rules must fit business semantics, which requires
someone who knows the business (the price of precision — the platform
lowers it to "write configuration" instead of "change code"). Compliance
mapping (data classification standards, regulatory clauses) is ruled by
your compliance team; the platform supplies the evidence chain (audit,
decision trace, export).

### Q6: What are the "lists" and "cumulative counters" in the console? How do they show up in rules?

Both are **data dependencies of rules** — create the data first, then
reference it by name:

- **Lists (data lists)**: dimensioned static sets, created on the console
  "Lists" page with dimensions `keyword` / `user_id` / `device_id` /
  `ip_cidr` / `var` (logical variable, paired with tool arguments). Example:
  `political_sensitive_list` (dimension `var`, values `person_x`…) — a rule
  referencing the list fires its action on a hit.
- **Cumulative counters**: Redis rolling-window counters, created on the
  "Cumulative" page, e.g. `user_query_1m` (dimension `session_id`, rolling
  1 minute). Rate-limit rules reference them for threshold enforcement.

**Showing up in rules** (built-in defaults are live examples):

| Built-in rule | Data used | Behavior |
|---|---|---|
| `gw_rate_tool_1m` | counter `tool_req_1m` (dimension `var:tool_name`, 1-min rolling) | same tool ≥ 60/min → challenge |
| `rl_rate_user_1h` / `rl_rate_app_1h` | user/app 1h-window counters | over threshold → challenge |

The full walkthrough (create list → create counter → register tool → write
the referencing rule) is the "Prerequisites" section of
[rule-examples.en.md](rule-examples.en.md). List lookups and counter reads
both resolve on Redis (typically sub-millisecond when co-located) — they never slow the decision path.

### Q7: How are rules configured? What skills are needed?

Choose by layer; the barrier rises with power (selection guide in the
[README architecture deep dive](README.md) and [USAGE_GUIDE.md](USAGE_GUIDE.md)):

| Layer | Rule type | Shape | Barrier |
|---|---|---|---|
| Edge | `lua-dsl` | JSON (keywords/lists) | zero code |
| Edge | `dlp-dsl` | JSON (PII regex) | zero code |
| Gateway | `lua` | Lua function `decide(ctx)` | scripting |
| Cloud | `prompt` | natural-language criterion | write the criterion |
| Cloud | `groovy` | Groovy `decide(ctx)` | able to write Java |
| Kernel | `falco` | JSON condition | eBPF field names |

The whole flow lives in the console: **create → simulate (instant hit check)
→ dry_run (record only) → canary → full**, versioned and rollbackable.
**Twelve complete rule configurations** (incl. approval modes, tool
registration, before/after) are in [rule-examples.en.md](rule-examples.en.md).

**Honest boundary.** The low barrier is "produce a working rule"; achieving
precise recall/FP balance requires business understanding and iteration via
dry_run/simulate. The platform drives the iteration cost toward zero — it
does not make the judgment for you.

### Q8: What about false positives?

Three tiers — an FP never means "blocked, deal with it":

1. **Before rollout**: simulation at rule creation (feed samples, see hits
   immediately); judge-rule thresholds tunable 0.05–0.95;
2. **During rollout**: dry_run records without blocking — observe real hit
   distribution before widening; canary limits the blast radius;
3. **In production**: severity downgrades (block → warn), context
   downgrades (auto-relax in docs/tests), and suppression (precisely pin
   adjudicated FPs so re-scans stay quiet).

FP samples flow back as few-shot examples on judge rules (rules carry an
`examples` field) — accuracy improves with use.

### Q9: Where does data land? Is the audit trustworthy?

- Decisions and audit data land in Redis (event streams, counters) and
  MySQL/SQLite (rules, traces, events) — **on your own infrastructure**, no
  cloud call-home;
- the audit log carries a **SHA-256 hash chain**: each record chains to the
  previous one; the console "Audit Integrity" page verifies it at any time —
  tampering is detectable;
- evidence is masked first: only DLP-masked content enters evidence and
  reports (data minimization);
- a single `trace_id` correlates edge/gateway/kernel/cloud events for
  one-pull session-level review.

### Q10: Which models / inference backends are supported?

Three kinds, for three different jobs:

| Purpose | Built-in default | Replaceable? License |
|---|---|---|
| **Prompt L1 input classification (guard)** | VirbiusGuard, default GGUF `i1see1you/virbiusguard:v2.0`; the engine calls it over OpenAI-compatible `/v1/chat/completions`, defaulting to a local Ollama (`127.0.0.1:11434`) | Any Ollama / vLLM / OpenAI-compatible server: set `virbius.prompt-llm.base-url`, `model`, `api-path`. The Helm chart's in-cluster Ollama downloads the GGUF (V2.0 F16, ~1.5GB) or uses ModelScope; with in-cluster Ollama disabled you must set `base-url` explicitly. `system-prompt` and `category-rule-mapping` are overridable too |
| **Custom-criterion rules (judge)** | `neohorse-4b` (Apache-2.0, default; 8 questions per forward, ~1.0s on a T4) | `startlux-4b` (**CC-BY-NC-4.0, non-commercial only**, one forward per question), `laya` (Apache-2.0, 322M multilingual mmBERT, internal canary, disabled by default). Protocol is Jev `/v1/systemone`; per-backend `base-url` / `model` / `auth-token` — see [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md) |
| **Your own business models** | none built in | Groovy L3 `mlPredict()` calls your deployed BERT / XGBoost service |

Key distinction: the ten built-in `prompt-*` rules use the **guard**; only **custom** `runtime=prompt` rules touch a **judge** backend. Judge licenses differ (startlux is non-commercial) — check the protocol doc before production use.

### Q11: How are non-MCP HTTP APIs covered?

**Out of the box, the coverage is MCP `tools/call` over HTTP.** Gateway mode (Mode 2, Higress/Envoy WASM, AUTHN phase) only intercepts requests whose path contains `/mcp/` or `/tools/call`; everything else is **forwarded transparently**. On a match:

```
tool allowlist (local) → expression rules → Redis rate limit (per tool+session)
→ low-risk fast path → on-demand engine /v1/evaluate → HTTP 403 on deny
```

`fail-open` / `fail-closed` is configurable; challenge approval tokens are verified at this layer too.

**Arbitrary business HTTP APIs** (REST/GraphQL…) get no semantic interception by default. Three ways to cover them:

1. wrap the capability as an **MCP tool** and go through the proxy/gateway (recommended — no protocol change to the business);
2. **SDK embed** (Mode 3) for in-process precheck;
3. **custom mapping** at the ingress: parse the request, then call engine `/v1/evaluate` with role `input`/`output`/`tool_call` — a custom integration, not a config switch.

**Honest boundary.** There is no one-click proxy for deep content inspection of non-MCP protocols; the gateway enforces the MCP surface — do not expect it to audit all REST traffic as a side effect.

### Q12: Versioning and compatibility policy?

- **Versioning**: SemVer (CHANGELOG follows Keep a Changelog), currently the 1.0.x line; artifacts ship on `v*` tags, and GHCR images should be pinned to a concrete version (e.g. `:v1.0.3`).
- **Compatibility contract** ([API_REFERENCE.md](API_REFERENCE.md)) has three tiers:

| Tier | Scope | Guarantee |
|---|---|---|
| **Public stable (GA)** | engine `/v1/evaluate*`, `/v1/memory/check`, `/v1/challenge/verify`; control `/api/v1/admin/**`, `/api/v1/challenges/**`; auth `/login`, `/oauth/token`, JWKS | **no breaking change within a major version**; breaking changes require a new versioned path (e.g. `/v2`) plus a deprecation announced one minor release ahead |
| **Component contract** | control `/api/v1/edge/**`, `/api/v1/gateway/**`, `/api/v1/tenants/**` | inter-component contract: **all components must run the same release** (upgrade the whole stack together) |
| **Internal / ops** | `control /api/v1/internal/**`, `engine /api/internal/**`, `/admin/**` | no guarantee; third parties must not depend on these |

- Deprecations are announced under the release's `Deprecated` heading in CHANGELOG; protocol-level breaking changes (e.g. the prompt-judge unification on Jev `/v1/systemone`) are marked **BREAKING** there, and protocol docs are versioned separately (`docs/judge-protocol-v1.md`).

---

## Part 4 — Reading index

| Want to know | Read |
|---|---|
| Positioning & quick start | [README.md](README.md) · [QUICKSTART.md](QUICKSTART.md) |
| Layered architecture & rule selection | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Install / integrate / author rules / operate | [USAGE_GUIDE.md](USAGE_GUIDE.md) |
| Twelve complete rule examples | [rule-examples.en.md](rule-examples.en.md) |
| Deployment topology & production parameters | [DEPLOYMENT.md](DEPLOYMENT.md) |
| MCP proxy protocol & configuration | [PROTOCOL.md](PROTOCOL.md) · [PROXY_CONFIG.md](PROXY_CONFIG.md) |
| Judge backend protocol | [docs/judge-protocol-v1.md](docs/judge-protocol-v1.md) |
| Comparison with peers | [docs/COMPARISON.md](docs/COMPARISON.md) |
| Changelog | [CHANGELOG.md](CHANGELOG.md) |
