package io.virbius.control.domain;

import java.time.Instant;

/**
 * A single clause in the enterprise AI Agent constitution.
 *
 * <p>Constitution rules are managed in the control plane, compiled into
 * prompt templates, and synced to the edge layer where
 * {@code PromptGateway} injects them into LLM prompts (§2.8).
 *
 * <p>Categories:
 * <ul>
 *   <li>{@code prohibition} — absolute prohibitions ("不得...")</li>
 *   <li>{@code tool_rule} — tool usage constraints</li>
 *   <li>{@code boundary} — scope boundaries (network, data, etc.)</li>
 *   <li>{@code principle} — general operating principles</li>
 * </ul>
 */
public record ConstitutionRule(
        Long id,
        String tenantId,
        String ruleId,
        String version,
        String category,
        int priority,
        String ruleText,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static final String CATEGORY_PROHIBITION = "prohibition";
    public static final String CATEGORY_TOOL_RULE = "tool_rule";
    public static final String CATEGORY_BOUNDARY = "boundary";
    public static final String CATEGORY_PRINCIPLE = "principle";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";
}
