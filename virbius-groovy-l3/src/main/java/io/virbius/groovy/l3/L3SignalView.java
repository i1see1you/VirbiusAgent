package io.virbius.groovy.l3;

public record L3SignalView(
        String ruleId,
        int ruleRevision,
        String layer,
        double score,
        String suggest,
        String reasonCode) {}
