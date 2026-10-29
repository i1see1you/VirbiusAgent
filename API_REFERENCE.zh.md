# VirbiusAgent API 参考（v1.0.3 稳定契约）

[English](API_REFERENCE.md)

本文档是 V1.0.0 的**对外 API 稳定性契约**：哪些接口属于公共契约、认证方式、错误信封、状态码语义与版本策略。字段级 schema 以代码与 [PROTOCOL.md](PROTOCOL.md) 为准，本文只承诺"不会在不兼容变更中悄悄破坏"的部分。

---

## 1. 稳定性分层

| 层级 | 范围 | 稳定性承诺 |
|---|---|---|
| **对外稳定（GA）** | engine `/v1/evaluate*`、`/v1/memory/check`、`/v1/challenge/verify`；control `/api/v1/admin/**`、`/api/v1/challenges/**`；auth `/login`、`/oauth/token`、`/.well-known/jwks.json` | 同一大版本内不破坏；破坏性变更必须走新版本路径并提前一个 minor 版本宣布弃用 |
| **组件间契约** | control `/api/v1/edge/**`、`/api/v1/gateway/**`、`/api/v1/tenants/**`（组件/SDK 用） | 随版本同步升级；同一 release 内各组件版本必须一致 |
| **内部/运维（不保证兼容）** | control `/api/v1/internal/**`、engine `/api/internal/**`（如 falco-alert）、engine `/admin/**`（health/ready/policy-version/cache-reload） | 可随时变更，第三方禁止依赖 |

## 2. 认证

| 接口面 | 机制 | 凭据 |
|---|---|---|
| control `/api/v1/admin/**`（自动化/租户 API） | API Key | 请求头 `X-Virbius-Api-Key: <key>`（运营台签发，HMAC 存储） |
| control `/api/v1/challenges/**`、运营台页面 API | 操作员 JWT | `Authorization: Bearer <token>`（经 auth `/login` + `/oauth/token` 获取） |
| engine `/v1/evaluate*` | evaluate 短 token | `Authorization: Bearer <vct_...>`，`POST /api/v1/admin/evaluate-token/*` 或 proxy 每会话自动换取；TTL 默认 900s |
| engine `/v1/memory/check`、`/v1/challenge/verify` | 无（内网接口） | 依赖网络层隔离 |
| auth `/login`、`/oauth/token` | 表单 | 用户名/密码 → 授权码 → token |

401/403 一律 **fail-closed**；限流返回 429（`Retry-After` 语义见 §4）。

## 3. 错误信封（control 全站统一）

control 的**所有错误响应**（含 `/api/v1/challenges/**`）统一为 `ApiResult`，且**永远非空**（1.0.0 修复了 500 空 body 导致前端 `Unexpected end of JSON input` 的问题）：

```json
{ "code": 500, "message": "challenge service unavailable", "timestamp": "2026-10-19T03:00:00Z" }
```

- `code`：业务码，**与 HTTP 状态码一致**（`GlobalExceptionHandler` 按 `HttpStatus.resolve(code)` 映射；仅未知码回落 400）。
- 成功响应：admin 系路由为 `{"code":0,"message":"success","data":...}`；**例外**——`/api/v1/challenges/**` 的成功体是引擎/审批库的原始 JSON（数组或对象），不套信封。客户端应先判 HTTP 状态，再按上述规则解析。

## 4. 状态码语义

| 状态码 | 含义 |
|---|---|
| 400 | 参数/状态错误（如挑战不存在、非 pending） |
| 401 / 403 | 未认证 / 无权限（fail-closed） |
| 404 | 资源不存在 |
| 409 / 423 | 状态冲突 / 锁定（如镜像黑名单重建中） |
| 429 | 限流；重试遵守 `Retry-After` |
| 500 | 服务内部错误 |
| 502 / 503 | 上游依赖故障（engine/DB/Redis/Kafka），客户端可重试 |

## 5. 主要路由分组

### 5.1 virbius-engine（8082，决策面）

| 路由 | 用途 | 层级 |
|---|---|---|
| `POST /v1/evaluate` | 工具调用/内容风险判定（唯一权威判定路径） | 对外稳定 |
| `POST /v1/evaluate/tool-result` | 工具返回结果的输出安全审查 | 对外稳定 |
| `POST /v1/memory/check` | 记忆投毒检查 | 对外稳定（内网部署） |
| `GET /v1/challenges` / `GET/POST /v1/challenge/{id}/{status,approve,reject}` | 人工审批挑战 | 组件间契约（control 代理） |
| `POST /v1/challenge/verify` | 审批 token 消费校验（一次性） | 对外稳定 |
| `POST /v1/simulate/prompt` | 提示词规则模拟 | 组件间契约 |
| `GET /admin/health` / `GET /admin/ready` | 存活/就绪探针 | 内部/运维 |
| `GET /admin/policy-version`、`POST /admin/cache/reload` | 缓存运维 | 内部/运维 |

### 5.2 virbius-control（8080，管理面）

| 路由 | 用途 | 层级 |
|---|---|---|
| `GET /api/v1/health` / `GET /api/v1/ready` | 存活/就绪 | 内部/运维 |
| `/api/v1/admin/tenants/**` | 租户、规则、license、名单、审计、告警、发布等全部管理 API | 对外稳定 |
| `/api/v1/admin/evaluate-token/**` | evaluate 短 token 签发/吊销 | 对外稳定 |
| `/api/v1/admin/platform/api-credentials/**` | 平台级 API Key 管理 | 对外稳定 |
| `/api/v1/challenges/**` | 运营台审批队列（代理 engine + SQL 历史） | 对外稳定 |
| `/api/v1/edge/**`、`/api/v1/gateway/**` | edge manifest 下发、网关产物 | 组件间契约 |
| `/api/v1/internal/**` | 内部回写（审计/集成） | 内部/运维 |
| `/api/v1/auth/**` | 登录态辅助 | 内部/运维 |

### 5.3 virbius-auth（8083，登录）

| 路由 | 用途 | 层级 |
|---|---|---|
| `GET/POST /login` | 登录页/登录提交（授权码流程） | 对外稳定 |
| `POST /oauth/token` | 授权码换 operator JWT | 对外稳定 |
| `GET /.well-known/jwks.json` | JWKS（control 验签公钥来源） | 对外稳定 |
| `GET /api/v1/health` | 存活 | 内部/运维 |

组件清单备注：virbius-mcp-proxy（9090，接入面）走 MCP JSON-RPC 协议（stdio/SSE/TCP），契约见 [PROTOCOL.md](PROTOCOL.md)，不属于 REST 面。

## 6. 版本策略

- 路径前缀即版本：`/v1/`（engine）、`/api/v1/`（control/auth）。
- 同一大版本内只做**向后兼容**的演进（新增字段/新增路由）；移除字段、改语义、改错误信封属破坏性变更，必须 `/v2` 并提前一个 minor 版本宣布弃用。
- 弃用公告位置：`CHANGELOG.md` 对应版本的 `Deprecated` 小节。

## 7. 兼容性注意事项（1.0.0 已知边界）

- `/v1/evaluate` 的限流是**每副本**计数（多副本时全局配额 = 配置值 × 副本数）。
- 引擎 `/admin/ready` 未鉴权，仅应在集群/内网暴露（Helm 的 Ingress 只路由 `/v1`）。
- control `/api/v1/challenges/**` 的错误信封在 1.0.0 从 `{error}` 统一为 `ApiResult`——该路由此前未对外发布过，无存量调用方。
