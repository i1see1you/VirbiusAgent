# VirbiusAgent On-Prem Three-Host Deployment Runbook (offline; K8s optional)

Version: **v1.1.0**.
This runbook deploys only VirbiusAgent components; **MySQL / Kafka / Redis / vLLM are provided by the customer's shared platform**. It ships inside the offline bundle `virbius-onprem-v1.1.0.tar.gz` (the `deploy/onprem/` directory). 中文现场版见 [README.zh.md](README.zh.md).

---

## 0. Prerequisites (confirm with the customer before the visit)

| Item | Requirement | Owner |
|---|---|---|
| MySQL | 8.0+ (or MariaDB 10.6+), database `virbius` + credentials. **Database must be `utf8mb4`**. All V1–V36 migrations are verified on both MySQL 8.0 and MariaDB 11 | Customer |
| Kafka | 3.x, SASL_PLAINTEXT + SCRAM-SHA-256 (override `KAFKA_SASL_MECHANISM` in `.env` if different) | Customer |
| Redis | 7.x with password; **persistence (AOF/RDB) required** — the audit chain head lives in Redis and losing it triggers chain-verification alerts. Since v1.0.2 the head is recovered from the DB on the next audit event, but persistence is still required to avoid losing events before recovery | Customer |
| vLLM | Optional (model option B); OpenAI-compatible endpoint | Customer |
| Three hosts | Docker 20.10+ (compose v2), mutual network reachability, ≥20 GB disk | Customer |
| Images | No external registry access on site → use the §3 offline bundle | Us |
| Model GGUF | ~1.5 GB, pre-downloaded via hf-mirror/ModelScope (§4) | Us |

**MySQL database (must be utf8mb4)**:

```sql
CREATE DATABASE virbius CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'virbius'@'%' IDENTIFIED BY '<password>';
GRANT ALL PRIVILEGES ON virbius.* TO 'virbius'@'%';
```

control/auth verify `@@character_set_database` before Flyway runs and refuse to start on anything other than `utf8mb4`, so a wrong database charset cannot silently corrupt data. For an existing database, check with `SELECT @@character_set_database, @@collation_database;`.

---

## 1. Host roles

| Host | Role | Components | Exposed ports | Sizing |
|---|---|---|---|---|
| A (`NODE_A`) | Control plane | `virbius-control` (8080) + `virbius-auth` (8083) | 8080 (console, office network only) / 8083 (internal) | 4C8G |
| B (`NODE_B`) | Decision plane | `virbius-engine` (8082), scalable; + Ollama (11434) for model option A | 8082 | 8C16G (+8 GB RAM for option A) |
| C (`NODE_C`) | Edge ingress | `virbius-mcp-proxy` (9090) | 9090 (MCP client entry) | 2C4G |

Replica policy (v1.1.0 capability boundaries):

- **engine can be multi-replica**: scale out by adding hosts — one engine per host (this compose file publishes a fixed 8082, so `--scale` on a single host collides). Each replica needs a unique `VIRBIUS_INSTANCE_ID`; compose container hostnames are already unique, and with bare `docker run` set it explicitly. With more than one engine, front them with an LB and point `VIRBIUS_ENGINE_URL` / the proxy at the LB.
- **control / auth / proxy stay single-replica**: auth's JWT signing key lives on a local volume (scaling it would invalidate logins); multi-replica control requires shared NFS (K8s path, DEPLOYMENT.md §8.5 — not covered for bare metal in v1.1.0); the proxy is the single TCP entry for MCP clients.

## 2. Network & firewall matrix

