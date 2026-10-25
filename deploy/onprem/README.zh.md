# VirbiusAgent 客户现场部署手册（单机 / 三机 · 离线 · 无外置 K8s/有 K8s 均适用）

[English](README.md)

适用版本：**v1.0.0**。
本手册只部署 VirbiusAgent 自身组件；**MySQL / Kafka / Redis / vLLM 由客户统一环境提供**。
随离线包 `virbius-onprem-v1.0.0.tar.gz` 分发（即 `deploy/onprem/` 目录）。
默认按**三机**编写；**单机**（全部组件同机、客户 infra 外置）见 §0.1、§1.2 与 §5.1。

---

## 0. 前提清单（进场前与客户确认）

| 项 | 要求 | 由谁提供 |
|---|---|---|
| MySQL | 8.0+（或 MariaDB 10.6+），建库 `virbius` + 账号密码；**1.0.0 全部迁移已在 MySQL 8.0 / MariaDB 11 双方言实测** | 客户 |
| Kafka | 3.x，SASL_PLAINTEXT + SCRAM-SHA-256/512 账号（机制不同改 `.env` 的 `KAFKA_SASL_MECHANISM`）；客户仅有 PLAINTEXT listener 时也可用（三份 compose 的 `KAFKA_SECURITY_PROTOCOL` 改 `PLAINTEXT`，SASL 账号填占位值）。topic `virbius-audit-events`、`virbius-trace-events` 预建（分区 ≥3）或授权我方自建 | 客户 |
| Redis | 7.x。**standalone（或主从+固定入口；不支持 Cluster / Sentinel 寻址）**；必须设密码；**不支持 TLS**（`rediss://` 不可用）；**必须开 AOF（或至少 RDB）且禁用数据淘汰类 `maxmemory-policy`**——审计链头/会话状态仅存 Redis；1.0.2 起链头丢失可从 DB 自愈续链（下一条审计事件触发），持久化仍是硬要求，避免自愈前窗口期丢事件 | 客户 |
| vLLM | 可选（模型选项B）；OpenAI 兼容端点 | 客户 |
| 机器 | Docker 20.10+（含 compose v2）。三机：彼此网络互通、每台磁盘 ≥20G；单机：磁盘 ≥40G（镜像/日志/导出同机） | 客户 |
| 镜像 | 现场无法拉外网仓库 → 用 §3 离线包 | 我方 |
| 模型 GGUF | ~1.5GB，经 hf-mirror/ModelScope 预下载（§4 选项A） | 我方 |
| 模型 safetensors | 选项B（客户 vLLM）需 HF 格式权重，ModelScope 同仓库（§4） | 我方/客户 |

### 0.1 单机（客户外置 infra）进场前准备

**我方提前准备**（有网机器）：

1. 离线包：`./deploy/scripts/offline-bundle.sh --tag v1.0.0`（单机走客户 vLLM 时**不要加 `--with-ollama`**），拷贝 `dist/offline/` 三个产物。
2. vLLM 权重：按 §4 选项B 下载 7 个 safetensors 文件交给客户，并**提前约好**加载方式与 `--served-model-name`（`.env` 的 `VIRBIUS_PROMPT_LLM_MODEL` 必须与之一致）。
3. 许可：确认 `license.lic` 的签发/上传方式（首次登录运营台后在 Licenses 页签发并下载）。

**现场 `.env` 必核项**（`cp .env.example .env` 后逐项确认）：

| 变量 | 注意 |
|---|---|
| `VIRBIUS_JDBC_URL` | 按客户 MySQL TLS 策略选 `sslMode`；`VERIFY_IDENTITY` 需放 CA 并解开 `node-a-control.yml` certs 卷注释（§6） |
| `KAFKA_SASL_*` | 四件套齐全；机制非 SCRAM-SHA-256 时改 `KAFKA_SASL_MECHANISM`（仅支持 SCRAM-SHA-256/512，不支持 PLAIN/SASL_SSL） |
| `REDIS_PASSWORD` | 同时确认客户已开 AOF/RDB 且 `maxmemory-policy` 不会淘汰数据（审计链头仅存 Redis，丢失不可自愈） |
| `VIRBIUS_LICENSE_MASTER_KEY` / `VIRBIUS_API_KEY_PEPPER` | 均 ≥32 字符，否则 control 拒绝启动 |
| `CONTROL_PUBLIC_URL` | 必须等于浏览器实际访问地址，否则登录回跳 401 |
| `PROMPT_LLM_BASE_URL` / `VIRBIUS_PROMPT_LLM_MODEL` | 选项B 指向客户 vLLM，模型名与 served-name 一致 |
| `NODE_A` / `NODE_B` / `NODE_C` | 单机三个值都填**本机可达 IP**（见 §1.2，不能填 `127.0.0.1`） |

