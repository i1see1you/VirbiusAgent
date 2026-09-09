---
name: review-security
description: >
  Deep security review of a diff or a specific file, grounded in 20
  evidence-based pattern libraries extracted from 400+ real-world bugs in major
  open-source projects (Linux kernel, OpenSSL, Chromium, Firefox, curl, Go,
  Rust, Kubernetes, Next.js, etc.). Use when the user says "security review",
  "security audit", "audit this for vulnerabilities", "threat model this",
  "check for CVEs", "check for injection/auth/crypto issues", or when a diff
  touches buffer handling, parsers, authentication, authorization,
  cryptography, state machines, CI/CD, dependencies, or trust boundaries. Also
  usable as a reference library — `/review` links here when a diff touches a
  security-sensitive area.
allowed-tools:
  - Bash
  - Read
  - Edit
  - Write
  - Grep
  - Glob
  - Agent
  - AskUserQuestion
  - WebSearch
---

# Security Review

This skill is a **reference library + workflow**. Twenty evidence-based security pattern files live under `patterns/`. Each one is grounded in specific CVEs, audit findings, and review processes from the Linux kernel, OpenSSL, Chromium, Firefox, curl, Go, Rust, Kubernetes, Next.js, and dozens of other projects.

The skill can be invoked two ways:
- **Standalone:** "do a security review of this branch" — run the workflow below end-to-end.
- **As a reference from `/review`:** the pre-landing review skill reads the matching pattern file(s) when a diff touches a security-sensitive area.

Each pattern file contains:
- **The Core Question** — the one question to keep in mind
- **What To Check** — specific code patterns with examples
- **Red Flags** — patterns that signal danger
- **Catalog References** — real CVEs demonstrating each pattern

---

## Step 1: Scope the review

Determine what is being reviewed.

1. If the user named specific files/paths — review those.
2. Otherwise detect the base branch and take the diff:
   - `gh pr view --json baseRefName -q .baseRefName` (falls back to `gh repo view --json defaultBranchRef -q .defaultBranchRef.name`, then to `main`).
   - `git fetch origin <base> --quiet && git diff origin/<base>`
3. If there is no diff and no named target, ask the user what they want audited.

Print the target (e.g. "Reviewing branch `feature/x` against `main` — 347 lines across 8 files").

---

## Step 2: Pick the relevant pattern files

Do not read all 20. Map the changed surface area to 1–4 patterns using the table below, then Read each matching `patterns/NN-*.md` file BEFORE flagging anything. The patterns include code examples and red flags that will calibrate your findings against real CVEs.

### Change Type → Primary Patterns

| Change touches…                     | Primary patterns          | Secondary patterns        |
|-------------------------------------|---------------------------|---------------------------|
| Buffer/array handling (C/C++)       | 01, 11                    | 06, 05                    |
| New API endpoint                    | 03, 02, 16                | 12, 17                    |
| Authentication/login flow           | 03, 04                    | 07, 09                    |
| Database queries                    | 02, 03                    | 20, 07                    |
| File upload/download                | 17, 02, 03                | 18, 20                    |
| Cryptographic operations            | 04                        | 05, 11, 15                |
| Network protocol                    | 10, 01                    | 05, 17                    |
| Configuration changes               | 12, 14                    | 18, 03                    |
| Dependency updates                  | 08, 14                    | 15                        |
| Lockfile drift (no package.json Δ)  | 08                        | 13, 14                    |
| Registry / install config           | 08, 13                    | 12, 18                    |
| CI/CD workflow changes              | 08                        | 13, 18                    |
| GitHub Actions cache / OIDC scope   | 08, 13                    | 18, 19                    |
| AI agent config (Claude/Cursor/etc) | 08, 13                    | 12, 18                    |
| Refactoring/cleanup                 | 14, 07                    | 06, 05                    |
| Error handling                      | 07                        | 06, 19, 03                |
| Logging changes                     | 19                        | 02, 12                    |
| Container/deployment                | 18, 12                    | 08, 13                    |
| Serialization/parsing               | 02, 17, 01                | 09, 10, 20                |
| Concurrency/threading               | 05                        | 06, 10                    |
| User input handling                 | 02, 17                    | 09, 20, 01                |
| Frontend/UI code                    | 16, 02                    | 09, 03                    |
| Go code                             | 07, 05, 09                | 06, 15                    |
| Rust `unsafe` blocks                | 06, 01                    | 05, 15                    |

### The 20 patterns (at a glance)

01 Bounds & Allocation · 02 Injection · 03 Auth · 04 Crypto Hygiene · 05 Race Conditions · 06 Memory Lifecycle · 07 Error Handling · 08 Supply Chain · 09 Type Safety · 10 State Machines · 11 Integer Arithmetic · 12 Config & Defaults · 13 Trust Boundaries · 14 Regression Prevention · 15 API Contracts · 16 Web App Security · 17 Validation & Canonicalization · 18 Sandbox & Isolation · 19 Logging & Observability · 20 DoS Resistance

