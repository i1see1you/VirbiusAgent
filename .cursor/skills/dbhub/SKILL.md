---
name: dbhub
description: Use DBHub MCP to explore schemas and run read-only SQL against project databases. Use when verifying table columns, indexes, row counts, or debugging SQL/mappers against live schema.
---

# DBHub (database MCP)

Requires **DBHub MCP** enabled in Cursor (`Tools & MCP` → `dbhub` green).

## When to use

- Confirm column names / types vs entity or Mapper XML
- Check indexes, row counts, sample rows (non-prod)
- Validate migration assumptions before writing SQL

Prefer **search_objects** first (schema exploration), then narrow **execute_sql**.

## Tools (typical)

| Tool | Use |
|------|-----|
| `search_objects` | List schemas, tables, columns, indexes |
| `execute_sql` | SELECT / EXPLAIN (respect readonly limits) |
| `explain_sql` | Query plans |

Call via `GetMcpTools` → `CallMcpTool` on server `dbhub` (or project MCP name).

## Configuration

| Mode | MCP args | When |
|------|----------|------|
| Demo | `--demo` | No DB credentials; smoke-test MCP |
| Project DB | `--config dbhub.toml` | After copying `dbhub.toml.example` → `dbhub.toml` |

Set env before Cursor start (example):

```bash
export DBHUB_HOST=127.0.0.1
export DBHUB_PORT=3306
export DBHUB_USER=readonly_user
export DBHUB_PASSWORD=...
export DBHUB_DATABASE=gm_dh
```

**Never** commit `dbhub.toml` with real passwords. Derive DSN from env via `${VAR}` in TOML.

## Agent rules

- Default to **read-only** SELECT; no DDL/DML unless user explicitly asks
- Do not paste production secrets into chat or commits
- If MCP status is `error` / `needsAuth`: ask user to fix `.cursor/mcp.json` and Reload Window
- For monorepo SQL paths: still use codebase `sql/` as source of truth; DBHub confirms runtime schema

## Install / repair

```bash
SKILL_ROOT="$HOME/.cursor/skills/dev-workflow"
"$SKILL_ROOT/scripts/install-project-skills.sh" dbhub
```

Then **Reload Window** in Cursor.
