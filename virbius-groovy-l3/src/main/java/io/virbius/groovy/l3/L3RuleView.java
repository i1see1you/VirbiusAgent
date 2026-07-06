package io.virbius.groovy.l3;

public record L3RuleView(
        String ruleId,
        int ruleRevision,
        String enforceMode,
        int canaryPercent,
        int riskScore) {}
