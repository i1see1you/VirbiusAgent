# Agent 风险规则配置示例

本文档通过 12 条典型规则,演示 Virbius Agent 风险规则的全生命周期配置。所有操作均在 Virbius 运营台完成。

## 规则类型概览

| 类型 | 适用层 | 评估位置 | 示例 |
|------|--------|----------|------|
| 云侧 Groovy | 云侧 | Engine 引擎 | 敏感实体查询、批量拉取、越权检查 |
| Agent 侧 Groovy | Agent 端 | Agent 运行时 | 实体类型探测、会话风险累积 |
| Edge DLP | 边缘网关 | Edge 网关 | 身份证脱敏 |
| Falco 系统调用 | 内核层 | Falco 守护进程 | /etc 敏感文件读取 |
| LLM Prompt | 云侧 | LLM 服务 | Prompt 安全分类 |

## 前置条件

在配置规则前,需要先设置好以下基础数据。

### 1. 创建数据列表

用于名单匹配,例如维护一份政治敏感人物名单:

1. 在运营台左侧导航栏点击 **名单**
2. 先填写以下字段,然后点击 **新建名单**
   - **名单**: `political_sensitive_list`
   - **维度**: 选择 `var（逻辑变量）`,输入 `entity_value`
   - **备注**: 政治敏感人物名单
3. 名单创建后,在列表中选择刚创建的名单
4. 在 **值** 输入框中依次输入 `person_x`、`person_y`、`person_z`,每次输入后点击 **添加明细**
5. 也可在 `keyword`、`user_id`、`device_id`、`ip_cidr` 等维度下创建名单

> **提示**: `var:xxx` 中的 `xxx`（如 `entity_value`）只是逻辑标识，不影响匹配逻辑，可以随便填写。但建议使用有意义的名称（如 `entity_value`、`sensitive_person` 等），便于理解和维护。

### 2. 创建累积计数器

用于频率限制,例如限制每分钟查询次数:

1. 在运营台左侧导航栏点击 **累计**
2. 点击 **新建累计**
3. 填写以下字段:
   - **名称**: `user_query_1m`
   - **维度**: 选择 `session_id`
   - **时间窗口**: `滚动`,时长 `1` 分钟
4. 点击 **保存定义**

### 3. 注册工具

用于配置工具的审批模式(影响挑战审批后的豁免行为):

1. 在运营台左侧导航栏点击 **工具注册**
2. 点击 **新建工具**
3. 填写以下字段:
   - **tool_name**: `query_audit_events`
   - **risk_class**: `low`
   - **sandbox_type**: `none`
   - **timeout_ms**: `30000`
   - **approval_mode**: 选择 **弱审批（不校验参数）**
4. 点击 **保存**

**审批模式说明**:
| 模式 | 行为 | 适用场景 |
|------|------|----------|
| **强审批（参数一致）**(默认) | 仅工具参数完全一致时才豁免 | 精确匹配,安全性高 |
| **弱审批（不校验参数）** | 只要工具名相同,任何参数都豁免 | LLM 生成的参数有微小差异时仍可豁免 |

### 4. 定义 MCP Tool

MCP Tool 定义了 Agent 可以调用的工具及其参数。工具名称和参数名会在规则中通过 `args.` 前缀访问。

```python
@mcp.tool()
def query_audit_events(
    tenant_id: str,
    entity_type: str,
    entity_value: str,
    time_start: Optional[str] = None,
    time_end: Optional[str] = None,
    limit: int = 100,
) -> str:
    """查询 user/device 在指定时间段的审计事件并聚合统计。"""
```

**工具名称来源**:
- 函数名 `query_audit_events` 就是 MCP tool 的名称
- `@mcp.tool()` 装饰器将 Python 函数注册为 MCP tool
- 参数名（`entity_type`, `entity_value`, `limit` 等）通过函数的参数签名定义
- 规则中通过 `ctx.var('args.entity_type')` 等方式访问这些参数

## 规则配置示例

### 1. 敏感实体查询 → 挑战审批

当用户查询 VP(副总裁)、CEO、CFO 等敏感高管信息时,触发人工审批。

**配置步骤**:
1. 进入运营台 **风险规则管理** 页面
2. 点击 **创建规则**
3. 填写规则配置:

