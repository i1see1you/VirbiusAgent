# VirbiusAgent Single Sign-On (OAuth2/OIDC) Integration — SSO INTEGRATION

| Item | Description |
|------|-------------|
| Document version | v0.2 |
| Status | **Design finalized (not implemented)** |
| Adopted route | **virbius-auth as OIDC Broker** (virbius-control **zero changes**) — the only route |
| Related | [ARCHITECTURE.md](ARCHITECTURE.md) · [SECURITY.md](SECURITY.md) · [DEPLOYMENT.md](DEPLOYMENT.md) · [USAGE_GUIDE.md](USAGE_GUIDE.md) |
| Applies to | virbius-auth (new OIDC credential source); control/engine unchanged |

> **Version note**: v0.1 was a "control-side OAuth2 client" draft; v0.2 finalizes the **auth-broker route** and removes the old design (see Git history).
>
> **Current implementation status (important)**: SSO is **not implemented** (no SSO code in the repo; virbius-auth contains no SSO either — today it is local login + self-signed JWT only). The only implemented operator login today is **virbius-auth local login**; production deployments should run virbius-auth (single replica) or keep the console on an isolated network. This document is a pre-implementation design; it ships with no code.

---

## 1. Background & Goals

### 1.1 Goals

- Integrate the **virbius-control operations console UI** (`/ui`, `/ops`, `/ops.html`) with corporate **OIDC** single sign-on.
- SSO is handled by **virbius-auth** (as the OIDC Relying Party / identity broker); after a successful login, auth still issues **exactly the same operator JWT as today**.
- **Retain** the API Key mechanism for programmatic calls; the two tracks coexist.

### 1.2 Protection Scope

| Scenario | Authentication |
|----------|----------------|
| Operators accessing the console UI and its management APIs via browser | SSO (OIDC Authorization Code + PKCE) → auth-issued JWT |
| Programmatic API calls (service-to-service, external integrations) | Existing API Key (`vrb_tk_`, unchanged) |

### 1.3 Non-Goals

- No changes to virbius-control (zero changes under this route).
- No changes to virbius-engine (its machine-caller surface already has evaluate-token caller authentication — see DEPLOYMENT §10.2.1; SSO concerns the operator console only).
- No replacement of the API Key mechanism.
- Does not solve auth single-replica / signing key on PVC (existing backlog, see §10).
- Single IdP in phase 1; no multi-IdP federation.

---

## 2. Route Decision: Why an auth Broker

| Dimension | auth Broker (adopted) | control-side OAuth2 client (v0.1, dropped) |
|-----------|----------------------|---------------------------------------------|
| control changes | **Zero** (still verifies auth's JWKS/JWT, still uses auth's `/login` + `/oauth/token`) | Introduces Spring Security + oauth2-client + SSO bridge filter |
| Risk surface | Isolated in the auth module; no Security-class risk | Spring Security default-lockdown risk lands on the **core control plane** |
| Dual track | Natural inside auth (local password and SSO produce the same JWT) | Arbitration needed inside control |
| Fate of auth | Retained and upgraded | Would be retired |
| Reuse | Full reuse of code→token→JWT→JWKS chain | A separate session system |

**Decision**: SSO becomes a *new credential source inside auth*, reusing the existing auth-code → token → JWT → JWKS chain end to end.

---

## 3. Core Proposition: Contract Unchanged, control Zero Changes

The auth↔control contract (**identical before and after SSO**):

| control depends on (`OperatorJwtProperties` / `UiAuthController` / `JwksJwtVerifier`) | Value |
|---|---|
| `loginUrl` | auth `GET /login` |
| `tokenUrl` | auth `POST /oauth/token` |
| `jwksUrl` | auth `GET /.well-known/jwks.json` |
| Token | ES256 JWT with required claims `iss`/`aud`/`sub`/`exp`/`role`/`tenant_id` (`preferred_username`→label optional) |

SSO only changes *how auth internally authenticates the user* (local password or OIDC); none of the endpoints, keys, or claim products above change ⇒ **not a single line of control changes**.

---

## 4. Authentication Flow (two redirect hops)

