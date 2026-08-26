# Agent Risk Rule Configuration Examples

This document demonstrates the full lifecycle of Virbius Agent risk rule configuration through 12 typical examples. All operations are performed in the Virbius Admin Console.

## Rule Type Overview

| Type | Layer | Evaluation Location | Example |
|------|-------|-------------------|---------|
| Cloud Groovy | Cloud | Engine | Sensitive entity query, bulk fetch, cross-user check |
| Agent Groovy | Agent | Agent runtime | Entity type probing, session risk escalation |
| Edge DLP | Edge Gateway | Edge gateway | ID card masking |
| Falco Syscall | Kernel | Falco daemon | /etc sensitive file read |
| LLM Prompt | Cloud | LLM service | Prompt safety classification |

## Prerequisites

Before configuring rules, set up the following foundational data.

### 1. Create Data List

Used for allowlist/blocklist matching, e.g., a list of politically sensitive persons:

1. Navigate to **Data List Management** in the Admin Console
2. Click **Create Data List**
3. Fill in:
   - **List Name**: `political_sensitive_list`
   - **Dimension**: Select `var:logical` (logical variable matching)
   - **Description**: Politically sensitive persons
4. After creation, click **Add Entries** in the list detail page
5. Enter: `person_x`, `person_y`, `person_z`, save

**Supported dimensions**: `keyword`, `user_id`, `device_id`, `ip_cidr`, `var:logical`

> **Note**: The `xxx` in `var:xxx` (e.g., `entity_value`) is just a logical identifier and does not affect matching logic. You can use any name, but it's recommended to use meaningful names (e.g., `entity_value`, `sensitive_person`) for better readability and maintainability.

### 2. Create Cumulative Counter

Used for rate limiting, e.g., queries per minute:

1. Navigate to **Cumulative Counter Management** in the Admin Console
2. Click **Create Counter**
3. Fill in:
   - **Counter Name**: `user_query_1m`
   - **Dimension**: Select `session_id` (per session)
   - **Time Window**: `Rolling`, 1 minute
   - **Status**: Enabled
4. Save

**Supported dimensions**: `user_id`, `device_id`, `ip`, `session_id`, `keyword`, `var:logical`
**Supported time windows**: `Rolling`, `Calendar Day`

### 3. Register Tool

Configures the tool's approval mode (affects exemption behavior after challenge approval):

1. Navigate to **Tool Registration** in the Admin Console
2. Click **Register Tool**
3. Fill in:
   - **Tool Name**: `query_audit_events`
   - **Risk Level**: Low
   - **Approval Mode**: Select **Lax Mode**
4. Save

**Approval Modes**:

| Mode | Behavior | Use Case |
|------|----------|----------|
| **Strict** (default) | Exemption only when tool args match exactly | High security, precise matching |
| **Lax** | Exemption for any args of the same tool | Tolerates minor LLM-generated arg variations |

## Rule Configuration Examples

### 1. Sensitive Entity Query → Challenge

Triggers human approval when querying sensitive executive types (VP, CEO, CFO, etc.).

**Configuration Steps**:
1. Navigate to **Risk Rule Management** in the Admin Console
2. Click **Create Rule**
3. Fill in the rule configuration:

| Field | Value | Description |
|-------|-------|-------------|
| Rule ID | `sensitive_entity_challenge` | Globally unique identifier |
| Layer | Cloud | Executed on the Engine side |
| Runtime | Groovy | Groovy script |
| Reason Code | `SENSITIVE_ENTITY` | Identifier code on match |
| Risk Score | 40 | Lower = milder action |
| Action | Challenge | Create approval task on match |
| Scope | Tool | Matches by tool name |
| Rule Script | See below | Decision logic |

**Rule Script**:
```groovy
def decide(ctx) {
  def et = ctx.var('args.entity_type')
  return et in ['VP','CEO','CFO','CISO','DIRECTOR','BOARD_MEMBER']
}
```

**Note**: `ctx.var('args.entity_type')` reads the `entity_type` field from tool call parameters. Tool parameters are accessed with the `args.` prefix to avoid conflicts with system variables.

---

### 2. Politically Sensitive Query → Deny

Directly blocks queries on politically sensitive persons using a data list.

**Configuration**:

| Field | Value |
|-------|-------|
| Rule ID | `political_sensitive_deny` |
| Layer | Cloud |
| Runtime | Groovy |
| Reason Code | `POLITICAL_SENSITIVE` |
| Risk Score | 100 |
| Action | Deny |
| Scope | Tool |

**Rule Script**:
```groovy
def decide(ctx) {
  return ctx.listMatch('political_sensitive_list', ctx.var('args.entity_value'))
}
```

**Note**: `ctx.listMatch(listName, value)` checks if a value exists in a data list. Use `args.` prefix for tool parameters.

---

### 3. Bulk Data Fetch → Challenge

Triggers approval when the `limit` parameter exceeds 100.

**Rule Script**:
```groovy
def decide(ctx) {
  def limit = ctx.var('args.limit')
  if (limit == null) return false
  try { return Integer.parseInt(limit) > 100 }
  catch (Exception e) { return false }
}
```

