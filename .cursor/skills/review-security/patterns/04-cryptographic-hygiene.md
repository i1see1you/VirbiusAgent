# Skill 04: Cryptographic Hygiene

The Debian OpenSSL disaster (CVE-2008-0166) reduced all SSL key generation to 32,768
possibilities by removing two lines of code. Apple's goto fail (CVE-2014-1266) skipped
signature verification with a duplicated line. These aren't complex cryptographic attacks
— they're review failures on code that touches cryptography.

A code reviewer doesn't need to be a cryptographer. Most cryptographic vulnerabilities
in real-world code come from misusing crypto APIs, not from breaking algorithms.

## The Core Question

> "Is the code using well-tested cryptographic primitives correctly, with proper
> randomness, appropriate algorithms, and no shortcuts?"

## What To Check

### 1. Random Number Generation
The #1 crypto implementation error. Using a non-cryptographic PRNG for security purposes.

**Red flags by language:**
```python
# BAD:
import random
token = ''.join(random.choices(string.ascii_letters, k=32))

# GOOD:
import secrets
token = secrets.token_urlsafe(32)
```
```go
// BAD:
import "math/rand"
token := rand.Int63()

// GOOD:
import "crypto/rand"
b := make([]byte, 32)
crypto_rand.Read(b)
```
```javascript
// BAD:
const token = Math.random().toString(36)

// GOOD:
const token = crypto.randomBytes(32).toString('hex')
```
```java
// BAD:
new Random().nextLong()

// GOOD:
SecureRandom.getInstanceStrong().nextLong()
```

**Review check:** Search for `math/rand`, `random.random`, `Math.random`, `new Random()`
in security-sensitive code paths. These are fast but predictable — they MUST NOT be used
for tokens, keys, nonces, or any security purpose.

### 2. Key and Secret Management
**Red flags:**
- Hardcoded keys/secrets in source code
- Keys in configuration files committed to version control
- Keys derived from low-entropy sources (timestamps, PIDs, sequential IDs)
- Keys shared across environments (dev/staging/prod using same key)
- Encryption keys stored alongside the data they protect

**Review check:**
- Search for `secret`, `password`, `key`, `token`, `api_key` in source files
- Check .gitignore for secret files (.env, *.pem, *.key)
- Verify key rotation is possible without code changes
- Verify keys are loaded from environment/vault/KMS, not from source

### 3. Algorithm Selection
**Red flags:**
- MD5 or SHA-1 for integrity (broken) — but fine for checksums/hashing non-security
- DES, 3DES, RC4 (broken/weak)
- RSA with 1024-bit keys (factorable)
- ECB mode for block ciphers (preserves patterns)
- CBC mode without proper padding (padding oracle attacks)
- Custom/homebrew cryptography

**Review check:**
- Hash: SHA-256+ for integrity, bcrypt/scrypt/Argon2 for passwords
- Symmetric: AES-GCM, ChaCha20-Poly1305 (authenticated encryption)
- Asymmetric: RSA 2048+, ECDSA P-256+, Ed25519
- For passwords: NEVER use general-purpose hashes; use password-specific functions

### 4. Nonce/IV Handling
Reusing a nonce with AES-GCM is catastrophic — it completely breaks confidentiality AND
authenticity. With CTR mode, nonce reuse XORs plaintexts together.

**Red flags:**
```java
// BAD: static/hardcoded IV
byte[] iv = "1234567890123456".getBytes();

// BAD: counter-based nonce that resets on restart
static int nonceCounter = 0;

// GOOD: random nonce for each encryption
byte[] nonce = new byte[12];
SecureRandom.getInstanceStrong().nextBytes(nonce);
```

**Review check:** Is a fresh, random nonce/IV generated for every encryption operation?
Is the nonce size correct for the algorithm (12 bytes for AES-GCM)?

### 5. Constant-Time Operations
Timing side channels leak secret data one bit at a time. The Bleichenbacher attack
(1998) and its 2017 revival (ROBOT) exploit timing differences in RSA padding validation.

