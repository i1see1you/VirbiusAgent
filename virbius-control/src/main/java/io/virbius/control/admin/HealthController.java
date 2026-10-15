package io.virbius.control.admin;

import io.virbius.control.common.response.ApiResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final KafkaListenerEndpointRegistry listenerRegistry;

    public HealthController(KafkaListenerEndpointRegistry listenerRegistry) {
        this.listenerRegistry = listenerRegistry;
    }

    @GetMapping("/api/v1/health")
    public ApiResult<Map<String, String>> health() {
        return ApiResult.ok(Map.of("status", "ok", "service", "virbius-control"));
    }

    /**
     * Readiness: the web server is up AND every Kafka listener container has
     * joined its group and holds at least one partition. {@link #health()}
     * answers as soon as the web server is up — before the consumers have
     * finished joining — so a probe on it marks the pod Ready while its ingest
     * plane is still taking shape. With a shared consumer group a partial
     * assignment is normal (other replicas hold the rest); what readiness must
     * guarantee is that this instance is actually consuming something.
     */
    @GetMapping("/api/v1/ready")
    public ResponseEntity<?> ready() {
        List<Map<String, Object>> consumers = new ArrayList<>();
        boolean ok = true;
        for (var container : listenerRegistry.getListenerContainers()) {
            boolean running = container.isRunning();
            var assigned = container.getAssignedPartitions();
            boolean joined = running && assigned != null && !assigned.isEmpty();
            ok = ok && joined;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("listener", container.getListenerId());
            row.put("running", running);
            row.put("assigned_partitions", assigned == null ? 0 : assigned.size());
            consumers.add(row);
        }
        if (ok) {
            return ResponseEntity.ok(ApiResult.ok(consumers));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResult.error(503, "kafka consumers not joined"));
    }
}
