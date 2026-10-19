package io.virbius.control.admin;

import io.virbius.control.audit.AlertService;
import io.virbius.control.audit.DecisionBomService;
import io.virbius.control.common.OperatorResolver;
import io.virbius.control.common.response.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C4: alert API. An alert is the entry point for traceability — its
 * {@code trace_id} expands into a Decision BOM (trajectory + judgement basis +
 * disposal), satisfying the "single alert → full reconstruction" requirement.
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/alerts")
public class AlertController {

    private final AlertService alertService;
    private final DecisionBomService decisionBom;

    public AlertController(AlertService alertService, DecisionBomService decisionBom) {
        this.alertService = alertService;
        this.decisionBom = decisionBom;
    }

    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return ApiResult.ok(alertService.list(tenantId, status, limit));
    }

    @GetMapping("/{alertId}")
    public ApiResult<?> get(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("alertId") String alertId) {
        Map<String, Object> alert = alertService.get(tenantId, alertId);
        if (alert == null) {
            return ApiResult.error(404, "not_found");
        }
        return ApiResult.ok(alert);
    }

    /** Acknowledge an alert (writes operator + timestamp). */
    @PostMapping("/{alertId}/ack")
    public ApiResult<Map<String, Object>> ack(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("alertId") String alertId,
            @RequestParam(value = "operator", required = false) String operator,
            HttpServletRequest request) {
        // Explicit operator param wins; otherwise X-User-Id / authenticated
        // principal; otherwise "unknown" — never a pretend "operator".
        String op = OperatorResolver.resolve(operator, request, "unknown");
        boolean ok = alertService.ack(tenantId, alertId, op);
        return ApiResult.ok(Map.of("acknowledged", ok));
    }

    /** Reconstruct the Decision BOM for the alert's trace (full traceability). */
    @GetMapping("/{alertId}/bom")
    public ApiResult<?> bom(
            @PathVariable("tenantId") String tenantId,
            @PathVariable("alertId") String alertId) {
        Map<String, Object> alert = alertService.get(tenantId, alertId);
        if (alert == null) {
            return ApiResult.error(404, "not_found");
        }
        Object traceId = alert.get("trace_id");
        if (traceId == null || String.valueOf(traceId).isBlank()) {
            return ApiResult.error(409, "alert_has_no_trace");
        }
        return ApiResult.ok(decisionBom.reconstruct(tenantId, String.valueOf(traceId)));
    }
}
