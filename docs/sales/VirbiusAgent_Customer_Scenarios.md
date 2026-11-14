# AI Agent Security Incident Evidence Base and Customer Scenarios

**Structured incident library, standards mapping, and verifiable controls · VirbiusAgent customer material**

| Item | Content |
|---|---|
| Version | v1.2 (2026-11-13) |
| Data snapshot | `ai-agent-incidents` v1.2.0 (2026-10-28), 88 records; 6 AIID entries reviewed (1152 / 1178 / 1424 / 1469 / 1542 / 1604), the rest not reviewed |
| Intended use | White paper "problem background" chapter, pre-sales PPT appendix, PoC scenario design, customer risk register |
| Not suitable for | Landing-page hero copy, cold-email subject lines, elevator pitches: incident fear as an opener reads as scareware |
| Core principle | **Every covered or partially covered incident must be immediately followed by "corresponding control → layer → a test the customer can run themselves"**; out-of-scope incidents are listed separately as boundaries, never as disaster displays |
| Source grades | A = vendor/research primary disclosure or official vulnerability advisory (including NVD/GHSA/OSV); B = structured incident archive such as AIID; C = media report. The main tables mark the source grade after each event link; grade C is used only to locate an event and never alone supports a control conclusion |

---

## 0. Executive Summary and Usage Rules

### 0.1 Executive summary

- Of the 88 public incidents, 57 (64.8%) map to at least one OWASP Agentic Top 10 class and 31 (35.2%) are unmapped; 26 map to two or more classes.
- Attack chains are concentrated in "untrusted content → model decision → privileged tool": 25 of the 26 ASI01 events enter via `indirect-injection`; all 17 ASI05 events hold `shell/exec` authority.
- ASI01 / ASI02 / ASI05 are the main battlefield. For ASI04, promise only "limit what a poisoned tool can actually do", never "prevent supply-chain poisoning". ASI08–ASI09 have no publicly verifiable samples, and ASI07 has only one sample; the architecture does not cover inter-agent communication. All three (ASI07–ASI09) are explicitly out of scope.
- Customer scenarios should be selected from R&D coding, IT operations, financial customer service, data analytics, and third-party MCP integration. Multi-agent orchestration is out of product scope and should be stated before the PoC.
- Strongest quantitative evidence: AgentDojo v1.2.2 (949 pairs) ASR 1.58% → 0% (fixed protocol, single model, fixed attack set); WildGuardTest recall 71.88% (public set); the Q4 quantized build did not pass the same gates, so use the f16/bf16 numbers.

### 0.2 What this document solves

Most incident lists are selected by media popularity. They are useful for storytelling but unsuitable for customer material. This document is organized around **evidence verifiability**: every event has a stable identifier (dataset id / AIID / CVE / ASI / ATLAS), a primary or structured source link, and a consistent coding schema. Each entry states **whether VirbiusAgent can catch it and, where it cannot, what exactly is out of scope**.

### 0.3 Usage rules

1. **Prioritize structured sources and primary disclosure.** Media reports are used only to locate an event and must be marked C. If a reader checks a number and it does not match, the most valuable asset in this category—credibility—is lost.
2. **Do not mock vendors by name.** The point is architectural necessity: the model is both a protected object and an attack vector, so security controls must exist **independently of the model**.
3. **State out-of-scope items proactively.** The "Explicitly out of scope" table in §3.6 is the most persuasive section; it prevents both pre-sales misunderstanding and later disputes.
4. **Every number must trace back to a dataset id / AIID / CVE** and carry the snapshot version. Do not use unverifiable secondary ratios.

> **Sampling caveat (must travel with the data)**: The 88 events are a **convenience sample of public disclosures**, not a population sample. No proportion may be phrased as "X% of agent incidents are…". Dataset dates are **public report months**, not occurrence dates; mappings are the maintainer's reading of primary sources; an empty mapping means "no confident mapping", not "not applicable". AIID titles often contain *Reportedly*; keep "reportedly" in citations.

---

## 1. Sources and Licensing

