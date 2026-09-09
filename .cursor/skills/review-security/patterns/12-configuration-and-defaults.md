# Skill 12: Configuration and Insecure Defaults

MongoDB's default no-auth configuration led to thousands of exposed databases. Redis's
default exposure enabled cryptomining botnets. Java XML parsers enable XXE by default.
When software ships with insecure defaults, every deployment that doesn't customize the
configuration is vulnerable.

## The Core Question

> "If this software is deployed with zero configuration changes, is it secure? What's
> the worst that happens if an administrator deploys it with all defaults?"

## What To Check

### 1. Network Exposure Defaults
**Red flags:**
```yaml
# BAD: binding to all interfaces by default
bind: 0.0.0.0  # accessible from any network
listen: :8080   # same

# GOOD: localhost by default
bind: 127.0.0.1
listen: localhost:8080
```

**Review check:** Does the service bind to localhost by default? If it needs to accept
external connections, is there a clear, prominent warning in the configuration?

### 2. Authentication Defaults
**Red flags:**
- No authentication required by default (MongoDB, Redis, Elasticsearch pattern)
- Default credentials that aren't required to change (admin/admin)
- Authentication disabled in "development mode" that's easy to accidentally deploy
- Anonymous access enabled by default

**Review check:**
- Is authentication enabled by default?
- Are there any default credentials? If so, is the first-run experience a forced password
  change?
- Is there a clear distinction between development and production configuration?

### 3. API Feature Exposure
**Red flags:**
```yaml
# BAD: powerful features enabled by default
graphql_introspection: true    # exposes full schema
debug_endpoints: true           # exposes /debug/pprof, /metrics
swagger_ui: true                # exposes API documentation
admin_console: true             # exposes admin interface

# GOOD: disabled by default, opt-in
graphql_introspection: false
debug_endpoints: false
```

**Review check:** Are debug/admin/introspection endpoints disabled by default? What
features are available without authentication?

### 4. Parser and Library Defaults
**Red flags:**
```java
// BAD: Java XML parser defaults enable XXE
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
DocumentBuilder db = dbf.newDocumentBuilder();
Document doc = db.parse(input);  // XXE enabled by default!

// GOOD: explicitly disable dangerous features
DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
```

**Review check:** For every parser/library initialization: are the defaults secure? For
XML: is external entity resolution disabled? For YAML: is safe_load used? For JSON: are
there limits on nesting depth and string length?

### 5. CORS Configuration
**Red flags:**
```javascript
// BAD: allow all origins
app.use(cors({
    origin: '*',
    credentials: true  // browsers reject this, but...
}));

// BAD: reflect any origin
app.use(cors({
    origin: (origin, callback) => callback(null, true),  // accepts everything
    credentials: true
}));

// GOOD: explicit allowlist
app.use(cors({
    origin: ['https://app.example.com'],
    credentials: true
}));
```

**Review check:** Does the CORS configuration use a specific allowlist of origins? Is
origin reflection (mirroring back the request origin) used? With credentials enabled,
is the origin validated?

### 6. Security Headers
**Review check:** Does the response include:
- `Content-Security-Policy` — restricts script/resource loading
- `Strict-Transport-Security` — enforces HTTPS
- `X-Content-Type-Options: nosniff` — prevents MIME sniffing
- `X-Frame-Options: DENY` — prevents clickjacking
- `Referrer-Policy: strict-origin-when-cross-origin` — limits referrer leakage
- `Permissions-Policy` — restricts browser features

### 7. Cookie Configuration
**Red flags:**
```
# BAD: insecure defaults
Set-Cookie: session=abc123

# GOOD: all security flags
Set-Cookie: session=abc123; Secure; HttpOnly; SameSite=Strict; Path=/; Max-Age=3600
```

**Review check:** Do cookies have Secure (HTTPS only), HttpOnly (no JS), SameSite (CSRF
prevention), and appropriate Path/Domain scope?

### 8. TLS/SSL Configuration
**Review check:**
- Minimum TLS version: 1.2 (prefer 1.3)
- No SSL 2.0/3.0
- No weak ciphers (RC4, DES, export ciphers, NULL ciphers)
- Certificate verification enabled
- HSTS header set

### 9. Logging and Error Verbosity
**Red flags:**
```python
# BAD: verbose errors in production
DEBUG = True  # Django debug mode in production
app.debug = True  # Flask debug mode
```

**Review check:**
- Is debug mode OFF by default (or tied to an explicit environment variable)?
- Do error responses hide internal details in production?
- Is sensitive data (passwords, tokens, PII) excluded from logs?

### 10. Resource Limits
**Red flags:**
- No limit on request body size (enables upload DoS)
- No limit on query result size (enables memory exhaustion)
- No rate limiting (enables brute force)
- No connection limit (enables connection exhaustion)
- No timeout on operations (enables slowloris)

**Review check:** Are there configured limits for:
- Request body size
- File upload size
- Query result count
- Request rate per client
- Maximum concurrent connections
- Operation timeout
- WebSocket message size

## The Configuration Security Audit

For every configurable setting:
1. **What's the default?** — is it secure?
2. **What's the worst case?** — if misconfigured, what can an attacker do?
3. **Is it documented?** — does the docs/example explain the security implications?
4. **Is it validated?** — does the code reject obviously insecure configurations?

## Catalog References
- M29 (XXE) — Java XML parser with dangerous defaults
- M36 (MongoDB no-auth) — no authentication by default
- M37 (Elasticsearch no-auth) — security as premium add-on
- M38 (Redis exposure) — no auth + file write = RCE
- O1 (Debug mode) — debug flags in production
- O6 (Unbounded resources) — no limits enabling DoS
- O7 (Missing headers) — security headers not set