| Source → Destination | Port | Purpose | Required |
|---|---|---|---|
| A → customer MySQL | 3306 | control/auth persistence (`sslMode` per customer policy) | ✅ |
| A → customer Kafka | 9092 | audit/trace events, rule-dispatch receipts | ✅ |
| A → customer Redis | 6379 | rule snapshots, issuer publish, rate limiting | ✅ |
| A → B | 8082 | control proxies the approval queue to engine | ✅ |
| B → customer Kafka / Redis | 9092/6379 | audit consume, session state, rule cache | ✅ |
| B → A | 8080 | engine callbacks to control | ✅ |
| B → local Ollama (option A) or customer vLLM (option B) | 11434 / 8000 | prompt-review model | per option |
| C → B | 8082 | tool-call decisions | ✅ |
| C → A | 8080 | per-session token exchange | ✅ |
| C → customer Redis / Kafka | 6379/9092 | snapshot fallback, audit produce | ✅ |
| C → customer MCP Server | per `.env` | proxied upstream | ✅ |
| Office network → A | 443/8080 | console (§6 TLS) | ✅ |
| MCP clients → C | 9090 | business entry | ✅ |

East-west component traffic is plaintext by default and relies on customer network isolation (same posture as the K8s path §9.3: Kafka SASL auth + network-level isolation). To encrypt in transit, upgrade the customer endpoints to TLS and adjust `.env` accordingly.

## 3. Offline image delivery (tar bundle)

**Build side** (our machine with network, repo root):

```bash
./deploy/scripts/offline-bundle.sh --tag v1.1.0 --with-ollama
# outputs dist/offline/:
#   virbius-images-v1.1.0.tar.gz   engine/control/auth/mcp-proxy (+ollama)
#   virbius-onprem-v1.1.0.tar.gz   all assets in this directory + .env sample
#   SHA256SUMS-v1.1.0.txt
```

> **Image architecture must match the customer hosts**: `docker build` defaults to the build host's architecture (arm64 on Apple Silicon). For x86_64 servers add `--platform linux/amd64` (or build on an x86_64 host), otherwise `docker load` succeeds but containers fail with `exec format error`. Check the bundle with `docker image inspect <image> --format '{{.Architecture}}'`.

**On site** (on every host, or load only the images that host needs):

```bash
sha256sum -c SHA256SUMS-v1.1.0.txt          # verify first
gunzip -c virbius-images-v1.1.0.tar.gz | docker load
tar -xzf virbius-onprem-v1.1.0.tar.gz       # yields onprem/
docker images | grep v1.1.0                 # 5 images present
```

## 4. Prompt-review model (pick one)

**Option A (recommended, verified path): Ollama next to the engine.** Pre-download the GGUF on a connected machine:

```bash
# ModelScope (recommended in mainland China)
curl -L -o virbiusguard-v2.0-f16.gguf \
  "https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/gguf/virbiusguard-v2.0-f16.gguf"
# or hf-mirror
curl -L -o virbiusguard-v2.0-f16.gguf \
  "https://hf-mirror.com/i1see1you/VirbiusGuard/resolve/main/gguf/virbiusguard-v2.0-f16.gguf"
```

Place it at `onprem/models/virbiusguard-v2.0-f16.gguf` on host B (the name must match `models/Modelfile`), and start with `--profile llm-local`; `ollama-init` imports it as `virbiusguard` idempotently.

**Option B: the customer's vLLM serves the model.** vLLM cannot serve GGUF; it needs safetensors/HF-format weights — **available on ModelScope** (same repository as the GGUF):

```bash
# On a connected machine, then copy the directory to the customer's vLLM host
mkdir -p virbiusguard-hf && cd virbiusguard-hf
for f in model-00001-of-00002.safetensors model-00002-of-00002.safetensors \
         model.safetensors.index.json config.json generation_config.json \
         tokenizer.json tokenizer_config.json; do
  curl -L -o "$f" "https://www.modelscope.cn/models/i1see1you/VirbiusGuard/resolve/master/$f"
done
# Customer vLLM side (example): vllm serve <this dir> --served-model-name virbiusguard
```

Then set in `.env`:

```bash
PROMPT_LLM_BASE_URL=http://<customer-vllm>:8000   # OpenAI-compatible base URL
VIRBIUS_PROMPT_LLM_MODEL=i1see1you/virbiusguard:v2.0             # must match the deployed model name
```

Smoke test: `curl $PROMPT_LLM_BASE_URL/v1/chat/completions` returns `choices`; then trigger one `runtime=prompt` rule from the console.

## 5. Per-host deployment

