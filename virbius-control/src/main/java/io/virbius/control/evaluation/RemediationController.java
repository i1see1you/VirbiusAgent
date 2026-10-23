package io.virbius.control.evaluation;

import io.virbius.control.common.response.ApiResult;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 一键处置端点（M1-min）。权限沿用 ApiKeyRoutePolicy：GET=TENANT_VIEWER，POST=TENANT_ADMIN。
 *
 * <ul>
 *   <li>GET  …/remediation-preview?risk_ids=a,b —— 变量/查重/可执行性预览
 *   <li>POST …/remediation-apply —— 创建 dry_run 规则（幂等，不覆盖已有规则）
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/evaluation/reports/{reportId}")
public class RemediationController {

    private final RemediationService service;

    public RemediationController(RemediationService service) {
        this.service = service;
    }

    @GetMapping("/remediation-preview")
    public ApiResult<Map<String, Object>> preview(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("reportId") String reportId,
            @RequestParam("risk_ids") String riskIds) {
        List<String> ids = Arrays.stream(riskIds.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(10)
                .toList();
        return ApiResult.ok(service.preview(tenantId, reportId, ids));
    }

    @PostMapping("/remediation-apply")
    public ApiResult<Object> apply(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("reportId") String reportId,
            @RequestBody RemediationService.ApplyRequest body) {
        return ApiResult.ok(service.apply(tenantId, reportId, body));
    }
}
