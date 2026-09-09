# Login error page (`/ui/callback` fail)

> Same shell as `pages/login.md`. Generated Enterprise Gateway / glitch effects do **not** apply.

Standalone HTML from Control `UiAuthController`, not Vue.

## Layout

- Full viewport, content centered
- Single card, max-width **400px**, padding **32px**, radius **8px**
- Brand mark + title + one `role="alert"` message + primary CTA

## Color / type

Same as login: bg `#f1f5f9`, card `#fff`, text `#0f172a`, muted `#64748b`, CTA `#2563eb`, error `#991b1b` / `#fee2e2`.

## Interaction

- CTA `cursor: pointer`, 200ms hover, visible focus ring
- `prefers-reduced-motion: reduce`
- CTA href `/ui/` starts a fresh login (new `vrb_login_state`)
