# Native Tool Integration (Mode A Observe / Mode B Enforce)

> In one line: `virbius-integrate` provides **Mode A (observe)** — "visible, auditable, inventoriable"; when you need **enforcement**, add **Mode B** (SDK pre-execution precheck or the MCP proxy). Recommended flow: **start with A, then layer B onto risky tools.**

This document covers both integration paths for LangChain native tools, coverage classification and warnings, ingest/query endpoints, storage and retention, and observability boundaries. Deployment details for the proxy and SDK live in [USAGE_GUIDE.md](USAGE_GUIDE.md) §3 and [PROXY_CONFIG.md](PROXY_CONFIG.md).

---

## 1. The two modes

| | Mode A: observe (advisory) | Mode B: enforce |
|---|---|---|
| Implementation | `virbius-integrate` (LangChain `BaseCallbackHandler`) | `virbius-mcp-python` (SDK `precheck`) / `virbius-mcp-proxy` |
| Wiring | one line at construction: `callbacks=[VirbiusAuditCallback(...)]` | SDK calls `precheck_tool` at the tool boundary, or route via the proxy |
| Capability | visible, auditable, inventoriable; risk classification + recommended mode | pre-execution interception: License allowlist, args schema, Guard adjudication |
| Blocks calls | **No** (never blocks, never enforces) | **Yes** |
| Enters audit chain | No (independent channel, claim-grade) | Yes (audit / decision chain) |
| Agent code changes | one line | tool call-site changes (SDK); or zero (proxy) |

> **Terminology**: "Mode A/B" here is the **integration** axis (observe vs enforce). The "Mode A/B" in [DEPLOYMENT.md](DEPLOYMENT.md) §8.3 is the **deployment** axis (Sidecar vs remote). They are independent — do not conflate them.

---

## 2. Recommended rollout

1. **Start with Mode A**: one-line `virbius-integrate` wiring → you get the tool inventory, risk classification, observation timeline, and integration health.
2. **Watch the coverage warnings**: the console marks tools with `recommended_mode=B` but `bound_mode=A`.
3. **Layer Mode B onto risky tools**: enforce them with `virbius-mcp-python` (or the MCP proxy).
4. **Keep A**: retain A after B ships, for the audit trail and continuous inventory.

**Four points to internalize**:

- **A is not a safety net**: in the A phase, high-risk tools are still **fully executable**. The A→B gap is an unprotected window — do not leave high-risk tools on A.
- **Layer, don't switch**: A gives visibility (audit/inventory), B gives interception; they complement each other.
- **B covers A's blind spots**: A is a cooperative hook (direct `tool.func` calls and non-callback side effects are invisible); B intercepts at the execution boundary.
- **Classification is heuristic and warn-only**: use it for **prioritization**, not as an automatic switch.

---

## 3. Mode A: LangChain callback observation

### 3.1 Install and wire

Install from this repository, then wire it at agent construction:

```bash
pip install ./virbius-integrate
```

```python
import os
from virbius_integrate import VirbiusAuditCallback

agent = create_react_agent(llm, tools, callbacks=[VirbiusAuditCallback(
    app_id="crm-agent",
    tenant_id="acme",
    endpoint="http://virbius-control:8080",
    token=os.environ["VIRBIUS_INTEGRATE_TOKEN"],
    tools=tools,
)])
```

Two-point events: `on_tool_start` (`started`, emitted immediately) and `on_tool_end` / `on_tool_error` (`completed`/`error`, supplementary), with `call_id = run_id`.

### 3.2 Configuration (`IntegrateConfig`)

| Field | Default | Description |
|---|---|---|
| `app_id` / `tenant_id` | required | ingest routing keys |
| `endpoint` | required | control base URL (or `VIRBIUS_INTEGRATE_ENDPOINT`) |
| `token` | `""` | ingest token (or `VIRBIUS_INTEGRATE_TOKEN`) |
| `device_id` | `$HOSTNAME` | device identifier (L1 correlation key) |
| `strict` | `False` | synchronous startup handshake, raise on failure (default: background handshake, warn only) |
| `handshake` | `True` | startup handshake: verifies reachability/auth/`tenant/app` route |
| `handshake_deep` | `False` | deep handshake: adds an ingest round-trip (probe event written and read back) |
| `sample_rate` | `1.0` | sampling rate in `[0,1]` |
| `heartbeat_interval` | `60.0` | heartbeat interval (seconds) |
| `batch_size` / `flush_interval` | `50` / `0.5` | batch size and flush interval |
| `queue_size` | `512` | ingest queue capacity |
| `max_repr` | `2048` | bounded length for args/result previews |
| `record_args` | `True` | record argument previews |
| `spill` | `False` | local disk buffering (off by default) |
| `mode` | `"observe"` | observation mode |

