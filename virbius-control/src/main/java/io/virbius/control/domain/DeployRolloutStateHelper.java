package io.virbius.control.domain;

import io.virbius.control.domain.enums.DeployRolloutState;
import java.util.List;
import java.util.Set;

/** Validates deploy rollout state transitions. */
public final class DeployRolloutStateHelper {

    private DeployRolloutStateHelper() {}

    public static void validateTransition(String fromRaw, String toRaw) {
        DeployRolloutState from = DeployRolloutState.parse(fromRaw);
        DeployRolloutState to = DeployRolloutState.parse(toRaw);
        if (from == to) {
            return;
        }
        Set<DeployRolloutState> allowed = switch (from) {
            case PENDING -> Set.of(DeployRolloutState.CANARY, DeployRolloutState.ROLLED_BACK);
            case CANARY -> Set.of(
                    DeployRolloutState.CANARY,
                    DeployRolloutState.PAUSED,
                    DeployRolloutState.FULL,
                    DeployRolloutState.ROLLED_BACK);
            case PAUSED -> Set.of(
                    DeployRolloutState.CANARY,
                    DeployRolloutState.ROLLED_BACK);
            case FULL -> Set.of(
                    DeployRolloutState.EDGE_DONE,
                    DeployRolloutState.FINALIZED,
                    DeployRolloutState.ROLLED_BACK);
            case EDGE_DONE -> Set.of(
                    DeployRolloutState.FINALIZED,
                    DeployRolloutState.ROLLED_BACK);
            case ROLLED_BACK, FINALIZED -> Set.of();
        };
        if (!allowed.contains(to)) {
            throw new IllegalArgumentException(
                    "invalid deploy rollout transition: " + from.value() + " -> " + to.value());
        }
    }

    /** Returns the next ladder step strictly greater than {@code currentPercent}, or 0 if none. */
    public static int nextLadderStep(List<Integer> ladder, int currentPercent) {
        if (ladder == null || ladder.isEmpty()) {
            return 0;
        }
        for (Integer p : ladder) {
            if (p != null && p > currentPercent) {
                return p;
            }
        }
        return 0;
    }

    public static boolean isFullPercent(int percent) {
        return percent >= 100;
    }
}
