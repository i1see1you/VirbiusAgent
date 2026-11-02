package io.virbius.control.evaluation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 评估抑制注册表：特性门控的**惰性**建表（P1）。
 *
 * <p>不进 db/schema.sql、不进启动迁移——非评估客户升级/安装 control 时零 schema 足迹；
 * 首次访问 suppressions API 时才建表（{@code CREATE TABLE IF NOT EXISTS} 幂等）。
 *
 * <p>可移植四规则（SQLite 今日可用、MySQL 补一份方言串即可）：
 * <ol>
 *   <li>版本追踪用 {@code ve_schema_version} 表（不用 SQLite 专有 PRAGMA user_version）；</li>
 *   <li>时间戳一律 ISO-8601 TEXT（不用原生 TIMESTAMP）；</li>
 *   <li>paths 存 JSON 字符串 TEXT（不用原生 JSON 类型）；</li>
 *   <li>无自增列（id 为确定性/派生 TEXT 主键）。</li>
 * </ol>
 *
 * <p>索引创建兼容：MySQL 不支持 CREATE INDEX IF NOT EXISTS → 捕获"已存在"类错误视为成功
 * （多副本同时激活的建表竞态同理由 IF NOT EXISTS + 异常吞并兜底）。
 */
@Component
public class EvaluationSuppressionsMigrator {

    private static final Logger log = LoggerFactory.getLogger(EvaluationSuppressionsMigrator.class);

    /** 评估平面 schema 版本（加列/改约束时 +1 并在 applyV* 中补迁移）。 */
    static final int TARGET_VERSION = 1;

    private final JdbcTemplate jdbc;

    private volatile boolean ready = false;

    public EvaluationSuppressionsMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等确保表存在（进程内 ready 短路；跨副本靠 IF NOT EXISTS + 异常吞并）。 */
    public synchronized void ensureTables() {
        if (ready) {
            return;
        }
        jdbc.execute("CREATE TABLE IF NOT EXISTS ve_schema_version ("
                + "version INTEGER NOT NULL)");
        int current = currentVersion();
        if (current < 1) {
            jdbc.execute("CREATE TABLE IF NOT EXISTS ve_suppression_rules ("
                    + "id VARCHAR(64) NOT NULL,"
                    + "tenant_id VARCHAR(64) NOT NULL,"
                    + "match_type VARCHAR(16) NOT NULL,"
                    + "risk_id VARCHAR(64),"
                    + "check_id VARCHAR(64),"
                    + "paths TEXT,"
                    + "reason VARCHAR(512) NOT NULL,"
                    + "sev_at_accept VARCHAR(16),"
                    + "created_by VARCHAR(128) NOT NULL,"
                    + "created_at VARCHAR(32) NOT NULL,"
                    + "updated_at VARCHAR(32),"
                    + "expires_at VARCHAR(32),"
                    + "status VARCHAR(16) NOT NULL DEFAULT 'active',"
                    + "hit_count INTEGER NOT NULL DEFAULT 0,"
                    + "last_hit_at VARCHAR(32),"
                    + "version INTEGER NOT NULL DEFAULT 1,"
                    + "PRIMARY KEY (id))");
            createIndexIgnoreDuplicate("idx_ve_suppr_tenant",
                    "CREATE INDEX idx_ve_suppr_tenant ON ve_suppression_rules (tenant_id, status)");
            writeVersion(1);
            log.info("evaluation suppressions registry created (ve_schema_version=1)");
        }
        ready = true;
    }

    int currentVersion() {
        Integer v = jdbc.query("SELECT MAX(version) FROM ve_schema_version",
                rs -> rs.next() ? rs.getInt(1) : 0);
        return v == null ? 0 : v;
    }

    private void writeVersion(int version) {
        int updated = jdbc.update("UPDATE ve_schema_version SET version = ?", version);
        if (updated == 0) {
            jdbc.update("INSERT INTO ve_schema_version (version) VALUES (?)", version);
        }
    }

    private void createIndexIgnoreDuplicate(String name, String ddl) {
        try {
            jdbc.execute(ddl);
        } catch (DataAccessException e) {
            String msg = String.valueOf(e.getMostSpecificCause().getMessage()).toLowerCase();
            if (msg.contains("already exist") || msg.contains("duplicate key name")) {
                return; // 幂等：多副本竞态下另一副本已建
            }
            throw e;
        }
    }
}
