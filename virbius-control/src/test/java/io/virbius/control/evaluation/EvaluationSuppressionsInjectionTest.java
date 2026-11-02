package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virbius.control.evaluation.EvaluationSuppressionsService.CreateRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * 代理注入（P1）：/scans、/skills 转发体的 suppress 注入语义——
 * 请求级优先、无规则不动、坏 JSON 兜底、租户隔离。
 */
class EvaluationSuppressionsInjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private SingleConnectionDataSource ds;
    private EvaluationProxyController controller;
    private EvaluationSuppressionsService service;

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        ds = new SingleConnectionDataSource(conn, true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        service = new EvaluationSuppressionsService(
                new JdbcEvaluationSuppressionsRepository(jdbc,
                        new EvaluationSuppressionsMigrator(jdbc)));
        controller = new EvaluationProxyController(
                new EvaluationSettingsService(new EvaluationSettingsService.Settings(
                        true, "http://127.0.0.1:1", "tok", false, "https://promo.example/x")),
                service);
    }

    @AfterEach
    void tearDown() {
        ds.destroy();
    }

    @Test
    void noActiveRulesBodyUnchanged() {
        String body = "{\"target_path\":\"/tmp/x\",\"agent_name\":\"a\"}";
        assertEquals(body, controller.injectSuppress("default", body));
    }

    @Test
    void activeRuleInjectedAsSuppressField() throws Exception {
        service.create("default", new CreateRequest("accept-abc12345", "check_paths", null,
                "secrets", List.of("tests/**"), "测试夹具合成 AKID，非真实凭据", null, null));
        String out = controller.injectSuppress("default",
                "{\"target_path\":\"/tmp/x\",\"agent_name\":\"a\"}");
        JsonNode node = JSON.readTree(out);
        assertTrue(node.has("suppress"), out);
        String yaml = node.get("suppress").asText();
        assertTrue(yaml.startsWith("version: 1\n"), yaml);
        assertTrue(yaml.contains("accept-abc12345"), yaml);
        assertEquals("/tmp/x", node.get("target_path").asText(), "原字段必须保留");
    }

    @Test
    void explicitRequestSuppressWins() {
        service.create("default", new CreateRequest(null, "check_mute", null, "secrets", null,
                "注册表里的静音规则理由", null, null));
        String body = "{\"target_path\":\"/x\",\"suppress\":\"version: 1\\nsuppressions:\\n...\"}";
        assertEquals(body, controller.injectSuppress("default", body), "请求级 suppress 优先");
    }

    @Test
    void tenantScopedInjection() {
        service.create("acme", new CreateRequest(null, "check_mute", null, "secrets", null,
                "acme 租户的静音规则理由", null, null));
        String body = "{\"target_path\":\"/x\"}";
        assertEquals(body, controller.injectSuppress("default", body), "default 无规则不动");
        String out = controller.injectSuppress("ACME", body);
        assertTrue(out.contains("suppress"), "租户规范化（大小写不敏感）后 acme 规则注入");
    }

    @Test
    void malformedJsonFallsThroughUntouched() {
        service.create("default", new CreateRequest(null, "check_mute", null, "secrets", null,
                "注册表里的静音规则理由", null, null));
        String bad = "{\"target_path\": broken";
        String out = controller.injectSuppress("default", bad);
        assertEquals(bad, out, "坏 JSON 原样透传（由 VE 400 兜底）");
        assertEquals("", controller.injectSuppress("default", ""));
        assertNull(controller.injectSuppress("default", null));
    }

    @Test
    void nullServiceSkipsInjection() {
        EvaluationProxyController bare = new EvaluationProxyController(
                new EvaluationSettingsService(new EvaluationSettingsService.Settings(
                        true, "http://127.0.0.1:1", "tok", false, "https://promo.example/x")));
        String body = "{\"target_path\":\"/x\"}";
        assertEquals(body, bare.injectSuppress("default", body), "遗留构造（测试形态）跳过注入");
    }
}
