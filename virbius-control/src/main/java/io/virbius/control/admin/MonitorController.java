package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import io.virbius.control.service.MonitorService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/monitor")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @GetMapping("/rule-ranking")
    public ApiResult<Map<String, Object>> ruleRanking(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return ApiResult.ok(monitorService.ruleRanking(tenantId, hours, limit));
    }

    @GetMapping("/scene-traffic")
    public ApiResult<Map<String, Object>> sceneTraffic(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResult.ok(monitorService.sceneTraffic(tenantId, hours));
    }

    @GetMapping("/degradation")
    public ApiResult<Map<String, Object>> degradation(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "24") int hours) {
        return ApiResult.ok(monitorService.degradation(tenantId, hours));
    }

    @GetMapping("/event-timeline")
    public ApiResult<Map<String, Object>> eventTimeline(
            @PathVariable("tenantId") String tenantId,
            @RequestParam(name = "hours", defaultValue = "48") int hours,
            @RequestParam(name = "limit", defaultValue = "30") int limit) {
        return ApiResult.ok(monitorService.eventTimeline(tenantId, hours, limit));
    }
}
