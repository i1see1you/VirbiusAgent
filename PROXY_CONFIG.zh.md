# virbius-mcp-proxy 配置参考

`virbius-mcp-proxy` 是 MCP 安全代理，位于 Agent 客户端与上游 MCP Server 之间，提供 License 校验、风控引擎调用、审计日志和决策链路追踪。

## 目录

- [配置文件](#配置文件)
- [完整配置项](#完整配置项)
  - [proxy — 传输与上游](#proxy--传输与上游)
  - [security — 安全配置](#security--安全配置)
  - [audit — 审计配置](#audit--审计配置)
  - [trace — 链路追踪配置](#trace--链路追踪配置)
  - [memory — 记忆拦截器](#memory--记忆拦截器)
- [环境变量](#环境变量)
- [Dev 环境配置示例（Redis Stream）](#dev-环境配置示例redis-stream)
- [线上环境配置示例（Kafka）](#线上环境配置示例kafka)
- [后端选择逻辑](#后端选择逻辑)
- [License 凭证配置](#license-凭证配置)
- [启动方式](#启动方式)

---

## 配置文件

Proxy 启动时按以下顺序查找配置文件，找到第一个即加载：

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 | `./virbius-mcp-proxy.toml` | 当前工作目录 |
| 2 | `/etc/virbius/mcp-proxy.toml` | 系统目录 |

如果两个文件都不存在，使用全部默认值 + 环境变量覆盖。

> **文件名固定**，不支持 `--config` 命令行参数自定义路径。可通过环境变量覆盖所有配置项。

---

## 完整配置项

### proxy — 传输与上游

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `listen` | string | `"stdio"` | 监听地址：`"stdio"`（标准输入输出）或 `"tcp://0.0.0.0:9090"` / `"http://0.0.0.0:9090"` |
| `upstream_url` | string | `"http://localhost:8080"` | 单上游 MCP Server 地址（简写模式） |
| `upstream_transport` | string | `"sse"` | 上游传输协议：`"sse"` 或 `"stdio"` |
| `upstream_sse_path` | string | `"/sse"` | 上游 SSE 端点路径 |
| `upstreams` | array | `[]` | 多上游模式配置（与 `upstream_url` 二选一，`upstreams` 优先） |
| `session_ttl_secs` | u64 | `1800` | 会话 TTL（秒），超时自动清理 |

**多上游 `upstreams` 数组元素：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | string | 上游名称（用于路由和日志） |
| `url` | string | 上游 MCP Server 地址 |
| `sse_path` | string | SSE 端点路径，默认 `"/sse"` |

> 如果 `upstreams` 为空且 `upstream_url` 非空，自动归一化为单条 `upstreams` 记录。

---

### security — 安全配置

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `control_base_url` | string | `"http://localhost:8080"` | Control Plane 地址（预留） |
| `engine_url` | string | `"http://localhost:8082"` | 风控引擎地址 |
| `license_public_key` | string | `""` | License 公钥 PEM 文件路径（Ed25519） |
| `license_file` | string | `""` | License JWT 文件路径（回退凭证） |
| `fallback_policy` | string | `"minimum_privilege"` | 无 License 时的回退策略 |

**`fallback_policy` 可选值：**

| 值 | 行为 |
|----|------|
| `"minimum_privilege"` | 低风险工具放行，高风险工具拒绝 |
| `"default_deny"` | 全部拒绝 |
| `"audit_only"` | 全部放行（仅审计记录） |

**`[security.fast_path]` — 快速路径：**

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 启用快速路径（跳过 Engine 调用） |
| `warmup_calls` | u32 | `5` | 预热调用次数（前 N 次必走 Engine） |
| `risk_threshold` | u32 | `30` | 风险分阈值（低于此值走快速路径） |

**`[security.failover]` — Engine 不可用时的容错：**

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `high_risk_fail_closed` | bool | `true` | 高风险工具 Engine 不可用时拒绝 |
| `low_risk_fail_open` | bool | `true` | 低风险工具 Engine 不可用时放行 |
| `engine_timeout_ms` | u64 | `3000` | Engine 调用超时（毫秒） |

**`[security.output_review]` — 工具输出安全审查：**

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `true` | 启用输出内容 LLM 审查 |
| `min_text_length` | usize | `512` | 触发审查的最小文本长度 |
| `min_risk_score` | u32 | `50` | 触发审查的最小会话风险分 |
| `fail_open` | bool | `true` | Engine 不可用时放行输出（false 则阻断） |

---

### audit — 审计配置

审计事件记录每次工具调用的决策结果（allow / block / challenge）。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `backend` | string | `""` | 后端类型：留空（自动选择）或 `"kafka"` |
| `redis_url` | string | `""` | Redis 地址，格式 `host:port` |
| `kafka_brokers` | string | `""` | Kafka broker 列表，逗号分隔 |
| `kafka_topic` | string | `"virbius-audit-events"` | Kafka topic 名称 |
| `sample_rate` | f64 | `1.0` | 采样率 `0.0`~`1.0`（仅对 `allow` 事件生效） |

> Redis 模式下写入 Stream key `virbius:audit:stream`。

---

### trace — 链路追踪配置

决策链路追踪记录完整的工具调用链：输入 → 推理 → 工具调用 → 工具结果 → 输出。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `backend` | string | `""` | 后端类型：留空（自动选择）或 `"kafka"` |
| `redis_url` | string | `""` | Redis 地址，留空则复用 `audit.redis_url` |
| `kafka_brokers` | string | `""` | Kafka broker 列表 |
| `kafka_topic` | string | `"virbius-trace-events"` | Kafka topic 名称 |
| `enabled` | bool | `true` | 启用/禁用追踪 |

> Redis 模式下写入 Stream key `virbius:trace:stream`。

---

### memory — 记忆拦截器

记忆拦截器在代理侧覆盖 edge manifest 的记忆配置，用于检测 Agent 通过工具写入记忆时的注入风险。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | bool | `false` | 启用记忆拦截 |
| `max_entry_size` | usize | `4096` | 单条记忆最大字节数 |
| `tool_patterns` | array | `[]` | 触发记忆拦截的工具名匹配模式 |

---

## 环境变量

所有配置项均可通过环境变量覆盖，**优先级高于配置文件**。

### 传输与上游

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_TRANSPORT` | `proxy.listen` |
| `VIRBIUS_UPSTREAM_URL` | `proxy.upstream_url` |
| `VIRBIUS_UPSTREAM_TRANSPORT` | `proxy.upstream_transport` |
| `VIRBIUS_UPSTREAM_SSE_PATH` | `proxy.upstream_sse_path` |
| `VIRBIUS_UPSTREAMS` | `proxy.upstreams`（JSON 数组格式） |
| `VIRBIUS_SESSION_TTL_SECS` | `proxy.session_ttl_secs` |

`VIRBIUS_UPSTREAMS` 示例：
```bash
export VIRBIUS_UPSTREAMS='[{"name":"fs","url":"http://localhost:8001","sse_path":"/sse"},{"name":"gh","url":"http://localhost:8002","sse_path":"/sse"}]'
```

### 安全

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_CONTROL_URL` | `security.control_base_url` |
| `VIRBIUS_ENGINE_URL` | `security.engine_url` |
| `VIRBIUS_LICENSE_PUBLIC_KEY` | `security.license_public_key` |
| `VIRBIUS_LICENSE_FILE` | `security.license_file` |
| `VIRBIUS_FALLBACK_POLICY` | `security.fallback_policy` |

### 审计

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_REDIS_URL` | `audit.redis_url` |
| `VIRBIUS_AUDIT_BACKEND` | `audit.backend` |
| `VIRBIUS_AUDIT_KAFKA_BROKERS` | `audit.kafka_brokers` |
| `VIRBIUS_AUDIT_KAFKA_TOPIC` | `audit.kafka_topic` |

### 链路追踪

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_TRACE_REDIS_URL` | `trace.redis_url` |
| `VIRBIUS_TRACE_BACKEND` | `trace.backend` |
| `VIRBIUS_TRACE_KAFKA_BROKERS` | `trace.kafka_brokers` |
| `VIRBIUS_TRACE_KAFKA_TOPIC` | `trace.kafka_topic` |

---

## Dev 环境配置示例（Redis Stream）

开发环境使用 Redis Stream 作为审计和追踪后端：

**`virbius-mcp-proxy.toml`：**

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

# Trace — 复用 audit.redis_url
[trace]
enabled = true
```

或使用环境变量（无需配置文件）：

```bash
export VIRBIUS_TRANSPORT=stdio
export VIRBIUS_UPSTREAM_URL=http://localhost:8080
export VIRBIUS_ENGINE_URL=http://localhost:8082
export VIRBIUS_LICENSE_PUBLIC_KEY=/path/to/license_pub.pem
export VIRBIUS_LICENSE_FILE=/path/to/agent-license.jwt
export VIRBIUS_REDIS_URL=localhost:6379

cargo run --release -p virbius-mcp-proxy
```

**验证审计 Stream：**

```bash
# 查看审计事件
redis-cli XREAD COUNT 10 STREAMS virbius:audit:stream 0

# 查看追踪事件
redis-cli XREAD COUNT 10 STREAMS virbius:trace:stream 0
```

> **注意：** Redis 后端使用裸 TCP 连接，不支持密码认证和 TLS。仅适用于本地开发环境。

---

## 线上环境配置示例（Kafka）

生产环境使用 Kafka 作为审计和追踪后端：

**`/etc/virbius/mcp-proxy.toml`：**

```toml
[proxy]
listen = "tcp://0.0.0.0:9090"

# 多上游模式
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

或使用环境变量：

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

**验证 Kafka topic：**

```bash
# 消费审计事件
kafka-console-consumer.sh --bootstrap-server kafka-1:9092 \
  --topic virbius-audit-events --from-beginning --max-messages 10

# 消费追踪事件
kafka-console-consumer.sh --bootstrap-server kafka-1:9092 \
  --topic virbius-trace-events --from-beginning --max-messages 10
```

---

## 后端选择逻辑

审计和追踪的后端选择遵循相同逻辑：

```
backend == "kafka" 且 kafka_brokers 非空
    → Kafka

否则 redis_url 非空（trace 为空则复用 audit.redis_url）
    → Redis Stream

否则
    → Disabled（不发送）
```

| | Redis Stream | Kafka |
|---|---|---|
| **适用环境** | 开发 / 测试 | 生产 |
| **Stream Key** | `virbius:audit:stream` / `virbius:trace:stream` | — |
| **Topic** | — | `virbius-audit-events` / `virbius-trace-events` |
| **认证** | 不支持密码 / TLS | 由 broker 配置决定（SASL/SSL） |
| **消息 Key** | — | `tenant_id`（用于分区路由） |
| **容错** | 断线重连（5 秒间隔） | `message.timeout.ms=5000` |
| **队列缓冲** | 512（audit） / 1024（trace） | 同左 |

> Audit 和 Trace 可以独立配置不同后端（例如 Audit 用 Kafka，Trace 用 Redis）。Trace 的 `redis_url` 留空时自动复用 Audit 的 `redis_url`。

---

## License 凭证配置

### 公钥（验签用）

从 VirbiusAgent 运营台 → Agent 凭证 → 签名密钥 获取公钥 PEM，保存为文件：

```bash
# 在运营台复制公钥后
cat > /etc/virbius/license_pub.pem << 'EOF'
-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEA...
-----END PUBLIC KEY-----
EOF
```

### License JWT（回退凭证）

在运营台签发凭证后，将 JWT 字符串保存为文件（单行）：

```bash
# 签发后复制 JWT，保存为文件
echo 'eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhcHBfaWQiOi...' > /etc/virbius/agent-license.jwt
```

### 优先级

License JWT 有两个来源，按优先级从高到低：

1. **Agent 传入**（`initialize._meta.license_jwt`）— Agent 在 MCP `initialize` 请求中携带
2. **配置文件回退**（`license_file`）— Agent 未传入时使用

如果两个来源都为空，执行 `fallback_policy` 策略。

> JWT 默认有效期 1 年（31536000 秒），可在签发时自定义。过期后需重新签发并更新文件。

---

## 启动方式

### stdio 模式（Sidecar）

```bash
# 方式 1：使用配置文件（当前目录）
cd /opt/virbius && virbius-mcp-proxy

# 方式 2：使用环境变量
VIRBIUS_TRANSPORT=stdio VIRBIUS_UPSTREAM_URL=http://localhost:8080 virbius-mcp-proxy
```

Agent 客户端配置（以 Claude Desktop 为例）：

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

### SSE/HTTP 模式（远程）

```bash
# 方式 1：使用配置文件
virbius-mcp-proxy  # 配置文件中 listen = "tcp://0.0.0.0:9090"

# 方式 2：使用环境变量
VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090 virbius-mcp-proxy
```

Agent 客户端连接：

```
SSE 端点: http://proxy-host:9090/sse
消息端点: http://proxy-host:9090/messages/?session_id=xxx
简单 HTTP: POST http://proxy-host:9090/
```

### Docker 启动

```bash
docker run -d \
  --name virbius-mcp-proxy \
  -p 9090:9090 \
  -v /etc/virbius/mcp-proxy.toml:/etc/virbius/mcp-proxy.toml:ro \
  -v /etc/virbius/license_pub.pem:/etc/virbius/license_pub.pem:ro \
  -v /etc/virbius/agent-license.jwt:/etc/virbius/agent-license.jwt:ro \
  virbius/mcp-proxy:latest
```