**Red flags:**
```python
# BAD: early return on first mismatch
def verify_mac(expected, received):
    if len(expected) != len(received):
        return False
    for i in range(len(expected)):
        if expected[i] != received[i]:
            return False
    return True

# GOOD: constant-time comparison
import hmac
hmac.compare_digest(expected, received)
```

**Review check:** Is every comparison of secrets (MACs, hashes, tokens, password hashes)
using a constant-time comparison function? Search for `==` or `!=` applied to secrets.

Language-specific constant-time functions:
- Python: `hmac.compare_digest()`
- Go: `crypto/subtle.ConstantTimeCompare()`
- Node.js: `crypto.timingSafeEqual()`
- Java: `MessageDigest.isEqual()` (since Java 6u17)
- C: `CRYPTO_memcmp()` or `timingsafe_bcmp()`

### 6. TLS Configuration
**Red flags:**
- SSL 2.0/3.0 or TLS 1.0/1.1 enabled
- Certificate verification disabled (`InsecureSkipVerify: true`, `verify=False`)
- Weak cipher suites (export ciphers, NULL ciphers, RC4)
- Missing HSTS header
- Mixed HTTP/HTTPS content

**Review check:**
- Is the minimum TLS version 1.2?
- Is certificate verification enabled?
- Are cipher suites restricted to strong options?
- Is HSTS set with appropriate max-age?
- Are all resources loaded over HTTPS?

### 7. Password Hashing
**Red flags:**
```python
# BAD:
hashlib.md5(password.encode()).hexdigest()
hashlib.sha256(password.encode()).hexdigest()
hashlib.sha256(password.encode() + salt).hexdigest()  # fast hash, no work factor

# GOOD:
bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=12))
# Or: argon2.hash(password), scrypt
```

**Review check:** Is password hashing using a dedicated password hash function (bcrypt,
scrypt, Argon2) with:
- Per-user random salt (usually generated by the library automatically)
- Sufficient work factor (bcrypt rounds >= 10, Argon2 with adequate memory)
- NOT a general-purpose hash (MD5, SHA-*) even with salt

### 8. Entropy Sources
The Debian PRNG disaster happened because someone "fixed" a Valgrind warning by removing
the entropy source. Always understand where randomness comes from.

**Red flags:**
- Key generation at boot time before entropy is available
- Entropy from predictable sources (PID, timestamp, MAC address alone)
- "Fixing" warnings about uninitialized data in crypto code without understanding why
- Custom entropy pooling/mixing implementations

**Review check:** For key/token generation: what is the entropy source? Is it a CSPRNG
backed by OS entropy? On embedded devices: is there sufficient entropy at the time of
key generation?

### 9. Certificate Validation
**Red flags:**
```go
// BAD: disabling cert verification
&tls.Config{InsecureSkipVerify: true}  // Go

# BAD: disabling cert verification
requests.get(url, verify=False)  # Python

// BAD: accepting all certificates
NODE_TLS_REJECT_UNAUTHORIZED=0  // Node.js
```

**Review check:** Search the codebase for InsecureSkipVerify, verify=False,
REJECT_UNAUTHORIZED=0, and similar. These are sometimes added for debugging and never
removed. If found, is there a comment explaining why and a plan to fix?

### 10. Key Derivation
**Red flags:**
```python
# BAD: using password directly as key
key = password.encode().ljust(32, b'\0')[:32]

# GOOD: using a KDF
key = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, iterations=600000)
# Or: HKDF for deriving multiple keys from a master key
```

**Review check:** If encryption keys are derived from passwords, is a proper KDF used
with sufficient iterations/memory cost? Is there a unique salt per derivation?

## Catalog References
- C1 (Debian PRNG) — entropy source removed to fix tool warning
- C2 (Apple goto fail) — duplicated goto skipped signature verification
- C3 (POODLE) — vulnerable protocol version still enabled
- C4 (DROWN) — legacy protocol sharing keys with modern protocol
- C5 (FREAK) — export cipher downgrade via state machine bug
- C6 (Bleichenbacher/ROBOT) — timing side channel in padding validation
- C7 (Go P-521) — carry propagation bug in field arithmetic
