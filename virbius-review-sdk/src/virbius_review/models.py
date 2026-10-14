"""Verdict and stream-event models."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional


@dataclass
class InputVerdict:
    """Result of a pre-flight input review (``role=input``).

    ``blocked`` is derived from ``effective_action in {deny, block}`` — the
    caller should NOT send the message to the LLM when blocked.
    """

    blocked: bool
    effective_action: str
    reason_code: Optional[str]
    rule_id: Optional[str]
    risk_score: int
    raw: dict

    @property
    def error(self) -> Optional[str]:
        return self.raw.get("error")


@dataclass
class OutputVerdict:
    """Result of an output review (``role=output``).

    ``disposal_action`` is one of ``reject | omit | redact | warn`` and the
    caller applies it verbatim via :func:`apply_disposal`. On older engines
    without the disposal fields the SDK derives it from ``effective_action``.
    """

    blocked: bool
    disposal_action: str
    sanitized_content: Optional[str]
    effective_action: str
    reason_code: Optional[str]
    rule_id: Optional[str]
    risk_score: int
    raw: dict

    @property
    def error(self) -> Optional[str]:
        return self.raw.get("error")


@dataclass
class Delta:
    """A reviewed chunk of content safe to forward to the end user."""

    text: str


@dataclass
class Blocked:
    """The window review rejected the stream; stop forwarding and abort upstream."""

    reason_code: Optional[str]
    rule_id: Optional[str]
    risk_score: Optional[int]
    disposal_action: str


@dataclass
class Done:
    """The stream finished cleanly (every window reviewed and passed)."""