```
① Browser → control /ui
   control → 302  auth /login/sso?return_uri=<control/ui/callback>&state=X     (return_uri allowlist check, §8.2)
② auth → 302  IdP authorization_endpoint                                     (code+PKCE-S256+state+nonce; cookie details in §8.2)
③ IdP login → 302  auth /sso/callback?code=…&state=Y          ← verify state==Y (one-time)
④ auth exchanges the code server-side (client_secret + code_verifier)
   → full id_token validation (§8) → claim mapping (§7)
   → issues its OWN authCode → 302  <return_uri>?code=<authCode>&state=X (Cache-Control: no-store)
⑤ control /ui/callback → POST auth /oauth/token (authorization_code)
   → operator JWT (byte-identical to today) → control stores the cookie
```

Key point: the IdP `redirect_uri` points at **auth's `/sso/callback`** (auth is the OIDC RP); control's `/ui/callback` and `/oauth/token` are **unchanged**.

---

## 5. Change List (entirely inside virbius-auth)

| # | Item | Notes |
|---|------|-------|
| 1 | Dependency | `com.nimbusds:oauth2-oidc-sdk` (same family as the existing nimbus-jose-jwt; **no Spring Security**) |
| 2 | New endpoints | `GET /login/sso` (start OIDC), `GET /sso/callback` (RP callback), `GET /logout` (front-channel logout, §9) |
| 3 | New services | `OidcClient` (discovery/token/JWKS; startup cache + refresh on unknown kid), `OidcLoginService` (state/nonce/PKCE/id_token validation/mapping) |
| 4 | Reuse hook | `LoginService` gains `issueCodeForUser(user, returnUri)` (extracted from the existing code-issuance path); `TokenService`/`JwtIssuer`/`JwksController` untouched |
| 5 | Config | `AuthProperties` gains the `sso` subtree + a **fail-closed startup validator** (§6) |
| 6 | Login page | Existing password form + an "Enterprise login" button; local password governed by the break-glass policy (§6) |
| 7 | Audit | One audit event per SSO login: `ts/sub/username/matched groups/role & tenant decision/iss/result` (transient users are logged too) |

---

## 6. Configuration & Startup Validation (fail-closed)

```yaml
virbius.auth:
  issuer: https://control.example.com            # auth's own JWT iss (unchanged)
  return-uris: ["https://control.example.com/ui/callback"]
  sso:
    enabled: true
    issuer-uri: https://idp.corp.example.com/realms/virbius
    client-id: virbius-control
    client-secret: ${VIRBIUS_AUTH_SSO_CLIENT_SECRET}   # confidential client, server-side only
    use-pkce: true                                 # S256 always on
    scopes: [openid, profile, email]               # groups scope additions: see §13.1
    redirect-uri: https://auth.example.com/sso/callback   # must be registered at the IdP
    group-prefix: "virbius-"                       # only this prefix counts; avoids corporate name collisions
    role-mapping:
      platform_admin: ["virbius-admins"]
      tenant_admin:   ["virbius-tenant-admins"]
    default-role: tenant_viewer
    default-tenant: default                        # "*" forbidden (startup-validated)
    require-group: "virbius-users"                 # mandatory; empty → refuse to start
    allow-local-login: false                       # local password restricted to break-glass accounts
    break-glass-users: ["admin"]
```

**Startup validation** (when `sso.enabled=true`; any failure ⇒ **refuse to start**):
1. `require-group` is non-empty (fail-closed against "any IdP user may log in");
2. `default-tenant != "*"` (prevents cross-tenant escalation; `*` is only allowed via an explicit platform_admin group mapping);
3. Discovery reachable and the document's `issuer` matches `issuer-uri` **character for character** (RFC 8414 §4.3);
4. `redirect-uri` / `group-prefix` / `role-mapping` are well-formed.

---

## 7. Claim Mapping & Authorization (transient only; no JIT)

- **Candidate groups** = id_token `groups` ∩ `group-prefix` (**case-sensitive exact match**; taken from the id_token only — server-verified — never from UserInfo/access_token).
- **Entry gate**: the user must be in `require-group`; otherwise a 403 page + audit event.
- **Role**: the **highest** role hit in `role-mapping` (`platform_admin` > `tenant_admin` > `tenant_viewer`); no hit → `default-role`. The decision and the matched groups are written to the audit log.
- **Tenant**: `platform_admin` → `"*"` (the **only** path allowed to use `*`); other roles → `default-tenant` (must be a concrete tenant). Group-derived tenants (e.g. `virbius-tenant-<id>`) are a future extension.
- **User object**: per login, construct a **transient** `OperatorUser(sub, preferred_username||email, role, tenantId)` — nothing persisted — so IdP-side demotion/departure takes effect **on the next login** (no local privilege freeze).

