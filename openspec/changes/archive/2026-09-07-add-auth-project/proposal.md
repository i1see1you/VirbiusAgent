## Why

运营台 `/ui` 与管理 API 没有「人」的登录：默认关掉 API Key 时全员等同 `platform_admin`，打开后只有 `vrb_tk_` 机器凭证。运营人员无法以独立于 control 的身份接入，也无法为后续公司 SSO 留出双轨。现在补一个可独立部署的鉴权工程，把人认证从业务进程里拆出来，并复用已有三角色模型。

## What Changes

- 本仓库新增可独立部署的 `virbius-auth` 进程：本地运营用户（用户名密码）、首位 `platform_admin` 引导、此后仅 admin 建用户。
- auth 托管登录页；未登录访问运营台时跳转到该页，成功后用 authorization code 回到 `/ui` 并换得人用 JWT。
- JWT 由 auth 用 JWKS 可验证的密钥签发；claims 携带 `role` 与 `tenant_id`（权威在 auth 用户表）。
- `virbius-control` 对人用 JWT、对机器用现有 `vrb_tk_` 双轨验收；映射为现有 `ApiKeyPrincipal` 后走原路径鉴权。默认仍可关认证（dev）。
- 保护 `/ui`：无有效人身份则跳转登录。密码不经过 control。
- **不改** `virbius-engine`、License JWT、API Key 签发/吊销语义。本期 **不实现** 公司 OIDC；token 形状预留给日后 auth 做 IdP 经纪。
- **不按** `SSO_INTEGRATION.zh.md` 把 SSO 做进 control（与独立部署冲突）。

## Capabilities

### New Capabilities

- `operator-identity`: 独立鉴权服务上的运营用户、引导开户、登录换票、JWKS 与用户管理。
- `control-request-auth`: control 对管理 API 与 `/ui` 的请求认证：人 JWT 与机器 API Key 双轨，以及未登录跳转。

### Modified Capabilities

- （无。仓库尚无 `openspec/specs/` 基线。）

## Impact

- **新增** Maven 模块 `virbius-auth`（父 POM）、独立数据目录与 JWKS 端点。
- **修改** `virbius-control` 认证过滤器与运营台入口（`/ui` 跳转、Bearer 分流）；业务 Controller / `ApiKeyRoutePolicy` 角色规则保持原语义。
- **调用方**：浏览器运营人员走登录 + JWT；engine、edge、外部集成继续 `vrb_tk_`。
- **依赖**：auth 与 control 运行时分离；control 需配置 auth 的 issuer / JWKS URL 与登录回跳白名单。不为双轨给 control 引入 Spring Security。
- **明确不碰**：`virbius-engine`、License 签发与核验、公司 IdP。
