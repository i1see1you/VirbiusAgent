# VirbiusAgent 客户现场三机部署手册（离线 · 无外置 K8s/有 K8s 均适用）

[English](README.md)

适用版本：**v1.0.0**。
本手册只部署 VirbiusAgent 自身组件；**MySQL / Kafka / Redis / vLLM 由客户统一环境提供**。
随离线包 `virbius-onprem-v1.0.0.tar.gz` 分发（即 `deploy/onprem/` 目录）。

---

## 0. 前提清单（进场前与客户确认）

| 项 | 要求 | 由谁提供 |
|---|---|---|
| MySQL | 8.0+（或 MariaDB 10.6+），建库 `virbius` + 账号密码；**1.0.0 全部迁移已在 MySQL 8.0 / MariaDB 11 双方言实测** | 客户 |
| Kafka | 3.x，SASL_PLAINTEXT + SCRAM-SHA-256 账号；若机制不同改 `.env` 的 `KAFKA_SASL_MECHANISM` | 客户 |
| Redis | 7.x，带密码；**需开持久化（AOF/RDB）**——审计链头在 Redis，断电丢链头会触发审计校验告警 | 客户 |
| vLLM | 可选（模型选项B）；OpenAI 兼容端点 | 客户 |
| 三台机器 | Docker 20.10+（含 compose v2）；彼此网络互通；磁盘 ≥20G | 客户 |
| 镜像 | 现场无法拉外网仓库 → 用 §3 离线包 | 我方 |
| 模型 GGUF | ~1.5GB，经 hf-mirror/ModelScope 预下载（§4） | 我方 |

---

## 1. 三机角色分配

| 机器 | 角色 | 组件 | 对外端口 | 资源建议 |
|---|---|---|---|---|
| A（`NODE_A`） | 管理面 | `virbius-control`（8080）+ `virbius-auth`（8083） | 8080（运营台，建议仅办公网）/8083（内部） | 4C8G |
| B（`NODE_B`） | 决策面 | `virbius-engine`（8082），可 `--scale` 多副本；模型选项A时 + Ollama（11434） | 8082 | 8C16G；选项A加 8G 内存（模型常驻） |
| C（`NODE_C`） | 接入面 | `virbius-mcp-proxy`（9090） | 9090（MCP 客户端入口） | 2C4G |

副本策略（1.0.0 能力边界）：

- **engine 可多副本**：横向扩容请加主机，每台一个 engine（本 compose 固定发布 8082，单机 `--scale` 会端口冲突）。每个副本的 `VIRBIUS_INSTANCE_ID` 必须唯一（compose 容器 hostname 天然唯一；手动 `docker run` 需显式设置）。多于一个 engine 时前置 LB，control 与 proxy 的 `VIRBIUS_ENGINE_URL` 指向 LB。
- **control / auth / proxy 单副本**：auth 的 JWT 签名密钥落在本机卷（扩副本会互踢登录）；control 多副本需共享 NFS（K8s 路径见 DEPLOYMENT.md §8.5，裸机本期不做）；proxy 是 MCP 客户端的单 TCP 入口。

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

