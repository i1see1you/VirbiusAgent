# Skill 05: Race Conditions and Concurrency

Dirty COW (CVE-2016-5195) hid in the Linux kernel for 9 years because the race window was
measured in nanoseconds. The regreSSHion bug (CVE-2024-6387) was a signal handler race
reintroduced by a logging refactor 14 years after the original fix. Dirty Pipe
(CVE-2022-0847) emerged from a flag not being cleared when data moved between contexts.

Concurrency bugs are the hardest to catch in review because the code looks correct for
any single execution — the bug only exists in the interaction between concurrent executions.

## The Core Question

> "If two threads/processes/signals execute this code simultaneously, or if an interrupt
> arrives between any two instructions, is the result always correct?"

## What To Check

### 1. Shared Mutable State Without Synchronization
If two threads can read AND write the same memory location without a lock, mutex, or atomic
operation, there is a data race.

**Red flags:**
```go
// BAD: concurrent map access in Go causes panic
var cache = make(map[string]string)
// goroutine 1: cache["key"] = "value"
// goroutine 2: v := cache["key"]

// GOOD:
var cache sync.Map
cache.Store("key", "value")
v, _ := cache.Load("key")
```

**Review check:** For every shared variable (global, struct field accessed by multiple
goroutines/threads): is every access protected by a lock, atomic operation, or channel?
In Go: can you run with `-race` flag? In C/C++: does ThreadSanitizer (TSan) pass?

### 2. TOCTOU (Time-of-Check-to-Time-of-Use)
Checking a condition and then acting on it as two separate operations creates a window
where the condition can change.

**Red flags:**
```c
// BAD: file TOCTOU
if (access(path, R_OK) == 0) {  // check
    fd = open(path, O_RDONLY);   // use — may be different file now
}

// GOOD: open first, then check
fd = open(path, O_RDONLY | O_NOFOLLOW);
if (fd >= 0) {
    // fstat(fd, ...) to verify the opened file
}
```

```python
# BAD: check-then-act
if os.path.exists(filepath):
    with open(filepath) as f:  # file could be replaced/deleted between check and open
        data = f.read()

# GOOD: just try and handle errors
try:
    with open(filepath) as f:
        data = f.read()
except FileNotFoundError:
    handle_missing()
```

**Review check:** Is any security decision (permission check, existence check, type check)
made in a separate step from the action? Can the checked property change between check and
use?

### 3. Signal Handler Safety
Signal handlers interrupt normal execution at arbitrary points. If the handler calls
functions that use internal state (malloc, printf, syslog), it can corrupt that state.

**The async-signal-safe function list is very short:**
`_exit`, `write`, `signal`, `sigaction`, and a few others. Notably NOT safe: `malloc`,
`free`, `printf`, `syslog`, `pthread_mutex_lock`.

**Red flags:**
```c
// BAD: calling non-async-signal-safe functions in signal handler
void handler(int sig) {
    syslog(LOG_ERR, "caught signal %d", sig);  // NOT safe
    free(global_ptr);  // NOT safe
    exit(1);  // NOT safe — use _exit()
}

// GOOD: set a flag, handle in main loop
volatile sig_atomic_t got_signal = 0;
void handler(int sig) {
    got_signal = 1;  // sig_atomic_t write is safe
}
```

**Review check:** Does every signal handler call ONLY async-signal-safe functions? If
the handler does logging, memory management, or I/O, it's vulnerable to the regreSSHion
pattern.

### 4. Lock Ordering
Deadlock occurs when two threads acquire the same locks in different orders.

**Red flags:**
```
Thread A: lock(mutex_a) → lock(mutex_b)
Thread B: lock(mutex_b) → lock(mutex_a)  // DEADLOCK
```

**Review check:** Is there a defined global lock ordering? Do all code paths acquire
locks in the same order? Can you document the lock hierarchy?

### 5. Flag/Metadata Inheritance Across Contexts
When data moves between contexts (splice, migration, fork), metadata from the source
context may be inappropriate in the destination context.

