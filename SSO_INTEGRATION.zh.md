# VirbiusAgent 统一登录（OAuth2/OIDC）接入方案 — SSO INTEGRATION

| 项目 | 说明 |
|------|------|
| 文档版本 | v0.2 |
| 状态 | **设计定稿（未实现）** |
| 采纳路线 | **virbius-auth 作为 OIDC Broker**（virbius-control **零改动**）——唯一路线 |
| 关联 | [ARCHITECTURE.zh.md](ARCHITECTURE.zh.md) · [SECURITY.md](SECURITY.md) · [DEPLOYMENT.zh.md](DEPLOYMENT.zh.md) · [USAGE_GUIDE.zh.md](USAGE_GUIDE.zh.md) |
| 适用组件 | virbius-auth（新增 OIDC 认证来源）；control/engine 无改动 |

> **版本记录**：v0.1 为「control 端 OAuth2 client」草案；v0.2 定稿改为 **auth Broker 路线**并移除旧方案（历史见 Git）。
>
> **当前实现状态（重要）**：SSO **尚未实现**（仓内无任何 SSO 代码；virbius-auth 亦不含 SSO，当前仅为本地登录 + 自签 JWT）。当前唯一已实现的运营台登录是 **virbius-auth 本地登录**；生产部署应部署 virbius-auth（单副本）或将控制台限制在内网。本文档为实施前的设计定稿，未随任何代码交付。

---

## 1. 背景与目标

### 1.1 目标

- 将 **virbius-control 运营管理后台 UI**（`/ui`、`/ops`、`/ops.html`）接入公司 **OIDC** 统一登录（SSO）。
- SSO 由 **virbius-auth** 承接（作为 OIDC Relying Party / 身份代理），登录成功后仍由 auth **签发与今天完全相同的运营 JWT**。
- **保留** API Key 机制服务程序化调用，与 SSO 双轨并存。

### 1.2 保护范围

| 场景 | 认证方式 |
|------|---------|
| 运营人员浏览器访问后台 UI 及其调用的管理 API | SSO（OIDC Authorization Code + PKCE）→ auth 自签 JWT |
| 程序化 API 调用（服务间、外部集成） | 现有 API Key（`vrb_tk_`，不变） |

### 1.3 非目标

- 不改造 virbius-control（本路线下 control **零改动**）。
- 不改造 virbius-engine（其机器调用面已有 evaluate-token 调用方认证，见 DEPLOYMENT §10.2.1；SSO 仅涉运营台登录）。
- 不替换 API Key 机制。
- 不解决 auth 单副本 / 签名密钥落盘（既有待办，见 §10）。
- 一期单 IdP；不做多 IdP 联合。

---

## 2. 路线决策：为什么是 auth Broker

| 维度 | auth Broker（采纳） | control 端 OAuth2 client（v0.1，已弃） |
|------|--------------------|--------------------------------------|
| control 改动 | **零**（仍验 auth 的 JWKS/JWT，仍走 auth 的 `/login`+`/oauth/token`） | 引入 Spring Security + oauth2-client + SSO 桥接 Filter |
| 风险面 | 隔离在认证模块；Security 类风险不引入 | Spring Security 默认锁死风险落在**核心控制面** |
| 双轨 | auth 内天然（本地口令与 SSO 产出同一 JWT） | 需在 control 内做双轨仲裁 |
| auth 去留 | 保留并升级 | 需退役 |
| 复用 | code→token→JWT→JWKS 全链路复用 | 另起会话体系 |

**决策**：SSO 作为 auth 内的"新认证来源"，复用既有授权码→令牌→JWT→JWKS 全链路。

---

## 3. 核心命题：契约不变，control 零改动

auth↔control 契约（SSO 前后**完全不变**）：

| control 依赖（`OperatorJwtProperties` / `UiAuthController` / `JwksJwtVerifier`） | 值 |
|---|---|
| `loginUrl` | auth `GET /login` |
| `tokenUrl` | auth `POST /oauth/token` |
| `jwksUrl` | auth `GET /.well-known/jwks.json` |
| 令牌 | ES256 JWT，必需声明 `iss`/`aud`/`sub`/`exp`/`role`/`tenant_id`（`preferred_username`→label 可选） |

SSO 只改变 auth **内部"如何确认用户身份"**（本地口令或 OIDC），不改变上述任何端点、密钥与声明产物 ⇒ **control 一行不改**。

---

## 4. 认证流程（两跳重定向）