Boot order: customer infra ready → A → B → C. Engine `/admin/ready` may briefly return 503 until control publishes the issuers — normal; it becomes ready automatically within ~30s.

```bash
cd onprem && cp .env.example .env && vi .env   # one shared .env for all three hosts
```

**Host A**:

```bash
docker compose --env-file .env -f node-a-control.yml up -d
curl -sf http://localhost:8083/api/v1/health             # auth ready
curl -sf http://localhost:8080/api/v1/health           # control ready
# Open CONTROL_PUBLIC_URL in a browser; log in with AUTH_BOOTSTRAP_USERNAME/PASSWORD
```

**Host B** (option A: place the GGUF into `onprem/models/` first, see §4):

```bash
docker compose --env-file .env -f node-b-engine.yml --profile llm-local up -d   # option A
docker compose --env-file .env -f node-b-engine.yml up -d                       # option B
curl -sf http://localhost:8082/admin/health
curl -s  http://localhost:8082/admin/ready    # expect 200; when not-ready, the body's redis/cache_subscriber/rule_seeder fields say why
docker exec onprem-ollama-1 ollama list       # option A: i1see1you/virbiusguard:v2.0 listed
```

**Host C** (license first):

```bash
# license.lic: issue + download from the console's Licenses page on host A, copy to onprem/license.lic
docker compose --env-file .env -f node-c-proxy.yml up -d
```

### 5.3 Local code execution (optional, gVisor)

> **Hard requirement: to enable the sandbox the proxy container MUST be `privileged: true`.** An unprivileged container cannot create a runsc sandbox (no CAP_SYS_ADMIN; seccomp kills the sentry; `/sys/fs/cgroup` is read-only), so local execution fails closed (`sandbox_unavailable`) instead of degrading to unsandboxed. `node-c-proxy.local-exec.yml` already sets this.

`virbius__shell` / `virbius__python` / `virbius__node` are **off by default** (`node-c-proxy.yml` carries no local-exec config; the overlay enables it). On Host C:

1. **Install runsc** (node-level trusted compute base; not baked into the image). Releases are now tarballs:
   ```bash
   ARCH=$(uname -m)   # x86_64 or aarch64
   mkdir -p /tmp/gvisor && cd /tmp/gvisor
   curl -fsSL -o gvisor.tar.bz2 \
     "https://storage.googleapis.com/gvisor/releases/release/latest/${ARCH}/gvisor.tar.bz2"
   tar -xjf gvisor.tar.bz2
   install -m 0755 runsc /usr/local/bin/runsc
   cp -a gvisor-bin /usr/local/bin/gvisor-bin
   runsc --version
   ```
   runsc looks for `gvisor-bin/gvisor_sentry` **next to its own binary**, so keep them together.
2. **Prepare a read-only rootfs**: the sandbox has **no network and no persistent workspace**, so no pip/npm at runtime — preinstall interpreters. Quickest is exporting the matching proxy image rootfs (already has python3/nodejs):
   ```bash
   mkdir -p /opt/virbius/rootfs
   docker create --name vroot hb.grainmind.cn/library/virbius-mcp-proxy:v1.1.0
   docker export vroot | tar -x -C /opt/virbius/rootfs
   docker rm vroot && chmod -R a+rX /opt/virbius/rootfs
   ```
   A smaller, least-privilege rootfs built from debian/alpine also works (`INTEGRATION.md` §2 item 5).
3. **Provide the edge manifest (carries `gvisor_config`)**: the pool's limits and on/off switch come from the `gvisor_config` control delivers. **Without the manifest, local execution is always `sandbox_unavailable`** — the overlay only lets the container run runsc. Copy the published manifest from Host A to Host C:
   ```bash
   # Host A: export from the control-data volume (<app_id> = the license app_id, e.g. mcp-demo)
   docker run --rm -v virbius-onprem-a_control-data:/data alpine \
     cat /data/edge/default/<app_id>/edge-manifest.json > edge-manifest.json
   # On Host C:
   mkdir -p /opt/virbius/edge
   cp edge-manifest.json /opt/virbius/edge/edge-manifest.json
   ```
   Prerequisite: create a `runtime=gvisor` sandbox rule in the console and promote it to **full** (`dry_run` is not written into the manifest; `gvisor_config` stays `{}`).
   The proxy reads the manifest at startup and then **re-reads it every `VIRBIUS_EDGE_SYNC_INTERVAL_SECS`** (default 30s); a newly copied manifest takes effect within one interval, no restart needed (restart still works).
   To pull automatically instead of copying the file, set `PROXY_EDGE_CONTROL_BASE_URL`, `PROXY_EDGE_APP_ID` and `PROXY_EDGE_API_KEY` in `.env` (the overlay maps them to `VIRBIUS_*`).
