# virbius-mcp-proxy

Security-aware MCP proxy (stdio/SSE) with a built-in tool-call pipeline:
License + allowlist check, engine adjudication, multi-upstream routing, audit
and decision tracing.

Part of [VirbiusAgent](https://github.com/i1see1you/VirbiusAgent) — an open-source
four-tier **Edge–Gateway–Kernel–Cloud** security platform for AI Agents.

## Install

```bash
cargo install virbius-mcp-proxy
```

## Run

```bash
VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090 virbius-mcp-proxy
```

## Links

- Repository: <https://github.com/i1see1you/VirbiusAgent>
- Documentation: <https://github.com/i1see1you/VirbiusAgent#readme>
- Website: <https://www.virbius.tech>
- License: MIT
