package io.virbius.control.domain;

import static org.junit.jupiter.api.Assertions.*;

import io.virbius.control.domain.enums.DeployRolloutState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeployRolloutStateHelperTest {

    @Test
    void parseValid() {
        assertEquals(DeployRolloutState.PENDING, DeployRolloutState.parse("pending"));
        assertEquals(DeployRolloutState.CANARY, DeployRolloutState.parse("canary"));
        assertEquals(DeployRolloutState.FULL, DeployRolloutState.parse("full"));
        assertEquals(DeployRolloutState.EDGE_DONE, DeployRolloutState.parse("edge_done"));
        assertEquals(DeployRolloutState.FINALIZED, DeployRolloutState.parse("finalized"));
        assertEquals(DeployRolloutState.ROLLED_BACK, DeployRolloutState.parse("rolled_back"));
    }

    @Test
    void parseNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> DeployRolloutState.parse(null));
    }

    @Test
    void parseBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> DeployRolloutState.parse(" "));
    }

    @Test
    void parseUnknownThrows() {
        assertThrows(IllegalArgumentException.class, () -> DeployRolloutState.parse("bogus"));
    }

    // ---------------------------------------------------------------
    // State transitions
    // ---------------------------------------------------------------

    @Test
    void pendingToCanary() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("pending", "canary"));
    }

    @Test
    void pendingToRolledBack() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("pending", "rolled_back"));
    }

    @Test
    void pendingToFullNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("pending", "full"));
    }

    @Test
    void pendingToPausedNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("pending", "paused"));
    }

    @Test
    void canaryToCanary() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("canary", "canary"));
    }

    @Test
    void canaryToPaused() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("canary", "paused"));
    }

    @Test
    void canaryToFull() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("canary", "full"));
    }

    @Test
    void canaryToRolledBack() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("canary", "rolled_back"));
    }

    @Test
    void canaryToFinalizedNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("canary", "finalized"));
    }

    @Test
    void pausedToCanary() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("paused", "canary"));
    }

    @Test
    void pausedToRolledBack() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("paused", "rolled_back"));
    }

    @Test
    void pausedToFullNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("paused", "full"));
    }

    @Test
    void fullToEdgeDone() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("full", "edge_done"));
    }

    @Test
    void fullToFinalized() {
        // Allowed: edge artifact step is optional for cloud/gateway-only deploys.
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("full", "finalized"));
    }

    @Test
    void fullToRolledBack() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("full", "rolled_back"));
    }

    @Test
    void fullToCanaryNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("full", "canary"));
    }

    @Test
    void edgeDoneToFinalized() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("edge_done", "finalized"));
    }

    @Test
    void edgeDoneToRolledBack() {
        assertDoesNotThrow(() ->
                DeployRolloutStateHelper.validateTransition("edge_done", "rolled_back"));
    }

    @Test
    void edgeDoneToCanaryNotAllowed() {
        assertThrows(IllegalArgumentException.class, () ->
                DeployRolloutStateHelper.validateTransition("edge_done", "canary"));
    }

    @Test
    void rolledBackNoTransitions() {
        for (var s : DeployRolloutState.values()) {
            if (s == DeployRolloutState.ROLLED_BACK) continue;
            assertThrows(IllegalArgumentException.class, () ->
                    DeployRolloutStateHelper.validateTransition("rolled_back", s.value()));
        }
    }

    @Test
    void finalizedNoTransitions() {
        for (var s : DeployRolloutState.values()) {
            if (s == DeployRolloutState.FINALIZED) continue;
            assertThrows(IllegalArgumentException.class, () ->
                    DeployRolloutStateHelper.validateTransition("finalized", s.value()));
        }
    }

    // ---------------------------------------------------------------
    // Ladder steps
    // ---------------------------------------------------------------

    @Test
    void nextLadderStepReturnsFirstGreater() {
        var ladder = List.of(5, 20, 50, 100);
        assertEquals(5, DeployRolloutStateHelper.nextLadderStep(ladder, 0));
        assertEquals(20, DeployRolloutStateHelper.nextLadderStep(ladder, 5));
        assertEquals(50, DeployRolloutStateHelper.nextLadderStep(ladder, 20));
        assertEquals(100, DeployRolloutStateHelper.nextLadderStep(ladder, 50));
        assertEquals(0, DeployRolloutStateHelper.nextLadderStep(ladder, 100));
    }

    @Test
    void nextLadderStepEmptyLadder() {
        assertEquals(0, DeployRolloutStateHelper.nextLadderStep(List.of(), 0));
    }

    @Test
    void nextLadderStepNullLadder() {
        assertEquals(0, DeployRolloutStateHelper.nextLadderStep(null, 0));
    }

    @Test
    void isFullPercent() {
        assertTrue(DeployRolloutStateHelper.isFullPercent(100));
        assertTrue(DeployRolloutStateHelper.isFullPercent(200));
        assertFalse(DeployRolloutStateHelper.isFullPercent(99));
        assertFalse(DeployRolloutStateHelper.isFullPercent(0));
    }
}
