package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.BundleRelease;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcBundleReleaseRepository implements BundleReleaseRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcBundleReleaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public BundleRelease create(BundleRelease release) {
        String snapshotJson = JsonHelper.toJson(release.frozenSnapshot());
        jdbc.update(
                """
                INSERT INTO tb_bundle_releases (tenant_id, bundle_id, version, status, frozen_snapshot, created_at, deployed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                release.tenantId(),
                release.bundleId(),
                release.version(),
                release.status(),
                snapshotJson,
                TimeHelper.nowIso(),
                null);
        return get(release.tenantId(), release.bundleId(), release.version()).orElseThrow();
    }

    @Override
    public Optional<BundleRelease> get(String tenantId, String bundleId, String version) {
        List<BundleRelease> rows = jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, frozen_snapshot, created_at, deployed_at
                FROM tb_bundle_releases WHERE tenant_id = ? AND bundle_id = ? AND version = ?
                """,
                (rs, rowNum) -> {
                    List<Map<String, Object>> snapshot;
                    try {
                        snapshot = JSON.readValue(rs.getString("frozen_snapshot"), new TypeReference<>() {});
                    } catch (Exception e) {
                        snapshot = List.of();
                    }
                    return new BundleRelease(
                            rs.getString("tenant_id"),
                            rs.getString("bundle_id"),
                            rs.getString("version"),
                            rs.getString("status"),
                            snapshot,
                            TimeHelper.parseInstant(rs.getString("created_at")),
                            TimeHelper.parseInstant(rs.getString("deployed_at")));
                },
                tenantId, bundleId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<BundleRelease> list(String tenantId, String bundleId) {
        return jdbc.query(
                """
                SELECT tenant_id, bundle_id, version, status, frozen_snapshot, created_at, deployed_at
                FROM tb_bundle_releases WHERE tenant_id = ? AND bundle_id = ?
                ORDER BY created_at DESC
                """,
                (rs, rowNum) -> {
                    List<Map<String, Object>> snapshot;
                    try {
                        snapshot = JSON.readValue(rs.getString("frozen_snapshot"), new TypeReference<>() {});
                    } catch (Exception e) {
                        snapshot = List.of();
                    }
                    return new BundleRelease(
                            rs.getString("tenant_id"),
                            rs.getString("bundle_id"),
                            rs.getString("version"),
                            rs.getString("status"),
                            snapshot,
                            TimeHelper.parseInstant(rs.getString("created_at")),
                            TimeHelper.parseInstant(rs.getString("deployed_at")));
                },
                tenantId, bundleId);
    }

    @Override
    public void updateStatus(String tenantId, String bundleId, String version, String status) {
        jdbc.update(
                "UPDATE tb_bundle_releases SET status = ? WHERE tenant_id = ? AND bundle_id = ? AND version = ?",
                status, tenantId, bundleId, version);
    }

    @Override
    public String getActiveVersion(String tenantId, String bundleId) {
        List<String> rows = jdbc.queryForList(
                "SELECT release_version FROM tb_bundle_active WHERE tenant_id = ? AND bundle_id = ?",
                String.class, tenantId, bundleId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void setActiveVersion(String tenantId, String bundleId, String version) {
        int updated = jdbc.update(
                "UPDATE tb_bundle_active SET release_version = ?, updated_at = ? WHERE tenant_id = ? AND bundle_id = ?",
                version, TimeHelper.nowIso(), tenantId, bundleId);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO tb_bundle_active (tenant_id, bundle_id, release_version, updated_at) VALUES (?, ?, ?, ?)",
                    tenantId, bundleId, version, TimeHelper.nowIso());
        }
    }
}
