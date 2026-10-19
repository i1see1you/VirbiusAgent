package io.virbius.engine.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.virbius.engine.cache.CacheReloadSubscriber;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleCacheSeeder;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** T1: /admin/ready 200-vs-503 contract (Redis + subscriber + seeder). */
class EngineAdminControllerTest {

    private final RuleCache cache = new RuleCache();
    private final PolicyDataCache policyData = new PolicyDataCache();
    private final CacheReloadSubscriber subscriber = mock(CacheReloadSubscriber.class);
    private final RuleCacheSeeder seeder = mock(RuleCacheSeeder.class);
    private final JedisPool pool = mock(JedisPool.class);
    private final Jedis jedis = mock(Jedis.class);

    private EngineAdminController controller() {
        return new EngineAdminController(cache, policyData, Optional.of(pool), subscriber, seeder, true);
    }

    @Test
    void readyWhenRedisSubscriberAndSeederAllUp() {
        when(pool.getResource()).thenReturn(jedis);
        when(subscriber.isReady()).thenReturn(true);
        when(seeder.isSeeded()).thenReturn(true);

        ResponseEntity<Map<String, Object>> r = controller().ready();

        assertEquals(200, r.getStatusCode().value());
        assertEquals("ready", r.getBody().get("status"));
        assertEquals("up", r.getBody().get("redis"));
        assertEquals("ready", r.getBody().get("cache_subscriber"));
        assertEquals("done", r.getBody().get("rule_seeder"));
    }

    @Test
    void notReadyWhenRedisUnreachable() {
        when(pool.getResource()).thenThrow(new RuntimeException("redis down"));
        when(subscriber.isReady()).thenReturn(true);
        when(seeder.isSeeded()).thenReturn(true);

        ResponseEntity<Map<String, Object>> r = controller().ready();

        assertEquals(503, r.getStatusCode().value());
        assertEquals("not-ready", r.getBody().get("status"));
        assertEquals("down", r.getBody().get("redis"));
    }

    @Test
    void notReadyWhileSeeding() {
        when(pool.getResource()).thenReturn(jedis);
        when(subscriber.isReady()).thenReturn(true);
        when(seeder.isSeeded()).thenReturn(false);

        ResponseEntity<Map<String, Object>> r = controller().ready();

        assertEquals(503, r.getStatusCode().value());
        assertEquals("seeding", r.getBody().get("rule_seeder"));
    }

    /** A3: readiness is latched — a transient Redis blip after first success must not
     *  flip the replica NotReady (which would pull every pod from the Service). */
    @Test
    void readinessIsLatchedAfterFirstSuccess() {
        when(pool.getResource()).thenReturn(jedis);
        when(subscriber.isReady()).thenReturn(true);
        when(seeder.isSeeded()).thenReturn(true);
        EngineAdminController c = controller();

        assertEquals(200, c.ready().getStatusCode().value());

        // Redis goes away: still Ready, but the body reports the degradation.
        when(pool.getResource()).thenThrow(new RuntimeException("redis blip"));
        ResponseEntity<Map<String, Object>> r = c.ready();
        assertEquals(200, r.getStatusCode().value());
        assertEquals("down", r.getBody().get("redis"));
    }

    /** With the latch disabled, readiness re-evaluates on every probe. */
    @Test
    void readinessReEvaluatesWhenLatchDisabled() {
        when(pool.getResource()).thenReturn(jedis);
        when(subscriber.isReady()).thenReturn(true);
        when(seeder.isSeeded()).thenReturn(true);
        EngineAdminController c =
                new EngineAdminController(cache, policyData, Optional.of(pool), subscriber, seeder, false);

        assertEquals(200, c.ready().getStatusCode().value());

        when(pool.getResource()).thenThrow(new RuntimeException("redis outage"));
        ResponseEntity<Map<String, Object>> r = c.ready();
        assertEquals(503, r.getStatusCode().value());
        assertEquals("not-ready", r.getBody().get("status"));
    }
}
