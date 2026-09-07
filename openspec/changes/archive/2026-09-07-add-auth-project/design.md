## Context

See `proposal.md` (Why) and the two specs. Constraints that shape the how:

- `virbius-control` already authenticates with `ApiKeyAuthFilter`: `vrb_tk_` / `X-Virbius-Api-Key`, SHA-256 → `tb_tenant_api_credential`, principal + `ApiKeyRoutePolicy`. `/ui` is skipped. `virbius.security.api-key.enabled` defaults to `false` (implicit `DEV_PRINCIPAL`).
- Control is Spring Boot 3.2 / Java 17, JDBC + Flyway, no Spring Security, no JWT library. Ops UI is a Vue app under `virbius-control/frontend`; `fetch` is same-origin and already optionally sends `Authorization: Bearer <apiKey>`.
- License JWT is a separate Ed25519 artifact for agents. Do not reuse that signer or key for people.
- `SSO_INTEGRATION.zh.md` embeds an OAuth2 client in control via `spring-boot-starter-oauth2-client`. That pulls `SecurityAutoConfiguration` and locks every endpoint. Rejected.

## Goals / Non-Goals

**Goals:**

- New Maven module `virbius-auth` that runs as its own process and database.
- Thin dual-track on control: Bearer prefix or cookie, no Security filter chain.
- Browser login never posts a password to control; `/ui` identity is a cookie set after server-side code exchange.
- Same `ApiKeyPrincipal` + `ApiKeyRoutePolicy` after either track.

**Non-Goals:**

- Company OIDC / IdP broker (same JWT shape is enough later).
- Refresh tokens, JWT denylist, or session store on control.
- Ops UI screens for user CRUD (auth HTTP API only).
- PKCE (first-party + one-time code is enough for v1).
- Engine auth, License JWT changes, replacing API Key issue/revoke.

## Decisions

### D1 — New Spring Boot module, not a library

`virbius-auth` joins the parent POM next to control. Same stack: `spring-boot-starter-web` + JDBC + Flyway + SQLite (dev) / MariaDB (prod). Own `data` dir and port.

**Why:** Spec requires start/login/JWKS without control running. A library inside control cannot be independently deployed.

**Rejected:** Embed login in control (SSO draft). Shared library only.

### D2 — Authorization code + control callback cookie (not SPA token)

```
  GET /ui  (no cookie)
       |
       v
  control 302 --> auth GET /login?return_uri=<control>/ui/callback&state=...
       |
       |  POST /login  (password stays on auth)
       v
  auth 302 --> control GET /ui/callback?code=&state=
       |
       |  server POST auth /oauth/token
       v
  Set-Cookie: vrb_op=<jwt>; HttpOnly; SameSite=Lax; Path=/
  302 /ui/
```

Control redeems the code. JWT lives in an HttpOnly cookie (`vrb_op`). Same-origin `fetch` already uses `credentials: "same-origin"` by default, so the Vue client sends the cookie without a new token store. `sessionStorage` JWTs were rejected (XSS, and the SPA would have to learn a second credential).

`/ui/callback` is unauthenticated. `state` is a short-lived signed value (or server nonce) to stop CSRF on the callback.

**Rejected:** Fragment token; control form + CORS; full OIDC AS; cookie on the auth origin (cross-site, needs BFF).

### D3 — Dual-track in the existing filter, no Spring Security

Extend `ApiKeyAuthFilter` (or a sibling `OncePerRequestFilter` registered beside it):

| Credential | Use |
|------------|-----|
| `Authorization: Bearer vrb_tk_...` or `X-Virbius-Api-Key` | Existing API Key path |
| Other `Bearer` | Operator JWT, verify via cached JWKS |
| Cookie `vrb_op` | Operator JWT only (never treat cookie as an API Key) |
| None, both flags off | Today's `DEV_PRINCIPAL` |

`/ui` (except `/ui/callback` and static assets required to render login-return): if `operator-jwt.enabled` and no valid `vrb_op`, 302 to auth `/login`. API Key MUST NOT unlock `/ui`.

Flags (both default `false`):

- `virbius.security.api-key.enabled` — unchanged
- `virbius.security.operator-jwt.enabled` — new

"Request authentication disabled" in the spec = both false. Either flag on ⇒ management APIs require a valid credential of a type that is enabled.

