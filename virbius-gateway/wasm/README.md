# virbius-gateway WASM plugin

Higress (Envoy) WASM plugin for MCP security pre-check.

## Build

```bash
# Build WASM binary (requires tinygo)
tinygo build -o virbius-gateway.wasm -scheduler=none -target=wasi ./...

# Or using Higress build toolchain
higress wasm build -p .
```

## Configuration

The plugin is configured via Higress `WasmPlugin` CRD:

```yaml
apiVersion: networking.higress.io/v1
kind: WasmPlugin
metadata:
  name: virbius-gateway
spec:
  url: oci://registry.internal/virbius-gateway:latest
  phase: AUTHN
  defaultConfig:
    tenant_id: "default"
    evaluate: true
    engine_url: "http://virbius-engine:8082"
    engine_timeout_ms: 3000
    tool_rate_limit: 50
    fast_path_tools: ["search", "calculator"]
    tool_allowlist: ["read_file", "search", "calculator"]
    license_verify: true
    tls: true
    fail_mode: "open"
```

## Capabilities

| Capability | Phase | Implementation |
|-----------|-------|---------------|
| tool allowlist | RequestHeaders | Local match against config |
| Rate limiting | RequestHeaders | Redis INCR (async) |
| Fast-path bypass | RequestHeaders | Skip engine for low-risk tools |
| Engine evaluate | RequestHeaders | HTTP POST /v1/evaluate (async) |
| HTTP 403 block | Response | Envoy direct response 403 + JSON-RPC error |
