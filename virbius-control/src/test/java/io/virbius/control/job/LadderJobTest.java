package io.virbius.control.job;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.control.config.ControlJedisPools;
import io.virbius.control.repository.RegistryRepository;
import io.virbius.control.repository.TenantRolloutPolicyRepository;
import io.virbius.control.service.PublishOrchestrator;
import io.virbius.control.service.RolloutService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/** T6: LadderJob Redis health gate (auto-advance must not race the deploy lock). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LadderJobTest {

    @Mock JdbcTemplate jdbc;
    @Mock RegistryRepository registry;
    @Mock TenantRolloutPolicyRepository policyRepository;
    @Mock RolloutService rolloutService;
    @Mock PublishOrchestrator orchestrator;
    @Mock ControlJedisPools jedisPools;
    @Mock JedisPool pool;
    @Mock Jedis jedis;

    private LadderJob job() {
        return new LadderJob(jdbc, registry, policyRepository, rolloutService, orchestrator, jedisPools);
    }

    @Test
    void skipsTickWhenRedisUnhealthy() {
        when(jedisPools.pool()).thenReturn(Optional.of(pool));
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenThrow(new RuntimeException("redis down"));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        job().tick();

        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    void runsTickWhenRedisHealthy() {
        when(jedisPools.pool()).thenReturn(Optional.of(pool));
        when(pool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenReturn("PONG");
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        job().tick();

        verify(jdbc).queryForList(anyString());
    }

    @Test
    void runsTickWhenNoRedisPoolConfigured() {
        when(jedisPools.pool()).thenReturn(Optional.empty());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        job().tick();

        verify(jdbc).queryForList(anyString());
    }
}
