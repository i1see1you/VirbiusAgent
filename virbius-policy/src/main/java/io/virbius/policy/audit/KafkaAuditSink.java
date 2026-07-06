package io.virbius.policy.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

public class KafkaAuditSink implements AuditEventSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditSink.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final KafkaTemplate<String, String> kafka;
    private final String topic;

    public KafkaAuditSink(KafkaTemplate<String, String> kafka, String topic) {
        this.kafka = kafka;
        this.topic = topic != null && !topic.isBlank() ? topic : "virbius-audit-events";
    }

    @Override
    public void publish(List<Map<String, String>> events) {
        for (Map<String, String> fields : events) {
            try {
                String tenantId = fields.getOrDefault("tenant_id", "");
                String json = mapper.writeValueAsString(fields);
                kafka.send(topic, tenantId, json);
            } catch (Exception e) {
                log.warn("audit kafka publish failed: {}", e.getMessage());
            }
        }
    }
}
