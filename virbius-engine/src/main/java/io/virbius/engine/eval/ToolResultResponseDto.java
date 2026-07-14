package io.virbius.engine.eval;

/**
 * Response DTO for STI Taint evaluation of tool return values.
 */
public record ToolResultResponseDto(
        String action,              // allow | block
        String detectedPattern,    // detected injection pattern (LLM-detected reason)
        String reason,             // human-readable reason
        String traceId) {}