---

## 1. 角色分配（三机 / 单机）

### 1.1 三机（默认）

| 机器 | 角色 | 组件 | 对外端口 | 资源建议 |
|---|---|---|---|---|
| A（`NODE_A`） | 管理面 | `virbius-control`（8080）+ `virbius-auth`（8083） | 8080（运营台，建议仅办公网）/8083（内部） | 4C8G |
| B（`NODE_B`） | 决策面 | `virbius-engine`（8082），可 `--scale` 多副本；模型选项A时 + Ollama（11434） | 8082 | 8C16G；选项A加 8G 内存（模型常驻） |
| C（`NODE_C`） | 接入面 | `virbius-mcp-proxy`（9090） | 9090（MCP 客户端入口） | 2C4G |

副本策略（1.0.0 能力边界）：

- **engine 可多副本**：横向扩容请加主机，每台一个 engine（本 compose 固定发布 8082，单机 `--scale` 会端口冲突）。每个副本的 `VIRBIUS_INSTANCE_ID` 必须唯一（compose 容器 hostname 天然唯一；手动 `docker run` 需显式设置）。多于一个 engine 时前置 LB，control 与 proxy 的 `VIRBIUS_ENGINE_URL` 指向 LB。
- **control / auth / proxy 单副本**：auth 的 JWT 签名密钥落在本机卷（扩副本会互踢登录）；control 多副本需共享 NFS（K8s 路径见 DEPLOYMENT.md §8.5，裸机本期不做）；proxy 是 MCP 客户端的单 TCP 入口。

### 1.2 单机（全部组件同机）

A/B/C 三个角色合并到一台机器：使用**同一份 `.env`**，三个 compose 文件同时运行；端口互不冲突。

| 组件 | 监听端口 |
|---|---|
| virbius-control | 8080 |
| virbius-auth | 8083 |
| virbius-engine | 8082 |
| virbius-mcp-proxy | 9090 |

- **资源建议**：三机建议值合计约 14C28G；单机共享 OS 后可 **12C24G 推荐、8C16G 最低**（选项B 不含本机 Ollama，选项A 另加 8G 内存）；磁盘 ≥40G。
- **`.env` 的 `NODE_A` / `NODE_B` / `NODE_C` 三个值都填本机 IP**，且必须是**容器可达的地址**（容器内 `127.0.0.1` 指向容器自身，不能用）。跨机场景下 control/engine/proxy 通过 `${NODE_*}:端口` 互联，单机同理。
- **网络矩阵塌缩**：§2 中的 A↔B、A↔C、B↔C 全部变成本机互通，无需额外防火墙放通；实际只需「单机 → 客户 MySQL/Kafka/Redis/vLLM」出站，以及「办公网 → 8080」「MCP 客户端 → 9090」。
- **限制不变**：control/auth/proxy 保持单副本；engine 不使用 `--scale`（固定发布 8082 会端口冲突），需要扩容时改为多机或前置 LB。
- 部署命令见 §5.1。

## 2. 组件间网络与防火墙矩阵

