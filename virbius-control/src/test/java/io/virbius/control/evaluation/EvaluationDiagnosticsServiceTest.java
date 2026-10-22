package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 分步诊断：url/dns/tcp/health/auth/license，失败即停并给出可操作原因。 */
class EvaluationDiagnosticsServiceTest {

    private final EvaluationDiagnosticsService svc = new EvaluationDiagnosticsService();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void allStepsPassForTrialLicense() throws Exception {
        String base = startStub(200, 200);
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertTrue(r.ok(), () -> r.steps().toString());
        assertTrue(r.steps().size() == 6);
        assertTrue(r.steps().stream().allMatch(EvaluationDiagnosticsService.Step::ok));
    }

    @Test
    void tokenMismatchStopsAtAuth() throws Exception {
        String base = startStub(200, 401);
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "wrong"));
        assertFalse(r.ok());
        var auth = r.steps().stream().filter(s -> s.step().equals("auth")).findFirst().orElseThrow();
        assertFalse(auth.ok());
        assertTrue(auth.detail().contains("401"));
        assertFalse(r.steps().stream().anyMatch(s -> s.step().equals("license")),
                "auth 失败后不再继续");
    }

    @Test
    void missingTokenIsReportedBeforeRequest() throws Exception {
        String base = startStub(200, 200);
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, ""));
        assertFalse(r.ok());
        var auth = r.steps().stream().filter(s -> s.step().equals("auth")).findFirst().orElseThrow();
        assertTrue(auth.detail().contains("未配置 API token"));
    }

    @Test
    void refusedPortFailsAtTcp() throws Exception {
        int dead;
        try (ServerSocket s = new ServerSocket(0)) {
            dead = s.getLocalPort();
        }
        EvaluationDiagnosticsService.Result r = svc.run(
                cfg("http://127.0.0.1:" + dead, "tok"));
        assertFalse(r.ok());
        assertEqualsStep(r, "tcp");
    }

    @Test
    void invalidBaseUrlFailsAtUrl() {
        EvaluationDiagnosticsService.Result r = svc.run(cfg("ftp://x", "tok"));
        assertFalse(r.ok());
        assertEqualsStep(r, "url");
    }

    @Test
    void invalidLicenseModeFailsAtLicenseStep() throws Exception {
        String base = startStub(200, 200, "expired", false);
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertFalse(r.ok());
        var lic = r.steps().stream().filter(s -> s.step().equals("license")).findFirst().orElseThrow();
        assertFalse(lic.ok());
        assertTrue(lic.detail().contains("expired"));
    }

    // ---------- helpers ----------

    private static EvaluationSettingsService.Settings cfg(String base, String token) {
        return new EvaluationSettingsService.Settings(true, base, token, true, "");
    }

    private static void assertEqualsStep(EvaluationDiagnosticsService.Result r, String step) {
        assertTrue(r.steps().get(r.steps().size() - 1).step().equals(step), r.steps().toString());
    }

    private String startStub(int healthStatus, int licenseStatus) throws Exception {
        return startStub(healthStatus, licenseStatus, "trial", true);
    }

    private String startStub(int healthStatus, int licenseStatus, String mode, boolean active)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/health", ex -> respond(ex, healthStatus,
                "{\"code\":0,\"data\":{\"status\":\"UP\"}}"));
        server.createContext("/api/v1/license", ex -> {
            if (licenseStatus != 200) {
                respond(ex, licenseStatus, "{\"code\":401,\"message\":\"unauthorized\"}");
                return;
            }
            respond(ex, 200, "{\"code\":0,\"data\":{\"mode\":\"" + mode + "\",\"active\":"
                    + active + ",\"days_left\":30,\"detail\":\"\"}}");
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