| 字段 | 填写值 | 说明 |
|------|--------|------|
| 规则 ID | `sensitive_entity_challenge` | 全局唯一标识 |
| 适用层 | 云侧 | 在 Engine 端执行 |
| 执行引擎 | Groovy | Groovy 脚本 |
| 原因码 | `SENSITIVE_ENTITY` | 命中时的标识码 |
| 风险分数 | 40 | 越低表示越温和 |
| 处置动作 | 挑战审批 | 命中时创建审批任务 |
| 作用域 | tool | 按工具名称匹配 |
| 规则脚本 | 见下方 | 判定逻辑 |

**规则脚本**:
```groovy
def decide(ctx) {
    def entityType = ctx.var('args.entity_type')
    return entityType in ['VP', 'CEO', 'CFO', 'CISO', 'DIRECTOR', 'BOARD_MEMBER']
}
```

**说明**: `ctx.var('args.entity_type')` 从工具调用的参数中读取 `entity_type` 字段，检查是否为敏感高管职位。Tool 参数通过 `args.` 前缀访问，避免与系统变量冲突。

---

### 2. 政治敏感人物查询 → 拒绝

结合已创建的数据列表,直接拒绝查询敏感人物。

**配置**:

| 字段 | 填写值 |
|------|--------|
| 规则 ID | `political_sensitive_deny` |
| 适用层 | 云侧 |
| 执行引擎 | Groovy |
| 原因码 | `POLITICAL_SENSITIVE` |
| 风险分数 | 100 |
| 处置动作 | 拒绝 |
| 作用域 | tool | 按工具名称匹配 |

**规则脚本**:
```groovy
def decide(ctx) {
  return ctx.listMatch('political_sensitive_list', ctx.var('args.entity_value'))
}
```

**说明**: `ctx.listMatch(列表名, 值)` 检查值是否在数据列表中。注意使用 `args.` 前缀访问 tool 参数。

---

### 3. 批量数据拉取 → 挑战审批

当单次查询的 limit 参数超过 100 时触发审批。

**规则脚本**:
```groovy
def decide(ctx) {
  def limit = ctx.var('args.limit')
  if (limit == null) return false
  try { return Integer.parseInt(limit) > 100 }
  catch (Exception e) { return false }
}
```

**说明**: 从参数中读取 `limit` 值,超过 100 则触发挑战。注意使用 `args.` 前缀访问 tool 参数。

---

### 4. 高频查询限流 → 拒绝

利用累积计数器,同一会话 1 分钟内查询超过 3 次则拒绝。

**规则脚本**:
```groovy
def decide(ctx) {
  return ctx.getCumulative('user_query_1m') > 3
}
```

**说明**:
- `ctx.getCumulative('user_query_1m')` 读取计数器的当前值(该会话 1 分钟内已发起的查询次数)
- 计数器在每次评估后自动 +1,无需手动管理
- 阈值设为 > 3,即 4 次触发拒绝

---

### 5. 跨实体类型探测 → 挑战审批 (Agent 端)

该规则在 **Agent 端执行**,通过分析会话历史,检测用户是否在连续查询不同的实体类型。

**配置注意**: 适用层选 **Agent 端**,执行引擎选 **Agent-Groovy**

**规则脚本**:
```groovy
def decide(ctx) {
  def types = ctx.sessionHistory(10).collect{
    // Agent 端 session history 中 args 已解析为 Map
    it.args?.entity_type ?: ''
  }.unique().findAll{ it != '' }
  return types.size() >= 3
}
```

**说明**: `ctx.sessionHistory(10)` 获取当前会话最近 10 次工具调用记录,提取其中不重复的实体类型（`entity_type`），超过 3 种则触发。

> **注意**: 此规则为 Agent-Groovy 类型，在 Agent 端执行。Agent 端的 `it.args` 已解析为 Map 对象，可直接通过 `it.args?.entity_type` 访问。如果在云侧 Groovy 中使用 session history，`args` 为 JSON 字符串，需先解析。

---

### 6. 越权查询 → 挑战审批

检查用户是否在查询不属于自己的数据。

**规则脚本**:
```groovy
def decide(ctx) {
  def uid = ctx.var('user_id')
  def ev = ctx.var('args.entity_value')
  return ev != null && uid != null && ev != uid
}
```

**说明**: 比较 `user_id`(系统变量,当前登录用户)和 `args.entity_value`(tool 参数,查询目标),不一致则判定为越权。

