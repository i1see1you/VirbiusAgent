# Skill 13: Trust Boundaries and Privilege Crossings

The runc container escape (CVE-2019-5736) happened when a privileged process entered an
untrusted namespace. The xz backdoor operated across the boundary between build system and
runtime. The Next.js middleware bypass exploited a header trusted across a client-server
boundary. Every serious vulnerability involves data or control crossing a trust boundary
without proper validation.

## The Core Question

> "Where are the trust boundaries in this system? What crosses each boundary? Is
> everything that crosses validated, sanitized, or restricted at the crossing point?"

## What To Check

### 1. Identify Every Trust Boundary
A trust boundary is where code at one privilege level interacts with code or data at a
different privilege level.

**Common trust boundaries:**
```
Client → Server          (HTTP requests, WebSocket messages)
User → Kernel            (syscalls, ioctl, procfs)
Renderer → Browser       (IPC, Mojo messages in Chrome)
Container → Host         (/proc, volumes, network)
Plugin → Host App        (plugin API, callbacks)
CI/CD → Production       (deploy pipeline, artifacts)
External → Internal      (API gateway, load balancer)
User Input → Database    (SQL queries, document stores)
User Input → Shell       (command execution)
User Input → Template    (template rendering)
User Input → Log         (log messages)
User Input → Filesystem  (file paths, names)
Deserialized → Runtime   (pickle, Java serialization, YAML)
```

**Review check:** Draw the trust boundaries for the system. For each boundary, list
everything that crosses it. Every crossing point needs validation.

### 2. Data Crossing Boundaries Must Be Validated at the Boundary
Not before, not after — at the boundary itself. The receiving side must validate because
the sending side might be compromised, bypassed, or lying.

**Red flags:**
```
# BAD: validation only on the client
Client validates input → sends to Server → Server trusts it
# An attacker bypasses the client entirely

# BAD: validation only at the outer boundary
Load Balancer validates → passes to App → App passes to DB unvalidated
# LB validates HTTP, but app-level semantics need app-level validation

# GOOD: each boundary validates what it needs
Client validates (UX) → Server validates (auth + input) → DB validates (constraints)
```

**Review check:** Is input validated at the point where it crosses the trust boundary,
by the receiving (more-privileged) side? Client-side validation is for UX, not security.

### 3. Privilege Should Not Enter Unprivileged Context
When a privileged process enters an untrusted context (container, sandbox, user namespace),
the untrusted context can observe and potentially manipulate it.

**Red flags (the runc pattern):**
```
# BAD: privileged process entering untrusted namespace
runc (root on host) → enters container namespace → container sees /proc/self/exe
→ container overwrites runc binary on host

# BAD: mounting host paths into containers
volumes:
    - /var/run/docker.sock:/var/run/docker.sock  # container gets full host control
    - /:/host  # container can read/write all host files
```

**Review check:** When a privileged process interacts with an untrusted environment:
can the environment observe the process (/proc, /sys)? Can it manipulate files the
process reads? Can it control the process's execution?

### 4. Internal Headers Must Be Stripped at the Edge
Headers meant for internal use between components become attack vectors when clients
can set them.

**Red flags:**
```
X-Forwarded-For: attacker-controlled unless proxy strips and re-adds
X-Real-IP: same
X-Original-URL: same
X-Middleware-Subrequest: Next.js internal header accessible to clients
X-Forwarded-Host: same
```

**Review check:** For every HTTP header used in security decisions: does the load
balancer/reverse proxy strip it from incoming requests? Is it possible for a client to
set or forge this header?

### 5. Serialization Boundaries
Deserialization is a trust boundary. The serialized data format may be able to instantiate
arbitrary objects or execute code.

**Red flags:**
```
Untrusted → pickle.loads()      → arbitrary code execution
Untrusted → ObjectInputStream   → gadget chain execution
Untrusted → yaml.load()         → arbitrary object instantiation
Untrusted → Marshal.load()      → arbitrary code execution
Untrusted → unserialize()       → PHP object injection
```

**Review check:** When deserializing data from an untrusted source:
- Can the deserialization format instantiate arbitrary types?
- Is there a type/class allowlist?
- Would JSON or another inert format work instead?

### 6. Plugin/Extension Boundaries
Plugins run within the host application but may have different trust levels.

**Red flags:**
- Plugins with access to all host resources (filesystem, network, memory)
- No permission model for plugins
- Plugin code executing with host application's privileges
- No isolation between plugins

**Review check:** What can a malicious plugin do? Is there a permission model? Can
plugins be sandboxed?

### 7. Build-to-Runtime Boundary
The build system runs code to produce artifacts. If the build is compromised, the
artifacts are compromised.

**Red flags:**
- Build scripts downloading and executing code from the internet
- CI/CD secrets accessible during build
- Build environment shared between trusted and untrusted workloads
- Release tarballs containing files not in source control (xz pattern)

**Review check:** Is the build environment isolated and ephemeral? Can the build be
reproduced from source? Are build-time secrets separate from runtime secrets?

### 8. Cross-Origin Boundaries
The browser enforces same-origin policy, but applications must also enforce it.

**Red flags:**
- API accepting requests from any origin (CORS *)
- WebSocket connections not checking Origin header
- PostMessage listeners not checking message origin
- Cookies scoped too broadly (domain includes untrusted subdomains)

**Review check:** Does the application validate the origin of cross-origin requests at
every endpoint that accepts them?

### 9. Service-to-Service Boundaries
Internal services communicating without authentication trusts network segmentation as the
only security boundary.

**Red flags:**
- Services communicating over plaintext HTTP internally
- No mutual TLS (mTLS) between services
- No authentication on internal APIs
- Internal service ports accessible from outside the service mesh

**Review check:** Do internal services authenticate each other? Is communication
encrypted? Is the network segmentation enforced (not just assumed)?

### 10. Environment Variable Boundaries
Environment variables cross process boundaries and can influence library behavior in
unexpected ways.

**Red flags:**
```bash
# BAD: user-controlled environment variables affecting security
LD_PRELOAD=evil.so  # loads attacker library
PYTHONPATH=/evil     # loads attacker Python modules
PATH=/evil:$PATH     # finds attacker binaries
NODE_TLS_REJECT_UNAUTHORIZED=0  # disables TLS verification
```

**Review check:** Can user input influence environment variables? Are security-sensitive
environment variables sanitized before being passed to child processes?

## The Trust Boundary Audit

1. **Draw the boundaries** — system diagram with trust levels marked
2. **List the crossings** — what data, commands, and control flow cross each boundary
3. **Check each crossing** — is there validation/sanitization at the crossing point?
4. **Check the direction** — does privilege ever flow from more-trusted to less-trusted
   context? (This should be a capability grant, not a process entering the context)
5. **Check stripping** — are internal headers/metadata stripped at the edge?

## Catalog References
- A1 (Next.js middleware bypass) — internal header not stripped at edge
- A5 (runc container escape) — privileged process entering untrusted namespace
- M17 (PHP CGI) — query strings crossing into CLI argument context
- M34 (Docker socket) — host control plane mounted into container
- I24 (deserialization) — untrusted data crossing serialization boundary
- S1 (xz backdoor) — build system crossing into runtime
