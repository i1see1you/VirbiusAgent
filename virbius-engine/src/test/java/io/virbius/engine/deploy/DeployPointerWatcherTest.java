package io.virbius.engine.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** T8: per-instance instance-id resolution, group naming, shutdown destroy. */
class DeployPointerWatcherTest {

    @Test
    void resolveInstanceIdPrefersConfigured() {
        assertEquals("cfg-id", NodePoolResolver.resolveInstanceId("cfg-id", "host"));
    }

    @Test
    void resolveInstanceIdFallsBackToHostnameAndPid() {
        assertTrue(NodePoolResolver.resolveInstanceId("", "hostA").startsWith("hostA-"));
    }

    @Test
    void resolveInstanceIdUsesUnknownWhenHostnameBlank() {
        assertTrue(NodePoolResolver.resolveInstanceId("", "").startsWith("unknown-"));
    }

    @Test
    void groupNameIsPerInstanceAndDistinct() {
        assertEquals("engine-deploy-a", DeployPointerWatcher.groupName("a"));
        assertEquals("engine-deploy-b", DeployPointerWatcher.groupName("b"));
    }

    @Test
    void shutdownDrainsAndDestroysThePerInstanceGroup() {
        JedisPool pool = mock(JedisPool.class);
        Jedis jedis = mock(Jedis.class);
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.xpending(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        // The group is resolved in the constructor from the instance id (no
        // NodePoolResolver injection — that would create a bean cycle).
        DeployPointerWatcher watcher = new DeployPointerWatcher(Optional.of(pool), "abc", "");

        watcher.shutdown();

        verify(jedis).xgroupDestroy("virbius:deploy:notify", "engine-deploy-abc");
    }
}
