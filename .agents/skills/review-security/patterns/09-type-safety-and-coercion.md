# Skill 09: Type Safety and Coercion

PHP's loose comparison operator let attackers bypass authentication because `"0e12345" ==
"0e67890"` evaluates to true (both are scientific notation for zero). JavaScript's type
coercion means `[] == false` is true but `!![]` is also true. Go's nil interface
comparison has bitten every Go programmer at least once.

Type safety bugs are language-specific and subtle. The code looks correct to someone who
doesn't know the language's specific coercion rules.

## The Core Question

> "Does this comparison/conversion/cast behave correctly for ALL possible input types,
> including adversarial ones? Could implicit type conversion change the result?"

## What To Check

### 1. PHP Loose Comparison (`==` vs `===`)
PHP's `==` performs type juggling that can bypass security checks.

**Critical behaviors:**
```php
"0e12345" == "0e67890"   // TRUE (both are "0" in scientific notation)
"0" == false              // TRUE
"" == false               // TRUE
"0" == null               // FALSE (PHP 8+: TRUE in PHP 7)
"1abc" == 1               // TRUE (PHP 7) / FALSE (PHP 8)
md5("240610708") == md5("QNKCDZO")  // TRUE (both start with "0e")
```

**Red flags:**
```php
// BAD: loose comparison for auth
if ($user_input == $stored_token) { grant_access(); }
if (md5($password) == $stored_hash) { authenticate(); }

// GOOD: strict comparison
if ($user_input === $stored_token) { grant_access(); }
if (hash_equals($stored_hash, md5($password))) { authenticate(); }
```

**Review check:** In PHP, every security-sensitive comparison must use `===` (strict)
or `hash_equals()`. Search for `==` in auth, token validation, and hash comparison code.

### 2. JavaScript Type Coercion
**Critical behaviors:**
```javascript
[] == false        // TRUE (but !![] is also TRUE)
"" == false        // TRUE
0 == false         // TRUE
null == undefined  // TRUE
NaN == NaN         // FALSE
"0" == false       // TRUE
"" == 0            // TRUE
```

**Red flags:**
```javascript
// BAD: loose equality in security checks
if (user.role == 0) { denyAccess(); }
// If role is "" or false or null, this matches!

if (amount == "0") { freeItem(); }
// Amount of "" would also match

// GOOD: strict equality
if (user.role === 0) { denyAccess(); }
```

**Review check:** In JavaScript/TypeScript, use `===` for all comparisons. Search for
`==` and `!=` in security-sensitive code. In TypeScript: are `as any` casts bypassing
type safety?

### 3. Go Interface Nil Confusion
**Critical behavior:**
```go
var err *MyError = nil
var iface error = err

iface == nil  // FALSE!
// The interface is non-nil because it has a type, even though the value is nil
```

**Red flags:**
```go
// BAD: returning typed nil
func doWork() error {
    var err *MyError
    // ... processing ...
    return err  // returns non-nil interface with nil value!
}

if err := doWork(); err != nil {  // TRUE even when err.(*MyError) is nil!
    handleError(err)
}

// GOOD: return nil explicitly
func doWork() error {
    // ... processing ...
    return nil  // explicit nil interface
}
```

**Review check:** In Go, does any function return a concrete error type (not `error`
interface) that could be nil? Does this nil-typed-value get compared to nil through an
interface? The fix is to always return `nil` explicitly for the nil case.

### 4. Python Type Confusion
**Red flags:**
```python
# BAD: isinstance can be bypassed with duck typing
if type(obj) == dict:  # doesn't catch subclasses
    process_dict(obj)

# BAD: truth value testing as type check
if user_input:  # empty string, 0, [], {} are all falsy
    process(user_input)

# BAD: int/str confusion
if user_id == 0:  # what if user_id is "0"?
    admin_access()
```

**Review check:** Are type checks using `isinstance()` (not `type() ==`)? Are falsy
value checks distinguished from None checks? Are string/int comparisons explicit?

### 5. C/C++ Integer Type Issues
**Critical behaviors:**
```c
// Signed/unsigned comparison
int a = -1;
unsigned int b = 1;
if (a < b) {  // FALSE! -1 becomes a large unsigned value
    // This branch is NOT taken
}

