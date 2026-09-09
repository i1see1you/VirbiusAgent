---
workflowStatus: completed
totalSteps: 5
stepsCompleted:
  - step-01-detect-mode
  - step-02-load-context
  - step-03-risk-and-testability
  - step-04-coverage-plan
  - step-05-generate-output
lastStep: step-05-generate-output
nextStep: ''
lastSaved: '2026-09-07'
runScope: epic-level
runKey: epic-add-auth-project
---

# Test Design: Epic add-auth-project — independent virbius-auth + control dual-track JWT

**Date:** 2026-09-07
**Author:** Huych
**Status:** Draft (implementation-qa)

P0/P1/P2/P3 = priority, not execution timing.

---

## Executive Summary

**Scope:** Epic-level test design for OpenSpec change `add-auth-project` (`operator-identity` + `control-request-auth`).

**Mode:** Epic-level (OpenSpec proposal + specs as acceptance criteria). No PRD/ADR pair.

**Stack:** fullstack — Maven/JUnit (`pom.xml`, `src/test/`) + Vue ops console. No Playwright/Cypress in repo. Browser exploration skipped (`playwright-cli` not used; no running env for `/implementation-qa`). `@seontechnologies/playwright-utils` and Pact.js are not installed — TEA flags are on but the mandate does not apply until those packages exist. `pact_mcp_reachable`: not probed (no Pact artifacts).

**Risk Summary:**

- Total risks identified: 6
- High-priority risks (≥6): 3
- Critical categories: SEC, TECH

**Coverage Summary:**

- P0: ~12–16 unit/filter tests (existing + gaps) — ~2–4 hours remaining
- P1: ~4–6 tests — ~1–2 hours
- P2/P3: ~2–4 — ~0.5–1 hour
- **Total remaining effort:** ~4–8 hours (most P0 already implemented)

---

## Not in Scope

| Item | Reasoning | Mitigation |
| ---- | --------- | ---------- |
| Playwright / Cypress E2E | Repo has no `e2e/` or `playwright.config.*`; implementation-qa forbids inventing a stack | P0 covered at filter/service unit level; browser login smoke deferred |
| virbius-engine / License JWT | Spec: unchanged | `git show f552597 --stat` has no engine / LicenseSigner files |
| Company OIDC / SSO | Explicitly out of this change | Future epic |
| Pact consumer/provider | No Pact artifacts; JWT+JWKS already unit-tested | Optional later if broker is adopted |

---

## Risk Assessment

### High-Priority Risks (Score ≥6)

| Risk ID | Category | Description | Probability | Impact | Score | Mitigation | Owner | Timeline |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ---------- | ----- | -------- |
| R-001 | SEC | Open redirect or code leak on login (`return_uri` not allowlisted / token in URL) | 2 | 3 | 6 | Unit: reject unknown URI; code in query only, JWT only at `/oauth/token` | Dev | done |
| R-002 | SEC | Dual-track mix-up: cookie treated as API Key, or API Key unlocks `/ui` | 2 | 3 | 6 | Filter tests: cookie JWT-only; API Key does not unlock `/ui` | Dev | done |
| R-003 | SEC | User-admin API callable without `platform_admin` | 2 | 3 | 6 | `OperatorAuthFilter` 401/403 tests | Dev | this QA |

### Medium-Priority Risks (Score 3–4)

| Risk ID | Category | Description | Probability | Impact | Score | Mitigation | Owner |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ---------- | ----- |
| R-004 | TECH | Stale/unknown JWKS `kid` or expired code still issues JWT | 2 | 2 | 4 | Exchange once; expired/unknown empty; JWKS verify | Dev |
| R-005 | BUS | Auth flags default on in prod-like config and lock ops console | 2 | 2 | 4 | Default `enabled=false`; filter skip when both off | Dev |

### Low-Priority Risks (Score 1–2)

| Risk ID | Category | Description | Probability | Impact | Score | Action |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ------ |
| R-006 | OPS | Auth and control clocks drift beyond 60s skew | 1 | 2 | 2 | Monitor; skew already 60s |

### Risk Category Legend

- **TECH**: Technical/Architecture
- **SEC**: Security
- **PERF**: Performance
- **DATA**: Data Integrity
- **BUS**: Business Impact
- **OPS**: Operations

---

