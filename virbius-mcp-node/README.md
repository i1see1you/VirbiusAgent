# virbius-mcp-node

VirbiusAgent edge enforcement primitives for Node.js — a napi-rs binding of
`virbius-core`: tool-call precheck, License verification, DLP masking, and
Prompt Gateway.

Part of [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) — an open-source
four-tier **Edge–Gateway–Kernel–Cloud** security platform for AI Agents.

## Install

```bash
npm install virbius-mcp-node
```

## Platform support

Prebuilt native binaries are currently published for **macOS arm64 (Apple
Silicon)** only. Linux (x64/arm64), macOS x64 and Windows binaries are
produced by the CI matrix (see the repository roadmap); until then, on other
platforms `require('virbius-mcp-node')` throws `Failed to load native
binding`. As a workaround, build from source on your platform: clone the
repository and run `npm run build` in `virbius-mcp-node/` (requires the Rust
toolchain).

## Usage

```js
const {
  precheckTool,
  verifyLicense,
  configureRules,
  desensitize,
  restore,
  enhancePrompt,
} = require('virbius-mcp-node')

const decision = precheckTool(
  'send_email',
  JSON.stringify({ to: 'user@example.com' }),
  licenseJwt,
  publicKeyPem,
  'agent1'
)

if (!decision.allowed) {
  throw new Error(`blocked: ${decision.code ?? decision.reason}`)
}
```

## Links

- Repository: <https://github.com/i1see1you/VirbiusAgent>
- Website: <https://www.virbius.cn>
- License: MIT
