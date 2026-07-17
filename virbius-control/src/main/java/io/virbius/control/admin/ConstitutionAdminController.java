package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.ConstitutionService;
import io.virbius.control.service.ConstitutionService.CreateConstitutionRuleRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for Constitution rule and template management.
 *
 * <p>Base path: {@code /api/v1/admin/tenants/{tenantId}/constitution}
 *
 * <ul>
 *   <li>POST /rules — create a constitution rule</li>
 *   <li>GET /rules — list rules (optional ?status=active)</li>
 *   <li>GET /rules/{ruleId} — get a rule (optional ?version=1.0)</li>
 *   <li>PATCH /rules/{ruleId}/status — enable/disable a rule</li>
 *   <li>DELETE /rules/{ruleId} — delete a rule (optional ?version=1.0)</li>
 *   <li>POST /compile — compile active rules into a prompt template</li>
 *   <li>GET /templates — list compiled templates (optional ?version=1.2)</li>
 *   <li>GET /templates/{version} — get a specific template</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/constitution")
public class ConstitutionAdminController {

    private final ConstitutionService constitutionService;

    public ConstitutionAdminController(ConstitutionService constitutionService) {
        this.constitutionService = constitutionService;
    }

    // ---- Rule CRUD ----

    @PostMapping("/rules")
    public ApiResult<Map<String, Object>> createRule(
            @PathVariable("tenantId") String tenantId,
            @RequestBody CreateConstitutionRuleRequest body) {
        return ApiResult.ok(constitutionService.createRule(tenantId, body));
    }

    @GetMapping("/rules")
    public ApiResult<List<Map<String, Object>>> listRules(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResult.ok(constitutionService.listRules(tenantId, status));
    }

    @GetMapping("/rules/{ruleId}")
    public ApiResult<Map<String, Object>> getRule(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestParam(value = "version", required = false) String version) {
        return ApiResult.ok(constitutionService.getRule(tenantId, ruleId, version));
    }

    @PatchMapping("/rules/{ruleId}/status")
    public ApiResult<Void> updateRuleStatus(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestBody UpdateConstitutionStatusRequest body) {
        String version = body.version() != null ? body.version() : "1.0";
        constitutionService.updateRuleStatus(tenantId, ruleId, version, body.status());
        return ApiResult.ok();
    }

    @DeleteMapping("/rules/{ruleId}")
    public ApiResult<Void> deleteRule(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("ruleId") String ruleId,
            @RequestParam(value = "version", required = false) String version) {
        constitutionService.deleteRule(tenantId, ruleId, version != null ? version : "1.0");
        return ApiResult.ok();
    }

    // ---- Compilation ----

    @PostMapping("/compile")
    public ApiResult<Map<String, Object>> compile(
            @PathVariable("tenantId") String tenantId,
            @RequestBody CompileConstitutionRequest body) {
        return ApiResult.ok(constitutionService.compile(tenantId, body.version()));
    }

    // ---- Template retrieval ----

    @GetMapping("/templates")
    public ApiResult<List<Map<String, Object>>> listTemplates(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "version", required = false) String version) {
        return ApiResult.ok(constitutionService.listTemplates(tenantId, version));
    }

    @GetMapping("/templates/{version}")
    public ApiResult<Map<String, Object>> getTemplate(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("version") String version) {
        return ApiResult.ok(constitutionService.getTemplate(tenantId, version));
    }

    // ---- Request DTOs ----

    public record UpdateConstitutionStatusRequest(String version, String status) {}

    public record CompileConstitutionRequest(String version) {}
}
