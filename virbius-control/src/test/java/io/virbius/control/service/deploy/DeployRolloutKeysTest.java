package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class DeployRolloutKeysTest {

    @Test
    void activePointerKey() {
        assertEquals("virbius:deploy:active:tenant-1",
                DeployRolloutKeys.activePointerKey("tenant-1"));
    }

    @Test
    void nodeKey() {
        assertEquals("virbius:nodes:cloud:tenant-1:inst-1",
                DeployRolloutKeys.nodeKey("cloud", "tenant-1", "inst-1"));
    }

    @Test
    void nodeKeyPrefix() {
        assertEquals("virbius:nodes:gateway:tenant-1:",
                DeployRolloutKeys.nodeKeyPrefix("gateway", "tenant-1"));
    }

    @Test
    void streamKeyConstant() {
        assertEquals("virbius:deploy:notify", DeployRolloutKeys.STREAM_KEY);
    }
}
