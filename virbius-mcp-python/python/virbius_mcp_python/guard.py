"""Stateful pre-check wrapper for the Virbius in-process SDK.

The raw binding (:func:`virbius_mcp_python.precheck_tool`) is stateless — every
call needs ``(tool_name, args_json, license_jwt, public_key_pem, app_id)``.
That is fine for one-off checks but tedious for a real agent where the license
material is loaded once. :class:`VirbiusGuard` holds the credential and exposes:

    guard = VirbiusGuard.from_env()
    guard.guard("read_doc", {"path": "/tmp/a"})     # raises VirbiusDenied
    guard.precheck("read_doc", {...})               # -> VirbiusDecision

plus framework adapters and a framework-agnostic entry point. ``govern(fn)`` is
the headline: it wraps **any** callable so every call is pre-checked, with no
dependency on an agent framework. The OpenAI Agents SDK / LangChain adapters
(``@guard.openai_tool`` / ``@guard.langchain_tool``) are thin layers over the
same enforcement core.

Environment (same variable names the MCP proxy uses, so a rigged environment
transfers unchanged):

============================  =========================================
``VIRBIUS_LICENSE_FILE``      path to the license JWT file
``VIRBIUS_LICENSE_JWT``       the JWT inline (alternative to the file)
``VIRBIUS_LICENSE_PUBLIC_KEY``path to the public key PEM file **or** the PEM
                              inline (detected by its ``BEGIN PUBLIC KEY``
                              header)
``VIRBIUS_APP_ID``            the app id the license is bound to (required)
``VIRBIUS_TENANT_ID``         optional, informational
============================  =========================================
"""

from __future__ import annotations

import functools
import inspect
import json
import os
from typing import Any, Callable, Iterable, Optional


# Generic line used as the safe fallback. Deliberately drops the Agents SDK's
# trailing "Please try again." — that nudge invites the model to retry, which is
# wrong for a policy refusal. Keep it terse and neutral.
_DEFAULT_TOOL_ERROR = "An error occurred while running the tool."


class VirbiusDecision:
    """Typed result of a pre-check (mirrors AGT's ``evaluation`` / ``verdict``).

    :meth:`VirbiusGuard.precheck` returns this. It is **attribute-first**
    (``d.allowed``) and also supports ``d["allowed"]`` / ``d.get(...)`` /
    ``"allowed" in d`` so the subscript idiom stays available, plus
    :meth:`to_dict` for serialization (e.g. ``json.dumps``).

    ``reason_code`` is the machine code the Rust pre-check emits (the binding
    calls the field ``code``); it is exactly what
    :attr:`VirbiusDenied.reason_code` carries.
    """

    __slots__ = ("allowed", "reason", "reason_code", "fast_path", "sandbox_type", "raw")

    def __init__(
        self,
        allowed: bool,
        reason: Optional[str] = None,
        reason_code: Optional[str] = None,
        fast_path: bool = False,
        sandbox_type: Optional[str] = None,
        raw: Optional[dict] = None,
    ) -> None:
        self.allowed = allowed
        self.reason = reason
        self.reason_code = reason_code
        self.fast_path = fast_path
        self.sandbox_type = sandbox_type
        self.raw = raw or {}

    @classmethod
    def from_raw(cls, raw: dict) -> "VirbiusDecision":
        """Wrap the binding's dict (accepts ``reason_code`` or the raw ``code``)."""
        return cls(
            allowed=bool(raw.get("allowed")),
            reason=raw.get("reason"),
            reason_code=raw.get("reason_code") or raw.get("code"),
            fast_path=bool(raw.get("fast_path")),
            sandbox_type=raw.get("sandbox_type"),
            raw=dict(raw),
        )

    def to_dict(self) -> dict:
        return {
            "allowed": self.allowed,
            "reason": self.reason,
            "reason_code": self.reason_code,
            "fast_path": self.fast_path,
            "sandbox_type": self.sandbox_type,
        }

    def __getitem__(self, key: str) -> Any:
        return self.to_dict()[key]

    def get(self, key: str, default: Any = None) -> Any:
        return self.to_dict().get(key, default)

    def __contains__(self, key: object) -> bool:
        return key in self.to_dict()

    def __repr__(self) -> str:
        return (
            f"VirbiusDecision(allowed={self.allowed!r}, "
            f"reason_code={self.reason_code!r}, reason={self.reason!r})"
        )


