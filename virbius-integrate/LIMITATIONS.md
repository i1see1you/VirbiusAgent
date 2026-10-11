# LIMITATIONS — virbius-integrate (P0)

> 透明即特性。本文列出本插件**不做**的事，接入前请据此做架构决策。

## 覆盖边界

- 仅覆盖经注册 callback 的工具调用：直调 `tool.func`、节点内非工具副作用、spawn 的子进程 → **不可见**
- 协作式钩子：`callbacks=False` / 未注册 config → 无事件；doctor 能检出"未注册"，拦不住故意移除

## 审计语义

- **claim 级**：事件由被观测进程自身生成，进程被攻陷时可伪造/抑制；token 只校验"谁能写"，不证明"是谁"
- **无链完整性**：不进 Hash Chain（守护测试保证 `tb_audit_events`/`tb_audit_chain_state` 零写入）
- **attempt 语义**：`started` 在 ≤500ms 批量窗内发出；进程死于窗内该次调用仍会丢（计入丢弃可见口径之外）
- **记录 flush 时点快照**：args/result 为有界捕获字符串（可变对象在 flush 前被修改时，记录的不是调用瞬时值）
- **记录尝试，不保证结果**：`completed` 只说明调用返回，不断言外部世界成功

## 投递

- **best-effort**（对齐平台审计语义）：队列 512，满时优先丢 `completed/error`、保 `started`，丢弃计数随包络上报并在 doctor/控制台可见
- **无持久中间缓冲**：不经过 Kafka；control 重启会丢失其进程内缓冲；本地 spill 默认关闭（`spill=False` 可开）
- `strict=True`（opt-in）**同步**握手（可达 + 鉴权 + 路由；`handshake_deep=True` 追加摄取往返）失败即 raise；默认后台握手 + 大声告警，不阻断业务
- **启动握手与首帧摘要日志是进程内信号，不是投递保证**：握手成功只说明"此刻端到端通"，之后仍可能因网络/control 故障丢事件（best-effort）

## 信任边界

- 事件**永不**作为 session 风险、权威聚合、阻断决策的输入（契约由 control 侧守护测试固化）
- `trace_id` 为 advisory 键，仅用于展示拼接
- 控制台 B 道叠加继承审计抽样策略（allow 默认部分入库），低危 B 放行不保证同屏

## 分级器

- 规则版分级为 **advisory**：输出带 confidence 与 matched_rules，误报需人工 override；任何执法用途（钉死）必须先经人工确认（P1）

## 性能

- 回调热路径工作 = 一次有界截断 + 一次入队；实测（`benchmarks/bench_callback.py`，Python 3.11，本机）：**p50 ≈ 4.3µs / p95 ≈ 4.7µs 每次 handler 调用**（含参数有界截断），接入前请以自己的环境实测为准
- 掩码/序列化/截断全部在后台 flush 线程执行（不进热路径）
