package io.virbius.engine.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/** Dedicated producer for async rule-hit actions. Deliberately isolated from the audit
 * producer (which wants retries/batching): action delivery is at-most-once (acks=1,
 * retries=0) with a bounded block so a dead broker can never stall evaluation. */
@Configuration
@EnableConfigurationProperties(AsyncActionProperties.class)
public class AsyncActionKafkaConfig {

    public static final String TEMPLATE_BEAN = "asyncActionKafkaTemplate";

    @Bean(TEMPLATE_BEAN)
    public KafkaTemplate<String, String> asyncActionKafkaTemplate(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            AsyncActionProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        cfg.put(ProducerConfig.ACKS_CONFIG, "1");
        cfg.put(ProducerConfig.RETRIES_CONFIG, 0);
        cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, props.kafka().maxBlockMs());
        cfg.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        cfg.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        ProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(cfg);
        return new KafkaTemplate<>(factory);
    }
}