```
① 浏览器 → control /ui
   control → 302  auth /login/sso?return_uri=<control/ui/callback>&state=X     （return_uri 白名单校验，§8.2）
② auth → 302  IdP authorization_endpoint                                     （code+PKCE-S256+state+nonce，cookie 细节见 §8.2）
③ IdP 登录 → 302  auth /sso/callback?code=…&state=Y          ← 校验 state==Y（一次性）
④ auth 服务端换 token（client_secret + code_verifier）→ id_token 完整校验（§8）→ 声明映射（§7）
   → 发【自有 authCode】 → 302  <return_uri>?code=<authCode>&state=X（Cache-Control: no-store）
⑤ control /ui/callback → POST auth /oauth/token（authorization_code）
   → 运营 JWT（与今天完全一致）→ control 存 cookie
```

要点：IdP 的 `redirect_uri` 指向 **auth 的 `/sso/callback`**（auth 是 OIDC RP）；control 的 `/ui/callback` 与 `/oauth/token` **不变**。

---

## 5. 改造清单（全部在 virbius-auth 模块内）

| # | 项 | 说明 |
|---|---|---|
| 1 | 依赖 | `com.nimbusds:oauth2-oidc-sdk`（与现有 nimbus-jose-jwt 同源；**不引 Spring Security**） |
| 2 | 新端点 | `GET /login/sso`（发起 OIDC）、`GET /sso/callback`（RP 回调）、`GET /logout`（前端通道登出，§9） |
| 3 | 新服务 | `OidcClient`（discovery/token/JWKS；启动缓存 + kid 未命中刷新）、`OidcLoginService`（state/nonce/PKCE/id_token 校验/映射） |
| 4 | 复用钩子 | `LoginService` 新增 `issueCodeForUser(user, returnUri)`（抽出现有发码逻辑）；`TokenService`/`JwtIssuer`/`JwksController` **不动** |
| 5 | 配置 | `AuthProperties` 增 `sso` 子树 + **fail-closed 启动校验器**（§6） |
| 6 | 登录页 | 原口令表单 + 「企业登录」按钮；本地口令受 break-glass 策略约束（§6） |
| 7 | 审计 | 每次 SSO 登录记事件：`ts/sub/username/命中组/角色与租户决策/iss/结果`（瞬态用户也留痕） |

---

## 6. 配置设计与启动校验（fail-closed）

```yaml
virbius.auth:
  issuer: https://control.example.com            # auth 自签 JWT 的 iss（不变）
  return-uris: ["https://control.example.com/ui/callback"]
  sso:
    enabled: true
    issuer-uri: https://idp.corp.example.com/realms/virbius
    client-id: virbius-control
    client-secret: ${VIRBIUS_AUTH_SSO_CLIENT_SECRET}   # confidential client，仅服务端持有
    use-pkce: true                                 # S256 恒开
    scopes: [openid, profile, email]               # groups 是否需增补 scope 见 §13.1
    redirect-uri: https://auth.example.com/sso/callback   # 必须在 IdP 注册
    group-prefix: "virbius-"                       # 只认此前缀，防企业同名组误命中
    role-mapping:
      platform_admin: ["virbius-admins"]
      tenant_admin:   ["virbius-tenant-admins"]
    default-role: tenant_viewer
    default-tenant: default                        # 禁止 "*"（启动校验）
    require-group: "virbius-users"                 # 必填；空 → 拒绝启动
    allow-local-login: false                       # 本地口令默认仅 break-glass 账号
    break-glass-users: ["admin"]
```

**启动校验**（`sso.enabled=true` 时，任一失败**拒绝启动**）：
1. `require-group` 非空（防任意 IdP 用户放行，fail-closed）；
2. `default-tenant != "*"`（防跨租户越权；`*` 仅允许来自 `platform_admin` 的显式组映射）；
3. discovery 可达且文档 `issuer` 与 `issuer-uri` **逐字符相同**（RFC 8414 §4.3）；
4. `redirect-uri` / `group-prefix` / `role-mapping` 合法。

---

## 7. 声明映射与授权（仅瞬态，不做 JIT）

- **候选组** = id_token `groups` ∩ `group-prefix`（**大小写敏感、精确匹配**；仅取自 id_token——服务端已验签——不取 UserInfo/access_token）。
- **放行闸门**：必须命中 `require-group`，否则 403 页 + 审计事件。
- **角色**：命中 `role-mapping` 中的**最高角色**（`platform_admin` > `tenant_admin` > `tenant_viewer`）；未命中任何映射 → `default-role`。决策与命中的组写入审计日志。
- **租户**：`platform_admin` → `"*"`（**唯一**允许 `*` 的路径）；其余角色 → `default-tenant`（必须为具体租户）。租户级组派生（如 `virbius-tenant-<id>`）列为后续扩展。
- **用户对象**：每次登录按当前 claims 构造**瞬态** `OperatorUser(sub, preferred_username||email, role, tenantId)`，不落库——IdP 侧降权/离职**下次登录即生效**（无本地权限固化）。