---

### 7. 会话风险累积 → 挑战审批 (Agent 端)

Agent 端规则,当会话风险分数超过阈值时触发。

**规则脚本**:
```groovy
def decide(ctx) {
  return ctx.sessionRiskScore() > 60
}
```

**说明**: 风险分数由之前的多次评估累积,范围 0-100。每次触发规则或检测到异常行为都会增加分数。

---

### 8. 信任边界泄漏 → 拒绝

检测工具调用内容是否包含敏感信息(如密码)。

**规则脚本**:
```groovy
def decide(ctx) {
  return ctx.var('content')?.contains('password=') == true
}
```

**说明**: 检查 `content` 字段（系统变量）是否包含 `password=` 字符串，是则阻断。注意 `content` 是系统变量，不需要 `args.` 前缀。

---

### 9. DLP 身份证脱敏 → 放行 (Edge 层)

边缘网关层规则,用于识别身份证号并进行脱敏。注意:此规则在 Edge 层执行,不在 Engine 中评估。

**配置注意**: 适用层选 **边缘网关**,执行引擎选 **DLP DSL**

**规则 DSL 配置**:
```json
{"entity_type": "idcard_cn", "action": "mask"}
```

**说明**: 识别中国身份证号并执行掩码脱敏,不阻断请求。

---

### 10. Prompt 安全分类 → 挑战审批 (LLM)

调用 LLM 服务对用户输入进行安全分类,检测 prompt 注入等攻击。

**配置注意**: 执行引擎选 **Prompt**

**说明**: 规则脚本无需填写。系统自动将用户输入发送给 qwen3guard LLM 服务进行分类,高风险内容触发挑战。

---

### 11. Falco 系统调用检测 → 拒绝

通过 Falco 内核模块检测容器内是否读取了 `/etc/passwd`、`/etc/shadow` 等敏感文件。

**配置注意**: 适用层选 **Falco**,执行引擎选 **Falco 规则**

**规则 Falco 配置**:
```json
{
  "condition": "open_file and (fd.name contains /etc/passwd or fd.name contains /etc/shadow or fd.name contains /etc/ssh/ or fd.name contains /etc/sudoers)",
  "output": "Sensitive file read detected (file=%fd.name user=%user.name)",
  "tags": ["filesystem", "mitre_persistence"]
}
```

**说明**: Falco 通过内核事件监控文件打开操作,匹配到敏感文件路径时通过 Webhook 推送到 Engine 进行阻断。

---

### 12. 工具高频切换探测 → 挑战审批

检测会话中是否在短时间内频繁切换不同工具(如依次调用查用户、HTTP GET、写文件)。

**规则脚本**:
```groovy
def decide(ctx) {
  def tools = ctx.sessionHistory(5).collect{ it.tool_name ?: '' }.unique().findAll{ it != '' }
  return tools.size() >= 2
}
```

**说明**:
- `ctx.sessionHistory(5)` 获取最近 5 条工具调用记录
- 统计其中不重复的工具名
- 阈值使用 `>= 2` 而非 `>= 3`,因为当前工具调用在规则评估后才记录到历史中

## 规则生命周期管理

### 创建规则

在运营台 **风险规则管理** 页面:

1. 点击 **创建规则**
2. 按上文的配置表填写各字段
3. 在脚本编辑器中粘贴规则脚本
4. 点击 **保存**(此时规则状态为"草稿")

### 激活规则

1. 在规则列表中找到刚创建的规则
2. 点击 **激活**
3. 规则状态变为"生效中"(dry_run 模式)

### 设置执行模式

**注意**: 不能直接从 dry_run 切换为 full。推荐使用灰度发布:

1. 在规则详情页点击 **运行配置**
2. 执行模式选择 **灰度**
3. 灰度比例设为 **100%**
4. 保存

此时规则将在所有请求中执行完整逻辑(挑战/拒绝)。

### 发布到引擎

1. 在运营台顶部点击 **发布快照**
2. 确认发布
3. 系统会将当前所有已激活的规则推送到 Engine 缓存

### 重启引擎后的处理

如果 Engine 服务重启,由于缓存机制原因,需要**重新发布快照**:

1. Engine 启动后等待 5 秒
2. 在运营台再次点击 **发布快照**
3. 确认发布

## 评估请求格式

