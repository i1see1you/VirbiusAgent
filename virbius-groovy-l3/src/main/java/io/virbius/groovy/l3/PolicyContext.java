package io.virbius.groovy.l3;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Groovy L3 allowlist API (G2). Scripts may only access public methods of {@code ctx}.
 */
public final class PolicyContext {

    private static final int BLOCK_THRESHOLD = 100;

    private final String tenantId;
    private final String sessionId;
    private final String scene;
    private final String currentRuleId;
    private final Map<String, L3RuleView> rulesById;
    private final List<L3SignalView> signals;
    private final Map<String, String> vars;
    private final ScriptEnvironment scriptEnv;

    // === Agent Session API fields ===
    private final List<Map<String, Object>> sessionHistory;
    private final int currentRiskScore;
    private final Map<String, Long> toolCounts;

    public PolicyContext(
            String tenantId,
            String sessionId,
            String scene,
            String currentRuleId,
            Map<String, L3RuleView> rulesById,
            List<L3SignalView> signals) {
        this(tenantId, sessionId, scene, currentRuleId, rulesById, signals, Map.of(), null, List.of(), 0, Map.of());
    }

    public PolicyContext(
            String tenantId,
            String sessionId,
            String scene,
            String currentRuleId,
            Map<String, L3RuleView> rulesById,
            List<L3SignalView> signals,
            Map<String, String> vars) {
        this(tenantId, sessionId, scene, currentRuleId, rulesById, signals, vars, null, List.of(), 0, Map.of());
    }

    public PolicyContext(
            String tenantId,
            String sessionId,
            String scene,
            String currentRuleId,
            Map<String, L3RuleView> rulesById,
            List<L3SignalView> signals,
            Map<String, String> vars,
            ScriptEnvironment scriptEnv) {
        this(tenantId, sessionId, scene, currentRuleId, rulesById, signals, vars, scriptEnv, List.of(), 0, Map.of());
    }

    public PolicyContext(
            String tenantId,
            String sessionId,
            String scene,
            String currentRuleId,
            Map<String, L3RuleView> rulesById,
            List<L3SignalView> signals,
            Map<String, String> vars,
            ScriptEnvironment scriptEnv,
            List<Map<String, Object>> sessionHistory,
            int currentRiskScore,
            Map<String, Long> toolCounts) {
        this.tenantId = tenantId != null ? tenantId : "";
        this.sessionId = sessionId != null ? sessionId : "";
        this.scene = scene != null ? scene : "";
        this.currentRuleId = currentRuleId != null ? currentRuleId : "";
        this.rulesById = rulesById != null ? Map.copyOf(rulesById) : Map.of();
        this.signals = signals != null ? List.copyOf(signals) : List.of();
        this.vars = vars != null ? Map.copyOf(vars) : Map.of();
        this.scriptEnv = scriptEnv;
        this.sessionHistory = sessionHistory != null ? List.copyOf(sessionHistory) : List.of();
        this.currentRiskScore = currentRiskScore;
        this.toolCounts = toolCounts != null ? Map.copyOf(toolCounts) : Map.of();
    }

    public String tenantId() {
        return tenantId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String scene() {
        return scene;
    }

    public String currentRuleId() {
        return currentRuleId;
    }

    public List<L3SignalView> signals() {
        return signals;
    }

    /** Read-only RequestContext logical variable table. */
    public Map<String, String> vars() {
        return vars;
    }

    /** Read a logical variable, e.g. {@code app_id}, {@code debug_flag}. */
    public String var(String logicalName) {
        if (logicalName == null || logicalName.isBlank()) {
            return null;
        }
        return vars.get(logicalName.trim());
    }

    public String enforceMode(String ruleId) {
        L3RuleView r = rulesById.get(ruleId);
        return r != null && r.enforceMode() != null ? r.enforceMode() : "full";
    }

    public int riskScore(String ruleId) {
        L3RuleView r = rulesById.get(ruleId);
        return r != null ? r.riskScore() : BLOCK_THRESHOLD;
    }

    public int canaryPercent(String ruleId) {
        L3RuleView r = rulesById.get(ruleId);
        return r != null ? r.canaryPercent() : 100;
    }

    /** Any signal reaches the block threshold (risk_score &ge; 100 or suggest=block). */
    public boolean wouldHitBlock() {
        for (L3SignalView s : signals) {
            if (s.score() >= BLOCK_THRESHOLD || "block".equalsIgnoreCase(s.suggest())) {
                return true;
            }
        }
        return false;
    }

    public boolean inCanaryBucket(String sessionKey, int percent) {
        if (percent >= 100) {
            return true;
        }
        if (percent <= 0) {
            return false;
        }
        String key = sessionKey == null || sessionKey.isBlank() ? "default" : sessionKey;
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        long bucket = crc.getValue() % 100;
        return bucket < percent;
    }

    /** Script API: match named list (value resolved from list dimension). */
    public boolean listMatch(String listName) {
        return scriptEnv != null && scriptEnv.listMatch(listName);
    }

    /** Script API: match named list against explicit value. */
    public boolean listMatch(String listName, String value) {
        return scriptEnv != null && scriptEnv.listMatch(listName, value);
    }

    /** Script API: read cumulative counter for current request context (window count). */
    public long getCumulative(String cumulativeName) {
        if (scriptEnv == null) {
            return 0;
        }
        return scriptEnv.getCumulative(cumulativeName);
    }

    // ========== Agent Session API ==========

    /**
     * Get last N tool calls in this session.
     * Data is pre-loaded from Redis at evaluate time.
     */
    public List<Map<String, Object>> sessionHistory(int n) {
        if (sessionHistory == null || sessionHistory.isEmpty()) {
            return List.of();
        }
        return sessionHistory.stream().limit(n).toList();
    }

    /** Get current session risk score (0-100). Pre-loaded from Redis. */
    public int sessionRiskScore() {
        return currentRiskScore;
    }

    /** Get count of calls for a specific tool in this session. */
    public long toolCallCount(String toolName) {
        if (toolCounts == null) return 0;
        return toolCounts.getOrDefault(toolName, 0L);
    }

    /** Check if URL points to internal network. Uses CIDR/domain list from session context. */
    public boolean isInternalHost(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null) return false;
            if (host.endsWith(".internal") || host.endsWith(".local") || host.endsWith(".svc.cluster.local")) return true;
            if (host.startsWith("10.") || host.startsWith("172.") || host.startsWith("192.168.")) return true;
            if (host.equals("localhost") || host.equals("127.0.0.1")) return true;
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
