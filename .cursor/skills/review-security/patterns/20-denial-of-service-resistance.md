# Skill 20: Denial of Service Resistance

ReDoS (Regular Expression Denial of Service) can hang a server with a single crafted
string. Hash collision attacks (HashDoS) can turn an O(1) hash table into O(n²). XML
billion laughs can exhaust memory with a few bytes. Algorithmic complexity attacks exploit
worst-case behavior that normal testing never triggers.

## The Core Question

> "Can an attacker craft an input that causes disproportionate resource consumption
> (CPU, memory, disk, connections, file descriptors)?"

## What To Check

### 1. Regular Expression Denial of Service (ReDoS)
**Red flags:**
```javascript
// BAD: nested quantifiers — exponential backtracking
/(a+)+$/           // O(2^n) on "aaaaaaaaa!"
/(a|a)+$/          // overlapping alternatives
/(.*a){10}/        // nested repetition
/([a-z]+)+@/       // nested character class + quantifier

// GOOD: use RE2 or linear-time engines for untrusted input
// Or: avoid nested quantifiers entirely
```

**Review check:** For every regex applied to user input:
- Does it contain nested quantifiers? `(a+)+`, `(a*)*`, `(a+)*`
- Does it have overlapping alternatives? `(a|ab)+`
- Can the input cause exponential backtracking?
- Consider using RE2 (linear-time guarantee) for untrusted input

### 2. Hash Collision Attacks (HashDoS)
**Red flags:**
```python
# BAD: using user-controlled strings as hash table keys without protection
user_data = json.loads(request.data)
# Attacker sends thousands of keys with same hash — O(n²) lookups

# Mitigations:
# - Use hash randomization (default in Python 3, Go, recent Java)
# - Limit the number of keys accepted
# - Limit request body size
```

**Review check:** Is hash randomization enabled? (It's default in most modern languages.)
Is there a limit on the number of keys/fields in deserialized input?

### 3. XML Bombs
**Red flags:**
```xml
<!-- Billion Laughs attack: small input → exponential expansion -->
<!DOCTYPE lolz [
  <!ENTITY lol "lol">
  <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
  <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
  ...
]>
<root>&lol9;</root>
<!-- ~3GB of "lol" from a few KB of XML -->
```

**Review check:** For XML parsing of untrusted input:
- Is entity expansion disabled or limited?
- Is external entity resolution disabled?
- Is DTD processing disabled?
- Is there a maximum input size?

### 4. Unbounded Data Structures
**Red flags:**
```python
# BAD: unbounded list growth
results = []
while more_data():
    results.append(next_item())  # can grow indefinitely

# BAD: unbounded cache
cache = {}
def get_data(key):
    if key not in cache:
        cache[key] = expensive_query(key)  # cache grows forever
    return cache[key]
```

**Review check:** For every data structure that grows based on input:
- Is there a maximum size?
- Is there an eviction policy?
- What happens when the limit is reached?

### 5. Algorithmic Complexity Attacks
**Red flags:**
```python
# BAD: O(n²) or worse with attacker-controlled n
def find_duplicates(items):  # O(n²) nested loop
    for i in items:
        for j in items:
            if i == j: yield i

# BAD: sort with pathological input
# Quicksort is O(n²) worst case — use algorithms with O(n log n) guarantee

# BAD: string concatenation in loop
result = ""
for item in items:
    result += str(item)  # O(n²) string copying
```

**Review check:** For algorithms processing untrusted input: what's the worst-case time
complexity? Can an attacker trigger the worst case?

### 6. Connection/Resource Exhaustion
**Red flags:**
```
# BAD: no connection limit
server.listen()  # accepts unlimited connections — Slowloris attack

# BAD: no request timeout
# Client sends headers very slowly, holding connection open

# BAD: no request body size limit
app.use(express.json())  # default: no size limit
# Attacker sends 10GB JSON body
```

**Review check:**
- Is there a maximum number of concurrent connections?
- Are there timeouts for: connection establishment, header reading, body reading,
  response writing?
- Is request body size limited?
- Are WebSocket message sizes limited?

### 7. Database Query Amplification
**Red flags:**
```python
# BAD: user-controlled query with no pagination
@app.get("/users")
def list_users():
    return User.objects.all()  # returns ALL users — could be millions

# BAD: user-controlled join depth
# GraphQL: { users { posts { comments { author { posts { ... } } } } } }

# BAD: N+1 query with user-controlled N
for id in user_provided_ids:
    results.append(db.query("SELECT * FROM items WHERE id = %s", id))
```

**Review check:**
- Are query results paginated with maximum page sizes?
- Are query depths/complexity limited (especially GraphQL)?
- Are batch query sizes limited?
- Can a user trigger expensive queries (full table scans, cross joins)?

### 8. File System Exhaustion
**Red flags:**
- Unlimited file upload size
- Unlimited number of uploads per user
- Temporary files not cleaned up on error
- Log files that grow without rotation
- No disk space monitoring

**Review check:** Are there limits on file upload size, count per user, and total storage?
Are temporary files cleaned up reliably? Is log rotation configured?

### 9. Memory Exhaustion
**Red flags:**
```python
# BAD: loading entire file into memory
data = request.files['upload'].read()  # could be 10GB

# BAD: unbounded JSON depth
import json
json.loads(deeply_nested_json)  # stack overflow or excessive memory

# GOOD: streaming processing
for chunk in request.files['upload'].stream:
    process_chunk(chunk)
```

**Review check:** Is large input processed in streaming/chunked mode rather than loaded
entirely into memory? Is there a maximum nesting depth for recursive data formats?

### 10. CPU Exhaustion
**Red flags:**
- Image processing without size limits (resizing a 100MP image)
- PDF rendering without page limits
- Compression bombs (small compressed file → huge decompressed)
- Cryptographic operations with user-controlled work factor

**Review check:** Are CPU-intensive operations bounded? Is there a maximum input size for
image/PDF/video processing? Are compression ratios checked?

## Catalog References
- I10 (ReDoS) — exponential regex backtracking
- M10 (SQLite depth) — recursive parsing without depth limits
- M19 (kernel netlink) — recursive parsing exhausting kernel stack
- O6 (Unbounded resources) — no limits enabling DoS
- O14 (Missing timeout) — slowloris attacks
- L23 (SQL N+1) — query amplification
