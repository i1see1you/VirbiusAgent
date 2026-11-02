package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.virbius.control.evaluation.EvaluationSuppressionsService.CreateRequest;
import io.virbius.control.evaluation.JdbcEvaluationSuppressionsRepository.Row;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 抑制注册表全链路（惰性建表 → 校验/接受 → 导出 yaml → 撤销/乐观锁 → 到期/租户隔离）。
 * 数据面本地自治：全程不触达 VE（代理注入另见 EvaluationSuppressionsInjectionTest）。
 */
class EvaluationSuppressionsServiceTest {

    private SingleConnectionDataSource ds;
    private EvaluationSuppressionsService service;
    private EvaluationSuppressionsMigrator migrator;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        migrator = new EvaluationSuppressionsMigrator(jdbc);
        service = new EvaluationSuppressionsService(
                new JdbcEvaluationSuppressionsRepository(jdbc, migrator));
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    // ---------- 惰性建表 ----------

    @Test
    void lazyMigratorIsIdempotentAndStampsVersion() {
        service.list("default", false); // 首次访问触发建表
        service.list("default", true);
        migrator.ensureTables();
        assertEquals(1, migrator.currentVersion(), "重复执行幂等，版本恒为 1");
    }

    @Test
    void freshDatabaseHasNoSuppressionTablesUntilFirstAccess() {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer n = jdbc.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 've_%'",
                rs -> rs.next() ? rs.getInt(1) : 0);
        assertEquals(0, n, "未访问前不得存在 ve_* 表（非评估客户零 schema 足迹）");
        service.list("default", false);
        Integer after = jdbc.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 've_%'",
                rs -> rs.next() ? rs.getInt(1) : 0);
        assertTrue(after >= 2, "首次访问后建表（rules + version）");
    }

    // ---------- reason 治理 ----------

    @Test
    void reasonGovernanceRejectsBlankShortAndTodo() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "check_paths", null, "secrets",
                        List.of("tests/**"), null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "check_paths", null, "secrets",
                        List.of("tests/**"), "太短")));
        IllegalArgumentException todo = assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "check_paths", null, "secrets",
                        List.of("tests/**"), "TODO: 填写抑制原因")));
        assertTrue(todo.getMessage().contains("TODO"), todo.getMessage());
    }

    // ---------- match_type 一致性 ----------

    @Test
    void matchTypeConsistencyEnforced() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "check_paths", null, "secrets",
                        null, "测试夹具合成 AKID，非真实凭据")),
                "check_paths 缺 paths 应拒");
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "risk_id", "  ", null,
                        null, "测试夹具合成 AKID，非真实凭据")),
                "risk_id 缺值应拒");
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "risk_id", "abc; drop", null,
                        null, "测试夹具合成 AKID，非真实凭据")),
                "非法字符应拒");
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req(null, "wildcard", null, "secrets",
                        null, "测试夹具合成 AKID，非真实凭据")),
                "未知 match_type 应拒");
    }

    // ---------- 接受 / 导出 / 撤销 ----------

    @Test
    void acceptThenExportThenWithdrawLifecycle() {
        Row r = service.create("default", req("accept-abc12345", "check_paths", null, "secrets",
                List.of("tests/**"), "测试夹具合成 AKID，非真实凭据"));
        assertEquals("active", r.status());
        assertEquals(1, r.version());
        assertEquals("default", r.tenantId());
        assertEquals("accept-abc12345", r.id());
        assertNotNull(r.expiresAt(), "缺省有效期应自动填充 90 天");
        assertTrue(r.expiresAt().compareTo(Instant.now().plus(89, ChronoUnit.DAYS).toString()) > 0);

        String yaml = service.activeYaml("default");
        assertNotNull(yaml);
        assertTrue(yaml.startsWith("version: 1\nsuppressions:\n"), yaml);
        assertTrue(yaml.contains("- id: \"accept-abc12345\""), yaml);
        assertTrue(yaml.contains("check_id: \"secrets\""), yaml);
        assertTrue(yaml.contains("paths:"), yaml);
        assertTrue(yaml.contains("- \"tests/**\""), yaml);

        Row withdrawn = service.withdraw("default", "accept-abc12345", 1);
        assertEquals("withdrawn", withdrawn.status());
        assertEquals(2, withdrawn.version());
        assertNull(service.activeYaml("default"), "撤销后无活跃规则");
    }

    @Test
    void withdrawUsesOptimisticLock() throws InterruptedException {
        Row created = service.create("default", req(null, "check_mute", null,
                "policy-audit-log", null, "管理员确认整项静音（已复核）"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.withdraw("default", created.id(), 999));
        assertTrue(e.getMessage().contains("并发修改"), e.getMessage());
    }

    @Test
    void riskIdAndMuteRuleYamlShapes() {
        service.create("default", req("accept-11111111", "risk_id", "1111111122223333", null,
                null, "官方示例密钥白名单未覆盖，业务确认"));
        service.create("default", req(null, "check_mute", null, "policy-risk-control", null,
                "管理员确认整项静音（已复核）"));
        String yaml = service.activeYaml("default");
        assertTrue(yaml.contains("risk_id: \"1111111122223333\""), yaml);
        assertTrue(yaml.contains("check_id: \"policy-risk-control\""), yaml);
        assertFalse(yaml.contains("paths:"), "risk_id/check_mute 形态不应输出 paths");
    }

    @Test
    void quotingSurvivesSpecialCharacters() {
        service.create("default", req("accept-22222222", "check_paths", null, "secrets",
                List.of("tests/**"), "含\"引号\"与\\反斜杠\n换行的理由"));
        String yaml = service.activeYaml("default");
        assertTrue(yaml.contains("reason: \"含\\\"引号\\\"与\\\\反斜杠\\n换行的理由\""), yaml);
    }

    // ---------- 租户隔离与到期 ----------

    @Test
    void tenantIsolationAndExpiryFiltering() throws InterruptedException {
        service.create("acme", req(null, "check_mute", null, "secrets", null,
                "acme 租户接受的静音规则"));
        assertTrue(service.activeYaml("default") == null, "default 租户不应看到 acme 的规则");
        assertNotNull(service.activeYaml("acme"));

        Row expiring = service.create("default", req("accept-expired1", "check_mute", null,
                "secrets", null, "立即过期的接受规则（测试）",
                Instant.now().plusSeconds(1).toString()));
        assertNotNull(service.activeYaml("default"), "未过期前仍导出");
        Thread.sleep(1300);
        assertNull(service.activeYaml("default"), "已过期规则不导出");
        assertEquals(1, service.list("default", true).size(), "含 inactive 视图仍可见（追溯）");
    }

    @Test
    void duplicateIdRejected() {
        service.create("default", req("accept-dup00001", "check_mute", null, "secrets", null,
                "第一次接受的合法理由"));
        assertThrows(IllegalArgumentException.class,
                () -> service.create("default", req("accept-dup00001", "check_mute", null,
                        "secrets", null, "第二次接受的同名理由")),
                "同租户同 id 二次接受应拒");
    }

    // ---------- helpers ----------

    private static CreateRequest req(String id, String matchType, String riskId, String checkId,
            List<String> paths, String reason) {
        return new CreateRequest(id, matchType, riskId, checkId, paths, reason, null, null);
    }

    private static CreateRequest req(String id, String matchType, String riskId, String checkId,
            List<String> paths, String reason, String expiresAt) {
        return new CreateRequest(id, matchType, riskId, checkId, paths, reason, null, expiresAt);
    }
}
