package io.virbius.control.repository;

import io.virbius.control.domain.ChallengeApprovalRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcChallengeApprovalRepository implements ChallengeApprovalRepository {

    private final JdbcTemplate jdbc;

    public JdbcChallengeApprovalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ChallengeApprovalRecord> MAPPER = (rs, rowNum) -> map(rs);

    private static ChallengeApprovalRecord map(ResultSet rs) throws SQLException {
        return new ChallengeApprovalRecord(
                rs.getString("challenge_id"),
                rs.getString("tenant_id"),
                rs.getString("status"),
                rs.getString("tool_name"),
                rs.getString("args_hash"),
                rs.getString("session_id"),
                rs.getString("rule_id"),
                rs.getString("reason_code"),
                rs.getInt("risk_score"),
                rs.getString("approval_mode"),
                longVal(rs.getObject("created_at")),
                longVal(rs.getObject("expires_at")),
                rs.getString("approved_by"),
                longVal(rs.getObject("approved_at")),
                rs.getString("rejected_by"),
                longVal(rs.getObject("rejected_at")),
                rs.getString("comment"));
    }

    private static Long longVal(Object v) {
        if (v instanceof Number n) return n.longValue();
        return null;
    }

    @Override
    public void save(ChallengeApprovalRecord record) {
        int updated = jdbc.update(
                """
                UPDATE tb_challenge_approvals SET
                  status=?, tool_name=?, args_hash=?, session_id=?, rule_id=?,
                  reason_code=?, risk_score=?, approval_mode=?, created_at=?, expires_at=?,
                  approved_by=?, approved_at=?, rejected_by=?, rejected_at=?, comment=?
                WHERE challenge_id=?
                """,
                record.status(),
                record.toolName(),
                record.argsHash(),
                record.sessionId(),
                record.ruleId(),
                record.reasonCode(),
                record.riskScore(),
                record.approvalMode(),
                record.createdAt(),
                record.expiresAt(),
                record.approvedBy(),
                record.approvedAt(),
                record.rejectedBy(),
                record.rejectedAt(),
                record.comment(),
                record.challengeId());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO tb_challenge_approvals (
                      challenge_id, tenant_id, status, tool_name, args_hash, session_id,
                      rule_id, reason_code, risk_score, approval_mode, created_at, expires_at,
                      approved_by, approved_at, rejected_by, rejected_at, comment
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.challengeId(),
                    record.tenantId(),
                    record.status(),
                    record.toolName(),
                    record.argsHash(),
                    record.sessionId(),
                    record.ruleId(),
                    record.reasonCode(),
                    record.riskScore(),
                    record.approvalMode(),
                    record.createdAt(),
                    record.expiresAt(),
                    record.approvedBy(),
                    record.approvedAt(),
                    record.rejectedBy(),
                    record.rejectedAt(),
                    record.comment());
        }
    }

    @Override
    public List<ChallengeApprovalRecord> listByTenantAndStatus(String tenantId, String status, int max) {
        return jdbc.query(
                """
                SELECT * FROM tb_challenge_approvals
                WHERE tenant_id = ? AND status = ?
                ORDER BY COALESCE(approved_at, rejected_at, created_at) DESC
                LIMIT ?
                """,
                MAPPER,
                tenantId,
                status,
                max);
    }
}