Control config: `jwks-url`, `issuer`, `audience`, `login-url`. JWKS cached; refresh on unknown `kid`. Clock skew 60s.

**Rejected:** `spring-boot-starter-oauth2-resource-server` (pulls Security auto-config). Introspection on every request.

### D4 — JWT shape

- `alg`: ES256 (P-256). Standard JWK (`EC`), not the License Ed25519 custom signer.
- Claims: `iss`, `aud` (`virbius-control`), `sub` (user id), `role`, `tenant_id`, `preferred_username`, `iat`, `exp`, `kid`.
- TTL: 60 minutes. No refresh in v1; expiry ⇒ login again. No denylist.
- Map to `ApiKeyPrincipal(sub, tenant_id, ApiRole.parse(role), preferred_username)`.
- Library: `nimbus-jose-jwt` on both auth (sign) and control (verify). Auth also depends on `spring-security-crypto` **only** (BCrypt), not `starter-security`.

**Rejected:** Reuse `LicenseSigner`. Opaque tokens. Long-lived JWT.

### D5 — Auth data model and HTTP surface

Tables (auth DB only):

- `tb_operator_user`: `user_id`, unique `username`, `password_hash`, `role`, `tenant_id`, `status` (`active`/`disabled`), timestamps. `platform_admin` ⇒ `tenant_id = *`.
- `tb_auth_code`: hash of code, `user_id`, `return_uri`, `expires_at` (~2 min), `consumed_at`.

Bootstrap: if user count is 0 and `VIRBIUS_AUTH_BOOTSTRAP_USERNAME` + `VIRBIUS_AUTH_BOOTSTRAP_PASSWORD` are set, insert one `platform_admin`. Later starts ignore bootstrap.

Endpoints:

| Method | Path | Auth |
|--------|------|------|
| GET/POST | `/login` | public (allowlisted `return_uri`) |
| POST | `/oauth/token` | public, one-time code |
| GET | `/.well-known/jwks.json` | public |
| GET/POST | `/api/v1/users` | operator JWT, `platform_admin` |
| POST | `/api/v1/users/{id}/disable` | same |

Return URI allowlist is config (`virbius.auth.return-uris`), typically `http://localhost:<control>/ui/callback` in dev.

Auth verifies its own JWTs for admin APIs (same keys). Failed login: generic error, no username-existence leak.

### D6 — Frontend delta stays small

No new auth SPA. After cookie is set, existing `adminFetch` works if `apiKey` is empty (cookie carries the person). Optional: hide the API Key field when operator JWT is on; add a logout that hits `POST /ui/logout` (clear cookie) and redirects to `/ui`.

User create remains an auth API (curl / later UI).

## Risks / Trade-offs

- **[Risk] Short-lived JWT + no revoke** → stolen cookie works until `exp`. Mitigation: 60m TTL, HttpOnly + SameSite=Lax, HTTPS `Secure` in prod. Denylist later if needed.
- **[Risk] Control must reach auth JWKS and token URL** → deploy coupling. Mitigation: cache JWKS; fail closed (401 / login redirect) if JWKS unavailable while `operator-jwt.enabled`.
- **[Risk] Open redirect** → allowlist + exact `return_uri` match on code row.
- **[Risk] Two flags mis-set in prod** (`api-key` on, JWT off) → `/ui` still open. Mitigation: deployment checklist; staging profile turns both on.
- **[Risk] Code in the callback query** → one-time, hashed at rest, 2 min TTL; immediate 302 to `/ui/` drops the query.
- **[Trade-off] No PKCE / no refresh** → fewer parts, weaker than a full AS. Acceptable for first-party ops; add later without changing claims.

## Migration Plan

1. Ship `virbius-auth` with flags off on control. Existing deploys unchanged (`DEV_PRINCIPAL`, open `/ui`).
2. Stand up auth, bootstrap the first admin, confirm JWKS.
3. Enable `operator-jwt` on staging; log in through `/ui`; confirm API Key clients still work when `api-key.enabled` is true.
4. Prod: create users, then enable both flags. Rollback = set `operator-jwt.enabled=false` (and `api-key.enabled=false` if you must restore implicit admin). No control schema migration.

## Open Questions

- Auth listen port in local/dev (suggest `8082` if control stays `8080`) — does not change tasks, only runbooks.
- Whether v1 logout is a control cookie-clear only or also a dead authorization-code list — cookie-clear is enough given 60m TTL.
