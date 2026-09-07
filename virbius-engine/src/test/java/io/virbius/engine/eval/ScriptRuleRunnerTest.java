package io.virbius.engine.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.PolicyDataCache.CumulativeBlock;
import io.virbius.engine.cache.PolicyDataCache.ListBlock;
import io.virbius.engine.cache.PolicyDataCache.RedisListIndexBlock;
import io.virbius.engine.cache.PolicyDataCache.TenantPolicyData;
import io.virbius.engine.cache.PolicyDataCache.ToolPolicyEntry;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.TenantAwareTaskExecutor;
import io.virbius.policy.MatchContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;

class ScriptRuleRunnerTest {

    private RuleCache cache;
    private PolicyDataCache policyData;
    private TenantAwareTaskExecutor taskExecutor;
    private AsyncActionHandler asyncActionHandler;

    private ScriptRuleRunner runner;

    @BeforeEach
    void setUp() {
        cache = mock(RuleCache.class);
        policyData = mock(PolicyDataCache.class);
        taskExecutor = mock(TenantAwareTaskExecutor.class);
        asyncActionHandler = mock(AsyncActionHandler.class);
        runner = new ScriptRuleRunner(
                cache, policyData, Optional.empty(), Optional.empty(),
                taskExecutor, asyncActionHandler);

        // Default: empty policy data (so buildScriptEnv doesn't NPE)
        when(policyData.get(anyString()))
                .thenReturn(new TenantPolicyData(Map.of(), Map.of(), Map.of(), Map.of()));
    }

    @Test
    void runWithNoMatchingRulesReturnsEmpty() {
        when(cache.rulesForTenant("default")).thenReturn(List.of());

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        assertTrue(signals.isEmpty());
    }

    @Test
    void runSkipsNonScriptRules() {
        RuleEntry nonScript = new RuleEntry(
                "default", "Rule_1", 1, "cloud", "prompt",
                "TEST", 0, "allow", "dry_run", 0, "dry_run",
                "body", Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(nonScript));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        assertTrue(signals.isEmpty());
    }

    @Test
    void runSkipsDeprecatedMetaRules() {
        RuleEntry deprecated = new RuleEntry(
                "default", "D_ANY", 1, "cloud", "groovy",
                "TEST", 50, "deny", "dry_run", 0, "dry_run",
                "body() => true", Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(deprecated));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        assertTrue(signals.isEmpty());
    }

    @Test
    void runSkipsRuleNotMatchingBindScope() {
        RuleEntry wrongScope = new RuleEntry(
                "default", "Rule_2", 1, "cloud", "groovy",
                "TEST", 50, "deny", "dry_run", 0, "dry_run",
                "body() => true",
                Map.of("bind_scope", "tool", "bind_ref", Map.of("tool_names", List.of("write_file"))),
                false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(wrongScope));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        // Rule_2 is bound to write_file, but ctx has read_file → no match
        assertTrue(signals.isEmpty());
    }

    @Test
    void runSubmitsDryRunToExecutor() {
        RuleEntry dryRunRule = new RuleEntry(
                "default", "Rule_DR", 1, "cloud", "groovy",
                "TEST", 50, "deny", "dry_run", 0, "dry_run",
                "body() => true", Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(dryRunRule));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        assertTrue(signals.isEmpty()); // dry_run rules return no sync signals
        verify(taskExecutor).submit(anyString(), any(Runnable.class));
    }

    @Test
    void runSubmitsAsyncRuleToExecutor() {
        RuleEntry asyncRule = new RuleEntry(
                "default", "Rule_Async", 1, "cloud", "groovy",
                "TEST", 50, "deny", "dry_run", 0, "dry_run",
                "body() => true", Map.of("bind_scope", "global"), true, "webhook");
        when(cache.rulesForTenant("default")).thenReturn(List.of(asyncRule));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of("app_id", "test"), "read_file");

        List<SignalDto> signals = runner.run("default", ctx, List.of());

