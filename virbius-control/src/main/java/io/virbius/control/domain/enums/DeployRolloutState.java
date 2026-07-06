package io.virbius.control.domain.enums;

/**
 * Lifecycle state of a deploy rollout (machine-bucket canary deployment).
 *
 * <pre>
 *   pending → canary(5/20/50) ⇄ paused → ... → full(100) → edge_done → finalized
 *      └── rollback ──▶ rolled_back (terminal)
 * </pre>
 */
public enum DeployRolloutState {
    PENDING("pending"),
    CANARY("canary"),
    PAUSED("paused"),
    FULL("full"),
    EDGE_DONE("edge_done"),
    ROLLED_BACK("rolled_back"),
    FINALIZED("finalized");

    private final String value;

    DeployRolloutState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DeployRolloutState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("deploy rollout state required");
        }
        for (DeployRolloutState s : values()) {
            if (s.value.equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        throw new IllegalArgumentException(
                "deploy rollout state must be pending/canary/paused/full/edge_done/rolled_back/finalized");
    }

    public boolean isTerminal() {
        return this == ROLLED_BACK || this == FINALIZED;
    }
}
