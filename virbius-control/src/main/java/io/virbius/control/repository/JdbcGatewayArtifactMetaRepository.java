package io.virbius.control.repository;

import io.virbius.control.domain.GatewayArtifactMeta;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGatewayArtifactMetaRepository implements GatewayArtifactMetaRepository {

    private static final RowMapper<GatewayArtifactMeta> MAPPER = (rs, rowNum) -> new GatewayArtifactMeta(
            rs.getString("tenant_id"),
            rs.getLong("artifact_revision"),
            rs.getString("access_lists_sha256"),
            rs.getString("scene_registry_sha256"),
            rs.getTimestamp("published_at").toInstant(),
            rs.getString("publish_id"),
            rs.getString("trigger"),
            rs.getString("storage"));

    private final JdbcTemplate jdbc;

    public JdbcGatewayArtifactMetaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<GatewayArtifactMeta> get(String tenantId) {
        List<GatewayArtifactMeta> rows = jdbc.query(
                """
                SELECT tenant_id, artifact_revision, access_lists_sha256, scene_registry_sha256,
                       published_at, publish_id, trigger, storage
                FROM tb_gateway_artifact_meta
                WHERE tenant_id = ?
                """,
                MAPPER,
                tenantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public void save(GatewayArtifactMeta meta) {
        if (get(meta.tenantId()).isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO tb_gateway_artifact_meta
                        (tenant_id, artifact_revision, access_lists_sha256, scene_registry_sha256,
                         published_at, publish_id, trigger, storage)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    meta.tenantId(),
                    meta.artifactRevision(),
                    meta.accessListsSha256(),
                    meta.sceneRegistrySha256(),
                    Timestamp.from(meta.publishedAt()),
                    meta.publishId(),
                    meta.trigger(),
                    meta.storage());
            return;
        }
        jdbc.update(
                """
                UPDATE tb_gateway_artifact_meta
                SET artifact_revision = ?, access_lists_sha256 = ?, scene_registry_sha256 = ?,
                    published_at = ?, publish_id = ?, trigger = ?, storage = ?
                WHERE tenant_id = ?
                """,
                meta.artifactRevision(),
                meta.accessListsSha256(),
                meta.sceneRegistrySha256(),
                Timestamp.from(meta.publishedAt()),
                meta.publishId(),
                meta.trigger(),
                meta.storage(),
                meta.tenantId());
    }
}
