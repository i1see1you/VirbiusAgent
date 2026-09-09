# Login Page Overrides

> **PROJECT:** Virbius Ops  
> **Page:** Auth `/login` (standalone HTML, not Vue)

Inherited from ops console (`virbius-control/frontend/src/styles/theme.css`). Design-system “Enterprise Gateway” landing pattern does **not** apply.

## Layout

- Full viewport, content **vertically and horizontally centered**
- Single card, max-width **400px**, internal padding **32px**, radius **8px** (same as `.v-card`)
- Stacked fields only: label above input, full width, equal 16px field gap
- Brand row centered: 32px mark + title + muted subtitle
- Primary CTA full-width, same 8px radius as inputs

## Color / type (ops console, not the generated #0369A1)

| Token | Value |
|---|---|
| Page bg | `#f1f5f9` |
| Card | `#fff` / border `#e2e8f0` |
| Text | `#0f172a` |
| Muted | `#64748b` |
| Primary / CTA | `#2563eb` hover `#1d4ed8` |
| Error | bg `#fee2e2` text `#991b1b` |
| Font | `system-ui, -apple-system, "Segoe UI", Roboto, sans-serif` 13px |

## Interaction

- Labels use `for` + matching `id` (no placeholder-only)
- Visible 2px focus ring `#2563eb`
- Button `cursor: pointer`, 200ms color/shadow, no scale
- Error `role="alert"`
- `prefers-reduced-motion: reduce` disables transitions
- Autofocus username

## Breakpoints

- **375:** card 100% width, page padding 20px
- **768 / 1024+:** card 400px, viewport centered
