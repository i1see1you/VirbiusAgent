# Skill 01: Bounds Checking and Allocation Safety

Every buffer overflow in history — Heartbleed, GHOST, Stagefright, curl SOCKS5 — shares a
single root cause: a size was trusted when it should have been verified. This skill trains
the reviewer to systematically find missing or incorrect bounds checks.

## The Core Question

> "For every buffer access: where does the size come from, and is it validated against
> the actual buffer capacity before use?"

## What To Check

### 1. External Length Fields
When a length/size/count comes from outside the program (network, file, IPC, user input),
it MUST be validated before use.

**Red flags:**
```c
// BAD: trusting wire length
uint16_t payload_len = ntohs(hdr->length);
memcpy(buf, payload, payload_len);  // Heartbleed pattern

// GOOD: validating against actual received size
uint16_t payload_len = ntohs(hdr->length);
if (payload_len > received_bytes - sizeof(*hdr)) return ERROR;
memcpy(buf, payload, payload_len);
```

**Review check:** Trace every length/size field backward. If it comes from external input,
there must be an explicit bounds check between the read and the use.

### 2. Allocation-Usage Mismatch
When one piece of code calculates the size to allocate and a different piece fills the
buffer, both must agree on the exact size — including alignment, padding, null terminators,
and edge cases like trailing delimiters.

**Red flags:**
- Size calculation in a different function than buffer fill
- Separate loops for "counting" and "copying"
- Manual size arithmetic: `n * sizeof(T) + header_size + padding`
- Off-by-one on null terminator: `malloc(strlen(s))` instead of `malloc(strlen(s) + 1)`

**Review check:** For every allocation, find the code that fills the buffer. Do both
handle the same edge cases? What happens with empty input? Maximum-size input?

### 3. Integer Overflow in Size Arithmetic
Multiplying two values to compute a buffer size can overflow to a small value, leading to
a small allocation followed by a large copy.

**Red flags:**
```c
// BAD: multiplication can overflow
size_t total = count * element_size;
buf = malloc(total);

// GOOD: overflow check
if (count > SIZE_MAX / element_size) return ERROR;
size_t total = count * element_size;
buf = malloc(total);
```

**Review check:** For every multiplication used in allocation, can the operands be
attacker-controlled? Is there an overflow check before the multiplication?

### 4. Off-by-One Boundaries
The most common buffer overflow variant. Usually involves `<=` vs `<`, or forgetting
the null terminator/final element.

**Red flags:**
- `for (i = 0; i <= n; i++) buf[i] = ...` — writes n+1 elements to n-element buffer
- `if (len <= sizeof(buf))` — should be `<` if len includes null terminator
- `strncpy(dst, src, sizeof(dst))` — doesn't null-terminate if src >= sizeof(dst)

**Review check:** At every boundary comparison, ask: "Is this inclusive or exclusive?
What value causes index == capacity? Does that write out of bounds?"

### 5. Path-Dependent Buffer Sizes
When multiple code paths write to the same buffer with different size assumptions (like
curl's SOCKS5 bug), each path must validate independently.

**Red flags:**
- Fallback code paths that handle the same buffer with different constraints
- Switch/case blocks where each case writes different amounts to a shared buffer
- Mode-dependent behavior where the buffer size was chosen for one mode

**Review check:** For every buffer, enumerate all code paths that write to it. Does
each path independently validate that its writes fit within the buffer capacity?

### 6. Recursive/Nested Parsing Depth
Recursive parsers can exhaust stack space. In kernel context (8KB stack), this is
especially dangerous.

**Red flags:**
- Recursive descent parsers without depth counters
- JSON/XML/YAML parsers processing untrusted input without depth limits
- Nested message formats (protobuf, ASN.1) without recursion bounds

**Review check:** "Does this parser recurse? If so, is there a maximum depth? What
happens when the limit is hit — clean error or undefined behavior?"

## Language-Specific Notes

**C/C++:** The primary language for bounds errors. No runtime checking. strncpy doesn't
null-terminate. snprintf returns the number of characters that WOULD have been written,
not the number actually written.

**Go:** Slice bounds are checked at runtime (panic). But `unsafe.Pointer` arithmetic
bypasses this. cgo code is C code with C bugs.

**Rust:** Array bounds are checked at runtime (panic). But `unsafe` blocks bypass this.
`get_unchecked()` is a bounds-check bypass that must be justified.

**Java/C#/.NET:** Array bounds are checked at runtime (exception). But `ByteBuffer` and
`Unsafe` class bypass this.

**JavaScript/Python/Ruby:** Array access returns undefined/None/nil for out-of-bounds,
which is safer but can still cause logic bugs.

## Catalog References
- M1 (Heartbleed) — external length field trusted
- M2 (sudo) — allocation/usage off-by-one
- M4 (curl SOCKS5) — path-dependent buffer size
- M5 (glibc GHOST) — allocation arithmetic error
- M6 (Stagefright) — integer overflow in allocation
- M8 (OpenSSL X.509) — codec output bounds
- M10 (SQLite) — recursive depth exhaustion
- M16 (zlib) — unsigned underflow bypassing check
