package io.virbius.control.evaluation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEvaluationSettingsRepository implements EvaluationSettingsRepository {

    private final JdbcTemplate jdbc;

    public JdbcEvaluationSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<Row> find() {
        List<Row> rows = jdbc.query(
                "SELECT enabled, base_url, api_token_ct, promo_enabled, promo_url,"
                        + " probe_tenants, updated_by FROM tb_evaluation_setting WHERE id = 1",
                (rs, n) -> map(rs));
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    @Override
    public void upsert(Row row) {
        // 可移植 upsert（SQLite + MySQL）：先 UPDATE，缺行再 INSERT（与现有仓储一致）
        int updated = jdbc.update(
                "UPDATE tb_evaluation_setting SET enabled = ?, base_url = ?, api_token_ct = ?,"
                        + " promo_enabled = ?, promo_url = ?, probe_tenants = ?,"
                        + " updated_at = CURRENT_TIMESTAMP, updated_by = ? WHERE id = 1",
                row.enabled(), row.baseUrl(), row.apiTokenCt(), row.promoEnabled(),
                row.promoUrl(), row.probeTenants(), row.updatedBy());
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO tb_evaluation_setting"
                            + " (id, enabled, base_url, api_token_ct, promo_enabled, promo_url,"
                            + "  probe_tenants, updated_at, updated_by)"
                            + " VALUES (1, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?)",
                    row.enabled(), row.baseUrl(), row.apiTokenCt(), row.promoEnabled(),
                    row.promoUrl(), row.probeTenants(), row.updatedBy());
        }
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getBoolean("enabled"), rs.getString("base_url"),
                rs.getString("api_token_ct"), rs.getBoolean("promo_enabled"),
                rs.getString("promo_url"), rs.getString("probe_tenants"),
                rs.getString("updated_by"));
    }
}