---

## 8. Security Baseline (finalized by security review)

### 8.1 id_token validation checklist (aligned with OIDC Core §3.1.3.7)

JWKS signature verification (ES256/RS256; **unknown kid → refresh JWKS and retry once**, tolerating IdP key rotation) → `iss` **exact match** (trailing-slash handled by the startup discovery check) → `aud` contains `client_id` (when there are multiple audiences, `azp` must equal `client_id`) → `exp`/`nbf` (clock skew ≤60s) → `iat` (reject if older than 5 min) → `nonce` matches the cookie (one-time).

### 8.2 Session & redirection

- state/nonce cookies: `HttpOnly` + `Secure` + **`SameSite=Lax`** (Strict breaks the IdP callback), one-time, TTL 5min.
- PKCE `S256` always on; confidential client (secret stays server-side, never reaches the browser).
- `return_uri` exact-allowlist check at `/login/sso` entry **and** before the code redirect in ④ (reusing `AuthProperties.isReturnUriAllowed`; no wildcards).
- Discovery/JWKS cached at startup + kid-miss refresh + periodic TTL (avoids per-login IdP calls; DoS resistance).

### 8.3 Logging & transport

- Responses carrying code/token get `Cache-Control: no-store`; logs never contain code/token/secret or raw token claims (PII redaction).
- Simple rate limiting on `/oauth/token`; dev-verification token samples contain PII — never paste them into issues/chats, discard after use.

### 8.4 Audit

- One audit event per SSO login (§5-7); SSO users have no local account row — forensics rely on control audit + auth logs.
- Responsibility boundary must be stated in ops docs: **MFA is the IdP's job; break-glass local accounts bypass IdP MFA**.

---

## 9. Logout

- **Standard tier**: control logs out → `auth /logout` → clears auth's own cookies → 302 to the IdP `end_session_endpoint` (with `id_token_hint` + `post_logout_redirect_uri`).
- **Downgraded tier** (IdP lacks end_session support): clear only the control cookie, **register the residual risk**, and add a "on shared computers, close the browser / log out of the IdP" warning to the ops documentation.

---

## 10. Residual Risk Register

| # | Risk | Handling |
|---|------|----------|
| 1 | Operator JWT (60min TTL) is **not revocable** (no denylist) | Accepted; denylist is a phase-2 item |
| 2 | Shared-computer logout completeness depends on IdP `end_session` support | §9; downgrade + warning if unsupported |
| 3 | Break-glass local accounts **bypass IdP MFA** | By design (emergency path); responsibility documented |
| 4 | auth **single replica** / signing key on PVC | Existing backlog, unchanged by SSO; externalize the key before scaling |
| 5 | IdP unavailability = no SSO login | Mitigated by break-glass; monitor IdP reachability |

---

## 11. Testing & Acceptance (DoD)

- [ ] §8.1 checklist, negative cases one by one: tampered signature / wrong `iss` (incl. trailing-slash variant) / wrong `aud` / expired / missing or replayed `nonce` / stale `iat` → all rejected
- [ ] Wrong/missing/replayed state → 400; cookie attribute regression (Lax)
- [ ] Unregistered `return_uri` → 400 at both entry and ④
- [ ] Fail-closed startup: empty `require-group` / `default-tenant="*"` / discovery issuer mismatch → **refuses to start**
- [ ] User not in `require-group` → 403 + audit event; multi-group hits resolve to the highest role with correct logs
- [ ] With `sso.enabled=true`, non-break-glass local login is rejected; `allow-local-login=true` reopens it
- [ ] Logout goes through `end_session_endpoint` (mock IdP); downgrade warning works when unsupported
- [ ] End-to-end: SSO login reaches the console; **not a single control line changed**; JWKS/claims byte-identical to before
- CI uses a mock OIDC provider (Keycloak container or a lightweight mock) covering the happy path and key negatives

---

## 12. Implementation Prerequisites (go/no-go)

### 12.1 Prerequisite: verify a real token sample from the customer IdP (mandatory before starting)

> **Key insight**: whether `groups` lands in the id_token is usually a **per-client mapper/optional-claims setting** — token samples from *another* application, or verbal "our IdP supports groups" confirmations, **do not count**. The sample must come from a real login under **the client registered for virbius**.