4. **Start Host C with the overlay** (`privileged` + `cgroup: host` + runsc/rootfs/state/manifest mounts):
   ```bash
   mkdir -p /var/lib/virbius/gvisor
   docker compose --env-file .env \
     -f node-c-proxy.yml -f node-c-proxy.local-exec.yml up -d
   ```
   Optional `.env` overrides: `PROXY_RUNSC_HOST_PATH`, `PROXY_GVISOR_BIN_HOST_PATH`, `PROXY_ROOTFS_HOST_PATH`, `PROXY_STATE_HOST_PATH`, `PROXY_EDGE_MANIFEST_DIR`, `PROXY_GVISOR_ENFORCE_CGROUPS`.

**Why `privileged` + `cgroup: host`**:

- Even with the default `--ignore-cgroups`, runsc creates user/pid/mount namespaces and forks the gofer (`/proc/self/exe`); a normal container fails with `operation not permitted`, so local exec **requires `privileged`**.
- A private cgroup namespace makes `cgroup.subtree_control` fail with `device or resource busy`; `cgroup: host` lets runsc create cgroups, so `PROXY_GVISOR_ENFORCE_CGROUPS=1` actually enforces memory/cpu/pids (verified: ~200MB alloc against a 128MB limit is OOM-killed, exit 137).

**Limit honesty**: default `enforce=0` reports `_meta.gvisor_limits_enforced=false` (read-only rootfs, tmpfs caps and RLIMIT_NOFILE/NPROC still apply); `=1` reports `true` only when the host can apply cgroups. If it cannot, execution fails closed (`sandbox_unavailable`) — never unsandboxed. Verify with `INTEGRATION.md` §8 (`m1-doctor.py --call-shell`, expect `sandbox_used=gvisor`).

**Rollback**: drop `-f node-c-proxy.local-exec.yml` and restart; upstream tool proxying is unaffected.

> K8s: use `proxy.localExec.enabled=true` in the Helm values. Standard K8s exposes no per-Pod cgroup namespace, so **cgroup hard limits are not supported on K8s** — keep `enforce_cgroups=false`; read-only rootfs / tmpfs / rlimits still apply.

## 6. TLS (mandatory for production)

- **Console / admin API (host A)**: terminate HTTPS with nginx in front, certificates from the customer's internal CA:

  ```nginx
  server {
      listen 443 ssl;
      server_name virbius-console.customer.internal;
      ssl_certificate     /etc/nginx/certs/console.crt;
      ssl_certificate_key /etc/nginx/certs/console.key;
      location / { proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
  }
  ```

  Then set `CONTROL_PUBLIC_URL=https://virbius-console.customer.internal` in `.env` and restart host A (the login redirect depends on it).
- **MCP entry (C:9090)** is TCP: use the customer's LB or an nginx `stream` block for TCP pass-through or TLS termination across segments; plaintext inside a trusted LAN is acceptable.
- **MySQL**: for `sslMode=VERIFY_IDENTITY`, switch `.env` to the commented `VIRBIUS_JDBC_URL` variant (it carries `serverSslCert=/certs/ca.pem`), place the customer CA at `./certs/ca.pem`, and uncomment the `certs` volume line on both host-A services.

## 7. Upgrades & rollback

