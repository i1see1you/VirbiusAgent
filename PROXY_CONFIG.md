# virbius-mcp-proxy Configuration Reference

[中文版](PROXY_CONFIG.zh.md)

`virbius-mcp-proxy` is an MCP security proxy positioned between the Agent client and upstream MCP Servers. It provides License verification, risk engine evaluation, audit logging, and decision chain tracing.

## Contents

- [Configuration File](#configuration-file)
- [Full Configuration Reference](#full-configuration-reference)
  - [proxy — Transport & Upstream](#proxy--transport--upstream)
  - [security — Security Configuration](#security--security-configuration)
  - [audit — Audit Configuration](#audit--audit-configuration)
  - [trace — Trace Configuration](#trace--trace-configuration)
  - [memory — Memory Interceptor](#memory--memory-interceptor)
- [Environment Variables](#environment-variables)
- [Dev Configuration Example (Redis Stream)](#dev-configuration-example-redis-stream)
- [Production Configuration Example (Kafka)](#production-configuration-example-kafka)
- [Backend Selection Logic](#backend-selection-logic)
- [License Credential Configuration](#license-credential-configuration)
- [Startup Modes](#startup-modes)

---

## Configuration File

The proxy searches for a configuration file in the following order and loads the first one found:

| Priority | Path | Description |
|----------|------|-------------|
| 1 | `./virbius-mcp-proxy.toml` | Current working directory |
| 2 | `/etc/virbius/mcp-proxy.toml` | System directory |

If neither file exists, all defaults are used with environment variable overrides.

> **The filename is fixed** — there is no `--config` CLI flag. All configuration can be overridden via environment variables.

---

## Full Configuration Reference

### proxy — Transport & Upstream

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `listen` | string | `"stdio"` | Listen address: `"stdio"` (stdin/stdout) or `"tcp://0.0.0.0:9090"` / `"http://0.0.0.0:9090"` |
| `upstream_url` | string | `"http://localhost:8080"` | Single upstream MCP Server URL (shorthand mode) |
| `upstream_transport` | string | `"sse"` | Upstream transport: `"sse"` or `"stdio"` |
| `upstream_sse_path` | string | `"/sse"` | Upstream SSE endpoint path |
| `upstreams` | array | `[]` | Multi-upstream mode config (mutually exclusive with `upstream_url`; `upstreams` takes priority) |
| `session_ttl_secs` | u64 | `1800` | Session TTL in seconds; cleaned up on expiry |

**Multi-upstream `upstreams` array elements:**

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Upstream name (used for routing and logging) |
| `url` | string | Upstream MCP Server URL |
| `sse_path` | string | SSE endpoint path, defaults to `"/sse"` |

> If `upstreams` is empty and `upstream_url` is non-empty, it is auto-normalized into a single-entry `upstreams` array.

---

### security — Security Configuration

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `control_base_url` | string | `"http://localhost:8080"` | Control Plane URL (reserved) |
| `engine_url` | string | `"http://localhost:8082"` | Risk engine URL |
| `license_public_key` | string | `""` | Path to License public key PEM file (Ed25519) |
| `license_file` | string | `""` | Path to License JWT file (fallback credential) |
| `fallback_policy` | string | `"minimum_privilege"` | Fallback policy when no License is present |

**`fallback_policy` options:**

| Value | Behavior |
|-------|----------|
| `"minimum_privilege"` | Allow low-risk tools, deny high-risk tools |
| `"default_deny"` | Deny all |
| `"audit_only"` | Allow all (audit-only logging) |

**`[security.fast_path]` — Fast Path:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | bool | `true` | Enable fast path (skip Engine evaluation) |
| `warmup_calls` | u32 | `5` | Warmup calls — first N calls always go through the Engine |
| `risk_threshold` | u32 | `30` | Risk score threshold — calls below this use fast path |

**`[security.failover]` — Engine Failover:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `high_risk_fail_closed` | bool | `true` | Deny high-risk tools when Engine is unavailable |
| `low_risk_fail_open` | bool | `true` | Allow low-risk tools when Engine is unavailable |
| `engine_timeout_ms` | u64 | `3000` | Engine call timeout in milliseconds |

**`[security.output_review]` — Tool Output Safety Review:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | bool | `true` | Enable LLM-based output content review |
| `min_text_length` | usize | `512` | Minimum text length to trigger review |
| `min_risk_score` | u32 | `50` | Minimum session risk score to trigger review |
| `fail_open` | bool | `true` | Allow output when Engine is unavailable (false = block) |

---

### audit — Audit Configuration

Records audit events for each tool call decision (allow / block / challenge).

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `backend` | string | `""` | Backend type: empty (auto-select) or `"kafka"` |
| `redis_url` | string | `""` | Redis address, format `host:port` |
| `kafka_brokers` | string | `""` | Comma-separated Kafka broker list |
| `kafka_topic` | string | `"virbius-audit-events"` | Kafka topic name |
| `sample_rate` | f64 | `1.0` | Sampling rate `0.0`–`1.0` (only applies to `allow` events) |

> In Redis mode, events are written to Stream key `virbius:audit:stream`.

---

### trace — Trace Configuration

Decision chain tracing records the full tool call chain: input → reasoning → tool_call → tool_result → output.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `backend` | string | `""` | Backend type: empty (auto-select) or `"kafka"` |
| `redis_url` | string | `""` | Redis address; leave empty to reuse `audit.redis_url` |
| `kafka_brokers` | string | `""` | Kafka broker list |
| `kafka_topic` | string | `"virbius-trace-events"` | Kafka topic name |
| `enabled` | bool | `true` | Enable/disable tracing |

> In Redis mode, events are written to Stream key `virbius:trace:stream`.

---

### memory — Memory Interceptor

The memory interceptor overrides the edge manifest's memory configuration at the proxy level. It detects injection risks when an Agent writes to memory via tools.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | bool | `false` | Enable memory interception |
| `max_entry_size` | usize | `4096` | Maximum bytes per memory entry |
| `tool_patterns` | array | `[]` | Tool name match patterns that trigger memory interception |

---

## Environment Variables

All configuration can be overridden via environment variables, **with higher priority than the config file**.

### Transport & Upstream

| Environment Variable | Maps To |
|---------------------|---------|
| `VIRBIUS_TRANSPORT` | `proxy.listen` |
| `VIRBIUS_UPSTREAM_URL` | `proxy.upstream_url` |
| `VIRBIUS_UPSTREAM_TRANSPORT` | `proxy.upstream_transport` |
| `VIRBIUS_UPSTREAM_SSE_PATH` | `proxy.upstream_sse_path` |
| `VIRBIUS_UPSTREAMS` | `proxy.upstreams` (JSON array format) |
| `VIRBIUS_SESSION_TTL_SECS` | `proxy.session_ttl_secs` |

`VIRBIUS_UPSTREAMS` example:
```bash
export VIRBIUS_UPSTREAMS='[{"name":"fs","url":"http://localhost:8001","sse_path":"/sse"},{"name":"gh","url":"http://localhost:8002","sse_path":"/sse"}]'
```

### Security

| Environment Variable | Maps To |
|---------------------|---------|
| `VIRBIUS_CONTROL_URL` | `security.control_base_url` |
| `VIRBIUS_ENGINE_URL` | `security.engine_url` |
| `VIRBIUS_LICENSE_PUBLIC_KEY` | `security.license_public_key` |
| `VIRBIUS_LICENSE_FILE` | `security.license_file` |
| `VIRBIUS_FALLBACK_POLICY` | `security.fallback_policy` |

### Audit

| Environment Variable | Maps To |
|---------------------|---------|
| `VIRBIUS_REDIS_URL` | `audit.redis_url` |
| `VIRBIUS_AUDIT_BACKEND` | `audit.backend` |
| `VIRBIUS_AUDIT_KAFKA_BROKERS` | `audit.kafka_brokers` |
| `VIRBIUS_AUDIT_KAFKA_TOPIC` | `audit.kafka_topic` |

### Trace

| Environment Variable | Maps To |
|---------------------|---------|
| `VIRBIUS_TRACE_REDIS_URL` | `trace.redis_url` |
| `VIRBIUS_TRACE_BACKEND` | `trace.backend` |
| `VIRBIUS_TRACE_KAFKA_BROKERS` | `trace.kafka_brokers` |
| `VIRBIUS_TRACE_KAFKA_TOPIC` | `trace.kafka_topic` |

---

## Dev Configuration Example (Redis Stream)

Development environments use Redis Stream as the audit and tracing backend:

**`virbius-mcp-proxy.toml`:**

```toml
[proxy]
listen = "stdio"
upstream_url = "http://localhost:8080"
session_ttl_secs = 1800

[security]
engine_url = "http://localhost:8082"
license_public_key = "/path/to/license_pub.pem"
license_file = "/path/to/agent-license.jwt"
fallback_policy = "minimum_privilege"

[security.fast_path]
enabled = true
warmup_calls = 5
risk_threshold = 30

[security.failover]
high_risk_fail_closed = true
low_risk_fail_open = true
engine_timeout_ms = 3000

[security.output_review]
enabled = true
min_text_length = 512
min_risk_score = 50
fail_open = true

# Audit — Redis Stream
[audit]
redis_url = "localhost:6379"
sample_rate = 1.0

# Trace — reuses audit.redis_url
[trace]
enabled = true
```

Or using environment variables (no config file needed):

```bash
export VIRBIUS_TRANSPORT=stdio
export VIRBIUS_UPSTREAM_URL=http://localhost:8080
export VIRBIUS_ENGINE_URL=http://localhost:8082
export VIRBIUS_LICENSE_PUBLIC_KEY=/path/to/license_pub.pem
export VIRBIUS_LICENSE_FILE=/path/to/agent-license.jwt
export VIRBIUS_REDIS_URL=localhost:6379

cargo run --release -p virbius-mcp-proxy
```

**Verifying the audit Stream:**

```bash
# View audit events
redis-cli XREAD COUNT 10 STREAMS virbius:audit:stream 0

# View trace events
redis-cli XREAD COUNT 10 STREAMS virbius:trace:stream 0
```

> **Note:** The Redis backend uses raw TCP connections and does not support password authentication or TLS. For local development only.

---

## Production Configuration Example (Kafka)

Production environments use Kafka as the audit and tracing backend:

**`/etc/virbius/mcp-proxy.toml`:**

```toml
[proxy]
listen = "tcp://0.0.0.0:9090"

# Multi-upstream mode
[[proxy.upstreams]]
name = "filesystem"
url = "http://mcp-server-1:8001"
sse_path = "/sse"

[[proxy.upstreams]]
name = "github"
url = "http://mcp-server-2:8002"
sse_path = "/sse"

session_ttl_secs = 1800

[security]
control_base_url = "http://virbius-control:8080"
engine_url = "http://virbius-engine:8082"
license_public_key = "/etc/virbius/license_pub.pem"
license_file = "/etc/virbius/agent-license.jwt"
fallback_policy = "minimum_privilege"

[security.fast_path]
enabled = true
warmup_calls = 5
risk_threshold = 30

[security.failover]
high_risk_fail_closed = true
low_risk_fail_open = true
engine_timeout_ms = 3000

[security.output_review]
enabled = true
min_text_length = 512
min_risk_score = 50
fail_open = true

# Audit — Kafka
[audit]
backend = "kafka"
kafka_brokers = "kafka-1:9092,kafka-2:9092,kafka-3:9092"
kafka_topic = "virbius-audit-events"
sample_rate = 0.1

# Trace — Kafka
[trace]
backend = "kafka"
kafka_brokers = "kafka-1:9092,kafka-2:9092,kafka-3:9092"
kafka_topic = "virbius-trace-events"
enabled = true
```

Or using environment variables:

```bash
export VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090
export VIRBIUS_ENGINE_URL=http://virbius-engine:8082
export VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license_pub.pem
export VIRBIUS_LICENSE_FILE=/etc/virbius/agent-license.jwt
export VIRBIUS_AUDIT_BACKEND=kafka
export VIRBIUS_AUDIT_KAFKA_BROKERS=kafka-1:9092,kafka-2:9092,kafka-3:9092
export VIRBIUS_AUDIT_KAFKA_TOPIC=virbius-audit-events
export VIRBIUS_TRACE_BACKEND=kafka
export VIRBIUS_TRACE_KAFKA_BROKERS=kafka-1:9092,kafka-2:9092,kafka-3:9092
export VIRBIUS_TRACE_KAFKA_TOPIC=virbius-trace-events

virbius-mcp-proxy
```

**Verifying Kafka topics:**

```bash
# Consume audit events
kafka-console-consumer.sh --bootstrap-server kafka-1:9092 \
  --topic virbius-audit-events --from-beginning --max-messages 10

# Consume trace events
kafka-console-consumer.sh --bootstrap-server kafka-1:9092 \
  --topic virbius-trace-events --from-beginning --max-messages 10
```

---

## Backend Selection Logic

Audit and tracing backends follow the same selection logic:

```
backend == "kafka" and kafka_brokers is non-empty
    → Kafka

otherwise redis_url is non-empty (trace falls back to audit.redis_url if empty)
    → Redis Stream

otherwise
    → Disabled (no events sent)
```

| | Redis Stream | Kafka |
|---|---|---|
| **Environment** | Development / Testing | Production |
| **Stream Key** | `virbius:audit:stream` / `virbius:trace:stream` | — |
| **Topic** | — | `virbius-audit-events` / `virbius-trace-events` |
| **Authentication** | Password / TLS not supported | Determined by broker config (SASL/SSL) |
| **Message Key** | — | `tenant_id` (for partition routing) |
| **Fault Tolerance** | Reconnect on disconnect (5s interval) | `message.timeout.ms=5000` |
| **Queue Buffer** | 512 (audit) / 1024 (trace) | Same |

> Audit and Trace can use different backends independently (e.g., Audit via Kafka, Trace via Redis). If Trace's `redis_url` is empty, it automatically reuses Audit's `redis_url`.

---

## License Credential Configuration

### Public Key (for signature verification)

Obtain the public key PEM from the VirbiusAgent console (Agent Credentials → Signing Keys), then save it to a file:

```bash
# After copying the public key from the console
cat > /etc/virbius/license_pub.pem << 'EOF'
-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEA...
-----END PUBLIC KEY-----
EOF
```

### License JWT (Fallback Credential)

After issuing a credential in the console, save the JWT string to a file (single line):

```bash
# Copy the JWT after issuance and save to a file
echo 'eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhcHBfaWQiOi...' > /etc/virbius/agent-license.jwt
```

### Priority

License JWT has two sources, in priority order:

1. **Agent-provided** (`initialize._meta.license_jwt`) — The Agent carries it in the MCP `initialize` request
2. **Config file fallback** (`license_file`) — Used when the Agent does not provide one

If both sources are empty, the `fallback_policy` is applied.

> JWT default validity is 1 year (31536000 seconds), customizable at issuance. After expiry, re-issue and update the file.

---

## Startup Modes

### stdio Mode (Sidecar)

```bash
# Method 1: Use config file (current directory)
cd /opt/virbius && virbius-mcp-proxy

# Method 2: Use environment variables
VIRBIUS_TRANSPORT=stdio VIRBIUS_UPSTREAM_URL=http://localhost:8080 virbius-mcp-proxy
```

Agent client configuration (using Claude Desktop as example):

```json
{
  "mcp_servers": {
    "virbius_proxied": {
      "command": "/opt/virbius/virbius-mcp-proxy",
      "args": [],
      "env": {
        "VIRBIUS_UPSTREAM_URL": "http://localhost:8080",
        "VIRBIUS_ENGINE_URL": "http://localhost:8082",
        "VIRBIUS_LICENSE_PUBLIC_KEY": "/etc/virbius/license_pub.pem",
        "VIRBIUS_LICENSE_FILE": "/etc/virbius/agent-license.jwt",
        "VIRBIUS_REDIS_URL": "localhost:6379"
      }
    }
  }
}
```

### SSE/HTTP Mode (Remote)

```bash
# Method 1: Use config file
virbius-mcp-proxy  # config file has listen = "tcp://0.0.0.0:9090"

# Method 2: Use environment variables
VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090 virbius-mcp-proxy
```

Agent client connection:

```
SSE endpoint:   http://proxy-host:9090/sse
Message endpoint: http://proxy-host:9090/messages/?session_id=xxx
Simple HTTP:    POST http://proxy-host:9090/
```

### Docker

```bash
docker run -d \
  --name virbius-mcp-proxy \
  -p 9090:9090 \
  -v /etc/virbius/mcp-proxy.toml:/etc/virbius/mcp-proxy.toml:ro \
  -v /etc/virbius/license_pub.pem:/etc/virbius/license_pub.pem:ro \
  -v /etc/virbius/agent-license.jwt:/etc/virbius/agent-license.jwt:ro \
  virbius/mcp-proxy:latest
```