Print the selection: "Applying patterns 03, 02, 16 (new API endpoint with DB query)."

---

## Step 3: Apply each pattern to the diff

For each selected pattern:

1. Read `patterns/NN-*.md`.
2. Walk its "What To Check" list against the diff and against referenced code the diff calls into. Be specific — cite `file:line` and tie the finding to the pattern's Core Question and a Red Flag.
3. **Trace, don't skim.** When a pattern says "trace every length field backward" or "list every route through the middleware chain," actually follow the call graph. Use Grep + Read to inspect code outside the diff when the pattern is about trust boundaries or fix completeness.
4. **Verify claims.** If you say "this is handled elsewhere," cite the line. If you say "tests cover this," name the test. Never say "likely safe" — verify or flag as unverified.

---

## Step 4: Adversarial pass (for non-trivial diffs)

Dispatch an adversarial subagent via Agent when any of:
- More than 200 lines changed
- Touches crypto, auth, parsers, deserialization, or CI/CD workflows
- Introduces a new external service / new dependency
- User explicitly requested deep review

Subagent prompt:
"Read the diff for this branch with `git diff origin/<base>`. You are a security auditor. Assume the author wrote the happy path and missed at least one attack vector. Find it. Consider: authentication bypasses, injection via non-obvious channels (logs, filenames, headers, template engines), integer overflow in size arithmetic, race conditions between check and use, error paths that fail open, trust-boundary violations where user input reaches a privileged context, and regression-introducing refactors. For each finding, cite the CVE or public incident it pattern-matches to, and classify as FIXABLE or INVESTIGATE."

Fold FIXABLE findings into the Fix-First pipeline in Step 6. INVESTIGATE findings are informational.

---

## Step 5: Catalog cross-reference (optional, for big audits)

For high-stakes reviews (release audits, new auth systems, new crypto code), cross-reference findings against the catalog files:
- `catalog/missed-in-review.md` — 200 bugs that slipped through code review
- `catalog/caught-in-review.md` — 106 bugs caught by audits / fuzzing / static analysis / peer review
- `catalog/concurrency-and-crypto-bugs.md` — deep dive on 30 concurrency + crypto failures

If a finding matches a cataloged pattern, cite it: "IDOR on `/orders/:id` — same shape as I30 in catalog."

---

## Step 6: Fix-First output

Every finding gets action.

Output header: `Security Review: N findings (X critical, Y high, Z informational)`

**Severity rubric:**
- **CRITICAL** — remotely exploitable, authentication bypass, RCE, data exfiltration, privilege escalation
- **HIGH** — exploitable with authenticated access, information disclosure, DoS on shared infra
- **INFORMATIONAL** — defense-in-depth gaps, hygiene issues, risky patterns that are not currently reachable

**For each finding:**
```
[SEVERITY] [pattern NN] file:line
  Problem: <1-2 lines>
  Evidence: <cite the red flag from the pattern file, or the CVE it matches>
  Fix: <concrete patch>
```

**Classify AUTO-FIX vs ASK** with the same Fix-First heuristic as `/review`: mechanical fixes (missing `Secure`/`HttpOnly` flags, missing timeout, algorithm allowlist, constant-time compare, parameterized query) are AUTO-FIX. Anything requiring a design decision (change auth model, rework error path, add rate limiter) is ASK.

Auto-fix AUTO-FIX items directly. Batch ASK items into one `AskUserQuestion`:
- Each item: severity label, pattern reference, problem, recommended fix
- Options A) Fix  B) Skip
- Include overall RECOMMENDATION

Apply user-approved fixes.

---

## Step 7: Verdict

Issue one of:
- **PASS** — no CRITICAL or HIGH findings unresolved. Informational findings noted.
- **PASS WITH REMEDIATIONS** — CRITICAL/HIGH findings existed but were all fixed (auto-fixed or user-approved). Summarize what was fixed.
- **FAIL** — unresolved CRITICAL or HIGH findings. List each: pattern reference, what's broken, what's needed to fix it. Do NOT merge.

---

## Important rules

- **Read the pattern file before flagging.** The red flags and Core Question calibrate your judgment against real CVEs. Flagging from memory produces noise.
- **Cite specifics.** `file:line`, the red flag you matched, and when possible the CVE / catalog entry. No vague findings.
- **Trace outside the diff when the pattern demands it.** Auth, trust boundaries, and regression prevention all require reading code the diff touches — not just the diff itself.
- **Do not commit, push, or create PRs.** Apply fixes in the working tree only.
- **No preamble, no "looks good overall."** Findings or verdict — that's it.

---

## Relationship to `/review`

- `/review` is the **workflow** for every pre-landing check — scope drift, structural issues, design, adversarial, landing verdict. It calls into this skill's pattern files when a diff touches a security-sensitive surface area.
- `/review-security` (this skill) is the **reference library + deep audit workflow**. Use it standalone for security audits, or let `/review` pull the relevant pattern files inline.

The two skills are complements, not replacements.
