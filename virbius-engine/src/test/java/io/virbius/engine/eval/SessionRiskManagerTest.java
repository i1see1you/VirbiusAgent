package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.PolicyDataCache.TenantPolicyData;
import io.virbius.engine.cache.PolicyDataCache.ToolPolicyEntry;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

class SessionRiskManagerTest {

    private JedisPool jedisPool;
    private Jedis jedis;
    private Pipeline pipeline;
    private PolicyDataCache policyDataCache;
    private SessionRiskManager manager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        pipeline = mock(Pipeline.class);
        policyDataCache = mock(PolicyDataCache.class);

        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.pipelined()).thenReturn(pipeline);

        // Default empty policy data
        when(policyDataCache.get("default"))
                .thenReturn(new TenantPolicyData(Map.of(), Map.of(), Map.of(), Map.of()));

        manager = new SessionRiskManager(
                Optional.of(jedisPool), policyDataCache,
                true, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);
    }

    @Test
    void returnsZeroWhenDisabled() {
        SessionRiskManager disabled = new SessionRiskManager(
                Optional.of(jedisPool), policyDataCache,
                false, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);

        RiskUpdateInput input = new RiskUpdateInput("sess-1", "default", 100, 0, 0, 0, 0);
        assertEquals(0, disabled.updateRiskScore(input));
    }

    @Test
    void returnsZeroWhenSessionIdBlank() {
        RiskUpdateInput input = new RiskUpdateInput("", "default", 100, 0, 0, 0, 0);
        assertEquals(0, manager.updateRiskScore(input));
    }

    @Test
    void returnsZeroWithoutRedis() {
        SessionRiskManager noRedis = new SessionRiskManager(
                Optional.empty(), policyDataCache,
                true, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);

        RiskUpdateInput input = new RiskUpdateInput("sess-1", "default", 100, 0, 0, 0, 0);
        assertEquals(0, noRedis.updateRiskScore(input));
    }

    @Test
    void computeToolWeightReturnsZeroForEmpty() {
        assertEquals(0, manager.computeToolWeight(Map.of()));
    }

    @Test
    void computeToolWeightReturnsZeroForNull() {
        assertEquals(0, manager.computeToolWeight(null));
    }

    @Test
    void computeToolWeightWithSingleTool() {
        Map<String, Long> counts = Map.of("read_file", 3L);
        int weight = manager.computeToolWeight(counts);
        // risk_class for read_file is "low" → 1
        // log(3+1) = log(4) ≈ 1.386 → round to 1
        // total = 1 * 1 = 1
        assertEquals(1, weight);
    }

    @Test
    void computeToolWeightWithMultipleTools() {
        Map<String, Long> counts = Map.of("read_file", 1L, "http_get", 5L);
        int weight = manager.computeToolWeight(counts);
        // read_file: low → 1, log(1+1)=log2≈0.693→1, total += 1
        // http_get: low → 1, log(5+1)=log6≈1.79→2, total += 2
        // total = 3
        assertEquals(3, weight);
    }

    @Test
    void computeToolWeightWithHighRiskTool() {
        ToolPolicyEntry entry = new ToolPolicyEntry("http_get", "network", null, 0, false, null);
        when(policyDataCache.get("default"))
                .thenReturn(new TenantPolicyData(Map.of(), Map.of(), Map.of(), Map.of("http_get", entry)));

        Map<String, Long> counts = Map.of("http_get", 1L);
        int weight = manager.computeToolWeight(counts);
        // network → 4, log(1+1)=log2≈0.693→1, total = 4
        assertEquals(4, weight);
    }

    @Test
    void applyDecayNoDecayWhenZero() {
        assertEquals(0, manager.applyDecay(0, 30));
    }

    @Test
    void applyDecayNoDecayWhenElapsedZero() {
        assertEquals(100, manager.applyDecay(100, 0));
    }

    @Test
    void applyDecayCutoffAfterTwoHours() {
        assertEquals(0, manager.applyDecay(100, 121));
    }

    @Test
    void applyDecayReducesValue() {
        int result = manager.applyDecay(100, 30);
        // 100 * exp(-30/30) = 100 * exp(-1) ≈ 36.79 → round to 37
        assertTrue(result < 100);
        assertTrue(result > 0);
    }

    @Test
    void getRiskScoreWithoutRedis() {
        SessionRiskManager noRedis = new SessionRiskManager(
                Optional.empty(), policyDataCache,
                true, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);
        assertEquals(0, noRedis.getRiskScore("sess-1"));
    }

    @Test
    void getRiskScoreReturnsStoredValue() {
        when(jedis.get("session:sess-1:risk_score")).thenReturn("42");
        assertEquals(42, manager.getRiskScore("sess-1"));
    }

    @Test
    void getRiskScoreReturnsZeroOnError() {
        when(jedis.get(anyString())).thenThrow(new RuntimeException("redis down"));
        assertEquals(0, manager.getRiskScore("sess-1"));
    }

    @Test
    void onFalcoAlertIncrementsCounter() {
        when(jedis.pipelined()).thenReturn(pipeline);

        manager.onFalcoAlert("sess-1");

        verify(pipeline).incr("session:sess-1:falco_pending");
        verify(pipeline).expire("session:sess-1:falco_pending", 3600);
        verify(pipeline).sync();
    }

    @Test
    void onFalcoAlertSkipsWhenDisabled() {
        SessionRiskManager disabled = new SessionRiskManager(
                Optional.of(jedisPool), policyDataCache,
                false, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);

        disabled.onFalcoAlert("sess-1");
        verify(pipeline, never()).incr(anyString());
    }

    @Test
    void onFalcoAlertSkipsWithoutRedis() {
        SessionRiskManager noRedis = new SessionRiskManager(
                Optional.empty(), policyDataCache,
                true, 0.1, 15, 10, 30.0, 120,
                80, 60, 30, 3600);

        noRedis.onFalcoAlert("sess-1");
        verify(pipeline, never()).incr(anyString());
    }

    @Test
    void fullUpdateFlowWithNewSession() throws Exception {
        // Simulate fresh session (no existing data in Redis)
        String sessionId = "sess-new";
        RiskUpdateInput input = new RiskUpdateInput(sessionId, "default", 100, 0, 15, 0, 0);

        when(pipeline.hgetAll("session:sess-new:risk_breakdown"))
                .thenReturn(mock(Response.class));
        when(pipeline.get("session:sess-new:risk_last_update"))
                .thenReturn(mock(Response.class));
        when(pipeline.hgetAll("session:sess-new:tool_counts"))
                .thenReturn(mock(Response.class));
        when(pipeline.get("session:sess-new:falco_pending"))
                .thenReturn(mock(Response.class));

        Response<Map<String, String>> breakdownResp = mock(Response.class);
        when(breakdownResp.get()).thenReturn(Map.of());
        Response<String> lastUpdateResp = mock(Response.class);
        when(lastUpdateResp.get()).thenReturn(null);
        Response<Map<String, String>> toolCountsResp = mock(Response.class);
        when(toolCountsResp.get()).thenReturn(Map.of());
        Response<String> falcoPendingResp = mock(Response.class);
        when(falcoPendingResp.get()).thenReturn(null);

        when(pipeline.hgetAll("session:sess-new:risk_breakdown")).thenReturn(breakdownResp);
        when(pipeline.get("session:sess-new:risk_last_update")).thenReturn(lastUpdateResp);
        when(pipeline.hgetAll("session:sess-new:tool_counts")).thenReturn(toolCountsResp);
        when(pipeline.get("session:sess-new:falco_pending")).thenReturn(falcoPendingResp);

        int score = manager.updateRiskScore(input);

        // base_risk = 100 * 0.1 = 10
        // tool_weight = 0 (no tool counts)
        // chain_anomaly = 0
        // prompt_injection = 0 (injectionHitCount=0)
        // falco_alert = 0
        // total = 10
        assertEquals(10, score);

        verify(pipeline).set(eq("session:sess-new:risk_score"), eq("10"));
        verify(pipeline, times(2)).sync();
    }
}
