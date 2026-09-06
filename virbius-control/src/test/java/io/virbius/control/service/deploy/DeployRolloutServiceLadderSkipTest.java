package io.virbius.control.service.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.control.domain.DeployRollout;
import java.time.Instant;
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
        service = new DeployRolloutService(null, null, null, null, null, null, null, nodeRegistry, null, null, null, null);
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

    @Test
    void previewMatchesEffectiveStepWhenCanaryWouldSkip() {
        String instA = findInstanceIdForBucket(17);
        String instB = findInstanceIdForBucket(73);
        when(nodeRegistry.listNodes("cloud", "t"))
                .thenReturn(List.of(Map.of("instance_id", instA), Map.of("instance_id", instB)));
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        List<Integer> ladder = List.of(5, 20, 50, 100);
        int current = 5;
        int effective = service.computeNextEffectiveStep("t", ladder, current);
        Map<String, Object> preview = service.previewNextUpgrade(rollout("canary", current));

        assertEquals(100, effective);
        assertEquals(20, preview.get("nominal_next_percent"));
        assertEquals(effective, preview.get("effective_next_percent"));
        assertEquals("full", preview.get("effective_next_state"));
        assertEquals(List.of(20, 50), preview.get("skipped_steps"));
    }

    @Test
    void previewPendingSkipsEmptyFirstSteps() {
        String instA = findInstanceIdForBucket(17);
        when(nodeRegistry.listNodes("cloud", "t"))
                .thenReturn(List.of(Map.of("instance_id", instA)));
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        Map<String, Object> preview = service.previewNextUpgrade(rollout("pending", 0));

        assertEquals(5, preview.get("nominal_next_percent"));
        assertEquals(20, preview.get("effective_next_percent"));
        assertEquals("canary", preview.get("effective_next_state"));
        assertEquals(List.of(5), preview.get("skipped_steps"));
    }

    @Test
    void previewPendingSkipsToFullWhenNoNodeEntersEarlySteps() {
        String inst = findInstanceIdForBucket(73);
        when(nodeRegistry.listNodes("cloud", "t"))
                .thenReturn(List.of(Map.of("instance_id", inst)));
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        List<Integer> ladder = List.of(5, 20, 50, 100);
        int effective = service.computeNextEffectiveStep("t", ladder, 0);
        Map<String, Object> preview = service.previewNextUpgrade(rollout("pending", 0));

        assertEquals(100, effective);
        assertEquals(5, preview.get("nominal_next_percent"));
        assertEquals(100, preview.get("effective_next_percent"));
        assertEquals("full", preview.get("effective_next_state"));
        assertEquals(List.of(5, 20, 50), preview.get("skipped_steps"));
    }

    @Test
    void previewPendingFallsBackToFirstStepWhenNoLiveNodes() {
        when(nodeRegistry.listNodes("cloud", "t")).thenReturn(List.of());
        when(nodeRegistry.listNodes("gateway", "t")).thenReturn(List.of());

        Map<String, Object> preview = service.previewNextUpgrade(rollout("pending", 0));

        assertEquals(5, preview.get("nominal_next_percent"));
        assertEquals(5, preview.get("effective_next_percent"));
        assertEquals("canary", preview.get("effective_next_state"));
        assertEquals(List.of(), preview.get("skipped_steps"));
    }

    @Test
    void previewPausedStaysAtCurrentPercent() {
        Map<String, Object> preview = service.previewNextUpgrade(rollout("paused", 5));

        assertEquals(5, preview.get("nominal_next_percent"));
        assertEquals(5, preview.get("effective_next_percent"));
        assertEquals("canary", preview.get("effective_next_state"));
        assertEquals(List.of(), preview.get("skipped_steps"));
    }

    @Test
    void previewFullHasNoNextStep() {
        Map<String, Object> preview = service.previewNextUpgrade(rollout("full", 100));

        assertEquals(0, preview.get("nominal_next_percent"));
        assertEquals(0, preview.get("effective_next_percent"));
        assertNull(preview.get("effective_next_state"));
        assertEquals(List.of(), preview.get("skipped_steps"));
    }

    private static DeployRollout rollout(String state, int percent) {
        return new DeployRollout(
                "d1",
                "t",
                "b",
                state,
                percent,
                false,
                "v2",
                "v1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(5, 20, 50, 100),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                "op",
                "note");
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
