package io.virbius.engine.eval;

/**
 * File-guard signal rule ids and factories. Every degraded path (unsupported
 * type, no text, OCR unavailable, timeout, truncation, embedded-image limit)
 * must emit a distinct review signal — never a silent allow.
 */
final class FileSignals {

    static final String FILE_LIMIT = "FILE_LIMIT";
    static final String FILE_INVALID = "FILE_INVALID";
    static final String FILE_EMPTY = "FILE_EMPTY";
    static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    static final String FILE_UNKNOWN = "FILE_UNKNOWN";
    static final String FILE_NO_TEXT = "FILE_NO_TEXT";
    static final String FILE_TRUNCATED = "FILE_TRUNCATED";
    static final String FILE_OCR_UNAVAILABLE = "FILE_OCR_UNAVAILABLE";
    static final String FILE_OCR_TIMEOUT = "FILE_OCR_TIMEOUT";
    static final String FILE_EMBEDDED_LIMIT = "FILE_EMBEDDED_LIMIT";
    static final String FILE_LEGACY_DOC = "FILE_LEGACY_DOC";
    static final String FILE_INTERNAL_ERROR = "FILE_INTERNAL_ERROR";
    static final String PROMPT_INJECTION = "PROMPT_INJECTION";

    private FileSignals() {}

    static SignalDto review(String ruleId, String reason) {
        return new SignalDto(ruleId, 1, "cloud", "cloud", 10, reason,
                "review", "full", null, null);
    }

    static SignalDto block(String ruleId, String reason) {
        return new SignalDto(ruleId, 1, "cloud", "cloud", 100, reason,
                "deny", "full", null, null);
    }

    static SignalDto injection(double riskDelta) {
        return new SignalDto(PROMPT_INJECTION, 1, "cloud", "cloud", riskDelta,
                PROMPT_INJECTION, "deny", "full", null, null);
    }
}
