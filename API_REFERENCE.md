# VirbiusAgent API Reference (v1.0.3 stability contract)

[中文](API_REFERENCE.zh.md)

This document is the **public API stability contract** for V1.0.0: which surfaces are public, how authentication works, the error envelope, status-code semantics, and the versioning policy. Field-level schemas live in the code and [PROTOCOL.md](PROTOCOL.md); this document only promises what will not silently break you.

---

## 1. Stability tiers

| Tier | Scope | Guarantee |
|---|---|---|
| **Public stable (GA)** | engine `/v1/evaluate*`, `/v1/memory/check`, `/v1/challenge/verify`; control `/api/v1/admin/**`, `/api/v1/challenges/**`; auth `/login`, `/oauth/token`, `/.well-known/jwks.json` | No breaking change within a major version; breaking changes require a new versioned path plus a deprecation announced one minor release ahead |
| **Component contract** | control `/api/v1/edge/**`, `/api/v1/gateway/**`, `/api/v1/tenants/**` | Upgraded in lockstep; all components must run the same release |
| **Internal / ops (no guarantee)** | control `/api/v1/internal/**`, engine `/api/internal/**` (such as falco-alert), engine `/admin/**` (health/ready/policy-version/cache-reload) | May change at any time; third parties must not depend on these |

## 2. Authentication

| Surface | Mechanism | Credential |
|---|---|---|
| control `/api/v1/admin/**` (automation / tenant API) | API key | header `X-Virbius-Api-Key: <key>` (issued from the console, HMAC-stored) |
| control `/api/v1/challenges/**`, console page APIs | operator JWT | `Authorization: Bearer <token>` (via auth `/login` + `/oauth/token`) |
| engine `/v1/evaluate*` | short-lived evaluate token | `Authorization: Bearer <vct_...>`, minted via `POST /api/v1/admin/evaluate-token/*` or exchanged per session by the proxy; TTL 900s by default |
| engine `/v1/memory/check`, `/v1/challenge/verify` | none (in-cluster) | rely on network isolation |
| auth `/login`, `/oauth/token` | form | username/password → authorization code → token |

401/403 are always **fail-closed**; throttling returns 429 (see §4).

## 3. Error envelope (uniform across control)

Every control error response — including `/api/v1/challenges/**` — uses the `ApiResult` envelope and is **never empty** (1.0.0 fixed the 500-with-empty-body that broke the console with `Unexpected end of JSON input`):

```json
{ "code": 500, "message": "challenge service unavailable", "timestamp": "2026-10-19T03:00:00Z" }
```

- `code`: business code, **identical to the HTTP status** (`GlobalExceptionHandler` maps via `HttpStatus.resolve(code)`; unknown codes fall back to 400).
- Success responses: admin routes return `{"code":0,"message":"success","data":...}`. **Exception** — `/api/v1/challenges/**` success bodies are the raw engine/SQL payloads (array or object), not enveloped. Clients should check the HTTP status first, then parse per the above.

## 4. Status-code semantics

| Code | Meaning |
|---|---|
| 400 | argument/state error (such as a challenge that is missing or not pending) |
| 401 / 403 | unauthenticated / unauthorized (fail-closed) |
| 404 | resource not found |
| 409 / 423 | state conflict / locked (such as an image-blacklist rebuild in progress) |
| 429 | rate limited; respect `Retry-After` |
| 500 | internal error |
| 502 / 503 | upstream dependency failure (engine/DB/Redis/Kafka); safe to retry |

## 5. Route groups

### 5.1 virbius-engine (8082, decision plane)

| Route | Purpose | Tier |
|---|---|---|
| `POST /v1/evaluate` | tool-call/content risk decision (the single authoritative decision path) | Public stable |
| `POST /v1/evaluate/tool-result` | output-safety review of tool results | Public stable |
| `POST /v1/memory/check` | memory-poisoning check | Public stable (in-cluster) |
| `GET /v1/challenges`, `GET/POST /v1/challenge/{id}/{status,approve,reject}` | human-approval challenges | Component contract (proxied by control) |
| `POST /v1/challenge/verify` | one-time approval-token consumption | Public stable |
| `POST /v1/simulate/prompt` | prompt-rule simulation | Component contract |
| `GET /admin/health`, `GET /admin/ready` | liveness/readiness probes | Internal/ops |
| `GET /admin/policy-version`, `POST /admin/cache/reload` | cache operations | Internal/ops |

### 5.2 virbius-control (8080, control plane)

| Route | Purpose | Tier |
|---|---|---|
| `GET /api/v1/health`, `GET /api/v1/ready` | liveness/readiness | Internal/ops |
| `/api/v1/admin/tenants/**` | tenants, rules, licenses, lists, audit, alerts, publishing — the full admin API | Public stable |
| `/api/v1/admin/evaluate-token/**` | evaluate-token issuance/revocation | Public stable |
| `/api/v1/admin/platform/api-credentials/**` | platform-level API keys | Public stable |
| `/api/v1/challenges/**` | console approval queue (proxies engine + SQL history) | Public stable |
| `/api/v1/edge/**`, `/api/v1/gateway/**` | edge manifest delivery, gateway artifacts | Component contract |
| `/api/v1/internal/**` | internal write-back (audit/integration) | Internal/ops |
| `/api/v1/auth/**` | login-session helpers | Internal/ops |

### 5.3 virbius-auth (8083, login)

| Route | Purpose | Tier |
|---|---|---|
| `GET/POST /login` | login page / submit (authorization-code flow) | Public stable |
| `POST /oauth/token` | code → operator JWT | Public stable |
| `GET /.well-known/jwks.json` | JWKS (control's verification source) | Public stable |
| `GET /api/v1/health` | liveness | Internal/ops |

Component inventory note: virbius-mcp-proxy (9090, edge) speaks MCP JSON-RPC over stdio/SSE/TCP — contract in [PROTOCOL.md](PROTOCOL.md); it is not part of the REST surface.

## 6. Versioning policy

- The path prefix is the version: `/v1/` (engine), `/api/v1/` (control/auth).
- Within a major version we make only **backward-compatible** changes (new fields/routes). Removing fields, changing semantics, or the error envelope is breaking and requires `/v2` plus a deprecation announced one minor release ahead.
- Deprecations are announced in `CHANGELOG.md` under the release's `Deprecated` heading.

## 7. Compatibility notes (known 1.0.0 boundaries)

- `/v1/evaluate` rate limits count **per replica** (global quota = configured value × replica count).
- engine `/admin/ready` is unauthenticated and must stay in-cluster (the Helm Ingress routes only `/v1`).
- The error envelope of control `/api/v1/challenges/**` was unified from `{error}` to `ApiResult` in 1.0.0 — that route had no external callers before this release.
