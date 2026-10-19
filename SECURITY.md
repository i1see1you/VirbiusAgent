# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x | ✅ Security fixes land on the latest 1.0.x patch release |
| < 1.0 (pre-GA development) | ❌ Not supported |

## Reporting a Vulnerability

If you discover a security vulnerability in VirbiusAgent, please report it privately by opening a GitHub Security Advisory:

https://github.com/i1see1you/VirbiusAgent/security/advisories

Please do **not** report security vulnerabilities via public GitHub Issues.

### What to include

- A clear description of the vulnerability
- Steps to reproduce (PoC preferred)
- Affected versions and components
- Any suggested fix (optional)

### Disclosure Timeline

1. **48 hours** — Acknowledgment of receipt
2. **7 days** — Initial assessment and mitigation plan
3. **30 days** — Release of fix (depending on severity)
4. **Coordinated disclosure** — Public announcement after fix is released

## Security Features

VirbiusAgent includes built-in security mechanisms:

- **Ed25519-signed License JWTs** for Agent authentication and authorization
- **Multi-layer policy enforcement** (Edge, Gateway, Kernel, Cloud)
- **Falco syscall monitoring** for kernel-level threat detection
- **Prompt injection detection** (LLM-based)
- **Audit hash chain** for tamper-evident logging

For details, see [ARCHITECTURE.md](ARCHITECTURE.md) and [DESIGN.md](DESIGN.md).

## Credential Security Baseline (P0)

The platform enforces a fail-fast secret policy at startup
(`SecretPolicyValidator` in virbius-control):

| Requirement | Enforcement |
|---|---|
| License master key present in **every** profile (`VIRBIUS_LICENSE_MASTER_KEY`, ≥32 chars, no known defaults) | startup failure |
| API-key pepper set when API-key auth is enabled (`VIRBIUS_API_KEY_PEPPER`, ≥32 chars) | startup failure (prod/staging) |
| At least one auth mechanism enabled in prod/staging; no implicit admin principal outside dev | startup failure + runtime reject |
| Redis URL embeds a password (`redis://:<password>@host:port`) | startup failure (prod/staging) |
| MySQL JDBC uses TLS with certificate validation (`sslMode=VERIFY_IDENTITY`/`VERIFY_CA`) | startup failure (prod/staging) |
| Kafka uses SASL (`SASL_PLAINTEXT`/`SASL_SSL`) with credentials | startup failure (prod/staging) |

Additional properties:

- Tenant API keys are stored as `HMAC-SHA256(pepper, key)`; legacy unkeyed
  SHA-256 rows are upgraded transparently on first authentication.
- License private keys are encrypted with an HKDF-derived key (envelope
  version `v1:`); legacy ciphertext still decrypts with the old derivation.
- `virbius-auth` is the **only implemented operator login today** (local password +
  self-signed JWT); the dev compose keeps a commented `admin/admin` convenience
  default (never set in non-dev deployments). **SSO is not implemented yet** (design
  finalized — see `SSO_INTEGRATION.md`); production should run virbius-auth as a
  single replica (its signing key is a per-pod file) or keep the console on an
  isolated network.

Known accepted risks (upgrade triggers documented in DEPLOYMENT.md):

- Kafka runs SASL/SCRAM over PLAINTEXT in P0 — audit payloads traverse the
  internal network unencrypted until SASL_SSL lands (P1).
- Compose-generated self-signed MySQL CA — protect the CA key (`0600`, never
  committed); rotate certificates per the DEPLOYMENT.md runbook.
