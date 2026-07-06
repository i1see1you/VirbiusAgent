package io.virbius.policy;

import com.fasterxml.jackson.databind.JsonNode;

/** Structured rule condition (PoC: cumulative count comparison). */
public record RuleCondition(String compareOp, int threshold) {

    public RuleCondition {
        compareOp = normalizeCompareOp(compareOp);
        if (threshold < 1) {
            throw new IllegalArgumentException("condition.threshold must be >= 1");
        }
    }

    public static RuleCondition fromJson(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        if (!node.has("threshold")) {
            return null;
        }
        int threshold = node.get("threshold").asInt();
        return new RuleCondition(text(node, "compare_op"), threshold);
    }

    /** Reads {@code body.condition} or legacy flat {@code threshold}/{@code compare_op}. */
    public static RuleCondition parseFromRuleBody(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        RuleCondition nested = fromJson(body.get("condition"));
        if (nested != null) {
            return nested;
        }
        if (body.has("threshold")) {
            return new RuleCondition(text(body, "compare_op"), body.get("threshold").asInt());
        }
        return null;
    }

    public boolean evaluate(long value) {
        return RuleConditionEvaluator.evaluateCompare(value, compareOp, threshold);
    }

    private static String normalizeCompareOp(String compareOp) {
        if (compareOp == null || compareOp.isBlank()) {
            return "gte";
        }
        return compareOp.trim().toLowerCase();
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n != null && !n.isNull() ? n.asText() : null;
    }
}
