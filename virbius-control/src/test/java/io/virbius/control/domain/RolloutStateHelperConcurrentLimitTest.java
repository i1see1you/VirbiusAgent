package io.virbius.control.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RolloutStateHelperConcurrentLimitTest {

    @Test
    void firstPublishConsumesSlot() {
        assertTrue(RolloutStateHelper.shouldEnforceConcurrentLimit("draft", "dry_run", false));
    }

    @Test
    void republishAfterDisableDoesNotConsumeSlot() {
        assertFalse(RolloutStateHelper.shouldEnforceConcurrentLimit("draft", "dry_run", true));
    }

    @Test
    void upgradeAndRecoverDoNotCheck() {
        assertFalse(RolloutStateHelper.shouldEnforceConcurrentLimit("dry_run", "canary", false));
        assertFalse(RolloutStateHelper.shouldEnforceConcurrentLimit("disabled", "draft", false));
        assertFalse(RolloutStateHelper.shouldEnforceConcurrentLimit("full", "disabled", false));
    }
}
