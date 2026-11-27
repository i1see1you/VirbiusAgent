# gVisor gate fixtures (TEST ONLY)

`license.jwt` + `license.pub.pem` are a **test-only** Ed25519 license fixture for
`scripts/test-gvisor-gate.sh`:

- `app_id = gvisor-gate`, `tenant_id = default`
- `allowed_tools = ["virbius__shell", "virbius__python", "virbius__node"]`
- `exp = 2035-01-01`

The signing private key was generated and discarded; only the public key and the
pre-signed JWT are committed, so this gate needs no control plane.

**Do not trust this public key or JWT in production.** It only works if a proxy
is explicitly configured with `VIRBIUS_LICENSE_PUBLIC_KEY=.../license.pub.pem`;
real deployments use the key issued by Control.
