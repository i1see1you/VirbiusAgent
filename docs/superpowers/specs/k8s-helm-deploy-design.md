# K8s Helm 全栈部署 — Design

| Item | Value |
|------|-------|
| Date | 2026-08-17 |
| Status | Approved |
| Branch | `feature/k8s-helm-deploy` |

## Goal

Ship one Helm chart and a build/push/install script that deploys four Virbius sides plus production dependencies onto an existing Kubernetes cluster with a container registry.

| Side | Workload | Port |
|------|----------|------|
| 管侧 | `virbius-control` | 8080 |
| 云侧 | `virbius-engine` | 8082 |
| 端侧 | `virbius-mcp-proxy` (TCP) | 9090 |
| 靶场 | `virbius-demo` | 8000 |
| Infra | MySQL 8, Kafka 3.7 KRaft, Redis 7, Ollama + VirbiusGuard GGUF | 3306 / 9092 / 6379 / 11434 |

## Non-goals

- Higress, Falco / kernel DaemonSet
- Changing the demo Agent lab from stdio proxy to cluster TCP proxy
- Multi-replica HA for MySQL or Kafka
- Installing an Ingress Controller

## Required bugfix

Root `Dockerfile` stage `virbius-mcp-proxy` does not `COPY` the Rust binary from `rust-build`. The edge image cannot start until this is fixed:

```
COPY --from=rust-build /build/target/release/virbius-mcp-proxy /usr/local/bin/virbius-mcp-proxy
```

## Required small demo change (bind address only)

`dvla_agent/mcp_sse_server.py` currently binds `127.0.0.1:9091`. Cluster-side TCP proxy cannot use `http://virbius-demo:9091` as upstream.

- Bind host from env `VIRBIUS_SSE_BIND`, default `127.0.0.1` (local compose unchanged)
- Kubernetes demo sets `VIRBIUS_SSE_BIND=0.0.0.0` and exposes container port 9091
- Demo Agent labs still spawn in-pod stdio `virbius-mcp-proxy`

## Architecture

```
                    Ingress (nginx, four independent hosts)
     range.base     control.base     engine.base     proxy.base
          |              |                |              |
     virbius-demo   virbius-control  virbius-engine  mcp-proxy
     :8000 / :9091  :8080            :8082           :9090
          |              |                |              |
          +------+-------+--------+-------+------+-------+
                 |                |              |
            virbius-mysql   virbius-kafka   virbius-redis
```

In-cluster DNS (release name `virbius`, `fullnameOverride: virbius`):

- Apps → `http://virbius-control:8080`, `http://virbius-engine:8082`
- Control JDBC → `jdbc:mariadb://virbius-mysql:3306/virbius?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC`
- Kafka → `virbius-kafka:9092`
- Redis → `redis://virbius-redis:6379`
- Edge proxy upstream default → `http://virbius-demo:9091`

Ingress uses **independent hosts**, not path prefixes (Spring Boot / Flask have no shared context-path).

## Chart layout

```
deploy/
  helm/virbius/
    Chart.yaml
    values.yaml
    values.example.yaml
    templates/
      _helpers.tpl
      secret.yaml
      mysql.yaml
      redis.yaml
      kafka.yaml
      control.yaml
      engine.yaml
      proxy.yaml
      demo.yaml
      ingress.yaml
      NOTES.txt
  scripts/
    k8s-build-push.sh
    k8s-deploy.sh
```

No `namespace.yaml`. Install with `helm --create-namespace -n virbius`.

## Images

| Image | Build |
|-------|-------|
| `{registry}/virbius-engine:{tag}` | repo-root `Dockerfile --target virbius-engine` |
| `{registry}/virbius-control:{tag}` | `--target virbius-control` |
| `{registry}/virbius-mcp-proxy:{tag}` | `--target virbius-mcp-proxy` |
| `{registry}/virbius-demo:{tag}` | `virbius-demo/Dockerfile`, context = repo root |

`imagePullSecrets` comes from values. Caller runs `docker login` before the script.

## values.yaml contract

```yaml
global:
  imageRegistry: ""          # required for real install
  imageTag: latest
  imagePullSecrets: []

ingress:
  enabled: true
  className: nginx
  baseDomain: virbius.example.com
  tls:
    enabled: false
    secretName: ""
  hosts:
    range: range.virbius.example.com
    control: control.virbius.example.com
    engine: engine.virbius.example.com
    proxy: proxy.virbius.example.com

mysql / redis / kafka:
  enabled: true
  persistence size: 10Gi / 5Gi / 10Gi
  single-replica StatefulSet

control / engine / proxy / demo:
  replicaCount: 1

engine.promptLlm.baseUrl: ""   # empty + ollama.enabled → in-cluster Ollama
engine.promptLlm.model: virbiusguard

ollama:
  enabled: true
  image: ollama/ollama:0.11.10
  model: virbiusguard
  ggufUrl: HuggingFace V13 Q4_K_M (override for ModelScope)

proxy.upstreamUrl: http://virbius-demo:9091

control.apiKeyEnabled: false   # matches docker-compose.prod.yml local-test default

demo.agentUseDocker: "0"

secrets:
  existingSecret: ""           # if set, chart does not create a Secret
  mysqlRootPassword / mysqlPassword / licenseMasterKey
  deepseekApiKey / openrouterApiKey
```

