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
- [Dev 环境配置示例（Kafka）](#dev-环境配置示例kafka)
- [线上环境配置示例（Kafka）](#线上环境配置示例kafka)
- [后端选择逻辑](#后端选择逻辑)
- [License 凭证配置](#license-凭证配置)
- [启动方式](#启动方式)
- [命令行](#命令行)
- [常见报错对照表](#常见报错对照表)

---

## 配置文件

Proxy 启动时按以下顺序查找配置文件，找到第一个即加载：

| 优先级 | 路径 | 说明 |
|--------|------|------|
| 1 | `./virbius-mcp-proxy.toml` | 当前工作目录 |
| 2 | `/etc/virbius/mcp-proxy.toml` | 系统目录 |

如果两个文件都不存在，使用全部默认值 + 环境变量覆盖。

> **`--config <path>` 显式指定**：显式给出的配置文件**必须**可读且可解析——路径写错或
> TOML 损坏会直接 `exit(2)`，不会静默回退默认值。未指定时按上表搜索：搜索路径上存在
> 但解析失败的文件会**打印 warning** 后继续用默认值（旧行为是完全静默）。所有配置项
> 仍可被环境变量覆盖。

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
| `control_base_url` | string | `"http://localhost:8080"` | Control Plane 地址 —— `enroll` 默认目标，也可由 `VIRBIUS_BASE_URL` 推导 |
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
| `backend` | string | `""` | 后端类型：`"kafka"`（留空时 broker 非空则自动选 Kafka，否则 Disabled） |
| `redis_url` | string | `""` | 共享 Redis 地址（`host:port`）。审计总线已 Kafka-only；此值现仅作策略订阅 / delegated 快照的默认回退 |
| `kafka_brokers` | string | `""` | Kafka broker 列表，逗号分隔 |
| `kafka_topic` | string | `"virbius-audit-events"` | Kafka topic 名称 |
| `sample_rate` | f64 | `1.0` | 采样率 `0.0`~`1.0`（仅对 `allow` 事件生效） |

---

### trace — 链路追踪配置

决策链路追踪记录完整的工具调用链：输入 → 推理 → 工具调用 → 工具结果 → 输出。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `backend` | string | `""` | 后端类型：`"kafka"`（留空时 broker 非空则自动选 Kafka，否则 Disabled） |
| `kafka_brokers` | string | `""` | Kafka broker 列表 |
| `kafka_topic` | string | `"virbius-trace-events"` | Kafka topic 名称 |
| `enabled` | bool | `true` | 启用/禁用追踪 |

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
| `VIRBIUS_AUTH_CONTROL_URL` | `auth.control_base_url`（evaluate-token 换取） |

### 单一基地址（可选，opt-in）

| 环境变量 | 作用 |
|----------|------|
| `VIRBIUS_BASE_URL` | 一个变量推导三个端点：`control_base_url` 与 `auth.control_base_url` = 该地址本身；`engine_url` = 同主机 `:8082`。仅替换仍是内置默认值的项——显式设置的上面的变量或配置文件里的值**总是优先**。适合单机部署把环境变量数量从 3 个减到 1 个。 |

### 审计

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_REDIS_URL` | `audit.redis_url` |
| `VIRBIUS_AUDIT_BACKEND` | `audit.backend` |
| `VIRBIUS_AUDIT_KAFKA_BROKERS` | `audit.kafka_brokers` |
| `VIRBIUS_AUDIT_KAFKA_TOPIC` | `audit.kafka_topic` |

#### Kafka SASL（P0）

审计/trace 的 Kafka 生产者读取以下环境变量（两个后端共用，producer 创建时生效）：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VIRBIUS_KAFKA_SECURITY_PROTOCOL` | *(空 = PLAINTEXT)* | `SASL_PLAINTEXT` 或 `SASL_SSL`（其他值直接终止） |
| `VIRBIUS_KAFKA_SASL_MECHANISM` | `SCRAM-SHA-256` | |
| `VIRBIUS_KAFKA_SASL_USERNAME` / `VIRBIUS_KAFKA_SASL_PASSWORD` | *(空)* | SASL 模式必填 |
| `VIRBIUS_KAFKA_SSL_CA` | *(空)* | CA 证书路径，仅 `SASL_SSL` 使用 |

代理读取 Redis URL 的位置（策略订阅、delegated 快照）均接受带密码形式：
`redis://:<password>@host:port`。

### 链路追踪

| 环境变量 | 对应配置项 |
|----------|-----------|
| `VIRBIUS_TRACE_BACKEND` | `trace.backend` |
| `VIRBIUS_TRACE_KAFKA_BROKERS` | `trace.kafka_brokers` |
| `VIRBIUS_TRACE_KAFKA_TOPIC` | `trace.kafka_topic` |

---

## Dev 环境配置示例（Kafka）

开发环境同样使用 Kafka 作为审计和追踪后端（Compose 内置 broker，`localhost:9092`）：

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

# Audit — Kafka
[audit]
backend = "kafka"
kafka_brokers = "localhost:9092"
kafka_topic = "virbius-audit-events"
sample_rate = 1.0

# Trace — Kafka
[trace]
backend = "kafka"
kafka_brokers = "localhost:9092"
kafka_topic = "virbius-trace-events"
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
export VIRBIUS_AUDIT_BACKEND=kafka
export VIRBIUS_AUDIT_KAFKA_BROKERS=localhost:9092
export VIRBIUS_TRACE_BACKEND=kafka
export VIRBIUS_TRACE_KAFKA_BROKERS=localhost:9092

cargo run --release -p virbius-mcp-proxy
```

**验证 Kafka topic：**

```bash
# 查看审计事件
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic virbius-audit-events --from-beginning --max-messages 10

# 查看追踪事件
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic virbius-trace-events --from-beginning --max-messages 10
```

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

审计和追踪的后端选择逻辑（两者一致，Kafka-only）：

```
backend == "kafka" 且 kafka_brokers 非空
    → Kafka

否则
    → Disabled（不发送）
```

| 项 | 值 |
|---|---|
| **适用环境** | dev / staging / prod（统一 Kafka） |
| **Topic** | `virbius-audit-events` / `virbius-trace-events` |
| **认证** | 由 broker 配置决定（SASL/SSL，见上方 Kafka SASL） |
| **消息 Key** | `tenant_id`（用于分区路由） |
| **容错** | `message.timeout.ms=5000`，断线自动重连 |
| **队列缓冲** | 512（audit） / 1024（trace） |

> Redis Stream 后端已移除；`audit.redis_url` 仍作为策略订阅 / delegated 快照的共享默认端点保留。

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

---

## 命令行

```
virbius-mcp-proxy [--config <path>] [run]      # 运行（默认）
virbius-mcp-proxy doctor [--config <path>]     # 起飞前自检
virbius-mcp-proxy enroll [选项]                # 从运营台一键领取 license + 公钥
virbius-mcp-proxy --help / --version
```

### doctor — 起飞前自检

一条命令回答"为什么连不上"：配置摘要、公钥可解析性、license 可验证性（含剩余天数）、
engine/control/token-exchange/upstream 连通性探测。连通性问题只算 **WARN**（proxy 有
failover），配置/license 问题算 **FAIL**（退出码 1）。

```bash
VIRBIUS_LICENSE_FILE=/etc/virbius/agent-license.jwt \
VIRBIUS_LICENSE_PUBLIC_KEY=/etc/virbius/license_pub.pem \
VIRBIUS_BASE_URL=http://virbius-control:8080 \
virbius-mcp-proxy doctor
```

### enroll — 一键领取凭证

把"手工拼 license.jwt + license_pub.pem"变成一条命令：建租户（幂等）→ 注册工具 → 签发
license → 拉取公钥 → 落盘。

```bash
virbius-mcp-proxy enroll \
  --control http://127.0.0.1:8080 \
  --tenant my-tenant --app-id my-agent \
  --tools read_file,search \
  --out-dir /etc/virbius
```

安全设计：

- 管理凭证**只**从环境变量 `VIRBIUS_ADMIN_API_KEY` 读取（以 `X-Virbius-Api-Key` 头发送），
  不提供命令行参数——避免 shell 历史 / `ps` 泄漏。
- 两个文件以 **0600** 权限写入；PEM 归一为恰好一个结尾换行。
- **绝不打印 JWT 本体**，只打印 app_id/租户/工具窗口/有效期和文件路径。
- 对已有 active license 的 app 重复 enroll 会 409；加 `--revoke` 先吊销旧票再签发。

### GET /status — 运行时运维面（tcp/http 模式）

`GET http://<proxy>:9090/status` 返回：版本、uptime、upstream/engine/control 接线、
audit/trace 后端、**fallback license 摘要与验签结果**、最近 50 条裁决（含被审计采样
丢弃的 allow）。只读、不暴露任何密钥材料。排查"proxy 到底拦了什么、为什么"先看这里，
不用去翻 Kafka。

---

## 常见报错对照表

| 报错 | 含义 | 自查 |
|------|------|------|
| `license app_id mismatch: jwt='…', session='…'` | license 绑定的 app_id 与会话声明的不一致 | `_meta.app_id` 与签发时 `--app-id` 是否一致 |
| `license public key PEM unparseable: …` | 公钥文件解析失败（常见：结尾多空行——现在会自动 trim，仍失败说明内容不是 Ed25519 PEM） | `doctor` 校验文件；从运营台重新下载或用 `enroll` 落盘 |
| `license signature invalid` | Ed25519 验签失败 | 公钥与签发方不配对（rotate-key 后旧 PEM）；重新拉公钥 |
| `license expired` | license 过期 | 运营台重签 + `enroll --revoke` |
| `-32001 license_invalid` | 上面任一 license 类失败 | 看具体 reason 文本 |
| `-32004 not_in_allowlist` | 工具不在 license 白名单 | 运营台把工具加入 allowed_tools 重签，或让 Agent 只用白名单工具 |
| engine 401 `missing_token` | 引擎要求 evaluate token，而 proxy 未配置换取端点 | 设 `VIRBIUS_AUTH_CONTROL_URL`，或用 `VIRBIUS_BASE_URL` 一并推导 |
| `-32603 Upstream MCP server error (…)` | 上游 MCP Server 不可达/失败 | `doctor` 看 upstream 探测；检查 upstream_url/sse_path |

### stdio 模式（无 HTTP 面）

多数 MCP 客户端（Claude Desktop 等）把 proxy 当作 stdio sidecar 拉起，没有 HTTP 端口。
此时运维面走同一条 JSON-RPC 线：发

```json
{"jsonrpc":"2.0","id":1,"method":"virbius/status"}
```

返回 `{version, sessions_active, fallback_license{app_id,tenant_id,allowed_tools,verified}, recent_decisions[]}`
——等价于 `GET /status`（本地方法，绝不转发到上游）。`doctor` 在 stdio 模式下同样可用（它是
命令行自检）。

### 错误形状 & 把拦截结果传给 LLM

每个 Virbius 失败都是 JSON-RPC error，其 `message`（与 `data.reason`）是**人话**；
稳定机器码在 `data.reason_code`（以及数字 `error.code`）。`rule_id` 属内部，**不对外**。

```jsonc
"error": {
  "code": -32004,
  "message": "The tool call was blocked by policy: tool 'danger_exec' not in license allowlist.",
  "data": {
    "reason_code": "not_in_allowlist",
    "reason": "The tool call was blocked by policy: tool 'danger_exec' not in license allowlist.",
    "tool_name": "danger_exec", "trace_id": "…", "session_risk_score": 0, "http_analog": 403
  }
}
```

标准 JSON-RPC 错误（`-32603` 上游/传输、解析错误）保持简短固定文案——**绝不嵌入上游原始错误文本**。

OpenAI Agents SDK 的**默认** `failure_error_function` 会把 message 换成通用文案、埋掉原因。
传入**透传** formatter 即可原样揭示 proxy 的 message（不检测、不改写）：

```python
from virbius_mcp_python import virbius_tool_error
MCPServerSse({"url": proxy_sse_url}, failure_error_function=virbius_tool_error)
```

进程内 SDK 模式（`govern` / `VirbiusGuard.openai_tool`）自动且按**类型**揭示：拒绝抛 `VirbiusDenied`
（`PermissionError` 子类，携带 `reason_code`、结构化 `decision` 与可选 `audit_ref`，由 `from_decision`
构造）并被透出；其他异常保持通用文案，绝不把用户工具的内部错误文本交给模型。

文案本身由 `virbius_mcp_python.policy_blocked_message(reason)` **单点构造**（`VirbiusGuard.guard()`
使用）。"用事实性人话而非 SDK 默认文案"这一取舍有 A/B 实测支撑：同一 Agent、温度 0 下，SDK 默认的
"Please try again." 会诱导模型**重试策略拒绝**，Virbius 文案不会。复现：`scripts/test-error-wording-ab.sh`
（信息型，无 key 时 SKIP）。
