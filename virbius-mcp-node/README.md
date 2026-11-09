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
