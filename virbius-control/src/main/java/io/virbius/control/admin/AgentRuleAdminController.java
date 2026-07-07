package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.request.ValidateScriptRequest;
import io.virbius.control.domain.enums.RuleLayer;
import io.virbius.control.domain.enums.RuleRuntime;
import io.virbius.control.service.RuleService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Agent-layer rule CRUD (§5.3 — Groovy L3 Agent rules).
 *
 * <p>Agent rules use {@code layer=agent} and {@code runtime=agent-groovy}.
 * They run in the cloud layer (virbius-engine) with extended PolicyContext
 * that includes sessionHistory, sessionRiskScore, recordToolCall, etc.
 *
 * <p>Base path: {@code /api/v1/admin/tenants/{tenantId}/agent-rules}
 *
 * <p>This controller is a thin wrapper around {@link RuleService} that injects
 * agent-specific defaults (layer=agent, runtime=agent-groovy) so callers don't
 * need to specify them explicitly.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/agent-rules")
public class AgentRuleAdminController {

    private final RuleService ruleService;

    public AgentRuleAdminController(RuleService ruleService) {
        this.ruleService = ruleService;
    }

    /**
     * List all agent-layer rules for a tenant.
     */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> listAgentRules(
            @PathVariable("tenantId") String tenantId) {
        return ApiResult.ok(ruleService.listRules(tenantId, RuleLayer.AGENT.value()));
    }

    /**
     * Create or update an agent rule.
     * The layer is forced to "agent" and runtime defaults to "agent-groovy".
     */
    @PostMapping
    public ApiResult<Map<String, Object>> upsertAgentRule(
            @PathVariable("tenantId") String tenantId,
            @RequestBody UpsertAgentRuleRequest body) {
        UpsertRuleRequest req = new UpsertRuleRequest(
                body.ruleId(),
                body.bundleId(),
                RuleLayer.AGENT.value(),
                body.runtime() != null ? body.runtime() : RuleRuntime.AGENT_GROOVY.value(),
                body.reasonCode(),
                body.riskScore(),
                body.intentAction(),
                body.scope(),
                body.body(),
                body.editorMode(),
                body.condition(),
                body.isAsync(),
                body.asyncActionConfig());
        return ApiResult.ok(ruleService.upsertRule(tenantId, req));
    }

    /**
     * Get a single agent rule by ID.
     */
    @GetMapping("/{ruleId}")
    public ApiResult<Map<String, Object>> getAgentRule(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(ruleService.getRule(tenantId, ruleId));
    }

    /**
     * List all revisions of an agent rule.
     */
    @GetMapping("/{ruleId}/revisions")
    public ApiResult<List<Map<String, Object>>> listRevisions(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(ruleService.listRevisions(tenantId, ruleId));
    }

    /**
     * Validate an agent-groovy script without saving.
     */
    @PostMapping("/validate-script")
    public ApiResult<Map<String, Object>> validateScript(
            @PathVariable("tenantId") String tenantId,
            @RequestBody ValidateScriptRequest body) {
        String runtime = body.runtime() != null ? body.runtime() : RuleRuntime.AGENT_GROOVY.value();
        ValidateScriptRequest fixed = new ValidateScriptRequest(RuleLayer.AGENT.value(), runtime, body.body());
        return ApiResult.ok(ruleService.validateScript(tenantId, fixed));
    }

    /**
     * Get a specific revision of an agent rule.
     */
    @GetMapping("/{ruleId}/revisions/{revision}")
    public ApiResult<Map<String, Object>> getRevision(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @PathVariable("revision") int revision) {
        return ApiResult.ok(ruleService.getRevision(tenantId, ruleId, revision));
    }

    /**
     * Request DTO for agent rule upsert.
     * Unlike {@link UpsertRuleRequest}, layer and runtime are optional
     * (they default to agent / agent-groovy).
     */
    public record UpsertAgentRuleRequest(
            String ruleId,
            String bundleId,
            String runtime,
            String reasonCode,
            Integer riskScore,
            String intentAction,
            Map<String, Object> scope,
            Object body,
            String editorMode,
            Map<String, Object> condition,
            Boolean isAsync,
            String asyncActionConfig) {}
}
