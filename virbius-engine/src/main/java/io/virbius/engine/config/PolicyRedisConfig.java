package io.virbius.engine.config;

import io.virbius.policy.CounterStore;
import io.virbius.policy.GatewayListRedisMatcher;
import io.virbius.policy.ListMatchResultCache;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class PolicyRedisConfig {

    @Bean
    public Optional<JedisPool> jedisPool(@Value("${virbius.redis.url:}") String redisUrl) {
        return CounterStore.createPool(redisUrl);
    }

    @Bean
    public Optional<GatewayListRedisMatcher> gatewayListRedisMatcher(
            Optional<JedisPool> jedisPool,
            @Value("${virbius.lists.redis.match-cache-ttl-sec:60}") long cacheTtlSec,
            @Value("${virbius.lists.redis.match-cache-max-entries:200000}") int cacheMaxEntries) {
        return GatewayListRedisMatcher.create(jedisPool, cacheTtlSec, cacheMaxEntries);
    }
}
