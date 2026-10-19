package io.virbius.engine.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.deploy.NodePoolResolver;
import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** G7: the cache-reload subscriber's readiness flag + shutdown group cleanup. */
class CacheReloadSubscriberTest {

    @Test
    void notReadyBeforeStartAndShutdownDestroysThePerInstanceGroup() {
        NodePoolResolver resolver = mock(NodePoolResolver.class);
        when(resolver.instanceId()).thenReturn("abc");
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.xpending(anyString(), anyString(), any())).thenReturn(List.of());

        CacheReloadSubscriber subscriber = new CacheReloadSubscriber(
                new RuleCache(), new PolicyDataCache(), pool, resolver);

        assertFalse(subscriber.isReady());

        subscriber.shutdown();

        verify(jedis).xgroupDestroy("virbius:engine:cache-reload", "engine-cache-abc");
    }
}
