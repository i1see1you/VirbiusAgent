# K8s Helm Full-Stack Deploy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Helm chart and build/push/install scripts that deploy 靶场, 端侧, 云侧, 管侧 plus MySQL/Kafka/Redis to an existing Kubernetes cluster.

**Architecture:** One chart `deploy/helm/virbius` with StatefulSets for infra and Deployments for the four apps; nginx Ingress with four independent hosts; scripts build four images from existing Dockerfiles and `helm upgrade --install`. Demo Agent labs keep in-pod stdio proxy; cluster TCP proxy is a separate edge Deployment.

**Tech Stack:** Helm 3, Kubernetes Deployments/StatefulSets/Ingress/Secret/PVC, bash, existing multi-stage Dockerfiles, Spring `prod` profile.

## Global Constraints

- Chart install namespace default `virbius`; release name `virbius`; `fullnameOverride: virbius`
- Service DNS names: `virbius-mysql`, `virbius-redis`, `virbius-kafka`, `virbius-control`, `virbius-engine`, `virbius-mcp-proxy`, `virbius-demo`
- Control/engine `SPRING_PROFILES_ACTIVE=prod`
- Ingress: four hosts, no path prefixes; class `nginx`
- Secrets never committed; `values-prod.yaml` gitignored
- Do not install nginx-ingress, Higress, Falco, or Ollama
- Do not rewrite demo `mcpproxy_client.py` from stdio to TCP
- Root Dockerfile proxy stage must COPY the binary
- SSE server bind host from `VIRBIUS_SSE_BIND` (default `127.0.0.1`)
- Skip git commits unless the user asks

## File map

| Path | Responsibility |
|------|----------------|
| `Dockerfile` (proxy stage) | Copy `virbius-mcp-proxy` binary into the runtime image |
| `virbius-demo/dvla_agent/mcp_sse_server.py` | Bind host from `VIRBIUS_SSE_BIND` |
| `deploy/helm/virbius/*` | Chart, values, templates |
| `deploy/scripts/k8s-build-push.sh` | docker build + push four images |
| `deploy/scripts/k8s-deploy.sh` | build-push then helm install + wait |
| `.gitignore` | ignore `values-prod.yaml` |
| `DEPLOYMENT.zh.md` | K8s Helm section |

---

### Task 1: Proxy image COPY + SSE bind host

**Files:**
- Modify: `Dockerfile` (virbius-mcp-proxy stage, before `ENTRYPOINT`)
- Modify: `virbius-demo/dvla_agent/mcp_sse_server.py` (`main()`)

**Interfaces:**
- Consumes: rust-build output at `/build/target/release/virbius-mcp-proxy`
- Produces: binary on `PATH` as `virbius-mcp-proxy`; SSE listen address `os.environ.get("VIRBIUS_SSE_BIND", "127.0.0.1")`

- [ ] **Step 1:** In `Dockerfile` stage `virbius-mcp-proxy`, after `RUN mkdir -p /var/log/virbius`, add:

```dockerfile
COPY --from=rust-build /build/target/release/virbius-mcp-proxy /usr/local/bin/virbius-mcp-proxy
```

- [ ] **Step 2:** Change `mcp_sse_server.py` `main()` to:

```python
def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9091
    host = os.environ.get("VIRBIUS_SSE_BIND", "127.0.0.1").strip() or "127.0.0.1"
    server = ThreadingHTTPServer((host, port), Handler)
    print("dvla mcp SSE server listening on %s:%d" % (host, port), flush=True)
    server.serve_forever()
```

Add `import os` at the top of that file if missing.

- [ ] **Step 3:** Confirm default remains loopback: unset env → bind `127.0.0.1`.

---

### Task 2: Chart scaffolding (Chart.yaml, helpers, values)

**Files:**
- Create: `deploy/helm/virbius/Chart.yaml`
- Create: `deploy/helm/virbius/templates/_helpers.tpl`
- Create: `deploy/helm/virbius/values.yaml`
- Create: `deploy/helm/virbius/values.example.yaml`

**Interfaces:**
- Produces: `virbius.fullname` → `virbius` when `fullnameOverride: virbius`; `virbius.labels`; `virbius.selectorLabels`; `virbius.image`; `virbius.secretName`; `virbius.imagePullSecrets`

- [ ] **Step 1:** Write `Chart.yaml`:

```yaml
apiVersion: v2
name: virbius
description: VirbiusAgent full stack (range, edge, cloud, control) plus MySQL, Kafka, Redis
type: application
version: 0.1.0
appVersion: "0.1.0"
```

- [ ] **Step 2:** Write `_helpers.tpl` with `virbius.name`, `virbius.fullname` (honor `fullnameOverride` / `nameOverride`), `virbius.chart`, `virbius.labels`, `virbius.selectorLabels`, `virbius.serviceAccountName` unused, `virbius.secretName` (`existingSecret` or `{fullname}-secrets`), `virbius.image` taking a component name (`printf "%s/virbius-%s:%s" .registry .component .tag`), `virbius.imagePullSecrets`.

- [ ] **Step 3:** Write `values.yaml` matching the spec contract: `fullnameOverride: virbius`, empty `global.imageRegistry`, `imageTag: latest`, ingress four hosts, infra persistence, replicaCount 1, `control.apiKeyEnabled: false`, `proxy.upstreamUrl: http://virbius-demo:9091`, placeholder secrets (empty strings).

- [ ] **Step 4:** Write `values.example.yaml` with fake registry `registry.example.com/virbius`, example domain, and placeholder secret strings (not real keys).

---

### Task 3: Secret + infra StatefulSets

