package io.virbius.control.repository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.domain.ConstitutionRule;
import io.virbius.control.domain.ConstitutionTemplate;
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
public class JdbcConstitutionRepository implements ConstitutionRepository {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private static final RowMapper<ConstitutionRule> RULE_MAPPER = JdbcConstitutionRepository::mapRule;
    private static final RowMapper<ConstitutionTemplate> TEMPLATE_MAPPER = JdbcConstitutionRepository::mapTemplate;

    private final JdbcTemplate jdbc;

    public JdbcConstitutionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ===================== Rule CRUD =====================

    @Override
    public void saveRule(ConstitutionRule rule) {
        jdbc.update(
                """
                INSERT INTO tb_constitution
                    (tenant_id, rule_id, version, category, priority, rule_text, status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rule.tenantId(),
                rule.ruleId(),
                rule.version(),
                rule.category(),
                rule.priority(),
                rule.ruleText(),
                rule.status(),
                rule.createdBy());
    }

    @Override
    public Optional<ConstitutionRule> findRule(String tenantId, String ruleId, String version) {
        List<ConstitutionRule> rows = jdbc.query(
                """
                SELECT * FROM tb_constitution
                WHERE tenant_id = ? AND rule_id = ? AND version = ?
                """,
                RULE_MAPPER,
                tenantId, ruleId, version);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<ConstitutionRule> findLatestRule(String tenantId, String ruleId) {
        List<ConstitutionRule> rows = jdbc.query(
                """
                SELECT * FROM tb_constitution
                WHERE tenant_id = ? AND rule_id = ?
                ORDER BY created_at DESC LIMIT 1
                """,
                RULE_MAPPER,
                tenantId, ruleId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ConstitutionRule> listRules(String tenantId, String status) {
        String sql = "SELECT * FROM tb_constitution WHERE tenant_id = ?";
        if (status != null && !status.isBlank()) {
            sql += " AND status = ?";
            return jdbc.query(sql + " ORDER BY priority DESC, created_at DESC", RULE_MAPPER, tenantId, status);
        }
        return jdbc.query(sql + " ORDER BY priority DESC, created_at DESC", RULE_MAPPER, tenantId);
    }

    @Override
    public List<ConstitutionRule> listActiveRules(String tenantId) {
        return jdbc.query(
                """
                SELECT * FROM tb_constitution
                WHERE tenant_id = ? AND status = 'active'
                ORDER BY priority DESC, created_at ASC
                """,
                RULE_MAPPER,
                tenantId);
    }

    @Override
    public void updateRuleStatus(String tenantId, String ruleId, String version, String status) {
        jdbc.update(
                """
                UPDATE tb_constitution
                SET status = ?, updated_at = ?
                WHERE tenant_id = ? AND rule_id = ? AND version = ?
                """,
                status,
                Timestamp.from(Instant.now()),
                tenantId, ruleId, version);
    }

    @Override
    public void deleteRule(String tenantId, String ruleId, String version) {
        jdbc.update(
                "DELETE FROM tb_constitution WHERE tenant_id = ? AND rule_id = ? AND version = ?",
                tenantId, ruleId, version);
    }

    // ===================== Template CRUD =====================

    @Override
    public void saveTemplate(ConstitutionTemplate tmpl) {
        // Upsert: delete then insert
        jdbc.update(
                "DELETE FROM tb_constitution_templates WHERE tenant_id = ? AND constitution_version = ?",
                tmpl.tenantId(), tmpl.constitutionVersion());
        jdbc.update(
                """
                INSERT INTO tb_constitution_templates
                    (tenant_id, constitution_version, system_prefix, dynamic_suffix,
                     prohibitions, tool_rules, compiled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                tmpl.tenantId(),
                tmpl.constitutionVersion(),
                tmpl.systemPrefix(),
                tmpl.dynamicSuffix(),
                toJson(tmpl.prohibitions()),
                toJson(tmpl.toolRules()),
                Timestamp.from(tmpl.compiledAt()));
    }

    @Override
    public Optional<ConstitutionTemplate> findTemplate(String tenantId, String constitutionVersion) {
        List<ConstitutionTemplate> rows = jdbc.query(
                """
                SELECT * FROM tb_constitution_templates
                WHERE tenant_id = ? AND constitution_version = ?
                """,
                TEMPLATE_MAPPER,
                tenantId, constitutionVersion);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<ConstitutionTemplate> listTemplates(String tenantId) {
        return jdbc.query(
                "SELECT * FROM tb_constitution_templates WHERE tenant_id = ? ORDER BY constitution_version DESC",
                TEMPLATE_MAPPER,
                tenantId);
    }

    @Override
    public List<ConstitutionTemplate> listTemplatesByVersion(String tenantId, String constitutionVersion) {
        return jdbc.query(
                """
                SELECT * FROM tb_constitution_templates
                WHERE tenant_id = ? AND constitution_version = ?
                """,
                TEMPLATE_MAPPER,
                tenantId, constitutionVersion);
    }

    @Override
    public void deleteTemplatesByVersion(String tenantId, String constitutionVersion) {
        jdbc.update(
                "DELETE FROM tb_constitution_templates WHERE tenant_id = ? AND constitution_version = ?",
                tenantId, constitutionVersion);
    }

    // ===================== Row Mappers =====================

    private static ConstitutionRule mapRule(ResultSet rs, int rowNum) throws SQLException {
        return new ConstitutionRule(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getString("rule_id"),
                rs.getString("version"),
                rs.getString("category"),
                rs.getInt("priority"),
                rs.getString("rule_text"),
                rs.getString("status"),
                rs.getString("created_by"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static ConstitutionTemplate mapTemplate(ResultSet rs, int rowNum) throws SQLException {
        return new ConstitutionTemplate(
                rs.getLong("id"),
                rs.getString("tenant_id"),
                rs.getString("constitution_version"),
                rs.getString("system_prefix"),
                rs.getString("dynamic_suffix"),
                parseStringList(rs.getString("prohibitions")),
                parseStringList(rs.getString("tool_rules")),
                toInstant(rs.getTimestamp("compiled_at")));
    }

    // ===================== Helpers =====================

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    private static String toJson(List<String> list) {
        try {
            return JSON.writeValueAsString(list != null ? list : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }
}
