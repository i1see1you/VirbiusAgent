package io.virbius.control.service.deploy;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * Computes the canary bucket (0-99) for an instance id, matching the algorithm used by
 * the engine-side {@code NodePoolResolver} and gateway-agent.
 */
public final class BucketCalculator {

    private BucketCalculator() {}

    public static int bucketOf(String instanceId) {
        if (instanceId == null || instanceId.isEmpty()) {
            return 0;
        }
        CRC32C crc = new CRC32C();
        crc.update(instanceId.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100);
    }
}
