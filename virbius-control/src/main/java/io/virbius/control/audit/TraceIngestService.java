package io.virbius.control.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Trace ingest health for the admin API. Events are consumed from Kafka by
 * {@link KafkaTraceConsumer}.
 */
@Service
public class TraceIngestService {

    private final boolean enabled;
    private final String topic;

    public TraceIngestService(
            @Value("${trace.ingest.enabled:true}") boolean enabled,
            @Value("${trace.ingest.kafka.topic:virbius-trace-events}") String topic) {
        this.enabled = enabled;
        this.topic = topic != null && !topic.isBlank() ? topic : "virbius-trace-events";
    }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("backend", "kafka");
        out.put("stream_key", topic);
        out.put("topic", topic);
        return out;
    }
}