| 源 → 目的 | 端口 | 用途 | 必须开通 |
|---|---|---|---|
| A → 客户 MySQL | 3306 | control/auth 持久化（`sslMode` 按客户策略，见 `.env`） | ✅ |
| A → 客户 Kafka | 9092 | 审计/链路事件、规则下发回执 | ✅ |
| A → 客户 Redis | 6379 | 规则快照发布、issuer 发布、限流 | ✅ |
| A → B | 8082 | control 代理审批队列到 engine | ✅ |
| B → 客户 Kafka / Redis | 9092/6379 | 审计消费、会话状态、规则缓存 | ✅ |
| B → A | 8080 | engine 回调 control | ✅ |
| B → 本机 Ollama（选项A）或客户 vLLM（选项B） | 11434 / 8000 | 提示词审查模型 | 按选项 |
| C → B | 8082 | 工具调用决策 | ✅ |
| C → A | 8080 | 会话 token 换取 | ✅ |
| C → 客户 Redis / Kafka | 6379/9092 | 快照订阅兜底、审计发送 | ✅ |
| C → 客户 MCP Server | 按 `.env` | 被代理上游 | ✅ |
| 运维办公网 → A | 443/8080 | 运营台（§6 TLS） | ✅ |
| MCP 客户端 → C | 9090 | 业务入口 | ✅ |

东西向组件流量默认明文，依赖客户内网隔离（与 K8s 路径 §9.3 同一口径：Kafka SASL 认证 + 网络层隔离；若客户用 PLAINTEXT listener，则须以 Kafka 侧防火墙/安全组白名单 A/B/C 三 IP 补偿）。

> **单机部署**：上表中 A↔B、A↔C、B↔C 行全部视为本机互通（无需放通）；只保留「单机 → 客户 MySQL/Kafka/Redis/vLLM」与「办公网 → 8080」「MCP 客户端 → 9090」。

## 3. 镜像离线交付（tar 包）

**构建侧**（我方有网机器，仓库根目录）：

```bash
./deploy/scripts/offline-bundle.sh --tag v1.0.0 --with-ollama
# 产物 dist/offline/：
#   virbius-images-v1.0.0.tar.gz   engine/control/auth/mcp-proxy (+ollama)
#   virbius-onprem-v1.0.0.tar.gz   本目录全部资产 + .env 样例
#   SHA256SUMS-v1.0.0.txt
```

**现场侧**（三台机器各执行一次，或按角色只 load 所需镜像）：

```bash
sha256sum -c SHA256SUMS-v1.0.0.txt          # 先校验
gunzip -c virbius-images-v1.0.0.tar.gz | docker load
tar -xzf virbius-onprem-v1.0.0.tar.gz       # 得到 onprem/ 目录
docker images | grep v1.0.0                 # 确认 5 个镜像就位
```

## 4. 提示词审查模型（二选一）

**选项A（推荐，已验证路径）：B 机旁挂 Ollama。** GGUF 预下载（有网机器执行，再拷贝）：

```bash
# ModelScope（国内推荐）
curl -L -o virbiusguard-v2.0-f16.gguf \
  "https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/gguf/virbiusguard-v2.0-f16.gguf"
# 或 hf-mirror
curl -L -o virbiusguard-v2.0-f16.gguf \
  "https://hf-mirror.com/i1see1you/VirbiusGuard/resolve/main/gguf/virbiusguard-v2.0-f16.gguf"
```

放到 B 机 `onprem/models/virbiusguard-v2.0-f16.gguf`（文件名须与 `models/Modelfile` 一致），启动带 `--profile llm-local`，`ollama-init` 自动导入为 `virbiusguard`（幂等）。

**选项B：客户 vLLM 直服。** vLLM 不支持 GGUF，需 safetensors/HF 格式权重——**ModelScope 上已提供**（与 GGUF 同仓库）：

```bash
# 有网机器执行，得到完整 HF 目录后拷贝给客户的 vLLM 机器
mkdir -p virbiusguard-hf && cd virbiusguard-hf
for f in model-00001-of-00002.safetensors model-00002-of-00002.safetensors \
         model.safetensors.index.json config.json generation_config.json \
         tokenizer.json tokenizer_config.json; do
  curl -L -o "$f" "https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/$f"
done
# 客户 vLLM 侧加载（示例）：vllm serve <该目录> --served-model-name virbiusguard
```

然后 `.env` 改：

```bash
PROMPT_LLM_BASE_URL=http://<客户vLLM地址>:8000   # OpenAI 兼容，不含 /v1 之外的路径
VIRBIUS_PROMPT_LLM_MODEL=i1see1you/virbiusguard:v2.0            # 与客户部署的模型名一致
```

