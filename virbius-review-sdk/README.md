# virbius-review-sdk

VirbiusAgent 官方审查 SDK（Python）——在**调用方侧**完成输入前置审查、输出后置审查与流式滑动窗口审查，全部经引擎 `POST /v1/evaluate`。

引擎侧契约见 PROTOCOL §2.6.5（role 副作用矩阵：`input` 喂会话风险/零工具副作用；`output` 零副作用并返回 `disposal_action`）。**双审二选一**：用了本 SDK 就不要再把同一内容走引擎 chat 代理或重复自审。

## 安装

```bash
pip install .                # 同步客户端（requests）
pip install '.[async]'       # + 异步客户端（httpx）
```

## 三步接入

```python
from virbius_review import ReviewClient

client = ReviewClient(
    "http://virbius-engine:8082",
    fail_open=True,                 # 引擎不可达时放行（False 则抛 ReviewError）
    window_chars=200, context_chars=50,
    refusal_text="抱歉，该内容已被安全策略拦截。",
)

# ① 输入前置（调 LLM 之前；命中即不要发给模型）
v = client.review_input(user_msg, tenant_id="default",
                         session_id=sid, trace_id=tid, user_id=uid)
if v.blocked:
    return refusal()

answer = call_your_llm(messages)          # 任意 provider，调用方直连

# ② 输出后置（非流式；拿到完整回答）
v = client.review_output(answer, session_id=sid, trace_id=tid)
final = client.apply_disposal(answer, v)  # reject→拒答文案 / omit→空 / redact→脱敏 / warn→原文
```

## 流式输出审查

```python
from virbius_review.adapters import from_openai_sse   # 或 from_ollama_ndjson

resp = requests.post(llm_url, json={...,"stream":True}, stream=True)
lines = (line.decode() for line in resp.iter_lines())
deltas = from_openai_sse(lines)

for ev in client.wrap_stream(deltas, session_id=sid, trace_id=tid):
    match ev:
        case Delta(text):  send(text)                   # 只含已过审内容（不含重叠）
        case Blocked(rc):  resp.close(); break          # 断上游流省 token，向用户发违规提示
        case Done:         finish()
```

窗口算法与引擎 `StreamReviewWindow` **同一套测试向量**（`schemas/review-window-vectors.json`）钉死，永不漂移。

## 异步客户端

```python
async with AsyncReviewClient("http://virbius-engine:8082") as client:
    v = await client.review_input_async(msg, session_id=sid, trace_id=tid)
    async for ev in client.wrap_stream_async(delta_aiter, session_id=sid, trace_id=tid):
        ...
```

## 配置（调用方侧）

| 参数 | 默认 | 说明 |
|---|---|---|
| `engine_url` | `http://127.0.0.1:8082` | 引擎地址 |
| `fail_open` | `True` | 引擎不可达/超时：放行（带 `error` 标记）或抛 `ReviewError`（**429/限流不受此开关控制，恒 fail-closed**） |
| `window_chars` / `context_chars` | `200` / `50` | 流式窗口硬上限 / 上下文重叠 |
| `stream_first` | `False` | 先发后审：延迟低，**已发 token 不可撤回**（默认先审后发） |
| `refusal_text` | 抱歉… | `apply_disposal` 对 `reject` 的替换文案 |
| `timeout` | `30` | 单次 evaluate 超时（秒） |

## 行为要点

- **429 处理**：命中引擎限流 → **恒抛 `ReviewError`（fail-closed），无放行开关**——限流是人为节流而非故障，fail-open 会让恶意刷限流的调用方把后续内容全部未审放行。
- **自定义 transport 契约**：429 必须返回引擎的 JSON body（`{"error":"rate_limited",...}`）而不是抛异常——抛出的 429 无法与传输故障区分，会落入 `fail_open` 策略。默认 requests/httpx 传输已按此处理。
- **契约严格化**：`role=output` 响应缺 `disposal_action` 即抛 `ReviewError`（引擎与 SDK 同仓同版，不存在版本偏斜）；本地 fail-open 合成判定除外（推导为 warn/reject）。
- **trace 贯通**：`trace_id/session_id/user_id` 贯穿 input、每窗、output——审计/统计按回答聚合依赖它。
- **规则寻址**：控制台里绑 `tool_names:["chat_input"]` 仅输入生效，`["chat_completion"]` 仅输出生效。

## 测试

```bash
PYTHONPATH=src python3 -m unittest discover -s tests
```
