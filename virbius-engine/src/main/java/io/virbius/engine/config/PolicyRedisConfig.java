package io.virbius.engine.config;

import io.virbius.policy.CounterStore;
import io.virbius.policy.GatewayListRedisMatcher;
import io.virbius.policy.ListMatchResultCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class PolicyRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(PolicyRedisConfig.class);

    @Bean
    public JedisPool jedisPool(@Value("${virbius.redis.url}") String redisUrl) {
        if (redisUrl == null || redisUrl.isBlank()) {
            throw new IllegalStateException("Redis is required for virbius-engine: virbius.redis.url must be set");
        }
        log.info("Creating JedisPool for redisUrl={}", redisUrl);
        return new JedisPool(redisUrl);
    }

    @Bean
    public GatewayListRedisMatcher gatewayListRedisMatcher(
            JedisPool jedisPool,
            @Value("${virbius.lists.redis.match-cache-ttl-sec:60}") long cacheTtlSec,
            @Value("${virbius.lists.redis.match-cache-max-entries:200000}") int cacheMaxEntries) {
        return new GatewayListRedisMatcher(
                jedisPool, new ListMatchResultCache(cacheTtlSec * 1000L, cacheMaxEntries));
    }
}
