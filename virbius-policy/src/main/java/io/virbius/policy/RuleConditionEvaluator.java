package io.virbius.policy;

import java.util.Map;

/**
 * Evaluates rule conditions against injected context values.
 *
 * <p>PoC: structured {@link RuleCondition} on cumulative count. Expression form reserved for Post-MVP.
 */
public final class RuleConditionEvaluator {

    private RuleConditionEvaluator() {}

    public static boolean evaluate(long left, RuleCondition condition) {
        if (condition == null) {
            return false;
        }
        return condition.evaluate(left);
    }

    /**
     * Future: {@code evaluate("cumulative('user_req_1h').count >= 120", context)}.
     * PoC uses {@link #evaluate(long, RuleCondition)} only.
     */
    public static boolean evaluate(String express, Map<String, Object> context) {
        throw new UnsupportedOperationException(
                "expression evaluate not implemented; use RuleCondition on cumulative count");
    }

    public static boolean evaluateCompare(long count, String compareOp, int threshold) {
        String op = compareOp == null || compareOp.isBlank() ? "gte" : compareOp.trim().toLowerCase();
        return switch (op) {
            case "gt" -> count > threshold;
            case "eq" -> count == threshold;
            case "lte" -> count <= threshold;
            case "lt" -> count < threshold;
            default -> count >= threshold;
        };
    }
}
