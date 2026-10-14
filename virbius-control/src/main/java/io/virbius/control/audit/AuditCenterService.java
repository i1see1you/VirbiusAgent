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
                "tb_audit_events stores review/block/challenge and sampled allow events. "
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

    /**
     * Output-review statistics (B7): grouped by effective_action × reason_code.
     * Run a benign-output sample set first, then read this — deny/block counts on
     * the benign run are the output-review false positives.
     */
    public Map<String, Object> outputReviewStats(
            String tenantId, java.time.Instant from, java.time.Instant to) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant_id", tenantId);
        out.put("scene", "output");
        if (from != null) {
            out.put("from", from.toString());
        }
        if (to != null) {
            out.put("to", to.toString());
        }
        List<Map<String, Object>> rows = dashboardService.outputReviewStats(tenantId, from, to);
        out.put("groups", rows);
        // Answer-level aggregation: a streaming review emits one event per
        // window, so an "answer" (trace_id, falling back to event_id) is the
        // unit for totals/denies; window_events keeps the raw event count.
        Map<String, Object> answerStats =
                dashboardService.outputReviewAnswerStats(tenantId, from, to);
        long total = ((Number) answerStats.getOrDefault("answers", 0L)).longValue();
        long denied = ((Number) answerStats.getOrDefault("denied_answers", 0L)).longValue();
        long windowEvents = ((Number) answerStats.getOrDefault("window_events", 0L)).longValue();
        out.put("total", total);
        out.put("denied", denied);
        out.put("deny_rate", total == 0 ? 0.0 : denied / (double) total);
        out.put("window_events", windowEvents);
        return out;
    }
}
