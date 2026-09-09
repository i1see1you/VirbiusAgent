# Skill 03: Authentication and Authorization

The Next.js middleware bypass (CVE-2025-29927) let attackers skip all auth by setting one
HTTP header. The Kubernetes API server bug (CVE-2018-1002105) gave any user admin privileges
through a half-open connection. The Apache path traversal (CVE-2021-41773) bypassed access
controls entirely. These bugs share a theme: the auth check existed but could be circumvented.

## The Core Question

> "Can a request reach the protected resource WITHOUT passing through the authentication
> and authorization check? Is there ANY path — alternative route, header manipulation,
> encoding trick, error condition — that bypasses the check?"

## What To Check

### 1. Middleware/Filter Bypass
Auth implemented in middleware can be bypassed if certain routes skip middleware.

**Red flags:**
- Static file routes, health check endpoints, plugin routes that skip auth middleware
- Framework-internal headers that disable middleware (Next.js x-middleware-subrequest)
- Route ordering where a catch-all matches before the auth middleware runs
- Special URL patterns that bypass the middleware URL matcher

**Review check:** List every route in the application. For each, trace the request through
the middleware chain. Does every route that needs auth actually hit the auth middleware?
Are there any routes that were added after the auth middleware was configured?

```
# Danger: does the plugin route skip auth?
/api/users → [auth middleware] → handler  ✓
/api/admin → [auth middleware] → handler  ✓
/plugins/x → handler  ✗ BYPASSES AUTH
/health → handler  (intentional, but verify it exposes nothing sensitive)
```

### 2. HTTP Header Trust
Never trust HTTP headers for security decisions unless the reverse proxy strips them.

**Red flags:**
```
X-Forwarded-For: (can be spoofed unless proxy strips and re-adds)
X-Real-IP: (same)
X-Original-URL: (IIS/nginx can rewrite, attacker can set)
X-Middleware-Subrequest: (Next.js internal header, attacker can set)
Host: (can be manipulated for host header injection)
```

**Review check:** For every HTTP header used in security logic: can an external client
set this header? Does the load balancer/reverse proxy strip it before forwarding?

### 3. Object-Level Authorization (IDOR)
Authentication says "who are you." Authorization says "can you access THIS object."
Many APIs check the former but not the latter.

**Red flags:**
```python
# BAD: any authenticated user can access any order
@login_required
def get_order(request, order_id):
    return Order.objects.get(id=order_id)

# GOOD: verify ownership
@login_required
def get_order(request, order_id):
    return Order.objects.get(id=order_id, user=request.user)
```

**Review check:** For every endpoint that accepts a resource ID: is there a check that
the authenticated user has permission to access that specific resource? Not just "is a
user logged in" but "does THIS user own/have access to THIS resource?"

### 4. Function-Level Authorization
The UI hides the admin button, but does the API enforce it?

**Red flags:**
- Admin endpoints relying on frontend to hide them
- Missing role/permission check on individual endpoints
- Shared controller handling both user and admin requests with conditional logic

**Review check:** For every admin/privileged endpoint: what happens if a regular user
sends a direct HTTP request to it? Is the permission check in server-side code, not just
in the UI?

### 5. Mass Assignment / Privilege Field Injection
**Red flags:**
```python
# BAD: binding all request fields to model
user.update(**request.data)
# Attacker sends: {"name": "Alice", "is_admin": true, "role": "superuser"}

# GOOD: explicit allowlist
allowed = ['name', 'email', 'bio']
user.update(**{k: v for k, v in request.data.items() if k in allowed})
```

**Review check:** Can the user set fields they shouldn't through API requests? Is there
an explicit allowlist of fields that can be set, or does the endpoint blindly bind all
incoming data to the model?

### 6. Authentication State Transitions
Session management has specific requirements around login, logout, and privilege changes.

**Review check:**
- Is the session ID regenerated after login? (prevent session fixation)
- Is the session invalidated on logout? (not just clearing the client cookie)
- Is the session invalidated on password change? (all other sessions)
- Are all sessions revocable? (for breach response)
- Is re-authentication required for sensitive operations? (password change, email change)

### 7. JWT and Token Validation
**Red flags:**
```python
# BAD: no algorithm restriction
jwt.decode(token, key)  # may accept "alg": "none"

# BAD: no expiry check
payload = jwt.decode(token, key, algorithms=["HS256"])
# but payload has no "exp" claim

# BAD: symmetric key from source code
SECRET = "hardcoded-secret-in-source-code"
jwt.encode(payload, SECRET)

# GOOD:
jwt.decode(token, key, algorithms=["RS256"], options={"require": ["exp", "iss"]})
```

**Review check:**
- Is the algorithm explicitly restricted (allowlist, not blocklist)?
- Is expiry (`exp`) required and checked?
- Are issuer (`iss`) and audience (`aud`) validated?
- Is the signing key strong and properly managed (not in source code)?
- Can an RSA public key be confused with an HMAC secret?

### 8. OAuth/OIDC Implementation
**Red flags:**
- Missing `state` parameter (CSRF on callback)
- Redirect URI validated with prefix match instead of exact match
- Token stored in localStorage (accessible to XSS)
- Missing PKCE for public clients

**Review check:** Does the OAuth flow use state parameter, exact redirect URI matching,
PKCE for public clients, and secure token storage?

### 9. Cookie Security
**Red flags:**
```
Set-Cookie: session=abc123
# Missing: Secure, HttpOnly, SameSite, Path, Domain restrictions

# GOOD:
Set-Cookie: session=abc123; Secure; HttpOnly; SameSite=Strict; Path=/
```

**Review check:** Are auth cookies set with Secure (HTTPS only), HttpOnly (no JS access),
SameSite (CSRF protection), and appropriately scoped Domain and Path?

### 10. Error Path Authorization
**Red flags:**
- Error responses that leak information about resource existence
  (`404 Not Found` vs `403 Forbidden` tells attacker the resource exists)
- Error handling that fails open (granting access on error rather than denying)
- Catch-all exception handlers that skip authorization checks

**Review check:** On error, does the system fail closed (deny access) or fail open (grant
access)? Do error responses leak authorization information?

## The Authorization Audit

For a comprehensive auth review, build an access control matrix:

```
Endpoint            | Anonymous | User  | Admin | Service
--------------------|-----------|-------|-------|--------
GET /api/users      | 403       | own   | all   | all
POST /api/users     | 200       | 403   | 200   | 200
PUT /api/users/:id  | 403       | own   | all   | all
DELETE /api/users/:id| 403      | 403   | 200   | 200
GET /admin/dashboard| 403       | 403   | 200   | 403
```

Then verify each cell with an actual request.

## Catalog References
- A1 (Next.js middleware bypass) — internal header trusted from external clients
- A2 (K8s API server) — error path not closing authenticated connection
- A4 (Tomcat) — path normalization inconsistency between auth and handler
- A5 (runc container escape) — privileged process entering untrusted namespace
- M31 (Grafana) — plugin routes bypassing auth middleware
- M33 (Jenkins) — new endpoints missing auth checks
- I30 (IDOR) — object access without ownership check
- I31 (Mass Assignment) — privilege fields settable via API
