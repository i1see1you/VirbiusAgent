package io.virbius.control.admin;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.BundleRelease;
import io.virbius.control.domain.BundleStaging;
import io.virbius.control.domain.DeployEvent;
import io.virbius.control.domain.DeployRollout;
import io.virbius.control.domain.RuleRevision;
import io.virbius.control.repository.DeployRolloutRepository;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.service.BundleReleaseService;
import io.virbius.control.service.BundleStagingService;
import io.virbius.control.service.RolloutDashboardService;
import io.virbius.control.service.deploy.DeployRolloutService;
import io.virbius.control.service.deploy.NodeRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/deploy-rollout")
public class DeployRolloutController {

    private final DeployRolloutService deployRolloutService;
    private final DeployRolloutRepository rolloutRepo;
    private final NodeRegistryService nodeRegistryService;
    private final BundleReleaseService releaseService;
    private final BundleStagingService stagingService;
    private final RegistryRepository ruleRepo;
    private final RolloutDashboardService dashboardService;

    public DeployRolloutController(
            DeployRolloutService deployRolloutService,
            DeployRolloutRepository rolloutRepo,
            NodeRegistryService nodeRegistryService,
            BundleReleaseService releaseService,
            BundleStagingService stagingService,
            RegistryRepository ruleRepo,
            RolloutDashboardService dashboardService) {
        this.deployRolloutService = deployRolloutService;
        this.rolloutRepo = rolloutRepo;
        this.nodeRegistryService = nodeRegistryService;
        this.releaseService = releaseService;
        this.stagingService = stagingService;
        this.ruleRepo = ruleRepo;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/next-version")
    public ApiResult<Map<String, String>> nextVersion(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "bundle_id", defaultValue = "poc-default") String bundleId) {
        String version = releaseService.nextVersion(tenantId, bundleId);
        return ApiResult.ok(Map.of("version", version));
    }