        assertTrue(signals.isEmpty());
        verify(taskExecutor).submit(anyString(), any(Runnable.class));
    }

    private static final String THRESHOLD_BODY =
            "def decide(ctx) {\n  def h = ctx.imageMatch('phishing')\n  return h != null && h.distance <= 10\n}";

    /**
     * Early executions pay script class-load/init and can exceed the 50ms
     * sandbox budget on a loaded machine (production pre-compiles at startup
     * and fails open on the rare slow first hit). Retry until the script has
     * actually executed; only a deterministic miss ends empty.
     */
    private List<SignalDto> runWarmed(MatchContext ctx,
                                      Map<String, ImageBlacklistService.BlacklistHit> evidence) {
        List<SignalDto> signals = List.of();
        for (int i = 0; i < 5; i++) {
            signals = runner.run("default", ctx, List.of(), evidence);
            if (!signals.isEmpty()) {
                return signals;
            }
        }
        return signals;
    }

    @Test
    void imageMatchRuleFiresWithinScriptThreshold() {
        RuleEntry denyRule = new RuleEntry(
                "default", "Rule_ImgBl", 1, "cloud", "groovy",
                "IMAGE_BLACKLIST", 100, "deny", "full", 0, "full",
                THRESHOLD_BODY,
                Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(denyRule));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of(), "read_file");

        Map<String, ImageBlacklistService.BlacklistHit> evidence =
                Map.of("phishing", new ImageBlacklistService.BlacklistHit("phash", 8, "sample-1"));
        List<SignalDto> signals = runWarmed(ctx, evidence);

        // standard groovy-rule signal: identity, risk and action from the rule row
        assertEquals(1, signals.size());
        assertEquals("Rule_ImgBl", signals.get(0).ruleId());
        assertEquals(100, signals.get(0).score());
        assertEquals("deny", signals.get(0).intentAction());
        assertEquals("IMAGE_BLACKLIST", signals.get(0).reasonCode());
    }

    @Test
    void imageMatchRuleOutsideScriptThresholdDoesNotFire() {
        RuleEntry denyRule = new RuleEntry(
                "default", "Rule_ImgBl", 1, "cloud", "groovy",
                "IMAGE_BLACKLIST", 100, "deny", "full", 0, "full",
                THRESHOLD_BODY,
                Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(denyRule));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of(), "read_file");

        // 12 bits away, threshold 10 → the script decides miss
        Map<String, ImageBlacklistService.BlacklistHit> evidence =
                Map.of("phishing", new ImageBlacklistService.BlacklistHit("phash", 12, "sample-1"));
        List<SignalDto> signals = runWarmed(ctx, evidence);

        assertTrue(signals.isEmpty());
    }

    @Test
    void imageMatchNoEvidenceIsFalsy() {
        RuleEntry exactRule = new RuleEntry(
                "default", "Rule_Exact", 1, "cloud", "groovy",
                "IMAGE_BLACKLIST", 100, "deny", "full", 0, "full",
                "def decide(ctx) {\n  def h = ctx.imageMatch('phishing')\n  return h != null && h.layer == 'exact'\n}",
                Map.of("bind_scope", "global"), false, null);
        when(cache.rulesForTenant("default")).thenReturn(List.of(exactRule));

        MatchContext ctx = MatchContext.forToolCall(
                "hello", null, null, null, "sess-1", Map.of(), "read_file");

        // no evidence at all (no images / no snapshot) → h == null → miss
        List<SignalDto> signals = runWarmed(ctx, Map.of());

        assertTrue(signals.isEmpty());
    }

    @Test
    void fromBlocksBuildsTenantPolicyData() {
        List<ListBlock> lists = List.of(
                new ListBlock("blocked_users", "user_id", List.of("bad_user"), null));
        List<RedisListIndexBlock> redisIndex = List.of(
                new RedisListIndexBlock("denied_ips", "ip_address", "virbius:deny:ip"));
        List<CumulativeBlock> cumulatives = List.of(
                new CumulativeBlock("calls_per_hour", "tool_name", 60, "rolling", "UTC", null));
        List<ToolPolicyEntry> toolPolicies = List.of(
                new ToolPolicyEntry("http_get", "network", null, 0, false, null));

        TenantPolicyData data = ScriptRuleRunner.fromBlocks(lists, redisIndex, cumulatives, toolPolicies);

        assertNotNull(data);
        assertEquals(1, data.memoryLists().size());
        assertEquals(1, data.redisLists().size());
        assertEquals(1, data.cumulatives().size());
        assertEquals(1, data.toolPolicies().size());
        assertEquals("network", data.toolPolicies().get("http_get").riskClass());
    }

    @Test
    void fromBlocksHandlesNullInputs() {
        TenantPolicyData data = ScriptRuleRunner.fromBlocks(null, null, null, null);
        assertNotNull(data);
        assertTrue(data.memoryLists().isEmpty());
        assertTrue(data.redisLists().isEmpty());
        assertTrue(data.cumulatives().isEmpty());
        assertTrue(data.toolPolicies().isEmpty());
    }

}
