# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.x (development) | ✅ Security fixes applied to main branch |

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
