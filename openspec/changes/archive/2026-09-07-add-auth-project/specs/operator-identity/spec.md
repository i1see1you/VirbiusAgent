## Purpose

独立鉴权服务为运营人员提供可单独部署的账户、登录换票与公钥发布，使人的身份与角色权威不绑在 control 进程上，并为日后 SSO 预留同一套 JWT 形状。

## ADDED Requirements

### Requirement: Identity service is independently deployable
The system SHALL provide an operator identity service that can be started, configured, and scaled without running the control process. The identity service SHALL own operator accounts and SHALL be the authority for each operator's `role` and `tenant_id`.

#### Scenario: Identity service runs without control
- **WHEN** the identity service is started with its own data store and signing keys
- **THEN** it SHALL accept login and publish verification keys without requiring the control process to be running

### Requirement: First platform admin is bootstrapped once
When the identity service has no operator accounts, it SHALL create exactly one `platform_admin` from configured bootstrap credentials (environment or equivalent startup configuration). After at least one account exists, it MUST NOT create another account from bootstrap credentials.

#### Scenario: Empty store creates the first admin
- **WHEN** the identity service starts with an empty account store and valid bootstrap username and password
- **THEN** it SHALL persist one active `platform_admin` with that username

#### Scenario: Bootstrap does not repeat
- **WHEN** the identity service starts again with bootstrap credentials after an account already exists
- **THEN** it MUST NOT create an additional account from those credentials

### Requirement: Operators are provisioned only by platform admin
The identity service MUST NOT offer open self-registration. Creating a new operator account SHALL require an authenticated caller whose role is `platform_admin`. Each account SHALL have a unique username, a secret password, a role of `tenant_viewer`, `tenant_admin`, or `platform_admin`, and a `tenant_id` (`*` for `platform_admin`).

#### Scenario: Platform admin creates a tenant operator
- **WHEN** a `platform_admin` submits a create-user request with username, password, role `tenant_admin`, and a tenant id
- **THEN** the identity service SHALL persist an active account with those attributes and MUST NOT return the raw password later

#### Scenario: Non-admin cannot create users
- **WHEN** a caller whose role is not `platform_admin` submits a create-user request
- **THEN** the identity service SHALL refuse the request without creating an account

#### Scenario: Anonymous registration is rejected
- **WHEN** an unauthenticated client submits a create-user or register request
- **THEN** the identity service SHALL refuse the request without creating an account

### Requirement: Auth hosts login and returns a one-time code
The identity service SHALL host the operator login page. Operator passwords MUST be submitted only to the identity service. On successful username and password authentication, the identity service SHALL redirect the browser to a pre-registered return URI with a single-use, short-lived authorization code. A return URI that is not on the allowlist MUST be rejected.

#### Scenario: Successful login redirects with a code
- **WHEN** an active operator submits a correct username and password and a whitelisted return URI
- **THEN** the identity service SHALL redirect to that return URI with a one-time authorization code and MUST NOT put the access token in the redirect URL

#### Scenario: Wrong password fails closed
- **WHEN** a client submits an unknown username or an incorrect password
- **THEN** the identity service SHALL refuse login, SHALL NOT issue a code, and MUST NOT indicate whether the username exists

#### Scenario: Open redirect is rejected
- **WHEN** a login request names a return URI that is not on the allowlist
- **THEN** the identity service SHALL refuse the request and SHALL NOT redirect the browser to that URI

### Requirement: Authorization code is exchanged for an operator JWT
Presenting a valid, unused authorization code to the identity service token endpoint SHALL return an access JWT. The code MUST be single-use. An expired, reused, or unknown code MUST be rejected. The JWT SHALL include a subject, `role`, and `tenant_id` taken from the authenticated operator account, and SHALL be verifiable with the keys the identity service publishes.

#### Scenario: Fresh code yields a JWT
- **WHEN** a client redeems a valid unused authorization code at the token endpoint
- **THEN** the identity service SHALL return an access JWT whose `role` and `tenant_id` match the operator who logged in

#### Scenario: Code cannot be reused
- **WHEN** a client redeems an authorization code that was already exchanged
- **THEN** the identity service SHALL refuse the request and SHALL NOT issue another JWT for that code

### Requirement: Verification keys are published
The identity service SHALL publish a JWKS document that contains the public keys needed to verify currently valid operator JWTs. Each issued JWT SHALL be verifiable against that document until the JWT expires.

#### Scenario: Verifier fetches JWKS
- **WHEN** a relying party requests the identity service JWKS
- **THEN** the response SHALL include at least one public key that verifies a JWT issued by that service

### Requirement: Passwords are stored non-reversibly
The identity service MUST store operator passwords only as a non-reversible hash. It MUST NOT log raw passwords or return them in any API response after create or login.

#### Scenario: Account record has no password plaintext
- **WHEN** an operator account is created or a login succeeds
- **THEN** persisted account data and API responses MUST NOT contain the raw password