---

## 8. 安全基线（安全评审定稿）

### 8.1 id_token 校验清单（对齐 OIDC Core §3.1.3.7）

JWKS 验签（ES256/RS256；**kid 未命中 → 刷新 JWKS 重试一次**，兼容 IdP 密钥轮换）→ `iss` **精确匹配**（尾斜杠问题在启动 discovery 校验中统一）→ `aud` 含 `client_id`（多 aud 时须 `azp == client_id`）→ `exp`/`nbf`（时钟偏移 ≤60s）→ `iat`（拒绝超过 5 分钟的 iat）→ `nonce` 与 cookie 匹配（一次性）。

### 8.2 会话与重定向

- state/nonce cookie：`HttpOnly` + `Secure` + **`SameSite=Lax`**（Strict 会断 IdP 回调）、一次性、TTL 5min。
- PKCE `S256` 恒开；confidential client（secret 仅服务端，永不出浏览器）。
- `return_uri` 在 `/login/sso` 入口**和**④发码前**双重**精确匹配白名单（复用 `AuthProperties.isReturnUriAllowed`，无通配）。
- discovery/JWKS 启动缓存 + kid-miss 刷新 + 定期 TTL（防每登录打 IdP、抗 DoS）。

### 8.3 日志与传输

- 含 code/token 的响应加 `Cache-Control: no-store`；日志不落 code/token/secret 与 token 明文 claims（PII 脱敏）。
- `/oauth/token` 加简单限速；token 样本（开发验证用）含 PII，不外传、测完即弃。

### 8.4 审计

- 每次 SSO 登录记审计事件（§5-7）；SSO 用户无本地账号行，取证以 control 审计 + auth 日志为准。
- 责任边界须写入运维文档：**MFA 由 IdP 承担；break-glass 本地账号不经过 IdP MFA**。

---

## 9. 登出

- **标准档**：control 登出 → `auth /logout` → 清 auth 自身 cookie → 302 IdP `end_session_endpoint`（带 `id_token_hint` + `post_logout_redirect_uri`）。
- **降级档**（IdP 不支持 end_session）：仅清 control cookie，**登记残余风险**并在运维文档加"共享电脑用后须关闭浏览器/登出 IdP"警示。

---

## 10. 残余风险登记

| # | 风险 | 处置 |
|---|---|---|
| 1 | 运营 JWT（60min TTL）**不可吊销**（无 denylist） | 接受；吊销名单列二期 |
| 2 | 共享电脑登出完整性依赖 IdP `end_session` 支持 | §9；不支持则降级+警示 |
| 3 | break-glass 本地账号**绕过 IdP MFA** | 设计如此（应急通道），文档写明责任边界 |
| 4 | auth **单副本** / 签名密钥落 PVC | 既有待办，SSO 不改变；扩副本需先外置密钥 |
| 5 | IdP 不可用 = 无法 SSO 登录 | break-glass 缓解；告警监控 IdP 可达性 |

---

## 11. 测试与验收（DoD）

- [ ] §8.1 清单逐项负例：篡改签名 / 错 `iss`（含尾斜杠变体）/ 错 `aud` / 过期 / 缺失或重放 `nonce` / 过老 `iat` → 全部拒绝
- [ ] state 错误/缺失/重放 → 400；cookie 属性回归（Lax）
- [ ] `return_uri` 未注册 → 入口与④两处均 400
- [ ] 启动校验 fail-closed：`require-group` 空 / `default-tenant="*"` / discovery issuer 不符 → **拒绝启动**
- [ ] 无 `require-group` 用户 → 403 + 审计事件；多组命中取最高角色且日志正确
- [ ] `sso.enabled=true` 时非 break-glass 账号本地登录被拒；`allow-local-login=true` 放开
- [ ] 登出走 `end_session_endpoint`（mock IdP）；不支持时降级警示生效
- [ ] 端到端：SSO 登录进运营台；**control 未改一行**；JWKS/声明与改造前逐字节一致
- CI 采用 mock OIDC provider（Keycloak 容器或轻量 mock）覆盖主路径与关键负例

---

## 12. 实施前置（go/no-go）

### 12.1 前置验证：客户 IdP 真实 token 样本（动工前必做）