// Integer truncation
size_t big = 0x100000001ULL;
unsigned int small = (unsigned int)big;  // truncated to 1

// Sign extension
char c = 0xFF;  // -1 as signed char
int i = c;       // -1 (sign-extended), not 255
unsigned int u = (unsigned char)c;  // 255 (zero-extended)
```

**Review check:**
- Are signed/unsigned comparisons flagged by compiler warnings (-Wsign-compare)?
- Can integer truncation occur when passing values between different-width types?
- Is sign extension correct when widening char/short to int?

### 6. Java Autoboxing and Equality
**Critical behavior:**
```java
Integer a = 127;
Integer b = 127;
a == b  // TRUE (cached small integers)

Integer c = 128;
Integer d = 128;
c == d  // FALSE (different objects, not cached)

// Always use .equals() for object comparison
c.equals(d)  // TRUE
```

**Review check:** In Java, are boxed types (Integer, Long, Boolean) compared with
`.equals()` rather than `==`? `==` compares references, not values.

### 7. TypeScript `any` and Type Assertions
**Red flags:**
```typescript
// BAD: any disables type checking
function processUser(user: any) {
    return user.profile.email;  // no type safety
}

// BAD: type assertion without validation
const data = JSON.parse(input) as UserData;
// data might not actually be UserData — no runtime check

// GOOD: runtime validation
const data = UserSchema.parse(JSON.parse(input));
```

**Review check:** Search for `as any`, `: any`, and type assertions (`as TypeName`) in
TypeScript code. Each is a type safety escape hatch that should be justified. For data
from external sources (API, JSON, user input), is there runtime validation (Zod, io-ts)?

### 8. Null/Undefined/Optional Handling
**Red flags across languages:**
```swift
// BAD in Swift: force unwrap
let value = optionalValue!  // crashes if nil

// BAD in Kotlin: null assertion
val value = nullableValue!!  // throws if null

// BAD in C#: null forgiveness
string value = nullableString!;  // suppresses warning
```

**Review check:** Are force-unwrap operators (`!` in Swift, `!!` in Kotlin, `!` in C#)
used on values from untrusted sources? These should only be used when nil is genuinely
impossible.

### 9. Serialization Type Confusion
**Red flags:**
```python
# JSON doesn't distinguish int from float in all cases
json.loads("9999999999999999999")  # may lose precision
json.loads("1.0000000000000001")  # may round

# YAML type coercion surprises
yaml.safe_load("on")   # True (boolean)
yaml.safe_load("off")  # False
yaml.safe_load("no")   # False
yaml.safe_load("1.2.3") # string (looks like version)
```

**Review check:** Does deserialized data have the expected types? Are version numbers,
boolean-like strings, and large numbers handled correctly?

### 10. Prototype Pollution (JavaScript)
**Red flags:**
```javascript
// BAD: recursive merge without prototype key filtering
function merge(target, source) {
    for (let key in source) {
        if (typeof source[key] === 'object') {
            target[key] = merge(target[key] || {}, source[key]);
        } else {
            target[key] = source[key];
        }
    }
}
// merge({}, JSON.parse('{"__proto__": {"admin": true}}'))
// Now ALL objects have .admin === true

// GOOD: filter dangerous keys
const BLOCKED_KEYS = new Set(['__proto__', 'constructor', 'prototype']);
function safeMerge(target, source) {
    for (let key in source) {
        if (BLOCKED_KEYS.has(key)) continue;
        // ...
    }
}
```

**Review check:** Does any recursive merge/clone/set-by-path operation filter out
`__proto__`, `constructor`, and `prototype` keys?

## Catalog References
- I8 (PHP type juggling) — loose comparison bypassing auth
- I11 (Prototype pollution) — __proto__ injection via merge
- M13 (Chrome V8 type confusion) — JIT type speculation bugs
- L6 (Wrong comparison operator) — = vs == vs ===
- AA25 (sparse type checking) — static analysis catching type annotation violations at trust boundaries