Steps:
1. Customer IT registers a **dev client** (recommended: public + PKCE, redirect `http://localhost:PORT/callback`) plus a test user **in the target groups**;
2. Locally run a minimal RP script (Python `authlib` / Node `openid-client`, ~50 lines) or use Postman to complete a real authorization-code login;
3. Decode the id_token and check:
   - [ ] Does `groups` exist? What is the actual claim name (`groups`/`roles`/`entitlements`)?
   - [ ] Issued by default, or does it need an **extra scope / client mapper** (Keycloak mapper, Entra optional claims, Okta claim policy)?
   - [ ] Exact `issuer` value (**trailing slash**); `jwks_uri`; whether `end_session_endpoint` exists (decides the §9 tier)
   - [ ] Signature algorithm (ES256/RS256); `aud`/`azp` behavior; `iat/exp` clock skew
4. Samples contain PII: do not paste into issues/chats; discard after use.

### 12.2 Customer IT cooperation items (critical path — start early)

- dev client (and later the production client) registration: client_id, redirect allowlist (`auth /sso/callback`), confidential + PKCE;
- Test users and group-structure planning (`virbius-*` prefixes: `virbius-users`/`virbius-admins`/`virbius-tenant-admins`);
- Network reachability: dev machine / deployment environment → IdP (VPN/intranet; mind proxies for discovery/JWKS egress).

### 12.3 Others

- The IdP must be **OIDC** (SAML is out of scope for this design).
- Default implementation choice is **nimbus-oidc-sdk** (no Spring Security); if the team strongly prefers `spring-boot-starter-oauth2-client`, the FilterChain configuration surface needs additional review.

---

## 13. Implementation Schedule & Cut Lines

- **Full tier**: about **2–2.5 person-weeks (single dev)**; two devs in parallel ≈ **1.5 weeks**.
- **1-week MVP (two devs, scoped down)**: deferred items = full security matrix (keep 4 key negatives), CI mock infrastructure, front-channel logout (downgraded tier), doc overhaul; deferrals registered for 1.1.
- **Red lines (never cut, even in the 1-week tier)**: id_token validation (SDK), state/nonce/PKCE (SDK), `return_uri` allowlist, `require-group` fail-closed, `default-tenant` no `*`, break-glass restriction.
- **Not in V1.0.0**: new feature + strong external dependency + security-sensitive + needs customer joint debugging — deliver as 1.1; the customer-IT prerequisite request (§12.2) can start early.

---

## Appendix A: Environment Variable Quick Reference (new in this route)

> Lists only the keys overridable via **environment variables**; the remaining §6 keys (`use-pkce` is always on; `scopes`/`role-mapping`/`return-uris`) are set via the config file or deployment manifest.

| Variable | Required (when sso.enabled=true) | Description |
|---|---|---|
| `VIRBIUS_AUTH_SSO_ENABLED` | yes | Master switch; `false` = local-password only (today's behavior) |
| `VIRBIUS_AUTH_SSO_ISSUER_URI` | yes | IdP issuer base (serves `/.well-known/openid-configuration`) |
| `VIRBIUS_AUTH_SSO_CLIENT_ID` | yes | IdP client ID |
| `VIRBIUS_AUTH_SSO_CLIENT_SECRET` | yes (confidential) | IdP client secret (server-side only) |
| `VIRBIUS_AUTH_SSO_REDIRECT_URI` | yes | RP callback (`https://<auth>/sso/callback`, registered at the IdP) |
| `VIRBIUS_AUTH_SSO_GROUP_PREFIX` | no | Group prefix filter, default `virbius-` |
| `VIRBIUS_AUTH_SSO_REQUIRE_GROUP` | yes | Entry-gate group; empty = refuse to start |
| `VIRBIUS_AUTH_SSO_DEFAULT_ROLE` | no | Default role when no mapping hits, default `tenant_viewer` |
| `VIRBIUS_AUTH_SSO_DEFAULT_TENANT` | yes | Default tenant (concrete tenant; `*` forbidden) |
| `VIRBIUS_AUTH_SSO_ALLOW_LOCAL_LOGIN` | no | Default `false` (local password is break-glass only) |
| `VIRBIUS_AUTH_SSO_BREAK_GLASS_USERS` | no | Break-glass account list |
