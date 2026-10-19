package io.virbius.engine.cache;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.resps.StreamConsumerInfo;
import redis.clients.jedis.resps.StreamConsumersInfo;
import redis.clients.jedis.resps.StreamGroupInfo;

/** V4: startup reaper destroys only long-idle prefix groups, never the live/own one. */
class StreamGroupJanitorTest {

    private final Jedis jedis = mock(Jedis.class);

    private StreamGroupInfo group(String name) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(StreamGroupInfo.NAME, name);
        m.put(StreamGroupInfo.CONSUMERS, 1L);
        m.put(StreamGroupInfo.PENDING, 0L);
        m.put(StreamGroupInfo.LAST_DELIVERED, new redis.clients.jedis.StreamEntryID("0-0"));
        return new StreamGroupInfo(m);
    }

    private StreamConsumersInfo consumer(long idle) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(StreamConsumerInfo.NAME, "c");
        m.put(StreamConsumerInfo.IDLE, idle);
        m.put(StreamConsumerInfo.PENDING, 0L);
        m.put(StreamConsumerInfo.INACTIVE, null);
        return new StreamConsumersInfo(m);
    }

    @Test
    void destroysStalePrefixGroupButKeepsOwnAndUnrelated() {
        StreamGroupInfo stale = group("engine-cache-old");
        StreamGroupInfo own = group("engine-cache-mine");
        StreamGroupInfo other = group("engine-deploy-x");
        when(jedis.xinfoGroups("s")).thenReturn(List.of(stale, own, other));
        when(jedis.xinfoConsumers("s", "engine-cache-old")).thenReturn(List.of(consumer(9_999_999L)));

        StreamGroupJanitor.reapStaleGroups(jedis, "s", "engine-cache-", "engine-cache-mine", 600_000L);

        verify(jedis).xgroupDestroy("s", "engine-cache-old");
        verify(jedis, never()).xgroupDestroy("s", "engine-cache-mine");
        verify(jedis, never()).xgroupDestroy("s", "engine-deploy-x");
    }

    @Test
    void keepsFreshGroupWithNoConsumersAndActiveGroup() {
        StreamGroupInfo fresh = group("engine-cache-fresh");
        StreamGroupInfo active = group("engine-cache-active");
        when(jedis.xinfoGroups("s")).thenReturn(List.of(fresh, active));
        when(jedis.xinfoConsumers("s", "engine-cache-fresh")).thenReturn(List.of());
        when(jedis.xinfoConsumers("s", "engine-cache-active")).thenReturn(List.of(consumer(120L)));

        StreamGroupJanitor.reapStaleGroups(jedis, "s", "engine-cache-", "engine-cache-mine", 600_000L);

        verify(jedis, never()).xgroupDestroy(anyString(), anyString());
    }
}
