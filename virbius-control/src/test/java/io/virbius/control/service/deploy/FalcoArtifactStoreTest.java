package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.config.ControlJedisPools;
import org.junit.jupiter.api.Test;

/**
 * Regression: the store used to inject {@code Optional<JedisPool>} directly, which never matched
 * any bean, so every write silently no-op'd. Wiring now goes through {@link ControlJedisPools},
 * and operations fail loudly when Redis is not configured.
 */
class FalcoArtifactStoreTest {

    private final FalcoArtifactStore store = new FalcoArtifactStore(new ControlJedisPools(""));

    @Test
    void unavailableWhenRedisUrlBlank() {
        assertFalse(store.isAvailable());
    }

    @Test
    void nextRevisionFailsLoudlyWithoutRedis() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> store.nextRevision("t1"));
        assertTrue(ex.getMessage().contains("Redis"), ex.getMessage());
    }

    @Test
    void putSnapshotFailsLoudlyWithoutRedis() {
        assertThrows(IllegalStateException.class,
                () -> store.putSnapshot("t1", 1L, "rules"));
    }

    @Test
    void publishFailsLoudlyWithoutRedis() {
        assertThrows(IllegalStateException.class,
                () -> store.publishRuleUpdate("t1", 1L, "canary"));
    }

    @Test
    void readsAreSafeWithoutRedis() {
        assertEquals(0L, store.getStableRevision("t1"));
    }

    @Test
    void retentionFloorKeepsTenRevisionsAndNeverGoesBelowOne() {
        assertEquals(1L, store.retentionFloor(3L));
        assertEquals(1L, store.retentionFloor(10L));
        assertEquals(2L, store.retentionFloor(11L));
        assertEquals(91L, store.retentionFloor(100L));
    }
}
