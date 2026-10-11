package io.virbius.control.integrate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/integrate")
public class IntegrateAdminController {

    private final ObservationQueryService queryService;

    public IntegrateAdminController(ObservationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/apps")
    public Map<String, Object> apps(@PathVariable String tenantId) {
        List<Map<String, Object>> apps = queryService.apps(tenantId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apps", apps);
        out.put("count", apps.size());
        return out;
    }

    @GetMapping("/apps/{appId}/observations")
    public Map<String, Object> observations(
            @PathVariable String tenantId,
            @PathVariable String appId,
            @RequestParam(value = "call_id", required = false) String callId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "500") int limit) {
        List<Map<String, Object>> events = queryService.query(tenantId, appId, callId, since, until, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", events);
        out.put("count", events.size());
        out.put("note", "Mode A observations (advisory). Not hash-chained; not session risk input.");
        return out;
    }

    @GetMapping("/apps/{appId}/coverage")
    public Map<String, Object> coverage(@PathVariable String tenantId, @PathVariable String appId) {
        List<Map<String, Object>> tools = queryService.coverage(tenantId, appId);
        List<Map<String, Object>> warnings = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            if ("B".equals(tool.get("recommended_mode")) && "A".equals(tool.get("bound_mode"))) {
                warnings.add(tool);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tools", tools);
        out.put("warnings", warnings);
        out.put("note", "warn-only: high/medium risk tools bound in mode A");
        return out;
    }

    @GetMapping("/apps/{appId}/liveness")
    public Map<String, Object> liveness(@PathVariable String tenantId, @PathVariable String appId) {
        List<Map<String, Object>> devices = queryService.liveness(tenantId, appId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", devices);
        out.put("count", devices.size());
        return out;
    }
}