冒烟验证：`curl $PROMPT_LLM_BASE_URL/v1/chat/completions` 返回 `choices`；再在运营台发一条 `runtime=prompt` 规则触发一次语义检测。

## 5. 部署

先准备 `.env`（单机/三机通用）：

```bash
cd onprem && cp .env.example .env && vi .env   # 按现场填写（同机/多机共用同一份）
```

### 5.1 单机（按顺序执行三个 compose）

启动顺序：客户 infra 就绪 → control/auth → engine → proxy。engine 在 control 发布 issuer 前 `/admin/ready` 可能短暂返回 503，属正常，control 就绪后自动转 ready（30s 内）。

```bash
docker compose --env-file .env -f node-a-control.yml up -d
docker compose --env-file .env -f node-b-engine.yml up -d          # 选项B；选项A加 --profile llm-local
docker compose --env-file .env -f node-c-proxy.yml up -d

curl -sf http://localhost:8083/api/v1/health             # auth（直接访问本机发布端口）
curl -sf http://localhost:8080/api/v1/health             # control
curl -s  http://localhost:8082/admin/ready               # engine，应 200
```

> 注意：容器之间用 `.env` 的 `NODE_A/B/C` 互联，这三个值要填本机可达 IP；上面 `curl localhost` 只是从宿主侧验证发布端口。三个 compose 的 `name` 不同（`virbius-onprem-a/b/c`），可共存，清理时需分别 `down`。

之后按 §6–§10 做 TLS、升级回滚、备份、验收与排障。

### 5.2 三机

启动顺序：客户 infra 就绪 → A 机 → B 机 → C 机。engine 在 control 发布 issuer 前 `/admin/ready` 可能短暂返回 503，属正常，control 就绪后自动转 ready（30s 内）。

**A 机**：

```bash
docker compose --env-file .env -f node-a-control.yml up -d
curl -sf http://localhost:8083/api/v1/health             # auth 就绪
curl -sf http://localhost:8080/api/v1/health           # control 就绪
# 浏览器打开 CONTROL_PUBLIC_URL，用 AUTH_BOOTSTRAP_USERNAME/PASSWORD 登录
```

**签发 license + 导出公钥（A 机就绪后、C 机启动前）**：

```bash
# 1) 登录运营台拿会话 cookie 后（或脚本登录），签发——
#    请求体字段必须 snake_case，camelCase 会 409 "failed to sign license"
curl -X POST http://localhost:8080/api/v1/admin/tenants/default/licenses/issue \
  -H 'Content-Type: application/json' -b <会话cookie> \
  -d '{"app_id":"mcp-demo","agent_name":"onprem-proxy","allowed_tools":["read_file"],"risk_quota":100,"tool_rate_limit":50,"expiry_seconds":86400}'
# 2) 把返回 data.jwt 单独写入文件，拷到 C 机 onprem/license.lic（FLAT 文件，仅 JWT 本体）
# 3) 拉 license 公钥，拷到 C 机 onprem/license_pub.pem（与 license.lic 同目录）
curl -b <会话cookie> http://localhost:8080/api/v1/admin/tenants/default/licenses/public-key
#    → data.public_key_pem
```

**B 机**（模型选项A：先把 GGUF 放到 `onprem/models/` 再起，见 §4）：

```bash
docker compose --env-file .env -f node-b-engine.yml --profile llm-local up -d   # 选项A
docker compose --env-file .env -f node-b-engine.yml up -d                       # 选项B
curl -sf http://localhost:8082/admin/health
curl -s  http://localhost:8082/admin/ready    # 应 200；not-ready 时看 body 的 redis/cache_subscriber/rule_seeder 三项定位
docker exec onprem-ollama-1 ollama list       # 选项A：应见 i1see1you/virbiusguard:v2.0
```

**C 机**（先放 license.lic 与 license_pub.pem，见 §5.2 A 机步骤）：

