package io.virbius.engine.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.engine.cache.PolicyDataCache;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

/**
 * Session Risk Manager: multi-dimensional weighted scoring with time decay.
 *
 * <p>Replaces the simple {@code INCRBY} mechanism in {@link SessionStatePreloader} with:
 * <ul>
 *   <li><b>State-derived dimensions</b> ({@code base_risk}, {@code tool_weight}) — recomputed
 *       fresh each time. No decay.</li>
 *   <li><b>Event-driven dimensions</b> ({@code chain_anomaly}, {@code prompt_injection},
 *       {@code falco_alert}) — accumulated with {@code exp(-elapsed/30)} time decay.</li>
 * </ul>
 *
 * <h3>Scoring formula</h3>
 * <pre>
 * session_risk = base_risk                                    // state-derived, no decay
 *              + tool_weight                                  // state-derived, no decay
 *              + decay(chain_anomaly, elapsed)                // event-driven, exp(-t/30)
 *              + decay(prompt_injection, elapsed)             // event-driven, exp(-t/30)
 *              + decay(falco_alert, elapsed)                  // event-driven, exp(-t/30)
 * </pre>
 *
 * <h3>Tool weight</h3>
 * <pre>
 * tool_weight = Σ( tool_risk_class(tool) × round(log(call_count(tool) + 1)) )
 * </pre>
 *
 * <h3>Time decay</h3>
 * <pre>
 * decay(stored_value, elapsed_minutes) = stored_value × exp(-elapsed_minutes / 30)
 * </pre>
 *
 * <p>Called by {@link EvaluateOrchestrator#evaluate(EvaluateRequestDto)} after each tool call
 * evaluation. Uses lazy computation — decay is applied only when {@code updateRiskScore()} is
 * called, not via a background timer.
 */
@Component
public class SessionRiskManager {

    private static final Logger log = LoggerFactory.getLogger(SessionRiskManager.class);

    // ── Redis key templates ──
    private static final String KEY_RISK_SCORE    = "session:%s:risk_score";
    private static final String KEY_BREAKDOWN     = "session:%s:risk_breakdown";
    private static final String KEY_LAST_UPDATE   = "session:%s:risk_last_update";
    private static final String KEY_TOOL_COUNTS   = "session:%s:tool_counts";
    private static final String KEY_FALCO_PENDING = "session:%s:falco_pending";

    // ── Threshold action keys ──
    private static final String KEY_FORCE_DISCONNECT = "session:%s:force_disconnect";
    private static final String KEY_EXIT_FAST_PATH   = "session:%s:exit_fast_path";
    private static final String KEY_AUDIT_SAMPLE_RATE = "session:%s:audit_sample_rate";

    // ── Constants ──
    private static final int TTL_SECONDS = 3600;
    private static final int THRESHOLD_FLAG_TTL_SECONDS = 300;

    private static final Map<String, Integer> RISK_CLASS_MAP = Map.of(
            "low", 1,
            "medium", 3,
            "high", 5,
            "network", 4
    );
    
    // ── Dependencies ──
    private final Optional<JedisPool> jedisPool;
    private final ObjectMapper mapper;
    private final PolicyDataCache policyDataCache;

    // ── Configuration ──
    private final boolean enabled;
    private final double baseRiskRatio;
    private final int injectionWeight;
    private final int falcoWeight;
    private final double decayHalfLifeMinutes;
    private final int decayCutoffMinutes;
    private final int thresholdDisconnect;
    private final int thresholdFullAudit;
    private final int thresholdSampleAudit;
    private final int sessionTtlSeconds;

    // ── Lazy tool risk-class cache (legacy, replaced by PolicyDataCache) ──

