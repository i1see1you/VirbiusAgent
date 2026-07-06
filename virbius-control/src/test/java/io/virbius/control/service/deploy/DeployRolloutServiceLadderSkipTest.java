package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeployRolloutServiceLadderSkipTest {

    private NodeRegistryService nodeRegistry;
    private DeployRolloutService service;

    @BeforeEach
    void setUp() {
        nodeRegistry = mock(NodeRegistryService.class);
        service = new DeployRolloutService(null, null, null, null, null, null, null, nodeRegistry, null, null, null);
    }

    @Test
    void fallsBackToOriginalLadderWhenNoLiveNodes() {
        when(nodeRegistry.listNodes("cloud", "t")).thenReturn(List.of());
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        int next = service.computeNextEffectiveStep("t", List.of(5, 20, 50, 100), 0);

        assertEquals(5, next);
    }

    @Test
    void skipsLadderStepsThatDoNotMoveAnyNode() {
        // Pick instance ids whose CRC32C buckets are 17 and 73.
        String instA = findInstanceIdForBucket(17);
        String instB = findInstanceIdForBucket(73);
        when(nodeRegistry.listNodes("cloud", "t"))
                .thenReturn(List.of(Map.of("instance_id", instA), Map.of("instance_id", instB)));
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        // Starting from 0% → next ladder is 5%, but no bucket in [0,5), skip to 20% which catches bucket 17.
        assertEquals(20, service.computeNextEffectiveStep("t", List.of(5, 20, 50, 100), 0));
        // From 20% → next ladder is 50%, but no bucket in [20,50), skip to 100% which catches bucket 73.
        assertEquals(100, service.computeNextEffectiveStep("t", List.of(5, 20, 50, 100), 20));
    }

    @Test
    void doesNotSkipWhenStepActuallyMovesNode() {
        String instA = findInstanceIdForBucket(3);
        when(nodeRegistry.listNodes("cloud", "t"))
                .thenReturn(List.of(Map.of("instance_id", instA)));
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        // bucket 3 ∈ [0,5) so first step at 5% is effective, don't skip.
        assertEquals(5, service.computeNextEffectiveStep("t", List.of(5, 20, 50, 100), 0));
    }

    @Test
    void returnsZeroWhenAlreadyAtEnd() {
        when(nodeRegistry.listNodes("cloud", "t")).thenReturn(List.of());
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        assertEquals(0, service.computeNextEffectiveStep("t", List.of(5, 20, 50, 100), 100));
    }

    private static String findInstanceIdForBucket(int targetBucket) {
        for (long i = 0; i < 10_000_000L; i++) {
            String id = "inst-" + i;
            if (BucketCalculator.bucketOf(id) == targetBucket) {
                return id;
            }
        }
        throw new IllegalStateException("could not find instance id for bucket " + targetBucket);
    }
}
