package io.virbius.control.domain;

public record ChallengeApprovalRecord(
    String challengeId,
    String tenantId,
    String status,
    String toolName,
    String argsHash,
    String sessionId,
    String ruleId,
    String reasonCode,
    int riskScore,
    String approvalMode,
    Long createdAt,
    Long expiresAt,
    String approvedBy,
    Long approvedAt,
    String rejectedBy,
    Long rejectedAt,
    String comment
) {}
