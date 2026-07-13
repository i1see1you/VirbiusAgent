package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.PolicyDataCache;
import io.virbius.engine.cache.RuleCache;
import io.virbius.engine.cache.RuleEntry;
import io.virbius.engine.config.TenantAwareTaskExecutor;
import io.virbius.groovy.l3.GroovyL3Executor;
import io.virbius.groovy.l3.L3RuleView;
import io.virbius.groovy.l3.L3SignalView;
import io.virbius.groovy.l3.PolicyContext;
import io.virbius.groovy.l3.ScriptEnvironment;
import io.virbius.policy.CounterStore;
import io.virbius.policy.CumulativeWindow;
import io.virbius.policy.GatewayListRedisMatcher;
import io.virbius.policy.IntentAction;
import io.virbius.policy.MatchContext;
import io.virbius.policy.ValueResolver;
import io.virbius.policy.ValueSource;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPool;

/** Cloud {@code groovy} and Agent {@code agent-groovy} script rules:
 *  {@code decide(ctx)} → boolean; signal from rule row on hit. */
@Component
public class ScriptRuleRunner {

    private static final Logger log = LoggerFactory.getLogger(ScriptRuleRunner.class);

    private final RuleCache cache;
    private final PolicyDataCache policyData;
    private final GroovyL3Executor executor;
    private final Optional<CounterStore> counterStore;
    private final Optional<GatewayListRedisMatcher> listRedisMatcher;
    private final TenantAwareTaskExecutor tenantTaskExecutor;
    private final AsyncActionHandler asyncActionHandler;
    private final SessionStatePreloader sessionStatePreloader;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScriptRuleRunner(
            RuleCache cache,
            PolicyDataCache policyData,
            Optional<JedisPool> jedisPool,
            Optional<GatewayListRedisMatcher> listRedisMatcher,
            TenantAwareTaskExecutor tenantTaskExecutor,
            AsyncActionHandler asyncActionHandler) {
        this.cache = cache;
        this.policyData = policyData;
        this.executor = new GroovyL3Executor();
        this.counterStore = jedisPool.map(CounterStore::new);
        this.listRedisMatcher = listRedisMatcher;
        this.tenantTaskExecutor = tenantTaskExecutor;
        this.asyncActionHandler = asyncActionHandler;
        this.sessionStatePreloader = jedisPool.map(pool -> new SessionStatePreloader(pool, mapper)).orElse(null);
    }

    @PostConstruct
    public void precompileGroovyRules() {
        int count = 0;
        for (RuleEntry rule : cache.rulesForTenant("default")) {
            if (!isExecutableScriptRule(rule)) {
                continue;
            }
            String body = bodyText(rule.body());
            if (!body.isBlank()) {
                executor.precompile(body);
                count++;
            }
        }
        if (count > 0) {
            log.info("pre-compiled {} groovy/agent-groovy rule(s)", count);
        }
    }

    public List<SignalDto> run(String tenantId, MatchContext matchCtx, List<SignalDto> priorSignals) {
        List<SignalDto> syncSignals = new ArrayList<>();
        PolicyDataCache.TenantPolicyData data = policyData.get(tenantId);
        ScriptEnvironment scriptEnv = buildScriptEnv(tenantId, matchCtx, data);

        // Pre-load session data once for all rules in this evaluation
        Map<String, Object> sessionData = preloadSession(matchCtx.sessionId());

        for (RuleEntry rule : cache.rulesForTenant(tenantId)) {
            if (!isExecutableScriptRule(rule)) {
                continue;
            }
            if (LegacyPolicyRules.isDeprecatedMetaRule(rule.ruleId())) {
                continue;
            }
            if (!RuleScopeSupport.matchesBind(rule, matchCtx)) {
                continue;
            }
            String rolloutState = rule.rolloutStateOrDefault();
            if (rule.isAsync()) {
                tenantTaskExecutor.submit(tenantId, () ->
                        evaluateAsync(rule, tenantId, matchCtx, priorSignals, scriptEnv, sessionData));
            } else if ("dry_run".equalsIgnoreCase(rolloutState)) {
                tenantTaskExecutor.submit(tenantId, () ->
                        evaluateDryRun(rule, tenantId, matchCtx, priorSignals, scriptEnv, sessionData));
            } else {
                try {
                    PolicyContext ctx = buildContext(tenantId, matchCtx, rule, priorSignals, syncSignals, scriptEnv, sessionData);
                    boolean hit = executor.executeDecide(bodyText(rule.body()), ctx);
                    if (hit) {
                        syncSignals.add(toSignal(rule));
                    }
                } catch (Exception e) {
                    log.warn("groovy script failed tenant={} rule={}: {}", tenantId, rule.ruleId(), e.getMessage());
                }
            }
        }
        return syncSignals;
    }