- **Upgrade**: we ship a new bundle → `docker load` on site → `docker compose up -d` per host (order is not strict; start with A so the migrations run first). DB migrations run automatically via control's Flyway; **see DEPLOYMENT.md §9.5** (including the ≤15-minute token-401 window caused by the V19 issuer dedup, which affects only upgrades of databases that already had multiple active issuers).
- **Rollback**: point the image tag back and restart; DB migrations are not rolled back (additive columns/indexes are backward compatible).

## 8. Backup essentials

| Data | Location | Method |
|---|---|---|
| Rules/tenants/audit/issuance | customer MySQL `virbius` DB | customer's standard backup |
| Audit chain head / session state | customer Redis | **customer must enable AOF/RDB** |
| Edge manifests / audit exports | host A volume `control-data` | periodic copy |
| auth JWT signing key | host A volume `auth-data` | periodic copy; loss forces re-login for all sessions |
| engine / proxy | stateless | log volumes only |

## 9. Acceptance checklist

| # | Check | Command / action | Expected |
|---|---|---|---|
| 1 | All containers healthy | `docker compose ps` | all `healthy`/`running` |
| 2 | Console login | open `CONTROL_PUBLIC_URL` | login succeeds |
| 3 | Engine ready | B: `curl :8082/admin/ready` | 200 `status=ready` |
| 4 | Rule dispatch loop | create a rule in the console → publish snapshot → B: `curl :8082/admin/policy-version?tenant_id=default` | `rule_count` ≥1 |
| 5 | Model (per option) | option A: `ollama list` on host B / option B: curl vLLM | model listed / returns choices |
| 6 | MCP end-to-end | one allowlisted tool call through C:9090 | call succeeds, visible in console audit |
| 7 | Deny loop | trigger a deny rule | client rejected, deny record in console |
| 8 | Audit export (if enabled) | export once from the console | ciphertext file under `data/exports/` |
| 9 | MySQL charset | run `SELECT @@character_set_database` on the `virbius` DB | `utf8mb4` |

## 10. Troubleshooting quick reference

| Symptom | Where to look |
|---|---|
| control fails to start | first ERROR in the log: missing/short keys (§0 table), JDBC unreachable, Kafka SASL failure |
| engine `/admin/ready` stuck 503 | read the body: `redis=down` → Redis reachability/password; `cache_subscriber=starting` → wait 30s or check Kafka; `rule_seeder=seeding` → control has not published a snapshot |
| console 401 / broken login redirect | `VIRBIUS_AUTH_ISSUER` must match on auth and control; `CONTROL_PUBLIC_URL` must equal the browser-visible address |
| approval-queue page errors | network A→B:8082; failures return a JSON error body instead of an empty response |
| audit-chain verification alerts | customer Redis lacks persistence and lost the chain head. Since v1.0.2 the head is recovered from the DB on the next audit event (log: `recovered from DB`); events produced during the lost window still need manual investigation |
| proxy audit missing | `VIRBIUS_AUDIT_BACKEND=kafka` + the four SASL vars configured; the proxy only supports the Kafka backend |

---

## Appendix: the equivalent path when K8s is available

If the customer's three hosts are K8s nodes, use Helm (DEPLOYMENT.md §8.5) and point the chart at the shared infrastructure:

```yaml
mysql:  { enabled: false, jdbcUrl: "jdbc:mariadb://<customer-mysql>:3306/virbius?sslMode=REQUIRED&serverTimezone=UTC" }
kafka:  { enabled: false, bootstrapServers: "<customer-kafka>:9092" }
redis:  { enabled: false, url: "redis://:<password>@<customer-redis>:6379" }
ollama: { enabled: false }            # model option B; option A can still use the in-cluster Ollama
engine: { promptLlm: { baseUrl: "http://<customer-vllm>:8000", model: virbiusguard } }
global: { imageRegistry: <registry reachable on site>, imageTag: v1.1.0 }
```

Pin control/auth, engine, and mcp-proxy onto the three nodes with `nodeSelector`/affinity; the chart already applies a soft hostname spread for engine/control replicas (`maxSkew: 1`, `ScheduleAnyway`). The port matrix, TLS, and the acceptance checklist are identical to this runbook. The offline image bundle also works on K8s: `docker load` on each node (or import into the customer's registry) and set `imageRegistry` accordingly.
