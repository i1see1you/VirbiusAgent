package io.virbius.control.config;

import io.virbius.policy.CounterStore;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/** Shared Redis pool for control-plane features (lists, artifacts, audit). */
@Component
public class ControlJedisPools {

    private final Optional<JedisPool> pool;

    public ControlJedisPools(@Value("${virbius.redis.url:}") String redisUrl) {
        this.pool = CounterStore.createPool(redisUrl);
    }

    public Optional<JedisPool> pool() {
        return pool;
    }
}
