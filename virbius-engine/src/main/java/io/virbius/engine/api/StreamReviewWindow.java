package io.virbius.engine.api;

/**
 * Sliding-window buffer for streaming output review (B2).
 *
 * <p>Mirrors the NeMo output-rail streaming model: deltas accumulate in a pending
 * buffer; once the buffer reaches {@code windowChars} (the hard cap — any content
 * beyond the window is always judged) or the stream ends, the tail window — the
 * pending content plus the last {@code contextChars} of already-reviewed text —
 * is evaluated. Passed windows are then forwarded to the client; blocked windows
 * terminate the stream. The context overlap lets the reviewer see sentence
 * fragments that span a window boundary.
 *
 * <p>Pure state machine, no I/O — unit-testable.
 */
final class StreamReviewWindow {

    private final int windowChars;
    private final int contextChars;

    private final StringBuilder pending = new StringBuilder();
    private String reviewedTail = "";

    StreamReviewWindow(int windowChars, int contextChars) {
        this.windowChars = Math.max(1, windowChars);
        this.contextChars = Math.max(0, contextChars);
    }

    /**
     * Append a delta. Returns the text to evaluate when a window is due
     * (context overlap + pending), or {@code null} when more input is needed.
     */
    String due(String delta, boolean done) {
        if (delta != null) {
            pending.append(delta);
        }
        boolean windowFull = pending.length() >= windowChars;
        boolean flushedAtEnd = done && pending.length() > 0;
        if (!windowFull && !flushedAtEnd) {
            return null;
        }
        return reviewedTail + pending;
    }

    /**
     * Mark the current window as passed. Returns the pending content that is now
     * safe to forward to the client (context overlap is never re-forwarded).
     */
    String accept() {
        String forward = pending.toString();
        String reviewed = reviewedTail + pending;
        reviewedTail = reviewed.length() <= contextChars
                ? reviewed
                : reviewed.substring(reviewed.length() - contextChars);
        pending.setLength(0);
        return forward;
    }

    /** Mark the current window as blocked: nothing is forwarded, buffer dropped. */
    void reject() {
        pending.setLength(0);
    }

    /** Pending (not yet reviewed) length — for tests/diagnostics. */
    int pendingLength() {
        return pending.length();
    }
}
