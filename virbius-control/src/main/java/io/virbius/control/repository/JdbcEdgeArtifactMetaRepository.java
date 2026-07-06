package io.virbius.control.repository;

import io.virbius.control.domain.EdgeArtifactMeta;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEdgeArtifactMetaRepository implements EdgeArtifactMetaRepository {

    private static final RowMapper<EdgeArtifactMeta> MAPPER =
            (rs, rowNum) -> new EdgeArtifactMeta(
                    rs.getString("tenant_id"),
                    rs.getString("app_id"),
                    rs.getString("pool"),
                    rs.getLong("artifact_revision"),
                    rs.getString("content_sha256"),
                    rs.getTimestamp("published_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcEdgeArtifactMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<EdgeArtifactMeta> get(String tenantId, String appId, String pool) {
        List<EdgeArtifactMeta> rows = jdbc.query(
                """
                SELECT tenant_id, app_id, pool, artifact_revision, content_sha256, published_at
                FROM tb_edge_artifact_meta
                WHERE tenant_id = ? AND app_id = ? AND pool = ?
                """,
                MAPPER,
                tenantId,
                appId,
                pool);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void save(EdgeArtifactMeta meta) {
        Optional<EdgeArtifactMeta> existing = get(meta.tenantId(), meta.appId(), meta.pool());
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO tb_edge_artifact_meta
                        (tenant_id, app_id, pool, artifact_revision, content_sha256, published_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    meta.tenantId(),
                    meta.appId(),
                    meta.pool(),
                    meta.artifactRevision(),
                    meta.contentSha256(),
                    Timestamp.from(meta.publishedAt()));
            return;
        }
        jdbc.update(
                """
                UPDATE tb_edge_artifact_meta
                SET artifact_revision = ?, content_sha256 = ?, published_at = ?
                WHERE tenant_id = ? AND app_id = ? AND pool = ?
                """,
                meta.artifactRevision(),
                meta.contentSha256(),
                Timestamp.from(meta.publishedAt()),
                meta.tenantId(),
                meta.appId(),
                meta.pool());
    }

    @Override
    public void delete(String tenantId, String appId, String pool) {
        jdbc.update(
                """
                DELETE FROM tb_edge_artifact_meta
                WHERE tenant_id = ? AND app_id = ? AND pool = ?
                """,
                tenantId, appId, pool);
    }
}