Secrets are not committed. `values.example.yaml` holds placeholders. Real files: `values-prod.yaml` (gitignored) or `--set`.

Secret keys:

| Key | Used by |
|-----|---------|
| `mysql-root-password` | mysql |
| `mysql-password` | mysql + control JDBC |
| `license-master-key` | control |
| `deepseek-api-key` | demo |
| `openrouter-api-key` | demo |

## Workloads

Spring profile for control and engine: `prod`.

| Workload | Init wait (busybox `nc -z`) | Probe |
|----------|-----------------------------|-------|
| mysql | — | `mysqladmin ping` |
| redis | — | `redis-cli ping` |
| kafka | — | `kafka-topics.sh --bootstrap-server localhost:9092 --list` |
| engine | kafka 9092, redis 6379 | `GET /admin/health` |
| control | mysql 3306, redis 6379, kafka 9092, engine 8082 | `GET /api/v1/health` |
| proxy | control 8080, engine 8082 | `GET /health` |
| demo | control 8080, engine 8082 | `GET /` |

Java apps: `initialDelaySeconds` 60. Others: 15–20.

Env mapping (same names as compose.prod / compose full profile):

- control: `SPRING_PROFILES_ACTIVE=prod`, `VIRBIUS_JDBC_URL/USER/PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `VIRBIUS_REDIS_URL`, `VIRBIUS_ENGINE_URL`, `VIRBIUS_LICENSE_MASTER_KEY`, `VIRBIUS_SECURITY_API_KEY_ENABLED`
- engine: `SPRING_PROFILES_ACTIVE=prod`, `KAFKA_BOOTSTRAP_SERVERS`, `VIRBIUS_REDIS_URL`, `VIRBIUS_CONTROL_BASE_URL`, `VIRBIUS_PROMPT_LLM_BASE_URL`, `VIRBIUS_PROMPT_LLM_MODEL`
- proxy: `VIRBIUS_TRANSPORT=tcp://0.0.0.0:9090`, `VIRBIUS_CONTROL_URL`, `VIRBIUS_ENGINE_URL`, `VIRBIUS_REDIS_URL`, `VIRBIUS_UPSTREAM_URL`
- demo: `VIRBIUS_CONTROL_URL`, `VIRBIUS_ENGINE_URL`, `VIRBIUS_SSE_BIND=0.0.0.0`, `VIRBIUS_MCP_PROXY_BIN=/usr/local/bin/virbius-mcp-proxy`, `VIRBIUS_CONFIG_DIR=/data`, `DEEPSEEK_API_KEY`, `OPENROUTER_API_KEY`, `AGENT_USE_DOCKER=0`

Kafka advertised listener **must** be `PLAINTEXT://virbius-kafka:9092` (not compose's `kafka:9092`). KRaft `CLUSTER_ID` is a fixed string so PVC reuse stays consistent.

Demo Service exposes `http:8000` (Ingress) and `sse:9091` (cluster proxy upstream). Flask Ingress does not publish 9091.

Persistence: StatefulSet PVC for mysql/redis/kafka; PVC for control `/data` and demo `/data`; engine/proxy logs `emptyDir`.

`replicaCount` is 1 for all v1 workloads. No HA.

## Ingress

Four hosts, `ingressClassName` from values, TLS off by default. If TLS is on, all four hosts share `ingress.tls.secretName`. Chart does not install nginx.

## Scripts

`deploy/scripts/k8s-build-push.sh`:

- Requires `--registry`
- Optional `--tag` (default `latest`), `--apt-mirror`
- Builds four images, tags `{registry}/virbius-{engine,control,mcp-proxy,demo}:{tag}`, pushes

`deploy/scripts/k8s-deploy.sh`:

- Checks `docker`, `helm`, `kubectl`
- Calls build-push unless `--skip-build`
- `helm upgrade --install virbius deploy/helm/virbius -n <ns> --create-namespace`
- Extra `--values` file optional
- `kubectl wait` four Deployments Available (timeout 180s)
- Prints Ingress hosts and health URLs
- Does not create a registry or run `docker login`

## Failure behaviour

| Case | Behaviour |
|------|-----------|
| MySQL/Kafka not ready | control/engine stay unready via init/probes until deps recover |
| Prompt LLM unreachable | engine process stays up; prod prompt path is fail-closed |
| Proxy has no working upstream | `/health` still 200; `tools/call` fails |
| Demo missing LLM API key | Flask starts; chat endpoints keep existing key-missing behaviour |
| Default license key | control already warns in prod; `NOTES.txt` reminds to set `licenseMasterKey` |
| Image pull failure | `ImagePullBackOff`; docs mention `imagePullSecrets` |

`helm uninstall` does not delete PVCs (Helm default).

## Verification

- `helm lint deploy/helm/virbius`
- `helm template` with example values renders four Ingress hosts and four Deployments
- Scripts print usage on `--help` and error when `--registry` is missing
- Manual: after wait, curl the four Ingress health paths
- CI without a cluster is not required to `helm install`

## Docs

Add section **K8s Helm 部署** to `DEPLOYMENT.zh.md`: prerequisites (Ingress Controller, registry, secrets), commands, four URLs. No new long-form whitepaper.

## Out of scope reminders

Demo Agent 靶场 traffic stays on in-pod stdio proxy. Ingress `proxy.*` is the platform TCP MCP entry. Wiring Agent labs through the cluster proxy is a later change.
