package io.virbius.control.audit;

import io.virbius.control.service.RolloutDashboardService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class AuditCenterService {

    private final RolloutDashboardService dashboardService;

    public AuditCenterService(RolloutDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    public Map<String, Object> traceDetail(String tenantId, String traceId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trace_id", traceId);
        out.put("tenant_id", tenantId);
        List<Map<String, Object>> dbEvents = dashboardService.trace(tenantId, traceId);
        out.put("db_events", dbEvents);
        out.put("db_count", dbEvents.size());
        out.put(
                "note",
                "tb_audit_events stores review/block/captcha and sampled allow events. "
                        + "Unsampled allow requests are archived as JSONL files only.");
        return out;
    }

    public Map<String, Object> recent(String tenantId, int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        List<Map<String, Object>> dbEvents = dashboardService.recent(tenantId, limit);
        out.put("db_events", dbEvents);
        out.put("db_count", dbEvents.size());
        out.put("note", "Recent " + limit + " audit events (ordered by intercepted_at DESC).");
        return out;
    }
}
