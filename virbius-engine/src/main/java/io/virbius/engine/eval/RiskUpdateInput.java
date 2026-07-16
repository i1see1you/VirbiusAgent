package io.virbius.engine.eval;

/**
 * Input for a session risk score update.
 *
 * <p>Passed by {@link EvaluateOrchestrator} after each tool call evaluation to
 * {@link SessionRiskManager#updateRiskScore(RiskUpdateInput)}.
 *
 * <p>The model distinguishes between:
 * <ul>
 *   <li><b>State-derived dimensions</b> ({@code base_risk}, {@code tool_weight}) — recomputed
 *       fresh each time from {@code risk_quota} and Redis {@code tool_counts}. No decay.</li>
 *   <li><b>Event-driven dimensions</b> ({@code chain_anomaly}, {@code prompt_injection},
 *       {@code falco_alert}) — accumulated with exponential time decay {@code exp(-elapsed/30)}.</li>
 * </ul>
 *
 * @param sessionId         the session ID (Redis key namespace)
 * @param tenantId          the tenant ID (for alerting / scoping)
 * @param riskQuota         the License risk quota for this Agent (determines {@code base_risk})
 * @param injectionHitCount number of prompt-injection hits in this request (0 or 1 typically)
 * @param injectionRiskDelta per-hit risk delta (default 15, may be higher for LLM-detected)
 * @param chainAnomalyDelta  risk delta from Groovy L3 chain-anomaly rules (0 if none)
 * @param falcoAlertDelta    Falco alerts since last update (usually 0; consumed async)
 */
public record RiskUpdateInput(
        String sessionId,
        String tenantId,
        int riskQuota,
        int injectionHitCount,
        int injectionRiskDelta,
        int chainAnomalyDelta,
        int falcoAlertDelta) {

    /** Convenience: no new events, just recompute from current state. */
    public static RiskUpdateInput recompute(String sessionId, String tenantId, int riskQuota) {
        return new RiskUpdateInput(sessionId, tenantId, riskQuota, 0, 0, 0, 0);
    }
}
