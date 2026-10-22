package io.virbius.control.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Trace ingest health for the admin API. Events are consumed from Kafka by
 * {@link KafkaTraceConsumer}.
 *
 * <p>Besides the consumer config, the status exposes a producer-side signal:
 * the tenant's last received trace event. The control plane cannot see the
 * proxy's trace configuration, but "no events ever received" is the observable
 * symptom of a proxy started without VIRBIUS_TRACE_* — the console surfaces it
 * as an explicit warning instead of an empty page.
 */
@Service
public class TraceIngestService {

    private final boolean enabled;
    private final String topic;
    private final JdbcTemplate jdbc;

    public TraceIngestService(
            @Value("${virbius.trace.ingest.enabled:true}") boolean enabled,
            @Value("${virbius.trace.ingest.kafka.topic:virbius-trace-events}") String topic,
            JdbcTemplate jdbc) {
        this.enabled = enabled;
        this.topic = topic != null && !topic.isBlank() ? topic : "virbius-trace-events";
        this.jdbc = jdbc;
    }

    public Map<String, Object> status(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("backend", "kafka");
        out.put("stream_key", topic);
        out.put("topic", topic);
        if (tenantId != null && !tenantId.isBlank()) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT COUNT(*) AS total_events, MAX(occurred_at) AS last_event_at "
                                + "FROM tb_agent_trace WHERE tenant_id = ?",
                        tenantId);
                Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
                out.put("total_events", ((Number) row.getOrDefault("total_events", 0)).longValue());
                Object last = row.get("last_event_at");
                out.put("last_event_at", last != null ? last.toString() : null);
            } catch (Exception e) {
                out.put("total_events", null);
                out.put("last_event_at", null);
                out.put("stats_error", e.getMessage());
            }
        }
        return out;
    }
}