    /**
     * A1: Auto-ingest cumulative counters for this request.
     *
     * <p>Iterates over all configured cumulative definitions for the tenant,
     * resolves the aggregation value from {@link MatchContext}, and writes
     * a +1 delta to {@link CounterStore}. This is the cloud-side counterpart
     * of the gateway Lua ingest — configuration-driven, zero hardcoding.
     *
     * <p>Must be called <em>after</em> rule evaluation so that the current
     * request is not counted in its own evaluation window.
     */
    public void ingestCumulatives(String tenantId, MatchContext matchCtx) {
        if (counterStore.isEmpty()) {
            return;
        }
        CounterStore store = counterStore.get();
        PolicyDataCache.TenantPolicyData data = policyData.get(tenantId);
        Map<String, ScriptEnvironment.CumulativeDefinition> cumulatives = data.cumulatives();
        if (cumulatives == null || cumulatives.isEmpty()) {
            return;
        }
        for (var entry : cumulatives.entrySet()) {
            ScriptEnvironment.CumulativeDefinition def = entry.getValue();
            Optional<String> value = ValueResolver.resolve(
                    def.dimension(), def.valueSource(), matchCtx);
            if (value.isPresent()) {
                try {
                    ZoneId zone = ZoneId.of(def.timezone() != null ? def.timezone() : "UTC");
                    store.ingest(
                            tenantId,
                            def.cumulativeName(),
                            value.get(),
                            def.windowMinutes(),
                            def.windowKind(),
                            zone,
                            1L);
                } catch (Exception e) {
                    log.warn("cumulative ingest failed tenant={} cum={} : {}",
                            tenantId, def.cumulativeName(), e.getMessage());
                }
            }
        }
    }

