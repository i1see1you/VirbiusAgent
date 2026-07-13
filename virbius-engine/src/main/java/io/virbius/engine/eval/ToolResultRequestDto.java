package io.virbius.engine.eval;

/**
 * Request DTO for STI Taint evaluation of tool return values.
 */
public record ToolResultRequestDto(
        String tenantId,
        String sessionId,
        String traceId,
        String toolName,
        String toolResult,
        int sessionRiskScore,
        String scene) {}
