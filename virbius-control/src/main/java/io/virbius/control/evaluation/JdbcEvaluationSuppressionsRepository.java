package io.virbius.control.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 评估抑制注册表仓储：租户隔离、软撤销、乐观锁（version）。
 *
 * <p>存储约定见 {@link EvaluationSuppressionsMigrator}（ISO-8601 TEXT 时间、paths JSON TEXT、
 * 无自增）。写路径仅 control（VE 零 DB）；导出/注入时序列化为 .ve-suppress.yaml schema。
 */
@Repository
public class JdbcEvaluationSuppressionsRepository {

    /** match_type 三档：risk_id 精确 | check_id+paths 抗漂移 | check 静音（危险，UI 侧标红）。 */
    public static final String MATCH_RISK_ID = "risk_id";
    public static final String MATCH_CHECK_PATHS = "check_paths";
    public static final String MATCH_CHECK_MUTE = "check_mute";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SELECT_COLS =
            "id, tenant_id, match_type, risk_id, check_id, paths, reason,"
                    + " sev_at_accept, created_by, created_at, updated_at, expires_at,"
                    + " status, hit_count, last_hit_at, version";

    private final JdbcTemplate jdbc;
    private final EvaluationSuppressionsMigrator migrator;

    public JdbcEvaluationSuppressionsRepository(JdbcTemplate jdbc,
            EvaluationSuppressionsMigrator migrator) {
        this.jdbc = jdbc;
        this.migrator = migrator;
    }

    /** suppressions 注册行（paths 为 glob 列表）。 */
    public record Row(String id, String tenantId, String matchType, String riskId, String checkId,
                      List<String> paths, String reason, String sevAtAccept, String createdBy,
                      String createdAt, String updatedAt, String expiresAt, String status,
                      long hitCount, String lastHitAt, long version) {
    }

    /** 新建入参（id 可空=自动派生；其余字段由 service 层校验/填充）。 */
    public record NewRule(String id, String tenantId, String matchType, String riskId,
                          String checkId, List<String> paths, String reason, String sevAtAccept,
                          String createdBy, String expiresAt) {
    }

    // ---------- 读 ----------

    /** 列表（租户隔离；includeInactive=false 时仅 active 且未过期）。 */
    public List<Row> list(String tenantId, boolean includeInactive) {
        migrator.ensureTables();
        StringBuilder sql = new StringBuilder(
                "SELECT " + SELECT_COLS + " FROM ve_suppression_rules WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (!includeInactive) {
            sql.append(" AND status = ?");
            args.add(STATUS_ACTIVE);
        }
        sql.append(" ORDER BY created_at DESC, id");
        List<Row> rows = jdbc.query(sql.toString(), this::map, args.toArray());
        if (includeInactive) {
            return rows;
        }
        String now = Instant.now().toString();
        return rows.stream().filter(r -> !expired(r, now)).toList();
    }

    /** 活跃（active 且未过期）规则的 suppress yaml 全文；无活跃规则返回 null。 */
    public String activeYaml(String tenantId) {
        List<Row> rows = list(tenantId, false);
        return rows.isEmpty() ? null : toYaml(rows);
    }

    public Optional<Row> find(String tenantId, String id) {
        migrator.ensureTables();
        return jdbc.query("SELECT " + SELECT_COLS
                        + " FROM ve_suppression_rules WHERE tenant_id = ? AND id = ?",
                this::map, tenantId, id).stream().findFirst();
    }

    // ---------- 写 ----------

    /** 新建（默认 active，version=1）。 */
    public Row insert(NewRule rule) {
        migrator.ensureTables();
        String id = rule.id() != null && !rule.id().isBlank()
                ? rule.id().trim()
                : "accept-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String now = Instant.now().toString();
        String pathsJson = rule.paths() == null || rule.paths().isEmpty()
                ? null : toJson(rule.paths());
        jdbc.update("INSERT INTO ve_suppression_rules"
                        + " (id, tenant_id, match_type, risk_id, check_id, paths, reason,"
                        + "  sev_at_accept, created_by, created_at, expires_at, status, version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
                id, rule.tenantId(), rule.matchType(), rule.riskId(), rule.checkId(), pathsJson,
                rule.reason(), rule.sevAtAccept(), rule.createdBy(), now, rule.expiresAt(),
                STATUS_ACTIVE);
        return find(rule.tenantId(), id).orElseThrow();
    }

