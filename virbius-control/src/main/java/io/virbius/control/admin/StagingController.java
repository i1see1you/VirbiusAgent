package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.domain.BundleStaging;
import io.virbius.control.service.BundleStagingService;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/bundles/{bundleId}/staging")
public class StagingController {

    private final BundleStagingService stagingService;

    public StagingController(BundleStagingService stagingService) {
        this.stagingService = stagingService;
    }

    @GetMapping("/{layer}")
    public ApiResult<BundleStaging> get(
            @PathVariable String tenantId,
            @PathVariable String bundleId,
            @PathVariable String layer) {
        return ApiResult.ok(stagingService.getStaging(tenantId, bundleId, layer));
    }

    @PatchMapping("/{layer}/rules/{ruleId}")
    public ApiResult<Map<String, Object>> applyRuleChange(
            @PathVariable String tenantId,
            @PathVariable String bundleId,
            @PathVariable String layer,
            @PathVariable String ruleId,
            @RequestBody Map<String, Object> body) {
        String diffType = body.get("diff_type") instanceof String s ? s : "rollout_only";
        return ApiResult.ok(stagingService.applyRuleChange(tenantId, bundleId, layer, ruleId, diffType));
    }

    @DeleteMapping("/{layer}/rules/{ruleId}")
    public ApiResult<Map<String, Object>> removeRuleDiff(
            @PathVariable String tenantId,
            @PathVariable String bundleId,
            @PathVariable String layer,
            @PathVariable String ruleId) {
        return ApiResult.ok(stagingService.removeRuleDiff(tenantId, bundleId, layer, ruleId));
    }
}
