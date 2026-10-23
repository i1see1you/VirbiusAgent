package io.virbius.control.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import io.virbius.control.security.ApiKeyAuthContext;
import io.virbius.control.security.ApiRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制面「评估」页代理（商业扩展的可选集成；开源仓不含评估服务本体）。
 *
 * <p>权限沿用 ApiKeyRoutePolicy：GET → TENANT_VIEWER，POST → TENANT_ADMIN。
 * 配置来自 {@link EvaluationSettingsService}（DB 单源、热生效），本类不再读 env。
 * 业务响应（含 400/403/404/502）逐字节透传；本代理自身错误用稳定业务码：
 * 5031=未配置、5032=不可达、5033=集成已关闭——前端据此显示引导页/诊断而非裸报错。
 */
@RestController
@RequestMapping("/api/v1/admin/tenants/{tenantId}/evaluation")
public class EvaluationProxyController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationProxyController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final int CODE_NOT_CONFIGURED = 5031;
    public static final int CODE_UNREACHABLE = 5032;
    public static final int CODE_DISABLED = 5033;

    public record LicenseInfo(String mode, String edition, List<String> features,
                              String expiresAt, Long daysLeft, Boolean active,
                              Boolean clockFrozen, String instanceId, String detail) {
    }

    public record Status(boolean enabled, boolean configured, boolean reachable,
                         LicenseInfo license, boolean promoEnabled, String promoUrl) {
    }

    private record CacheEntry(Instant at, String fingerprint, Status status) {
    }

    private final EvaluationSettingsService settings;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    /** 转发超时（> evaluation 同步截止 90s；jobs 提交立即返回，不受影响）。 */
    @Value("${virbius.evaluation.forward-timeout-seconds:150}")
    private long forwardTimeoutSeconds = 150;
    private final long statusTtlSeconds = 45;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    public EvaluationProxyController(EvaluationSettingsService settings) {
        this.settings = settings;
    }

    /** 三态聚合（45s 缓存；配置变更自动失效；?refresh=true 强制刷新）。 */
    @GetMapping("/status")
    public Status status(@PathVariable String tenantId,
                         @RequestParam(value = "refresh", required = false) Boolean refresh) {
        EvaluationSettingsService.Settings cfg = settings.get();
        String fp = fingerprint(cfg);
        CacheEntry c = cache.get();
        if (!Boolean.TRUE.equals(refresh) && c != null && fp.equals(c.fingerprint())
                && c.at().plusSeconds(statusTtlSeconds).isAfter(Instant.now())) {
            return c.status();
        }
        Status s = probeStatus(cfg);
        cache.set(new CacheEntry(Instant.now(), fp, s));
        return s;
    }

    @GetMapping("/reports")
    public ResponseEntity<String> listReports(@PathVariable String tenantId,
            @RequestParam(value = "target_fingerprint", required = false) String targetFingerprint,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset,
            @RequestParam(value = "agent_name", required = false) String agentName,
            @RequestParam(value = "grade", required = false) String grade,
            @RequestParam(value = "target_type", required = false) String targetType) {
        StringBuilder path = new StringBuilder("/api/v1/evaluations/reports");
        List<String> qs = new ArrayList<>();
        if (targetFingerprint != null && !targetFingerprint.isBlank()) {
            qs.add("target_fingerprint=" + targetFingerprint);
        }
        if (limit != null) {
            qs.add("limit=" + limit);
        }
        if (offset != null) {
            qs.add("offset=" + offset);
        }
        if (agentName != null && !agentName.isBlank()) {
            qs.add("agent_name=" + agentName);
        }
        if (grade != null && !grade.isBlank()) {
            qs.add("grade=" + grade);
        }
        if (targetType != null && !targetType.isBlank()) {
            qs.add("target_type=" + targetType);
        }
        if (!qs.isEmpty()) {
            path.append('?').append(String.join("&", qs));
        }
        return forward(tenantId, HttpRequest.newBuilder().GET(), path.toString());
    }

    @GetMapping("/reports/{reportId}")
    public ResponseEntity<String> getReport(@PathVariable String tenantId,
            @PathVariable String reportId) {
        return forward(tenantId, HttpRequest.newBuilder().GET(),
                "/api/v1/evaluations/reports/" + reportId);
    }

    @GetMapping("/reports/{reportId}/html")
    public ResponseEntity<String> reportHtml(@PathVariable String tenantId,
            @PathVariable String reportId) {
        return forward(tenantId, HttpRequest.newBuilder().GET(),
                "/api/v1/evaluations/reports/" + reportId + "/html");
    }

    @GetMapping("/reports/{reportId}/sarif")
    public ResponseEntity<String> reportSarif(@PathVariable String tenantId,
            @PathVariable String reportId) {
        return forward(tenantId, HttpRequest.newBuilder().GET(),
                "/api/v1/evaluations/reports/" + reportId + "/sarif");
    }

    @PostMapping("/scans")
    public ResponseEntity<String> scan(@PathVariable String tenantId, @RequestBody String body) {
        return forward(tenantId, post(body), "/api/v1/evaluations/scans");
    }

    @PostMapping("/probes")
    public ResponseEntity<String> probe(@PathVariable String tenantId, @RequestBody String body) {
        if (!probeAllowed(tenantId)) {
            return error(HttpStatus.FORBIDDEN, 40305,
                    "该租户未启用探针（平台设置 → 评估 → 探针租户白名单）");
        }
        return forward(tenantId, post(body), "/api/v1/evaluations/probes");
    }

    @PostMapping("/replays")
    public ResponseEntity<String> replay(@PathVariable String tenantId, @RequestBody String body) {
        return forward(tenantId, post(body), "/api/v1/evaluations/replays");
    }

    @PostMapping("/replays/import")
    public ResponseEntity<String> replayImport(@PathVariable String tenantId,
            @RequestBody String body) {
        return forward(tenantId, post(body), "/api/v1/evaluations/replays/import");
    }

    @PostMapping("/skills")
    public ResponseEntity<String> skill(@PathVariable String tenantId, @RequestBody String body) {
        return forward(tenantId, post(body), "/api/v1/evaluations/skills");
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<String> job(@PathVariable String tenantId, @PathVariable String jobId) {
        return forward(tenantId, HttpRequest.newBuilder().GET(),
                "/api/v1/evaluations/jobs/" + jobId);
    }

    @GetMapping("/jobs")
    public ResponseEntity<String> jobs(@PathVariable String tenantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", required = false) Integer offset) {
        StringBuilder path = new StringBuilder("/api/v1/evaluations/jobs");
        List<String> qs = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            qs.add("status=" + status);
        }
        if (limit != null) {
            qs.add("limit=" + limit);
        }
        if (offset != null) {
            qs.add("offset=" + offset);
        }
        if (!qs.isEmpty()) {
            path.append('?').append(String.join("&", qs));
        }
        return forward(tenantId, HttpRequest.newBuilder().GET(), path.toString());
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<String> cancelJob(@PathVariable String tenantId,
            @PathVariable String jobId) {
        return forward(tenantId, post("{}"), "/api/v1/evaluations/jobs/" + jobId + "/cancel");
    }

    /** 探针租户闸：白名单含 * 或该租户，或调用者为平台管理员。 */
    private boolean probeAllowed(String tenantId) {
        Set<String> allowed = settings.get().probeTenantSet();
        if (allowed.contains("*") || allowed.contains(
                tenantId == null ? "default" : tenantId.strip().toLowerCase())) {
            return true;
        }
        var principal = ApiKeyAuthContext.current();
        return principal != null && principal.role() == ApiRole.PLATFORM_ADMIN;
    }

    // ---------- 内部 ----------

    private Status probeStatus(EvaluationSettingsService.Settings cfg) {
        if (!cfg.enabled()) {
            return new Status(false, false, false, null, cfg.promoEnabled(), cfg.promoUrl());
        }
        if (!cfg.configured()) {
            return new Status(true, false, false, null, cfg.promoEnabled(), cfg.promoUrl());
        }
        try {
            HttpResponse<String> health = http.send(
                    request(cfg, "/api/v1/health").timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200) {
                return new Status(true, true, false, null, cfg.promoEnabled(), cfg.promoUrl());
            }
            return new Status(true, true, true, fetchLicense(cfg), cfg.promoEnabled(), cfg.promoUrl());
        } catch (Exception e) {
            log.debug("evaluation status probe failed: {}", e.toString());
            return new Status(true, true, false, null, cfg.promoEnabled(), cfg.promoUrl());
        }
    }

    private LicenseInfo fetchLicense(EvaluationSettingsService.Settings cfg) {
        if (cfg.apiToken().isBlank()) {
            return licenseUnknown("未配置 API token，无法读取许可状态");
        }
        try {
            HttpResponse<String> r = http.send(
                    request(cfg, "/api/v1/license").timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) {
                return licenseUnknown("HTTP 401：API token 不匹配");
            }
            if (r.statusCode() == 404) {
                return licenseUnknown("评估服务未提供 /license（版本过旧）");
            }
            if (r.statusCode() != 200) {
                return licenseUnknown("许可状态读取失败: HTTP " + r.statusCode());
            }
            JsonNode data = JSON.readTree(r.body()).path("data");
            List<String> features = new ArrayList<>();
            if (data.path("features").isArray()) {
                data.get("features").forEach(n -> features.add(n.asText()));
            }
            return new LicenseInfo(text(data, "mode"), text(data, "edition"), features,
                    text(data, "expires_at"),
                    data.hasNonNull("days_left") ? data.get("days_left").asLong() : null,
                    data.hasNonNull("active") ? data.get("active").asBoolean() : null,
                    data.hasNonNull("clock_frozen") ? data.get("clock_frozen").asBoolean() : null,
                    text(data, "instance_id"), text(data, "detail"));
        } catch (Exception e) {
            return licenseUnknown("许可状态读取失败: " + e.getMessage());
        }
    }

    private static LicenseInfo licenseUnknown(String detail) {
        return new LicenseInfo("unknown", null, List.of(), null, null, null, null, null, detail);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String fingerprint(EvaluationSettingsService.Settings cfg) {
        return cfg.enabled() + "|" + cfg.baseUrl() + "|" + cfg.apiToken().hashCode()
                + "|" + cfg.promoEnabled() + "|" + cfg.promoUrl();
    }

    private HttpRequest.Builder post(String body) {
        return HttpRequest.newBuilder()
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
    }

    private HttpRequest.Builder request(EvaluationSettingsService.Settings cfg, String path) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + path));
        if (!cfg.apiToken().isBlank()) {
            b.header("Authorization", "Bearer " + cfg.apiToken());
        }
        return b;
    }

    private ResponseEntity<String> forward(String tenantId, HttpRequest.Builder builder, String path) {
        EvaluationSettingsService.Settings cfg = settings.get();
        if (!cfg.enabled()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, CODE_DISABLED,
                    "评估集成已关闭（平台设置 → 评估 → 启用）");
        }
        if (!cfg.configured()) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, CODE_NOT_CONFIGURED,
                    "评估服务未配置：请平台管理员在「评估 → 设置」填写服务地址与 token");
        }
        try {
            HttpRequest.Builder rb = builder
                    .uri(URI.create(cfg.baseUrl().replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(forwardTimeoutSeconds))
                    .header("X-VE-Tenant", tenantId == null || tenantId.isBlank()
                            ? "default" : tenantId.strip().toLowerCase());
            if (!cfg.apiToken().isBlank()) {
                rb.header("Authorization", "Bearer " + cfg.apiToken());
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            MediaType contentType = resp.headers().firstValue("content-type")
                    .map(ct -> {
                        try {
                            return MediaType.parseMediaType(ct);
                        } catch (Exception e) {
                            return MediaType.APPLICATION_JSON;
                        }
                    }).orElse(MediaType.APPLICATION_JSON);
            return ResponseEntity.status(resp.statusCode()).contentType(contentType).body(resp.body());
        } catch (Exception e) {
            cache.set(null);
            log.warn("evaluation proxy failed path={} err={}", path, e.toString());
            return error(HttpStatus.BAD_GATEWAY, CODE_UNREACHABLE,
                    "评估服务不可达: " + e.getMessage());
        }
    }

    /** 处置链路复用：GET 转发（同包 RemediationService 取报告用；错误码语义同上）。 */
    ResponseEntity<String> forwardGet(String tenantId, String path) {
        return forward(tenantId, HttpRequest.newBuilder().GET(), path);
    }

    private static String jsonQuote(String s) {
        try {
            return JSON.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private static ResponseEntity<String> error(HttpStatus status, int code, String message) {
        String body = "{\"code\":" + code + ",\"message\":" + jsonQuote(message)
                + ",\"data\":null}";
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