## NFR Planning

| NFR Category | Requirement / Threshold | Risk Link | Planned Validation | Evidence Needed |
| ------------ | ----------------------- | --------- | ------------------ | --------------- |
| Security | Passwords hashed; no plaintext in store/API; JWT ES256 + JWKS | R-001, R-003 | JUnit on hasher, create, login, JWKS | Surefire reports |
| Security | Dual-track Bearer; cookie JWT-only | R-002 | Filter tests | Surefire reports |
| Reliability | Auth code ~2 min, single-use | R-004 | Unit: reuse + expired | Surefire reports |
| Performance | UNKNOWN (no SLA in spec) | — | Not in this epic | — |

**Unknown thresholds:** JWT clock skew 60s is implemented but not an NFR in the spec. Login/token latency: UNKNOWN.

---

## Entry Criteria

- [x] OpenSpec specs archived and on `main`
- [x] JUnit + Mockito already in modules
- [ ] Running auth `:8082` + control with `operator-jwt.enabled` (only for deferred browser smoke)

## Exit Criteria

- [ ] All P0 automated tests passing
- [ ] P1 gaps documented or passing
- [ ] No open high-severity auth bugs on this change

---

## Test Coverage Plan

### P0 (Critical)

Criteria: security-critical auth/authz with no safe workaround.

| Test ID | Requirement | Test Level | Risk Link | Notes |
| ------- | ----------- | ---------- | --------- | ----- |
| AUTH.P0-01 | Empty store bootstraps one `platform_admin`; second start does not | Unit | R-005 | `BootstrapServiceTest` |
| AUTH.P0-02 | Unknown `return_uri` refused, no redirect/code | Unit | R-001 | `LoginAndTokenTest.rejectsUnknownReturnUri` |
| AUTH.P0-03 | Wrong password and unknown user share failure | Unit | R-001 | `LoginAndTokenTest` |
| AUTH.P0-04 | Disabled user cannot login | Unit | R-003 | `LoginAndTokenTest` |
| AUTH.P0-05 | Fresh code → JWT verifies against JWKS; reuse/unknown rejected | Unit | R-004 | `LoginAndTokenTest.codeExchangesOnceForJwtMatchingJwks` |
| AUTH.P0-06 | Expired code rejected | Unit | R-004 | **gap → add** |
| AUTH.P0-07 | Create user returns no password; disable stops login | Unit | R-003 | `LoginAndTokenTest` |
| AUTH.P0-08 | Anonymous / non-admin cannot hit `/api/v1/users` | Unit (filter) | R-003 | **gap → `OperatorAuthFilterTest`** |
| CTL.P0-01 | Valid operator JWT → principal; `vrb_tk_` still key path | Unit (filter) | R-002 | `OperatorJwtAuthFilterTest` |
| CTL.P0-02 | Bad/expired JWT → 401; cookie never API Key | Unit (filter) | R-002 | `OperatorJwtAuthFilterTest` |
| CTL.P0-03 | `tenant_admin` 403 on platform route; cross-tenant 403 | Unit (filter) | R-002 | `OperatorJwtAuthFilterTest` |
| CTL.P0-04 | `/ui` without cookie → login redirect; API Key does not unlock `/ui` | Unit (filter) | R-002 | `OperatorJwtAuthFilterTest` |
| CTL.P0-05 | Callback missing/invalid code/state does not set cookie; redeem fail does not set cookie | Unit | R-001 | `UiAuthControllerTest` + redeem-fail **gap** |
| CTL.P0-06 | Logout clears `vrb_op`; flags off skip `/ui` filter | Unit | R-002 | `UiAuthControllerTest` + `bothFlagsOffSkipsUiFilter` |
| CTL.P0-07 | API-key-only tests still pass (dev principal / key path) | Unit | R-005 | `ApiKeyAuthFilterTest` |

**Total P0:** 15 scenarios, ~2–4 hours remaining for gaps

### P1 (High)

Criteria: core frequent paths with limited workaround.

| Test ID | Requirement | Test Level | Risk Link | Notes |
| ------- | ----------- | ---------- | --------- | ----- |
| AUTH.P1-01 | App starts / health without control | Unit | R-005 | `AuthApplicationTest` |
| CTL.P1-01 | Valid cookie serves `/ui` | Unit | R-002 | `uiWithValidCookieProceeds` |
| AUTH.P1-02 | Unknown JWKS kid does not verify | Unit | R-004 | `unknownKidDoesNotVerify` (weak: empty set only) |

