package io.virbius.control.audit;

import io.virbius.control.config.SqlDialectConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Audit ingest health for the admin API. Events are consumed from Kafka by
 * {@link KafkaAuditConsumer}.
 */
@Service
public class AuditIngestService {

    private final AuditEventIngestor ingestor;
    private final SqlDialectConfig dialect;
    private final boolean enabled;
    private final String topic;

    public AuditIngestService(
            AuditEventIngestor ingestor,
            SqlDialectConfig dialectConfig,
            @Value("${audit.ingest.enabled:true}") boolean enabled,
            @Value("${audit.ingest.kafka.topic:virbius-audit-events}") String topic) {
        this.ingestor = ingestor;
        this.dialect = dialectConfig;
        this.enabled = enabled;
        this.topic = topic != null && !topic.isBlank() ? topic : "virbius-audit-events";
    }

    public Map<String, Object> status(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("backend", "kafka");
        out.put("stream_key", topic);
        out.put("topic", topic);
        Long dbTotal = countDbEvents(tenantId, null);
        Long db24h = countDbEvents(tenantId, 24);
        out.put("db_events_total", dbTotal != null ? dbTotal : 0L);
        out.put("db_events_24h", db24h != null ? db24h : 0L);
        return out;
    }

    private Long countDbEvents(String tenantId, Integer hours) {
        if (hours == null) {
            return jdbcCount(
                    """
                    SELECT COUNT(*) FROM tb_audit_events WHERE tenant_id = ?
                    """,
                    tenantId);
        }
        String timeExpr;
        Object timeArg;
        if (dialect.isMysql()) {
            timeExpr = "DATE_SUB(NOW(), INTERVAL ? HOUR)";
            timeArg = hours;
        } else if (dialect.isPostgresql()) {
            timeExpr = "NOW() - INTERVAL '?' HOUR";
            timeArg = hours;
        } else {
            timeExpr = "datetime('now', ?)";
            timeArg = "-" + hours + " hours";
        }
        return jdbcCount(
                "SELECT COUNT(*) FROM tb_audit_events WHERE tenant_id = ? AND intercepted_at >= " + timeExpr,
                tenantId,
                timeArg);
    }

    private Long jdbcCount(String sql, Object... args) {
        try {
            return ingestor.countForStatus(sql, args);
        } catch (Exception e) {
            return null;
        }
    }
}