> **关键认知**：`groups` 是否进 id_token 通常是 **per-client 的 mapper/optional-claims 配置**——他人应用的 token 样本、或"IdP 全局支持 groups"的口头确认**都不算数**；样本必须来自**为 virbius 注册的那个 client** 的一次真实登录。

步骤：
1. 客户 IT 注册 **dev client**（建议 public + PKCE、回调 `http://localhost:PORT/callback`）+ 一个**带目标组**的测试用户；
2. 本机用最小 RP 脚本（Python `authlib` / Node `openid-client`，~50 行）或用 Postman 完成一次真实授权码登录；
3. 解码 id_token 核对清单：
   - [ ] `groups` 是否存在？claim 实际名称（`groups`/`roles`/`entitlements`）？
   - [ ] 默认下发，还是需**额外 scope / client mapper**（Keycloak mapper、Entra optional claims、Okta claim policy）？
   - [ ] `issuer` 精确值（**尾斜杠**）；`jwks_uri`；`end_session_endpoint` 是否存在（决定 §9 档位）
   - [ ] 签名算法（ES256/RS256）；`aud`/`azp` 行为；`iat/exp` 时钟偏移
4. 样本含 PII：不贴 issue/群聊，测完即弃。

### 12.2 客户 IT 配合项（进度关键路径，尽早发起）

- dev client（及后续生产 client）注册：client_id、回调白名单（`auth /sso/callback`）、confidential + PKCE；
- 测试用户与组结构规划（`virbius-*` 前缀：`virbius-users`/`virbius-admins`/`virbius-tenant-admins`）；
- 网络可达：开发机/部署环境 → IdP（VPN/内网；注意 discovery/JWKS 出网代理）。

### 12.3 其它

- IdP 类型须为 **OIDC**（SAML 不适用本方案）。
- 实现选型默认 **nimbus-oidc-sdk**（不引 Spring Security）；如团队强烈偏好 `spring-boot-starter-oauth2-client`，须额外评审 FilterChain 配置面。

---

## 13. 实施排期与砍单红线

- **完整档**：约 **2–2.5 人周（单人）**；双人并行 ≈ **1.5 周**。
- **1 周 MVP（双人，砍单）**：延后项 = 完整安全矩阵（保留 4 个关键负例）、CI mock 基建、前端通道登出（降级档）、文档改版；上述延后项登记进 1.1。
- **红线（1 周也不许砍）**：id_token 校验（SDK）、state/nonce/PKCE（SDK）、`return_uri` 白名单、`require-group` fail-closed、`default-tenant` 禁 `*`、break-glass 限制。
- **不进 V1.0.0**：新功能 + 外部强依赖 + 安全敏感 + 需客户联调，建议作为 1.1 交付；客户 IT 前置申请（§12.2）可先行。

---

## 附录 A：环境变量速查（路线 A 新增）

> 仅列出可经**环境变量**覆盖的键；其余 §6 键（`use-pkce` 恒为开、`scopes`/`role-mapping`/`return-uris`）经配置文件或部署清单设置。

| 变量 | 必填（sso.enabled=true 时） | 说明 |
|---|---|---|
| `VIRBIUS_AUTH_SSO_ENABLED` | 是 | SSO 总开关；`false`=纯本地口令（现状） |
| `VIRBIUS_AUTH_SSO_ISSUER_URI` | 是 | IdP issuer 基址（含 `/.well-known/openid-configuration`） |
| `VIRBIUS_AUTH_SSO_CLIENT_ID` | 是 | IdP 客户端 ID |
| `VIRBIUS_AUTH_SSO_CLIENT_SECRET` | 是（confidential） | IdP 客户端密钥（仅服务端） |
| `VIRBIUS_AUTH_SSO_REDIRECT_URI` | 是 | RP 回调（`https://<auth>/sso/callback`，须在 IdP 注册） |
| `VIRBIUS_AUTH_SSO_GROUP_PREFIX` | 否 | 组前缀过滤，默认 `virbius-` |
| `VIRBIUS_AUTH_SSO_REQUIRE_GROUP` | 是 | 放行闸门组；空=拒绝启动 |
| `VIRBIUS_AUTH_SSO_DEFAULT_ROLE` | 否 | 无映射命中时的默认角色，默认 `tenant_viewer` |
| `VIRBIUS_AUTH_SSO_DEFAULT_TENANT` | 是 | 默认租户（具体租户，禁 `*`） |
| `VIRBIUS_AUTH_SSO_ALLOW_LOCAL_LOGIN` | 否 | 默认 `false`（本地口令仅 break-glass） |
| `VIRBIUS_AUTH_SSO_BREAK_GLASS_USERS` | 否 | break-glass 账号名单 |