**Note**: Read `limit` from tool parameters using `args.` prefix. Trigger challenge if exceeds 100.

---

### 4. High-Frequency Query Rate Limit → Deny

Uses a cumulative counter to block requests exceeding 3 calls within 1 minute in the same session.

**Rule Script**:
```groovy
def decide(ctx) {
  return ctx.getCumulative('user_query_1m') > 3
}
```

**Notes**:
- `ctx.getCumulative('user_query_1m')` reads the current counter value (queries in the last 1 minute for this session)
- The counter auto-increments after each evaluation
- Threshold > 3 means the 4th call triggers denial

---

### 5. Cross-Entity Type Probe → Challenge (Agent-side)

Executes on the **Agent side**. Detects if a user is querying different entity types in succession (e.g., person name → domain → IP address).

**Configuration note**: Set Layer to **Agent**, Runtime to **Agent-Groovy**.

**Rule Script**:
```groovy
def decide(ctx) {
  def types = ctx.sessionHistory(10).collect{
    // In Agent-side, args in session history is already parsed as Map
    it.args?.entity_type ?: ''
  }.unique().findAll{ it != '' }
  return types.size() >= 3
}
```

**Note**: `ctx.sessionHistory(10)` retrieves the last 10 tool call records, extracts unique entity types, triggers when 3+ different types are found.

> **Note**: This is an Agent-Groovy rule that executes on the Agent side. In Agent-side session history, `it.args` is already parsed as a Map object, so you can access it directly via `it.args?.entity_type`. If using session history in cloud-side Groovy, `args` is a JSON string and needs to be parsed first.

---

### 6. Cross-User Query → Challenge

Checks if a user is querying data that does not belong to them.

**Rule Script**:
```groovy
def decide(ctx) {
  def uid = ctx.var('user_id')
  def ev = ctx.var('args.entity_value')
  return ev != null && uid != null && ev != uid
}
```

**Note**: Compares `user_id` (system variable, current logged-in user) with `args.entity_value` (tool parameter, query target). Mismatch = unauthorized query.

---

### 7. Session Risk Escalation → Challenge (Agent-side)

Agent-side rule that triggers when the session risk score exceeds a threshold.

**Rule Script**:
```groovy
def decide(ctx) {
  return ctx.sessionRiskScore() > 60
}
```

**Note**: The risk score accumulates from previous evaluations, ranging from 0 to 100. Each triggered rule or detected anomaly increases the score.

---

### 8. Trust Boundary Leak → Deny

Detects sensitive information (e.g., passwords) in tool call content.

**Rule Script**:
```groovy
def decide(ctx) {
  return ctx.var('content')?.contains('password=') == true
}
```

**Note**: Checks if the `content` field contains `password=`; if so, blocks the request.

---

### 9. DLP ID Card Masking → Allow (Edge Layer)

Edge gateway rule for identifying Chinese ID numbers and masking them.

**Configuration note**: Set Layer to **Edge Gateway**, Runtime to **DLP DSL**.

**Rule DSL Configuration**:
```json
{"entity_type": "idcard_cn", "action": "mask"}
```

**Note**: Identifies Chinese ID numbers and applies masking without blocking the request.

---

### 10. Prompt Safety Classification → Challenge (LLM)

Calls an LLM service to classify user input for safety (detects prompt injection, etc.).

**Configuration note**: Set Runtime to **Prompt**.

**Note**: No rule script is needed. The system automatically sends user input to the VirbiusGuard LLM service for classification. High-risk content triggers a challenge.

---

### 11. Falco Syscall Detection → Deny

Detects reads of sensitive files (`/etc/passwd`, `/etc/shadow`, etc.) within containers via the Falco kernel module.

**Configuration note**: Set Layer to **Falco**, Runtime to **Falco Rule**.

**Falco Configuration**:
```json
{
  "condition": "open_file and (fd.name contains /etc/passwd or fd.name contains /etc/shadow or fd.name contains /etc/ssh/ or fd.name contains /etc/sudoers)",
  "output": "Sensitive file read detected (file=%fd.name user=%user.name)",
  "tags": ["filesystem", "mitre_persistence"]
}
```

**Note**: Falco monitors file open operations via kernel events. When a sensitive file path is matched, it pushes an alert to Engine via Webhook for blocking.

---

### 12. Rapid Tool Switch Detection → Challenge

Detects frequent switching between different tools in a short period (e.g., query user → HTTP GET → write file).

**Rule Script**:
```groovy
def decide(ctx) {
  def tools = ctx.sessionHistory(5).collect{ it.tool_name ?: '' }.unique().findAll{ it != '' }
  return tools.size() >= 2
}
```

**Notes**:
- `ctx.sessionHistory(5)` retrieves the last 5 tool call records
- Counts unique tool names among them
- Threshold is `>= 2` (not `>= 3`) because the current tool call hasn't been recorded yet when the rule is evaluated

## Rule Lifecycle Management