    /** 软撤销（乐观锁：version 不符抛 IllegalStateException；不存在抛 NoSuchElementException）。 */
    public Row withdraw(String tenantId, String id, long expectedVersion) {
        migrator.ensureTables();
        int updated = jdbc.update(
                "UPDATE ve_suppression_rules SET status = ?, updated_at = ?,"
                        + " version = version + 1"
                        + " WHERE tenant_id = ? AND id = ? AND version = ?",
                STATUS_WITHDRAWN, Instant.now().toString(), tenantId, id, expectedVersion);
        if (updated == 0) {
            if (find(tenantId, id).isEmpty()) {
                throw new NoSuchElementException("suppression not found: " + id);
            }
            throw new IllegalStateException("suppression 已被并发修改，请刷新后重试: " + id);
        }
        return find(tenantId, id).orElseThrow();
    }

    // ---------- 内部 ----------

    private static boolean expired(Row r, String now) {
        if (r.expiresAt() == null) {
            return false;
        }
        try {
            return !Instant.parse(r.expiresAt()).isAfter(Instant.now());
        } catch (DateTimeParseException e) {
            return r.expiresAt().compareTo(now) <= 0;
        }
    }

    private Row map(ResultSet rs, int rowNum) throws SQLException {
        String pathsJson = rs.getString("paths");
        List<String> paths = null;
        if (pathsJson != null && !pathsJson.isBlank()) {
            try {
                paths = JSON.readValue(pathsJson,
                        JSON.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                paths = List.of();
            }
        }
        return new Row(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("match_type"), rs.getString("risk_id"), rs.getString("check_id"),
                paths, rs.getString("reason"), rs.getString("sev_at_accept"),
                rs.getString("created_by"), rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("expires_at"), rs.getString("status"), rs.getLong("hit_count"),
                rs.getString("last_hit_at"), rs.getLong("version"));
    }

    private static String toJson(List<String> paths) {
        try {
            return JSON.writeValueAsString(paths);
        } catch (Exception e) {
            throw new IllegalArgumentException("paths 序列化失败", e);
        }
    }

    /**
     * 序列化为 VE SuppressionSet 兼容的 .ve-suppress.yaml（version:1，严格 schema）。
     * 标量用 JSON 双引号转义（YAML 双引号字面量兼容 JSON 转义集）。
     */
    static String toYaml(List<Row> rows) {
        ObjectMapper mapper = new ObjectMapper();
        StringBuilder sb = new StringBuilder("version: 1\nsuppressions:\n");
        for (Row r : rows) {
            sb.append("- id: ").append(quote(mapper, r.id())).append('\n');
            sb.append("  reason: ").append(quote(mapper, r.reason())).append('\n');
            sb.append("  match:\n");
            switch (r.matchType()) {
                case MATCH_RISK_ID -> sb.append("    risk_id: ")
                        .append(quote(mapper, r.riskId())).append('\n');
                case MATCH_CHECK_PATHS -> {
                    sb.append("    check_id: ").append(quote(mapper, r.checkId())).append('\n');
                    sb.append("    paths:\n");
                    for (String p : r.paths()) {
                        sb.append("    - ").append(quote(mapper, p)).append('\n');
                    }
                }
                case MATCH_CHECK_MUTE -> sb.append("    check_id: ")
                        .append(quote(mapper, r.checkId())).append('\n');
                default -> throw new IllegalStateException("unknown match_type: " + r.matchType());
            }
        }
        return sb.toString();
    }

    private static String quote(ObjectMapper mapper, String s) {
        try {
            return mapper.writeValueAsString(s == null ? "" : s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    /** 解析 ISO-8601 到期时间（非法值在入口即拒）。 */
    static Instant parseInstant(String s, String field) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    field + " 须为 ISO-8601（如 2026-11-30T00:00:00Z）: " + s);
        }
    }
}
