package io.virbius.engine.eval;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * DTO for /v1/memory/check request (LLM-based injection detection for memory writes).
 *
 * This endpoint is invoked by the Proxy after local Memory Interceptor checks
 * pass (PII desensitized, no credentials, size within limits).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCheckRequestDto {
    /**
     * Trace ID for correlation with the parent tool call.
     */
    private String traceId;

    /**
     * Session ID.
     */
    private String sessionId;

    /**
     * App ID (from License).
     */
    private String appId;

    /**
     * Tenant ID (from License).
     */
    private String tenantId;

    /**
     * The (PII-desensitized) content to check for prompt injection.
     */
    private String content;

    /**
     * Tool name (for context, e.g., "memory_save").
     */
    private String toolName;
}