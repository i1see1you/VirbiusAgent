# Skill 19: Logging, Observability, and Information Disclosure

Log4Shell (CVE-2021-44228) turned logging into a remote code execution vector. Verbose
error messages expose database schemas, file paths, and internal IPs. Debug endpoints left
enabled in production expose profiling data, metrics, and sometimes live memory dumps.
Logging is simultaneously a critical security tool AND a security risk.

## The Core Question

> "Does logging capture enough detail for incident response without leaking sensitive
> data to attackers or creating new attack vectors?"

## What To Check

### 1. Log Injection
**Red flags:**
```python
# BAD: user input directly in log messages (Log4Shell pattern)
logger.info(f"User login: {username}")
# If logging framework performs template substitution (Log4j): RCE
# If not: log forgery via newlines

# BAD: user input can forge log entries
username = "admin\n2024-01-01 12:00:00 INFO User admin logged out"
logger.info(f"User login: {username}")
# Creates a fake "logged out" entry

# GOOD: sanitize user input in logs
logger.info("User login: %s", sanitize_for_log(username))
# Or: structured logging
logger.info("user_login", extra={"username": username})  # value, not interpolation
```

**Review check:**
- Can user input contain characters that the logging framework interprets as special?
  (Log4j: `${...}`, general: `\n`, `\r`, ANSI escape codes)
- Is structured logging (JSON) used instead of string formatting?
- Are log messages parameterized (values as separate fields, not concatenated into strings)?

### 2. Sensitive Data in Logs
**Red flags:**
```python
# BAD: logging credentials
logger.info(f"Connecting to DB: {db_connection_string}")  # password in conn string
logger.debug(f"Auth header: {request.headers['Authorization']}")  # token
logger.error(f"Login failed for {username} with password {password}")  # password!
logger.info(f"Processing payment: {credit_card_number}")  # PII/PCI

# GOOD: redact sensitive fields
logger.info("DB connection established to %s", db_host)  # only host, no creds
logger.info("Auth: Bearer %s...%s", token[:4], token[-4:])  # truncated
logger.info("Processing payment for card ending in %s", card[-4:])
```

**Review check:** Search logs for: password, secret, token, key, authorization, cookie,
session, credit_card, ssn, email, phone. Are any of these logged in full?

### 3. Error Message Information Disclosure
**Red flags:**
```python
# BAD: stack trace in API response
@app.errorhandler(500)
def error(e):
    return {"error": traceback.format_exc()}, 500
# Exposes: file paths, library versions, code structure, variable values

# BAD: SQL error in response
except DatabaseError as e:
    return {"error": str(e)}, 500
# Exposes: table names, column names, query structure

# GOOD: generic external, detailed internal
@app.errorhandler(500)
def error(e):
    error_id = uuid4()
    logger.error("Internal error %s: %s", error_id, e, exc_info=True)
    return {"error": "Internal error", "id": str(error_id)}, 500
```

**Review check:**
- Do error responses to clients contain only generic messages?
- Are stack traces, SQL errors, and file paths logged server-side only?
- Is there a correlation ID connecting client error responses to server logs?

### 4. Debug Endpoints in Production
**Red flags:**
```python
# BAD: debug endpoints accessible in production
app.debug = True  # Flask: enables debugger, which has RCE!

# BAD: profiling endpoints
/debug/pprof        # Go: memory/CPU profiles
/debug/vars         # Go: all exported variables
/metrics            # Prometheus: internal metrics
/__debug__/         # Django debug toolbar
/actuator           # Spring Boot: health, env, heap dump
/server-status      # Apache: request details
/nginx_status       # nginx: connection info
```

**Review check:**
- Is debug mode disabled in production?
- Are debug/profiling endpoints either disabled or behind authentication?
- Do health check endpoints expose only liveness, not internal details?

### 5. Audit Logging Completeness
**Review check:** Are these security-relevant events logged?
- Authentication: login success, login failure, logout
- Authorization: access denied, privilege escalation attempts
- Data: read of sensitive data, modification, deletion
- Admin: configuration changes, user management
- System: service start/stop, errors, crashes

For each logged event, is the following captured?
- Who: authenticated user/service identity
- What: action performed and on which resource
- When: timestamp (UTC, with timezone)
- Where: source IP, user agent, request ID
- Result: success or failure

### 6. Log Storage Security
**Review check:**
- Are logs protected from tampering (append-only, shipped to remote storage)?
- Are logs encrypted at rest and in transit?
- Is log access restricted (not world-readable)?
- Are logs retained for an appropriate duration?
- Can an attacker with application access delete or modify logs?

### 7. Metrics and Monitoring Security
**Red flags:**
- Custom metrics with high-cardinality labels (user IDs, IPs) — can be exploited for
  metric cardinality DoS
- Metrics containing sensitive values (request parameters, headers)
- Monitoring dashboards without authentication

**Review check:**
- Are metric labels bounded in cardinality?
- Do metrics exclude sensitive data?
- Are monitoring dashboards behind authentication?

### 8. Timing Information Disclosure
**Red flags:**
```python
# BAD: different response times for "user not found" vs "wrong password"
def login(username, password):
    user = db.get_user(username)
    if not user:
        return error("invalid credentials")  # fast
    if not verify_password(password, user.password_hash):
        return error("invalid credentials")  # slow (bcrypt comparison)
    # Attacker can enumerate valid usernames by timing
```

**Review check:** Do authentication/authorization responses take the same time regardless
of which check failed? Is there a constant-time code path for "user not found"?

## Catalog References
- I1 (Log4Shell) — logging framework evaluating user data as templates
- I40 (CRLF in logs) — log injection via newlines
- O1 (Debug mode) — debug features in production
- O2 (Verbose errors) — stack traces in error responses
- O3 (Logging PII) — sensitive data in logs
- O4 (Health endpoint) — internal info in health checks
- O13 (Metrics endpoint) — unprotected monitoring