**Files:**
- Create: `deploy/helm/virbius/templates/secret.yaml`
- Create: `deploy/helm/virbius/templates/mysql.yaml`
- Create: `deploy/helm/virbius/templates/redis.yaml`
- Create: `deploy/helm/virbius/templates/kafka.yaml`

**Interfaces:**
- Consumes: `secrets.*`, `mysql.*`, `redis.*`, `kafka.*`
- Produces: Secret keys listed in the spec; Services `virbius-mysql:3306`, `virbius-redis:6379`, `virbius-kafka:9092`

- [ ] **Step 1:** `secret.yaml` — if `secrets.existingSecret` is empty, create Opaque Secret with b64enc values. Keys: `mysql-root-password`, `mysql-password`, `license-master-key`, `deepseek-api-key`, `openrouter-api-key`.

- [ ] **Step 2:** `mysql.yaml` — StatefulSet `virbius-mysql`, image `mysql:8.0`, env from Secret, database `virbius` user `virbius`, charset command same as `docker-compose.prod.yml`, volumeClaimTemplate, Service ClusterIP 3306, probe `mysqladmin ping`.

- [ ] **Step 3:** `redis.yaml` — StatefulSet `redis:7-alpine`, port 6379, probe `redis-cli ping`, PVC 5Gi.

- [ ] **Step 4:** `kafka.yaml` — StatefulSet `apache/kafka:3.7.0`, KRaft env from compose.prod **except** `KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://virbius-kafka:9092`, `CLUSTER_ID: MkUjOEJhQjY1QUY1NDQ2Nk1BODI`, probe using `/opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list`, Service 9092.

Each infra template wrapped in `{{- if .Values.<component>.enabled }}`.

---

### Task 4: App Deployments (control, engine, proxy, demo)

**Files:**
- Create: `deploy/helm/virbius/templates/control.yaml`
- Create: `deploy/helm/virbius/templates/engine.yaml`
- Create: `deploy/helm/virbius/templates/proxy.yaml`
- Create: `deploy/helm/virbius/templates/demo.yaml`

**Interfaces:**
- Consumes: secret name helper, service DNS from Task 3, values for images/resources
- Produces: Deployments+Services named as Global Constraints; demo Service ports `http=8000` and `sse=9091`

- [ ] **Step 1:** `engine.yaml` — initContainers wait kafka+redis via `busybox:1.36` `nc -z`; env as spec; probe `/admin/health` port 8082; `initialDelaySeconds: 60`.

- [ ] **Step 2:** `control.yaml` — wait mysql, redis, kafka, engine:8082; JDBC from Secret; PVC for `/data`; probe `/api/v1/health`.

- [ ] **Step 3:** `proxy.yaml` — wait control+engine; `VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090`; probe `/health` 9090.

- [ ] **Step 4:** `demo.yaml` — wait control+engine; `VIRBIUS_SSE_BIND=0.0.0.0`; API keys from Secret; PVC `/data`; ports 8000 and 9091; probe `/` on 8000.

Use `imagePullSecrets` helper. Component image names: `virbius-engine`, `virbius-control`, `virbius-mcp-proxy`, `virbius-demo`.

---

### Task 5: Ingress + NOTES

**Files:**
- Create: `deploy/helm/virbius/templates/ingress.yaml`
- Create: `deploy/helm/virbius/templates/NOTES.txt`

- [ ] **Step 1:** Ingress with four rules mapping spec hosts to services/ports 8000, 8080, 8082, 9090. Optional TLS block.

- [ ] **Step 2:** NOTES.txt prints the four URLs and reminds to set `licenseMasterKey` and `imagePullSecrets`.

---

### Task 6: Scripts, gitignore, DEPLOYMENT.zh.md

**Files:**
- Create: `deploy/scripts/k8s-build-push.sh` (executable)
- Create: `deploy/scripts/k8s-deploy.sh` (executable)
- Modify: `.gitignore`
- Modify: `DEPLOYMENT.zh.md` (append §8.5)

- [ ] **Step 1:** `k8s-build-push.sh` — `set -euo pipefail`; parse `--registry` (required), `--tag` (default latest), `--apt-mirror`; build four images; push.

- [ ] **Step 2:** `k8s-deploy.sh` — check docker/helm/kubectl; optional `--skip-build`, `--namespace`, `--values`, `--release`; helm upgrade --install; wait four Deployments; print hosts.

- [ ] **Step 3:** gitignore `deploy/helm/virbius/values-prod.yaml` and `deploy/helm/virbius/values-*.local.yaml`.

- [ ] **Step 4:** Append K8s Helm 部署 section to `DEPLOYMENT.zh.md` with prerequisites and commands.

---

### Task 7: Verify helm lint + template

**Files:** none new

- [ ] **Step 1:** Run `helm lint deploy/helm/virbius`

Expected: `1 chart(s) linted, 0 chart(s) failed`

- [ ] **Step 2:** Run:

```bash
helm template virbius deploy/helm/virbius \
  --set global.imageRegistry=example.registry/virbius \
  --set secrets.mysqlRootPassword=root \
  --set secrets.mysqlPassword=pass \
  --set secrets.licenseMasterKey=key
```

Expected: YAML contains Deployments `virbius-control`, `virbius-engine`, `virbius-mcp-proxy`, `virbius-demo`; Ingress hosts `range.virbius.example.com`, `control.virbius.example.com`, `engine.virbius.example.com`, `proxy.virbius.example.com`; Kafka advertised listener `virbius-kafka:9092`; COPY is not testable via helm — grep Dockerfile for the COPY line.

- [ ] **Step 3:** `bash deploy/scripts/k8s-deploy.sh --help` exits 0; `bash deploy/scripts/k8s-build-push.sh` without `--registry` exits non-zero.
