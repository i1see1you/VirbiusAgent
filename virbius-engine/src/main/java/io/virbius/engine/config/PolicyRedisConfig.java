package io.virbius.engine.config;

import io.virbius.policy.CounterStore;
import io.virbius.policy.GatewayListRedisMatcher;
import io.virbius.policy.ListMatchResultCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;

@Configuration
public class PolicyRedisConfig {

    private static final Logger log = LoggerFactory.getLogger(PolicyRedisConfig.class);

    /**
     * Registers a {@link JedisPool} bean when {@code virbius.redis.url} is set.
     *
     * <p>At injection points, {@code Optional<JedisPool>} is resolved by Spring's
     * optional-dependency mechanism: it looks for a {@link JedisPool} bean (the
     * inner type), <em>not</em> an {@code Optional<JedisPool>} bean. Therefore the
     * bean method must return {@link JedisPool} directly — returning
     * {@code Optional<JedisPool>} causes every injection point to receive
     * {@code Optional.empty()}.
     *
     * <p>When Redis is intentionally disabled (blank URL), the conditional skips
     * bean creation and injection points get {@code Optional.empty()}.
     *
     * @param redisUrl the Redis connection URL (e.g. {@code redis://127.0.0.1:6379})
     * @return a new {@link JedisPool} backed by the given URL
     */
    @Bean
    @ConditionalOnProperty(name = "virbius.redis.url")
    public JedisPool jedisPool(@Value("${virbius.redis.url}") String redisUrl) {
        log.info("Creating JedisPool for redisUrl={}", redisUrl);
        return new JedisPool(redisUrl);
    }

    /**
     * Registers a {@link GatewayListRedisMatcher} bean when a {@link JedisPool}
     * is available.
     *
     * <p>Must return {@link GatewayListRedisMatcher} directly (not
     * {@code Optional<GatewayListRedisMatcher>}) so that Spring's
     * optional-dependency mechanism can find it by type at injection points
     * such as {@code Optional<GatewayListRedisMatcher>} in
     * {@link io.virbius.engine.eval.ScriptRuleRunner}. When Redis is disabled,
     * {@code @ConditionalOnBean} skips creation and injection points get
     * {@code Optional.empty()}.
     */
    @Bean
    @ConditionalOnBean(JedisPool.class)
    public GatewayListRedisMatcher gatewayListRedisMatcher(
            JedisPool jedisPool,
            @Value("${virbius.lists.redis.match-cache-ttl-sec:60}") long cacheTtlSec,
            @Value("${virbius.lists.redis.match-cache-max-entries:200000}") int cacheMaxEntries) {
        return new GatewayListRedisMatcher(
                jedisPool, new ListMatchResultCache(cacheTtlSec * 1000L, cacheMaxEntries));
    }
}