class VirbiusDenied(PermissionError):
    """Raised by :meth:`VirbiusGuard.guard` when a tool call is refused.

    Subclasses ``PermissionError``. Carries the stable machine ``reason_code``
    plus, when built via :meth:`from_decision`, the full :class:`VirbiusDecision`
    and an optional ``audit_ref`` (a caller-supplied correlation id — the
    in-process pre-check does not emit an audit record synchronously, so
    :meth:`audit_record` returns whatever was passed, else ``None``).
    """

    def __init__(
        self,
        message: str,
        reason_code: Optional[str] = None,
        *,
        decision: Optional[VirbiusDecision] = None,
        audit_ref: Any = None,
    ) -> None:
        super().__init__(message)
        self.reason_code = reason_code
        self.decision = decision
        self.audit_ref = audit_ref

    @classmethod
    def from_decision(cls, decision: VirbiusDecision, *, audit_ref: Any = None) -> "VirbiusDenied":
        """Build a denial from a :class:`VirbiusDecision` (message single-sourced)."""
        reason = decision.reason or "denied by policy"
        return cls(
            policy_blocked_message(reason),
            reason_code=decision.reason_code,
            decision=decision,
            audit_ref=audit_ref,
        )

    def audit_record(self) -> Any:
        """The decision's audit reference, if any (``None`` by default)."""
        return self.audit_ref


def policy_blocked_message(reason: str) -> str:
    """Human-facing message for a policy refusal (single source of truth).

    Used by :meth:`VirbiusGuard.guard` when raising :class:`VirbiusDenied`. Exposed
    (and imported by the A/B harness) so the model-visible refusal wording can
    never drift from what production actually sends.
    """
    return f"The tool call was blocked by policy: {reason}."


def virbius_tool_error(ctx: Any, error: Exception) -> str:
    """A pass-through ``failure_error_function`` for MCP-proxy mode.

    The proxy already authors a **human, sanitized** ``message`` for every error
    it returns (see PROXY_CONFIG.md). The Agents SDK's *default* formatter would
    replace it with a generic "please try again" line, so this reveals the
    proxy's message instead. It performs **no detection and no rewriting** — no
    fragile string matching — and falls back to the generic line only when the
    message is empty.

        MCPServerSse({...}, failure_error_function=virbius_tool_error)
    """
    return (str(error) or "").strip() or _DEFAULT_TOOL_ERROR


def openai_tool_error(ctx: Any, error: Exception) -> str:
    """OpenAI Agents SDK ``failure_error_function`` for governed tools.

    Type-based, not string-based: a :class:`VirbiusDenied` is revealed (it is our
    own message); any other exception falls back to the generic line so a user
    tool's internal error text is never handed to the model.

    Wired automatically by :meth:`VirbiusGuard.openai_tool` /
    :meth:`VirbiusGuard.openai_tools`; public so the framework formatters stay
    symmetric — ``openai_tool_error`` here, :func:`langchain_tool_error` for
    LangChain, :func:`virbius_tool_error` for MCP-proxy mode.
    """
    if isinstance(error, VirbiusDenied):
        return str(error) or _DEFAULT_TOOL_ERROR
    return _DEFAULT_TOOL_ERROR


def langchain_tool_error(error: Exception) -> str:
    """LangChain ``handle_tool_error`` for governed tools.

    The LangChain adapter routes a denial through a ``ToolException`` (LangChain
    only sends ``ToolException`` to ``handle_tool_error``), so this reveals the
    message verbatim and falls back to the generic line only when it is empty.
    """
    return (str(error) or "").strip() or _DEFAULT_TOOL_ERROR


