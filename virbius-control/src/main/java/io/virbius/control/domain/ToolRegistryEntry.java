package io.virbius.control.domain;

/**
 * Canonical tool metadata entry stored in {@code tb_tool_registry}.
 *
 * <p>Each tool has exactly one definition per tenant. This replaces the previous
 * approach of extracting tool metadata from {@code bind_scope=tool} rules.
 *
 * <p>Consumers:
 * <ul>
 *   <li>{@code ArtifactService.buildToolPolicyBlocks} — generates Edge Manifest {@code tool_policies}</li>
 *   <li>{@code PublishService} — pushes tool policies to Engine via Redis stream</li>
 *   <li>{@code SessionRiskManager} (Engine) — looks up {@code risk_class} for session risk scoring</li>
 *   <li>{@code EvaluateOrchestrator} (Engine) — looks up {@code approval_mode} for challenge
 *       exemption binding ({@code strict}: session+tool+args_hash; {@code lax}: session+tool)</li>
 * </ul>
 */
public record ToolRegistryEntry(
        String tenantId,
        String toolName,
        String riskClass,
        String sandboxType,
        int timeoutMs,
        boolean fastPath,
        String allowedArgsSchemaJson,
        String description,
        String approvalMode) {

    public static ToolRegistryEntry create(
            String tenantId, String toolName, String riskClass, String sandboxType,
            int timeoutMs, boolean fastPath, String allowedArgsSchemaJson, String description,
            String approvalMode) {
        return new ToolRegistryEntry(
                tenantId,
                validateToolName(toolName),
                validateRiskClass(riskClass),
                validateSandboxType(sandboxType),
                validateTimeoutMs(timeoutMs),
                fastPath,
                allowedArgsSchemaJson,
                description,
                validateApprovalMode(approvalMode));
    }

    private static String validateToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool_name required");
        }
        String tn = toolName.trim();
        if (!tn.matches("[a-z][a-z0-9_-]*")) {
            throw new IllegalArgumentException("invalid tool_name: " + tn
                    + " (expected [a-z][a-z0-9_-]*)");
        }
        return tn;
    }

    private static String validateRiskClass(String riskClass) {
        String rc = riskClass != null ? riskClass.trim().toLowerCase() : "low";
        return switch (rc) {
            case "low", "medium", "high", "network" -> rc;
            default -> throw new IllegalArgumentException("invalid risk_class: " + rc);
        };
    }

    private static String validateSandboxType(String sandboxType) {
        String st = sandboxType != null ? sandboxType.trim().toLowerCase() : "none";
        return switch (st) {
            case "none", "landlock", "gvisor" -> st;
            default -> throw new IllegalArgumentException("invalid sandbox_type: " + st);
        };
    }

    private static int validateTimeoutMs(int timeoutMs) {
        if (timeoutMs < 1000 || timeoutMs > 300000) {
            throw new IllegalArgumentException("timeout_ms must be 1000-300000, got " + timeoutMs);
        }
        return timeoutMs;
    }

    private static String validateApprovalMode(String approvalMode) {
        String am = approvalMode != null ? approvalMode.trim().toLowerCase() : "strict";
        return switch (am) {
            case "strict", "lax" -> am;
            default -> throw new IllegalArgumentException("invalid approval_mode: " + am
                    + " (expected strict|lax)");
        };
    }
}
