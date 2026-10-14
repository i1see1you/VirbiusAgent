"""Sliding-window state machine — a faithful port of the engine's
``StreamReviewWindow`` (io.virbius.engine.api.StreamReviewWindow).

Both implementations are pinned to the shared test vectors in
``schemas/review-window-vectors.json`` so they can never drift apart.
"""

from __future__ import annotations


class StreamReviewWindow:
    def __init__(self, window_chars: int, context_chars: int) -> None:
        self.window_chars = max(1, window_chars)
        self.context_chars = max(0, context_chars)
        self._pending: list[str] = []
        self.reviewed_tail = ""

    # -- engine API parity ------------------------------------------------

    @property
    def pending(self) -> str:
        return "".join(self._pending)

    def due(self, delta: str, done: bool) -> "str | None":
        """Append a delta; return the text to review when a window is due.

        The returned text is ``reviewed_tail + pending`` (the context overlap
        lets the reviewer see sentence fragments spanning a window boundary);
        ``None`` means more input is needed.
        """
        if delta:
            self._pending.append(delta)
        window_full = len(self.pending) >= self.window_chars
        flushed_at_end = done and len(self.pending) > 0
        if not window_full and not flushed_at_end:
            return None
        return self.reviewed_tail + self.pending

    def accept(self) -> str:
        """Mark the window as passed; return the content safe to forward.

        Only the newly reviewed pending text is forwarded — the context
        overlap is never re-forwarded.
        """
        forward = self.pending
        reviewed = self.reviewed_tail + forward
        self.reviewed_tail = (
            reviewed if len(reviewed) <= self.context_chars
            else reviewed[len(reviewed) - self.context_chars:]
        )
        self._pending.clear()
        return forward

    def reject(self) -> None:
        """Mark the window as blocked: nothing is forwarded, buffer dropped."""
        self._pending.clear()

    # -- diagnostics -------------------------------------------------------

    def pending_length(self) -> int:
        return len(self.pending)
