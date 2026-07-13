package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class KafkaTraceConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTraceConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final TraceEventIngestor ingestor;

    public KafkaTraceConsumer(TraceEventIngestor ingestor) {
        this.ingestor = ingestor;
    }

    @KafkaListener(
        topics = "${trace.ingest.kafka.topic:virbius-trace-events}",
        groupId = "${trace.ingest.kafka.group-id:virbius-trace-ingest}"
    )
    public void onMessage(String payload) {
        try {
            TraceEventIngestor.IngestResult result = ingestor.ingestPayload(payload);
            if ("rejected".equals(result.status())) {
                log.warn("trace kafka ingest rejected: {}", result.message());
            }
        } catch (Exception e) {
            log.warn("trace kafka ingest failed: {}", e.getMessage());
        }
    }
}
