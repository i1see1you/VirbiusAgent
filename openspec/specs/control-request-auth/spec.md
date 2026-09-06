# control-request-auth Specification

## Purpose
Control 对运营台页面与管理 API 做请求认证：运营人员使用身份服务签发的 JWT，程序化调用继续使用现有 API Key，两者映射到同一套角色与租户校验，未登录访问运营台时跳转登录。

## Requirements

### Requirement: Dual-track bearer authentication
When request authentication is enabled, control SHALL accept either an operator JWT or an existing API Key (`vrb_tk_` prefix) as the caller credential on protected management APIs. A `vrb_tk_` credential SHALL be validated by the existing API Key rules (hash lookup, active status, role, tenant scope). A non-key bearer token SHALL be treated as an operator JWT and validated with the identity service JWKS. Control MUST NOT require Spring Security (or equivalent framework lock-down of all endpoints) to implement this split.

#### Scenario: Operator JWT is accepted on a management API
- **WHEN** request authentication is enabled and a client calls a protected management API with a valid, unexpired operator JWT
- **THEN** control SHALL authenticate the caller as that operator's `role` and `tenant_id` and SHALL apply the existing route authorization rules

#### Scenario: API Key continues to work
- **WHEN** request authentication is enabled and a client calls a protected management API with a valid active `vrb_tk_` API Key
- **THEN** control SHALL authenticate the caller using the existing API Key credential and SHALL apply the same route authorization rules as today

#### Scenario: Missing or invalid credential is rejected
- **WHEN** request authentication is enabled and a protected management API is called with no credential, an expired JWT, a JWT that fails JWKS verification, or an unknown API Key
- **THEN** control SHALL return 401 and MUST NOT execute the business operation

### Requirement: Mapped principal reuses existing authorization
After successful JWT or API Key authentication, control SHALL expose a request principal that includes role, tenant scope, and a display label, and SHALL authorize the request with the existing path and method policy. Insufficient role or tenant mismatch SHALL return 403. Issuing and revoking API Keys SHALL keep their current semantics.

#### Scenario: Tenant operator cannot call platform-only routes
- **WHEN** an authenticated `tenant_admin` calls a management route that requires `platform_admin`
- **THEN** control SHALL return 403

#### Scenario: Tenant scope is enforced for JWT callers
- **WHEN** an authenticated operator whose `tenant_id` is `acme` calls a tenant-scoped management route for a different tenant
- **THEN** control SHALL return 403

### Requirement: Ops UI requires an operator identity
When operator authentication is enabled, unauthenticated browser access to `/ui` SHALL redirect to the identity service login with a return URI that comes back to the ops console. After a successful code exchange, subsequent `/ui` access SHALL proceed for that operator. Control MUST NOT accept operator passwords. An API Key MUST NOT substitute for an operator identity on `/ui`.

#### Scenario: Anonymous /ui redirects to login
- **WHEN** operator authentication is enabled and a browser requests `/ui` without a valid operator identity
- **THEN** control SHALL redirect to the identity service login and SHALL NOT serve the console as an authenticated operator

#### Scenario: Authenticated operator reaches /ui
- **WHEN** operator authentication is enabled and the browser presents a valid operator identity obtained via authorization-code exchange
- **THEN** control SHALL serve the ops console

#### Scenario: Password is not posted to control
- **WHEN** an operator logs in
- **THEN** control MUST NOT receive or persist the operator password

### Requirement: Authentication can stay disabled for development
When request authentication is disabled (the development default), control SHALL preserve today's behavior: management APIs SHALL proceed as an implicit platform-scoped administrator, and `/ui` SHALL NOT require a login redirect.

#### Scenario: Disabled auth keeps the console open
- **WHEN** request authentication is disabled and a browser requests `/ui` without credentials
- **THEN** control SHALL serve the ops console without redirecting to the identity service

#### Scenario: Disabled auth does not require a bearer token
- **WHEN** request authentication is disabled and a client calls a protected management API without a bearer token
- **THEN** control SHALL proceed using the implicit development principal

### Requirement: Engine and license identity are unchanged
This capability MUST NOT change `virbius-engine` authentication or License JWT issue and verification. Programmatic callers that already use API Keys SHALL keep that path.

#### Scenario: Engine is out of scope
- **WHEN** this change is applied
- **THEN** engine HTTP endpoints SHALL continue to require no new authentication, and License JWT issue and verification SHALL behave as they do today