```bash
docker compose --env-file .env -f node-c-proxy.yml up -d
# 就绪自检：license verify 应为 ok；未放 license_pub.pem 时恒 failed
docker exec onprem-virbius-mcp-proxy-1 virbius-mcp-proxy doctor
# MCP 调用一次后，决策健康检查：recent_decisions 应为 engine:allow；
# 出现 fail_open:engine_unavailable → 查 proxy→A:8080 / proxy→B:8082 连通
curl -s http://localhost:9090/status | head -c 400
```

## 6. TLS（生产必做）

- **运营台/管理 API（A 机）**：前置 nginx 终结 HTTPS，客户内网 CA 签发证书：

  ```nginx
  server {
      listen 443 ssl;
      server_name virbius-console.customer.internal;
      ssl_certificate     /etc/nginx/certs/console.crt;
      ssl_certificate_key /etc/nginx/certs/console.key;
      location /login { proxy_pass http://127.0.0.1:8083; proxy_set_header Host $host; }
      location /oauth/ { proxy_pass http://127.0.0.1:8083; proxy_set_header Host $host; }
      location / { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  }
  ```

  配置后把 `.env` 的 `CONTROL_PUBLIC_URL` 改成 `https://virbius-console.customer.internal`，并把 A 机 compose 的 `VIRBIUS_AUTH_LOGIN_URL` 改回 `${CONTROL_PUBLIC_URL}/login` 后重启（登录页/auth API 路由依赖上面两条 location；**不加这两条 location 登录必 400**）。
- **MCP 入口（C 机 9090）**：TCP 协议。跨网段时由客户前置 LB/nginx `stream` 块做 TCP 直通或 TLS 终结；同内网可直连明文。
- **MySQL**：用 `sslMode=VERIFY_IDENTITY` 时，把 `.env` 换成注释里的 VERIFY_IDENTITY 版 `VIRBIUS_JDBC_URL`（含 `serverSslCert=/certs/ca.pem`），把客户 CA 放到 `./certs/ca.pem`，并解开 A 机两个服务的 certs 卷注释。

## 7. 升级与回滚

- **升级**：我方出新 tar 包 → 现场 `docker load` → 逐机 `docker compose up -d`（顺序无硬依赖，先升 A 可让迁移先跑；单机依次执行三个 compose）。DB 迁移由 control Flyway 自动执行；**升级说明见 DEPLOYMENT.md §9.5**（含 V19 issuer 去重引起的 ≤15 分钟 token 401 窗口，仅影响已有多个 active issuer 的存量库升级）。
- **回滚**：镜像 tag 指回旧版本重启即可；DB 迁移不回滚（新增列/索引向后兼容）。

## 8. 备份要点

| 数据 | 位置 | 方式 |
|---|---|---|
| 规则/租户/审计/签发记录 | 客户 MySQL `virbius` 库 | 客户统一备份 |
| 审计链头/会话状态 | 客户 Redis | **客户必须开 AOF/RDB 且禁用数据淘汰**；v1.0.1 链头丢失不可自愈（§10），备份是唯一兜底 |
| edge manifest / 审计导出 | A 机卷 `control-data` | 定期拷贝 |
| auth JWT 签名密钥 | A 机卷 `auth-data` | 定期拷贝；丢失则全部会话需重登 |
| engine / proxy | 无状态 | 仅日志卷 |

## 9. 现场验收 checklist

> 单机部署时下表 A/B/C 均指本机，`localhost` 命令直接在单机执行。

| # | 检查 | 命令/操作 | 预期 |
|---|---|---|---|
| 1 | 三机容器健康 | `docker compose ps` | 全部 `healthy`/`running` |
| 2 | 控制台登录 | 浏览器开 `CONTROL_PUBLIC_URL` | 登录成功进首页 |
| 3 | 引擎就绪 | B: `curl :8082/admin/ready` | 200 `status=ready` |
| 4 | 规则下发闭环 | 运营台建一条规则→发布快照→B 机 `curl :8082/admin/policy-version?tenant_id=default` | `rule_count` ≥1 |
| 5 | 模型（按选项） | 选项A：B 机 `ollama list` / 选项B：curl vLLM | 模型在列 / 返回 choices |
| 6 | MCP 端到端 | MCP 客户端经 C:9090 调一次白名单工具 | 调用成功；C:9090/status 的 recent_decisions 为 `engine:allow`；运营台审计可见 |
| 7 | 拦截闭环 | 触发一条 deny 规则 | 客户端被拒，运营台有 deny 记录 |
| 8 | 审计链自检 | A 机：`curl -X POST :8080/api/v1/admin/tenants/default/audit/verify`；`curl :8080/api/v1/admin/tenants/default/audit/trace-chain/verify`（带运营台会话 cookie） | 两处均 `passed:true` |
| 9 | 审计导出（如启用） | 运营台导出一次 | `data/exports/` 出密文文件 |