**Red flags (the Dirty Pipe pattern):**
```c
// When data moves from context A to context B:
// Are all flags/metadata from A appropriate for B?
// Should any flags be cleared, changed, or revalidated?
```

**Review check:** When a data structure is moved, copied, or shared between contexts
(processes, namespaces, file systems, pipe buffers): are all metadata fields appropriate
for the new context? Should any be cleared?

### 6. Atomic Operations and Memory Ordering
On weakly-ordered architectures (ARM, POWER), reads and writes can be reordered. Code that
works on x86 may fail on ARM.

**Red flags:**
```c
// BAD: no memory barrier on weak architectures
ready_flag = 1;  // another CPU may see this before the data it protects
data = important_value;

// GOOD: explicit memory ordering
smp_wmb();  // write memory barrier
ready_flag = 1;
```

**Review check:** For lock-free data structures and flag-based synchronization: are
appropriate memory barriers or atomic operations used? Does the code work on ARM/POWER,
not just x86?

### 7. Goroutine/Thread Lifecycle
Goroutines and threads that are created but never properly terminated leak resources.

**Red flags:**
```go
// BAD: goroutine blocked forever if channel is never written
go func() {
    result := <-ch  // blocks forever if sender exits
    process(result)
}()

// GOOD: context cancellation
go func() {
    select {
    case result := <-ch:
        process(result)
    case <-ctx.Done():
        return  // clean exit
    }
}()
```

**Review check:** For every goroutine/thread: what causes it to exit? Can it block on
a channel/lock that will never be released? Is there a cancellation mechanism?

### 8. Double-Checked Locking
The classic concurrency pitfall. Only works correctly with specific memory ordering
guarantees.

**Red flags:**
```java
// BAD in Java (before volatile fix):
if (instance == null) {
    synchronized(lock) {
        if (instance == null) {
            instance = new Singleton();  // may be partially constructed
        }
    }
}

// GOOD in Java 5+:
private volatile static Singleton instance;  // volatile required
```

**Review check:** Does double-checked locking use `volatile` (Java 5+), `atomic`
operations (C++11), or equivalent memory ordering guarantees?

### 9. Connection/Resource Pool Exhaustion
When all connections in a pool are in use, new requests block. If a single transaction
can hold multiple connections, deadlock is possible.

**Red flags:**
- Nested queries that each need a connection from the same pool
- Long-running transactions holding connections while waiting for external I/O
- No timeout on connection acquisition
- Pool size smaller than max concurrent request count

**Review check:** Can a single request hold multiple connections? Is there a timeout on
connection acquisition? What happens at max concurrency?

### 10. Cache Stampede / Thundering Herd
When a cached value expires, all concurrent requests race to regenerate it, overloading
the backend.

**Red flags:**
- Cache expiry without lock/singleflight for regeneration
- TTL-only expiry without probabilistic early refresh
- No circuit breaker on backend calls

**Review check:** When a cache entry expires, is regeneration serialized? Can 1000
concurrent requests all try to regenerate the same cache entry simultaneously?

## Concurrency Review Strategy

1. **Identify shared state** — globals, struct fields, database rows, files, caches
2. **Identify concurrent accessors** — threads, goroutines, signal handlers, processes
3. **Check synchronization** — locks, atomics, channels, transactions
4. **Check ordering** — lock hierarchy, memory barriers, happens-before relationships
5. **Check cleanup** — are resources released on all paths, including error and timeout?
6. **Check lifecycle** — can a referenced object be freed while still in use?

## Catalog References
- R1 (Dirty COW) — race between COW and madvise
- R2 (Dirty Pipe) — flag not cleared across splice
- R3 (TOCTOU) — check-then-use pattern
- R4 (Signal handler) — async-signal-unsafe function calls
- M7 (regreSSHion) — signal handler race reintroduced by refactoring
- M12 (Netfilter UAF) — missing RCU synchronization