当用户通过 Agent 调用工具时,系统自动构造评估请求。运营台无需关注请求格式,此处仅为理解工作原理提供参考。

请求包含的信息: 租户 ID、会话 ID、工具名称、工具参数、用户输入内容、用户身份标识等。

评估后返回: 处置动作(允许/挑战/拒绝)、命中的规则、风险分数、挑战 ID(如有)。

## 挑战审批流程

当规则命中且处置动作为"挑战审批"时,会创建一个审批任务。

### 查看待审批任务

1. 进入运营台 **审批管理** 页面
2. 可看到所有待审批的挑战列表
3. 每个挑战包含:工具名称、参数、风险分数、创建时间

### 严格模式(默认)

1. 点击挑战的 **审批** 按钮
2. 填写审批意见
3. 点击 **通过**
4. 生成一次性的验证令牌
5. **效果**: 该会话中,相同工具的相同参数再次调用时,自动豁免

### 宽松模式

前提: 在工具注册时已将该工具的审批模式设为"宽松模式"

1. 审批操作与严格模式相同
2. **效果**: 该会话中,相同工具的任何参数调用都会豁免,容忍 LLM 生成参数的微小差异

### 重要说明

- 如果拒绝规则(处置动作=拒绝)或频率限制先命中,则不会进入挑战审批流程
- 未注册的工具默认为严格模式
- 要在宽松模式下工作,必须先注册工具并选择宽松模式

## 规则优先级

当同一个请求命中多条规则时,按以下优先级决定最终处置:

1. **拒绝** — 优先级最高,只要有一条规则判定拒绝,直接阻断
2. **挑战审批** — 次之,如果没触发拒绝,则创建审批任务
3. **放行** — 默认状态,无规则命中时放行

**示例**: 一个请求同时触发了批量查询挑战(风险分35,挑战)和频率限制(风险分80,拒绝),最终结果为**拒绝**。

## 已知限制

| 规则类型 | 限制 | 说明 |
|---------|------|------|
| Agent-Groovy | 不在 Engine 缓存中 | 只发布到 Agent 端,运营台无法直接验证 |
| Edge DLP | Engine 不评估 | 在边缘网关层独立执行 |
| Falco 规则 | Engine 不主动评估 | Falco 通过 Webhook 推送告警 |
| Prompt LLM | 需要 LLM 服务 | 依赖 qwen3guard 外部服务,如不可用则降级为放行 |

## 附录: Groovy 脚本参考

### `ctx` 可用方法

| 方法 | 返回类型 | 说明 |
|------|----------|------|
| `ctx.var(name)` | 字符串 | 读取变量值。系统变量如 `ctx.var('user_id')`、`ctx.var('content')`；Tool 参数使用 `args.` 前缀如 `ctx.var('args.entity_type')` |
| `ctx.listMatch(name)` | 布尔 | 检查当前请求是否匹配某数据列表 |
| `ctx.listMatch(name, value)` | 布尔 | 检查指定值是否在数据列表中 |
| `ctx.getCumulative(name)` | 数字 | 读取累积计数器的当前值 |
| `ctx.sessionHistory(n)` | 列表 | 读取当前会话最近 n 条工具调用记录 |
| `ctx.sessionRiskScore()` | 数字 | 当前会话风险分数(0-100) |
| `ctx.toolCallCount(name)` | 数字 | 当前会话中某工具的调用总次数 |
| `ctx.isInternalHost(url)` | 布尔 | 判断 URL 是否为内网地址 |
| `ctx.tenantId()` | 字符串 | 当前租户 ID |
| `ctx.sessionId()` | 字符串 | 当前会话 ID |
| `ctx.currentRuleId()` | 字符串 | 当前规则 ID |
| `ctx.wouldHitBlock()` | 布尔 | 是否有信号已达到阻断阈值 |
| `ctx.inCanaryBucket(key, pct)` | 布尔 | 灰度分组判断 |

### 脚本编写注意事项

- 脚本必须定义 `def decide(ctx)` 函数,返回 `true`(命中)或 `false`(未命中)
- 所有 `ctx` 方法均为只读,不能修改状态
- 脚本执行超时限制为 50ms,建议逻辑尽量简单
- 单字段条件用 `==`,列表包含用 `in`,空值判断用 `== null`
- 参数值默认都是字符串类型,数值比较需要解析: `Integer.parseInt(value)`
