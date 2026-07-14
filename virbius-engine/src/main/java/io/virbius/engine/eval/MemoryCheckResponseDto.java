package io.virbius.engine.eval;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

/**
 * DTO for /v1/memory/check response (LLM-based injection detection result).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemoryCheckResponseDto {
    /**
     * Whether the memory write is allowed.
     * - true: No injection detected, write allowed.
     * - false: Prompt injection detected, write blocked.
     */
    private boolean allowed;

    /**
     * Reason for blocking (when allowed is false).
     * e.g., "prompt_injection_detected", "risk_threshold_exceeded".
     */
    private String blockReason;

    /**
     * Risk score (0-100) from the injection detection.
     * Higher scores indicate more likely injection attempts.
     */
    private Integer riskScore;

    /**
     * The model used for detection (e.g., "qwen3guard:0.6b").
     */
    private String model;

    /**
     * Additional metadata (optional).
     */
    private String metadata;
}