package io.virbius.control.service.deploy;

import io.virbius.control.domain.DeployRollout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Advisory convergence view for kernel (falco) nodes: compares each node's reported applied
 * revision — written by the config-subscriber into the kernel node registry
 * ({@code virbius:nodes:kernel:{tenant}:{node_id}}) — against the revision that node should
 * run per the deploy pointer and its canary bucket.
 *
 * <p>Read-only and non-blocking by design: it never gates rollout transitions. Expected
 * revisions are computed from the same Redis facts the subscriber resolves against
 * ({@code virbius:deploy:active:{tenant}} + {@code virbius:falco:pointer:{tenant}}), so the
 * view matches node-side semantics exactly.
 *
 * <p>Only live nodes appear: registry keys expire after 60s without a heartbeat, so an
 * offline node is simply absent from the list.
 */
@Service
public class KernelConvergenceService {

    private final NodeRegistryService nodeRegistryService;
    private final DeployRolloutPointerStore pointerStore;
    private final FalcoArtifactStore falcoStore;

    public KernelConvergenceService(
            NodeRegistryService nodeRegistryService,
            DeployRolloutPointerStore pointerStore,
            FalcoArtifactStore falcoStore) {
        this.nodeRegistryService = nodeRegistryService;
        this.pointerStore = pointerStore;
        this.falcoStore = falcoStore;
    }

    public Map<String, Object> convergence(String tenantId, DeployRollout rollout) {
        long stableRevision = falcoStore.getStableRevision(tenantId);
        DeployRolloutPointer pointer = pointerStore.getPointer(tenantId).orElse(null);

        // Gray is active only when the live pointer belongs to this rollout and carries a
        // falco canary revision — mirroring resolve_pool() in config-subscriber.
        boolean grayActive = pointer != null
                && pointer.deployId().equals(rollout.deployId())
                && pointer.canaryFalcoRevision() > 0;
        long canaryRevision = grayActive ? pointer.canaryFalcoRevision() : 0;
        int canaryPercent = grayActive ? pointer.canaryPercent() : 0;

        long now = Instant.now().getEpochSecond();
        List<Map<String, Object>> nodes = new ArrayList<>();
        int converged = 0;
        int lagging = 0;
        int errors = 0;
        for (Map<String, String> node : nodeRegistryService.listNodes("kernel", tenantId)) {
            String nodeId = node.get("instance_id");
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            int bucket = BucketCalculator.bucketOf(nodeId);
            boolean inCanary = grayActive && (canaryPercent >= 100 || bucket < canaryPercent);
            long expectedRevision = inCanary ? canaryRevision : stableRevision;
            String expectedPool = inCanary ? "canary" : "stable";

            long reportedRevision = parseLong(node.get("revision"));
            String status = node.getOrDefault("status", "unknown");
            long lastSeen = parseLong(node.get("last_seen"));
            boolean nodeConverged = reportedRevision == expectedRevision && !"error".equals(status);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("node_id", nodeId);
            row.put("bucket", bucket);
            row.put("expected_pool", expectedPool);
            row.put("expected_revision", expectedRevision);
            row.put("reported_pool", node.getOrDefault("pool", "unknown"));
            row.put("reported_revision", reportedRevision);
            row.put("status", status);
            row.put("error", node.getOrDefault("error", ""));
            row.put("sighup_pids", node.getOrDefault("sighup_pids", ""));
            row.put("last_seen", lastSeen);
            row.put("last_seen_age_seconds", lastSeen > 0 ? Math.max(0, now - lastSeen) : -1);
            row.put("converged", nodeConverged);
            nodes.add(row);

            if ("error".equals(status)) {
                errors++;
            } else if (nodeConverged) {
                converged++;
            } else {
                lagging++;
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", nodes.size());
        summary.put("converged", converged);
        summary.put("lagging", lagging);
        summary.put("error", errors);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deploy_id", rollout.deployId());
        out.put("state", rollout.state());
        out.put("gray_active", grayActive);
        out.put("stable_falco_revision", stableRevision);
        out.put("canary_falco_revision", canaryRevision);
        out.put("canary_percent", canaryPercent);
        out.put("nodes", nodes);
        out.put("summary", summary);
        out.put("note", "advisory only, never blocks rollout; only live nodes appear (registry TTL 60s)");
        return out;
    }

    private static long parseLong(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
