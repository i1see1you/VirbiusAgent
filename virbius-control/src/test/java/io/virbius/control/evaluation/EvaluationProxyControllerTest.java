package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 评估代理：三态/许可/稳定错误码/配置热生效（固定配置，无 DB/Redis 依赖）。 */
class EvaluationProxyControllerTest {

    private HttpServer server;
    private final AtomicInteger healthCalls = new AtomicInteger();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void notConfiguredShowsEnabledButUnconfigured() {
        EvaluationProxyController c = controller("");
        EvaluationProxyController.Status s = c.status("t", null);
        assertTrue(s.enabled());
        assertFalse(s.configured());
        assertFalse(s.reachable());
        assertNull(s.license());
        assertTrue(s.promoEnabled());

        ResponseEntity<String> r = c.listReports("t", null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        assertTrue(r.getBody().contains("\"code\":5031"), r.getBody());
    }

    @Test
    void readyReportsLicenseAndPassesThroughFingerprint() throws Exception {
        String base = startStub(200);
        EvaluationProxyController c = controller(base);
        EvaluationProxyController.Status s = c.status("t", true);
        assertTrue(s.configured());
        assertTrue(s.reachable());
        assertEquals("trial", s.license().mode());
        assertEquals(30L, s.license().daysLeft());
        assertEquals(Boolean.TRUE, s.license().active());

        ResponseEntity<String> r = c.listReports("t", "http:http://127.0.0.1:1/x");
        assertEquals(200, r.getStatusCode().value());
        assertEquals("target_fingerprint=http:http://127.0.0.1:1/x", lastQuery.get());
    }

    @Test
    void unreachableGetsStableBadGatewayCode() throws Exception {
        int dead;
        try (ServerSocket socket = new ServerSocket(0)) {
            dead = socket.getLocalPort();
        }
        EvaluationProxyController c = controller("http://127.0.0.1:" + dead);
        EvaluationProxyController.Status s = c.status("t", true);
        assertTrue(s.configured());
        assertFalse(s.reachable());

        ResponseEntity<String> r = c.listReports("t", null);
        assertEquals(HttpStatus.BAD_GATEWAY, r.getStatusCode());
        assertTrue(r.getBody().contains("\"code\":5032"), r.getBody());
    }

    @Test
    void tokenMismatchIsVisibleInLicenseDetail() throws Exception {
        String base = startStub(401);
        EvaluationProxyController.Status s = controller(base).status("t", true);
        assertTrue(s.reachable(), "health 不需要 token，仍应 reachable");
        assertEquals("unknown", s.license().mode());
        assertTrue(s.license().detail().contains("401"), s.license().detail());
    }

    @Test
    void disabledIntegrationHidesEntryAndRejectsWrites() {
        EvaluationProxyController off = new EvaluationProxyController(
                new EvaluationSettingsService(new EvaluationSettingsService.Settings(
                        false, "http://127.0.0.1:1", "tok", true, "https://promo.example/x")));
        EvaluationProxyController.Status s = off.status("t", null);
        assertFalse(s.enabled());
        assertFalse(s.configured());
        ResponseEntity<String> r = off.listReports("t", null);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, r.getStatusCode());
        assertTrue(r.getBody().contains("\"code\":5033"), r.getBody());
    }

    @Test
    void promoSwitchIsPassedThrough() {
        EvaluationProxyController off = new EvaluationProxyController(
                new EvaluationSettingsService(new EvaluationSettingsService.Settings(
                        true, "", "tok", false, "https://promo.example/x")));
        assertFalse(off.status("t", null).promoEnabled());
    }

    @Test
    void statusIsCachedUntilRefresh() throws Exception {
        String base = startStub(200);
        EvaluationProxyController c = controller(base);
        c.status("t", null);
        c.status("t", null);
        assertEquals(1, healthCalls.get(), "TTL 内应命中缓存");
        c.status("t", true);
        assertEquals(2, healthCalls.get(), "refresh=true 应绕过缓存");
    }

    // ---------- helpers ----------

    private EvaluationProxyController controller(String baseUrl) {
        return new EvaluationProxyController(new EvaluationSettingsService(
                new EvaluationSettingsService.Settings(true, baseUrl, "tok", true,
                        "https://promo.example/x")));
    }

    private String startStub(int licenseStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/health", ex -> {
            healthCalls.incrementAndGet();
            respond(ex, 200, "{\"code\":0,\"data\":{\"status\":\"UP\"}}");
        });
        server.createContext("/api/v1/license", ex -> {
            if (licenseStatus != 200) {
                respond(ex, licenseStatus, "{\"code\":401,\"message\":\"unauthorized\"}");
                return;
            }
            respond(ex, 200, "{\"code\":0,\"data\":{\"mode\":\"trial\",\"edition\":\"trial\","
                    + "\"features\":[],\"expires_at\":\"2026-11-19T00:00:00Z\",\"days_left\":30,"
                    + "\"active\":true,\"clock_frozen\":false,\"instance_id\":\"inst-1\",\"detail\":\"\"}}");
        });
        server.createContext("/api/v1/evaluations/reports", ex -> {
            lastQuery.set(ex.getRequestURI().getQuery());
            respond(ex, 200, "{\"code\":0,\"data\":[]}");
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