    /**
     * A1: Record a tool call in the session state (history + per-tool count).
     *
     * <p>Delegates to {@link SessionStatePreloader#recordToolCall} which
     * writes to Redis session-scoped keys. The per-tool counts are stored
     * in a Redis Hash so that {@code preload()} can read them back in a
     * single {@code HGETALL}.
     */
    public void recordToolCall(String sessionId, String toolName, String args, boolean allowed) {
        if (sessionStatePreloader == null) {
            return;
        }
        sessionStatePreloader.recordToolCall(sessionId, toolName, args, allowed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> preloadSession(String sessionId) {
        if (sessionStatePreloader == null || sessionId == null || sessionId.isBlank()) {
            return Map.of("history", List.of(), "riskScore", 0, "toolCounts", Map.of());
        }
        return sessionStatePreloader.preload(sessionId);
    }

    private void evaluateAsync(RuleEntry rule, String tenantId, MatchContext matchCtx,
                                List<SignalDto> priorSignals, ScriptEnvironment scriptEnv,
                                Map<String, Object> sessionData) {
        try {
            PolicyContext ctx = buildContext(tenantId, matchCtx, rule, priorSignals, List.of(), scriptEnv, sessionData);
            boolean hit = executor.executeDecide(bodyText(rule.body()), ctx);
            if (hit) {
                SignalDto signal = toSignal(rule);
                asyncActionHandler.executeIfConfigured(rule, signal, matchCtx);
                log.info("async rule hit tenant={} rule={} revision={}", tenantId, rule.ruleId(), rule.ruleRevision());
            }
        } catch (Exception e) {
            log.warn("async rule eval failed tenant={} rule={}: {}", tenantId, rule.ruleId(), e.getMessage());
        }
    }

    private void evaluateDryRun(RuleEntry rule, String tenantId, MatchContext matchCtx,
                                 List<SignalDto> priorSignals, ScriptEnvironment scriptEnv,
                                 Map<String, Object> sessionData) {
        try {
            PolicyContext ctx = buildContext(tenantId, matchCtx, rule, priorSignals, List.of(), scriptEnv, sessionData);
            boolean hit = executor.executeDecide(bodyText(rule.body()), ctx);
            if (hit) {
                log.info("dry_run rule hit tenant={} rule={} revision={} riskScore={}",
                        tenantId, rule.ruleId(), rule.ruleRevision(), rule.riskScore());
            }
        } catch (Exception e) {
            log.warn("dry_run rule eval failed tenant={} rule={}: {}", tenantId, rule.ruleId(), e.getMessage());
        }
    }

    private ScriptEnvironment buildScriptEnv(String tenantId, MatchContext matchCtx,
                                              PolicyDataCache.TenantPolicyData data) {
        ScriptEnvironment.CumulativeReader reader = counterStore
                .map(store -> (ScriptEnvironment.CumulativeReader) (t, name, value, wMin, kind, zone) ->
                        store.read(t, name, value, wMin, kind, zone))
                .orElse(null);
        ScriptEnvironment.RedisListReader redisReader = listRedisMatcher
                .map(m -> (ScriptEnvironment.RedisListReader) m::matches)
                .orElse(null);
        return new ScriptEnvironment(
                tenantId,
                matchCtx,
                data.memoryLists(),
                data.redisLists(),
                data.cumulatives(),
                reader,
                redisReader);
    }

    @SuppressWarnings("unchecked")
    private PolicyContext buildContext(
            String tenantId,
            MatchContext matchCtx,
            RuleEntry current,
            List<SignalDto> priorSignals,
            List<SignalDto> newSignals,
            ScriptEnvironment scriptEnv,
            Map<String, Object> sessionData) {
        Map<String, L3RuleView> rules = new HashMap<>();
        for (RuleEntry e : cache.rulesForTenant(tenantId)) {
            if (!"groovy".equals(e.runtime()) && !"agent-groovy".equals(e.runtime())) {
                continue;
            }
            rules.put(
                    e.ruleId(),
                    new L3RuleView(
                            e.ruleId(),
                            e.ruleRevision(),
                            e.enforceMode() != null ? e.enforceMode() : "full",
                            e.canaryPercent(),
                            e.riskScore()));
        }
        List<L3SignalView> all = new ArrayList<>();
        if (priorSignals != null) {
            all.addAll(priorSignals.stream().map(this::toL3Signal).toList());
        }
        all.addAll(newSignals.stream().map(this::toL3Signal).toList());
        Map<String, String> vars = matchCtx.varsOrEmpty();

        List<Map<String, Object>> history = sessionData != null
                ? (List<Map<String, Object>>) sessionData.getOrDefault("history", List.of())
                : List.of();
        int riskScore = sessionData != null
                ? (int) sessionData.getOrDefault("riskScore", 0)
                : 0;
        Map<String, Long> toolCounts = sessionData != null
                ? (Map<String, Long>) sessionData.getOrDefault("toolCounts", Map.of())
                : Map.of();

        return new PolicyContext(
                tenantId,
                matchCtx.sessionId(),
                matchCtx.scene(),
                current.ruleId(),
                rules,
                all,
                vars,
                scriptEnv,
                history,
                riskScore,
                toolCounts);
    }

    private L3SignalView toL3Signal(SignalDto s) {
        return new L3SignalView(
                s.ruleId(),
                s.ruleRevision(),
                s.layer(),
                s.score(),
                suggest(s.intentAction(), (int) s.score()),
                s.reasonCode());
    }

    private static String suggest(String intent, int score) {
        if (intent != null && !intent.isBlank()) {
            if (IntentAction.DENY.equals(intent)) {
                return "block";
            }
            return intent;
        }
        return RiskScore.suggest(score);
    }

    private SignalDto toSignal(RuleEntry rule) {
        return new SignalDto(
                rule.ruleId(),
                rule.ruleRevision(),
                rule.layer(),
                rule.layer(),
                rule.riskScore(),
                rule.reasonCode(),
                rule.intentAction(),
                rule.enforceMode(),
                rule.canaryPercent() > 0 ? rule.canaryPercent() : null,
                rule.asyncActionConfig());
    }

    /**
     * Whether this rule should be executed by the script runner.
     *
     * <p>Supports both cloud-layer {@code groovy} rules and agent-layer
     * {@code agent-groovy} rules. Both use the same Groovy L3 executor with
     * {@code decide(ctx)} → boolean semantics; the difference is that
     * agent-groovy rules have access to session-aware ctx extensions
     * (sessionHistory, sessionRiskScore, toolCallCount).
     */
    private static boolean isExecutableScriptRule(RuleEntry rule) {
        if (rule == null || rule.runtime() == null || rule.layer() == null) {
            return false;
        }
        return ("groovy".equals(rule.runtime()) && "cloud".equals(rule.layer()))
                || ("agent-groovy".equals(rule.runtime()) && "agent".equals(rule.layer()));
    }

    private static String bodyText(Object body) {
        if (body == null) {
            return "";
        }
        if (body instanceof String s) {
            return s;
        }
        try {
            return new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            return body.toString();
        }
    }

    /** Build tenant policy data from reload payload blocks. */
    public static PolicyDataCache.TenantPolicyData fromBlocks(
            List<PolicyDataCache.ListBlock> lists,
            List<PolicyDataCache.RedisListIndexBlock> redisIndex,
            List<PolicyDataCache.CumulativeBlock> cumulatives) {
        Map<String, ScriptEnvironment.ListDefinition> listMap = new HashMap<>();
        if (lists != null) {
            for (PolicyDataCache.ListBlock b : lists) {
                if (b.listName() == null || b.listName().isBlank()) {
                    continue;
                }
                listMap.put(
                        b.listName(),
                        new ScriptEnvironment.ListDefinition(
                                b.listName(),
                                b.dimension() != null ? b.dimension() : "keyword",
                                b.entries() != null ? b.entries() : List.of(),
                                b.valueSource()));
            }
        }
        Map<String, ScriptEnvironment.RedisListDefinition> redisMap = new HashMap<>();
        if (redisIndex != null) {
            for (PolicyDataCache.RedisListIndexBlock b : redisIndex) {
                if (b.listName() == null || b.listName().isBlank()) {
                    continue;
                }
                redisMap.put(
                        b.listName(),
                        new ScriptEnvironment.RedisListDefinition(
                                b.listName(),
                                b.dimension() != null ? b.dimension() : "user_id",
                                b.redisKey() != null ? b.redisKey() : ""));
            }
        }
        Map<String, ScriptEnvironment.CumulativeDefinition> cumMap = new HashMap<>();
        if (cumulatives != null) {
            for (PolicyDataCache.CumulativeBlock b : cumulatives) {
                if (b.cumulativeName() == null || b.cumulativeName().isBlank()) {
                    continue;
                }
                int wMin = b.windowMinutes() != null && b.windowMinutes() > 0
                        ? b.windowMinutes()
                        : CumulativeWindow.windowMinutes(
                                b.windowKind() != null ? b.windowKind() : "rolling",
                                b.windowMinutes(),
                                null);
                cumMap.put(
                        b.cumulativeName(),
                        new ScriptEnvironment.CumulativeDefinition(
                                b.cumulativeName(),
                                b.dimension() != null ? b.dimension() : "user_id",
                                wMin,
                                b.windowKind() != null ? b.windowKind() : "rolling",
                                b.timezone(),
                                b.valueSource()));
            }
        }
        return new PolicyDataCache.TenantPolicyData(listMap, redisMap, cumMap);
    }
}
