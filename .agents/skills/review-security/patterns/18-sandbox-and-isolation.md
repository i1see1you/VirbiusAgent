# Skill 18: Sandbox and Isolation Boundaries

The runc container escape (CVE-2019-5736) broke out of Docker by overwriting the host
binary through /proc. Chrome's multi-process sandbox has prevented hundreds of full-
compromise exploits. The Kubernetes RBAC model defines what each service can access. When
isolation fails, a single vulnerability becomes a complete compromise.

## The Core Question

> "If this component is fully compromised, what can the attacker access? Is the blast
> radius limited to this component, or can they reach other components, the host, or
> other users' data?"

## What To Check

### 1. Container Isolation
**Red flags:**
```yaml
# BAD: privileged container
privileged: true  # disables ALL isolation

# BAD: host namespace sharing
hostNetwork: true   # shares host network stack
hostPID: true       # can see host processes
hostIPC: true       # can access host shared memory

# BAD: dangerous volume mounts
volumes:
    - /var/run/docker.sock:/var/run/docker.sock  # full host control
    - /:/host                                     # full filesystem access
    - /proc:/host/proc                            # process information

# BAD: excessive capabilities
securityContext:
    capabilities:
        add: [SYS_ADMIN, NET_ADMIN, SYS_PTRACE]

# GOOD: minimal isolation
securityContext:
    runAsNonRoot: true
    readOnlyRootFilesystem: true
    allowPrivilegeEscalation: false
    capabilities:
        drop: [ALL]
```

**Review check:**
- Does the container run as non-root?
- Are all capabilities dropped except those specifically needed?
- Is the filesystem read-only where possible?
- Are host namespaces NOT shared?
- Are volume mounts minimal and specific?
- Is the Docker socket NOT mounted?

### 2. Process Sandbox (Browser/Desktop)
**Review check:**
- Are untrusted inputs processed in sandboxed processes?
- Is the IPC between sandboxed and privileged processes minimal?
- Is every IPC message from the sandbox validated?
- Can the sandbox access the filesystem, network, or other processes?

### 3. Database Isolation
**Red flags:**
```sql
-- BAD: application using root/admin database user
-- Application has DROP TABLE, CREATE USER permissions

-- BAD: shared database for multiple tenants without row-level security
-- One tenant can access another's data via SQL injection

-- GOOD: least-privilege database user
GRANT SELECT, INSERT, UPDATE ON app_tables TO app_user;
-- No DROP, ALTER, CREATE, GRANT permissions
```

**Review check:**
- Does the application use a least-privilege database user?
- Is multi-tenant data isolated (separate schemas, row-level security, or separate
  databases)?
- Can the application user modify the schema?

### 4. Network Segmentation
**Review check:**
- Can the application reach internal services it doesn't need?
- Are management/admin ports (metrics, debug, health) on a separate network?
- Is egress filtered (can a compromised service make arbitrary outbound connections)?
- Is the cloud metadata endpoint (169.254.169.254) blocked?

### 5. Filesystem Permissions
**Review check:**
- Are configuration files readable only by the service user?
- Are key/secret files readable only by the service user?
- Are log files not world-readable?
- Is the application's directory writable only by the appropriate user?
- Are temporary files created with restricted permissions?

### 6. Cloud IAM and Service Accounts
**Red flags:**
```json
// BAD: overly broad IAM policy
{
    "Effect": "Allow",
    "Action": "*",
    "Resource": "*"
}

// BAD: service account with admin permissions
// BAD: shared service account between multiple services
```

**Review check:**
- Does each service have its own service account?
- Are IAM permissions minimal (only the specific actions and resources needed)?
- Are wildcard permissions avoided?
- Is there a mechanism to rotate service account credentials?

### 7. Secret Isolation
**Review check:**
- Are secrets stored in a dedicated secret manager (Vault, AWS Secrets Manager, K8s
  Secrets) rather than environment variables or config files?
- Are secrets scoped to the specific service that needs them?
- Can one service access another service's secrets?
- Are secrets rotatable without downtime?

### 8. Tenant Isolation (Multi-tenant Systems)
**Review check:**
- Is data access filtered by tenant ID at the query level?
- Can a user access another tenant's data by manipulating IDs?
- Are background jobs tenant-scoped?
- Are caches tenant-scoped (no cross-tenant cache pollution)?
- Are file uploads tenant-scoped (no cross-tenant file access)?

### 9. Blast Radius Assessment
For every component, assess what happens if it's fully compromised:

```
Component    | Compromised Impact
-------------|---------------------------------------------------
Web frontend | XSS, session theft for that user
API server   | All user data, all API operations
Database     | All data, including other services if shared
CI/CD        | Supply chain compromise, production access
Admin panel  | Full system control
```

**Review check:** For the component being reviewed: if an attacker has full control,
what's the worst they can do? Is that blast radius acceptable, or should isolation be
tightened?

### 10. Defense in Depth
No single security control should be the only thing preventing compromise.

**Review check:**
- If authentication is bypassed, does authorization still block unauthorized access?
- If the WAF is bypassed, does input validation in the application catch attacks?
- If the network is compromised, does encryption protect data in transit?
- If one container is compromised, can it reach others?

## Catalog References
- A5 (runc escape) — /proc/self/exe accessible from untrusted container
- M34 (Docker socket) — host control plane mounted in container
- M35 (K8s RBAC defaults) — overly broad default permissions
- CR5-CR6 (Chromium IPC and Site Isolation review) — mandatory sandbox boundary validation catches
