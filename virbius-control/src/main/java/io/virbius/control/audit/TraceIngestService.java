package io.virbius.control.audit;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.config.SqlDialectConfig;
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

/**
 * Consumes trace events from Redis Stream {@code virbius:trace:stream} and
 * persists them into {@code tb_agent_trace} via {@link TraceEventIngestor}.
 *
 * <p>Mirrors the pattern of {@link AuditIngestService} for audit events.
 */
@Service
@Profile({"dev", "staging", "prod"})
public class TraceIngestService {

    private static final Logger log = LoggerFactory.getLogger(TraceIngestService.class);
    private static final StreamEntryID STREAM_START = new StreamEntryID("0-0");

    private final Optional<JedisPool> pool;
    private final TraceEventIngestor ingestor;
    private final TraceIngestCheckpointRepository checkpointRepository;
    private final SqlDialectConfig dialect;
    private final String streamKey;
    private final boolean enabled;
    private final int batchSize;
    private final String consumerGroup;
    private volatile Instant lastPollAt;
    private volatile long lastBatchIngested;

    public TraceIngestService(
            ControlJedisPools jedisPools,
            TraceEventIngestor ingestor,
            TraceIngestCheckpointRepository checkpointRepository,
            SqlDialectConfig dialectConfig,
            @Value("${trace.ingest.enabled:true}") boolean enabled,
            @Value("${trace.ingest.redis.stream-key:virbius:trace:stream}") String streamKey,
            @Value("${trace.ingest.batch-size:256}") int batchSize,
            @Value("${trace.ingest.consumer-group:virbius-trace-ingest}") String consumerGroup) {
        this.pool = jedisPools.pool();
        this.ingestor = ingestor;
        this.checkpointRepository = checkpointRepository;
        this.dialect = dialectConfig;
        this.enabled = enabled;
        this.streamKey = streamKey;
        this.batchSize = batchSize > 0 ? batchSize : 256;
        this.consumerGroup = consumerGroup != null && !consumerGroup.isBlank()
                ? consumerGroup
                : "virbius-trace-ingest";
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("trace ingest disabled");
            return;
        }
        if (pool.isEmpty()) {
            log.warn("trace ingest enabled but Redis unavailable");
            return;
        }
        ensureConsumerGroup();
        backfillOnStartup();
    }

    @Scheduled(fixedDelayString = "${trace.ingest.poll-ms:1000}")
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
                log.debug("trace ingest batch={} last_id={}", ingested, lastId);
            }
        } catch (Exception e) {
            log.warn("trace ingest poll failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> status() {
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
        return out;
    }

    private void ensureConsumerGroup() {
        try (Jedis jedis = pool.get().getResource()) {
            try {
                jedis.xgroupCreate(streamKey, consumerGroup, STREAM_START, true);
                log.info("trace ingest created consumer group {} on {}", consumerGroup, streamKey);
            } catch (Exception e) {
                if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                    log.debug("trace ingest xgroup create: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("trace ingest ensure group failed: {}", e.getMessage());
        }
    }

    private void backfillOnStartup() {
        if (checkpointRepository.load(streamKey).isPresent()) {
            return;
        }
        log.info("trace ingest backfill from stream start (no checkpoint)");
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
            log.warn("trace ingest backfill failed: {}", e.getMessage());
        }
        if (total > 0) {
            log.info("trace ingest backfill ingested {} events", total);
        }
    }

    private boolean ingestFields(Map<String, String> fields) {
        try {
            String payload = fields.get("data");
            if (payload == null) {
                payload = fields.get("payload");
            }
            if (payload == null) {
                // Serialize fields as JSON fallback
                payload = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(fields);
            }
            TraceEventIngestor.IngestResult result = ingestor.ingestPayload(payload);
            return "accepted".equals(result.status());
        } catch (Exception e) {
            log.warn("trace ingest row failed: {}", e.getMessage());
            return false;
        }
    }
}
