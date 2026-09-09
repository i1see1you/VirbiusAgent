# Skill 06: Memory and Resource Lifecycle

Chrome V8's type confusion bugs, Firefox's DOM use-after-free vulnerabilities, and the
Linux kernel's netfilter UAF all share a root cause: the code accessed an object that had
already been freed or changed type. Resource lifecycle bugs extend beyond memory — file
descriptors, database connections, locks, and goroutines can all leak or be used after
their valid lifetime.

## The Core Question

> "For every pointer dereference or resource use: is the object guaranteed to be alive
> and valid at this point? What prevents it from being freed, closed, or invalidated
> concurrently?"

## What To Check

### 1. Use-After-Free
The object was freed, but a pointer to it still exists and is dereferenced.

**Red flags:**
```c
// BAD: callback may fire after object is freed
register_callback(obj, on_complete);
free(obj);  // but callback still references obj

// BAD: iterator invalidation
for (item = list_first(list); item; item = list_next(item)) {
    if (should_remove(item)) {
        list_remove(list, item);  // invalidates iterator
    }
}
```

**Review check:** For every pointer/reference stored in a callback, closure, event
handler, or data structure: what guarantees the pointed-to object is still alive when
the callback fires? Is there a mechanism to cancel callbacks when the object is freed?

### 2. Double Free
Freeing the same memory twice corrupts the allocator's internal data structures, often
enabling exploitation.

**Red flags:**
```c
// BAD: error path frees, then cleanup frees again
if (error) {
    free(buf);
    goto cleanup;
}
// ...
cleanup:
    free(buf);  // double free if error path taken
```

**Review check:** For every free/close/release: is the variable set to NULL afterward?
Can any code path reach the same free twice? Are error-path cleanups consistent with
normal-path cleanups?

### 3. Reference Counting Balance
Reference-counted objects (kernel kobjects, COM objects, shared_ptr) must have matched
get/put pairs. Missing put = leak. Extra put = premature free (use-after-free).

**Red flags:**
- `get` without corresponding `put` on error paths
- `put` without `get` (borrowing reference without incrementing)
- Conditional `get` with unconditional `put` (or vice versa)
- Complex control flow where it's unclear which path incremented

**Review check:** For every refcounted object, trace through the function:
1. Where is the reference acquired (get)?
2. On every possible exit path (normal return, error return, exception), is the reference
   released (put) exactly once?
3. In loops: does each iteration properly manage its reference?

### 4. Resource Cleanup on Error Paths
The most common source of resource leaks. Resources allocated before an error occurs
must be freed on the error path.

**Red flags:**
```go
// BAD: file handle leaked on error
f, err := os.Open(path)
if err != nil { return err }
data, err := io.ReadAll(f)
if err != nil { return err }  // f is leaked!
defer f.Close()  // too late — must be before second error check

// GOOD: defer immediately after successful open
f, err := os.Open(path)
if err != nil { return err }
defer f.Close()
```

```python
# BAD: connection leaked if query fails
conn = db.connect()
result = conn.execute(query)  # if this throws, conn leaks
conn.close()

# GOOD: context manager
with db.connect() as conn:
    result = conn.execute(query)
```

**Review check:** For every resource acquisition (open, connect, lock, allocate):
1. Is cleanup registered immediately (defer, try-with-resources, RAII, context manager)?
2. Can any exception/error between acquisition and cleanup registration leak the resource?

### 5. Raw Pointers vs. Smart Pointers (C++)
**Red flags:**
```cpp
// BAD: raw pointer with manual delete
Widget* w = new Widget();
// ... code that might throw or return early ...
delete w;  // might not be reached

// GOOD: smart pointer
auto w = std::make_unique<Widget>();
// automatically cleaned up
```

**Review check:** In C++: is every `new` matched by a smart pointer wrapper? Are raw
pointers used only for non-owning references?

In Rust: is every `unsafe` block that creates raw pointers justified and documented?
Does the unsafe code maintain all safety invariants?

### 6. File Descriptor Leaks
Particularly insidious because they're silent — no crash, just gradual resource exhaustion
until the process hits the FD limit and starts failing.

**Red flags:**
- Opening files in loops without closing
- Socket creation without close-on-exec flag (leaked to child processes)
- Conditional close that doesn't cover all paths

**Review check:** For every `open`, `socket`, `accept`: is there a corresponding `close`
on every code path? Is close-on-exec set for FDs that shouldn't be inherited by child
processes?

### 7. Goroutine Leaks (Go-specific)
Goroutines blocked on channels that are never written to, or waiting for locks that are
never released, leak memory and stack space indefinitely.

**Red flags:**
```go
// BAD: goroutine leaks if nobody reads from ch
func process() {
    ch := make(chan Result)
    go func() {
        ch <- expensiveComputation()  // blocks forever if nobody reads
    }()
    // ... caller returns without reading ch
}

// GOOD: buffered channel or context cancellation
func process(ctx context.Context) {
    ch := make(chan Result, 1)  // buffered: goroutine won't block
    go func() {
        select {
        case ch <- expensiveComputation():
        case <-ctx.Done():
        }
    }()
}
```

**Review check:** For every goroutine: what causes it to exit? Can it block on a channel
operation where the other side has gone away?

### 8. Database Connection Lifecycle
**Red flags:**
- Connection acquired outside of request scope
- Connection not returned to pool on error
- Transaction started but not committed or rolled back on error
- Prepared statements not closed

**Review check:** Is every database connection/transaction managed with defer/context
manager/try-with-resources? Is there a maximum connection lifetime configured?

### 9. Object Lifecycle in Event-Driven Systems
In DOM/UI frameworks, objects referenced by event handlers can be destroyed by the events
they handle.

**Red flags (the Firefox DOM UAF pattern):**
```javascript
// Conceptual pattern:
element.addEventListener('click', () => {
    element.remove();  // element destroyed during its own event handler
    // ... but the event dispatch system still holds a reference
});
```

**Review check:** In event-driven code: can an event handler destroy the object that
owns the handler? Is the event dispatch system safe against mutation during dispatch?

### 10. Memory Leaks in Long-Running Processes
Not a crash bug, but a reliability concern. Common in servers, daemons, and services.

**Red flags:**
- Growing collections (maps, lists) that are added to but never pruned
- Caches without eviction policies or size limits
- Event listener registration without deregistration
- Closures capturing references to large objects

**Review check:** For every data structure that grows over time: is there a mechanism to
bound its size (LRU eviction, TTL, periodic cleanup)?

## Catalog References
- M12 (Netfilter UAF) — missing RCU synchronization
- M13 (Chrome V8 type confusion) — JIT compiler accessing freed/wrong-type memory
- M14 (Firefox DOM UAF) — JavaScript GC + C++ refcount interaction
- R2 (Dirty Pipe) — flag not cleared across splice context
- CR4 (GKH error path cleanup) — kernel maintainer catching missing goto err_free
- CR3 (Al Viro filesystem review) — kernel reviewer catching locking and resource balance issues
