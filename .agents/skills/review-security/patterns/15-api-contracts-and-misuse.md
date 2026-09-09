# Skill 15: API Contracts and Misuse Resistance

The Debian OpenSSL disaster happened because a maintainer "fixed" a Valgrind warning
without understanding why the code used uninitialized memory (it was the entropy source).
OpenSSL's API is famously easy to misuse — the error handling requirements, initialization
sequence, and threading model are full of traps. When an API is easy to misuse, it WILL
be misused.

## The Core Question

> "Is this API being used according to its contract? Are preconditions met, error returns
> checked, and resource lifecycle followed? Could a reasonable developer misuse this API
> in a dangerous way?"

## What To Check

### 1. Error Return Values
Many C APIs return error codes that callers silently ignore.

**Red flags:**
```c
// BAD: ignoring return values
close(fd);          // can fail (EINTR)
write(fd, buf, n);  // can write fewer bytes than requested
malloc(size);        // can return NULL

// GOOD: checking returns
if (close(fd) < 0 && errno == EINTR) { /* retry */ }
ssize_t written = write(fd, buf, n);
if (written < 0 || (size_t)written < n) { /* handle partial write */ }
void *p = malloc(size);
if (!p) { /* handle OOM */ }
```

**Review check:** For every function call: what does it return on error? Is the error
return checked? In Go: is `_` used to discard an error value?

### 2. Initialization and Teardown Sequence
Many libraries require specific initialization before use and cleanup after.

**Red flags:**
```c
// OpenSSL requires initialization before use
// BAD: using OpenSSL without initialization
SSL_CTX *ctx = SSL_CTX_new(TLS_method());  // may fail silently

// GOOD: proper initialization
SSL_library_init();
SSL_load_error_strings();
OpenSSL_add_all_algorithms();
```

**Review check:** Does the library have required initialization? Is it called before
first use? Is teardown called on shutdown?

### 3. Thread Safety Contracts
APIs have different thread safety levels, and violating them causes subtle bugs.

**Thread safety levels:**
- **Thread-safe:** can be called from any thread concurrently (most Go stdlib)
- **Thread-compatible:** can be called from any thread, but not concurrently (most C++)
- **Thread-hostile:** must be called from a specific thread (UI toolkits, signal handlers)

**Red flags:**
```go
// BAD: using non-thread-safe API from multiple goroutines
var client http.Client  // safe
var transport http.Transport  // safe
var body io.Reader  // typically NOT safe to share
```

**Review check:** Is the API documented as thread-safe? If not, is access serialized?
If shared between threads/goroutines, is there proper synchronization?

### 4. Ownership and Lifetime Semantics
Who owns the returned pointer/resource? Who is responsible for freeing it?

**Red flags:**
```c
// Ambiguous: does get_name() return a new string or a pointer to internal state?
char *name = get_name(obj);
free(name);  // correct if caller owns it; double-free if library owns it
```

**Review check:** For every pointer/resource returned by an API:
- Does the caller or the library own it?
- Is ownership documented?
- Is the lifetime documented (valid until next call? valid until object is freed?)

### 5. Precondition Violations
**Red flags:**
```python
# BAD: calling API without meeting preconditions
os.path.join(base, user_input)  # os.path.join doesn't prevent traversal
# Precondition: user_input should not contain ../

# BAD: using API in wrong context
asyncio.get_event_loop()  # fails if not in async context (Python 3.10+)
```

**Review check:** What are the API's preconditions? Does the calling code guarantee them?
Are preconditions checked by the API (defensive) or assumed (dangerous)?

### 6. Resource Cleanup Patterns
**Language-specific patterns for ensuring cleanup:**

```go
// Go: defer
f, err := os.Open(path)
if err != nil { return err }
defer f.Close()
```

```python
# Python: context manager
with open(path) as f:
    data = f.read()
```

```java
// Java: try-with-resources
try (Connection conn = dataSource.getConnection()) {
    // use connection
}
```

```rust
// Rust: RAII / Drop trait
let f = File::open(path)?;  // dropped automatically
```

```c++
// C++: RAII / smart pointers
auto ptr = std::make_unique<Widget>();  // deleted automatically
```

**Review check:** Is every acquired resource released? Is the cleanup pattern idiomatic
for the language? Can an exception/error between acquisition and cleanup cause a leak?

### 7. API Versioning and Deprecation
**Red flags:**
- Using deprecated API that has known security issues
- Using API that behaves differently in different versions
- Assuming specific behavior of an undocumented API

**Review check:** Is the API version documented? Is it deprecated? Are there known
security issues with this version?

### 8. Platform-Specific API Behavior
**Red flags:**
```c
// BAD: assuming POSIX behavior on Windows
int fd = open(path, O_CREAT | O_EXCL);  // atomic on POSIX, not guaranteed on Windows

// BAD: assuming Linux behavior on macOS
getentropy(buf, 256);  // exists on both, but behavior may differ

// BAD: assuming specific locale
setlocale(LC_ALL, "");  // behavior depends on system configuration
```

**Review check:** Does the code make platform-specific assumptions? Will it work
correctly on all target platforms?

### 9. Encoding and Format Contracts
**Red flags:**
```python
# BAD: assuming input encoding
text = data.decode('utf-8')  # may fail on non-UTF-8 input

# BAD: assuming date format
date = datetime.strptime(input, '%Y-%m-%d')  # may fail on other formats

# BAD: assuming line ending
lines = text.split('\n')  # misses \r\n on Windows
```

**Review check:** Are encoding/format assumptions documented and validated? What happens
when the input doesn't match the expected format?

### 10. Misuse-Resistant API Design (When Writing APIs)
When creating APIs, design them so the easy/obvious usage is the secure usage.

**Principles:**
- Secure defaults (no need to opt-in to security)
- Hard to misuse (make the dangerous path require explicit action)
- Clear ownership (who frees? who closes?)
- Fail loudly (errors should not be silently ignored)
- Context-appropriate (GFP_KERNEL vs GFP_ATOMIC in kernel)

**Review check (for API authors):** Can a developer use this API correctly just by
reading the function signature and a one-line description? What's the most likely mistake,
and does the API detect and report it?

## Catalog References
- C1 (Debian PRNG) — OpenSSL API misunderstood, entropy source removed
- C6 (Bleichenbacher) — TLS API misuse leading to timing side channels
- AA24 (Coccinelle API misuse) — semantic patterns catching wrong arguments, missing error checks, locking errors
- AA25 (sparse boundary checks) — static analysis catching missing __user annotations at kernel/userspace boundary
- I12 (child_process.exec) — exec vs execFile API confusion
- L5 (Error code ignored) — API contract for error handling not followed
