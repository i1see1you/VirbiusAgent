package io.virbius.engine.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.virbius.groovy.l3.ScriptEnvironment;
import io.virbius.policy.ValueSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PolicyDataCache {

    private static final Logger log = LoggerFactory.getLogger(PolicyDataCache.class);

    private final Map<String, TenantPolicyData> byTenant = new HashMap<>();

    public PolicyDataCache() {
    }

    public void replace(String tenantId, TenantPolicyData data) {
        if (tenantId == null || tenantId.isBlank()) {
            return;
        }
        if (data == null) {
            byTenant.remove(tenantId);
        } else {
            byTenant.put(tenantId, data);
        }
    }

    public TenantPolicyData get(String tenantId) {
        return byTenant.getOrDefault(tenantId, TenantPolicyData.empty());
    }

    public record TenantPolicyData(
            Map<String, ScriptEnvironment.ListDefinition> memoryLists,
            Map<String, ScriptEnvironment.RedisListDefinition> redisLists,
            Map<String, ScriptEnvironment.CumulativeDefinition> cumulatives,
            Map<String, ToolPolicyEntry> toolPolicies) {

        public static TenantPolicyData empty() {
            return new TenantPolicyData(Map.of(), Map.of(), Map.of(), Map.of());
        }
    }

    /** Tool policy entry cached from the Control's tool registry. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolPolicyEntry(
            @JsonProperty("tool_name") String toolName,
            @JsonProperty("risk_class") String riskClass,
            @JsonProperty("sandbox_type") String sandboxType,
            @JsonProperty("timeout_ms") long timeoutMs,
            @JsonProperty("fast_path") boolean fastPath,
            @JsonProperty("approval_mode") String approvalMode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListBlock(
            @JsonProperty("list_name") String listName,
            String dimension,
            List<String> entries,
            @JsonProperty("value_source") ValueSource valueSource) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RedisListIndexBlock(
            @JsonProperty("list_name") String listName,
            String dimension,
            @JsonProperty("redis_key") String redisKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CumulativeBlock(
            @JsonProperty("cumulative_name") String cumulativeName,
            String dimension,
            @JsonProperty("window_minutes") Integer windowMinutes,
            @JsonProperty("window_kind") String windowKind,
            String timezone,
            @JsonProperty("value_source") ValueSource valueSource) {}
}