### P2 (Medium)

| Test ID | Requirement | Test Level | Notes |
| ------- | ----------- | ---------- | ----- |
| UI.P2-01 | Logout control POSTs `/ui/logout`; `adminFetch` works with empty API key | Component / browser | Deferred: no E2E stack |

### P3 (Low)

| Test ID | Requirement | Test Level | Notes |
| ------- | ----------- | ---------- | ----- |
| OPS.P3-01 | Clock skew >60s | Manual | Monitor |

---

## Execution Order

**Philosophy:** Run all auth/control JUnit in PRs (`mvn -pl virbius-auth,virbius-control -am test` is seconds–minutes). Do not invent Playwright. Defer browser smoke to a later env.

### PR

- `virbius-auth` tests
- `OperatorJwtAuthFilterTest`, `UiAuthControllerTest`, `ApiKeyAuthFilterTest`

### Nightly / Weekly

- None for this epic (no perf/chaos suite)

---

## Resource Estimates

| Priority | Count (approx) | Effort |
| -------- | -------------- | ------ |
| P0 | 15 | ~2–4 hours remaining |
| P1 | 3 | ~1–2 hours |
| P2 | 1 | ~1–3 hours if E2E added later |
| P3 | 1 | monitor only |
| **Total remaining** | | **~4–8 hours** (~0.5–1 day) |

### Prerequisites

**Test data:** in-memory user/code repos; Mockito JWKS verifier

**Tooling:** JUnit 5, Mockito, Maven Surefire

**Environment:** none for P0 unit tests

---

## Quality Gate Criteria

- **P0 pass rate:** 100%
- **P1 pass rate:** ≥95%
- **High-risk mitigations:** R-001/R-002 covered; R-003 closed by this QA
- **Coverage target:** critical auth paths 100% of P0 table; no new E2E required
- NFR evidence: Surefire; final PASS/CONCERNS deferred to `nfr-assess` if run

### Non-Negotiable

- [ ] All P0 tests pass
- [ ] SEC tests in this epic pass 100%
- [ ] Engine/License files untouched

---

## Mitigation Plans

### R-001: Open redirect / token leak (Score: 6)

Allowlist `return_uri`; JWT only from token endpoint. **Status:** Complete (unit). **Verification:** AUTH.P0-02, AUTH.P0-05, CTL.P0-05

### R-002: Dual-track mix-up (Score: 6)

Cookie JWT-only; API Key does not unlock `/ui`. **Status:** Complete (unit). **Verification:** CTL.P0-01–04, CTL.P0-06

### R-003: User admin without platform_admin (Score: 6)

Filter 401/403. **Status:** In this QA. **Verification:** AUTH.P0-08

---

## Assumptions and Dependencies

### Assumptions

1. Default `operator-jwt.enabled=false` remains the shipped default.
2. No Playwright will be added in this QA pass.
3. Unrelated `DeployRolloutServiceLadderSkipTest` failure on `main` is out of this epic.

### Dependencies

1. Maven modules `virbius-auth`, `virbius-control` compile on JDK used by CI.

---

## Interworking & Regression

| Service/Component | Impact | Regression Scope |
| ----------------- | ------ | ---------------- |
| **virbius-auth** | New module | All tests in module |
| **virbius-control** filter + `/ui` | Dual-track + redirect | `ApiKeyAuthFilterTest`, `OperatorJwtAuthFilterTest`, `UiAuthControllerTest` |
| **virbius-engine** | None | No file changes |

---

## Appendix

### Knowledge Base References

- `risk-governance.md`, `probability-impact.md`, `test-levels-framework.md`, `test-priorities-matrix.md`

### Related Documents

- Proposal: `openspec/changes/archive/2026-09-07-add-auth-project/proposal.md`
- Specs: `openspec/specs/operator-identity/spec.md`, `openspec/specs/control-request-auth/spec.md`
- Design: `openspec/changes/archive/2026-09-07-add-auth-project/design.md`

**Generated by:** BMad TEA — `bmad-testarch-test-design` (epic-level), output path per `/implementation-qa`
