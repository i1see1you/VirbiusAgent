package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import io.virbius.control.common.exception.BusinessException;
import io.virbius.control.domain.BundleStaging;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBundleStagingRepository implements BundleStagingRepository {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcBundleStagingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BundleStaging> get(String tenantId, String bundleId, String layer) {
        List<BundleStaging> rows = jdbc.query(
                """
                SELECT tenant_id, bundle_id, layer, base_version, status, rule_diffs, version, updated_at
                FROM tb_bundle_staging WHERE tenant_id = ? AND bundle_id = ? AND layer = ?
                """,
                (rs, rowNum) -> {
                    Map<String, String> diffs;
                    try {
                        diffs = JSON.readValue(rs.getString("rule_diffs"), new TypeReference<>() {});
                    } catch (Exception e) {
                        diffs = Map.of();
                    }
                    return new BundleStaging(
                            rs.getString("tenant_id"),
                            rs.getString("bundle_id"),
                            rs.getString("layer"),
                            rs.getString("base_version"),
                            rs.getString("status"),
                            diffs,
                            rs.getInt("version"),
                            TimeHelper.parseInstant(rs.getString("updated_at")));
                },
                tenantId, bundleId, layer);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public BundleStaging getOrCreate(String tenantId, String bundleId, String layer, String baseVersion) {
        Optional<BundleStaging> existing = get(tenantId, bundleId, layer);
        if (existing.isPresent()) {
            return existing.get();
        }
        String now = TimeHelper.nowIso();
        try {
            jdbc.update(
                    """
                    INSERT INTO tb_bundle_staging (tenant_id, bundle_id, layer, base_version, status, rule_diffs, version, updated_at)
                    VALUES (?, ?, ?, ?, 'editing', '{}', 1, ?)
                    """,
                    tenantId, bundleId, layer, baseVersion, now);
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            return get(tenantId, bundleId, layer).orElseThrow();
        }
        return get(tenantId, bundleId, layer).orElseThrow();
    }

    @Override
    public BundleStaging applyRuleDiff(String tenantId, String bundleId, String layer, String ruleId, String diffType) {
        BundleStaging staging = get(tenantId, bundleId, layer).orElseThrow();
        if ("deploying".equals(staging.status())) {
            throw new BusinessException(423, "staging is locked by a deployment in progress");
        }
        Map<String, String> diffs = new LinkedHashMap<>(staging.ruleDiffs());
        diffs.put(ruleId, diffType);
        String diffsJson = JsonHelper.toJson(diffs);
        int updated = jdbc.update(
                """
                UPDATE tb_bundle_staging SET rule_diffs = ?, version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND bundle_id = ? AND layer = ? AND version = ?
                """,
                diffsJson, TimeHelper.nowIso(), tenantId, bundleId, layer, staging.version());
        if (updated == 0) {
            throw new BusinessException(409, "staging version conflict, please retry");
        }
        return get(tenantId, bundleId, layer).orElseThrow();
    }

    @Override
    public BundleStaging removeRuleDiff(String tenantId, String bundleId, String layer, String ruleId) {
        BundleStaging staging = get(tenantId, bundleId, layer).orElseThrow();
        Map<String, String> diffs = new LinkedHashMap<>(staging.ruleDiffs());
        diffs.remove(ruleId);
        String diffsJson = JsonHelper.toJson(diffs);
        jdbc.update(
                """
                UPDATE tb_bundle_staging SET rule_diffs = ?, version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND bundle_id = ? AND layer = ?
                """,
                diffsJson, TimeHelper.nowIso(), tenantId, bundleId, layer);
        return get(tenantId, bundleId, layer).orElseThrow();
    }

    @Override
    public void updateStatus(String tenantId, String bundleId, String layer, String status) {
        jdbc.update(
                "UPDATE tb_bundle_staging SET status = ?, updated_at = ? WHERE tenant_id = ? AND bundle_id = ? AND layer = ?",
                status, TimeHelper.nowIso(), tenantId, bundleId, layer);
    }

    @Override
    public void clear(String tenantId, String bundleId, String newBaseVersion) {
        jdbc.update(
                """
                UPDATE tb_bundle_staging SET rule_diffs = '{}', base_version = ?, status = 'editing', version = version + 1, updated_at = ?
                WHERE tenant_id = ? AND bundle_id = ?
                """,
                newBaseVersion, TimeHelper.nowIso(), tenantId, bundleId);
    }
}