## 10. 故障速查

> 单机部署时 A/B/C 均指本机；跨机连通性问题转为检查 `NODE_A/B/C` 是否填了容器可达 IP。

| 现象 | 定位 |
|---|---|
| control 启动失败 | 看日志首条 ERROR：密钥缺失/过短（§0 密钥表）、JDBC 不通、Kafka SASL 失败 |
| engine `/admin/ready` 一直 503 | 看 body：`redis=down`→Redis 连通/密码；`cache_subscriber=starting`→等 30s 或查 Kafka；`rule_seeder=seeding`→control 未发快照 |
| 控制台登录后 401/回跳异常 | auth 与 control 的 `VIRBIUS_AUTH_ISSUER` 必须一致；`CONTROL_PUBLIC_URL` 必须等于浏览器实际地址 |
| 审批队列页报错 | control→B 机 8082 网络；1.0.0 起失败响应返回 JSON 错误体而非空 body |
| 单机容器间互相调不通 | 常见为 `NODE_*` 误填 `127.0.0.1`（容器内是自身回环），或宿主防火墙未放通 8080/8082/8083/9090 |
| 审计链校验告警（`seq gap` / `curr_hash mismatch`） | 1.0.2 起 Redis 链头丢失（未持久化重启/FLUSH/淘汰）会在下一条审计事件时**从 DB 自愈续链**（日志 `recovered from DB`）。若 verify 仍报 mismatch：多半是事件在丢失窗口期产生（Kafka 有数据、DB 未入库）或有人为改库——保留现场联系我方定位，不要自行清库。预防仍是 §0 Redis 三项硬要求（AOF/RDB + 禁淘汰），避免窗口期丢事件 |
| proxy 审计不落 | ① `KAFKA_BOOTSTRAP_SERVERS`/协议/SCRAM 账号三件是否与客户 Kafka 匹配（支持 SASL_PLAINTEXT+SCRAM-SHA-256/512 与 PLAINTEXT，不支持 SASL_SSL）；② 确认 `VIRBIUS_AUDIT_BACKEND=kafka`；③ proxy 只支持 Kafka 后端；④ topic 未预建时看 proxy 日志 |

---

## 附：有 K8s 时的等价路径

若客户三机是 K8s 节点，走 Helm（DEPLOYMENT.md §8.5），把外置基础设施指给客户统一环境即可：

```yaml
mysql:  { enabled: false, jdbcUrl: "jdbc:mariadb://<客户MySQL>:3306/virbius?sslMode=REQUIRED&serverTimezone=UTC" }
kafka:  { enabled: false, bootstrapServers: "<客户Kafka>:9092" }
redis:  { enabled: false, url: "redis://:<密码>@<客户Redis>:6379" }
ollama: { enabled: false }            # 模型选项B；选项A仍可用集群内 Ollama
engine: { promptLlm: { baseUrl: "http://<客户vLLM>:8000", model: virbiusguard } }
global: { imageRegistry: <现场可写的镜像仓库或 load 后的 registry>, imageTag: v1.0.0 }
```

三机放置用 `nodeSelector`/亲和性把 control/auth、engine、mcp-proxy 分别钉到三台节点；engine/control 多副本时 chart 已自带软性主机分散（`maxSkew: 1`、`ScheduleAnyway`）；端口矩阵、TLS、验收与本手册相同。离线镜像包在 K8s 路径同样适用：`docker load` 到各节点后把 `imageRegistry` 指向本地 tag 即可。
