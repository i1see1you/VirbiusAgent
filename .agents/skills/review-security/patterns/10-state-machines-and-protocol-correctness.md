# Skill 10: State Machines and Protocol Correctness

The OpenSSL CCS injection (CVE-2014-0224) accepted a message in the wrong TLS state. The
FREAK attack (CVE-2015-0204) sent an unexpected message that the state machine didn't
reject. The Kubernetes API server (CVE-2018-1002105) left a connection in an authenticated
state after an error. Every protocol implementation is a state machine, and every state
machine bug is a security bug.

## The Core Question

> "Does this state machine handle every possible input in every possible state? Are
> transitions validated? Can an unexpected message put the system in an invalid state?"

## What To Check

### 1. Message Ordering Validation
Every protocol specifies a message sequence. Implementations must enforce it.

**Red flags:**
```
TLS handshake expected: ClientHello → ServerHello → Certificate → ServerKeyExchange → ...
But: what if ServerKeyExchange arrives before Certificate?
     what if ChangeCipherSpec arrives before key exchange completes?
     what if a message for state 5 arrives while in state 2?
```

**Review check:** Draw the state machine. For each state, list which messages are valid.
For each invalid message, verify the code rejects it with an error (not silently ignores
it or processes it anyway).

### 2. Unexpected Input in Valid State
**Red flags:**
- Switch/case without default (or default that does nothing)
- State machine that ignores unknown message types
- Protocol parser that skips unrecognized fields without error

**Review check:** What happens when the state machine receives:
- A valid message for a different state?
- A completely unknown message type?
- A message with valid type but invalid content?
- A duplicate of a message already received?

### 3. Error State Recovery
After an error, the state machine must be in a well-defined state — either cleaned up and
ready for a new connection, or completely terminated.

**Red flags (the K8s pattern):**
```
Normal: Client → [auth] → Server → [error] → connection closed ✓
Bug:    Client → [auth] → Server → [error] → connection open, still authenticated ✗
```

**Review check:** After an error in any state, is the connection/session/transaction
properly terminated? Or can the error leave the system in an intermediate state that
has residual privileges?

### 4. Rollback Attacks
Can an attacker force the state machine to transition back to a weaker state?

**Red flags:**
- Protocol version downgrade not detected
- Cipher suite renegotiation to weaker ciphers
- Session resumption that bypasses full authentication
- Feature toggle that disables security after it was enabled

**Review check:** Can any transition move the system to a less-secure state? Is there a
mechanism to detect and prevent downgrade attacks?

### 5. Parser Termination
Every parser should stop cleanly at the end of its expected input. Shellshock happened
because the Bash parser continued past the function definition boundary.

**Red flags:**
```c
// BAD (Shellshock pattern): parser continues past expected end
parse_function_definition(env_var);
// ... but what if there's more data after the closing brace?
// parser continues executing it as commands
```

**Review check:** When a parser finishes processing a complete valid construct, does it:
- Stop and return?
- Verify there's no trailing data?
- Report an error if extra data exists?

### 6. Request Smuggling via Parser Disagreement
When two parsers (proxy and backend, browser and server) interpret the same data
differently, the result is security-relevant.

**Red flags:**
- HTTP/1.1 with both Content-Length and Transfer-Encoding headers
- Chunked encoding with trailing data after the 0-length chunk
- Header line folding interpreted differently by different servers
- URL parsing differences between validation and fetching (SSRF)

**Review check:** If data passes through multiple parsers: will they agree on boundaries?
Does the parser reject ambiguous input rather than guessing?

### 7. Finite State Machine Completeness
An FSM is complete when every state has a defined transition for every possible input.
Incomplete FSMs silently ignore some inputs, which can be exploitable.

**Review check:** Create a state × input matrix:

```
State / Input    | MSG_A  | MSG_B  | MSG_C  | UNKNOWN
-----------------+--------+--------+--------+---------
INIT             | → AUTH | ERROR  | ERROR  | ERROR
AUTHENTICATING   | ERROR  | → READY| ERROR  | ERROR
READY            | ERROR  | ERROR  | → DONE | ERROR
DONE             | ERROR  | ERROR  | ERROR  | ERROR
```

Every cell should have a defined behavior. "Ignore" is suspicious; "error" is usually
correct.

### 8. Session State Cleanup
**Red flags:**
- Session data from a previous request leaking into the next request
- Thread-local state not cleared between requests
- Connection pooling reusing connections with stale authentication state

**Review check:** Is session state fully isolated between requests? When a connection
is returned to a pool, is its state reset?

### 9. Timeout Handling in Protocol States
What happens when a timeout fires while the state machine is mid-transition?

**Red flags:**
- LoginGraceTime timeout (regreSSHion) that calls unsafe cleanup
- Idle timeout that doesn't clean up pending operations
- Timeout that fires after the connection is already closed

**Review check:** For every timeout in the protocol:
- Is the timeout handler safe to call from any state?
- Does it properly clean up resources regardless of current state?
- Can it race with normal state transitions?

### 10. Version Negotiation
**Red flags:**
- Server accepting any version the client requests without minimum
- Version downgrade not authenticated (no Finished message covering version)
- Different code paths for different versions with different security properties

**Review check:** Is the negotiated version the highest mutually supported? Is the
version included in the authenticated handshake (preventing downgrade)?

## Catalog References
- M39 (OpenSSL CCS) — CCS accepted before key exchange
- C3 (POODLE) — protocol-level vulnerability in SSL 3.0
- C5 (FREAK) — unexpected message accepted in wrong state
- I2 (Shellshock) — parser continuing past expected input end
- I19 (HTTP smuggling) — parser disagreement on message boundaries
- A2 (K8s API server) — error leaving connection in authenticated state
- M7 (regreSSHion) — timeout handler calling unsafe functions
