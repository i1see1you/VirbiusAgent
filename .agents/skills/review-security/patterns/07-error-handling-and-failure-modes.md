# Skill 07: Error Handling and Failure Modes

Apple's goto fail skipped certificate verification because of a misplaced error path.
The Kubernetes API server escalation (CVE-2018-1002105) existed because an error response
didn't close the connection. The Apache Struts RCE (CVE-2017-5638) happened because error
messages went through the same expression evaluator as normal messages.

Error paths are where security bugs live, because they're the paths least tested, least
reviewed, and most likely to be written hastily.

## The Core Question

> "When this code fails, does it fail safely? Are resources cleaned up? Is the system
> left in a consistent state? Does the error path have the same security properties
> as the happy path?"

## What To Check

### 1. Fail Open vs. Fail Closed
**Red flags:**
```python
# BAD: fail open — error grants access
def check_auth(request):
    try:
        verify_token(request.token)
        return True
    except Exception:
        return True  # "let them through on error" — catastrophic

# GOOD: fail closed — error denies access
def check_auth(request):
    try:
        verify_token(request.token)
        return True
    except Exception:
        return False  # deny on error
```

**Review check:** When authentication/authorization code encounters an error, does it
deny access (fail closed) or grant access (fail open)? Search for catch/except blocks in
auth code paths.

### 2. Swallowed Errors
**Red flags:**
```go
// BAD: error silently ignored
result, _ := doImportantThing()

// BAD: empty catch block
try:
    validate_input(data)
except Exception:
    pass  # silently continue with invalid data

// BAD in Java:
catch (Exception e) {
    // TODO: handle this later
}
```

**Review check:** Search for `_ :=` in Go, empty `except`/`catch` blocks, and TODO
comments in error handlers. Every error should be either handled, propagated, or
explicitly documented as ignorable with a reason.

### 3. Error Path Security
The error path must have the same security properties as the happy path.

**Red flags:**
```python
# BAD: error response leaks internal information
except DatabaseError as e:
    return {"error": str(e)}  # exposes table names, query, connection details

# BAD: error path skips cleanup
def process_request():
    lock.acquire()
    if error:
        return error_response  # lock not released!
    lock.release()
    return success_response
```

**Review check:**
1. Does the error path clean up ALL resources (locks, connections, files, temp data)?
2. Does the error response expose internal details (stack traces, SQL, file paths)?
3. Does the error path skip any security checks the happy path performs?
4. Can the error path be triggered intentionally by an attacker?

### 4. Error Code Propagation
**Red flags:**
```c
// BAD: error code lost in translation
int result = low_level_func();  // returns -ENOMEM
if (result)
    return -1;  // specific error code lost

// BAD: wrong error semantics
ssize_t n = read(fd, buf, count);
if (n < 0) {
    if (errno == EAGAIN) return 0;  // not an error, but returns success
}
```

**Review check:** When errors cross module/layer boundaries, is the error information
preserved? Is the error code semantics correct for the receiving context?

### 5. Exception Safety Levels
In C++ and other languages with exceptions, code must maintain invariants even when
exceptions occur.

**Red flags:**
- Non-RAII resource management with exception-throwing code between acquire and release
- Partial state modification that isn't rolled back on exception
- Constructor that partially initializes an object before throwing

**Review check:** If an exception is thrown at any point in the function, is the program
state still consistent? Are all resources cleaned up?

### 6. Partial Failure in Distributed Operations
**Red flags:**
```python
# BAD: partial failure leaves inconsistent state
def transfer_money(from_account, to_account, amount):
    from_account.balance -= amount  # succeeds
    to_account.balance += amount     # fails — money disappeared!

# GOOD: transactional
with db.transaction():
    from_account.balance -= amount
    to_account.balance += amount
    # both succeed or both fail
```

**Review check:** For operations that modify multiple records/services: is the operation
transactional? If one step fails, is the previous step rolled back?

### 7. Error Information Disclosure
Error messages should help developers debug without helping attackers attack.

**Red flags:**
```python
# BAD: exposes internals
return {"error": f"Query failed: {sql_query} with params {params}"}
return {"error": traceback.format_exc()}
return {"error": f"File not found: {internal_path}"}
return {"error": f"Connection failed: {db_host}:{db_port}"}

# GOOD: generic external, detailed internal
logger.error(f"Query failed: {sql_query}", exc_info=True)
return {"error": "An internal error occurred", "id": error_id}
```

**Review check:** Are error responses to external users/clients generic? Are detailed
errors logged server-side with correlation IDs?

### 8. Panic/Crash Safety
**Red flags:**
```go
// BAD: panic in HTTP handler crashes the whole server
func handler(w http.ResponseWriter, r *http.Request) {
    data := r.URL.Query()["key"]
    value := data[0]  // panics if key not present
}

// BAD: assert/panic in library code called by server
func parseInput(data []byte) Result {
    if len(data) == 0 {
        panic("empty input")  // crashes caller's server
    }
}
```

**Review check:** Can user input trigger a panic/crash/assertion failure? Library code
should return errors, not panic. Server code should have recovery middleware.

### 9. Retry Logic Safety
**Red flags:**
```python
# BAD: retrying non-idempotent operation
def create_order(order):
    for i in range(3):
        try:
            result = api.post('/orders', order)
            return result
        except TimeoutError:
            continue  # might create duplicate orders!
```

**Review check:** Are retried operations idempotent? For non-idempotent operations,
is there an idempotency key or deduplication mechanism?

### 10. Graceful Degradation
**Red flags:**
- Service that crashes entirely when a non-critical dependency is unavailable
- Request that fails completely when an optional feature (analytics, logging) errors
- Cache miss that causes a hard failure instead of a slow path

**Review check:** Can the service operate in a degraded mode when non-critical
dependencies fail? Are critical and non-critical errors handled differently?

## The Error Path Audit

For every function:
1. **Enumerate error sources** — what can fail? (I/O, allocation, validation, external calls)
2. **Trace each error path** — what happens after the error? (return, throw, panic, goto)
3. **Check resource cleanup** — are all acquired resources released on the error path?
4. **Check state consistency** — is the system in a valid state after the error?
5. **Check information disclosure** — does the error reveal internal details?
6. **Check security properties** — does the error path bypass any security checks?

## Catalog References
- C2 (Apple goto fail) — misplaced goto caused security check to be skipped
- A2 (K8s API server) — error didn't close connection, leaving authenticated channel open
- M28 (Struts OGNL) — error messages evaluated as expressions
- L5 (Error code ignored) — unchecked return values
- L22 (Memory leak in error path) — early return skipping cleanup
- CR4 (GKH error path cleanup) — kernel maintainer catching missing goto err_free
