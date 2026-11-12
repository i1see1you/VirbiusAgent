# Judge Protocol v1 (Jev `/v1/systemone`)

> Status: **implemented** (engine prompt-judge, unified 2026-11-12). All wire facts
> below were measured against the NeoHorse official runtime on 2026-11-11
> (Kaggle T4 probe, artifacts in `virbius-guard/kaggle_neohorse_probe/out_v2/`).
> Design record: `docs/memory/prompt-judge-统一清单` (internal).

## Scope

Custom judge rules (`runtime=prompt`, non-built-in rule ids). The ten built-in
`prompt-*` category rules keep the guard-LLM chat path and are **not** part of
this protocol.

## Endpoint

`POST {base-url}/v1/systemone` — the path is a **protocol constant** (identical
across NeoHorse official runtime, Ollama 0.35+, StartLux gguf server, jevalt,
Lichen, ifReflex.cpp). Topology differences belong in `base-url`. Optional
`Authorization: Bearer <token>` when the backend enables authentication.

## Request

```json
{
  "model": "NeoHorse-Jev-4B",
  "state": {"content": "<user input, verbatim>"},
  "questions": {
    "<ruleId>": {"type": "noul", "instructions": "<criterion + hit examples>"}
  }
}
```

- `model` **required** — must be a vendor alias. NeoHorse accepts:
  `NeoHorse-Jev-4B`, `TokenRhythm/NeoHorse-Jev-4B`, `neohorse-jev`,
  `NeoHorse-JEV-4B`, `TokenRhythm/NeoHorse-JEV-4B`. Unknown values → 422.
- `state` is `{"content": …}` (string form is also legal; the engine always
  sends the object form).
- `instructions` may be a string (engine form) or an object (vendor examples).
- Multiple questions per request are supported (up to 16 for NeoHorse).

## 200 Response

```json
{
  "model": "NeoHorse-Jev-4B",
  "answers": {
    "<ruleId>": {"type": "noul", "noul": 0.983161449432373}
  },
  "usage": {"input_tokens": 32, "output_tokens": 30}
}
```

- `noul` = probability that the question holds for this state. The engine's hit
  rule is `noul >= rule.threshold`.
- On `/v1/systemone` a noul answer carries **only** `type` + `noul`
  (`probabilities` exist only on the separate `/v1/decision` endpoint).
- A missing answer for a rule id → that rule follows its `on_error` policy
  (fail_open skips, fail_closed blocks).

## Errors (HTTP status + `{"detail": "..."}`; there is no top-level `error` field)

| Status | Meaning | Engine behaviour |
|---|---|---|
| 401 | bad bearer token | whole batch → `on_error` |
| 413 | request body / text fields too large | `on_error` + ERROR log (no split) |
| 422 | validation (unknown model, unknown fields, state over limit, too many questions, invalid JSON) | `on_error` + ERROR log including `detail` |
| 429 / 529 | busy, with `Retry-After: 1` | **async / simulate**: retry the whole batch **once** honouring `Retry-After` (capped 2s), still failing → `on_error`. **sync decision path**: no retry — fail fast to `on_error` so a busy backend never adds latency to `effective_action` |
| other 5xx / timeout | — | `on_error`, no retry |

## Hard limits and engine-side budgets

| Limit | Value | Engine countermeasure |
|---|---|---|
| `state` | ≤ 2048 tokens (422, no silent truncation) | content truncated head+tail to a CJK-aware token budget (75% of the backend's `maxStateTokens`; ~1 CJK char ≈ 1 token) and the `max-content-chars` (4000) ceiling before sending, counted in `virbius.prompt.judge.content.truncated` |
| questions | ≤ 16 | batch size 8, `min(declared, override)` |
| request body | 1 MiB (streamed 8 MiB) | batch split when cumulative `instructions` exceed `max-prompt-chars` (24000) |

## Metrics

`virbius.prompt.judge.requests{backend,result=ok|http_4xx|http_5xx|busy|timeout|contract}`,
`virbius.prompt.judge.latency.ms{backend}`, `virbius.prompt.judge.batch.questions{backend}`,
`virbius.prompt.judge.noul{backend}` (drift monitoring), `virbius.prompt.judge.content.truncated{backend}`.

## Backends

| id | model | license | role |
|---|---|---|---|
| `neohorse-4b` | `NeoHorse-Jev-4B` | Apache-2.0 | default; 8 questions in one forward pass (measured 1.0s on T4) |
| `startlux-4b` | `StartLux-Decision-v1.1-4B-Instant` | CC-BY-NC-4.0 | non-commercial only; one forward per question (batching saves HTTP round trips only) |
| `laya` | `laya-multilingual` | Apache-2.0 | 322M mmBERT multilingual checkpoint (100+ languages, 1024-token default ctx); internal canary, disabled by default. The English root checkpoint (`laya`, 421M ModernBERT) cannot read non-Latin scripts |

## Measured golden pair (NeoHorse-Jev-4B, Kaggle T4, 2026-11-11)

Request:

```json
{
  "model": "NeoHorse-Jev-4B",
  "state": "同一笔订单扣了两次钱，请今天把多扣的那笔退给我。",
  "questions": {"refund": {"type": "noul", "instructions": "用户是否在要求退款？"}}
}
```

Response:

```json
{
  "model": "NeoHorse-Jev-4B",
  "answers": {"refund": {"type": "noul", "noul": 0.983161449432373}},
  "usage": {"input_tokens": 32, "output_tokens": 30}
}
```

Notes: `state` as `{"content": …}` is equally accepted (measured noul 0.98434,
input_tokens 35). Eight questions in one request: 200, 8/8 answers, 1.0s,
`usage.input_tokens=163`. State above 2048 tokens: 422
`{"detail": "state exceeds 2048 tokens: 50001"}`. There is no `/v1/models`
endpoint and no vendor-hosted API (weights + runtime only).
