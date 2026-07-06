package io.virbius.control.audit;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.config.SqlDialectConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadParams;
import redis.clients.jedis.resps.StreamEntry;

@Service
@Profile({"dev", "staging"})
public class AuditIngestService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestService.class);
    private static final StreamEntryID STREAM_START = new StreamEntryID("0-0");

    private final Optional<JedisPool> pool;
    private final AuditEventIngestor ingestor;
    private final AuditIngestCheckpointRepository checkpointRepository;
    private final SqlDialectConfig dialect;
    private final String streamKey;
    private final boolean enabled;
    private final int batchSize;
    private final String consumerGroup;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile Instant lastPollAt;
    private volatile long lastBatchIngested;

    public AuditIngestService(
            ControlJedisPools jedisPools,
            AuditEventIngestor ingestor,
            AuditIngestCheckpointRepository checkpointRepository,
            SqlDialectConfig dialectConfig,
            @Value("${audit.ingest.enabled:true}") boolean enabled,
            @Value("${audit.ingest.redis.stream-key:virbius:audit:events}") String streamKey,
            @Value("${audit.ingest.batch-size:256}") int batchSize,
            @Value("${audit.ingest.consumer-group:virbius-audit-ingest}") String consumerGroup) {
        this.pool = jedisPools.pool();
        this.ingestor = ingestor;
        this.checkpointRepository = checkpointRepository;
        this.dialect = dialectConfig;
        this.enabled = enabled;
        this.streamKey = streamKey;
        this.batchSize = batchSize > 0 ? batchSize : 256;
        this.consumerGroup = consumerGroup != null && !consumerGroup.isBlank()
                ? consumerGroup
                : "virbius-audit-ingest";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("audit ingest disabled");
            return;
        }
        if (pool.isEmpty()) {
            log.warn("audit ingest enabled but Redis unavailable");
            return;
        }
        ensureConsumerGroup();
        backfillOnStartup();
    }

    @Scheduled(fixedDelayString = "${audit.ingest.poll-ms:1000}")
    public void poll() {
        if (!enabled || pool.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.get().getResource()) {
            StreamEntryID cursor = checkpointRepository.load(streamKey).orElse(STREAM_START);
            XReadParams params = XReadParams.xReadParams().count(batchSize).block(500);
            List<Map.Entry<String, List<StreamEntry>>> batches =
                    jedis.xread(params, Map.of(streamKey, cursor));
            lastPollAt = Instant.now();
            if (batches == null || batches.isEmpty()) {
                return;
            }
            long ingested = 0;
            StreamEntryID lastId = cursor;
            for (Map.Entry<String, List<StreamEntry>> batch : batches) {
                for (StreamEntry entry : batch.getValue()) {
                    lastId = entry.getID();
                    if (ingestFields(entry.getFields())) {
                        ingested++;
                    }
                }
            }
            if (!lastId.equals(cursor)) {
                checkpointRepository.save(streamKey, lastId);
            }
            lastBatchIngested = ingested;
            if (ingested > 0) {
                log.debug("audit ingest batch={} last_id={}", ingested, lastId);
            }
        } catch (Exception e) {
            log.warn("audit ingest poll failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> status(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("stream_key", streamKey);
        out.put("redis_ok", pool.isPresent());
        out.put("last_poll_at", lastPollAt != null ? lastPollAt.toString() : null);
        out.put("last_batch_ingested", lastBatchIngested);
        checkpointRepository.loadRaw(streamKey).ifPresent(id -> out.put("checkpoint", id));
        if (pool.isPresent()) {
            try (Jedis jedis = pool.get().getResource()) {
                out.put("stream_length", jedis.xlen(streamKey));
            } catch (Exception e) {
                out.put("stream_length_error", e.getMessage());
            }
        }
        Long dbTotal = countDbEvents(tenantId, null);
        Long db24h = countDbEvents(tenantId, 24);
        out.put("db_events_total", dbTotal != null ? dbTotal : 0L);
        out.put("db_events_24h", db24h != null ? db24h : 0L);
        Object streamLen = out.get("stream_length");
        if (streamLen instanceof Long len && dbTotal != null) {
            out.put("lag_estimate", Math.max(0L, len - dbTotal));
        }
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

    private void ensureConsumerGroup() {
        try (Jedis jedis = pool.get().getResource()) {
            try {
                jedis.xgroupCreate(streamKey, consumerGroup, STREAM_START, true);
                log.info("audit ingest created consumer group {} on {}", consumerGroup, streamKey);
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                    log.debug("audit ingest xgroup create: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("audit ingest ensure group failed: {}", e.getMessage());
        }
    }

    private void backfillOnStartup() {
        if (checkpointRepository.load(streamKey).isPresent()) {
            return;
        }
        log.info("audit ingest backfill from stream start (no checkpoint)");
        long total = 0;
        StreamEntryID cursor = STREAM_START;
        try (Jedis jedis = pool.get().getResource()) {
            while (true) {
                List<Map.Entry<String, List<StreamEntry>>> batches =
                        jedis.xread(XReadParams.xReadParams().count(batchSize), Map.of(streamKey, cursor));
                if (batches == null || batches.isEmpty()) {
                    break;
                }
                StreamEntryID lastId = cursor;
                for (Map.Entry<String, List<StreamEntry>> batch : batches) {
                    for (StreamEntry entry : batch.getValue()) {
                        lastId = entry.getID();
                        if (ingestFields(entry.getFields())) {
                            total++;
                        }
                    }
                }
                if (lastId.equals(cursor)) {
                    break;
                }
                cursor = lastId;
                checkpointRepository.save(streamKey, lastId);
            }
        } catch (Exception e) {
            log.warn("audit ingest backfill failed: {}", e.getMessage());
        }
        if (total > 0) {
            log.info("audit ingest backfill ingested {} events", total);
        }
    }

    private boolean ingestFields(Map<String, String> fields) {
        try {
            String payload = fields.get("payload");
            if (payload == null) {
                payload = mapper.writeValueAsString(fields);
            }
            AuditEventIngestor.IngestResult result = ingestor.ingestPayload(payload);
            return "accepted".equals(result.status()) || "skipped_allow".equals(result.status());
        } catch (Exception e) {
            log.warn("audit ingest row failed: {}", e.getMessage());
            return false;
        }
    }
}