class VirbiusGuard:
    """Holds license material and pre-checks tool calls against it.

    This is the in-process "SDK embed" enforcement point (软边界 /
    defense-in-depth): if the host process is compromised the check can be
    bypassed — for strong isolation run tools behind the MCP proxy instead.
    """

    def __init__(
        self,
        license_jwt: str,
        public_key_pem: str,
        app_id: str,
        tenant_id: Optional[str] = None,
    ) -> None:
        self.license_jwt = license_jwt.strip()
        self.public_key_pem = public_key_pem
        self.app_id = app_id
        self.tenant_id = tenant_id or self._claim("tenant_id") or ""

    # ── construction ────────────────────────────────────────────────

    @classmethod
    def from_env(cls, env: Optional[dict] = None) -> "VirbiusGuard":
        """Build from environment variables (see module docstring)."""
        e = env if env is not None else os.environ
        jwt = e.get("VIRBIUS_LICENSE_JWT", "").strip()
        if not jwt:
            path = e.get("VIRBIUS_LICENSE_FILE", "").strip()
            if not path:
                raise ValueError(
                    "no license: set VIRBIUS_LICENSE_JWT or VIRBIUS_LICENSE_FILE"
                )
            with open(path, "r", encoding="utf-8") as fh:
                jwt = fh.read().strip()

        pem_source = e.get("VIRBIUS_LICENSE_PUBLIC_KEY", "").strip()
        if pem_source.startswith("-----BEGIN"):
            pem = pem_source
        elif pem_source:
            with open(pem_source, "r", encoding="utf-8") as fh:
                pem = fh.read()
        else:
            raise ValueError(
                "no public key: set VIRBIUS_LICENSE_PUBLIC_KEY "
                "(PEM inline or path to the PEM file)"
            )

        app_id = e.get("VIRBIUS_APP_ID", "").strip()
        if not app_id:
            raise ValueError("no app id: set VIRBIUS_APP_ID")
        return cls(jwt, pem, app_id, tenant_id=e.get("VIRBIUS_TENANT_ID") or None)

    # ── checks ──────────────────────────────────────────────────────

    def precheck(self, tool_name: str, args: Any) -> VirbiusDecision:
        """License allowlist + args-schema pre-check.

        ``args`` may be a dict (recommended) or an already-encoded JSON string.
        Returns a :class:`VirbiusDecision` (attribute access ``d.allowed``; also
        ``d["allowed"]`` / ``d.to_dict()``). License-level failures
        (bad/expired/mismatched credential) raise ``ValueError`` — that is a
        configuration problem, not a policy denial.
        """
        from . import precheck_tool  # the Rust binding

        args_json = args if isinstance(args, str) else json.dumps(args or {})
        return VirbiusDecision.from_raw(
            precheck_tool(tool_name, args_json, self.license_jwt, self.public_key_pem, self.app_id)
        )

    def guard(self, tool_name: str, args: Any) -> None:
        """Enforce :meth:`precheck`; raise :class:`VirbiusDenied` when denied."""
        decision = self.precheck(tool_name, args)
        if not decision.allowed:
            raise VirbiusDenied.from_decision(decision)

    def verify(self) -> dict:
        """Full license verification; returns the claims dict."""
        from . import verify_license

        return verify_license(self.license_jwt, self.public_key_pem, self.app_id)

    @property
    def claims(self) -> dict:
        """Decoded claims (no signature verification — display only)."""
        return {
            "app_id": self._claim("app_id") or self.app_id,
            "tenant_id": self._claim("tenant_id") or "",
            "allowed_tools": self._claim("allowed_tools") or [],
            "exp": self._claim("exp"),
        }

    def tool_allowed(self, tool_name: str) -> bool:
        return tool_name in (self._claim("allowed_tools") or [])

    # ── decorators ──────────────────────────────────────────────────

    def guarded(self, fn: Callable = None, *, name: Optional[str] = None):
        """Framework-agnostic decorator bound to this guard.

        Thin forwarding layer over :func:`govern` (identical behavior); prefer
        the module-level ``govern`` for the shortest path. Works on any callable
        and keeps the original signature, name and docstring.
        """
        return govern(fn, name=name, guard=self) if fn is not None else govern(name=name, guard=self)

    def openai_tool(self, fn: Callable):
        """Wrap ``fn`` as an OpenAI Agents SDK tool (``agents.function_tool``)
        with the pre-check applied before every invocation.

        The original signature (type hints) and docstring become the tool
        schema. A denial surfaces to the agent as a :class:`VirbiusDenied`
        message (type-based formatter), while any other exception keeps the
        SDK's generic message so the tool's internal text is not exposed. This is
        a thin adapter over :func:`govern`.
        """
        from agents import function_tool  # optional dependency

        return function_tool(
            govern(fn, name=getattr(fn, "__name__", "") or "", guard=self),
            failure_error_function=openai_tool_error,
        )

    def openai_tools(self, fns: Iterable[Callable]) -> list:
        """Wrap several functions as guarded Agents SDK tools in one call.

        ``tools=guard.openai_tools([read_doc, danger_exec])`` — the right formatter
        (``openai_tool_error``) is wired automatically, so a caller never names a
        formatter. Equivalent to ``[self.openai_tool(fn) for fn in fns]``.
        """
        return [self.openai_tool(fn) for fn in fns]

    def langchain_tool(self, fn: Callable, description: Optional[str] = None):
        """Wrap ``fn`` as a LangChain ``StructuredTool`` with the pre-check.

        A denial is surfaced through ``ToolException`` + ``handle_tool_error`` so
        the model reads the policy message; any other exception propagates
        untouched, so a user tool's internal text never reaches the model. Thin
        adapter over :func:`govern`.
        """
        from langchain_core.tools import StructuredTool, ToolException

        name = getattr(fn, "__name__", "") or "tool"
        governed = govern(fn, name=name, guard=self)

        @functools.wraps(fn)
        def _surface_denial(*args, **kwargs):
            try:
                return governed(*args, **kwargs)
            except VirbiusDenied as denied:
                raise ToolException(str(denied)) from denied

        return StructuredTool.from_function(
            func=_surface_denial,
            name=name,
            description=description or (getattr(fn, "__doc__", "") or "").strip(),
            handle_tool_error=langchain_tool_error,
        )

    def langchain_tools(self, fns: Iterable[Callable]) -> list:
        """Wrap several functions as guarded LangChain tools in one call.

        The LangChain counterpart of :meth:`openai_tools`.
        """
        return [self.langchain_tool(fn) for fn in fns]

    # ── internals ───────────────────────────────────────────────────

    def _claim(self, key: str):
        payload = self.license_jwt.split(".")
        if len(payload) < 2:
            return None
        import base64

        seg = payload[1]
        seg += "=" * ((4 - len(seg) % 4) % 4)
        try:
            claims = json.loads(base64.urlsafe_b64decode(seg))
        except Exception:  # noqa: BLE001 — display-only path
            return None
        return claims.get(key)


