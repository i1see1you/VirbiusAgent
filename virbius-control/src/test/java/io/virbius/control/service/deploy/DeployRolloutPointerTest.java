package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DeployRolloutPointerTest {

    @Test
    void roundTrip() {
        var original = new DeployRolloutPointer(
                "t1", "d1", "canary", 20,
                101L, 100L, 201L, 200L,
                301L, 300L,
                "v2", "v1", "2026-06-18T10:00:00Z");

        Map<String, String> hash = original.toRedisHash();
        var restored = DeployRolloutPointer.fromRedisHash("t1", hash);

        assertNotNull(restored);
        assertEquals("t1", restored.tenantId());
        assertEquals("d1", restored.deployId());
        assertEquals("canary", restored.state());
        assertEquals(20, restored.canaryPercent());
        assertEquals(101, restored.canaryEngineRevision());
        assertEquals(100, restored.stableEngineRevision());
        assertEquals(201, restored.canaryGatewayRevision());
        assertEquals(200, restored.stableGatewayRevision());
        assertEquals(301, restored.canaryEdgeRevision());
        assertEquals(300, restored.stableEdgeRevision());
        assertEquals("v2", restored.targetVersion());
        assertEquals("v1", restored.prevVersion());
    }

    @Test
    void fromEmptyHashReturnsNull() {
        assertNull(DeployRolloutPointer.fromRedisHash("t1", Map.of()));
    }

    @Test
    void fromNullHashReturnsNull() {
        assertNull(DeployRolloutPointer.fromRedisHash("t1", null));
    }

    @Test
    void fromHashMissingDeployIdReturnsNull() {
        var hash = Map.of("canary_percent", "10");
        assertNull(DeployRolloutPointer.fromRedisHash("t1", hash));
    }

    @Test
    void nullFieldsBecomeEmptyString() {
        var p = new DeployRolloutPointer(
                "t1", "d1", null, 0,
                0, 0, 0, 0, 0, 0,
                null, null, null);

        Map<String, String> hash = p.toRedisHash();
        assertEquals("", hash.get("state"));
        assertEquals("", hash.get("target_version"));
        assertEquals("", hash.get("prev_version"));
        assertEquals("", hash.get("updated_at"));
    }
}
