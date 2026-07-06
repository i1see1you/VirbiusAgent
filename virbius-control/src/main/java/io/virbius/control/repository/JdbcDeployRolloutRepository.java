package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.DeployEvent;
import io.virbius.control.domain.DeployRollout;
import io.virbius.control.domain.enums.DeployRolloutState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDeployRolloutRepository implements DeployRolloutRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcDeployRolloutRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(DeployRollout r) {
        String ladderJson = encodeLadder(r.canaryLadder());
        jdbc.update(
                """
                INSERT INTO tb_deploy_rollout (
                    deploy_id, tenant_id, bundle_id, state, canary_percent, edge_deployed,
                    target_version, prev_version,
                    canary_engine_revision, stable_engine_revision,
                    canary_gateway_revision, stable_gateway_revision,
                    canary_edge_revision, stable_edge_revision,
                    canary_ladder, started_at, updated_at, finalized_at, operator, note
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                r.deployId(),
                r.tenantId(),
                r.bundleId(),
                r.state(),
                r.canaryPercent(),
                r.edgeDeployed() ? 1 : 0,
                r.targetVersion(),
                r.prevVersion(),
                r.canaryEngineRevision(),
                r.stableEngineRevision(),
                r.canaryGatewayRevision(),
                r.stableGatewayRevision(),
                r.canaryEdgeRevision(),
                r.stableEdgeRevision(),
                ladderJson,
                TimeHelper.nowIso(),
                TimeHelper.nowIso(),
                null,
                r.operator(),
                r.note());
    }

    @Override
    public Optional<DeployRollout> get(String deployId) {
        List<DeployRollout> rows = jdbc.query(
                "SELECT * FROM tb_deploy_rollout WHERE deploy_id = ?",
                ROLLOUT_MAPPER, deployId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<DeployRollout> findActive(String tenantId) {
        List<DeployRollout> rows = jdbc.query(
                """
                SELECT * FROM tb_deploy_rollout
                WHERE tenant_id = ? AND state NOT IN (?, ?)
                ORDER BY started_at DESC LIMIT 1
                """,
                ROLLOUT_MAPPER,
                tenantId,
                DeployRolloutState.ROLLED_BACK.value(),
                DeployRolloutState.FINALIZED.value());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<DeployRollout> listByTenant(String tenantId, int limit) {
        return jdbc.query(
                "SELECT * FROM tb_deploy_rollout WHERE tenant_id = ? ORDER BY started_at DESC LIMIT ?",
                ROLLOUT_MAPPER, tenantId, Math.max(1, limit));
    }

    @Override
    public void updateState(String deployId, String state, int canaryPercent, boolean edgeDeployed) {
        jdbc.update(
                """
                UPDATE tb_deploy_rollout
                SET state = ?, canary_percent = ?, edge_deployed = ?, updated_at = ?
                WHERE deploy_id = ?
                """,
                state, canaryPercent, edgeDeployed ? 1 : 0, TimeHelper.nowIso(), deployId);
    }

    @Override
    public void markFinalized(String deployId, String terminalState) {
        String now = TimeHelper.nowIso();
        jdbc.update(
                """
                UPDATE tb_deploy_rollout
                SET state = ?, updated_at = ?, finalized_at = ?
                WHERE deploy_id = ?
                """,
                terminalState, now, now, deployId);
    }

    @Override
    public void recordEvent(DeployEvent e) {
        jdbc.update(
                """
                INSERT INTO tb_deploy_event (
                    event_id, deploy_id, tenant_id, event_type, reason, rule_id,
                    from_state, from_percent, to_state, to_percent, layer,
                    operator, note, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                e.eventId(),
                e.deployId(),
                e.tenantId(),
                e.eventType(),
                e.reason(),
                e.ruleId(),
                e.fromState(),
                e.fromPercent(),
                e.toState(),
                e.toPercent(),
                e.layer(),
                e.operator(),
                e.note(),
                TimeHelper.nowIso());
    }

    @Override
    public List<DeployEvent> listEvents(String deployId) {
        return jdbc.query(
                "SELECT * FROM tb_deploy_event WHERE deploy_id = ? ORDER BY created_at ASC",
                (rs, rowNum) -> new DeployEvent(
                        rs.getString("event_id"),
                        rs.getString("deploy_id"),
                        rs.getString("tenant_id"),
                        rs.getString("event_type"),
                        rs.getString("reason"),
                        rs.getString("rule_id"),
                        rs.getString("from_state"),
                        nullableInt(rs, "from_percent"),
                        rs.getString("to_state"),
                        nullableInt(rs, "to_percent"),
                        rs.getString("layer"),
                        rs.getString("operator"),
                        rs.getString("note"),
                        TimeHelper.parseInstant(rs.getString("created_at"))),
                deployId);
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<DeployRollout> ROLLOUT_MAPPER = (rs, rowNum) -> {
        List<Integer> ladder;
        try {
            String raw = rs.getString("canary_ladder");
            ladder = raw == null || raw.isBlank()
                    ? List.of(5, 20, 50, 100)
                    : JSON.readValue(raw, new TypeReference<>() {});
        } catch (Exception ex) {
            ladder = List.of(5, 20, 50, 100);
        }
        return new DeployRollout(
                rs.getString("deploy_id"),
                rs.getString("tenant_id"),
                rs.getString("bundle_id"),
                rs.getString("state"),
                rs.getInt("canary_percent"),
                rs.getInt("edge_deployed") != 0,
                rs.getString("target_version"),
                rs.getString("prev_version"),
                nullableLong(rs, "canary_engine_revision"),
                nullableLong(rs, "stable_engine_revision"),
                nullableLong(rs, "canary_gateway_revision"),
                nullableLong(rs, "stable_gateway_revision"),
                nullableLong(rs, "canary_edge_revision"),
                nullableLong(rs, "stable_edge_revision"),
                ladder,
                TimeHelper.parseInstant(rs.getString("started_at")),
                TimeHelper.parseInstant(rs.getString("updated_at")),
                TimeHelper.parseInstant(rs.getString("finalized_at")),
                rs.getString("operator"),
                rs.getString("note"));
    };

    private static String encodeLadder(List<Integer> ladder) {
        if (ladder == null || ladder.isEmpty()) {
            return "[5,20,50,100]";
        }
        try {
            return JSON.writeValueAsString(ladder);
        } catch (Exception e) {
            return "[5,20,50,100]";
        }
    }
}
