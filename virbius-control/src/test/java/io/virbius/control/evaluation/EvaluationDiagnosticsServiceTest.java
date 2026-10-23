package io.virbius.control.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

/** 分步诊断：url/dns/tcp/health/auth/license + 能力位（probe_remote/scan_root/egress/judge）。 */
class EvaluationDiagnosticsServiceTest {

    private static final String CAPS_OK = "{\"code\":0,\"data\":{"
            + "\"probe\":{\"enabled\":true,\"allow_remote\":true},"
            + "\"scan_root\":{\"configured\":true,\"usable\":true,\"roots\":1},"
            + "\"mcp_egress\":{\"allow_hosts_configured\":false,\"allow_hosts\":0,"
            + "\"deny_loopback\":false},"
            + "\"judge_llm\":{\"configured\":false,\"reachable\":false,\"detail\":\"未配置\"},"
            + "\"probe_headers\":{\"configured\":false,\"vars\":[]}}}";

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
        assertEquals(12, r.steps().size(), r.steps().toString());
        assertTrue(r.steps().stream().allMatch(EvaluationDiagnosticsService.Step::ok),
                r.steps().toString());
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
    void licenseStepSendsBearerToken() throws Exception {
        String base = startStub(200, 200);
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertTrue(r.ok(), () -> r.steps().toString());
        // BUG-2 回归网：评估 /api/v1/license 要求 Bearer（无头→401），诊断必须带 Authorization，
        // 否则真实环境「测试连接」对启用了鉴权的评估服务恒误报 401（false negative）。
        assertEquals("Bearer tok", lastLicenseAuth.get(),
                "license 步请求必须带 Authorization 头（不带会被评估服务拒为 401）");
    }

    @Test
    void capabilitiesStepSendsBearerToken() throws Exception {
        String base = startStub(200, 200);
        svc.run(cfg(base, "tok"));
        assertEquals("Bearer tok", lastCapabilitiesAuth.get(),
                "capabilities 步必须带 Authorization（与其它 /api/v1/* 一致）");
    }

    @Test
    void oldVeWithoutCapabilitiesDegrades() throws Exception {
        String base = startStub(200, 200, "trial", true, null, "[]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertTrue(r.ok(), r.steps().toString());
        var cap = r.steps().stream().filter(s -> s.step().equals("capabilities"))
                .findFirst().orElseThrow();
        assertTrue(cap.ok());
        assertTrue(cap.detail().contains("版本过旧"));
        assertFalse(r.steps().stream().anyMatch(s -> s.step().equals("probe_remote")),
                "老版本没有能力位可查，跳过而不是误报");
    }

    @Test
    void probeRemoteDisabledFailsTestStage() throws Exception {
        String caps = CAPS_OK.replace("\"allow_remote\":true", "\"allow_remote\":false");
        String base = startStub(200, 200, "trial", true, caps, "[]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertFalse(r.ok());
        var probe = r.steps().stream().filter(s -> s.step().equals("probe_remote"))
                .findFirst().orElseThrow();
        assertFalse(probe.ok());
        assertTrue(probe.detail().contains("allow_remote=false"));
        assertTrue(probe.detail().contains("403"));
    }

    @Test
    void licenseWithoutProbeFeatureFlagsProbeStep() throws Exception {
        String base = startStub(200, 200, "valid", true, CAPS_OK, "[\"skill\"]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertFalse(r.ok());
        var probe = r.steps().stream().filter(s -> s.step().equals("probe_remote"))
                .findFirst().orElseThrow();
        assertFalse(probe.ok());
        assertTrue(probe.detail().contains("license probe=false"));
        var feats = r.steps().stream().filter(s -> s.step().equals("license_features"))
                .findFirst().orElseThrow();
        assertTrue(feats.ok());
        assertEquals("skill", feats.detail());
    }

    @Test
    void emptyFeaturesMeansUnrestricted() throws Exception {
        String base = startStub(200, 200, "trial", true, CAPS_OK, "[]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        var feats = r.steps().stream().filter(s -> s.step().equals("license_features"))
                .findFirst().orElseThrow();
        assertTrue(feats.detail().contains("未限制"));
        assertTrue(r.ok(), r.steps().toString());
    }

    @Test
    void judgeLlmUnreachableFailsTestStage() throws Exception {
        String caps = CAPS_OK.replace(
                "\"judge_llm\":{\"configured\":false,\"reachable\":false,\"detail\":\"未配置\"}",
                "\"judge_llm\":{\"configured\":true,\"reachable\":false,"
                        + "\"detail\":\"TCP 不可达: ConnectException\"}");
        String base = startStub(200, 200, "trial", true, caps, "[]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertFalse(r.ok());
        var judge = r.steps().stream().filter(s -> s.step().equals("judge_llm"))
                .findFirst().orElseThrow();
        assertFalse(judge.ok());
        assertTrue(judge.detail().contains("不可达"));
    }

    @Test
    void scanRootUnusableFailsTestStage() throws Exception {
        String caps = CAPS_OK.replace("\"usable\":true", "\"usable\":false");
        String base = startStub(200, 200, "trial", true, caps, "[]");
        EvaluationDiagnosticsService.Result r = svc.run(cfg(base, "tok"));
        assertFalse(r.ok());
        var root = r.steps().stream().filter(s -> s.step().equals("scan_root"))
                .findFirst().orElseThrow();
        assertFalse(root.ok());
        assertTrue(root.detail().contains("usable=false"));
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

    private final java.util.concurrent.atomic.AtomicReference<String> lastLicenseAuth =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<String> lastCapabilitiesAuth =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static EvaluationSettingsService.Settings cfg(String base, String token) {
        return new EvaluationSettingsService.Settings(true, base, token, true, "");
    }

    private static void assertEqualsStep(EvaluationDiagnosticsService.Result r, String step) {
        assertTrue(r.steps().get(r.steps().size() - 1).step().equals(step), r.steps().toString());
    }

    private String startStub(int healthStatus, int licenseStatus) throws Exception {
        return startStub(healthStatus, licenseStatus, "trial", true, CAPS_OK, "[]");
    }

    private String startStub(int healthStatus, int licenseStatus, String mode, boolean active)
            throws Exception {
        return startStub(healthStatus, licenseStatus, mode, active, CAPS_OK, "[]");
    }

    private String startStub(int healthStatus, int licenseStatus, String mode, boolean active,
                             String capsJson, String featuresJson) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/health", ex -> respond(ex, healthStatus,
                "{\"code\":0,\"data\":{\"status\":\"UP\"}}"));
        server.createContext("/api/v1/license", ex -> {
            // 仿真评估服务行为：/license 要求 Bearer 头（无头 → 401，与真实端点一致）
            lastLicenseAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            if (licenseStatus != 200 || lastLicenseAuth.get() == null) {
                respond(ex, licenseStatus != 200 ? licenseStatus : 401,
                        "{\"code\":401,\"message\":\"unauthorized\"}");
                return;
            }
            respond(ex, 200, "{\"code\":0,\"data\":{\"mode\":\"" + mode + "\",\"active\":"
                    + active + ",\"days_left\":30,\"detail\":\"\",\"features\":"
                    + featuresJson + "}}");
        });
        if (capsJson != null) {
            server.createContext("/api/v1/capabilities", ex -> {
                lastCapabilitiesAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
                respond(ex, 200, capsJson);
            });
        }
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
