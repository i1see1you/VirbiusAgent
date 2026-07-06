package io.virbius.control.gateway.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GatewayArtifactKeysTest {

    @Test
    void blobKeyFormat() {
        String key = GatewayArtifactKeys.blobKey("virbius:artifacts:gateway", "default", 42, GatewayArtifactPart.ACCESS_LISTS);
        assertEquals("virbius:artifacts:gateway:default:r42:access-lists", key);
    }

    @Test
    void sha256Stable() {
        String hex = GatewayArtifactHash.sha256Hex("{\"tenant_id\":\"default\"}");
        assertEquals(64, hex.length());
        assertTrue(hex.matches("[0-9a-f]+"));
    }
}
