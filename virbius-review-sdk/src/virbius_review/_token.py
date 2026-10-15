"""Bearer-token acquisition for authenticated evaluate calls (P0-min).

Three mutually-exclusive sources, checked in order:

1. ``token_provider`` — a zero-arg callable evaluated before every call
   (supports mid-stream refresh and custom issuance flows).
2. ``license_jwt`` + ``auth_url`` — the SDK exchanges the long-lived License
   JWT ONCE at ``POST {auth_url}/api/v1/auth/evaluate-token`` for a short-lived
   evaluate token, caches it and refreshes at ~80% of its TTL. The License JWT
   never travels per-request.
3. ``auth_token`` — a static token (escape hatch; no refresh).

When none is configured the SDK sends no Authorization header (dev/engine-auth-
disabled). The exchange uses only the standard library so it works for both the
requests- and httpx-backed clients.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.request

from .config import ReviewConfig
from .core import ReviewError


class TokenManager:
    def __init__(self, cfg: ReviewConfig) -> None:
        self._cfg = cfg
        self._lock = threading.Lock()
        self._cached = ""
        self._expires_at = 0.0

    @property
    def configured(self) -> bool:
        return bool(self._cfg.token_provider or self._cfg.license_jwt or self._cfg.auth_token)

    def current(self) -> str:
        """Return a currently-valid bearer token ("" when unconfigured)."""
        if self._cfg.token_provider is not None:
            try:
                return str(self._cfg.token_provider() or "")
            except Exception as exc:  # provider failure = cannot authenticate
                raise ReviewError(f"token_provider failed: {exc}") from exc
        if self._cfg.license_jwt and self._cfg.auth_url:
            return self._exchange_cached()
        if self._cfg.auth_token:
            return self._cfg.auth_token
        return ""

    # -- license exchange --------------------------------------------------

    def _exchange_cached(self) -> str:
        now = time.time()
        with self._lock:
            if self._cached and now < self._expires_at:
                return self._cached
            token, ttl = self._exchange()
            self._cached = token
            # Refresh at ~80% of the TTL; never trust a 0 TTL.
            self._expires_at = now + max(1.0, ttl * 0.8)
            return token

    def _exchange(self):
        body = json.dumps({"license_jwt": self._cfg.license_jwt}).encode()
        req = urllib.request.Request(
            self._cfg.auth_url.rstrip("/") + "/api/v1/auth/evaluate-token",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self._cfg.timeout) as resp:
                data = json.loads(resp.read().decode())
        except Exception as exc:
            raise ReviewError(f"evaluate-token exchange failed: {exc}") from exc
        # Control wraps responses in {code,message,data}; accept both shapes.
        payload = data.get("data", data) if isinstance(data, dict) else {}
        token = payload.get("access_token") if isinstance(payload, dict) else None
        if not token:
            raise ReviewError(f"evaluate-token exchange returned no token: {data!r}")
        ttl = payload.get("expires_in", 900) or 900
        return str(token), float(ttl)
