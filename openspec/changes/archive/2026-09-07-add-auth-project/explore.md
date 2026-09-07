# Explore: add-auth-project

> 在 worktree 内探索的结论写在这里，避免切换 IDE 窗口后丢失上下文。
> 探索结束后可执行 `/opsx-new`、`/opsx-ff` 或 `/opsx-continue`。

## 主题

为 VirbiusAgent 新增可独立部署的鉴权工程 `virbius-auth`，解决运营人员（人）与系统之间没有登录、运营台 `/ui` 裸奔的问题；对接现有 control 三角色 API Key 模型，并为后续公司 OIDC SSO 留双轨。

需求原文：新增鉴权工程/服务，接入当前系统；人与系统间身份认证可独立部署；对接 control API Key 角色模型；SSO 双轨预留。

## 问题与约束

### 现状（代码）

```
  运营人员浏览器
       |
       v
  /ui  (OpsUiController → /ui/index.html)
       ApiKeyAuthFilter.shouldNotFilter: /ui 跳过 → 无登录

  管理 API /api/v1/admin|edge|gateway|tenants
       |
       v
  ApiKeyAuthFilter
       +-- virbius.security.api-key.enabled=false (默认)
       |     → DEV_PRINCIPAL = platform_admin / tenant *
       +-- enabled=true
             Bearer vrb_tk_ 或 X-Virbius-Api-Key
             SHA-256 → tb_tenant_api_credential
             ApiKeyPrincipal(credentialId, tenantId, role, label)
             ApiRole: TENANT_VIEWER < TENANT_ADMIN < PLATFORM_ADMIN
             ApiKeyRoutePolicy 按 method+path 卡角色和租户

  程序化调用 (engine→control / 外部) → 同一套 API Key

  Agent 运行时 → control LicenseSigner 签发的 Ed25519 License JWT
                 (给 virbius-core / MCP proxy，不是人)

  virbius-engine → 无认证
```

- 父 POM 模块：`virbius-control` / `engine` / `policy` / `compiler` / `groovy-l3`。无 auth 工程。
- control：Spring Boot 3.2、Java 17、dev SQLite + Flyway；有 `tb_tenant_api_credential`，无用户表。
- 运营台静态资源无登录、无 API Key 注入。
- `SSO_INTEGRATION.zh.md` 为草案：SSO **做进 control**（OAuth2 client），**不**新建 BFF/网关，engine 本期不动。与「新建可独立部署鉴权工程」冲突，**不按该草案落地**。

### 约束

- 人认证与 control 业务进程可分开部署。
- 机器/程序化调用继续用 `vrb_tk_`，不替换。
- 下游鉴权复用 `ApiKeyPrincipal` + `ApiKeyRoutePolicy`，避免业务 Controller 改一套 RBAC。
- 公司 OIDC 本期不实现，但 token 形状应能让 auth 日后当 IdP 经纪。
- License JWT、engine 认证本期不动。

## 方案对比

| 方案 | 含义 | 取舍 |
|------|------|------|
| 独立进程 `virbius-auth` | 本仓库新 Spring Boot 模块，独立端口/库 | 满足独立部署；control 只验 JWT。**已选** |
| SSO 做进 control | 按 `SSO_INTEGRATION.zh.md` | 无新工程，违背需求 |
| 完整 OIDC AS | auth 一上来做标准 AS | 过重；SSO 未到 |
| 仅共享库 | 无独立进程 | 不可独立部署 |
| JWT + JWKS 双轨 | `vrb_tk_` vs JWT，按 Bearer 前缀分流 | 与现有头兼容、无共享域名。**已选** |
| 不透明 token + introspection | control 每请求打 auth | 撤销强，耦合和延迟高 |
| Cookie / BFF | 跨 origin 要共享站点或反代 | 和独立部署打架 |
| control 自建会话 | auth 只认人，control 发 JSESSIONID | 人会话绑在 control，拆分不干净 |
| auth 拥有 role/tenant claims | 用户表写入 JWT | control 继续无用户表。**已选** |
| control 再映射 | JWT 只有 sub | 两处数据源 |
| 登录后换 API Key | 人仍是 `vrb_tk_` | 没有真正的人身份 |
| auth 托管 /login 再回 /ui | 密码不进 control | **已选** |
| control 表单 + CORS | 简单但跨域 | 次选 |
| 迷你 OIDC AS | authorize + PKCE | 可为 SSO 铺路，v1 偏重 |

## 结论与建议 change 范围

### 已确认（探索）

1. **形态**：本仓库新增可独立部署进程 `virbius-auth`（父 POM 新模块）。
2. **人机路径**：本地用户名密码；auth 托管 `/login`；成功后带回 control `/ui`；control 保护 `/ui` 与管理 API。
3. **信任**：auth 发 JWT；control JWKS 本地验签；`Authorization: Bearer` 双轨（`vrb_tk_` → 现有 API Key；否则 JWT）。
4. **权威**：auth 用户表拥有 `role` + `tenant_id`，写入 JWT claims；control 映射为 `ApiKeyPrincipal` 后走原 `ApiKeyRoutePolicy`。
5. **开户**：环境变量/启动参数引导首位 `platform_admin`；之后仅 admin 建用户。无开放注册。
6. **非目标**：不改造 `virbius-engine`；不实现公司 OIDC；不改 License JWT。

### 建议默认（探索未单独拍板，立项可当默认）

- 换票用 **authorization code**（避免 token 进 URL fragment / Referer）。
- control 用薄 JWT Filter 验 JWKS，**不为双轨引入 Spring Security**（避免 `SecurityAutoConfiguration` 锁全站；与 SSO 草案里的风险一致）。
- auth 自有库（dev SQLite + Flyway），与 control 库分离。
- 密码哈希用库内已有/标准算法（BCrypt 或 Argon2）；短生命周期 access JWT；撤销靠过期，v1 不做黑名单。
- 日后公司 SSO：只在 auth 加 IdP，对外仍发同一套 JWT claims。

### 建议落点（立项后）

```
  运营人员
     |
     |  未登录访问 /ui
     v
  virbius-control --302--> virbius-auth /login
     ^                         |
     |  ?code= 回调换 JWT       |  用户名密码
     +-------------------------+
     |
     |  /ui 持有 JWT
     |  API: Bearer <jwt> 或 Bearer vrb_tk_...
     v
  control 双轨 Filter --> ApiKeyPrincipal --> 原 RoutePolicy

  engine / License JWT / 公司 IdP = 本期不动
```

- **新模块** `virbius-auth`：用户存储、bootstrap、登录页、code 换票、JWKS、admin 用户 CRUD（JWT `platform_admin`）。
- **改** `virbius-control`：`ApiKeyAuthFilter`（或并列 Filter）接受 JWT；`/ui` 未认证跳转 auth `/login`；可选 `GET /api/v1/auth/me` 回当前 principal。
- **不改** engine、core License 校验、已有 API Key 签发/吊销语义。

## 待定问题

- JWT 具体 claim 名与时钟偏差（建议对齐草案：`role` / `tenant_id` / `preferred_username`，skew 60s）——立项 design 写死即可。
- access token TTL、是否要 refresh token。
- auth 与 control 的 CORS / `return_uri` 白名单（防开放重定向）。
- 首位 bootstrap 用户的环境变量命名与「仅空库执行一次」。
- 运营台前端如何持有 JWT（memory / sessionStorage）及登出时清票 + 调 auth。
- 本 worktree 无 `.cursor/dev-worktree-start.sh`，dev 服务未拉起；实现阶段需补 hook 或手动起 `virbius-auth` + `virbius-control`。