### Create Rule

In the **Risk Rule Management** page of the Admin Console:

1. Click **Create Rule**
2. Fill in fields as described in the configuration tables above
3. Paste the rule script into the script editor
4. Click **Save** (rule status is "Draft")

### Activate Rule

1. Find the rule in the rule list
2. Click **Activate**
3. Rule status changes to "Active" (dry_run mode)

### Set Enforcement Mode

**Note**: Switching directly from `dry_run` to `full` is not supported. Use canary release instead:

1. In the rule detail page, click **Runtime Configuration**
2. Set **Enforcement Mode** to **Canary**
3. Set **Canary Percentage** to **100%**
4. Save

The rule will now execute its full logic (challenge/deny) on all requests.

### Publish to Engine

1. Click **Publish Snapshot** at the top of the Admin Console
2. Confirm the publish
3. The system pushes all active rules to the Engine cache

### After Engine Restart

If the Engine service restarts, the cache must be refreshed due to the consumption mechanism:

1. Wait 5 seconds after Engine starts
2. Click **Publish Snapshot** again in the Admin Console
3. Confirm the publish

## Evaluation Request Flow

When a user calls a tool through the Agent, the system automatically constructs an evaluation request. Admin Console operators do not need to handle request formats directly. This section is for understanding the underlying mechanism.

The request includes: tenant ID, session ID, tool name, tool arguments, user input content, user identity, etc.

The response returns: action (allow/challenge/block), matched rule, risk score, challenge ID (if applicable).

## Challenge Approval Flow

When a rule matches and the action is "Challenge", an approval task is created.

### View Pending Tasks

1. Navigate to **Approval Management** in the Admin Console
2. View all pending challenges
3. Each challenge includes: tool name, parameters, risk score, creation time

### Strict Mode (Default)

1. Click **Approve** on a challenge
2. Enter approval comment
3. Click **Approve**
4. A one-time verification token is generated
5. **Effect**: Subsequent calls with the same tool and same parameters in this session are automatically exempted

### Lax Mode

Prerequisite: The tool was registered with "Lax" approval mode.

1. The approval operation is the same as strict mode
2. **Effect**: Subsequent calls with any parameters of the same tool in this session are exempted. Tolerates minor variations in LLM-generated parameters.

### Important Notes

- If a deny rule or rate limiter fires first, the challenge approval process is skipped
- Unregistered tools default to strict mode
- Tools must be registered with lax mode selected for lax exemption to work

## Rule Priority

When a single request matches multiple rules, the final action is determined by the following priority:

1. **Deny** — Highest priority. If any rule decides to deny, the request is blocked immediately
2. **Challenge** — Secondary. If no deny rule matches, an approval task is created
3. **Allow** — Default. If no rule matches, the request is allowed

**Example**: A request triggers both `bulk_query_challenge` (risk_score=35, challenge) and `query_rate_limit_1m` (risk_score=80, deny). The final action is **block**.

## Known Limitations

| Rule Type | Limitation | Description |
|-----------|-----------|-------------|
| Agent-Groovy | Not in Engine cache | Only published to Agent side, cannot be verified via Admin Console |
| Edge DLP | Not evaluated by Engine | Executed independently at the Edge gateway layer |
| Falco Rule | Not proactively evaluated | Falco pushes alerts via Webhook |
| Prompt LLM | Requires LLM service | Depends on VirbiusGuard external service; degrades to allow if unavailable |

## Appendix: Groovy Script Reference

### Available `ctx` Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `ctx.var(name)` | String | Read a variable value. System variables: `ctx.var('user_id')`, `ctx.var('content')`. Tool parameters use `args.` prefix: `ctx.var('args.entity_type')` |
| `ctx.listMatch(name)` | Boolean | Check if the current request matches a data list |
| `ctx.listMatch(name, value)` | Boolean | Check if a value exists in a data list |
| `ctx.getCumulative(name)` | Number | Read current cumulative counter value |
| `ctx.sessionHistory(n)` | List | Retrieve the last n tool call records for the current session |
| `ctx.sessionRiskScore()` | Number | Current session risk score (0-100) |
| `ctx.toolCallCount(name)` | Number | Total call count for a specific tool in the session |
| `ctx.isInternalHost(url)` | Boolean | Check if a URL points to an internal network address |
| `ctx.tenantId()` | String | Current tenant ID |
| `ctx.sessionId()` | String | Current session ID |
| `ctx.currentRuleId()` | String | Current rule ID |
| `ctx.wouldHitBlock()` | Boolean | Check if any signal has reached the block threshold |
| `ctx.inCanaryBucket(key, pct)` | Boolean | Canary bucket grouping decision |

### Script Writing Guidelines

- The script must define a `def decide(ctx)` function, returning `true` (match) or `false` (no match)
- All `ctx` methods are read-only; they cannot modify state
- Script execution timeout is 50ms — keep logic simple
- Use `==` for single value comparison, `in` for list containment, `== null` for null checks
- Parameter values are strings by default; use `Integer.parseInt(value)` for numeric comparison
