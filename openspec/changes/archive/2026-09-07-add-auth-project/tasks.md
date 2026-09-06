## 1. Scaffold virbius-auth

- [x] 1.1 Add Maven module `virbius-auth` to the parent POM (web, jdbc, sqlite, nimbus-jose-jwt, spring-security-crypto only) and verify `mvn -pl virbius-auth -am test` compiles an empty Spring Boot app
- [x] 1.2 Add auth `application-dev.yml` (own SQLite data dir, port `8082`, bootstrap env keys, return-uri allowlist) and verify the process starts without `virbius-control` and serves `/actuator/health` or `/api/v1/health`

## 2. Operator store and bootstrap

- [x] 2.1 Create `tb_operator_user` + `tb_auth_code` schema and repositories and verify a fresh DB creates both tables on startup
- [x] 2.2 Implement empty-store bootstrap from `VIRBIUS_AUTH_BOOTSTRAP_USERNAME` / `VIRBIUS_AUTH_BOOTSTRAP_PASSWORD` as `platform_admin` / `tenant_id=*` and verify a unit test: empty DB inserts one user; second start with the same env does not insert another
- [x] 2.3 Hash passwords with BCrypt and verify persisted rows and API responses never contain the raw password

## 3. Login, code, JWT, JWKS

- [x] 3.1 Serve GET/POST `/login` (HTML form) that accepts only allowlisted `return_uri` and verify a non-allowlisted URI is refused with no redirect
- [x] 3.2 On valid credentials issue a hashed, ~2 min, single-use auth code and 302 to `return_uri?code=&state=`; on bad username/password return a generic failure; verify tests cover success, wrong password (no user-existence leak), and disabled user
- [x] 3.3 Implement POST `/oauth/token` (authorization_code) that returns an ES256 JWT (`iss`, `aud=virbius-control`, `sub`, `role`, `tenant_id`, `preferred_username`, `exp` ~60m) and verify reuse/expiry/unknown code are rejected
- [x] 3.4 Publish GET `/.well-known/jwks.json` and verify a JWT from 3.3 verifies against that JWKS (unknown `kid` fails)

## 4. Auth user admin API

- [x] 4.1 Protect `/api/v1/users` with the same JWT/JWKS (require `platform_admin`) and verify anonymous and `tenant_admin` callers cannot create users
- [x] 4.2 Implement list/create/disable user endpoints (roles `tenant_viewer|tenant_admin|platform_admin`, tenant id required except `*`) and verify create returns public fields only and disable stops login

## 5. Control dual-track request auth

- [x] 5.1 Add `nimbus-jose-jwt` plus `virbius.security.operator-jwt.*` config (`enabled` default false, jwks-url, issuer, audience, login-url) and verify both flags false keeps today's `DEV_PRINCIPAL` and open `/ui` (`ApiKeyAuthFilterTest` still passes)
- [x] 5.2 Extend request auth so `Bearer vrb_tk_` / `X-Virbius-Api-Key` stay on the existing key path, other Bearer JWTs verify via cached JWKS (60s skew, refresh on unknown kid), and cookie `vrb_op` is JWT-only; verify tests: valid JWT → principal; valid key → principal; bad/expired JWT → 401; cookie is never treated as an API Key
- [x] 5.3 When either flag is on, require a credential of an enabled type on existing protected API prefixes and apply `ApiKeyRoutePolicy`; verify `tenant_admin` JWT gets 403 on platform-only routes and cross-tenant 403
- [x] 5.4 Confirm `virbius-engine` and License JWT issue/verify are untouched (`git diff --stat` has no engine / `LicenseSigner` changes)

## 6. Control /ui login callback and logout

- [x] 6.1 When `operator-jwt.enabled`, unauthenticated `/ui` (except `/ui/callback`) 302s to auth `/login` with `return_uri` + `state`; API Key MUST NOT unlock `/ui`; verify mock-filter/MVC tests for redirect vs cookie-present serve
- [x] 6.2 Implement GET `/ui/callback`: validate `state`, redeem code at auth `/oauth/token`, set `vrb_op` HttpOnly SameSite=Lax Path=/ (Secure when HTTPS), 302 `/ui/`; verify missing/invalid code or state does not set the cookie
- [x] 6.3 Implement POST `/ui/logout` that clears `vrb_op` and verify a follow-up `/ui` redirects to login when operator JWT is enabled

## 7. Ops UI small delta

- [x] 7.1 Keep `adminFetch` working with an empty API key (cookie carries the operator) and add a logout control that POSTs `/ui/logout`; verify the Vue client still loads tenants/admin APIs after a cookie login in a browser or Playwright smoke

## 8. Integration check

- [x] 8.1 Run auth on `:8082` + control with `operator-jwt.enabled=true`: bootstrap login through `/ui` → callback cookie → call `/api/v1/admin/tenants` (or equivalent) as that principal; verify a `vrb_tk_` client still works when `api-key.enabled=true`
- [x] 8.2 Run control with both flags false and verify `/ui` and admin APIs behave as before this change
