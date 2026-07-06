package io.virbius.control.domain;

import io.virbius.control.domain.enums.RolloutState;

public final class RolloutStateHelper {

    private RolloutStateHelper() {}

    public static String stateOf(RuleRevision rule) {
        if (rule == null || rule.rolloutState() == null || rule.rolloutState().isBlank()) {
            return RolloutState.DRAFT.value();
        }
        return rule.rolloutState();
    }

    public static boolean inRollout(String rolloutState) {
        return "dry_run".equals(rolloutState) || "canary".equals(rolloutState);
    }

    public static boolean inExecutionPlane(RuleRevision rule) {
        return inExecutionPlane(stateOf(rule));
    }

    public static boolean inExecutionPlane(String rolloutState) {
        RolloutState s = RolloutState.parse(rolloutState);
        return s == RolloutState.DRY_RUN || s == RolloutState.CANARY || s == RolloutState.FULL;
    }

    public static boolean isDisabled(RuleRevision rule) {
        return RolloutState.DISABLED == RolloutState.parse(stateOf(rule));
    }

    public static void requireNotDisabled(RuleRevision rule) {
        if (isDisabled(rule)) {
            throw new IllegalStateException("rule is disabled and cannot be modified: " + rule.ruleId());
        }
    }

    public static void validateTransition(String fromState, String toState) {
        String from = RolloutState.parse(fromState).value();
        String to = RolloutState.parse(toState).value();
        if (from.equals(to)) {
            return;
        }
        if (RolloutState.DRY_RUN.value().equals(from) && RolloutState.FULL.value().equals(to)) {
            throw new IllegalArgumentException("dry_run -> full is permanently forbidden");
        }
        boolean allowed =
                switch (from) {
                    case "draft" -> "dry_run".equals(to) || "disabled".equals(to);
                    case "dry_run" -> "canary".equals(to) || "disabled".equals(to);
                    case "canary" -> "canary".equals(to) || "full".equals(to) || "dry_run".equals(to) || "disabled".equals(to);
                    case "full" -> "dry_run".equals(to) || "disabled".equals(to);
                    case "disabled" -> "draft".equals(to);
                    default -> false;
                };
        if (!allowed) {
            throw new IllegalArgumentException("invalid rollout transition: " + from + " -> " + to);
        }
    }

    public static void validateCanaryPercent(String rolloutState, Integer canaryPercent) {
        RolloutState state = RolloutState.parse(rolloutState);
        if (state == RolloutState.CANARY) {
            if (canaryPercent == null || canaryPercent < 1 || canaryPercent > 100) {
                throw new IllegalArgumentException("canary_percent required (1-100) when rollout_state=canary");
            }
            return;
        }
        if (canaryPercent != null) {
            throw new IllegalArgumentException("canary_percent must be null unless rollout_state=canary");
        }
    }

    public static String normalize(String raw) {
        return RolloutState.parse(raw).value();
    }
}
