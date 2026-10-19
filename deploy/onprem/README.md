# VirbiusAgent On-Prem Three-Host Deployment Runbook (offline; K8s optional)

Version: **v1.0.0**.
This runbook deploys only VirbiusAgent components; **MySQL / Kafka / Redis / vLLM are provided by the customer's shared platform**. It ships inside the offline bundle `virbius-onprem-v1.0.0.tar.gz` (the `deploy/onprem/` directory). 中文现场版见 [README.zh.md](README.zh.md).

---

## 0. Prerequisites (confirm with the customer before the visit)

| Item | Requirement | Owner |
|---|---|---|
| MySQL | 8.0+ (or MariaDB 10.6+), database `virbius` + credentials. All 1.0.0 migrations are verified on both MySQL 8.0 and MariaDB 11 | Customer |
| Kafka | 3.x, SASL_PLAINTEXT + SCRAM-SHA-256 (override `KAFKA_SASL_MECHANISM` in `.env` if different) | Customer |
| Redis | 7.x with password; **persistence (AOF/RDB) required** — the audit chain head lives in Redis and losing it triggers chain-verification alerts | Customer |
| vLLM | Optional (model option B); OpenAI-compatible endpoint | Customer |
| Three hosts | Docker 20.10+ (compose v2), mutual network reachability, ≥20 GB disk | Customer |
| Images | No external registry access on site → use the §3 offline bundle | Us |
| Model GGUF | ~1.5 GB, pre-downloaded via hf-mirror/ModelScope (§4) | Us |

---

## 1. Host roles

| Host | Role | Components | Exposed ports | Sizing |
|---|---|---|---|---|
| A (`NODE_A`) | Control plane | `virbius-control` (8080) + `virbius-auth` (8083) | 8080 (console, office network only) / 8083 (internal) | 4C8G |
| B (`NODE_B`) | Decision plane | `virbius-engine` (8082), scalable; + Ollama (11434) for model option A | 8082 | 8C16G (+8 GB RAM for option A) |
| C (`NODE_C`) | Edge ingress | `virbius-mcp-proxy` (9090) | 9090 (MCP client entry) | 2C4G |

Replica policy (1.0.0 capability boundaries):

- **engine can be multi-replica**: scale out by adding hosts — one engine per host (this compose file publishes a fixed 8082, so `--scale` on a single host collides). Each replica needs a unique `VIRBIUS_INSTANCE_ID`; compose container hostnames are already unique, and with bare `docker run` set it explicitly. With more than one engine, front them with an LB and point `VIRBIUS_ENGINE_URL` / the proxy at the LB.
- **control / auth / proxy stay single-replica**: auth's JWT signing key lives on a local volume (scaling it would invalidate logins); multi-replica control requires shared NFS (K8s path, DEPLOYMENT.md §8.5 — not covered for bare metal in 1.0.0); the proxy is the single TCP entry for MCP clients.

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
./deploy/scripts/offline-bundle.sh --tag v1.0.0 --with-ollama
# outputs dist/offline/:
#   virbius-images-v1.0.0.tar.gz   engine/control/auth/mcp-proxy (+ollama)
#   virbius-onprem-v1.0.0.tar.gz   all assets in this directory + .env sample
#   SHA256SUMS-v1.0.0.txt
```

**On site** (on every host, or load only the images that host needs):

```bash
sha256sum -c SHA256SUMS-v1.0.0.txt          # verify first
gunzip -c virbius-images-v1.0.0.tar.gz | docker load
tar -xzf virbius-onprem-v1.0.0.tar.gz       # yields onprem/
docker images | grep v1.0.0                 # 5 images present
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
VIRBIUS_PROMPT_LLM_MODEL=virbiusguard             # must match the deployed model name
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
docker exec onprem-ollama-1 ollama list       # option A: virbiusguard listed
```

**Host C** (license first):

```bash
# license.lic: issue + download from the console's Licenses page on host A, copy to onprem/license.lic
docker compose --env-file .env -f node-c-proxy.yml up -d
```

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

## 10. Troubleshooting quick reference

| Symptom | Where to look |
|---|---|
| control fails to start | first ERROR in the log: missing/short keys (§0 table), JDBC unreachable, Kafka SASL failure |
| engine `/admin/ready` stuck 503 | read the body: `redis=down` → Redis reachability/password; `cache_subscriber=starting` → wait 30s or check Kafka; `rule_seeder=seeding` → control has not published a snapshot |
| console 401 / broken login redirect | `VIRBIUS_AUTH_ISSUER` must match on auth and control; `CONTROL_PUBLIC_URL` must equal the browser-visible address |
| approval-queue page errors | network A→B:8082; since 1.0.0, failures return a JSON error body instead of an empty response |
| audit-chain verification alerts | customer Redis lacks persistence and lost the chain head; control rebuilds it after recovery |
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
global: { imageRegistry: <registry reachable on site>, imageTag: v1.0.0 }
```

Pin control/auth, engine, and mcp-proxy onto the three nodes with `nodeSelector`/affinity; the port matrix, TLS, and the acceptance checklist are identical to this runbook. The offline image bundle also works on K8s: `docker load` on each node (or import into the customer's registry) and set `imageRegistry` accordingly.