    public SessionRiskManager(
            Optional<JedisPool> jedisPool,
            PolicyDataCache policyDataCache,
            @Value("${virbius.session-risk.enabled:true}") boolean enabled,
            @Value("${virbius.session-risk.base-risk-ratio:0.1}") double baseRiskRatio,
            @Value("${virbius.session-risk.injection-weight:15}") int injectionWeight,
            @Value("${virbius.session-risk.falco-weight:10}") int falcoWeight,
            @Value("${virbius.session-risk.decay-half-life-minutes:30}") double decayHalfLifeMinutes,
            @Value("${virbius.session-risk.decay-cutoff-minutes:120}") int decayCutoffMinutes,
            @Value("${virbius.session-risk.threshold.disconnect:80}") int thresholdDisconnect,
            @Value("${virbius.session-risk.threshold.full-audit:60}") int thresholdFullAudit,
            @Value("${virbius.session-risk.threshold.sample-audit:30}") int thresholdSampleAudit,
            @Value("${virbius.session-risk.session-ttl-seconds:3600}") int sessionTtlSeconds) {
        this.jedisPool = jedisPool;
        this.policyDataCache = policyDataCache;
        this.mapper = new ObjectMapper();
        this.enabled = enabled;
        this.baseRiskRatio = baseRiskRatio;
        this.injectionWeight = injectionWeight;
        this.falcoWeight = falcoWeight;
        this.decayHalfLifeMinutes = decayHalfLifeMinutes;
        this.decayCutoffMinutes = decayCutoffMinutes;
        this.thresholdDisconnect = thresholdDisconnect;
        this.thresholdFullAudit = thresholdFullAudit;
        this.thresholdSampleAudit = thresholdSampleAudit;
        this.sessionTtlSeconds = sessionTtlSeconds;
    }

    /**
     * Main entry: compute and update session risk score.
     *
     * <p>Called by {@link EvaluateOrchestrator#evaluate(EvaluateRequestDto)} after rule
     * evaluation and {@code recordToolCall}.
     *
     * @param input the risk update input
     * @return the updated total risk score (0 if disabled or Redis unavailable)
     */
    public int updateRiskScore(RiskUpdateInput input) {
        if (!enabled) {
            return 0;
        }
        String sessionId = input.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        if (jedisPool.isEmpty()) {
            log.debug("Redis not configured, skipping risk score update for session={}", sessionId);
            return 0;
        }

        try (Jedis jedis = jedisPool.get().getResource()) {
            // ── 1. Pipeline read all state ──
            String breakdownKey   = KEY_BREAKDOWN.formatted(sessionId);
            String lastUpdateKey  = KEY_LAST_UPDATE.formatted(sessionId);
            String toolCountsKey  = KEY_TOOL_COUNTS.formatted(sessionId);
            String falcoPendingKey = KEY_FALCO_PENDING.formatted(sessionId);

            Pipeline pipe = jedis.pipelined();
            var breakdownFuture   = pipe.hgetAll(breakdownKey);
            var lastUpdateFuture  = pipe.get(lastUpdateKey);
            var toolCountsFuture  = pipe.hgetAll(toolCountsKey);
            var falcoPendingFuture = pipe.get(falcoPendingKey);
            pipe.sync();

            Map<String, String> breakdownRaw  = breakdownFuture.get();
            String lastUpdateStr              = lastUpdateFuture.get();
            Map<String, String> toolCountsRaw = toolCountsFuture.get();
            String falcoPendingStr            = falcoPendingFuture.get();

            // ── 2. Parse stored breakdown ──
            int storedChain     = parseInt(breakdownRaw.get("chain_anomaly"), 0);
            int storedInjection = parseInt(breakdownRaw.get("prompt_injection"), 0);
            int storedFalco     = parseInt(breakdownRaw.get("falco_alert"), 0);

            // ── 3. Compute time decay ──
            long elapsedMin = computeElapsedMinutes(lastUpdateStr);
            double decayFactor = Math.exp(-elapsedMin / decayHalfLifeMinutes);

            // ── 4. Decay event-driven dimensions ──
            int decayedChain     = applyDecay(storedChain, elapsedMin);
            int decayedInjection = applyDecay(storedInjection, elapsedMin);
            int decayedFalco     = applyDecay(storedFalco, elapsedMin);

            // ── 5. Add new events ──
            int falcoPending = parseInt(falcoPendingStr, 0);
            int injectionDelta = input.injectionHitCount() > 0
                    ? input.injectionHitCount() * Math.max(input.injectionRiskDelta(), injectionWeight)
                    : 0;
            int newChain     = decayedChain + input.chainAnomalyDelta();
            int newInjection = decayedInjection + injectionDelta;
            int newFalco     = decayedFalco + (falcoPending * falcoWeight);

            // ── 6. Compute state-derived dimensions ──
            int baseRisk = (int) Math.round(input.riskQuota() * baseRiskRatio);
            Map<String, Long> toolCounts = parseToolCounts(toolCountsRaw);
            int toolWeight = computeToolWeight(toolCounts);

            // ── 7. Compute total ──
            int total = baseRisk + toolWeight + newChain + newInjection + newFalco;

            // ── 8. Write back ──
            String riskKey = KEY_RISK_SCORE.formatted(sessionId);
            String now = Instant.now().toString();

            Pipeline writePipe = jedis.pipelined();
            writePipe.set(riskKey, String.valueOf(total));
            Map<String, String> breakdownMap = new HashMap<>();
            breakdownMap.put("base_risk",        String.valueOf(baseRisk));
            breakdownMap.put("tool_weight",      String.valueOf(toolWeight));
            breakdownMap.put("chain_anomaly",    String.valueOf(newChain));
            breakdownMap.put("prompt_injection", String.valueOf(newInjection));
            breakdownMap.put("falco_alert",      String.valueOf(newFalco));
            writePipe.hset(breakdownKey, breakdownMap);
            writePipe.set(lastUpdateKey, now);
            writePipe.expire(riskKey, sessionTtlSeconds);
            writePipe.expire(breakdownKey, sessionTtlSeconds);
            writePipe.expire(lastUpdateKey, sessionTtlSeconds);
            // Clear consumed falco pending
            if (falcoPending > 0) {
                writePipe.del(falcoPendingKey);
            }
            writePipe.sync();

            // ── 9. Threshold actions ──
            triggerThresholdActions(sessionId, total, jedis);

            if (log.isDebugEnabled()) {
                log.debug(
                        "risk updated: session={} total={} base={} tool={} chain={} inj={} falco={} " +
                        "decay={} elapsed={}min",
                        sessionId, total, baseRisk, toolWeight, newChain, newInjection, newFalco,
                        String.format("%.3f", decayFactor), elapsedMin);
            }

            return total;

        } catch (Exception e) {
            log.error("Failed to update risk score for session={}: {}", sessionId, e.getMessage());
            return 0; // fail-open: don't block on risk computation failure
        }
    }

