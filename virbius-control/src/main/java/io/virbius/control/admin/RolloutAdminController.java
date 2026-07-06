package io.virbius.control.admin;

import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.TenantRolloutPolicy;
import io.virbius.control.domain.dto.request.RolloutEvaluateRequest;
import io.virbius.control.domain.dto.request.UpdateRolloutRequest;
import io.virbius.control.repository.DeployRolloutRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import io.virbius.control.service.RolloutDashboardService;
import io.virbius.control.service.RolloutService;
import io.virbius.control.audit.AuditCenterService;
import io.virbius.control.audit.AuditIngestService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}")
public class RolloutAdminController {

    private final RolloutService rolloutService;
    private final TenantRolloutPolicyRepository policyRepository;
    private final RolloutDashboardService dashboardService;
    private final AuditIngestService auditIngestService;
    private final AuditCenterService auditCenterService;
    private final DeployRolloutRepository deployRolloutRepo;

    public RolloutAdminController(
            RolloutService rolloutService,
            TenantRolloutPolicyRepository policyRepository,
            RolloutDashboardService dashboardService,
            AuditIngestService auditIngestService,
            AuditCenterService auditCenterService,
            DeployRolloutRepository deployRolloutRepo) {
        this.rolloutService = rolloutService;
        this.policyRepository = policyRepository;
        this.dashboardService = dashboardService;
        this.auditIngestService = auditIngestService;
        this.auditCenterService = auditCenterService;
        this.deployRolloutRepo = deployRolloutRepo;
    }

    @PatchMapping("/rules/{ruleId}/rollout")
    public ApiResult<Map<String, Object>> applyRollout(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestBody UpdateRolloutRequest body) {
        requireNoActiveDeploy(tenantId);
        boolean force = body.force() != null && body.force();
        return ApiResult.ok(rolloutService.applyRollout(
                tenantId,
                ruleId,
                body.rolloutState(),
                body.canaryPercent(),
                force,
                body.comment(),
                "manual",
                "admin"));
    }

    @PostMapping("/rules/{ruleId}/rollout/publish")
    public ApiResult<Map<String, Object>> publish(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        requireNoActiveDeploy(tenantId);
        return ApiResult.ok(rolloutService.publish(tenantId, ruleId));
    }

    @PostMapping("/rules/{ruleId}/rollout/rollback")
    public ApiResult<Map<String, Object>> rollback(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        requireNoActiveDeploy(tenantId);
        return ApiResult.ok(rolloutService.rollback(tenantId, ruleId));
    }

    @PostMapping("/rules/{ruleId}/rollout/disable")
    public ApiResult<Map<String, Object>> disable(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(rolloutService.disable(tenantId, ruleId));
    }

    @PostMapping("/rules/{ruleId}/rollout/recover")
    public ApiResult<Map<String, Object>> recover(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(rolloutService.recover(tenantId, ruleId));
    }

    @PostMapping("/rules/{ruleId}/rollout/evaluate")
    public ApiResult<Map<String, Object>> evaluate(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestBody RolloutEvaluateRequest body) {
        return ApiResult.ok(
                rolloutService.evaluate(tenantId, ruleId, body.targetState(), body.canaryPercent()));
    }

    @GetMapping("/rollout-policy")
    public ApiResult<TenantRolloutPolicy> getPolicy(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(policyRepository.getOrDefault(tenantId));
    }

