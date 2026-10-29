package io.virbius.engine.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Async rule-hit action delivery (webhook / kafka). Delivery is at-most-once: no retries,
 * no outbox; failures are only logged and metered, never affecting the evaluation outcome. */
@ConfigurationProperties(prefix = "virbius.async-action")
public record AsyncActionProperties(
        boolean enabled,
        Webhook webhook,
        Kafka kafka,
        int deliveryThreads,
        int deliveryQueueMax,
        int contentMaxChars,
        int fieldMaxChars) {

    public AsyncActionProperties {
        if (webhook == null) {
            webhook = new Webhook(5000, "");
        }
        if (contentMaxChars <= 0) {
            contentMaxChars = 2000;
        }
        if (fieldMaxChars <= 0) {
            fieldMaxChars = 500;
        }
        if (kafka == null) {
            kafka = new Kafka("virbius-rule-actions", 1000);
        }
        if (deliveryThreads <= 0) {
            deliveryThreads = 2;
        }
        if (deliveryQueueMax <= 0) {
            deliveryQueueMax = 1000;
        }
    }

    /**
     * @param timeoutMs    connect+read budget per delivery
     * @param allowedHosts empty = allow all (self-hosted default); otherwise exact host
     *                     or host:port matching
     */
    public record Webhook(int timeoutMs, String allowedHosts) {

        public Webhook {
            if (timeoutMs <= 0) {
                timeoutMs = 5000;
            }
            allowedHosts = allowedHosts == null ? "" : allowedHosts;
        }

        /** Empty string means allow-all. */
        public List<String> allowedHostList() {
            if (allowedHosts.isBlank()) {
                return List.of();
            }
            return java.util.Arrays.stream(allowedHosts.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }
    }

    /**
     * @param topic      default target topic; must be pre-created when the broker forbids
     *                   auto-creation (no runtime alerting surface beyond metrics)
     * @param maxBlockMs producer metadata/block budget; async delivery tolerates this in
     *                   the delivery pool, never in the evaluation thread
     */
    public record Kafka(String topic, int maxBlockMs) {

        public Kafka {
            if (topic == null || topic.isBlank()) {
                topic = "virbius-rule-actions";
            }
            if (maxBlockMs <= 0) {
                maxBlockMs = 1000;
            }
        }
    }
}
