# Skill 17: Data Validation and Canonicalization

The Apache path traversal (CVE-2021-41773) happened because URL decoding was done once but
paths could be double-encoded. Unicode normalization bypasses let XSS through fullwidth
angle brackets. Null bytes in filenames truncate at the OS level while the application sees
the full string. The root cause is always the same: data was validated in one form but used
in another.

## The Core Question

> "Is this data in its canonical form at the point of validation? Can the data be
> re-encoded, normalized, or transformed AFTER validation in a way that changes its
> security properties?"

## What To Check

### 1. Encode-Then-Validate (the Apache pattern)
**Red flags:**
```python
# BAD: validate, then decode
if ".." not in path:      # check for traversal
    path = unquote(path)   # decode %2e%2e to ..
    open(path)             # traversal possible!

# BAD: single decode of potentially double-encoded input
path = unquote(path)       # %252e → %2e (still encoded)
if ".." not in path:       # check passes
    open(path)             # but %2e is . — traversal!

# GOOD: decode fully, then validate
path = unquote(unquote(path))  # or decode in loop until stable
path = os.path.realpath(path)   # resolve to canonical path
if not path.startswith(BASE_DIR):
    raise ValueError("path traversal")
```

**Review check:** Is validation performed AFTER the data is in its final, canonical form?
Can the data be decoded/transformed again after validation?

### 2. Unicode Normalization
**Red flags:**
```python
# BAD: validate before normalization
if "<script>" in input:       # check for XSS
    raise ValueError()
input = unicodedata.normalize("NFKC", input)  # ＜script＞ → <script>

# BAD: different Unicode representations of the same string
"café"  # NFC: e + combining acute accent
"café"  # NFD: precomposed é
# These may compare as different but render the same
```

**Review check:** Is Unicode normalization applied BEFORE validation? Are confusable
characters handled for identifiers (usernames, domains)?

### 3. Path Canonicalization
**Red flags:**
```python
# Tricky path traversal variants:
"../../../etc/passwd"       # obvious
"..%2f..%2f..%2fetc/passwd" # URL-encoded
"..%252f..%252f"            # double-encoded
"....//....//etc/passwd"    # dot-stripping bypass
"/var/www/./../../etc/passwd" # dot segments
"symlink_to_etc/passwd"     # symbolic link
"/proc/self/root/etc/passwd" # proc filesystem
```

**Review check:** When accepting a file path from external input:
1. Decode/normalize the path first
2. Resolve to an absolute, canonical path (realpath)
3. Verify the canonical path is within the allowed directory
4. Handle symbolic links (resolve them, or reject with O_NOFOLLOW)

### 4. Null Byte Injection
In languages with C-backed I/O (older PHP, Python 2), null bytes truncate strings at
the system call level.

**Red flags:**
```php
// BAD in old PHP:
$file = $_GET['file'] . '.php';
include($file);
// Attacker: ?file=../../etc/passwd%00
// C runtime sees: ../../etc/passwd (null truncates .php)
```

**Review check:** Can user input contain null bytes? Does the language/runtime handle
null bytes consistently between the scripting layer and the C I/O layer?

### 5. Case Sensitivity
**Red flags:**
```python
# BAD: case-sensitive check on case-insensitive filesystem
if filename.endswith('.php'):
    reject()
# Attacker: uploads "shell.PHP" — passes check, executed by server

# BAD: case-sensitive URL routing with case-insensitive auth
if path.startswith('/admin'):
    require_auth()
# Attacker: requests /Admin or /ADMIN
```

**Review check:** Are security checks case-sensitive? Is the underlying system
(filesystem, database, URL routing) also case-sensitive? If they disagree, there's a bypass.

### 6. Whitespace and Invisible Characters
**Red flags:**
```python
# BAD: comparing without stripping
if username == "admin":  # attacker sends "admin " or " admin"
    # Never matches legitimate check, but DB might match with trailing space

# Invisible characters:
"admin\u200B"  # zero-width space — looks like "admin" but isn't
"admin\u00AD"  # soft hyphen — invisible in rendering
```

**Review check:** Is leading/trailing whitespace handled? Are invisible Unicode characters
filtered from security-sensitive identifiers?

### 7. Numeric Representation
**Red flags:**
```python
# Multiple representations of the same IP:
"127.0.0.1"      # dotted decimal
"0x7f000001"      # hex
"2130706433"      # decimal integer
"017700000001"    # octal
"127.0.0.01"      # leading zeros (octal in some parsers!)
"::ffff:127.0.0.1" # IPv6-mapped IPv4

# Check: does the SSRF filter block ALL representations of localhost?
```

**Review check:** If IP addresses or numbers are validated, are all representations
handled? Can an attacker bypass a blocklist by using an alternative representation?

### 8. HTML Entity Encoding
**Red flags:**
```html
<!-- Multiple ways to encode the same character -->
<  &lt;  &#60;  &#x3c;  &#x3C;  &#0060;

<!-- Does the sanitizer handle all of these? -->
<!-- What about invalid entities? &lt without semicolon? -->
```

**Review check:** Does HTML sanitization handle all entity encoding forms, including
numeric entities, hex entities, and entities without semicolons?

### 9. Regular Expression Anchoring
**Red flags:**
```python
# BAD: unanchored validation
re.match(r'admin', input)        # matches "admin_evil"
re.search(r'[a-z]+', input)     # matches if ANY substring matches

# GOOD: fully anchored
re.fullmatch(r'[a-z]+', input)  # entire string must match
re.match(r'^[a-z]+$', input)    # anchored at both ends
```

**Review check:** Are validation regexes anchored at both start and end? Can extra
characters before or after the expected pattern bypass the validation?

### 10. Content-Type and MIME Validation
**Red flags:**
```python
# BAD: trusting Content-Type header
if request.content_type == 'image/jpeg':
    save_image(request.data)
# Attacker sends Content-Type: image/jpeg with a PHP file body

# BAD: trusting file extension
if filename.endswith('.jpg'):
    save_image(data)
# Attacker: "shell.php.jpg" or "shell.jpg.php"
```

**Review check:** Is file type validated by content (magic bytes) in addition to extension
and Content-Type header? Are double extensions handled?

## The Canonicalization Checklist

For every input that undergoes validation:
1. **Decode** — fully decode all encoding layers (URL, HTML, Unicode, base64)
2. **Normalize** — apply canonical form (Unicode NFKC, path normalization, case folding)
3. **Validate** — check against allowlist/pattern
4. **Use** — use the validated, canonical form (not the original input)

The order matters: decode → normalize → validate → use. Never validate first and
transform later.

## Catalog References
- M32 (Apache path traversal) — double-encoded dots bypassing single-decode validation
- M18 (nginx alias traversal) — missing trailing slash enabling path traversal
- I25 (Unicode normalization bypass) — validation before normalization
- I26 (Null byte injection) — C runtime truncating at null
- L10 (Unicode identifiers) — confusable characters enabling impersonation
- L13 (Incorrect regex anchoring) — unanchored regex allowing extra characters