| Source | Grade | Stable identifier | Coverage / content | License and attribution | Role in customer material |
|---|---|---|---|---|---|
| **[ai-agent-incidents](https://github.com/basitalisandhu/ai-agent-incidents)** v1.2.0 | B | `id` 001–088 | 88 records (29 incidents, 48 vulnerability disclosures, 11 threat reports), 2023-02 → 2026-09. One schema-validated JSON per event, with eight coded fields, OWASP LLM / Agentic / ATLAS mappings, CVE, and source links | **CC BY 4.0, commercial use allowed**: attribution + link to the repository + note whether changes were made. Code is MIT. Citation format in `CITATION.cff` | **Primary data table**. JSON / JSONL / CSV / RSS / searchable site / Hugging Face mirror |
| **[AI Incident Database](https://incidentdatabase.ai/)** (AIID) | B | `Incident NNNN`, `incidentdatabase.ai/cite/NNNN/` | Long-term archive of all AI incidents; this round reviewed 1152 / 1178 / 1424 / 1469 / 1542 / 1604; the rest not reviewed | **CC BY-SA (Attribution-ShareAlike)**. Each page has a *Suggested Citation Format*. **Do not copy incident descriptions wholesale into commercial material** | **Identifier and cross-reference only**: cite "AIID Incident 1152" with the page's suggested format; do not reproduce the body text |
| **[OWASP Top 10 for Agentic Applications 2026](https://genai.owasp.org/resource/owasp-top-10-for-agentic-applications-for-2026/)** | A | `ASI01`–`ASI10` | Published 2025-12-09, peer-reviewed by 100+ experts; the first standard taxonomy for autonomous agents | Cite the original document; use official names | **Risk skeleton** (§2); CISO readers know these IDs |
| **[MITRE ATLAS](https://atlas.mitre.org/)** | A | `AML.Txxxx` techniques, `AML.CS00xx` cases | Each dataset record carries `mitre_atlas` technique IDs; publicly cited case IDs are listed in §8-C | Cite the ATLAS site; verify case IDs on the site before formal citation | **Threat-modeling language**, anchoring incidents to a framework buyers know |
| **[NVD](https://nvd.nist.gov/) / [GitHub Advisories](https://github.com/advisories) / [OSV](https://osv.dev/)** | A | `CVE-YYYY-NNNNN` / `GHSA-xxxx` | CVE lookup is the most accurate | Public | **Vulnerability verifiability**: readers can check status and fixed versions |
| Vendor / research primary disclosure | A | URL | AWS Security Bulletin, MSRC, Aim Security, Invariant Labs, Brave, OpenAI, Hugging Face, CERT/CC, GitHub Advisory, etc. | Per-site terms | **End point for fact verification**. Blogs and portals are used only to find localized phrasing, never as the factual source |
| Media report | C | URL | The Register, The Hacker News, etc. | Per-site terms; mark as "media report" | **Event location only**; a formal white paper should trace back to grade A or B |

**Version-difference note**: The dataset README says 51 events map to Agentic Top 10, while the generated `docs/owasp-agentic-crosstab.md` gives **57**. The former is an early seed count. This document uses the full crosstab result and labels the `v1.2.0 / 2026-10-28 snapshot`. The same applies to field statistics: this document uses `stats.json`, not the README prose counts (whose lens / adversarial totals are only 80).

---

## 2. Standard Skeleton: OWASP Agentic Top 10 2026 × Incident Statistics

Of the 88 records, **57 (64.8%)** map to at least one ASI class, **31 (35.2%)** are unmapped, and **26** map to two or more classes. The table below comes from the dataset's generated crosstab and is suitable for a white-paper appendix or pre-sales table.

| ID | Risk | Sample count | Main vector | Main authority | Main channel_out | Control recommendation (summarized here) |
|---|---|---:|---|---|---|---|
| ASI01 | Agent Goal Hijack | **26** | `indirect-injection` 25 | `read-only` 12 / `shell/exec` 7 | `tool-call send` 10 / `image/link fetch` 9 | Treat untrusted content as data; require confirmation for goal changes |
| ASI02 | Tool Misuse and Exploitation | **16** | `indirect-injection` 10 / `excessive-agency` 4 | `send-message` 5 / `shell/exec` 3 | `tool-call send` 9 / `data destruction` 3 | Least tool privilege, parameter allowlists, approval for destructive actions |
| ASI03 | Identity and Privilege Abuse | **7** | `indirect-injection` 3 / `exploitation` 2 | `shell/exec` 3 / `database` 2 | `code exec` 3 / `tool-call send` 3 | Per-agent identity, short-lived credentials, scoped authorization |
| ASI04 | Agentic Supply Chain Vulnerabilities | **12** | `supply-chain` 5 / `poisoning` 3 | `shell/exec` 7 | `code exec` 6 / `tool-call send` 3 | Pin versions, AIBOM, isolate third-party connectors |
| ASI05 | Unexpected Code Execution | **17** | `indirect-injection` 8 / `exploitation` 4 | **`shell/exec` 17 (all)** | `code exec` 15 | Always sandbox execution; never allow direct shell access |
| ASI06 | Memory and Context Poisoning | **4** | `retrieval-memory` 2 | `read-only` 3 | `image/link fetch` 3 | Validate writes, tag provenance, expire and audit |
| ASI07 | Insecure Inter-Agent Communication | **1** | `indirect-injection` 1 | `database` 1 | `tool-call send` 1 | Channel authentication and message signing |
| ASI08 | Cascading Agent Failures | **0** | — | — | — | Circuit breakers, blast-radius limits, independent validation between stages |
| ASI09 | Human-Agent Trust Exploitation | **0** | — | — | — | Action previews, friction on irreversible steps, out-of-band confirmation |
| ASI10 | Rogue Agents | **5** | `autonomous-ops` 4 | `shell/exec` 4 | `code exec` 3 / `file publish` 2 | Hard guardrails outside the model, tamper-evident audit, kill switch |

> **Reading note**: Sample counts are **mapping counts**, not distinct incidents; the total of 88 is not 88 independent events. Because 26 events map to two or more classes, the class counts exceed the 57 events that map to at least one class.

**This set of numbers supports an architectural judgment**:

- 25 of the 26 goal-hijack events enter via `indirect-injection`; all 17 code-execution events carry `shell/exec` authority. The attack chain is "untrusted content → model decision → privileged tool", not a single point.
- High-frequency co-occurrence: **ASI01+ASI02 = 9**, ASI01+ASI05 = 6, ASI02+ASI03 = 4, ASI04+ASI05 = 3. Blocking only the entrance or only the exit leaves the chain alive → this is consistent with the "edge–gateway–kernel–cloud" four-layer design hypothesis, but whether all four layers are necessary still requires customer PoC validation.
- **ASI08 / ASI09 have 0 public samples.** The framework has entries, but the sample has none. Do not force a case. Saying "no publicly verifiable sample" in customer material is more verifiable than inventing one.

---

## 3. Core Evidence Table: Incident → Standard → Control → Verifiable Test

**Reading the tables**: `Attack chain` follows `channel_in → authority → channel_out` exactly; `Control` is prefixed with `[Edge] [Gateway] [Kernel] [Cloud]` to mark the layer; `Source grade` A/B/C follows the event link; `†` marks a third-party SaaS/desktop agent case that applies only when the customer reproduces the same tool chain as a self-developed/self-hosted agent integrated with VirbiusAgent, and does not mean VirbiusAgent can intercept that third-party product itself; LLM-level entry/output review for such a client is a separate deployment path via the companion project VirbiusLLM (see §3.6 and §4), not a VirbiusAgent capability; `‡` marks an ATLAS case ID from a public secondary reference that must be verified on the ATLAS site before formal citation. Verdicts: **Covered** = a corresponding capability exists and can be demonstrated with a V suite; **Partial** = conditional or visibility only; **Not covered** = industry background only, no protection claim allowed.

### 3.1 ASI01 Goal Hijack — Entry Side

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 032 | [EchoLeak: M365 Copilot zero-click exfiltration](https://thehackernews.com/2025/06/zero-click-ai-vulnerability-exposes.html) (2025-06) `[C]` | ASI01 · **CVE-2025-32711** · AML.T0051.001 / T0077 | `email` → `read-only` → `image/link fetch`; CVSS 9.3 | [Cloud] output review; [Edge] STI taint tracking (blocks data encoded into a URL) | V1 V4 V5 | Partial† · off-the-shelf SaaS cannot be inserted; covers the server-side chain only for a self-developed email/document agent |
| 030 | [GitHub MCP toxic agent flow leaks private repos](https://invariantlabs.ai/blog/mcp-github-vulnerability) (2025-05) `[A]` | ASI01 / ASI02 / ASI03 · AML.T0051.001 / T0093 / T0086 | `repo issue/pr` → `write-repo` → `tool-call send` | [Gateway] MCP security proxy + tool allowlist; [Cloud] high-risk approval + decision-chain audit; [Edge] STI | V2 V3 V4 V8 | Covered · closest fit: a malicious issue and an over-broad token share one session |
| 037 | [Supabase MCP support-ticket injection leaks private tables](https://generalanalysis.com/blog/supabase-mcp-blog) (2025-07) `[A]` | ASI01 / ASI02 / ASI03 · AML.T0051.001 / T0093 / T0086 | `support ticket` → `database` → `tool-call send` | [Edge] tool-parameter DLP; [Cloud] output review; [Gateway] unauthorized tool-call blocking | V2 V4 V5 | Covered · demo data, not production loss; label as "PoC" |
| 005 | [ChatGPT plugin cross-plugin request forgery](https://embracethered.com/blog/posts/2023/chatgpt-cross-plugin-request-forgery-and-prompt-injection./) (2023-05) `[A]` | ASI01 / ASI02 · AML.T0051.001 / T0053 / T0086 | `web page` → `send-message` → `tool-call send` | [Edge] allowlist limits callable tools and parameters | V2 V4 | Covered · self-developed plugin/agent integration |
| 008 | [Google Bard exfiltration via image rendering](https://embracethered.com/blog/posts/2023/google-bard-data-exfiltration/) (2023-11) `[A]` | ASI01 · AML.T0051.001 / T0077 | `document` → `read-only` → `image/link fetch` | [Cloud] output review (answer-side link detection) | V5 | Partial† · off-the-shelf SaaS; used as an exfiltration-channel analogy, no protection claim |
| 046 | [ForcedLeak: Salesforce Agentforce](https://noma.security/blog/forcedleak-agent-risks-exposed-in-salesforce-agentforce/) (2025-09) `[A]` | ASI01 · AML.T0051.001 / T0093 / T0077 | `web page` → `read-only` → `image/link fetch`; CVSS 9.4 | [Cloud] output review; [Gateway] external-domain allowlist | V5 V2 | Partial† · off-the-shelf SaaS; the domain allowlist is a transferable control idea |
| 042 | [Gemini for Workspace calendar-invite promptware](https://www.safebreach.com/blog/invitation-is-all-you-need-hacking-gemini/) (2025-08) `[A]` | ASI01 / ASI02 · AML.T0051.001 / T0053 | `calendar invite` → `send-message` → `tool-call send` | [Cloud] high-risk tool approval; [Edge] tool allowlist | V3 V2 | Partial† · `outcome = none-demo` (research demo); **do not describe it as a production incident** |

> The out-of-scope browser-agent case (id 045, Perplexity Comet) is listed in §3.6. It is not in the main table and has no V test.

### 3.2 ASI02 Tool Misuse / ASI03 Identity and Privilege Abuse — Exit Side

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 036 | [Replit Agent deletes SaaStr production database](https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/) (2025-07) `[C]` · AIID 1152 `[B]` | AIID 1152 · ASI02 · LLM06 | `chat message` → `database` → `data destruction` | [Gateway] high-risk human approval (challenge → console → token gate); [Kernel] Landlock/gVisor; [Cloud] Hash Chain audit | V3 V7 V8 V6 | Covered† · a natural-language code freeze is not a technical constraint; applies only when a self-developed/self-hosted agent reproduces the same database tool chain |
| 071 | [PocketOS production database and backups deleted by Cursor Agent](https://www.theregister.com/software/2026/04/27/cursor-opus-agent-snuffs-out-startups-production-database/5224442) (2026-04) `[C]` · AIID 1469 `[B]` | AIID 1469 · ASI02 / ASI03 | `none` → `cloud-creds` → `data destruction` (9 seconds) | [Gateway] approval gate; [Kernel] syscall blocking; [Edge] credential-exfiltration DLP | V3 V7 V6 V4 | Covered† · account-level broad token + destructive API without approval |
| 055 | [Google Antigravity deletes user's D: drive](https://www.theregister.com/2025/12/01/google_antigravity_wipes_d_drive/) (2025-12) `[C]` | ASI02 · LLM06 | `chat message` → `file-delete` → `data destruction` | [Kernel] file write/delete syscall monitoring + sandbox path restriction; [Gateway] approval | V7 V6 V3 | Covered† · self-hosted runtime only; the third-party desktop agent itself is out of scope |
| 039 | [Claude Code DNS exfiltration via allowlisted commands](https://embracethered.com/blog/posts/2025/claude-code-exfiltration-via-dns-requests/) (2025-08) `[A]` | **CVE-2025-55284** · ASI01 / ASI02 | `document` → `shell/exec` → `tool-call send` | [Edge] tighter command allowlist + STI; [Kernel] network-connection observation | V2 V4 V6 | Covered† · an allowlist alone does not block the DNS channel; pair it with egress observation |
| 054 | [ServiceNow Now Assist second-order prompt injection](https://appomni.com/ao-labs/ai-agent-to-agent-discovery-prompt-injection/) (2025-11) `[A]` | ASI01 / ASI03 / **ASI07** · AML.T0051.001 / T0093 / T0086 | `support ticket` → `database` → `tool-call send` | [Edge] tool allowlist; [Cloud] approval (blocks a high-privilege agent driven by low-privilege content) | V2 V3 | Partial† · the dataset's only ASI07 sample; the architecture has no inter-agent authentication, so it can block the action but cannot claim "prevents agent chaining" |
| 087 | [PixelLeak: coding agents push developer screenshots to public repos](https://www.glow.io/blogs/how-ai-agents-exposed-developer-screenshots-from-leading-tech-companies) (2026-09) `[A]` | ASI02 | `chat message` → `write-repo` → `file publish` | [Cloud] output review; [Gateway] outbound-tool approval | V5 V3 | Covered† · self-developed/self-hosted coding agents only |

> **Supplement (AIID cross-references, not part of the 88-record dataset)**: [Incident 1178](https://incidentdatabase.ai/cite/1178/) Gemini CLI deleted user files (2025-07-21), [Incident 1424](https://incidentdatabase.ai/cite/1424/) Claude Code deleted DataTalks.Club production infrastructure via Terraform (2026-02-26), [Incident 1542](https://incidentdatabase.ai/cite/1542/) OpenClaw ignored a stop instruction and tried to delete email (2026-02-23). All three belong to the same "excessive autonomy" class as 036 and can be used in a timeline; cite the AIID ID and link only, and do not reproduce the description body.

### 3.3 ASI05 Unexpected Code Execution and Sandbox Escape — Kernel Layer

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 003 | [LangChain LLMMathChain injection to RCE](https://github.com/langchain-ai/langchain/issues/1026) (2023-04) `[A]` | **CVE-2023-29374** · ASI05 | `chat message` → `shell/exec` → `code exec` | [Kernel] gVisor/Landlock sandbox + execve observation | V7 V6 | Covered |
| 040 | [Cursor CurXecute injection to RCE](https://github.com/cursor/cursor/security/advisories/GHSA-4cxx-hrm3-49rm) (2025-08) `[A]` | **CVE-2025-54135** · ASI01 / ASI05 | `chat message` → `shell/exec` → `code exec` (writes `.cursor/mcp.json` without approval) | [Gateway] approval for config-file writes; [Kernel] file monitoring + sandbox | V3 V6 V7 | Covered† · official GHSA; best for a customer-verifiable PoC |
| 057 | [Anthropic Git MCP three-flaw chain to RCE](https://www.theregister.com/security/2026/01/20/anthropic-quietly-fixed-flaws-in-its-git-mcp-server/4676059) (2026-01) `[C]` | **CVE-2025-68143 / 68144 / 68145** · ASI02 / ASI05 | `document` → `shell/exec` → `code exec` | [Gateway] MCP proxy parameter validation; [Kernel] sandbox + observation | V2 V7 V6 | Covered · CVE verifiable; event narrative is media-sourced |
| 067 | [CrewAI Code Interpreter sandbox escape and SSRF](https://www.kb.cert.org/vuls/id/221883) (2026-03) `[A]` | **CVE-2026-2275 / 2285 / 2286 / 2287** · ASI05 · AML.T0105 | `chat message` → `shell/exec` → `code exec` | [Kernel] eBPF network-connection observation + SSRF rules + sandbox | V6 V7 | Covered · CERT/CC advisory; authoritative source |
| 083 | [DeepSeek Harness sandbox escape](https://www.ox.security/blog/cve-2026-82533-deepseek-harness-ai-agent-sandbox-escape/) (2026-09) `[A]` | **CVE-2026-82533** · ASI05 / ASI03 · AML.T0105 | `chat message` → `shell/exec` → `code exec` (local API trusts the Host header; one curl escalates) | [Kernel] process/network observation; [Gateway] approval gate; [Kernel] sandbox | V7 V6 V3 | Covered · "the sandbox was bypassed" best explains why syscall visibility **independent of the agent process** is required |

> The crosstab shows: **all 17 ASI05 events carry `shell/exec` authority, and 15 exit via `code exec`**. This single line supports "execution must be sandboxed + kernel-level observation" better than any adjective.

### 3.4 ASI04 Supply Chain and Tool Poisoning — Where Wording Matters Most

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 029 | [MCP tool description poisoning](https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks) (2025-04) `[A]` | ASI04 / ASI02 · AML.T0110 / T0051.001 / T0086 | `tool description` → `send-message` → `tool-call send` | [Edge] tool allowlist + parameter DLP + STI; [Cloud] output review | V2 V4 V5 | Covered · closest fit: even if a tool description is poisoned, what it can actually do is limited |
| 081 | [Deadbugz: runtime-gated malicious MCP](https://www.pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign) (2026-08) `[A]` | ASI04 / ASI01 · AML.T0110 / T0010.005 | `tool description` → `read-only` → `tool-call send` (changes metadata after three calls, induces searches for SSH keys / AWS credentials, then hides) | [Gateway] tool metadata-change detection + allowlist; [Edge] parameter DLP | V2 V4 V5 | Covered · tool behavior changes **at runtime**; `none-demo`, labeled "no confirmed victim" |
| 049 | [postmark-mcp malicious MCP server](https://thehackernews.com/2025/09/first-malicious-mcp-server-found.html) (2025-09) `[C]` | ASI04 · AML.CS0053‡ | `package` → `send-message` → `tool-call send` (BCCs all email since 1.0.16) | [Edge] outbound-tool allowlist + recipient-parameter review; [Kernel] network observation | V2 V4 V6 | Partial · pre-install integrity audit is out of scope; reword to "even if a tool is poisoned, limit what it can actually do" |
| 028 | [Rules File Backdoor (Copilot / Cursor)](https://www.pillar.security/blog/new-vulnerability-in-github-copilot-and-cursor-how-hackers-can-weaponize-code-agents) (2025-03) `[A]` | ASI06 / ASI04 · AML.T0081 | `rules file` → `write-repo` → `file publish` | [Cloud] output review; [Kernel] file-write observation | V5 V6 | Partial · pre-commit audit of rules files is out of scope |
| 062 | [ClawHavoc: 341 malicious ClawHub skills](https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html) (2026-02) `[C]` | ASI04 · AML.T0010.005 / T0104 | `package` → `shell/exec` → `code exec` | [Kernel] sandbox isolation + execve/file-access observation | V7 V6 | Partial · skill-market admission is out of scope; limit the actions of installed skills |
| 033 | [Amazon Q extension shipped wiper prompt](https://aws.amazon.com/security/security-bulletins/AWS-2025-015/) (2025-07) `[A]` | **CVE-2025-8217** · ASI04 / ASI05 · **AML.CS0047**‡ | `repo issue/pr` → `shell/exec` → `data destruction` (**not executed due to a payload syntax error**) | [Kernel] destructive-action blocking; [Gateway] approval gate | V7 V3 | Partial† · capability mapping for a similar self-developed agent only; the root cause is CI/CD credentials and code review, not runtime, so no protection claim |
| 044 | [Nx s1ngularity malicious packages hijack AI CLIs](https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c) (2025-08) `[A]` | **CVE-2025-10894** · ASI04 / ASI02 / ASI03 | `package` → `shell/exec` → `code exec` | [Kernel] execve observation; [Edge] credential-exfiltration DLP | V6 V4 | Partial · install/publish supply chain is out of scope; limit the actions of installed tools |

> **Consistent wording**: For ASI04, say only "**limit what a poisoned tool can actually do + make anomalous actions visible and auditable**"; never say "prevent supply-chain poisoning". Pre-install integrity checks, AIBOM, and dependency pinning belong to the customer's SDL, not to VirbiusAgent.

### 3.5 ASI06 Memory and Context Poisoning

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 022 | [Slack AI private-channel exfiltration](https://promptarmor.substack.com/p/data-exfiltration-from-slack-ai-via) (2024-08) `[A]` | ASI06 · AML.T0070 / T0077 | `chat message` → `read-only` → `image/link fetch` | [Cloud] output review (answer-side malicious links); [Edge] memory-write redaction | V5 V4 | Partial† · off-the-shelf SaaS; a self-developed RAG agent covers the answer side |
| 023 | [ChatGPT macOS SpAIware persistent memory exfiltration](https://embracethered.com/blog/posts/2024/chatgpt-macos-app-persistent-data-exfiltration/) (2024-09) `[A]` | ASI06 · AML.T0080.000 / T0077 | `web page` → `read-only` → `image/link fetch` | [Edge] redaction before memory writes + STI; [Cloud] output review | V4 V5 | Partial† · off-the-shelf client; used as a "write once, exploit many times" analogy |

> `FAQ.zh.md` also cites the Gemini calendar-invite case (Black Hat / DEF CON 2025), `CVE-2024-7042` (LangChain GraphCypherQAChain), and `CVE-2026-5027` (Langflow path traversal). The latter two are "insecure tool implementation": the traditional injection entry point becomes model output, and **the tool backend still needs parameterization under the existing security baseline**. VirbiusAgent does not replace secure coding.

### 3.6 ASI10 Rogue Agents and the ASI07/08/09 Boundary

| id | Event (date · source) | Main standards | Attack chain | VirbiusAgent control (layer) | Validation | Verdict |
|---|---|---|---|---|---|---|
| 075 | [OpenAI evaluation agents breach Hugging Face](https://openai.com/index/hugging-face-model-evaluation-security-incident/) (2026-07) `[A]` | **AIID 1604** · ASI10 · **ATLAS AML.CS0068**‡ | `document` → `shell/exec` → `code exec` | [Kernel] file/network syscall visibility; [Gateway] destructive-action gate; [Cloud] tamper-evident audit | V6 V7 V8 | Partial† · only "anomalous behavior visibility", not "prevents multi-agent chaining"; scale numbers come from a secondary summary `[C]` and await official review |
| 074 | [Anthropic evaluation agents breach three organizations](https://www.anthropic.com/news/investigating-incidents-cybersecurity-evals) (2026-07) `[A]` | ASI10 | `none` → `shell/exec` → `code exec` | [Kernel] syscall visibility; [Gateway] gate; [Cloud] audit | V6 | Partial† · only visibility and gates, not "prevents autonomous overreach" |

**Explicitly out of scope (state it, do not hide it)**

| Category | Why | Allowed wording |
|---|---|---|
| ASI07 inter-agent communication | The four-layer architecture has no inter-agent message authentication/signing | "Cross-agent trust chains are outside this product; implement channel authentication in the orchestration layer" |
| ASI08 cascading failures / ASI09 human-agent trust | 0 publicly verifiable samples in the dataset; also a matter of architecture and process governance | "The framework has entries; this material lists no cases" |
| Perplexity Comet indirect injection (id 045) | Browser agent + user's logged-in session; the four-layer architecture cannot reach it | "Industry background only; no protection claim" |
| Off-the-shelf SaaS (M365 Copilot, Slack AI, Gemini, ChatGPT) | The server-side chain is outside customer control | "The same control ideas can be transferred to a self-developed agent" |
| Install/publish supply chain (npm, skill markets, plugin markets) | No pre-install integrity audit, no AIBOM | "Limit the actions of installed tools" |
| CI/CD credentials and code review (Amazon Q, Nx) | Problems before runtime | Industry background |
| Endpoint Shadow AI (Samsung 004, Chevrolet 010, Air Canada 013) | README states endpoint control is not the core positioning; also DLP/product liability | Industry background only; not a protection capability |
| Third-party desktop/IDE agent **tool execution** (Cursor, Claude Code, Continue) | The third-party client executes tools locally; VirbiusAgent only intercepts self-developed/self-hosted agents through the MCP proxy and the kernel layer | "VirbiusAgent does not intercept this path. For **LLM entry/output review only**, route the client's OpenAI-compatible base URL through the companion project [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM); tool execution, approval gates, sandbox, and syscall observation remain out of scope" |

> **Companion-project boundary (must travel with any desktop-agent claim)**: VirbiusLLM ships a productized OpenAI-compatible guardrail proxy (`/v1/chat/completions`, JSON + SSE) that reviews requests before forwarding and reviews model output before returning it. It can review **what the model sees and says**, only when the client allows overriding its OpenAI-compatible base URL. It cannot enforce **what the client then executes** — that stays with VirbiusAgent's MCP/kernel path and only for self-developed/self-hosted agents. Never present the two products as one interception point.

---

## 4. Customer Scenario Mapping

This section answers "given this material, how do I find my scenario?" Start with a scenario, then go back to §3 for the corresponding incidents, controls, and V tests.

| Customer scenario | Typical tool chain | Prevalent risks | Primary controls (layer) | Suggested validation | Boundary |
|---|---|---|---|---|---|
| **Financial customer service / remote banking** | Ticketing, account lookup, transfers/payments, email, knowledge-base retrieval | ASI01 goal hijack, ASI02 tool misuse, ASI03 identity/privilege, ASI06 memory poisoning | [Edge] tool allowlist + parameter DLP + STI; [Gateway] high-risk approval + token gate; [Cloud] output review | V2 V3 V4 V5 | Off-the-shelf customer-service SaaS cannot be inserted at the server side; requires a self-developed/self-hosted agent |
| **R&D coding agent** | Repo read/write, shell, CI/CD, MCP tools, dependency install | ASI04 supply chain, ASI05 code execution, ASI01 goal hijack | [Gateway] MCP proxy + tool allowlist; [Kernel] sandbox + execve/eBPF observation; [Gateway] config-file write approval | V2 V6 V7 V3 | Third-party IDE plugins / Copilot itself are out of scope; pre-install integrity audit is not covered |
| **IT operations agent** | Cloud credentials, databases, release systems, delete/restart/scale | ASI02 tool misuse, ASI03 privilege abuse, ASI10 rogue agents | [Gateway] high-risk approval + token gate; [Kernel] destructive-action blocking + syscall observation; [Cloud] Hash Chain audit | V3 V6 V8 V7 | Broad tokens and short-lived credentials are the customer's IAM responsibility |
| **Data analytics / RAG agent** | Knowledge base, reports, database queries, outbound sharing | ASI01 goal hijack, ASI06 memory/context poisoning | [Cloud] output review; [Edge] STI + memory-write redaction; [Gateway] outbound approval | V4 V5 V2 | Memory provenance governance and retrieval-store permissions are implemented by the customer |
| **Third-party MCP / supply-chain integration** | npm packages, skill markets, plugin markets, remote MCP servers | ASI04 supply chain, ASI02 tool misuse | [Gateway] MCP proxy + tool allowlist + metadata-change detection; [Edge] parameter DLP; [Kernel] network observation | V2 V4 V6 | Pre-install integrity audit, AIBOM, and dependency pinning are out of scope |
| **Desktop/IDE assistant with a custom OpenAI-compatible base URL** | Cursor / Continue and similar clients; tools execute on the developer workstation | ASI01 goal hijack (entry side), ASI06 output leakage | [Companion] VirbiusLLM OpenAI-compatible proxy: input review + output review + QA reply | VirbiusLLM's own suite; **not** part of V1–V8 | Tool execution, approval, sandbox, and syscall observation are out of scope; VirbiusAgent does not intercept the client's local actions |
| **Multi-agent orchestration** | Inter-agent messages, shared memory, task dispatch | ASI07 inter-agent communication, ASI08 cascading failures | Channel authentication / message signing in the orchestration layer | None (not covered) | Explicitly out of product scope; implement in the orchestration layer |

**PoC selection advice**: If no scenario is fixed yet, start with **R&D coding** or **IT operations**: V2/V3/V6/V7 have the most complete evidence chain and do not require business-protocol changes. For financial customer service, first confirm the agent is self-developed/self-hosted; if it is off-the-shelf SaaS, the server-side chain cannot be inserted and only control ideas can be transferred. State the multi-agent orchestration gap before the PoC, not at acceptance.

---

## 5. Coverage Matrix: ASI × Four-Layer Capability

| Risk class | Samples | Edge (precheck/DLP/STI) | Gateway (rate limit/HTTP/approval token) | Kernel (eBPF/Falco/sandbox) | Cloud (LLM detection/L3/audit) | Overall | Boundary and wording |
|---|---:|---|---|---|---|---|---|
| ASI01 Goal hijack | 26 | Partial · parameter DLP / taint | Covered · block post-injection actions | Partial · visibility | Covered · semantic injection detection | **Partial** | Do not promise "100% injection detection"; promise "post-injection privilege abuse is blocked" |
| ASI02 Tool misuse | 16 | Covered · allowlist | Covered · mandatory checkpoint | Covered · destructive-action blocking | Covered · final policy decision | **Covered** | Core capability |
| ASI03 Privilege abuse | 7 | Partial · parameter validation | Covered · token validation | Covered · syscall observation | Partial · cumulative risk | **Partial** | Identity governance (short-lived credentials, separate identities) is the customer's IAM responsibility |
| ASI04 Supply chain | 12 | Covered · limit tool actions | Covered · online blocking | Covered · anomaly visibility | Partial · output review | **Partial** | Do not claim "prevents poisoning"; claim only "limits actions" |
| ASI05 Code execution | 17 | Partial · precheck | Covered · blocking | Covered · Landlock/gVisor + syscall | Partial · semantic | **Covered** | The kernel layer is the main battlefield; suitable for demonstration |
| ASI06 Memory poisoning | 4 | Covered · write redaction | — | — | Covered · output review | **Partial** | Memory provenance governance is implemented by the customer |
| ASI07 Inter-agent communication | 1 | Not covered | Not covered | Partial · visibility only | Not covered | **Not covered** | Explicitly out of scope |
| ASI08 Cascading failures | 0 | Not covered | Partial · rate limiting | Partial · visibility | Partial · cumulative risk | **Not covered** | No cases listed |
| ASI09 Human-agent trust | 0 | Not covered | Partial · approval friction | Not covered | Partial · action preview | **Not covered** | No cases listed |
| ASI10 Rogue agents | 5 | Partial · visibility | Covered · gate | Covered · visibility + blocking | Covered · tamper-evident audit | **Partial** | Visibility and gates only; do not claim "prevents autonomous overreach" |

> **Reading note**: The matrix is inferred from capability, not sample count. ASI08 / ASI09 have 0 samples; "Partial" only means a related capability exists in the architecture and is not a coverage claim. Layer-level "Covered" does not equal risk-class "Overall covered". The density of "Partial" and "Not covered" is the credibility signal: a matrix that states its gaps is easier to verify than an "all covered" matrix.

---

## 6. Reproducible Assets and Validation Suites

**V0 environment setup (prerequisite for local validation)**

```bash
git clone https://github.com/i1see1you/VirbiusAgent.git && cd VirbiusAgent
bash scripts/run-local.sh && bash scripts/smoke-test.sh
# or
docker compose build && docker compose up -d
curl -s http://localhost:8080/api/v1/health
docker compose --profile full up -d   # includes the MCP proxy; requires a License and upstream service
```

| Suite | Validation the customer can execute | Capability | Where to see results |
|---|---|---|---|
| V1 | Send injection samples to the VirbiusGuard online demo; reproduce public datasets with `scripts/benchmarks/` | Cloud prompt-injection detection + dynamic risk scoring | [Online demo](https://www.virbius.cn/demo), evaluation-script output |
| V2 | Connect a self-developed agent to the MCP security proxy (stdio/SSE), configure an allowlist, and issue an unauthorized tool call | Edge tool precheck + Gateway HTTP blocking | Console "rule configuration and rollout", alert list |
| V3 | Construct delete/transfer/outbound tool calls; verify that no approval means no execution, and that a token allows execution after approval | High-risk human approval challenge → token gate | Approval queue, decision BOM |
| V4 | Make a tool return PII/credentials, then have the agent place it into the next outbound tool parameter | STI taint tracking + parameter DLP | Session and trace, taint markers |
| V5 | Put PII, keys, or external links in tool returns/answers; check whether review blocks them | Output review + review API / `virbius-review-sdk` | Alerts, disposition returned by the review SDK |
| V6 | Canary-roll out a Falco rule that triggers file writes / network connections / process creation | Kernel eBPF syscall observation | Kernel events with `trace_id` in the session timeline |
| V7 | Have a high-risk tool write outside its sandbox / spawn a child process; verify syscall-level blocking | Landlock / gVisor sandbox isolation | Kernel alerts + audit events |
| V8 | Export a session audit and verify integrity | Hash Chain (SHA-256) audit log, decision BOM, session checkpoints, audit export | Console "Audit Integrity" (4 tabs) |

**Prerequisites**: V1 can use the online demo only; V2 requires an MCP proxy License and upstream service; V6 / V7 require Linux + eBPF / Landlock (usually unavailable natively on macOS; use a Linux host or K8s); V8 requires the console audit page. Put these in the PoC plan so environment mismatches are not discovered at acceptance.

**Benchmark protocol (must be preserved verbatim in external material; do not remove the limitations)**

| Dataset | n | Result |
|---|---:|---|
| gold_1000 (internal, Chinese/English) | 1,000 | Accuracy 97.9% / recall 97.89% / FP 1.82% |
| WildGuardTest (public) | 1,699 | Recall **71.88%** / FP 4.52% |
| WildGuardTest adversarial slice | 458 | Precision 95.73% / recall **59.24%** |
| XSTest (public) | 450 | 15 over-refusals / 5 misses |
| ToxicChat 1k slice | 1,000 | 84 false positives / jailbreak recall 90.7% |
| AgentDojo v1.2.2 (949 pairs) | 949 | Attack success rate **1.58% → 0%**; utility 86.62% → 90.20% |

Limitations (already stated in the README; copy them into external material): fixed audit prompt, greedy decoding, parse failures counted as misses. The AgentDojo agent is `deepseek-flash`, which is relatively injection-resistant; baseline ASR is only 1.58%, and the conclusion **applies only to that setting** (single model, fixed attack set, 949 pairs). **The Q4 quantized build did not pass the same gates** (WildGuardTest FPR 5.2%, XSTest 18 over-refusals); use f16/bf16 numbers. Stating these limitations is more verifiable than quoting a single "99%".

**Boundaries to confirm before delivery**

- `FAQ-agent-scenarios.md` was replaced by `FAQ.md` / `FAQ.zh.md` on 2026-11-13; Q1–Q12 now have answers, and the old file is deleted. Old references are no longer valid.
- SSO / OAuth2-OIDC is marked "design finalized, not implemented"; VirbiusEvaluation is a separately licensed closed-source evaluation module (the open-source repo neither contains nor depends on it; it integrates via an independent image + compose overlay, and the console entry is hidden by default). State both honestly when relevant.
- Product positioning is a **server-side guardrail for self-developed agents**; endpoint DLP / Shadow AI is out of core scope, and policies must be configured by engineers who understand both security and the business. Do not describe it as "out of the box".
- The MCP proxy requires a License; the kernel layer requires Linux / eBPF / Landlock. These are PoC prerequisites, not out-of-the-box capabilities.
- Third-party desktop/IDE agents (Cursor, Claude Code, Continue) are **not** intercepted by VirbiusAgent. If the customer only needs LLM entry/output review and the client supports overriding its OpenAI-compatible base URL, point it at the companion project [VirbiusLLM](https://github.com/i1see1you/VirbiusLLM) (independent deployment, MIT). Do not describe this as VirbiusAgent coverage; tool execution, approval, sandbox, and syscall observation remain out of scope.

---

## 7. Citation Norms and Wording

**Attribution template (dataset, CC BY 4.0)**

> Muhammad Basit Ali, *AI Agent Incidents: an open dataset of publicly documented AI-agent and LLM-application security incidents*, v1.2.0 (2026), https://github.com/basitalisandhu/ai-agent-incidents. Data licensed CC BY 4.0; this document adds mappings and control columns. When using the coding scheme, also cite Ali (2026), *AI as Weapon, Target, and Surface: A Threat Taxonomy and a Deterministic Control Plane for Securing LLM Agents*.

**AIID (CC BY-SA)**: cite only the ID and link, and use each `/cite/NNNN/` page's *Suggested Citation Format* (editor, access date). **Do not reproduce the incident description body** in commercial material, to avoid ShareAlike contagion. Example:

> Atherton, D. (2025, July 21). *Incident 1152: LLM-Driven Replit Agent Reportedly Executed Unauthorized Destructive Commands During Code Freeze, Leading to Loss of Production Data*. AI Incident Database. https://incidentdatabase.ai/cite/1152/ (accessed 2026-11-13).

**Source-grade rules**: cite grade A directly; use grade B for identifiers, timelines, and cross-references; use grade C only to locate an event, and add a grade A primary source for a formal white paper. For a grade C event that also has a CVE / GHSA / AIID ID, the vulnerability facts follow the official ID; the media source only carries the narrative.

| Prohibited wording | Recommended wording |
|---|---|
| "A WeChat account reported that a major vendor's agent deleted a database" | "According to AIID Incident 1152 / dataset id 036, the Replit Agent deleted a production database in 2025-07" |
| "We prevent supply-chain poisoning" | "Even if a tool is poisoned, we limit what it can actually do" |
| "Even OpenAI failed; we are safer" | "The model is both a protected object and an attack vector, so controls must be independent of the model" |
| "99% interception, full OWASP Top 10 coverage" | "AgentDojo, 949 pairs, single model, fixed attack set: no successful attack observed (baseline ASR 1.58%); ASI07–ASI09 are out of scope" |
| "A zero-click vulnerability caused a massive data breach" | "EchoLeak, CVE-2025-32711; Microsoft says no exploitation in the wild was found, and the service was patched" |
| Presenting a `none-demo` PoC as a production incident | Label "research demo / no confirmed victim" |
| Presenting a third-party SaaS/desktop agent case as "we protect that product" | Mark `†` and limit to "after a self-developed/self-hosted agent is integrated" |
| "VirbiusAgent protects Cursor / Claude Code / Continue" | "For LLM entry/output review only, route the client's OpenAI-compatible base URL through the companion project VirbiusLLM; VirbiusAgent does not intercept third-party clients' tool execution" |

**Structure advice**: Start from one scenario in §4, then expand by the ten ASI classes in §2. For each class, attach "one real case + one capability + one test the customer can run themselves"; always end with "Explicitly out of scope". Do not organize by incident, and do not put out-of-scope cases in the main tables.

---

## 8. Appendices

### A. Primary Source List (by dataset id, for customer verification)

Source grade: A = vendor/research primary or official vulnerability advisory (including NVD/GHSA/OSV); B = structured incident archive such as AIID; C = media report. A formal white paper should trace grade C back to grade A; where A/B is already available, prefer A/B.

| id | Grade | Type | URL |
|---|---|---|---|
| 003 | A | Official issue | https://github.com/langchain-ai/langchain/issues/1026 |
| 005 / 008 / 023 / 039 | A | Researcher disclosure (Embrace The Red) | https://embracethered.com/blog/posts/2023/chatgpt-cross-plugin-request-forgery-and-prompt-injection./ ｜ https://embracethered.com/blog/posts/2023/google-bard-data-exfiltration/ ｜ https://embracethered.com/blog/posts/2024/chatgpt-macos-app-persistent-data-exfiltration/ ｜ https://embracethered.com/blog/posts/2025/claude-code-exfiltration-via-dns-requests/ |
| 022 | A | Researcher disclosure (PromptArmor) | https://promptarmor.substack.com/p/data-exfiltration-from-slack-ai-via |
| 028 / 081 | A | Researcher disclosure (Pillar) | https://www.pillar.security/blog/new-vulnerability-in-github-copilot-and-cursor-how-hackers-can-weaponize-code-agents ｜ https://www.pillar.security/blog/deadbugz-currently-active-mcp-supply-chain-campaign |
| 029 / 030 | A | Researcher disclosure (Invariant Labs) | https://invariantlabs.ai/blog/mcp-security-notification-tool-poisoning-attacks ｜ https://invariantlabs.ai/blog/mcp-github-vulnerability |
| 032 / 049 / 062 | C | Media report (trace to MSRC / Aim Labs / Koi Research originals) | https://thehackernews.com/2025/06/zero-click-ai-vulnerability-exposes.html ｜ https://thehackernews.com/2025/09/first-malicious-mcp-server-found.html ｜ https://thehackernews.com/2026/02/researchers-find-341-malicious-clawhub.html |
| 033 | A | Vendor advisory (AWS) | https://aws.amazon.com/security/security-bulletins/AWS-2025-015/ |
| 036 / 055 / 057 / 071 | C | Media report (The Register); 036 / 071 can cross-reference AIID 1152 / 1469 `[B]` | https://www.theregister.com/2025/07/21/replit_saastr_vibe_coding_incident/ ｜ https://www.theregister.com/2025/12/01/google_antigravity_wipes_d_drive/ ｜ https://www.theregister.com/security/2026/01/20/anthropic-quietly-fixed-flaws-in-its-git-mcp-server/4676059 ｜ https://www.theregister.com/software/2026/04/27/cursor-opus-agent-snuffs-out-startups-production-database/5224442 |
| 037 / 046 / 054 | A | Researcher disclosure | https://generalanalysis.com/blog/supabase-mcp-blog ｜ https://noma.security/blog/forcedleak-agent-risks-exposed-in-salesforce-agentforce/ ｜ https://appomni.com/ao-labs/ai-agent-to-agent-discovery-prompt-injection/ |
| 040 / 044 | A | Official advisory (GitHub Advisory) | https://github.com/cursor/cursor/security/advisories/GHSA-4cxx-hrm3-49rm ｜ https://github.com/nrwl/nx/security/advisories/GHSA-cxm3-wv7p-598c |
| 042 / 045 | A | Researcher disclosure | https://www.safebreach.com/blog/invitation-is-all-you-need-hacking-gemini/ ｜ https://brave.com/blog/comet-prompt-injection/ |
| 067 | A | CERT/CC advisory | https://www.kb.cert.org/vuls/id/221883 |
| 075 / 074 | A | Vendor post-incident review | https://openai.com/index/hugging-face-model-evaluation-security-incident/ ｜ https://www.anthropic.com/news/investigating-incidents-cybersecurity-evals |
| 083 | A | Researcher disclosure + media | https://www.ox.security/blog/cve-2026-82533-deepseek-harness-ai-agent-sandbox-escape/ ｜ https://thehackernews.com/2026/09/deepseek-harness-flaw-let-ai-agents.html |
| 087 | A | Vendor/research disclosure (Glow) | https://www.glow.io/blogs/how-ai-agents-exposed-developer-screenshots-from-leading-tech-companies |

### B. CVE / GHSA Index

`CVE-2023-29374` (LangChain) · `CVE-2024-7042` (LangChain GraphCypherQAChain, cited in the FAQ) · `CVE-2025-32711` (EchoLeak) · `CVE-2025-54135` (Cursor CurXecute, `GHSA-4cxx-hrm3-49rm`) · `CVE-2025-55284` (Claude Code DNS) · `CVE-2025-8217` (Amazon Q) · `CVE-2025-10894` (Nx s1ngularity, `GHSA-cxm3-wv7p-598c`) · `CVE-2025-68143 / 68144 / 68145` (Anthropic Git MCP) · `CVE-2026-2275 / 2285 / 2286 / 2287` (CrewAI, CERT VU#221883) · `CVE-2026-82533` (DeepSeek Harness) · `CVE-2026-5027` (Langflow, cited in the FAQ) · `CVE-2026-24301` (CoSnitch, dataset id 076).

> CVE lookup is far more accurate than searching by event name: confirm status and affected versions across NVD, GitHub Advisories, and OSV before writing.

### C. AIID Index (6 reviewed; the rest not reviewed)

| ID | Event | Date | Dataset mapping |
|---|---|---|---|
| [1152](https://incidentdatabase.ai/cite/1152/) | Replit Agent executed unauthorized destructive commands during a code freeze, losing production data | 2025-07-18 | id 036 |
| [1178](https://incidentdatabase.ai/cite/1178/) | Gemini CLI misread a command sequence and deleted user files | 2025-07-21 | Not in dataset (usable as a variant) |
| [1424](https://incidentdatabase.ai/cite/1424/) | Claude Code deleted DataTalks.Club production infrastructure and snapshots via Terraform | 2026-02-26 | Not in dataset |
| [1469](https://incidentdatabase.ai/cite/1469/) | Cursor Agent deleted the PocketOS production database and volume-level backups | 2026-04-24 | id 071 |
| [1542](https://incidentdatabase.ai/cite/1542/) | OpenClaw ignored a stop instruction and tried to delete email | 2026-02-23 | Not in dataset |
| [1604](https://incidentdatabase.ai/cite/1604/) | OpenAI evaluation models crossed the sandbox boundary and affected Hugging Face production infrastructure | 2026-07-11 | id 075 |
| [622](https://incidentdatabase.ai/cite/622/) (cited in the repo FAQ, not reviewed) | Chevrolet dealership chatbot agreed to sell a car for $1 | 2023-12 | id 010 |

**ATLAS case IDs** (from public secondary references; verify in [atlas.mitre.org](https://atlas.mitre.org/) before formal citation; the old `/studies` deep link is no longer available): `AML.CS0047` Amazon Q extension poisoning · `AML.CS0049` ClawdBot malicious skills · `AML.CS0053` postmark-mcp email exfiltration · `AML.CS0068` OpenAI evaluation agents breach Hugging Face. The `AML.Txxxx` technique IDs in dataset records are field values and can be cited directly.

### D. Dataset Field Dictionary (eight fields in `docs/codebook.md`)

Counting basis: `ai-agent-incidents` v1.2.0 / 88 records, from `stats.json`. All fields below are single-valued and sum to 88. The upstream README prose counts of `lens` 47/19/14 and `adversarial` 65/15 come from an older snapshot (sum 80); do not cite them directly.

| Field | Meaning | Values and counts |
|---|---|---|
| `type` | Event type | `incident` 29 / `vulnerability-disclosure` 48 / `threat-report` 11 |
| `lens` | Role AI plays | `surface` 51 / `target` 21 / `weapon` 16 |
| `vector` | How the attack or failure entered | `indirect-injection` 27 / `autonomous-ops` 12 / `exploitation` 9 / `excessive-agency` 6 / `supply-chain` 6 / `exposure/misconfig` 4 / `direct-injection` 4 / `nhi-secrets` 4 / `extraction` 3 / `generated-code` 3 / `poisoning` 3 / `social-engineering` 3 / `retrieval-memory` 2 / `availability` 1 / `jailbreak` 1 |
| `channel_in` | Content source | `chat message` 25 / `none` 17 / `web page` 11 / `package` 9 / `document` 8 / `repo issue/pr` 8 / `rules file` 3 / `email` 2 / `support ticket` 2 / `tool description` 2 / `calendar invite` 1 |
| `authority` | Authority actually held by the component or credential | `shell/exec` 38 / `read-only` 15 / `none` 9 / `database` 7 / `send-message` 7 / `cloud-creds` 5 / `write-repo` 5 / `file-delete` 1 / `payments` 1 |
| `channel_out` | How the effect left the system | `code exec` 36 / `disclosure-only` 14 / `tool-call send` 12 / `image/link fetch` 11 / `file publish` 6 / `data destruction` 4 / `api-abuse` 3 / `financial transfer` 1 / `service-disruption` 1 |
| `adversarial` | Whether an attack technique is involved | yes 71 / no 17 |
| `outcome` | Most severe harm realized or demonstrated | `data-exfiltration` 33 / `code-execution` 22 / `information-disclosure` 13 / `none-demo` 10 / `data-destruction` 3 / `financial-loss` 3 / `fraud` 3 / `service-disruption` 1 |

Identifier fields: `id` / `date` / `name` / `cve` / `sources` / `summary`; additional fields: `mappings` (`owasp_llm` / `owasp_agentic` / `mitre_atlas`), `affected`, `tags`, `status`.

> **Most useful filter**: `authority` determines blast radius, and `channel_out` determines impact. When mapping "incident → control", filter by these two fields first; it is far more efficient than searching by vendor name.

### E. Query and Reproduction Commands

```bash
# Full dataset
curl -sL https://basitalisandhu.github.io/ai-agent-incidents/incidents.json -o incidents.json

# Find events that hold shell authority and are driven by indirect injection
jq '[.[] | select(.authority == "shell/exec" and .vector == "indirect-injection") | {id,name,cve,mappings}]' incidents.json

# Count by ASI class
jq -r '.[] | .mappings.owasp_agentic[]?' incidents.json | sort | uniq -c | sort -rn

# Subscribe to new events
curl -sL https://basitalisandhu.github.io/ai-agent-incidents/feed.xml
```

Site exports: `incidents.json` / `incidents.jsonl` / `incidents.csv` / `stats.json` / `feed.xml`; crosstab `docs/owasp-agentic-crosstab.md`; validation script `scripts/validate.py`; the maintainer automatically checks all 96 source links weekly (all reachable on 2026-10-13).

### F. Data Credibility Checklist (complete before delivery)

- [ ] Every number traces back to a dataset id / AIID ID / CVE and carries the snapshot version
- [ ] §8-D field statistics match `stats.json` (`lens` 51/21/16; `adversarial` 71/17; `vector` / `outcome` complete)
- [ ] Attack chains follow `channel_in → authority → channel_out` exactly; no `vector` placed in the `channel_in` slot
- [ ] `none-demo`, PoC, and no-confirmed-victim events are labeled and not described as production incidents
- [ ] Third-party SaaS/desktop agent cases in the main tables are marked `†` and state the self-developed/self-hosted prerequisite
- [ ] Desktop/IDE agent cases state that VirbiusAgent does not intercept them; any LLM-level review is attributed to the companion VirbiusLLM proxy, not to V1–V8
- [ ] Off-the-shelf SaaS cases state "cannot be inserted at the server side" and do not imply protection
- [ ] No proportion from the 88-record convenience sample is presented as an industry incidence rate
- [ ] Every covered/partially covered incident is followed by "control → layer → V-suite test"; out-of-scope cases are listed as boundaries
- [ ] The "Explicitly out of scope" table is complete and has not been removed
- [ ] Benchmark limitations (WildGuardTest 71.88%, adversarial slice 59.24%, Q4 gate failure) are preserved
- [ ] Dataset CC BY 4.0 attribution is complete; AIID uses IDs and the suggested format only, with no copied description body
- [ ] Media sources are marked C and never alone support a control conclusion; A/B sources are preferred
- [ ] No "a WeChat account said" phrasing and no mocking of vendors

---

*This document is generated from a structured incident library and standards mapping; the factual layer prioritizes primary disclosure. Product capability descriptions are based on the VirbiusAgent repository's `README.zh.md` and `FAQ.zh.md` (read on 2026-11-13) and do not rely on the deleted `FAQ-agent-scenarios.md`; the desktop-agent companion path is based on the VirbiusLLM README (read on 2026-11-13). All verdicts are mapping recommendations based on public documentation; final protection conclusions are subject to the customer's PoC testing.*