Environment: `VIRBIUS_INTEGRATE_ENDPOINT`, `VIRBIUS_INTEGRATE_TOKEN` (same value as control's `integrate.ingest.token`).

### 3.3 Tool inventory and classification

```python
from virbius_integrate import report_tools, classify_tool

report_tools(tools, config, mcp_names={"read_file": "mcp.read_file"})  # report inventory (with classification)
classify_tool("delete_records", "irreversible delete", ["sql"])
# -> {"risk_hint":"high", "matched_rules":[...], "confidence":"high",
#     "recommended_mode":"B", "rule_table_version":"integrate-rules-v1"}
```

Classification: high-risk verbs / credential args / destructive description → `high`; destructive/egress/sensitive-data schema → `medium`; otherwise `low`. `recommended_mode = "B" if risk in {high, medium} else "A"`.

### 3.4 doctor (self-verification)

```bash
python -m virbius_integrate.doctor --endpoint http://virbius-control:8080 \
  --app-id crm-agent --tenant acme --token $VIRBIUS_INTEGRATE_TOKEN
```

Detects "callback not registered / endpoint unreachable / invalid token" wiring problems (it **cannot** stop a deliberately removed hook).

### 3.5 Session labeling

`session(session_id)` / `current_session()`: tag observation events with `session_id` (thread_id) for per-session timeline aggregation.

### 3.6 Startup handshake and first-flush log

- **Startup handshake**: on wiring, verifies `endpoint` reachability + `token` validity + `tenant/app` route, and logs the outcome. `strict=True` runs it **synchronously** and raises `VirbiusIntegrateError` on failure (surfaced at startup); the default runs it in the **background** and only warns without blocking. `handshake_deep=True` additionally does an ingest round-trip (a probe event with `source=langchain-healthcheck` written and read back).
  ```
  INFO  virbius-integrate handshake ok: app=crm-agent tenant=acme control=http://… token=valid ingest=unverified (23ms)
  WARN  virbius-integrate handshake FAILED stage=auth status=401 reason=invalid integrate token; events will be dropped (best-effort)
  ```
- **First-flush log**: one line after the first flush (`INFO` on success / `WARNING` on failure), then a periodic summary every heartbeat interval that only escalates to `WARNING` when data is lost; sampled-out events are reported separately as `sampled_out`.
  ```
  WARN  virbius-integrate flush summary: flushed=1024 dropped=3 delivery_failures=1 queue_depth=0 sampled_out=12
  ```
- Logging goes through Python `logging` (`logger="virbius_integrate"`): with no app logging config, WARNING+ is surfaced to stderr via `logging.lastResort` (failures visible out of the box); INFO/DEBUG stay hidden until the app configures logging.

---

## 4. Mode B: enforcement

`virbius-mcp-python` (PyO3 bindings over `virbius-core`) runs pre-execution precheck at the tool boundary — see [virbius-mcp-python/README.md](virbius-mcp-python/README.md).

```python
import json
import virbius_mcp_python as v

v.configure_rules(json.dumps({
    "offline_manifest_path": "/etc/virbius/edge-manifest.json",
    "cache_dir": "./.virbius-edge-cache",
    "app_id": "crm-agent",
}))

r = v.precheck_tool("read_file", json.dumps({"path": "/tmp/a"}), jwt, pem, "crm-agent")
if not r["allowed"]:
    raise PermissionError(r["reason"])
```

- Check order: **License allowlist → (when a `tool_policy` exists) args schema → fast-path/sandbox markers**.
- Rule source: `configure_rules` supports **offline** (local manifest) and **control/cloud** (pulled from the control plane and cached); `reload_rules()` re-pulls.
- **Zero-code option**: `virbius-mcp-proxy` (see [USAGE_GUIDE.md](USAGE_GUIDE.md) §3.1, [PROXY_CONFIG.md](PROXY_CONFIG.md)).

---

## 5. Coverage classification and warnings

- The classifier emits `recommended_mode` (high/medium→B, else A) and `bound_mode` (which path currently wires the tool; Mode A records `"A"`).
- The console's "Coverage & Inventory" flags tools with `recommended_mode=B && bound_mode=A` as **warnings (warn-only)**: "strong mode recommended but bound to lightweight mode".
- Remedy: move the tool to Mode B (SDK or proxy), or manually override the classification and record it.

---

## 6. Data and observability

### 6.1 Console

Operations console `/integrate` "Native Tool Integration": after selecting an app, three tabs — **observation timeline** (aggregated by `call_id`: `started → completed/error`, with optional B-side audited events overlaid), **coverage & inventory**, and **integration health** (device `live`/`stale`/`never-seen`).

### 6.2 Ingest channel (plugin → control, internal)

Base: `/api/v1/internal/integrate/tenants/{tenantId}/apps/{appId}`

| Method | Path | Description |
|---|---|---|
| POST | `/tool-observations/batch` | body `{ "events": [...] }`, batch ≤ `integrate.ingest.max-batch-size`; returns `{ "accepted": N }` |
| POST | `/tool-inventory` | body `{ "tools": [...] }`; returns `{ "accepted": N }` |
| POST | `/heartbeat` | body `{ device_id, package_version, mode, dropped_total, ts }` |
| GET | `/tool-observations` | query (`call_id`/`since`/`until`/`limit`) |
| GET | `/liveness` | device liveness |
| GET | `/coverage` | coverage list + warnings |

Auth: `Authorization: Bearer <token>` or `X-Virbius-Integrate-Token`; control-side `integrate.ingest.token` empty means no auth.

### 6.3 Query channel (console / admin)

Base: `/api/v1/admin/tenants/{tenantId}/integrate`: `GET /apps`, `GET /apps/{appId}/observations`, `GET /apps/{appId}/coverage`, `GET /apps/{appId}/liveness`.

### 6.4 Storage and retention

Three independent tables (migration `V12__tool_observation.sql`):

- `tb_tool_observation`: two-point events (`event_id` dedup key, `call_id` linkage, `phase ∈ {started,completed,error}`).
- `tb_tool_inventory`: tool inventory (with `risk_class`, `recommended_mode`, `bound_mode`).
- `tb_obs_app_liveness`: device heartbeat / last event / drop counters.

Timestamps are RFC3339 strings (matching the audit path; supports lexical range queries).

| Config | Default | Description |
|---|---|---|
| `integrate.ingest.token` | empty | ingest auth token (empty = no auth) |
| `integrate.ingest.max-batch-size` | 100 | max events per batch |
| `integrate.retention-days` | 14 | observation retention (days) |
| `integrate.retention.cron` | `0 0 3 * * *` | retention job cron |
| `integrate.liveness.heartbeat-interval-secs` | 60 | heartbeat liveness threshold (×2) |
| `integrate.liveness.event-window-secs` | 300 | event liveness window |

Liveness states: `live` (heartbeat ≤ 2× interval or last event ≤ window), `stale`, `never-seen`.

---

## 7. Limitations and trust boundary (Mode A)

- **Pure observation**: never blocks, never enforces.
- **Claim-grade**: events are produced by the observed process itself; a compromised process can forge/suppress them. The token only verifies "who may write", not "who it is".
- **No chain integrity**: not in the Hash Chain, not in session risk, not an input to authoritative aggregation or block decisions.
- **Cooperative coverage**: `callbacks=False`, unregistered config, direct `tool.func` calls, non-tool side effects, and spawned subprocesses are **invisible**.
- **Best-effort delivery**: queue of 512; on overflow it drops `completed/error` first and keeps `started`; drop counters ride the envelope and are visible in doctor/console.
- **Classification is advisory**: false positives need human override; enforcement use requires human confirmation.

Full boundaries in [virbius-integrate/LIMITATIONS.md](virbius-integrate/LIMITATIONS.md).

---

## 8. Related docs

- Modules: [virbius-integrate/README.md](virbius-integrate/README.md), [virbius-mcp-python/README.md](virbius-mcp-python/README.md)
- Deployment / proxy: [USAGE_GUIDE.md](USAGE_GUIDE.md) §3, [PROXY_CONFIG.md](PROXY_CONFIG.md)
- Architecture: [ARCHITECTURE.md](ARCHITECTURE.md) §2.6
- Protocol: [PROTOCOL.md](PROTOCOL.md)