    @GetMapping("/diff-rules")
    public ApiResult<Map<String, Object>> diffRules(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "bundle_id", defaultValue = "poc-default") String bundleId,
            @RequestParam(name = "layer", required = false, defaultValue = "") String layerFilter) {
        String activeVersion = releaseService.getActiveVersion(tenantId, bundleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("base_version", activeVersion);

        // Load snapshot rules indexed by rule_id
        Map<String, Map<String, Object>> snapshotRules = new LinkedHashMap<>();
        if (activeVersion != null) {
            try {
                BundleRelease active = releaseService.getRelease(tenantId, bundleId, activeVersion);
                if (active != null && active.frozenSnapshot() != null) {
                    for (Map<String, Object> s : active.frozenSnapshot()) {
                        String rid = string(s, "rule_id");
                        if (rid != null) snapshotRules.put(rid, s);
                    }
                }
            } catch (Exception ignored) {
                // no prior release
            }
        }

        // Load current rules indexed by rule_id
        List<RuleRevision> currentRules = ruleRepo.listCurrentRules(tenantId, null);
        Map<String, RuleRevision> currentMap = new LinkedHashMap<>();
        for (RuleRevision r : currentRules) {
            currentMap.put(r.ruleId(), r);
        }

        // Per-layer diffs based on staging; filter by layer if specified
        List<String> targetLayers = layerFilter.isBlank()
                ? List.of("cloud", "gateway", "edge")
                : List.of(layerFilter);
        Map<String, Object> layers = new LinkedHashMap<>();
        for (String layer : targetLayers) {
            BundleStaging staging = stagingService.getStaging(tenantId, bundleId, layer);
            if (staging == null || staging.ruleDiffs() == null || staging.ruleDiffs().isEmpty()) continue;

            List<Map<String, Object>> changed = new ArrayList<>();
            for (Map.Entry<String, String> entry : staging.ruleDiffs().entrySet()) {
                String ruleId = entry.getKey();
                RuleRevision cur = currentMap.get(ruleId);
                Map<String, Object> snapshot = snapshotRules.get(ruleId);

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("rule_id", ruleId);
                item.put("diff_type", entry.getValue());
                item.put("rollout_state", cur != null ? cur.rolloutState() : "removed");
                item.put("layer", cur != null ? cur.layer() : (snapshot != null ? string(snapshot, "layer") : layer));

                // Categorize: added, removed, modified
                if (cur == null) {
                    item.put("change", "removed");
                } else if (snapshot == null) {
                    // Rule exists in current registry but not in active snapshot.
                    // If diff_type is "rollout_only" and rule is now non-execution,
                    // it was previously deployed via rollout and is now withdrawn → "removed".
                    String diffType = entry.getValue();
                    if ("rollout_only".equals(diffType) && ("disabled".equals(cur.rolloutState()) || "draft".equals(cur.rolloutState()))) {
                        item.put("change", "removed");
                    } else {
                        item.put("change", "added");
                    }
                } else {
                    String fromState = string(snapshot, "rollout_state");
                    String toState = cur.rolloutState();
                    item.put("from_state", fromState);
                    if (!java.util.Objects.equals(fromState, toState)) {
                        item.put("change", "modified");
                    } else {
                        item.put("change", "unchanged");
                    }
                }
                changed.add(item);
            }
            if (!changed.isEmpty()) {
                layers.put(layer, changed);
            }
        }
        result.put("layers", layers);

        // Summary counts
        long totalAdded = layers.values().stream().flatMap(v -> ((List<Map<String, Object>>) v).stream())
                .filter(i -> "added".equals(i.get("change"))).count();
        long totalRemoved = layers.values().stream().flatMap(v -> ((List<Map<String, Object>>) v).stream())
                .filter(i -> "removed".equals(i.get("change"))).count();
        long totalModified = layers.values().stream().flatMap(v -> ((List<Map<String, Object>>) v).stream())
                .filter(i -> "modified".equals(i.get("change"))).count();
        result.put("summary", Map.of("added", totalAdded, "removed", totalRemoved, "modified", totalModified));

        return ApiResult.ok(result);
    }

    @PostMapping("/prepare")
    public ApiResult<Map<String, Object>> prepare(
            @PathVariable("tenantId") String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request) {
        String bundleId = string(body, "bundle_id");
        String bundleVersion = string(body, "bundle_version");
        String targetVersion = string(body, "target_version");
        String layer = string(body, "layer");
        String description = string(body, "description");
        String operator = resolveOperator(request);

        DeployRollout rollout = deployRolloutService.prepare(
                tenantId, bundleId, bundleVersion, targetVersion, layer, description, operator);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/upgrade")
    public ApiResult<Map<String, Object>> upgrade(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.upgrade(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/pause")
    public ApiResult<Map<String, Object>> pause(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.pause(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/rollback")
    public ApiResult<Map<String, Object>> rollback(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.rollback(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/deploy-edge")
    public ApiResult<Map<String, Object>> deployEdge(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.deployEdge(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @PostMapping("/{deployId}/finalize")
    public ApiResult<Map<String, Object>> finalize(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String note = body != null ? string(body, "note") : null;
        String operator = resolveOperator(request);
        DeployRollout rollout = deployRolloutService.finalize(
                tenantId, deployId, operator, note);
        return ApiResult.ok(toMap(rollout));
    }

    @GetMapping("/active")
    public ApiResult<Map<String, Object>> active(
            @PathVariable("tenantId") String tenantId) {
        DeployRollout rollout = rolloutRepo.findActive(tenantId).orElse(null);
        if (rollout == null) {
            return ApiResult.ok(Map.of("active", false));
        }
        Map<String, Object> m = toMap(rollout);
        m.put("events", rolloutRepo.listEvents(rollout.deployId()).stream()
                .map(this::toEventMap).toList());
        m.put("cloud_nodes", nodeRegistryService.listNodes("cloud", tenantId));
        m.put("gateway_nodes", nodeRegistryService.listNodes("gateway", tenantId));
        m.put("pool_distribution", Map.of(
                "cloud", nodeRegistryService.poolDistribution("cloud", tenantId),
                "gateway", nodeRegistryService.poolDistribution("gateway", tenantId)));
        return ApiResult.ok(m);
    }

    @GetMapping("/list")
    public ApiResult<List<Map<String, Object>>> list(
            @PathVariable("tenantId") String tenantId) {
        List<DeployRollout> rollouts = rolloutRepo.listByTenant(tenantId, 20);
        return ApiResult.ok(rollouts.stream().map(this::toMap).toList());
    }

    @GetMapping("/metrics")
    public ApiResult<Map<String, Object>> metrics(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResult.ok(dashboardService.aggregateMetrics(tenantId, hours));
    }

    @GetMapping("/{deployId}")
    public ApiResult<Map<String, Object>> get(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("deployId") String deployId) {
        DeployRollout rollout = rolloutRepo.get(deployId)
                .filter(r -> r.tenantId().equals(tenantId))
                .orElseThrow(() -> new BusinessException("Deployment not found: " + deployId));
        Map<String, Object> m = toMap(rollout);
        m.put("events", rolloutRepo.listEvents(deployId).stream()
                .map(this::toEventMap).toList());
        m.put("cloud_nodes", nodeRegistryService.listNodes("cloud", tenantId));
        m.put("gateway_nodes", nodeRegistryService.listNodes("gateway", tenantId));
        return ApiResult.ok(m);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private Map<String, Object> toMap(DeployRollout r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deploy_id", r.deployId());
        m.put("tenant_id", r.tenantId());
        m.put("bundle_id", r.bundleId());
        m.put("state", r.state());
        m.put("canary_percent", r.canaryPercent());
        m.put("edge_deployed", r.edgeDeployed());
        m.put("target_version", r.targetVersion());
        m.put("prev_version", r.prevVersion());
        m.put("canary_engine_revision", r.canaryEngineRevision());
        m.put("stable_engine_revision", r.stableEngineRevision());
        m.put("canary_gateway_revision", r.canaryGatewayRevision());
        m.put("stable_gateway_revision", r.stableGatewayRevision());
        m.put("canary_edge_revision", r.canaryEdgeRevision());
        m.put("stable_edge_revision", r.stableEdgeRevision());
        m.put("canary_ladder", r.canaryLadder());
        m.put("started_at", r.startedAt());
        m.put("updated_at", r.updatedAt());
        m.put("finalized_at", r.finalizedAt());
        m.put("operator", r.operator());
        m.put("note", r.note());
        return m;
    }

    private Map<String, Object> toEventMap(DeployEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", e.eventId());
        m.put("event_type", e.eventType());
        m.put("reason", e.reason());
        m.put("rule_id", e.ruleId());
        m.put("from_state", e.fromState());
        m.put("from_percent", e.fromPercent());
        m.put("to_state", e.toState());
        m.put("to_percent", e.toPercent());
        m.put("layer", e.layer());
        m.put("operator", e.operator());
        m.put("note", e.note());
        m.put("created_at", e.createdAt());
        return m;
    }

    private static String string(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }

    private static String resolveOperator(HttpServletRequest request) {
        String user = request.getHeader("X-User-Id");
        if (user != null && !user.isBlank()) return user;
        user = request.getRemoteUser();
        if (user != null && !user.isBlank()) return user;
        return "system";
    }
}
