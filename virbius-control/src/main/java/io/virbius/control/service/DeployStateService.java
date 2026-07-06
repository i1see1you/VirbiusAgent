package io.virbius.control.service;

import io.virbius.control.repository.RegistryRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DeployStateService {

    private final JdbcTemplate jdbc;
    private final RegistryRepository store;

    public DeployStateService(JdbcTemplate jdbc, RegistryRepository store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    public void record(String tenantId, String layer) {
        String nowStr = Instant.now().toString();
        jdbc.update(
                "INSERT OR REPLACE INTO tb_deploy_state (tenant_id, layer, deployed_at) VALUES (?, ?, ?)",
                tenantId, layer, nowStr);
    }

    public Map<String, Object> status(String tenantId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String layer : new String[]{"gateway", "cloud", "edge"}) {
            Map<String, Object> st = new LinkedHashMap<>();
            String deployed = jdbc.query(
                    "SELECT deployed_at FROM tb_deploy_state WHERE tenant_id = ? AND layer = ?",
                    (rs, i) -> rs.getString("deployed_at"),
                    tenantId, layer).stream().findFirst().orElse(null);
            st.put("deployed_at", deployed);
            boolean hasUnpub = hasUnpublished(tenantId, layer, deployed);
            st.put("has_unpublished", hasUnpub);
            st.put("pending_rules", hasUnpub ? pendingRules(tenantId, layer, deployed) : java.util.List.of());
            out.put(layer, st);
        }
        return out;
    }

    private java.util.List<java.util.Map<String, Object>> pendingRules(String tenantId, String layer, String deployedAtStr) {
        if (deployedAtStr == null) {
            return jdbc.query(
                    "SELECT rule_id, rollout_state FROM tb_rules_current WHERE tenant_id = ? AND layer = ? AND rollout_state IN ('dry_run','canary','full') ORDER BY rule_id",
                    (rs, i) -> {
                        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("rule_id", rs.getString("rule_id"));
                        row.put("rollout_state", rs.getString("rollout_state"));
                        return row;
                    },
                    tenantId, layer);
        }
        return jdbc.query(
                "SELECT rule_id, rollout_state FROM tb_rules_current WHERE tenant_id = ? AND layer = ? AND updated_at > ? ORDER BY rule_id",
                (rs, i) -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("rule_id", rs.getString("rule_id"));
                    row.put("rollout_state", rs.getString("rollout_state"));
                    return row;
                },
                tenantId, layer, deployedAtStr);
    }

    private boolean hasUnpublished(String tenantId, String layer, String deployedAtStr) {
        if (deployedAtStr == null) {
            return hasAnyRuleInExecutionPlane(tenantId, layer);
        }
        String latestRuleUpdate = jdbc.query(
                "SELECT MAX(updated_at) FROM tb_rules_current WHERE tenant_id = ? AND layer = ?",
                (rs, i) -> rs.getString(1),
                tenantId, layer).stream().findFirst().orElse(null);
        if (latestRuleUpdate != null && latestRuleUpdate.compareTo(deployedAtStr) > 0) {
            return true;
        }
        if ("gateway".equals(layer) || "edge".equals(layer)) {
            String bundleUpdated = jdbc.query(
                    "SELECT MAX(updated_at) FROM tb_bundles WHERE tenant_id = ?",
                    (rs, i) -> rs.getString(1),
                    tenantId).stream().findFirst().orElse(null);
            if (bundleUpdated != null && bundleUpdated.compareTo(deployedAtStr) > 0) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyRuleInExecutionPlane(String tenantId, String layer) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tb_rules_current WHERE tenant_id = ? AND layer = ? AND rollout_state IN ('dry_run','canary','full')",
                Integer.class, tenantId, layer);
        return count != null && count > 0;
    }
}
