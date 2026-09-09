# Skill 11: Integer Arithmetic Safety

Stagefright (CVE-2015-1538) exploited an integer overflow in `num_entries * entry_size` to
allocate a tiny buffer then overflow it with a huge copy. The sudo heap overflow
(CVE-2021-3156) was an off-by-one in size calculation. Chrome has had dozens of integer
overflow CVEs in media parsing alone. When arithmetic goes wrong in size calculations, the
result is almost always exploitable.

## The Core Question

> "Can any arithmetic operation involving attacker-influenced values overflow, underflow,
> wrap around, truncate, or change sign in a way that affects buffer sizes, loop bounds,
> or security decisions?"

## What To Check

### 1. Multiplication Overflow in Allocation
The classic Stagefright pattern: `count * size` overflows to a small value.

**Red flags:**
```c
// BAD: multiplication can overflow to small value
size_t total = count * element_size;
void *buf = malloc(total);
for (int i = 0; i < count; i++)  // iterates WAY more than buffer can hold
    memcpy(buf + i * element_size, src + i * element_size, element_size);

// GOOD: check for overflow before allocation
if (count > 0 && element_size > SIZE_MAX / count) {
    return -ENOMEM;  // would overflow
}
size_t total = count * element_size;
```

**Review check:** For every multiplication used in allocation or loop bound: can both
operands be attacker-controlled? Is there an overflow check before the multiplication?

### 2. Addition Overflow
**Red flags:**
```c
// BAD: size + header can overflow
size_t total = data_size + HEADER_SIZE;  // if data_size ≈ SIZE_MAX, total wraps
buf = malloc(total);  // tiny allocation
memcpy(buf + HEADER_SIZE, data, data_size);  // massive overflow

// GOOD: check before addition
if (data_size > SIZE_MAX - HEADER_SIZE) {
    return -ENOMEM;
}
```

**Review check:** For every addition used in allocation: can the sum overflow? This is
especially dangerous when `SIZE_MAX - small_constant` is a valid input size.

### 3. Signed/Unsigned Confusion
**Red flags:**
```c
// BAD: signed/unsigned comparison
int user_len = get_user_value();  // could be negative
if (user_len > sizeof(buf))  // sizeof returns unsigned
    return -E2BIG;
memcpy(buf, data, user_len);  // negative len becomes huge unsigned

// GOOD: check for negative first, or use unsigned type
if (user_len < 0 || (size_t)user_len > sizeof(buf))
    return -E2BIG;
```

**Review check:** Are size/length variables using unsigned types? If a signed value is
used as a size, is it checked for negative values before use?

### 4. Integer Truncation
**Red flags:**
```c
// BAD: 64-bit value truncated to 32-bit
uint64_t big_size = get_file_size();  // could be > 4GB
uint32_t alloc_size = (uint32_t)big_size;  // truncated to lower 32 bits
buf = malloc(alloc_size);  // small allocation for large file
read(fd, buf, big_size);   // reads more than allocated
```

**Review check:** When values are passed between different integer widths (64→32, 32→16),
can truncation change the value? Is there a range check before the narrowing conversion?

### 5. Unsigned Underflow (Wrap-Around)
**Red flags:**
```c
// BAD: unsigned subtraction can underflow
unsigned int remaining = total_size - consumed;
// if consumed > total_size, remaining wraps to ~4 billion

if (remaining > sizeof(buf))  // this check passes because remaining is huge
    // ...

// GOOD: check before subtraction
if (consumed > total_size) return -EINVAL;
unsigned int remaining = total_size - consumed;
```

**Review check:** For every subtraction involving unsigned values: can the subtrahend be
larger than the minuend? If so, the result wraps to a very large value.

### 6. Division and Modulo Edge Cases
**Red flags:**
```c
// BAD: division by zero
int items_per_page = user_specified_page_size;
int num_pages = total_items / items_per_page;  // crash if page_size == 0

// BAD: INT_MIN / -1 causes signed overflow
int result = dividend / divisor;  // if dividend == INT_MIN and divisor == -1
```

**Review check:** For every division/modulo operation:
- Can the divisor be zero?
- For signed division: can `INT_MIN / -1` occur? (Result doesn't fit in the type)

### 7. Shift Overflow
**Red flags:**
```c
// BAD: shift by >= type width is undefined behavior
int value = 1 << shift_amount;  // UB if shift_amount >= 32
// BAD: left shift of negative value is UB (before C23)
int x = -1 << 5;  // undefined behavior
```

**Review check:** For every shift operation: is the shift amount bounded to [0, type_width-1]?
Is the shifted value positive?

### 8. Size_t and ssize_t Confusion
**Red flags:**
```c
// BAD: ssize_t (signed) compared with size_t (unsigned)
ssize_t n = read(fd, buf, sizeof(buf));
if (n < sizeof(buf)) {  // signed/unsigned comparison
    // if n is -1 (error), -1 < sizeof(buf) is FALSE because -1 becomes huge
}

// GOOD: check error first
ssize_t n = read(fd, buf, sizeof(buf));
if (n < 0) return -errno;  // error
if ((size_t)n < sizeof(buf)) // now safe to compare
```

**Review check:** Is `ssize_t` compared with `size_t`? Check error returns (negative
values) separately before comparing magnitudes.

### 9. Pointer Arithmetic Overflow
**Red flags:**
```c
// BAD: pointer arithmetic without bounds check
char *end = buf + user_offset;  // can wrap past end of address space
if (end < buf) // compiler may optimize this away for UB reasons!
    return -EINVAL;
```

**Review check:** Pointer arithmetic that depends on user-controlled offsets must be
checked for overflow. Note that compilers may optimize away overflow checks on pointers
because pointer overflow is undefined behavior in C.

### 10. Language-Specific Arithmetic

**Go:** Integer overflow wraps silently (no exception). Use `math/bits.Add64` and similar
for checked arithmetic.

**Rust:** Integer overflow panics in debug, wraps in release. Use `checked_add`,
`saturating_add`, or `wrapping_add` explicitly.

**Python:** Arbitrary-precision integers never overflow, but can consume unbounded memory.
Limit input sizes.

**Java:** Integer overflow wraps silently. Use `Math.addExact`, `Math.multiplyExact` for
checked arithmetic.

**JavaScript:** All numbers are IEEE 754 doubles. Integer precision is lost above 2^53.
Use BigInt for exact large integer arithmetic.

## Catalog References
- M2 (sudo) — off-by-one in size calculation
- M5 (glibc GHOST) — pointer count error in allocation arithmetic
- M6 (Stagefright) — integer overflow in count * size allocation
- M16 (zlib) — unsigned underflow bypassing bounds check
- M19 (kernel netlink) — recursive parsing exceeding stack
- AA24 (Coccinelle API misuse) — semantic patterns catching integer/size misuse in kernel code
- AA25 (sparse type checking) — static analysis catching wrong integer types at trust boundaries