# ── framework-agnostic entry point ──────────────────────────────────────

# Lazily built (and cached) guard for the env-only ``govern(fn)`` path.
_DEFAULT_GUARD: Optional[VirbiusGuard] = None


def _resolve_guard(guard: Optional[VirbiusGuard]) -> VirbiusGuard:
    """Return ``guard``, or a lazily built env guard (``from_env``), cached."""
    global _DEFAULT_GUARD
    if guard is not None:
        return guard
    if _DEFAULT_GUARD is None:
        _DEFAULT_GUARD = VirbiusGuard.from_env()
    return _DEFAULT_GUARD


def _bound_args(fn: Callable, args: tuple, kwargs: dict) -> dict:
    """The call's arguments as a dict, so the pre-check sees named params."""
    return dict(inspect.signature(fn).bind(*args, **kwargs).arguments)


def govern(
    fn: Callable = None,
    *,
    name: Optional[str] = None,
    guard: Optional[VirbiusGuard] = None,
):
    """Framework-agnostic check-then-call wrapper for **any** callable.

    The headline SDK entry point — no agent framework required::

        from virbius_mcp_python import govern

        safe_search = govern(my_search)              # tool name = fn.__name__
        safe_read = govern(my_read, name="read_file")

    Usable as a decorator too (bare or with ``name``)::

        @govern(name="read_file")
        def read(path: str) -> str: ...

    Every call is pre-checked against the license allowlist and raises
    :class:`VirbiusDenied` on refusal (the message is single-sourced via
    :func:`policy_blocked_message`). The credential comes from ``guard`` when
    given, else from the environment once (``VirbiusGuard.from_env()``, cached).

    ``async def`` functions are supported: the pre-check runs *before* the call
    is awaited. Framework adapters (:meth:`VirbiusGuard.openai_tool`,
    :meth:`~VirbiusGuard.langchain_tool`) are thin layers over this core.
    """

    def deco(f: Callable):
        tool = name or getattr(f, "__name__", "") or ""

        if inspect.iscoroutinefunction(f):

            @functools.wraps(f)
            async def _w(*args, **kwargs):
                _resolve_guard(guard).guard(tool, _bound_args(f, args, kwargs))
                return await f(*args, **kwargs)

            return _w

        @functools.wraps(f)
        def _w(*args, **kwargs):
            _resolve_guard(guard).guard(tool, _bound_args(f, args, kwargs))
            return f(*args, **kwargs)

        return _w

    return deco(fn) if fn is not None else deco
