package io.virbius.engine.config;

import io.virbius.policy.audit.AuditEventPublisher;
import io.virbius.policy.audit.AuditEventSink;
import io.virbius.policy.audit.KafkaAuditSink;
import io.virbius.policy.audit.RedisStreamAuditSink;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import redis.clients.jedis.JedisPool;

@Configuration
public class AuditPublisherConfig {

    private AuditEventPublisher publisher;

    @Bean
    @Profile({"dev", "staging"})
    public AuditEventSink redisStreamAuditSink(
            Optional<JedisPool> jedisPool,
            @Value("${audit.publish.redis.stream-key:virbius:audit:events}") String streamKey) {
        return new RedisStreamAuditSink(jedisPool, streamKey);
    }

    @Bean
    @Profile("prod")
    public AuditEventSink kafkaAuditSink(
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${audit.publish.kafka.topic:virbius-audit-events}") String topic) {
        return new KafkaAuditSink(kafkaTemplate, topic);
    }

    @Bean
    public AuditEventPublisher auditEventPublisher(
            AuditEventSink sink,
            @Value("${audit.publish.async:true}") boolean async,
            @Value("${audit.publish.queue-max:2000}") int queueMax,
            @Value("${audit.publish.flush-batch-size:64}") int batchSize,
            @Value("${audit.publish.flush-interval-ms:100}") long flushIntervalMs) {
        publisher = new AuditEventPublisher(sink, async, queueMax, batchSize, flushIntervalMs);
        return publisher;
    }

    @PreDestroy
    void shutdownPublisher() {
        if (publisher != null) {
            publisher.close();
        }
    }
}
