# Skill 16: Web Application Security

The Next.js middleware bypass let attackers skip auth with one header. The Apache path
traversal bypassed access controls with double-encoded dots. CORS misconfigurations
expose APIs to any origin. These aren't exotic attacks — they're the bread and butter of
web security, and they still appear in major frameworks.

## The Core Question

> "Can an attacker make the application do something its developers didn't intend, through
> any combination of HTTP headers, parameters, cookies, URLs, or request formatting?"

## What To Check

### 1. XSS Prevention
Modern frameworks escape by default, but escape hatches exist.

**Framework escape hatches to search for:**
- React: `dangerouslySetInnerHTML`
- Vue: `v-html` directive
- Angular: `bypassSecurityTrust*` methods
- Svelte: `{@html expression}`
- Laravel Blade: `{!! $var !!}` (unescaped)
- Jinja2: `{{ var | safe }}`, `{% autoescape false %}`
- EJS: `<%- var %>` (unescaped)
- Go html/template: `template.HTML()` type cast

**Additional XSS vectors:**
```html
<!-- href with user URL — javascript: protocol bypasses HTML escaping -->
<a href={userUrl}>  <!-- Must filter javascript:, data:, vbscript: -->

<!-- Event handlers with user data -->
<div onclick="doThing('{{userData}}')">  <!-- Template escaping != JS escaping -->

<!-- CSS injection -->
<div style="background: url({{userUrl}})">
```

**Review check:** Search for every escape hatch in the framework. For each: trace the
data source. Even API/database sources may contain user-generated content.

### 2. CSRF Protection
**Review check:**
- Does every state-changing endpoint verify a CSRF token?
- For cookie-based auth: is SameSite set to Strict or Lax?
- For APIs: is CORS properly configured to reject cross-origin requests?
- For WebSocket: is the Origin header validated during handshake?

### 3. Content Security Policy
**Red flags:**
```
# BAD: allows inline scripts (defeats XSS protection)
Content-Security-Policy: script-src 'self' 'unsafe-inline'

# BAD: allows eval (defeats XSS protection)
Content-Security-Policy: script-src 'self' 'unsafe-eval'

# BAD: allows any HTTPS source
Content-Security-Policy: script-src https:

# GOOD: nonce-based
Content-Security-Policy: script-src 'nonce-abc123' 'strict-dynamic'
```

**Review check:** Is CSP deployed? Does it avoid `unsafe-inline` and `unsafe-eval`?
Is it using nonce-based or hash-based script allowlisting?

### 4. URL Validation for Redirects and SSRF
**Red flags:**
```python
# BAD: open redirect
redirect_url = request.args['next']
return redirect(redirect_url)
# Attacker: ?next=//evil.com or ?next=\/evil.com

# BAD: SSRF
url = request.args['url']
response = requests.get(url)  # can access internal services
# Attacker: ?url=http://169.254.169.254/metadata (AWS metadata)
# Attacker: ?url=http://localhost:6379 (Redis)
```

**Review check for redirects:** Is the redirect URL validated against an allowlist of
hosts, or verified to be a relative path without protocol?

**Review check for SSRF:** Is the URL's resolved IP checked against internal/cloud
metadata ranges? Is the DNS resolution done by the validator (not separately)?

### 5. File Upload Security
**Review check:**
- Is the filename sanitized (stripped of path separators, limited characters)?
- Is the file type validated by content (magic bytes), not just extension?
- Are uploaded files stored outside the webroot?
- Are uploaded files served from a separate domain (prevents XSS via HTML uploads)?
- Is there a maximum file size?
- Can an attacker upload executable files (.php, .jsp, .aspx)?

### 6. HTTP Security Headers
**Complete review checklist:**
```
Strict-Transport-Security: max-age=31536000; includeSubDomains
Content-Security-Policy: [appropriate policy]
X-Content-Type-Options: nosniff
X-Frame-Options: DENY  (or use CSP frame-ancestors)
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

### 7. Session Management
**Review check:**
- Are session IDs generated with a CSPRNG (128+ bits of entropy)?
- Is the session ID regenerated after login?
- Is there a session timeout (idle and absolute)?
- Are sessions invalidated on logout (server-side, not just cookie deletion)?
- Are concurrent session limits enforced if needed?
- Is session data stored server-side (not in client cookies without encryption)?

### 8. API Security
**Review check:**
- Is input validated with explicit schemas (Zod, JSON Schema, OpenAPI)?
- Are rate limits applied per-user or per-IP?
- Are pagination limits enforced (max page size)?
- Are nested/batch requests bounded in depth and count?
- Is the API versioned to prevent breaking changes?

### 9. WebSocket Security
**Review check:**
- Is the Origin header validated during the handshake?
- Is authentication checked (cookies or token in first message)?
- Are message sizes limited?
- Is there rate limiting on messages?
- Are messages validated/sanitized the same as HTTP request data?

### 10. Third-Party Integration Security
**Review check:**
- Are OAuth redirect URIs validated with exact matching?
- Are webhook payloads verified with signatures (HMAC)?
- Are third-party scripts loaded with SRI (Subresource Integrity)?
- Are third-party iframes sandboxed?

## Catalog References
- A1 (Next.js middleware bypass) — framework-internal header trusted from client
- M32 (Apache path traversal) — double-encoded path traversal
- I15 (dangerouslySetInnerHTML) — React XSS via escape hatch
- I16 (CSRF) — missing CSRF protection on cookie-authed APIs
- I17 (JWT alg:none) — algorithm confusion in JWT validation
- I18 (Open redirect) — URL validation bypass
- I19 (HTTP smuggling) — parser disagreement between proxy and app