    @PutMapping("/rollout-policy")
    public ApiResult<TenantRolloutPolicy> putPolicy(
            @PathVariable("tenantId") String tenantId, @RequestBody TenantRolloutPolicy body) {
        TenantRolloutPolicy merged =
                new TenantRolloutPolicy(
                        tenantId,
                        body.autoMode() != null ? body.autoMode() : "assisted",
                        body.canaryLadder() != null ? body.canaryLadder() : TenantRolloutPolicy.defaults(tenantId).canaryLadder(),
                        body.minDryRunHours() > 0 ? body.minDryRunHours() : 1,
                        body.minReviewCount() > 0 ? body.minReviewCount() : 100,
                        body.maxReviewRate() > 0 ? body.maxReviewRate() : 0.05,
                        body.maxReviewSpikeRatio() > 0 ? body.maxReviewSpikeRatio() : 2.0,
                        body.minHoursPerStep() > 0 ? body.minHoursPerStep() : 12,
                        body.minBlockSamplesPerStep() > 0 ? body.minBlockSamplesPerStep() : 10,
                        body.allowForce(),
                        body.rollbackBlockSpikeRatio() > 0 ? body.rollbackBlockSpikeRatio() : 3.0,
                        body.edgeAuditSampleRateAllow() > 0 ? body.edgeAuditSampleRateAllow() : 0.1,
                        body.maxConcurrentRollouts() > 0 ? body.maxConcurrentRollouts() : 10);
        return ApiResult.ok(policyRepository.save(merged));
    }

    @GetMapping("/rules/{ruleId}/metrics")
    public ApiResult<Map<String, Object>> metrics(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestParam(value = "hours", defaultValue = "24") int hours) {
        return ApiResult.ok(dashboardService.metrics(tenantId, ruleId, hours));
    }

    @GetMapping("/rules/{ruleId}/rollout/timeline")
    public ApiResult<List<Map<String, Object>>> timeline(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(dashboardService.timeline(tenantId, ruleId));
    }

    @GetMapping("/rules/{ruleId}/audit-samples")
    public ApiResult<List<Map<String, Object>>> auditSamples(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestParam(value = "effective_action", defaultValue = "") String effectiveAction,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ApiResult.ok(dashboardService.auditSamples(tenantId, ruleId, effectiveAction, limit));
    }

    @GetMapping("/traces/{traceId}")
    public ApiResult<List<Map<String, Object>>> trace(
            @PathVariable("tenantId") String tenantId, @PathVariable("traceId") String traceId) {
        return ApiResult.ok(dashboardService.trace(tenantId, traceId));
    }

    @GetMapping("/rollout/ingest-health")
    public ApiResult<Map<String, Object>> ingestHealth(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "layer", defaultValue = "edge") String layer,
            @RequestParam(value = "hours", defaultValue = "24") int hours) {
        return ApiResult.ok(dashboardService.ingestHealth(tenantId, layer, hours));
    }

    @GetMapping("/audit/ingest-status")
    public ApiResult<Map<String, Object>> auditIngestStatus(@PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(auditIngestService.status(tenantId));
    }

    @GetMapping("/audit/trace/{traceId}")
    public ApiResult<Map<String, Object>> auditTraceDetail(
            @PathVariable("tenantId") String tenantId, @PathVariable("traceId") String traceId) {
        return ApiResult.ok(auditCenterService.traceDetail(tenantId, traceId));
    }

    @GetMapping("/audit/recent")
    public ApiResult<Map<String, Object>> auditRecent(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResult.ok(auditCenterService.recent(tenantId, limit));
    }

    @GetMapping("/rules/{ruleId}/rollout/gates")
    public ApiResult<List<Map<String, Object>>> gateLogs(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResult.ok(dashboardService.gateLogs(tenantId, ruleId, limit));
    }

    @PostMapping("/rules/{ruleId}/rollout/ladder/start")
    public ApiResult<Map<String, Object>> ladderStart(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        dashboardService.setLadderStatus(tenantId, ruleId, "running");
        return ApiResult.ok(Map.of("ladder_status", "running"));
    }

    @PostMapping("/rules/{ruleId}/rollout/ladder/pause")
    public ApiResult<Map<String, Object>> ladderPause(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        dashboardService.setLadderStatus(tenantId, ruleId, "paused");
        return ApiResult.ok(Map.of("ladder_status", "paused"));
    }

    private void requireNoActiveDeploy(String tenantId) {
        if (deployRolloutRepo.findActive(tenantId).isPresent()) {
            throw new BusinessException(423,
                    "Active machine canary deployment in progress. Please wait for it to complete or rollback before operating rule rollout.");
        }
    }
}