东西向组件流量默认明文，依赖客户内网隔离（与 K8s 路径 §9.3 同一口径：Kafka SASL 认证 + 网络层隔离；如需传输加密，由客户侧升级 TLS 后调整 `.env`）。

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
VIRBIUS_PROMPT_LLM_MODEL=virbiusguard            # 与客户部署的模型名一致
```

冒烟验证：`curl $PROMPT_LLM_BASE_URL/v1/chat/completions` 返回 `choices`；再在运营台发一条 `runtime=prompt` 规则触发一次语义检测。

## 5. 逐机部署

启动顺序：客户 infra 就绪 → A 机 → B 机 → C 机。engine 在 control 发布 issuer 前 `/admin/ready` 可能短暂返回 503，属正常，control 就绪后自动转 ready（30s 内）。

```bash
cd onprem && cp .env.example .env && vi .env   # 按现场填写（三机共用同一份 .env）
```

**A 机**：

```bash
docker compose --env-file .env -f node-a-control.yml up -d
curl -sf http://localhost:8083/api/v1/health             # auth 就绪
curl -sf http://localhost:8080/api/v1/health           # control 就绪
# 浏览器打开 CONTROL_PUBLIC_URL，用 AUTH_BOOTSTRAP_USERNAME/PASSWORD 登录
```

**B 机**（模型选项A：先把 GGUF 放到 `onprem/models/` 再起，见 §4）：

```bash
docker compose --env-file .env -f node-b-engine.yml --profile llm-local up -d   # 选项A
docker compose --env-file .env -f node-b-engine.yml up -d                       # 选项B
curl -sf http://localhost:8082/admin/health
curl -s  http://localhost:8082/admin/ready    # 应 200；not-ready 时看 body 的 redis/cache_subscriber/rule_seeder 三项定位
docker exec onprem-ollama-1 ollama list       # 选项A：应见 virbiusguard
```

**C 机**（先放 license）：

```bash
# license.lic：A 机运营台 Licenses 页签发并下载，拷到 onprem/license.lic
docker compose --env-file .env -f node-c-proxy.yml up -d
```

## 6. TLS（生产必做）

- **运营台/管理 API（A 机）**：前置 nginx 终结 HTTPS，客户内网 CA 签发证书：

  ```nginx
  server {
      listen 443 ssl;
      server_name virbius-console.customer.internal;
      ssl_certificate     /etc/nginx/certs/console.crt;
      ssl_certificate_key /etc/nginx/certs/console.key;
      location / { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  }
  ```

  配置后把 `.env` 的 `CONTROL_PUBLIC_URL` 改成 `https://virbius-console.customer.internal` 并重启 A 机（登录回跳地址依赖它）。
- **MCP 入口（C 机 9090）**：TCP 协议。跨网段时由客户前置 LB/nginx `stream` 块做 TCP 直通或 TLS 终结；同内网可直连明文。
- **MySQL**：用 `sslMode=VERIFY_IDENTITY` 时，把 `.env` 换成注释里的 VERIFY_IDENTITY 版 `VIRBIUS_JDBC_URL`（含 `serverSslCert=/certs/ca.pem`），把客户 CA 放到 `./certs/ca.pem`，并解开 A 机两个服务的 certs 卷注释。

## 7. 升级与回滚

- **升级**：我方出新 tar 包 → 现场 `docker load` → 逐机 `docker compose up -d`（顺序无硬依赖，先升 A 可让迁移先跑）。DB 迁移由 control Flyway 自动执行；**升级说明见 DEPLOYMENT.md §9.5**（含 V19 issuer 去重引起的 ≤15 分钟 token 401 窗口，仅影响已有多个 active issuer 的存量库升级）。
- **回滚**：镜像 tag 指回旧版本重启即可；DB 迁移不回滚（新增列/索引向后兼容）。

## 8. 备份要点

| 数据 | 位置 | 方式 |
|---|---|---|
| 规则/租户/审计/签发记录 | 客户 MySQL `virbius` 库 | 客户统一备份 |
| 审计链头/会话状态 | 客户 Redis | **客户必须开 AOF/RDB** |
| edge manifest / 审计导出 | A 机卷 `control-data` | 定期拷贝 |
| auth JWT 签名密钥 | A 机卷 `auth-data` | 定期拷贝；丢失则全部会话需重登 |
| engine / proxy | 无状态 | 仅日志卷 |

## 9. 现场验收 checklist

| # | 检查 | 命令/操作 | 预期 |
|---|---|---|---|
| 1 | 三机容器健康 | `docker compose ps` | 全部 `healthy`/`running` |
| 2 | 控制台登录 | 浏览器开 `CONTROL_PUBLIC_URL` | 登录成功进首页 |
| 3 | 引擎就绪 | B: `curl :8082/admin/ready` | 200 `status=ready` |
| 4 | 规则下发闭环 | 运营台建一条规则→发布快照→B 机 `curl :8082/admin/policy-version?tenant_id=default` | `rule_count` ≥1 |
| 5 | 模型（按选项） | 选项A：B 机 `ollama list` / 选项B：curl vLLM | 模型在列 / 返回 choices |
| 6 | MCP 端到端 | MCP 客户端经 C:9090 调一次白名单工具 | 调用成功，运营台审计可见 |
| 7 | 拦截闭环 | 触发一条 deny 规则 | 客户端被拒，运营台有 deny 记录 |
| 8 | 审计导出（如启用） | 运营台导出一次 | `data/exports/` 出密文文件 |

## 10. 故障速查

| 现象 | 定位 |
|---|---|
| control 启动失败 | 看日志首条 ERROR：密钥缺失/过短（§0 密钥表）、JDBC 不通、Kafka SASL 失败 |
| engine `/admin/ready` 一直 503 | 看 body：`redis=down`→Redis 连通/密码；`cache_subscriber=starting`→等 30s 或查 Kafka；`rule_seeder=seeding`→control 未发快照 |
| 控制台登录后 401/回跳异常 | auth 与 control 的 `VIRBIUS_AUTH_ISSUER` 必须一致；`CONTROL_PUBLIC_URL` 必须等于浏览器实际地址 |
| 审批队列页报错 | control→B 机 8082 网络；1.0.0 起失败响应返回 JSON 错误体而非空 body |
| 审计链校验告警 | 客户 Redis 未开持久化，链头丢失；恢复后 control 会重建 |
| proxy 审计不落 | `VIRBIUS_AUDIT_BACKEND=kafka` + SASL 四件套是否配置；proxy 只支持 Kafka 后端 |

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

三机放置用 `nodeSelector`/亲和性把 control/auth、engine、mcp-proxy 分别钉到三台节点；端口矩阵、TLS、验收与本手册相同。离线镜像包在 K8s 路径同样适用：`docker load` 到各节点后把 `imageRegistry` 指向本地 tag 即可。
