package io.virbius.control.domain;

import io.virbius.control.domain.enums.RolloutState;

/** Maps operational rollout_state to execution-plane enforce_mode. */
public final class RolloutEnforceExport {

    private RolloutEnforceExport() {}

    public static String enforceMode(String rolloutState) {
        RolloutState state = RolloutState.parse(rolloutState);
        return switch (state) {
            case DRY_RUN -> "dry_run";
            case CANARY -> "canary";
            case FULL -> "full";
            default -> "dry_run";
        };
    }

    public static Integer exportedCanaryPercent(String rolloutState, Integer canaryPercent) {
        if (RolloutState.CANARY == RolloutState.parse(rolloutState)) {
            return canaryPercent;
        }
        return null;
    }
}
