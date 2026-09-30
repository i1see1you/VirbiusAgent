# Suying tool contracts

Do not change these. Call them as written.

## MCP (`suying` via `.cursor/mcp.json`)

All tools return JSON text. Failures set `isError: true`.

### `run_case`

Input: `{ nl?: string, steps?: Step[] }` — one or the other, not both.

**Agent default:** pass `steps` (host model plans). Pass `nl` only when `SUYING_LLM_API_KEY` is set in MCP env and host-side planning is not used.

Success: `{ case_id, evidence_dir, report_path, browser_alive: true, recording?: boolean, steps }`

Browser stays up until `close`. At most one open case. When Oxi is up and `SUYING_RECORD≠0`, webreel records in the background (`recording: true`).

### `run_steps`

Input: `{ case_id: string, steps: Step[] }`

Success: `{ case_id, browser_alive: true, steps }`

Fails if `case_id` is not open.

### `screenshot`

Input: `{ case_id: string, full_page?: boolean, label?: string }`

Success: `{ case_id, path }` (relative PNG under the run dir)

### `close`

Input: `{ case_id: string }`

Success: `{ case_id, report_path, browser_alive: false, record_path: string | null, record_error?: string }`

Stops webreel first (if any), then the browser. Finalizes `.suying/runs/<id>/report.md` with `closed_at`. `record_path` is the MP4 when recording succeeded; otherwise null with optional `record_error`.

### `compare_runs`

Input: `{ before: string, after: string }` — each is a `case_id` or a directory path.

Success: `{ compare_id, report_path, compare, pixels, fix }`

Does not start a browser. Writes `.suying/compares/<id>/report.md` and `compare.json`.

## Step

```
{ action: goto | click | type | wait | shot, selector?, url?, text?, label?, timeoutMs? }
```

## CLI

```
npm run compare -- --before <id|path> --after <id|path>
npm run archive -- --compare <id|path>
```

Missing `--before` / `--after` / `--compare` → exit 1.

## Env

- `SUYING_LLM_API_KEY` — **optional** when the Agent skill plans `steps`; **required** only for MCP-side `nl` or optional compare AI (`fix`)
- `SUYING_LLM_BASE_URL`, `SUYING_LLM_MODEL` — used only when the key is set
- `SUYING_OXI_BIN` — override oxibrowser binary
- `SUYING_RECORD=0` — disable webreel auto-record on `run_case`

Preference: if LLM env is configured, MCP/`nl` and compare AI may use it; otherwise Agent-side `steps` + `fix: unknown` is the supported path.

Compare needs ffmpeg on PATH. Recording needs ffmpeg too (soft-fail if missing).

## Reports

- Single run: `.suying/runs/<id>/report.md` — timeline, passed/failed, optional `record: …`, `closed_at`
- Compare: `.suying/compares/<id>/report.md` — `compare` / `pixels` / `fix`. `compare: passed` means the comparison finished, not that the bug is fixed
- Archive: `.suying/archives/<id>/{compare,before,after,manifest.md}`
