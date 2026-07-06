package io.virbius.control.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
public class KafkaAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditConsumer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AuditEventIngestor ingestor;

    public KafkaAuditConsumer(AuditEventIngestor ingestor) {
        this.ingestor = ingestor;
    }

    @KafkaListener(topics = "${audit.ingest.kafka.topic:virbius-audit-events}")
    public void onMessage(String payload) {
        try {
            AuditEventIngestor.IngestResult result = ingestor.ingestPayload(payload);
            if ("rejected".equals(result.status())) {
                log.warn("audit kafka ingest rejected: {}", result.message());
            }
        } catch (Exception e) {
            log.warn("audit kafka ingest failed: {}", e.getMessage());
        }
    }
}
