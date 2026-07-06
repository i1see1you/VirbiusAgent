package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.dto.request.CompileConditionRequest;
import io.virbius.control.domain.dto.request.ParseConditionRequest;
import io.virbius.control.domain.dto.request.UpdateRuleStatusRequest;
import io.virbius.control.domain.dto.request.UpdateRuntimeRequest;
import io.virbius.control.domain.dto.request.UpsertRuleRequest;
import io.virbius.control.domain.dto.request.ValidateScriptRequest;
import io.virbius.control.service.PublishService;
import io.virbius.control.service.RuleAuthoringService;
import io.virbius.control.service.RuleService;
import io.virbius.control.service.RuleSimulateService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/rules")
public class RuleAdminController {

    private final RuleService ruleService;
    private final PublishService publishService;
    private final RuleAuthoringService authoringService;
    private final RuleSimulateService simulateService;

    public RuleAdminController(
            RuleService ruleService,
            PublishService publishService,
            RuleAuthoringService authoringService,
            RuleSimulateService simulateService) {
        this.ruleService = ruleService;
        this.publishService = publishService;
        this.authoringService = authoringService;
        this.simulateService = simulateService;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> listRules(
            @PathVariable("tenantId") String tenantId, @RequestParam(value = "layer", required = false) String layer) {
        return ApiResult.ok(ruleService.listRules(tenantId, layer));
    }

    @PostMapping
    public ApiResult<Map<String, Object>> upsertRule(
            @PathVariable("tenantId") String tenantId, @RequestBody UpsertRuleRequest body) {
        return ApiResult.ok(ruleService.upsertRule(tenantId, body));
    }

    @PostMapping("/validate-script")
    public ApiResult<Map<String, Object>> validateScript(
            @PathVariable("tenantId") String tenantId, @RequestBody ValidateScriptRequest body) {
        return ApiResult.ok(ruleService.validateScript(tenantId, body));
    }

    @PostMapping("/compile-condition")
    public ApiResult<Map<String, Object>> compileCondition(
            @PathVariable("tenantId") String tenantId, @RequestBody CompileConditionRequest body) {
        return ApiResult.ok(authoringService.compile(body.layer(), body.runtime(), body.condition()));
    }

    @PostMapping("/parse-condition")
    public ApiResult<Map<String, Object>> parseCondition(
            @PathVariable("tenantId") String tenantId, @RequestBody ParseConditionRequest body) {
        return ApiResult.ok(authoringService.parse(body.layer(), body.runtime(), body.script()));
    }

    @GetMapping("/recipes")
    public ApiResult<Map<String, Object>> recipes(
            @PathVariable("tenantId") String tenantId, @RequestParam(value = "layer", required = false) String layer) {
        return ApiResult.ok(authoringService.recipes(layer));
    }

    @PostMapping("/simulate")
    public ApiResult<Map<String, Object>> simulate(
            @PathVariable("tenantId") String tenantId, @RequestBody Map<String, Object> body) {
        return ApiResult.ok(simulateService.simulate(tenantId, body));
    }

    @GetMapping("/{ruleId}")
    public ApiResult<Map<String, Object>> getRule(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(ruleService.getRule(tenantId, ruleId));
    }

    @GetMapping("/{ruleId}/revisions")
    public ApiResult<List<Map<String, Object>>> listRevisions(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(ruleService.listRevisions(tenantId, ruleId));
    }

    @GetMapping("/{ruleId}/revisions/{revision}")
    public ApiResult<Map<String, Object>> getRevision(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId, @PathVariable("revision") int revision) {
        return ApiResult.ok(ruleService.getRevision(tenantId, ruleId, revision));
    }

    @PatchMapping("/{ruleId}/status")
    public ApiResult<Map<String, Object>> updateRuleStatus(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestBody UpdateRuleStatusRequest body) {
        return ApiResult.ok(ruleService.updateRuleStatus(tenantId, ruleId, body.ruleStatus()));
    }

    @PatchMapping("/{ruleId}/runtime")
    public ApiResult<Map<String, Object>> updateRuntime(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId, @RequestBody UpdateRuntimeRequest body) {
        return ApiResult.ok(ruleService.updateRuntime(tenantId, ruleId, body.enforceMode(), body.canaryPercent()));
    }

    /** PoC: {@code ruleId} is ignored; refreshes entire tenant RuleCache (runtime_only). */
    @PostMapping("/{ruleId}/runtime/publish-snapshot")
    public ApiResult<Map<String, Object>> runtimeSnapshot(
            @PathVariable("tenantId") String tenantId, @PathVariable("ruleId") String ruleId) {
        return ApiResult.ok(publishService.runtimeSnapshot(tenantId));
    }
}