    /**
     * Compute tool_weight from tool call counts.
     *
     * <p>State-derived: recomputed fresh each time, no decay.
     *
     * <p>Formula: {@code Σ(tool_risk_class(tool) × round(log(call_count(tool) + 1)))}
     *
     * @param toolCounts map of tool name → call count
     * @return the computed tool weight
     */
    int computeToolWeight(Map<String, Long> toolCounts) {
        if (toolCounts == null || toolCounts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (var entry : toolCounts.entrySet()) {
            String toolName = entry.getKey();
            long count = entry.getValue();
            if (toolName == null || toolName.isBlank() || count <= 0) {
                continue;
            }
            int riskClass = lookupRiskClass(toolName);
            // log(call_count + 1), rounded to integer
            int logWeight = (int) Math.round(Math.log(count + 1));
            total += riskClass * logWeight;
        }
        return total;
    }

    /**
     * Apply exponential time decay to an event-driven dimension.
     *
     * <p>Formula: {@code stored_value × exp(-elapsed_minutes / half_life)}
     *
     * @param storedValue    the value stored in Redis (from last update)
     * @param elapsedMinutes minutes since last update
     * @return the decayed value, rounded to integer
     */
    int applyDecay(int storedValue, long elapsedMinutes) {
        if (storedValue == 0 || elapsedMinutes <= 0) {
            return storedValue;
        }
        if (elapsedMinutes >= decayCutoffMinutes) {
            return 0; // cutoff: effectively zero after 2 hours
        }
        double factor = Math.exp(-elapsedMinutes / decayHalfLifeMinutes);
        return (int) Math.round(storedValue * factor);
    }

    /**
     * Falco alert callback: increment pending counter.
     *
     * <p>Called asynchronously when a Falco alert is associated with a session.
     * The pending count is consumed by the next {@code updateRiskScore()} call.
     *
     * @param sessionId the session ID
     */
    public void onFalcoAlert(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (jedisPool.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.get().getResource()) {
            String key = KEY_FALCO_PENDING.formatted(sessionId);
            Pipeline pipe = jedis.pipelined();
            pipe.incr(key);
            pipe.expire(key, TTL_SECONDS);
            pipe.sync();
        } catch (Exception e) {
            log.warn("Failed to record Falco alert for session={}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Get the current risk score for a session (fast read, no recompute).
     *
     * @param sessionId the session ID
     * @return the current risk score, or 0 if not set
     */
    public int getRiskScore(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || jedisPool.isEmpty()) {
            return 0;
        }
        try (Jedis jedis = jedisPool.get().getResource()) {
            String val = jedis.get(KEY_RISK_SCORE.formatted(sessionId));
            return parseInt(val, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // ──────────────────────────────────────────────
    //  Threshold Actions
    // ──────────────────────────────────────────────

    private void triggerThresholdActions(String sessionId, int risk, Jedis jedis) {
        // > disconnect threshold: force disconnect flag + alert
        if (risk > thresholdDisconnect) {
            jedis.setex(KEY_FORCE_DISCONNECT.formatted(sessionId), THRESHOLD_FLAG_TTL_SECONDS, "true");
            log.warn("session risk critical: session={} risk={} threshold={}",
                    sessionId, risk, thresholdDisconnect);
        }
        // > full audit threshold: exit fast path + full audit
        else if (risk > thresholdFullAudit) {
            jedis.setex(KEY_AUDIT_SAMPLE_RATE.formatted(sessionId), THRESHOLD_FLAG_TTL_SECONDS, "1.0");
            jedis.setex(KEY_EXIT_FAST_PATH.formatted(sessionId), THRESHOLD_FLAG_TTL_SECONDS, "true");
        }
        // > sample audit threshold: increase audit sampling
        else if (risk > thresholdSampleAudit) {
            jedis.setex(KEY_AUDIT_SAMPLE_RATE.formatted(sessionId), THRESHOLD_FLAG_TTL_SECONDS, "0.5");
        }
    }

    // ──────────────────────────────────────────────
    //  Tool Risk Class Lookup (from RuleCache)
    // ──────────────────────────────────────────────

    /**
     * Look up the risk class for a tool name.
     *
     * <p>Scans {@link RuleCache} for rules with {@code bind_scope=tool} whose
     * {@code bind_ref.tool_names} contains the tool name, and extracts
     * {@code bind_ref.risk_class}.
     *
     * <p>Results are cached and invalidated when the RuleCache generation changes.
     *
     * @param toolName the tool name
     * @return the risk class string ("low", "medium", "high", "network"), default "low"
     */
    private String lookupRiskClassString(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "low";
        }
        PolicyDataCache.TenantPolicyData data = policyDataCache.get("default");
        PolicyDataCache.ToolPolicyEntry entry = data.toolPolicies().get(toolName);
        if (entry != null && entry.riskClass() != null && !entry.riskClass().isBlank()) {
            return entry.riskClass().trim().toLowerCase();
        }
        return "low";
    }

    /**
     * Look up the numeric risk class for a tool.
     *
     * @param toolName the tool name
     * @return risk class value (1/3/5/4), default 1 (low)
     */
    int lookupRiskClass(String toolName) {
        return RISK_CLASS_MAP.getOrDefault(lookupRiskClassString(toolName), 1);
    }

    // ──────────────────────────────────────────────
    //  Utility methods
    // ──────────────────────────────────────────────

    private long computeElapsedMinutes(String lastUpdateIso) {
        if (lastUpdateIso == null || lastUpdateIso.isBlank()) {
            return 0;
        }
        try {
            Instant last = Instant.parse(lastUpdateIso);
            return Duration.between(last, Instant.now()).toMinutes();
        } catch (Exception e) {
            return 0;
        }
    }

    private int parseInt(String s, int defaultVal) {
        if (s == null || s.isBlank()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private Map<String, Long> parseToolCounts(Map<String, String> raw) {
        Map<String, Long> counts = new HashMap<>();
        if (raw != null) {
            for (var entry : raw.entrySet()) {
                try {
                    counts.put(entry.getKey(), Long.parseLong(entry.getValue()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return counts;
    }
}
