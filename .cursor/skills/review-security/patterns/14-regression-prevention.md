# Skill 14: Regression Prevention

The regreSSHion bug (CVE-2024-6387) was a regression of a vulnerability fixed 14 years
earlier (CVE-2006-5051). During a logging refactor, a protective `#ifdef` was removed,
reintroducing the same signal handler race. Spring4Shell (CVE-2022-22965) was a bypass of
a fix for CVE-2010-1622 that became exploitable on newer JDK versions. The Apache path
traversal fix (CVE-2021-41773) was itself incomplete, leading to CVE-2021-42013.

Regressions are especially dangerous because the team already knows the vulnerability
exists — they fixed it. The regression means the fix was fragile.

## The Core Question

> "Does this change touch code that was previously patched for a security issue? Could
> this change undo, bypass, or interact with a previous security fix?"

## What To Check

### 1. Security-Fix-Touching Changes
When refactoring code that contains or interacts with a previous security fix, the
reviewer must verify the fix survives the refactoring.

**Red flags:**
- Refactoring/cleanup of code in the same file as a CVE fix
- Moving or restructuring code that contains security-critical guards
- Removing "unnecessary" code that is actually a security guard
- Updating dependencies that may affect security behavior

**Review check:** Before reviewing a refactoring PR:
1. Check `git log` for CVE references in the affected files
2. Identify which lines are part of security fixes
3. Verify those lines survive the refactoring intact (or have equivalent replacements)

### 2. Fix Completeness
The first fix for a vulnerability is often incomplete. The Apache path traversal had TWO
CVEs because the first fix didn't handle double-encoding.

**Red flags:**
- Fix that addresses one variant of an attack but not others
- Fix that handles the reported exploit but not the underlying class
- Blocklist-based fix (blocks known-bad) instead of allowlist (allows known-good)
- Fix that works for current inputs but not for inputs the attacker hasn't tried yet

**Review check:** When reviewing a security fix:
1. Does the fix address the ROOT CAUSE or just the SYMPTOM?
2. Can the fix be bypassed with a different encoding, path, or input variation?
3. Is the fix a blocklist (fragile) or an allowlist (robust)?
4. Are there related attack variants that should be fixed simultaneously?

### 3. Fix Bypass on New Platforms/Versions
Spring4Shell (CVE-2022-22965) was exploitable because JDK 9+ module system exposed new
class properties that the original fix didn't anticipate.

**Red flags:**
- Security fix that depends on the current behavior of an external API
- Fix that works on specific versions of the runtime/language/OS
- Fix that assumes certain properties of the execution environment
- Fix that was written before a major platform change

**Review check:** Does this security fix depend on specific behavior of the runtime,
language version, or operating system? Will it still work on newer versions?

### 4. Regression Test Presence
If a CVE was fixed but no regression test was added, the fix WILL be accidentally
undone by a future change.

**Red flags:**
- Security fix committed without a corresponding test
- Test that only checks the specific exploit vector, not the general class
- Test that was disabled, skipped, or removed during "test cleanup"

**Review check:**
- Does every security fix have a regression test?
- Does the test check the general vulnerability class, not just the specific exploit?
- Is the test in a location where it won't be accidentally removed?
- Does the test name or comment reference the CVE/issue number?

### 5. Dependency Updates Affecting Security
Updating a dependency can change its security behavior — new defaults, removed features,
or changed API semantics.

**Red flags:**
- Major version bumps of security-relevant dependencies (crypto, auth, parsers)
- Dependency updates that change default behavior (TLS versions, CORS, CSP)
- Removing security-related dependencies without replacement
- Updating runtime versions (JDK, Node, Python) without checking security implications

**Review check:** When updating a dependency:
1. Read the changelog for security-relevant changes
2. Check if any deprecated security features are being used
3. Verify that security-related configuration still works with the new version
4. Run security tests against the new version

### 6. Feature Interaction with Security Fixes
New features can interact with existing security fixes in unexpected ways.

**Red flags:**
- New API endpoint that bypasses existing security middleware (because it was added after
  the middleware was configured)
- New plugin/extension system that can access resources the security fix restricted
- New caching layer that serves stale data, bypassing a security fix that changes data
- New admin interface that exposes data the security fix was designed to protect

**Review check:** Does this new feature interact with any existing security mechanism?
Could it bypass an existing security fix?

### 7. Code Deletion Review
When code is removed during refactoring, determine if any of it is security-relevant.

**Red flags:**
- Removal of bounds checks, input validation, or sanitization
- Removal of authentication/authorization checks
- Removal of ifdefs, guards, or assertions
- Removal of error handling or cleanup code

**Review check:** For every line deleted: is it a security guard? Is it part of a CVE
fix? Is it input validation? If the deleted code is security-relevant, does the new code
maintain the same security properties?

### 8. Configuration Change Review
Configuration changes can disable security features.

**Red flags:**
- Changing security-relevant defaults (TLS version, cipher suites, timeouts)
- Disabling features that were added for security (rate limiting, WAF rules)
- Widening access permissions (new CORS origins, broader IAM policies)
- Removing security headers from response configuration

**Review check:** Does this configuration change weaken any security property? Is there
a documented reason for the weakening?

## The Regression Prevention Checklist

Before approving any refactoring or cleanup PR:
1. `git log --all --grep="CVE"` on the affected files
2. `git log --all --grep="security"` on the affected files
3. `git log --all --grep="fix"` on the affected files
4. Identify security-critical code in the changed files
5. Verify security-critical code survives the change
6. Verify security regression tests exist and pass
7. If security tests don't exist, request they be added

## Catalog References
- M7 (regreSSHion) — refactoring removed signal safety guard from 14 years ago
- M26 (Spring4Shell) — JDK 9+ bypassed fix from 2010
- M32 (Apache path traversal) — incomplete fix led to second CVE
- M40 (Let's Encrypt) — loop logic error in authorization checking
- L3 (Off-by-one) — boundary condition errors reintroduced by refactoring
