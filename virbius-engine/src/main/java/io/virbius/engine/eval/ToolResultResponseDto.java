package io.virbius.engine.eval;

/**
 * Response DTO for STI Taint evaluation of tool return values.
 */
public record ToolResultResponseDto(
        String action,              // allow | block | sanitize
        String sanitizedResult,    // sanitized content (only for action=sanitize)
        String detectedPattern,    // detected injection pattern
        String reason,             // human-readable reason
        String traceId) {}
